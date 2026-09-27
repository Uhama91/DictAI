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
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.Collections
import java.util.Base64
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
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

    @Test(timeout = 180_000L)
    fun test5_captures_replayable_short_abca_token_and_diarization_windows() {
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

        val result = runHandyWithDiarization(
            handyModel = handyModel,
            diarModel = diarModel,
            pcm = pcm,
            audioMs = audioMs,
            expectedTranscript = EXPECTED_TRANSCRIPT,
            progressTag = "test5-short-abca",
            captureDiarProgress = true,
            captureHandyHistory = true,
        )
        val handyWindow = checkNotNull(result.handyWindow)
        val diarizationWindow = checkNotNull(result.diarizationWindow)
        val assembler = Test4MeetingHandyTranscriptAssembler()
        val initial = assembler.update(handyWindow, audioProcessedMs = audioMs, isFinal = true)
        val revisions = assembler.reviseDiarization(diarizationWindow, audioProcessedMs = audioMs)
        val latest = (initial + revisions).groupBy { it.utteranceId }.values
            .map { utterances -> utterances.maxBy { it.revision } }
        val words = latest.flatMap { it.words }
        val sourceLexemes = lexicalWords(result.transcript)
        val attributed = words.filter { it.channel in 1..8 }
        val unknown = words.filter { it.channel == 0 }
        val alignmentBroken = assembler.javaClass.getDeclaredField("alignmentBroken").let { field ->
            field.isAccessible = true
            field.getBoolean(assembler)
        }
        val unknownReasons = unknown.groupingBy { word ->
            val frameReason = test4UnknownReason(word, diarizationWindow)
            when {
                alignmentBroken -> "alignmentBroken+$frameReason"
                frameReason.startsWith("would-assign-channel-") -> "other-path+$frameReason"
                else -> frameReason
            }
        }.eachCount()
        assertEquals(EXPECTED_TRANSCRIPT, result.transcript)
        assertTrue("fixture transcript must contain lexical words", sourceLexemes.isNotEmpty())
        assertTrue("Handy must expose token timestamps for the captured text", handyWindow.totalTokenCount > 0)
        assertEquals("capture must include every Handy token, not a partial snapshot",
            handyWindow.totalTokenCount, handyWindow.tokenStartsMs.size)
        assertTrue(diarizationWindow.totalFrameCount > 0L)
        assertTrue("the frozen test4 classifier must produce timed words", words.isNotEmpty())
        assertTrue("the worker must capture incremental diarization snapshots", result.diarProgress.isNotEmpty())
        val expectedLiveSnapshotCount = ceil(pcm.size / PCM_BYTES_PER_20_MS.toDouble()).toInt()
        assertEquals("every paced Handy snapshot must be available for event-order replay",
            expectedLiveSnapshotCount + 1, result.handyHistory.size)
        assertEquals("the replay ends with a final Handy snapshot at the full PCM boundary",
            pcm.size.toLong(), result.handyHistory.last().processedPcmBytes)
        val orderedEvents = result.timelineEvents.sortedBy { it.eventSequence }
        assertTrue("the replay must include both Handy and diarization snapshots",
            orderedEvents.any { it.kind == "handy_snapshot" } &&
                orderedEvents.any { it.kind == "diarization_snapshot" })
        assertEquals("event sequence numbers must preserve a complete capture order",
            orderedEvents.indices.map(Int::toLong), orderedEvents.map { it.eventSequence })
        assertEquals("every live Handy snapshot must be represented in the event timeline",
            result.handyHistory.size,
            orderedEvents.count { it.kind == "handy_snapshot" || it.kind == "handy_finish" })
        val historyReplay = replayTest4History(result, audioMs)
        assertTrue("history replay must record every ordered event",
            historyReplay.states.size == orderedEvents.size)
        words.forEach { word ->
            assertTrue("captured token timing must stay inside the submitted audio: $word",
                word.startMs >= 0L && word.endMs > word.startMs && word.endMs <= audioMs)
        }

        val wordDiagnostics = latest.flatMap { update ->
            update.words.map { Test4WordDiagnostic(update.utteranceId, it) }
        }
        val output = writeReplayEvidence(
            contextCache = instrumentation.targetContext.cacheDir,
            result = result,
            sourcePcmBytes = pcm.size,
            sourcePcmSha256 = sha256(pcm),
            audioMs = audioMs,
            modelDirectory = models,
            wordDiagnostics = wordDiagnostics,
            alignmentBroken = alignmentBroken,
            historyReplay = historyReplay,
        )
        Log.i(LOG_TAG, "phase=test5-short-summary audioMs=$audioMs transcriptChars=${result.transcript.length} " +
            "sourceLexicalWords=${sourceLexemes.size} timedWords=${words.size} " +
            "timedLexicalWords=${words.sumOf { lexicalWords(it.text).size }} " +
            "timingUnknownWords=${(sourceLexemes.size - words.sumOf { lexicalWords(it.text).size }).coerceAtLeast(0)} " +
            "attributedWords=${attributed.size} unknownTimedWords=${unknown.size} " +
            "unknownReasons=$unknownReasons channels=${attributed.map { it.channel }.distinct().sorted()} " +
            "alignmentBroken=$alignmentBroken turns=${latest.size} diarFrames=${diarizationWindow.totalFrameCount} " +
            "stableFrames=${diarizationWindow.stableFrameCount} speakers=${diarizationWindow.speakerCount} " +
            "progressSamples=${result.diarProgress.size} replayDir=${output.absolutePath}")
        Log.i(LOG_TAG, "phase=test5-history-replay events=${historyReplay.states.size} " +
            "alignmentBrokenEver=${historyReplay.firstBrokenEvent != null} " +
            "firstBrokenEventSequence=${historyReplay.firstBrokenEvent?.eventSequence ?: -1L} " +
            "firstBrokenEventKind=${historyReplay.firstBrokenEvent?.kind ?: "none"} " +
            "firstBrokenPcmBytes=${historyReplay.firstBrokenEvent?.processedPcmBytes ?: -1L} " +
            "finalAlignmentBroken=${historyReplay.finalAlignmentBroken} " +
            "finalTimedWords=${historyReplay.finalTimedWordCount} " +
            "finalUnknownChannelWords=${historyReplay.finalUnknownChannelWordCount}")
        wordDiagnostics.forEach { diagnostic ->
            val word = diagnostic.word
            val reason = if (word.channel == 0) test4UnknownReason(word, diarizationWindow) else "assigned"
            Log.i(LOG_TAG, "phase=test5-word utteranceId=${diagnostic.utteranceId} text=${word.text} " +
                "startMs=${word.startMs} endMs=${word.endMs} channel=${word.channel} reason=$reason")
        }
        assertTrue("replay bundle must contain metadata and both native windows",
            File(output, "metadata.txt").isFile && File(output, "handy-tokens.tsv").isFile &&
                File(output, "diarization-frames.tsv").isFile && File(output, "test4-word-classification.tsv").isFile &&
                File(output, "diar-progress-frames.tsv").isFile && File(output, "test4-history-replay.tsv").isFile &&
                File(output, "replay.zip").isFile)
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
        captureDiarProgress: Boolean = false,
        captureHandyHistory: Boolean = false,
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
        val latestDiarProgress = AtomicReference<DiarProgressSample?>(null)
        val diarProgress = Collections.synchronizedList(mutableListOf<DiarProgressSample>())
        val handyHistory = mutableListOf<HandyHistorySample>()
        val timelineEvents = Collections.synchronizedList(mutableListOf<ReplayTimelineEvent>())
        val timelineSequence = AtomicLong(0L)
        val captureStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        fun recordTimelineEvent(kind: String, processedPcmBytes: Long, referenceIndex: Int): Long {
            val sequence = timelineSequence.getAndIncrement()
            val elapsedWallMs = (SystemClock.elapsedRealtimeNanos() - captureStartedAtNanos) / 1_000_000L
            timelineEvents += ReplayTimelineEvent(
                eventSequence = sequence,
                kind = kind,
                elapsedWallMs = elapsedWallMs,
                processedPcmBytes = processedPcmBytes,
                referenceIndex = referenceIndex,
            )
            return sequence
        }
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
            var nextDiarProgressBytes = PCM_BYTES_PER_SECOND * DIAR_PROGRESS_INTERVAL_SECONDS
            var previousProgressFeedWallNanos = 0L
            val workerStartedAtNanos = SystemClock.elapsedRealtimeNanos()
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
                    if (captureDiarProgress && processedBytes >= nextDiarProgressBytes) {
                        val frameWindow = activeDiar.snapshot(firstFrameIndex = 0L)
                        val feedNanos = diarFeedNanos.get()
                        val progressIndex = diarProgress.size
                        val eventSequence = if (captureHandyHistory) {
                            recordTimelineEvent("diarization_snapshot", processedBytes, progressIndex)
                        } else {
                            -1L
                        }
                        val sample = DiarProgressSample(
                            eventSequence = eventSequence,
                            processedPcmBytes = processedBytes,
                            captureElapsedWallMs = (SystemClock.elapsedRealtimeNanos() - captureStartedAtNanos) / 1_000_000L,
                            processedAudioMs = processedBytes * 1000L / PCM_BYTES_PER_SECOND,
                            elapsedMs = (SystemClock.elapsedRealtimeNanos() - workerStartedAtNanos) / 1_000_000L,
                            totalFrames = frameWindow.totalFrameCount,
                            stableFrames = frameWindow.stableFrameCount,
                            feedIntervalWallMs = (feedNanos - previousProgressFeedWallNanos) / 1_000_000L,
                            pssKb = Debug.getPss().toLong(),
                            window = frameWindow,
                        )
                        diarProgress += sample
                        latestDiarProgress.set(sample)
                        previousProgressFeedWallNanos = feedNanos
                        nextDiarProgressBytes += PCM_BYTES_PER_SECOND * DIAR_PROGRESS_INTERVAL_SECONDS
                    }
                }
                val finishStart = SystemClock.elapsedRealtime()
                activeDiar.finish()
                frames = activeDiar.snapshot(firstFrameIndex = 0L)
                if (captureHandyHistory) {
                    recordTimelineEvent("diarization_finish", processedBytes, -1)
                }
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
                        diarProgress = synchronized(diarProgress) { diarProgress.toList() },
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
            var latestHandyWindow: HandyTokenWindow? = null
            val feed = feedPacedPcm(
                pcm = pcm,
                accept = { chunk -> handy.acceptPcm16(chunk, chunk.size) },
                snapshot = {
                    val window = handy.snapshot(firstTokenIndex = 0, maxTokens = SNAPSHOT_TOKENS)
                    if (captureHandyHistory) latestHandyWindow = window
                    window.fullTextUtf8.toString(StandardCharsets.UTF_8)
                },
                onSnapshot = if (captureHandyHistory) { audioBytes, elapsedWallMs, _ ->
                    val window = checkNotNull(latestHandyWindow) { "snapshot callback lost its Handy window" }
                    val sampleIndex = handyHistory.size
                    val eventSequence = recordTimelineEvent("handy_snapshot", audioBytes.toLong(), sampleIndex)
                    handyHistory += HandyHistorySample(
                        eventSequence = eventSequence,
                        kind = "handy_snapshot",
                        processedPcmBytes = audioBytes.toLong(),
                        elapsedWallMs = elapsedWallMs,
                        window = copyHandyTokenWindow(window),
                    )
                } else null,
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
                        val progress = latestDiarProgress.get()
                        Log.i(LOG_TAG, "phase=$tag progressAudioMs=${audioBytes * 1000L / PCM_BYTES_PER_SECOND} " +
                            "elapsedMs=$elapsedMs diarProcessedBytes=${diarProcessedBytes.get()} " +
                            "backlogMs=${backlog * 1000L / PCM_BYTES_PER_SECOND} " +
                            "queueDepth=${queue.size} maxQueueDepth=${maxQueueDepth.get()} " +
                            "diarFrames=${progress?.totalFrames ?: -1L} " +
                            "diarStableFrames=${progress?.stableFrames ?: -1L}")
                    }
                },
            )
            assertTrue("live Handy snapshots must publish text before finish", feed.firstTextMs >= 0L)
            val finalWindow = handy.finish(firstTokenIndex = 0, maxTokens = SNAPSHOT_TOKENS)
            if (captureHandyHistory) {
                val finalElapsedWallMs = (SystemClock.elapsedRealtimeNanos() - feed.startedAtNanos) / 1_000_000L
                val sampleIndex = handyHistory.size
                val eventSequence = recordTimelineEvent("handy_finish", pcm.size.toLong(), sampleIndex)
                handyHistory += HandyHistorySample(
                    eventSequence = eventSequence,
                    kind = "handy_finish",
                    processedPcmBytes = pcm.size.toLong(),
                    elapsedWallMs = finalElapsedWallMs,
                    window = copyHandyTokenWindow(finalWindow),
                )
            }
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
            result.diarProgress.forEach { sample ->
                Log.i(LOG_TAG, "phase=diar-increment processedAudioMs=${sample.processedAudioMs} " +
                    "elapsedMs=${sample.elapsedMs} deltaFeedWallMs=${sample.feedIntervalWallMs} " +
                    "totalFrames=${sample.totalFrames} stableFrames=${sample.stableFrames} pssKb=${sample.pssKb}")
            }
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
                diarProgress = result.diarProgress,
                handyHistory = synchronized(handyHistory) { handyHistory.toList() },
                timelineEvents = synchronized(timelineEvents) { timelineEvents.sortedBy { it.eventSequence } },
                timingsMs = mapOf(
                    "handyOpen" to handyOpenMs,
                    "handyFirstText" to feed.firstTextMs,
                    "handyFeedWall" to feed.sourceFeedWallMs,
                    "handyFeedCalls" to feed.feedCallNanos / 1_000_000L,
                    "handySnapshotCalls" to feed.snapshotCallNanos / 1_000_000L,
                    "handyMaxLag" to feed.maxAsrLagMs,
                    "handyFinal" to handyFinalMs,
                    "diarOpen" to workerOpenMs.get(),
                    "diarFeedCalls" to diarFeedNanos.get() / 1_000_000L,
                    "diarFinish" to result.finishMs,
                    "diarDrain" to diarDrainMs,
                    "diarClose" to workerCloseMs.get(),
                    "maxDiarBacklog" to maxLagBytes.get() * 1000L / PCM_BYTES_PER_SECOND,
                    "pssHandyOpen" to pssAfterHandyOpenKb.toLong(),
                    "pssDiarOpenWithHandy" to workerPssAfterOpenKb.get(),
                    "pssBeforeDiarCloseWithHandy" to workerPssBeforeCloseKb.get(),
                    "pssAfterDiarClose" to pssAfterCloseKb.toLong(),
                ),
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

    /** Replays every captured Handy and diarization event in observed order through frozen test4. */
    private fun replayTest4History(result: StreamResult, audioMs: Long): HistoryReplaySummary {
        val assembler = Test4MeetingHandyTranscriptAssembler()
        val alignmentBrokenField = assembler.javaClass.getDeclaredField("alignmentBroken").also {
            it.isAccessible = true
        }
        val updates = mutableListOf<MeetingNativeUpdate>()
        val states = mutableListOf<HistoryReplayState>()
        var firstBrokenEvent: ReplayTimelineEvent? = null
        val finalDiarization = checkNotNull(result.diarizationWindow)

        result.timelineEvents.sortedBy { it.eventSequence }.forEach { event ->
            when (event.kind) {
                "handy_snapshot", "handy_finish" -> {
                    val sample = result.handyHistory[event.referenceIndex]
                    updates += assembler.update(
                        sample.window,
                        audioProcessedMs = sample.processedPcmBytes * 1000L / PCM_BYTES_PER_SECOND,
                        isFinal = event.kind == "handy_finish",
                    )
                }
                "diarization_snapshot" -> {
                    val sample = result.diarProgress[event.referenceIndex]
                    updates += assembler.reviseDiarization(
                        sample.window,
                        audioProcessedMs = sample.processedPcmBytes * 1000L / PCM_BYTES_PER_SECOND,
                    )
                }
                "diarization_finish" -> {
                    updates += assembler.reviseDiarization(finalDiarization, audioProcessedMs = audioMs)
                }
                else -> error("Unknown replay event kind ${event.kind}")
            }

            val alignmentBroken = alignmentBrokenField.getBoolean(assembler)
            if (alignmentBroken && firstBrokenEvent == null) firstBrokenEvent = event
            val latestWords = updates.groupBy { it.utteranceId }.values
                .map { utteranceUpdates -> utteranceUpdates.maxBy { it.revision } }
                .flatMap { it.words }
            states += HistoryReplayState(
                eventSequence = event.eventSequence,
                kind = event.kind,
                processedPcmBytes = event.processedPcmBytes,
                alignmentBroken = alignmentBroken,
                updateCount = updates.size,
                latestWordCount = latestWords.size,
                timedWordCount = latestWords.count { it.startMs >= 0L && it.endMs > it.startMs },
                unknownChannelWordCount = latestWords.count { it.channel == 0 },
            )
        }

        val finalState = states.lastOrNull()
        return HistoryReplaySummary(
            states = states,
            firstBrokenEvent = firstBrokenEvent,
            finalAlignmentBroken = finalState?.alignmentBroken ?: false,
            finalTimedWordCount = finalState?.timedWordCount ?: 0,
            finalUnknownChannelWordCount = finalState?.unknownChannelWordCount ?: 0,
        )
    }

    private fun lexicalWords(text: String): List<String> =
        LEXICAL_WORD_PATTERN.findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()

    private fun normalizeAsciiSpaces(text: String): String =
        text.replace(Regex(" +"), " ").trim(' ')

    /** Reproduces only test4's strict frame gate so each unknown word can be explained offline. */
    private fun test4UnknownReason(word: MeetingWord, diarization: DiarizationFrameWindow): String {
        if (word.startMs < 0L || word.endMs <= word.startMs) return "invalid-word-timing"
        val frameDurationMs = diarization.secondsPerFrame * 1_000.0
        if (!frameDurationMs.isFinite() || frameDurationMs <= 0.0) return "invalid-frame-duration"
        val firstFrame = floor(test4FramePosition(word.startMs, frameDurationMs)).toLong()
        val endFrameExclusive = ceil(test4FramePosition(word.endMs, frameDurationMs)).toLong()
        val frameCount = diarization.probabilities.size / diarization.speakerCount
        val windowEnd = diarization.firstFrameIndex + frameCount
        if (firstFrame < diarization.firstFrameIndex || endFrameExclusive > windowEnd) return "missing-frame-coverage"
        if (endFrameExclusive > diarization.stableFrameCount) return "unstable-frame-coverage"
        if (firstFrame >= endFrameExclusive) return "empty-frame-range"

        var speaker: Int? = null
        for (frameIndex in firstFrame until endFrameExclusive) {
            val localFrame = (frameIndex - diarization.firstFrameIndex).toInt()
            val active = (0 until diarization.speakerCount).filter { candidate ->
                val probability = diarization.probabilities[localFrame * diarization.speakerCount + candidate]
                probability.isFinite() && probability >= TEST4_MIN_SPEAKER_PROBABILITY
            }
            if (active.size > 1) return "ambiguous-frame-multiple-channels"
            val activeSpeaker = active.singleOrNull() ?: return "frame-below-0.5"
            if (speaker != null && speaker != activeSpeaker) return "speaker-switch-within-word"
            speaker = activeSpeaker
        }
        return "would-assign-channel-${checkNotNull(speaker) + 1}"
    }

    private fun test4FramePosition(timeMs: Long, frameDurationMs: Double): Double {
        val position = timeMs / frameDurationMs
        val nearestBoundary = round(position)
        val float32Error = abs(nearestBoundary) * TEST4_FLOAT32_BOUNDARY_TOLERANCE
        val tolerance = maxOf(TEST4_MIN_FRAME_POSITION_TOLERANCE, float32Error)
        return if (abs(position - nearestBoundary) <= tolerance) nearestBoundary else position
    }

    private fun writeReplayEvidence(
        contextCache: File,
        result: StreamResult,
        sourcePcmBytes: Int,
        sourcePcmSha256: String,
        audioMs: Long,
        modelDirectory: File,
        wordDiagnostics: List<Test4WordDiagnostic>,
        alignmentBroken: Boolean,
        historyReplay: HistoryReplaySummary,
    ): File {
        val handy = checkNotNull(result.handyWindow)
        val diarization = checkNotNull(result.diarizationWindow)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val packageInfo = target.packageManager.getPackageInfo(target.packageName, 0)
        val runName = "test5-short-abca-${SystemClock.elapsedRealtime()}"
        val output = File(contextCache, "meeting-test5/$runName")
        check(output.mkdirs()) { "Could not create replay evidence directory ${output.absolutePath}" }
        val installedApk = File(target.applicationInfo.sourceDir)
        val apkJniArtifacts = listOf(
            "lib/arm64-v8a/libdictai_meeting.so",
            "lib/arm64-v8a/libtranscribe_jni.so",
        ).joinToString("\n") { name -> apkEntryIdentity(installedApk, name) }
        val metadata = buildString {
            appendLine("format=meeting-test5-short-abca-replay-v2")
            appendLine("fixtureAsset=$FIXTURE_ASSET")
            appendLine("fixtureWavSha256=$FIXTURE_SHA256")
            appendLine("fixturePcmSha256=$sourcePcmSha256")
            appendLine("fixtureDescription=synthetic French ABCA public test fixture; not the user's football video")
            appendLine("pcmBytes=$sourcePcmBytes")
            appendLine("pcmDurationMs=$audioMs")
            appendLine("handyModel=${HANDY_MODEL_NAME} bytes=${modelDirectory.resolve(HANDY_MODEL_NAME).length()} sha256=${HANDY_MODEL_SHA256}")
            appendLine("diarModel=${DIAR_MODEL_NAME} bytes=${modelDirectory.resolve(DIAR_MODEL_NAME).length()} sha256=${DIAR_MODEL_SHA256}")
            appendLine("test4ReferenceCommit=$TEST4_ASSEMBLER_SOURCE_COMMIT")
            appendLine("test4ReferenceSourceSha256=$TEST4_ASSEMBLER_SOURCE_SHA256")
            appendLine("test4AlignmentBrokenFinalSnapshotOnly=$alignmentBroken")
            appendLine("test4HistoryAlignmentBrokenEver=${historyReplay.firstBrokenEvent != null}")
            appendLine("test4HistoryFirstBrokenEventSequence=${historyReplay.firstBrokenEvent?.eventSequence ?: -1L}")
            appendLine("test4HistoryFinalTimedWords=${historyReplay.finalTimedWordCount}")
            appendLine("test4HistoryFinalUnknownChannelWords=${historyReplay.finalUnknownChannelWordCount}")
            appendLine("appPackage=${target.packageName}")
            appendLine("appVersionName=${packageInfo.versionName}")
            appendLine("appVersionCode=${packageInfo.longVersionCode}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("androidSdk=${Build.VERSION.SDK_INT}")
            appendLine("androidFingerprint=${Build.FINGERPRINT}")
            appendLine("deviceModel=${Build.MODEL}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("nativeLibraryDir=${target.applicationInfo.nativeLibraryDir}")
            appendLine("installedBaseApk=${installedApk.absolutePath}")
            appendLine("installedBaseApkBytes=${installedApk.length()}")
            appendLine("installedBaseApkSha256=${sha256(installedApk)}")
            appendLine("apkJniEntriesBegin")
            appendLine(apkJniArtifacts)
            appendLine("apkJniEntriesEnd")
            appendLine("handyTotalTokenCount=${handy.totalTokenCount}")
            appendLine("handyCommittedTokenCount=${handy.committedTokenCount}")
            appendLine("handyFirstTokenIndex=${handy.firstTokenIndex}")
            appendLine("handyFullTextSha256=${sha256(handy.fullTextUtf8)}")
            appendLine("diarFirstFrameIndex=${diarization.firstFrameIndex}")
            appendLine("diarSecondsPerFrame=${diarization.secondsPerFrame}")
            appendLine("diarTotalFrameCount=${diarization.totalFrameCount}")
            appendLine("diarStableFrameCount=${diarization.stableFrameCount}")
            appendLine("diarSpeakerCount=${diarization.speakerCount}")
            appendLine("handyHistorySampleCount=${result.handyHistory.size}")
            appendLine("timelineEventCount=${result.timelineEvents.size}")
            appendLine("feedIntervalMetric=feedIntervalWallMs; elapsedRealtimeNanos duration around diar accept calls, not thread CPU")
            result.timingsMs.toSortedMap().forEach { (name, value) -> appendLine("timingMs.$name=$value") }
            appendLine("progressSampleCount=${result.diarProgress.size}")
        }
        File(output, "metadata.txt").writeText(metadata, StandardCharsets.UTF_8)
        File(output, "handy-full-text.utf8").writeBytes(handy.fullTextUtf8)
        File(output, "handy-token-bytes.bin").writeBytes(handy.tokenBytes)

        val tokenRows = buildString {
            appendLine("tokenIndex\tstartMs\tendMs\tcommitted\tbyteStart\tbyteEnd\trawHex")
            var byteStart = 0
            handy.tokenByteEnds.forEachIndexed { localIndex, byteEnd ->
                val tokenIndex = handy.firstTokenIndex + localIndex
                val tokenHex = handy.tokenBytes.copyOfRange(byteStart, byteEnd)
                    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                append(tokenIndex).append('\t')
                    .append(handy.tokenStartsMs[localIndex]).append('\t')
                    .append(handy.tokenEndsMs[localIndex]).append('\t')
                    .append(tokenIndex < handy.committedTokenCount).append('\t')
                    .append(byteStart).append('\t').append(byteEnd).append('\t').append(tokenHex).append('\n')
                byteStart = byteEnd
            }
            if (byteStart < handy.tokenBytes.size) {
                append("trailing-bytes\t-1\t-1\tfalse\t").append(byteStart).append('\t')
                    .append(handy.tokenBytes.size).append('\t')
                    .append(handy.tokenBytes.copyOfRange(byteStart, handy.tokenBytes.size)
                        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
                    .append('\n')
            }
        }
        File(output, "handy-tokens.tsv").writeText(tokenRows, StandardCharsets.UTF_8)

        val handyHistoryRows = buildString {
            appendLine("eventSequence\tkind\telapsedWallMs\tprocessedPcmBytes\tprocessedAudioMs\tfirstTokenIndex\ttotalTokenCount\tcommittedTokenCount\tfullTextUtf8Base64\ttokenBytesBase64\ttokenByteEndsCsv\ttokenStartsMsCsv\ttokenEndsMsCsv")
            result.handyHistory.forEach { sample ->
                val window = sample.window
                append(sample.eventSequence).append('\t')
                    .append(sample.kind).append('\t')
                    .append(sample.elapsedWallMs).append('\t')
                    .append(sample.processedPcmBytes).append('\t')
                    .append(sample.processedPcmBytes * 1000L / PCM_BYTES_PER_SECOND).append('\t')
                    .append(window.firstTokenIndex).append('\t')
                    .append(window.totalTokenCount).append('\t')
                    .append(window.committedTokenCount).append('\t')
                    .append(Base64.getEncoder().encodeToString(window.fullTextUtf8)).append('\t')
                    .append(Base64.getEncoder().encodeToString(window.tokenBytes)).append('\t')
                    .append(window.tokenByteEnds.joinToString(",")).append('\t')
                    .append(window.tokenStartsMs.joinToString(",")).append('\t')
                    .append(window.tokenEndsMs.joinToString(",")).append('\n')
            }
        }
        File(output, "handy-history.tsv").writeText(handyHistoryRows, StandardCharsets.UTF_8)

        val timelineRows = buildString {
            appendLine("eventSequence\tkind\telapsedWallMs\tprocessedPcmBytes\tprocessedAudioMs\treferenceIndex")
            result.timelineEvents.sortedBy { it.eventSequence }.forEach { event ->
                append(event.eventSequence).append('\t')
                    .append(event.kind).append('\t')
                    .append(event.elapsedWallMs).append('\t')
                    .append(event.processedPcmBytes).append('\t')
                    .append(event.processedPcmBytes * 1000L / PCM_BYTES_PER_SECOND).append('\t')
                    .append(event.referenceIndex).append('\n')
            }
        }
        File(output, "events.tsv").writeText(timelineRows, StandardCharsets.UTF_8)

        val replayRows = buildString {
            appendLine("eventSequence\tkind\tprocessedPcmBytes\talignmentBroken\tupdateCount\tlatestWordCount\ttimedWordCount\tunknownChannelWordCount")
            historyReplay.states.forEach { state ->
                append(state.eventSequence).append('\t')
                    .append(state.kind).append('\t')
                    .append(state.processedPcmBytes).append('\t')
                    .append(state.alignmentBroken).append('\t')
                    .append(state.updateCount).append('\t')
                    .append(state.latestWordCount).append('\t')
                    .append(state.timedWordCount).append('\t')
                    .append(state.unknownChannelWordCount).append('\n')
            }
        }
        File(output, "test4-history-replay.tsv").writeText(replayRows, StandardCharsets.UTF_8)

        val speakerHeaders = (0 until diarization.speakerCount).joinToString("\t") { "p${it}_float32_hex,p${it}_decimal" }
        val frameRows = buildString {
            appendLine("frameIndex\tstartMs\tendMs\tstable\t$speakerHeaders")
            val frameCount = diarization.probabilities.size / diarization.speakerCount
            repeat(frameCount) { localFrame ->
                val frameIndex = diarization.firstFrameIndex + localFrame
                val startMs = frameIndex * diarization.secondsPerFrame * 1_000.0
                val endMs = (frameIndex + 1L) * diarization.secondsPerFrame * 1_000.0
                append(frameIndex).append('\t').append(startMs).append('\t').append(endMs).append('\t')
                    .append(frameIndex < diarization.stableFrameCount)
                repeat(diarization.speakerCount) { speaker ->
                    val probability = diarization.probabilities[localFrame * diarization.speakerCount + speaker]
                    append('\t').append(probability.toRawBits().toUInt().toString(16).padStart(8, '0'))
                        .append(',').append(probability.toString())
                }
                append('\n')
            }
        }
        File(output, "diarization-frames.tsv").writeText(frameRows, StandardCharsets.UTF_8)

        val progressFrameRows = buildString {
            appendLine("eventSequence\tprocessedPcmBytes\tsampleAudioMs\tframeIndex\tstartMs\tendMs\tstable\t$speakerHeaders")
            result.diarProgress.forEach { sample ->
                val sampleWindow = sample.window
                val sampleFrameCount = sampleWindow.probabilities.size / sampleWindow.speakerCount
                repeat(sampleFrameCount) { localFrame ->
                    val frameIndex = sampleWindow.firstFrameIndex + localFrame
                    append(sample.eventSequence).append('\t').append(sample.processedPcmBytes).append('\t')
                        .append(sample.processedAudioMs).append('\t').append(frameIndex).append('\t')
                        .append(frameIndex * sampleWindow.secondsPerFrame * 1_000.0).append('\t')
                        .append((frameIndex + 1L) * sampleWindow.secondsPerFrame * 1_000.0).append('\t')
                        .append(frameIndex < sampleWindow.stableFrameCount)
                    repeat(sampleWindow.speakerCount) { speaker ->
                        val probability = sampleWindow.probabilities[localFrame * sampleWindow.speakerCount + speaker]
                        append('\t').append(probability.toRawBits().toUInt().toString(16).padStart(8, '0'))
                            .append(',').append(probability.toString())
                    }
                    append('\n')
                }
            }
        }
        File(output, "diar-progress-frames.tsv").writeText(progressFrameRows, StandardCharsets.UTF_8)

        val progressRows = buildString {
            appendLine("eventSequence\tprocessedPcmBytes\tprocessedAudioMs\tcaptureElapsedWallMs\tworkerElapsedMs\tfeedIntervalWallMs\ttotalFrames\tstableFrames\tpssKb")
            result.diarProgress.forEach { sample ->
                append(sample.eventSequence).append('\t').append(sample.processedPcmBytes).append('\t')
                    .append(sample.processedAudioMs).append('\t').append(sample.captureElapsedWallMs).append('\t')
                    .append(sample.elapsedMs).append('\t').append(sample.feedIntervalWallMs).append('\t')
                    .append(sample.totalFrames).append('\t')
                    .append(sample.stableFrames).append('\t').append(sample.pssKb).append('\n')
            }
        }
        File(output, "diar-progress.tsv").writeText(progressRows, StandardCharsets.UTF_8)

        val wordRows = buildString {
            appendLine("utteranceId\ttext\tstartMs\tendMs\tchannel\ttest4FrameGateReason")
            wordDiagnostics.forEach { diagnostic ->
                val word = diagnostic.word
                val reason = when {
                    word.channel in 1..8 -> "assigned"
                    alignmentBroken -> "alignmentBroken+${test4UnknownReason(word, diarization)}"
                    else -> test4UnknownReason(word, diarization)
                }
                append(diagnostic.utteranceId).append('\t').append(escapeTsv(word.text)).append('\t')
                    .append(word.startMs).append('\t').append(word.endMs).append('\t')
                    .append(word.channel).append('\t').append(reason).append('\n')
            }
        }
        File(output, "test4-word-classification.tsv").writeText(wordRows, StandardCharsets.UTF_8)
        File(output, "README.txt").writeText(
            "Test-only replay capture from the public synthetic French ABCA fixture. The test4 reference " +
            "assembler source is pinned in metadata.txt. handy-full-text.utf8 is authoritative text; " +
            "handy-token-bytes.bin plus handy-tokens.tsv preserve original token bytes and timestamps. " +
            "diarization-frames.tsv preserves each final Float32 probability row, and " +
            "diar-progress-frames.tsv preserves the same window at each two-second capture checkpoint. " +
            "handy-history.tsv stores each successive live Handy snapshot (Base64 fields decode to raw UTF-8/token bytes); " +
            "events.tsv orders Handy and diarization checkpoints by capture sequence and records processed PCM bytes. " +
            "test4-history-replay.tsv shows the frozen test4 assembler state after replaying each event in that order; " +
            "feedIntervalWallMs measures accumulated elapsedRealtime call durations, not thread CPU time. " +
            "No private recording is included.\n",
            StandardCharsets.UTF_8,
        )

        val zip = File(output, "replay.zip")
        ZipOutputStream(FileOutputStream(zip)).use { archive ->
            output.listFiles()?.filter { it.isFile && it != zip }?.sortedBy { it.name }?.forEach { file ->
                archive.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(archive) }
                archive.closeEntry()
            }
        }
        return output
    }

    private fun escapeTsv(value: String): String = value.replace("\\", "\\\\")
        .replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")

    private fun apkEntryIdentity(apk: File, entryName: String): String {
        require(apk.isFile) { "Installed base APK is missing: ${apk.absolutePath}" }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: return "$entryName missing from installed base APK"
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    byteCount += count
                    digest.update(buffer, 0, count)
                }
            }
            val hex = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "$entryName bytes=$byteCount sha256=$hex"
        }
    }

    private fun feedPacedPcm(
        pcm: ByteArray,
        accept: (ByteArray) -> Unit,
        snapshot: () -> String,
        onSnapshot: ((audioBytes: Int, elapsedWallMs: Long, text: String) -> Unit)? = null,
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
            val snapshotEndNanos = SystemClock.elapsedRealtimeNanos()
            snapshotCallNanos += snapshotEndNanos - snapshotStartNanos
            snapshotCount++
            val snapshotElapsedWallMs = (snapshotEndNanos - startedAtNanos) / 1_000_000L
            if (firstTextMs < 0L && latestText.isNotEmpty()) firstTextMs = snapshotElapsedWallMs
            onSnapshot?.invoke(offset, snapshotElapsedWallMs, latestText)
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

    private fun copyHandyTokenWindow(window: HandyTokenWindow): HandyTokenWindow = window.copy(
        fullTextUtf8 = window.fullTextUtf8.copyOf(),
        tokenBytes = window.tokenBytes.copyOf(),
        tokenByteEnds = window.tokenByteEnds.copyOf(),
        tokenStartsMs = window.tokenStartsMs.copyOf(),
        tokenEndsMs = window.tokenEndsMs.copyOf(),
    )

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
        val diarProgress: List<DiarProgressSample> = emptyList(),
        val handyHistory: List<HandyHistorySample> = emptyList(),
        val timelineEvents: List<ReplayTimelineEvent> = emptyList(),
        val timingsMs: Map<String, Long> = emptyMap(),
    )

    private data class HandyHistorySample(
        val eventSequence: Long,
        val kind: String,
        val processedPcmBytes: Long,
        val elapsedWallMs: Long,
        val window: HandyTokenWindow,
    )

    private data class ReplayTimelineEvent(
        val eventSequence: Long,
        val kind: String,
        val elapsedWallMs: Long,
        val processedPcmBytes: Long,
        val referenceIndex: Int,
    )

    private data class HistoryReplayState(
        val eventSequence: Long,
        val kind: String,
        val processedPcmBytes: Long,
        val alignmentBroken: Boolean,
        val updateCount: Int,
        val latestWordCount: Int,
        val timedWordCount: Int,
        val unknownChannelWordCount: Int,
    )

    private data class HistoryReplaySummary(
        val states: List<HistoryReplayState>,
        val firstBrokenEvent: ReplayTimelineEvent?,
        val finalAlignmentBroken: Boolean,
        val finalTimedWordCount: Int,
        val finalUnknownChannelWordCount: Int,
    )

    private data class Test4WordDiagnostic(
        val utteranceId: Long,
        val word: MeetingWord,
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
        val diarProgress: List<DiarProgressSample>,
    )

    private data class DiarProgressSample(
        val eventSequence: Long,
        val processedPcmBytes: Long,
        val captureElapsedWallMs: Long,
        val processedAudioMs: Long,
        val elapsedMs: Long,
        val totalFrames: Long,
        val stableFrames: Long,
        val feedIntervalWallMs: Long,
        val pssKb: Long,
        val window: DiarizationFrameWindow,
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
        const val DIAR_PROGRESS_INTERVAL_SECONDS = 2L
        const val SNAPSHOT_TOKENS = 8192
        // Mirrors the coordinator's 120-second audio retention at 20 ms / 640-byte chunks.
        const val DIAR_QUEUE_CAPACITY = 6_000
        const val DIAR_WORKER_JOIN_MS = 120_000L
        const val WORKER_CLEANUP_JOIN_MS = 5_000L
        const val SUSTAINED_REPEAT_COUNT = 5
        const val TEST4_ASSEMBLER_SOURCE_COMMIT = "fd65d3e"
        const val TEST4_ASSEMBLER_SOURCE_SHA256 = "3b2481b07cf110f9fe42b667f69db3f87307f9d9298955835e65d40d47292156"
        const val TEST4_MIN_SPEAKER_PROBABILITY = 0.5f
        const val TEST4_FLOAT32_BOUNDARY_TOLERANCE = 6.0e-8
        const val TEST4_MIN_FRAME_POSITION_TOLERANCE = 1.0e-9
        const val EXPECTED_TRANSCRIPT =
            "Bonjour, ouvrons la réunion de la semaine, je propose jeudi matin pour le suivi d'accord merci, nous reprenons jeudi."
        val LEXICAL_WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-‐‑][\\p{L}\\p{M}\\p{N}]+)*")
    }
}
