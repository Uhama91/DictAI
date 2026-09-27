package com.kafkasl.phonewhisper.meeting

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.TranscribeCppNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the additive Handy and standalone diarization ports on the same public PCM fixture. */
@RunWith(AndroidJUnit4::class)
class MeetingStreamingPortsInstrumentedTest {
    @Test
    fun handy_and_standalone_diarization_stream_together_without_losing_dictation_text() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val models = File("/data/local/tmp/dictai-conversation-20260927")
        val handyModel = models.resolve(HANDY_MODEL_NAME)
        val diarModel = models.resolve(DIAR_MODEL_NAME)
        assertArtifact(handyModel, HANDY_MODEL_BYTES, HANDY_MODEL_SHA256)
        assertArtifact(diarModel, DIAR_MODEL_BYTES, DIAR_MODEL_SHA256)

        val wav = instrumentation.context.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        assertEquals(FIXTURE_SHA256, sha256(wav))
        val pcm = extractMono16KhzPcm16(wav)
        val audioMs = pcm.size * 1000L / PCM_BYTES_PER_SECOND
        assertEquals(12_780L, audioMs)
        assertTrue(pcm.isNotEmpty() && pcm.size % 2 == 0)

        assertTrue("native integration probe must run on the ARM64 test AVD", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val handyOnlyBefore = runHandyOnly(handyModel, pcm, audioMs, pass = "handy-only-before")
        val combined = runHandyWithDiarization(handyModel, diarModel, pcm, audioMs)
        val handyOnlyAfter = runHandyOnly(handyModel, pcm, audioMs, pass = "handy-only-after")

        assertEquals(EXPECTED_TRANSCRIPT, handyOnlyBefore.transcript)
        assertEquals("standalone diarization must not alter the authoritative Handy transcript",
            handyOnlyBefore.transcript, combined.transcript)
        assertEquals("a repeated Handy-only pass must remain stable after the combined pass",
            handyOnlyBefore.transcript, handyOnlyAfter.transcript)
        assertTrue(combined.diarFrames > 0)
        assertTrue(combined.stableFrames in 1..combined.diarFrames)

        verifyAssemblerReducerAttribution(combined, audioMs)
        verifyLegacyDictationApiStillWorks(handyModel, pcm)
    }

    @Test
    fun standalone_diarization_timeline_covers_every_fed_pcm_sample() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val models = File("/data/local/tmp/dictai-conversation-20260927")
        val handyModel = models.resolve(HANDY_MODEL_NAME)
        val diarModel = models.resolve(DIAR_MODEL_NAME)
        assertArtifact(handyModel, HANDY_MODEL_BYTES, HANDY_MODEL_SHA256)
        assertArtifact(diarModel, DIAR_MODEL_BYTES, DIAR_MODEL_SHA256)

        val wav = instrumentation.context.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        assertEquals(FIXTURE_SHA256, sha256(wav))
        val pcm = extractMono16KhzPcm16(wav)
        val audioMs = pcm.size * 1000L / PCM_BYTES_PER_SECOND
        val result = runHandyWithDiarization(handyModel, diarModel, pcm, audioMs)
        assertTrue(result.diarFrames > 0L)
    }

    @Test
    fun compares_handy_and_diarization_on_sustained_sixty_four_second_stream() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val models = File("/data/local/tmp/dictai-conversation-20260927")
        val handyModel = models.resolve(HANDY_MODEL_NAME)
        val diarModel = models.resolve(DIAR_MODEL_NAME)
        assertArtifact(handyModel, HANDY_MODEL_BYTES, HANDY_MODEL_SHA256)
        assertArtifact(diarModel, DIAR_MODEL_BYTES, DIAR_MODEL_SHA256)

        val wav = instrumentation.context.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        assertEquals(FIXTURE_SHA256, sha256(wav))
        val onePassPcm = extractMono16KhzPcm16(wav)
        val pcm = ByteArray(onePassPcm.size * SUSTAINED_REPEAT_COUNT)
        repeat(SUSTAINED_REPEAT_COUNT) { repeatIndex ->
            onePassPcm.copyInto(pcm, destinationOffset = repeatIndex * onePassPcm.size)
        }
        val audioMs = pcm.size * 1000L / PCM_BYTES_PER_SECOND
        assertTrue("PCM duration includes integer-millisecond rounding", audioMs in 63_900L..63_901L)

        val handyOnly = runHandyOnly(
            model = handyModel,
            pcm = pcm,
            audioMs = audioMs,
            pass = "sustained-handy-only",
            expectedTranscript = null,
            progressTag = "sustained-handy-only",
        )
        val combined = runHandyWithDiarization(
            handyModel = handyModel,
            diarModel = diarModel,
            pcm = pcm,
            audioMs = audioMs,
            expectedTranscript = null,
            progressTag = "sustained-handy-plus-diar",
        )
        assertEquals("diarization must preserve the full Handy text after sustained input",
            handyOnly.transcript, combined.transcript)
        assertTrue(combined.diarFrames > 0L)
        assertTrue(combined.stableFrames in 1L..combined.diarFrames)
        Log.i(LOG_TAG, "phase=sustained-summary audioMs=$audioMs handyTranscriptChars=${handyOnly.transcript.length} " +
            "combinedTranscriptChars=${combined.transcript.length} diarFrames=${combined.diarFrames} " +
            "stableFrames=${combined.stableFrames} exactTextMatch=true")
    }

    private fun runHandyOnly(
        model: File,
        pcm: ByteArray,
        audioMs: Long,
        pass: String,
        expectedTranscript: String? = EXPECTED_TRANSCRIPT,
        progressTag: String? = null,
    ): StreamResult {
        val openStart = SystemClock.elapsedRealtime()
        val handy = HandyMeetingNative.open(model.absolutePath, "fr-FR")
        val openMs = SystemClock.elapsedRealtime() - openStart
        val pssAfterOpenKb = Debug.getPss()
        try {
            val feed = feedPacedPcm(
                pcm = pcm,
                accept = { chunk -> handy.acceptPcm16(chunk, chunk.size) },
                snapshot = {
                    handy.snapshot(firstTokenIndex = 0, maxTokens = SNAPSHOT_TOKENS)
                        .fullTextUtf8.toString(StandardCharsets.UTF_8)
                },
                onProgress = progressTag?.let { tag ->
                    { audioBytes, elapsedMs ->
                        Log.i(LOG_TAG, "phase=$tag progressAudioMs=${audioBytes * 1000L / PCM_BYTES_PER_SECOND} " +
                            "elapsedMs=$elapsedMs")
                    }
                },
            )
            assertTrue("live Handy snapshots must publish text before finish", feed.firstTextMs >= 0L)
            val finalWindow = handy.finish(firstTokenIndex = 0, maxTokens = SNAPSHOT_TOKENS)
            val handyFinalMs = elapsedMsSince(feed.startedAtNanos)
            val transcript = finalWindow.fullTextUtf8.toString(StandardCharsets.UTF_8)
            val tokenText = finalWindow.tokenBytes.toString(StandardCharsets.UTF_8).trim(' ')
            if (expectedTranscript != null) assertEquals(expectedTranscript, transcript)
            assertEquals(
                "full text remains authoritative while raw pieces stay byte-addressable",
                normalizeAsciiSpaces(transcript),
                normalizeAsciiSpaces(tokenText),
            )
            assertTrue(finalWindow.totalTokenCount > 0)
            assertEquals(finalWindow.totalTokenCount.coerceAtMost(SNAPSHOT_TOKENS), finalWindow.tokenStartsMs.size)
            assertEquals(finalWindow.tokenStartsMs.size, finalWindow.tokenEndsMs.size)
            val validTimedTokens = finalWindow.tokenStartsMs.zip(finalWindow.tokenEndsMs).count { (start, end) ->
                start >= 0L && end > start && end <= audioMs + 500L
            }
            assertTrue("token snapshot must contain timestamped pieces", validTimedTokens > 0)
            val prefix = handy.snapshot(firstTokenIndex = 0, maxTokens = 2)
            assertEquals(transcript, prefix.fullTextUtf8.toString(StandardCharsets.UTF_8))
            assertEquals(0, prefix.firstTokenIndex)
            assertTrue(prefix.tokenStartsMs.size <= 2)
            val tailFirst = (finalWindow.totalTokenCount - 2).coerceAtLeast(0)
            val tail = handy.snapshot(firstTokenIndex = tailFirst, maxTokens = 2)
            assertEquals(tailFirst, tail.firstTokenIndex)
            assertTrue(tail.tokenStartsMs.size <= 2)
            val firstTextMs = feed.firstTextMs
            assertTrue("the streamed PCM must produce Handy text", transcript.isNotEmpty() && firstTextMs >= 0L)
            val pssKb = Debug.getPss()
            val closeStart = SystemClock.elapsedRealtime()
            handy.close()
            val closeMs = SystemClock.elapsedRealtime() - closeStart
            val pssAfterCloseKb = Debug.getPss()
            Log.i(LOG_TAG, "phase=$pass openMs=$openMs firstTextMs=$firstTextMs " +
                "feedCallMs=${feed.feedCallNanos / 1_000_000L} " +
                "snapshotCallMs=${feed.snapshotCallNanos / 1_000_000L} snapshotCount=${feed.snapshotCount} " +
                "maxAsrLagMs=${feed.maxAsrLagMs} sourceFeedWallMs=${feed.sourceFeedWallMs} " +
                "handyFinalMs=$handyFinalMs audioMs=$audioMs " +
                "closeMs=$closeMs pssAfterOpenKb=$pssAfterOpenKb pssAfterStreamKb=$pssKb " +
                "pssAfterCloseKb=$pssAfterCloseKb tokenCount=${finalWindow.totalTokenCount} " +
                "committedTokens=${finalWindow.committedTokenCount} validTimedTokens=$validTimedTokens " +
                "transcript=\"$transcript\"")
            return StreamResult(
                transcript = transcript,
                diarFrames = -1L,
                stableFrames = -1L,
                handyWindow = finalWindow,
            )
        } finally {
            handy.close()
        }
    }

    private fun runHandyWithDiarization(
        handyModel: File,
        diarModel: File,
        pcm: ByteArray,
        audioMs: Long,
        expectedTranscript: String? = EXPECTED_TRANSCRIPT,
        progressTag: String? = null,
    ): StreamResult {
        val handyOpenStart = SystemClock.elapsedRealtime()
        val handy = HandyMeetingNative.open(handyModel.absolutePath, "fr-FR")
        val handyOpenMs = SystemClock.elapsedRealtime() - handyOpenStart
        val pssAfterHandyOpenKb = Debug.getPss()
        val diarFeedNanos = AtomicLong(0L)
        val submittedBytes = AtomicLong(0L)
        val diarProcessedBytes = AtomicLong(0L)
        val maxLagBytes = AtomicLong(0L)
        val maxQueueDepth = AtomicLong(0L)
        val workerFailure = AtomicReference<Throwable?>(null)
        val workerResult = AtomicReference<DiarWorkerResult?>(null)
        val workerOpenMs = AtomicLong(-1L)
        val workerPssAfterOpenKb = AtomicLong(-1L)
        val workerPssBeforeCloseKb = AtomicLong(-1L)
        val workerCloseMs = AtomicLong(-1L)
        val queue = ArrayBlockingQueue<ByteArray>(DIAR_QUEUE_CAPACITY)
        val poison = ByteArray(0)
        val worker = Thread {
            var diar: DiarizationNative? = null
            var localFailure: Throwable? = null
            var frames: DiarizationFrameWindow? = null
            var tailFrames: DiarizationFrameWindow? = null
            var endFrames: DiarizationFrameWindow? = null
            var finishMs = -1L
            var processedBytes = 0L
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val openStart = SystemClock.elapsedRealtime()
                val activeDiar = DiarizationNative.open(diarModel.absolutePath)
                diar = activeDiar
                workerOpenMs.set(SystemClock.elapsedRealtime() - openStart)
                workerPssAfterOpenKb.set(Debug.getPss().toLong())
                while (true) {
                    val chunk = queue.take()
                    if (chunk === poison) break
                    val start = SystemClock.elapsedRealtimeNanos()
                    activeDiar.acceptPcm16(chunk, chunk.size)
                    diarFeedNanos.addAndGet(SystemClock.elapsedRealtimeNanos() - start)
                    processedBytes += chunk.size
                    diarProcessedBytes.set(processedBytes)
                }
                val finishStart = SystemClock.elapsedRealtime()
                activeDiar.finish()
                frames = activeDiar.snapshot(firstFrameIndex = 0L)
                val totalFrames = checkNotNull(frames).totalFrameCount
                tailFrames = activeDiar.snapshot(
                    firstFrameIndex = (totalFrames - 4).coerceAtLeast(0L),
                    maxFrames = 4,
                )
                endFrames = activeDiar.snapshot(firstFrameIndex = totalFrames, maxFrames = 4)
                finishMs = SystemClock.elapsedRealtime() - finishStart
                workerPssBeforeCloseKb.set(Debug.getPss().toLong())
            } catch (failure: Throwable) {
                localFailure = failure
            } finally {
                val openedDiar = diar
                if (openedDiar != null) {
                    val closeStart = SystemClock.elapsedRealtime()
                    try {
                        openedDiar.close()
                    } catch (closeFailure: Throwable) {
                        if (localFailure == null) localFailure = closeFailure
                        else localFailure?.addSuppressed(closeFailure)
                    }
                    workerCloseMs.set(SystemClock.elapsedRealtime() - closeStart)
                }
                workerResult.set(
                    DiarWorkerResult(
                        frames = frames,
                        tailFrames = tailFrames,
                        endFrames = endFrames,
                        processedBytes = processedBytes,
                        finishMs = finishMs,
                    ),
                )
                workerFailure.set(localFailure)
            }
        }.apply {
            name = "meeting-diarization-background-test"
            start()
        }
        var diarDrainMs = -1L
        try {
            val feed = feedPacedPcm(
                pcm = pcm,
                accept = { chunk -> handy.acceptPcm16(chunk, chunk.size) },
                snapshot = {
                    handy.snapshot(firstTokenIndex = 0, maxTokens = SNAPSHOT_TOKENS)
                        .fullTextUtf8.toString(StandardCharsets.UTF_8)
                },
                afterSnapshot = { chunk, offsetAfterChunk ->
                    workerFailure.get()?.let { throw AssertionError("Diarization worker failed", it) }
                    submittedBytes.set(offsetAfterChunk.toLong())
                    check(queue.offer(chunk)) {
                        "Diarization queue saturated at ${queue.size}/$DIAR_QUEUE_CAPACITY chunks; refusing to drop audio"
                    }
                    maxQueueDepth.updateAndGet { old -> maxOf(old, queue.size.toLong()) }
                    maxLagBytes.updateAndGet { old ->
                        maxOf(old, (submittedBytes.get() - diarProcessedBytes.get()).coerceAtLeast(0L))
                    }
                },
                onProgress = progressTag?.let { tag ->
                    { audioBytes, elapsedMs ->
                        val backlog = (submittedBytes.get() - diarProcessedBytes.get()).coerceAtLeast(0L)
                        Log.i(LOG_TAG, "phase=$tag progressAudioMs=${audioBytes * 1000L / PCM_BYTES_PER_SECOND} " +
                            "elapsedMs=$elapsedMs diarProcessedBytes=${diarProcessedBytes.get()} " +
                            "backlogMs=${backlog * 1000L / PCM_BYTES_PER_SECOND} " +
                            "queueDepth=${queue.size} maxQueueDepth=${maxQueueDepth.get()}")
                    }
                },
            )
            assertTrue("live Handy snapshots must publish text before finish", feed.firstTextMs >= 0L)
            val finalWindow = handy.finish(firstTokenIndex = 0, maxTokens = SNAPSHOT_TOKENS)
            val handyFinalMs = elapsedMsSince(feed.startedAtNanos)
            val transcript = finalWindow.fullTextUtf8.toString(StandardCharsets.UTF_8)
            if (expectedTranscript != null) assertEquals(expectedTranscript, transcript)
            assertTrue("the streamed PCM must produce Handy text", transcript.isNotEmpty())
            val poisonQueuedAtNanos = SystemClock.elapsedRealtimeNanos()
            check(queue.offer(poison)) {
                "Could not enqueue diarization drain marker: queue is saturated at ${queue.size}/$DIAR_QUEUE_CAPACITY"
            }
            worker.join(DIAR_WORKER_JOIN_MS)
            assertTrue("background diarization worker must drain the bounded queue", !worker.isAlive)
            workerFailure.get()?.let { throw AssertionError("Diarization worker failed", it) }
            diarDrainMs = (SystemClock.elapsedRealtimeNanos() - poisonQueuedAtNanos) / 1_000_000L
            val result = checkNotNull(workerResult.get()) { "Diarization worker did not return a result" }
            assertEquals("diarization worker must consume every submitted PCM byte", pcm.size.toLong(), result.processedBytes)
            assertEquals(result.processedBytes, submittedBytes.get())
            val finalFrames = checkNotNull(result.frames) { "Diarization worker did not return final frames" }
            assertEquals(0.01, finalFrames.secondsPerFrame, 0.0001)
            assertEquals(finalFrames.totalFrameCount, finalFrames.stableFrameCount)
            assertTrue(finalFrames.totalFrameCount > 0L)
            assertEquals(finalFrames.totalFrameCount.toInt() * finalFrames.speakerCount,
                finalFrames.probabilities.size)
            val pcmDurationSeconds = pcm.size.toDouble() / PCM_BYTES_PER_SECOND
            val diarizedDurationSeconds = finalFrames.totalFrameCount * finalFrames.secondsPerFrame
            Log.i(LOG_TAG, "phase=diar-coverage inputPcmBytes=${pcm.size} " +
                "processedPcmBytes=${result.processedBytes} frameCount=${finalFrames.totalFrameCount} " +
                "secondsPerFrame=${finalFrames.secondsPerFrame} diarizedDurationSeconds=$diarizedDurationSeconds " +
                "inputDurationSeconds=$pcmDurationSeconds speakerCount=${finalFrames.speakerCount} " +
                "probabilityCount=${finalFrames.probabilities.size}")
            assertTrue(
                "diarization frame timeline must cover all PCM samples within two frame periods " +
                    "(input=$pcmDurationSeconds s, frames=$diarizedDurationSeconds s)",
                abs(diarizedDurationSeconds - pcmDurationSeconds) <= finalFrames.secondsPerFrame * 2.0,
            )
            val tail = checkNotNull(result.tailFrames)
            assertEquals(finalFrames.totalFrameCount, tail.firstFrameIndex + tail.probabilities.size / tail.speakerCount)
            val end = checkNotNull(result.endFrames)
            assertEquals(finalFrames.totalFrameCount, end.firstFrameIndex)
            assertTrue(end.probabilities.isEmpty())
            val firstTextMs = feed.firstTextMs
            val pssKb = Debug.getPss()
            val closeStart = SystemClock.elapsedRealtime()
            handy.close()
            val closeMs = SystemClock.elapsedRealtime() - closeStart
            val pssAfterCloseKb = Debug.getPss()
            Log.i(LOG_TAG, "phase=handy-plus-diar handyOpenMs=$handyOpenMs diarOpenMs=${workerOpenMs.get()} " +
                "firstTextMs=$firstTextMs handyFeedCallMs=${feed.feedCallNanos / 1_000_000L} " +
                "snapshotCallMs=${feed.snapshotCallNanos / 1_000_000L} snapshotCount=${feed.snapshotCount} " +
                "maxAsrLagMs=${feed.maxAsrLagMs} sourceFeedWallMs=${feed.sourceFeedWallMs} " +
                "handyFinalMs=$handyFinalMs diarFinishMs=${result.finishMs} diarDrainMs=$diarDrainMs " +
                "diarFeedCallMs=${diarFeedNanos.get() / 1_000_000L} diarCloseMs=${workerCloseMs.get()} " +
                "handyCloseMs=$closeMs pssAfterCloseKb=$pssAfterCloseKb " +
                "diarQueuedBytes=${diarProcessedBytes.get()} maxQueueDepth=${maxQueueDepth.get()} " +
                "maxDiarBacklogMs=${maxLagBytes.get() * 1000L / PCM_BYTES_PER_SECOND} " +
                "pssHandyOpenKb=$pssAfterHandyOpenKb pssAfterDiarOpenWithHandyKb=${workerPssAfterOpenKb.get()} " +
                "pssBeforeDiarCloseWithHandyKb=${workerPssBeforeCloseKb.get()} pssAfterStreamKb=$pssKb " +
                "diarFrames=${finalFrames.totalFrameCount} " +
                "stableFrames=${finalFrames.stableFrameCount} speakers=${finalFrames.speakerCount} " +
                "transcript=\"$transcript\"")
            return StreamResult(
                transcript = transcript,
                diarFrames = finalFrames.totalFrameCount,
                stableFrames = finalFrames.stableFrameCount,
                handyWindow = finalWindow,
                diarizationWindow = finalFrames,
            )
        } finally {
            if (worker.isAlive) {
                // Dropping queued PCM is cleanup only after a failed measurement, never a successful pass.
                queue.clear()
                queue.offer(poison)
                worker.interrupt()
                worker.join(WORKER_CLEANUP_JOIN_MS)
            }
            handy.close()
        }
    }

    private fun verifyLegacyDictationApiStillWorks(model: File, pcm: ByteArray) {
        val session = TranscribeCppNative.open(model.absolutePath)
        try {
            session.begin("fr-FR")
            var currentText = ""
            val feed = feedPacedPcm(
                pcm = pcm,
                accept = { chunk -> currentText = session.feed(pcm16ToFloatArray(chunk)).full },
                snapshot = { currentText },
            )
            val final = session.finish()
            assertEquals("existing Dictée API must preserve the fixture transcript", EXPECTED_TRANSCRIPT, final.full)
            assertTrue("legacy dictation text API must not emit replacement characters",
                listOf(currentText, final.full, final.committed, final.tentative).none { it.contains('\uFFFD') })
            Log.i(LOG_TAG, "phase=legacy-dictation status=pass firstTextMs=${feed.firstTextMs} " +
                "feedCallMs=${feed.feedCallNanos / 1_000_000L} sourceFeedWallMs=${feed.sourceFeedWallMs} " +
                "maxAsrLagMs=${feed.maxAsrLagMs} snapshotCount=${feed.snapshotCount} " +
                "audioMs=${pcm.size * 1000L / PCM_BYTES_PER_SECOND} transcript=\"${final.full}\"")
        } finally {
            session.close()
        }
    }

    private fun verifyAssemblerReducerAttribution(result: StreamResult, audioMs: Long) {
        val handyWindow = checkNotNull(result.handyWindow) { "final Handy token window is required" }
        val diarizationWindow = checkNotNull(result.diarizationWindow) { "final diarization window is required" }
        val assembler = MeetingHandyTranscriptAssembler()
        val assembled = assembler.update(handyWindow, audioProcessedMs = audioMs, isFinal = true)
        val revised = assembler.reviseDiarization(diarizationWindow, audioProcessedMs = audioMs)
        assertTrue("the final Handy window must produce an utterance", assembled.isNotEmpty())

        val updates = assembled + revised
        val runId = "fixture-attribution-run"
        val reducer = MeetingTranscriptReducer(sessionId = "fixture-attribution-session", runId = runId)
        updates.forEach { update ->
            reducer.apply(
                MeetingHypothesis(
                    runId = runId,
                    utteranceId = update.utteranceId,
                    revision = update.revision,
                    words = update.words,
                    transcript = update.transcript,
                    isFinal = update.isFinal,
                    stableSpeakerThroughMs = update.stableSpeakerThroughMs,
                    audioProcessedMs = update.audioProcessedMs,
                ),
            )
        }

        val latestUpdates = updates.groupBy { it.utteranceId }.values.map { revisionsForUtterance ->
            revisionsForUtterance.maxBy { it.revision }
        }
        assertEquals("the assembled utterance must retain Handy's exact authoritative text",
            result.transcript, latestUpdates.single().transcript)
        latestUpdates.flatMap { it.words }.forEach { word ->
            assertTrue("aligned word must start inside the fixture PCM: $word", word.startMs >= 0L)
            assertTrue("aligned word must have a positive duration: $word", word.endMs > word.startMs)
            assertTrue("aligned word must end inside processed fixture PCM: $word", word.endMs <= audioMs)
        }

        val document = reducer.snapshot()
        val sourceWords = lexicalWords(result.transcript)
        val projectionRows = MeetingProjection.rows(document)
        // Projection order is authoritative. Trimming row edges and joining rows with one space
        // restores only the separator between turns; case, accents, apostrophes and punctuation
        // remain an exact comparison against Handy's original text.
        val projectedText = projectionRows.joinToString(separator = " ") { it.body.trim() }.trim()
        assertEquals("assembler plus reducer must preserve Handy's exact text in projection order",
            result.transcript.trim(), projectedText)
        val reducedWords = projectionRows.flatMap { lexicalWords(it.body) }
        assertEquals("assembler plus reducer must preserve each recognized word exactly once", sourceWords, reducedWords)
        assertTrue("reducer must retain all recognized fixture text", document.turns.isNotEmpty())
        assertTrue("reducer word timings must stay inside the fixture PCM", document.turns
            .filter { it.timingKnown }
            .all { it.startMs >= 0L && it.endMs > it.startMs && it.endMs <= audioMs })

        val finalWords = latestUpdates.flatMap { it.words }
        val timedLexemeCount = finalWords.sumOf { lexicalWords(it.text).size }
        val attributedWords = finalWords.filter { it.channel in 1..8 }
        val unknownChannelWords = finalWords.count { it.channel == 0 }
        val distinctChannels = attributedWords.map { it.channel }.distinct().sorted()
        Log.i(LOG_TAG, "phase=assembler-reducer-attribution audioMs=$audioMs " +
            "sourceLexicalWords=${sourceWords.size} reducedLexicalWords=${reducedWords.size} " +
            "timedAlignedWords=$timedLexemeCount timingUnknownWords=${(sourceWords.size - timedLexemeCount).coerceAtLeast(0)} " +
            "attributedWords=${attributedWords.size} unknownChannelWords=$unknownChannelWords " +
            "channels=$distinctChannels reducedTurns=${document.turns.size} projectionRows=${projectionRows.size} " +
            "timedTurns=${document.turns.count { it.timingKnown }} " +
            "attributedTurns=${document.turns.count { it.automaticParticipantId != null }} " +
            "transcriptPreservedExactlyOnce=true")
    }

    private fun lexicalWords(text: String): List<String> =
        LEXICAL_WORD_PATTERN.findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()

    private fun normalizeAsciiSpaces(text: String): String =
        text.replace(Regex(" +"), " ").trim(' ')

    private fun feedPacedPcm(
        pcm: ByteArray,
        accept: (ByteArray) -> Unit,
        snapshot: () -> String,
        afterSnapshot: ((ByteArray, Int) -> Unit)? = null,
        onProgress: ((audioBytes: Int, elapsedMs: Long) -> Unit)? = null,
    ): FeedMetrics {
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        var offset = 0
        var firstTextMs = -1L
        var feedCallNanos = 0L
        var snapshotCallNanos = 0L
        var maxAsrLagNanos = 0L
        var snapshotCount = 0
        var latestText = ""
        var nextProgressAudioMs = 10_000L
        while (offset < pcm.size) {
            val endOffset = minOf(offset + PCM_BYTES_PER_20_MS, pcm.size)
            val chunk = pcm.copyOfRange(offset, endOffset)
            val deadlineNanos = startedAtNanos + endOffset * NANOS_PER_SECOND / PCM_BYTES_PER_SECOND
            waitUntil(deadlineNanos)
            val feedStartNanos = SystemClock.elapsedRealtimeNanos()
            accept(chunk)
            val feedEndNanos = SystemClock.elapsedRealtimeNanos()
            feedCallNanos += feedEndNanos - feedStartNanos
            maxAsrLagNanos = maxOf(maxAsrLagNanos, (feedEndNanos - deadlineNanos).coerceAtLeast(0L))
            offset = endOffset
            val snapshotStartNanos = SystemClock.elapsedRealtimeNanos()
            latestText = snapshot()
            snapshotCallNanos += SystemClock.elapsedRealtimeNanos() - snapshotStartNanos
            snapshotCount++
            if (firstTextMs < 0L && latestText.isNotEmpty()) firstTextMs = elapsedMsSince(startedAtNanos)
            afterSnapshot?.invoke(chunk, offset)
            val audioPositionMs = offset * 1000L / PCM_BYTES_PER_SECOND
            if (onProgress != null && audioPositionMs >= nextProgressAudioMs) {
                onProgress(offset, elapsedMsSince(startedAtNanos))
                nextProgressAudioMs += 10_000L
            }
        }
        return FeedMetrics(
            startedAtNanos = startedAtNanos,
            firstTextMs = firstTextMs,
            sourceFeedWallMs = elapsedMsSince(startedAtNanos),
            feedCallNanos = feedCallNanos,
            snapshotCallNanos = snapshotCallNanos,
            snapshotCount = snapshotCount,
            maxAsrLagMs = maxAsrLagNanos / 1_000_000L,
            latestText = latestText,
        )
    }

    private fun waitUntil(deadlineNanos: Long) {
        while (true) {
            val remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos <= 0L) return
            LockSupport.parkNanos(remainingNanos)
            check(!Thread.currentThread().isInterrupted) { "Interrupted while pacing fixture PCM" }
        }
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L

    private fun pcm16ToFloatArray(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0)
        return FloatArray(bytes.size / 2) { sampleIndex ->
            val low = bytes[sampleIndex * 2].toInt() and 0xFF
            val high = bytes[sampleIndex * 2 + 1].toInt()
            (low or (high shl 8)).toShort().toFloat() / 32768.0f
        }
    }

    private fun assertArtifact(file: File, expectedBytes: Long, expectedSha: String) {
        assertTrue("pre-staged test model is present", file.isFile)
        assertEquals(expectedBytes, file.length())
        assertEquals(expectedSha, sha256(file))
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun extractMono16KhzPcm16(wav: ByteArray): ByteArray {
        require(wav.size >= 44 && ascii(wav, 0, 4) == "RIFF" && ascii(wav, 8, 4) == "WAVE")
        var offset = 12
        var format = -1
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var dataOffset = -1
        var dataLength = -1
        while (offset + 8 <= wav.size) {
            val chunk = ascii(wav, offset, 4)
            val length = ByteBuffer.wrap(wav, offset + 4, 4).slice().order(ByteOrder.LITTLE_ENDIAN).int
            require(length >= 0 && offset + 8L + length <= wav.size)
            val body = offset + 8
            if (chunk == "fmt ") {
                val fmt = ByteBuffer.wrap(wav, body, length).slice().order(ByteOrder.LITTLE_ENDIAN)
                format = fmt.short.toInt() and 0xffff
                channels = fmt.short.toInt() and 0xffff
                sampleRate = fmt.int
                fmt.int
                fmt.short
                bits = fmt.short.toInt() and 0xffff
            } else if (chunk == "data") {
                dataOffset = body
                dataLength = length
                break
            }
            offset = body + length + (length and 1)
        }
        require(format == 1 && channels == 1 && sampleRate == 16_000 && bits == 16)
        require(dataOffset >= 0 && dataLength > 0 && dataLength % 2 == 0)
        return wav.copyOfRange(dataOffset, dataOffset + dataLength)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private data class StreamResult(
        val transcript: String,
        val diarFrames: Long,
        val stableFrames: Long,
        val handyWindow: HandyTokenWindow? = null,
        val diarizationWindow: DiarizationFrameWindow? = null,
    )

    private data class FeedMetrics(
        val startedAtNanos: Long,
        val firstTextMs: Long,
        val sourceFeedWallMs: Long,
        val feedCallNanos: Long,
        val snapshotCallNanos: Long,
        val snapshotCount: Int,
        val maxAsrLagMs: Long,
        val latestText: String,
    )

    private data class DiarWorkerResult(
        val frames: DiarizationFrameWindow?,
        val tailFrames: DiarizationFrameWindow?,
        val endFrames: DiarizationFrameWindow?,
        val processedBytes: Long,
        val finishMs: Long,
    )

    private companion object {
        const val LOG_TAG = "MeetingPorts"
        const val FIXTURE_ASSET = "meeting/synthetic-fr-ABCA-16k-mono.wav"
        const val FIXTURE_SHA256 = "196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9"
        const val HANDY_MODEL_NAME = "handy.gguf"
        const val HANDY_MODEL_BYTES = 751_094_240L
        const val HANDY_MODEL_SHA256 = "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c"
        const val DIAR_MODEL_NAME = "diar.gguf"
        const val DIAR_MODEL_BYTES = 107_012_128L
        const val DIAR_MODEL_SHA256 = "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1"
        const val PCM_BYTES_PER_SECOND = 32_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val PCM_BYTES_PER_20_MS = 640
        const val SNAPSHOT_TOKENS = 8192
        // Mirrors the coordinator's 120-second audio retention at 20 ms / 640-byte chunks.
        const val DIAR_QUEUE_CAPACITY = 6_000
        const val DIAR_WORKER_JOIN_MS = 120_000L
        const val WORKER_CLEANUP_JOIN_MS = 5_000L
        const val SUSTAINED_REPEAT_COUNT = 5
        const val EXPECTED_TRANSCRIPT =
            "Bonjour, ouvrons la réunion de la semaine, je propose jeudi matin pour le suivi d'accord merci, nous reprenons jeudi."
        val LEXICAL_WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-‐‑][\\p{L}\\p{M}\\p{N}]+)*")
    }
}
