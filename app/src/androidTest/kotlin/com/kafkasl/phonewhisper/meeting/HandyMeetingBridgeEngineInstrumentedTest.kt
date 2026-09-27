package com.kafkasl.phonewhisper.meeting

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end Android proof for the additive Handy bridge through the real MeetingEngine worker. */
@RunWith(AndroidJUnit4::class)
class HandyMeetingBridgeEngineInstrumentedTest {
    @Test(timeout = 300_000L)
    fun handy_bridge_through_engine_publishes_live_text_and_preserves_final_projection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val modelDirectory = File("/data/local/tmp/dictai-conversation-20260927")
        val handyModel = modelDirectory.resolve(HANDY_MODEL_NAME)
        val diarModel = modelDirectory.resolve(DIAR_MODEL_NAME)
        assertArtifact(handyModel, HANDY_MODEL_BYTES, HANDY_MODEL_SHA256)
        assertArtifact(diarModel, DIAR_MODEL_BYTES, DIAR_MODEL_SHA256)

        val wav = instrumentation.context.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        assertEquals(FIXTURE_SHA256, sha256(wav))
        val pcm = extractMono16KhzPcm16(wav)
        assertTrue(pcm.isNotEmpty() && pcm.size % 2 == 0)
        val audioDurationMs = pcm.size * 1_000L / PCM_BYTES_PER_SECOND
        assertEquals(12_780L, audioDurationMs)
        assertTrue("integration proof requires the ARM64 test runtime", Build.SUPPORTED_ABIS.contains("arm64-v8a"))

        val ready = CountDownLatch(1)
        val callbackFailure = AtomicReference<String?>(null)
        val hypotheses = ConcurrentLinkedQueue<ObservedHypothesis>()
        val firstTextMs = AtomicLong(-1L)
        val firstAttributedUpdateMs = AtomicLong(-1L)
        val firstVoiceRevisionMs = AtomicLong(-1L)
        val firstText = AtomicReference<String?>(null)
        val feedStartNanos = AtomicLong(0L)
        val callbackLock = Any()
        val previousCallbackHypothesisByUtterance = mutableMapOf<Long, MeetingHypothesis>()
        val engine = MeetingEngine(
            asrPath = handyModel.absolutePath,
            diarPath = diarModel.absolutePath,
            cacheDir = instrumentation.targetContext.cacheDir,
            native = HandyMeetingNativeBridge(),
        )
        val readyStartNanos = SystemClock.elapsedRealtimeNanos()
        val session = engine.start(
            runId = RUN_ID,
            language = LANGUAGE,
            onReady = { ready.countDown() },
            onUpdate = { hypothesis ->
                synchronized(callbackLock) {
                    val observedAt = SystemClock.elapsedRealtimeNanos()
                    val previous = previousCallbackHypothesisByUtterance.put(hypothesis.utteranceId, hypothesis)
                    val isVoiceRevision = previous != null && hypothesis.transcript == previous.transcript &&
                        (hypothesis.stableSpeakerThroughMs > previous.stableSpeakerThroughMs ||
                            hypothesis.words.map { it.channel } != previous.words.map { it.channel })
                    hypotheses.add(ObservedHypothesis(hypothesis, isVoiceRevision))

                    val startedAt = feedStartNanos.get()
                    if (startedAt > 0L) {
                        val elapsedMs = (observedAt - startedAt).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
                        if (hypothesis.transcript.isNotBlank() && firstTextMs.compareAndSet(-1L, elapsedMs)) {
                            firstText.set(hypothesis.transcript)
                        }
                        if (hypothesis.words.any { it.channel in FIRST_CHANNEL..LAST_CHANNEL }) {
                            firstAttributedUpdateMs.compareAndSet(-1L, elapsedMs)
                        }
                        if (isVoiceRevision) {
                            firstVoiceRevisionMs.compareAndSet(-1L, elapsedMs)
                        }
                    }
                }
            },
            onFailure = { message ->
                callbackFailure.compareAndSet(null, message)
                ready.countDown()
            },
        )

        var closed = false
        try {
            assertTrue("MeetingEngine must become ready", ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            callbackFailure.get()?.let { throw AssertionError("MeetingEngine failed before readiness: $it") }
            val readyMs = elapsedMsSince(readyStartNanos)
            val feedStart = SystemClock.elapsedRealtimeNanos()
            feedStartNanos.set(feedStart)
            val feed = feedPacedPcm(session, pcm)

            callbackFailure.get()?.let { throw AssertionError("MeetingEngine failed while processing PCM: $it") }
            val progressBeforeFinish = session.progress
            val voiceBeforeFinish = session.voiceProgress
            val liveUpdateCountBeforeFinish = hypotheses.size
            val firstLiveTextMs = firstTextMs.get()
            val firstAttributedLiveUpdateMs = firstAttributedUpdateMs.get()
            val firstLiveVoiceRevisionMs = firstVoiceRevisionMs.get()
            assertTrue("Handy must publish text before finish; updates=${hypotheses.size}", firstLiveTextMs >= 0L)
            assertTrue("diarization must publish an attributed word before finish; updates=${hypotheses.size}",
                firstAttributedLiveUpdateMs >= 0L)
            assertTrue("diarization must publish a voice-only revision before finish; updates=${hypotheses.size}",
                firstLiveVoiceRevisionMs >= 0L)
            assertNotNull("the first live hypothesis must contain text", firstText.get())
            assertTrue("live callbacks must have delivered at least one hypothesis", hypotheses.isNotEmpty())

            val finishRequestedAt = SystemClock.elapsedRealtimeNanos()
            val finishCallStarted = SystemClock.elapsedRealtimeNanos()
            session.finish()
            val finishCallMs = elapsedMsSince(finishCallStarted)
            session.closed.get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            closed = true
            val closeWaitMs = (SystemClock.elapsedRealtimeNanos() - finishRequestedAt) / NANOS_PER_MILLISECOND
            callbackFailure.get()?.let { throw AssertionError("MeetingEngine failed before close: $it") }

            val observedUpdates = hypotheses.toList()
            assertTrue("MeetingEngine must publish final hypothesis updates", observedUpdates.any { it.hypothesis.isFinal })
            val latestByUtterance = observedUpdates
                .map { it.hypothesis }
                .groupBy { it.utteranceId }
                .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            val finalWords = latestByUtterance.values.flatMap { it.words }
            finalWords.forEach { word ->
                assertTrue("final aligned word starts inside fixture audio: $word", word.startMs >= 0L)
                assertTrue("final aligned word has positive duration: $word", word.endMs > word.startMs)
                assertTrue("final aligned word ends inside fixture audio: $word", word.endMs <= audioDurationMs)
            }

            val reducer = MeetingTranscriptReducer(SESSION_ID, RUN_ID)
            val previousHypothesisByUtterance = mutableMapOf<Long, MeetingHypothesis>()
            val previousProjectedTextByUtterance = mutableMapOf<Long, String>()
            var preservedVoiceRevisionCount = 0
            observedUpdates.forEach { observed ->
                val hypothesis = observed.hypothesis
                val previous = previousHypothesisByUtterance[hypothesis.utteranceId]
                reducer.apply(hypothesis)
                val revisionDocument = reducer.snapshot()
                val utteranceByTurnId = revisionDocument.turns.associate { it.id to it.utteranceId }
                val projectedUtteranceText = MeetingProjection.rows(revisionDocument)
                    .filter { utteranceByTurnId[it.turnId] == hypothesis.utteranceId }
                    .joinToString(" ") { it.body.trim() }
                    .trim()
                if (previous != null && hypothesis.transcript == previous.transcript &&
                    (hypothesis.stableSpeakerThroughMs > previous.stableSpeakerThroughMs ||
                        hypothesis.words.map { it.channel } != previous.words.map { it.channel })
                ) {
                    assertEquals(
                        "voice-only revision must preserve the utterance's projected text",
                        previousProjectedTextByUtterance[hypothesis.utteranceId],
                        projectedUtteranceText,
                    )
                    preservedVoiceRevisionCount++
                }
                previousHypothesisByUtterance[hypothesis.utteranceId] = hypothesis
                previousProjectedTextByUtterance[hypothesis.utteranceId] = projectedUtteranceText
            }
            val document = reducer.snapshot()
            val projectionRows = MeetingProjection.rows(document)
            val projectedText = projectionRows.joinToString(" ") { it.body.trim() }.trim()
            assertEquals("the final reducer projection must preserve exact Handy text", EXPECTED_TRANSCRIPT, projectedText)

            val sourceWordCount = lexicalWords(EXPECTED_TRANSCRIPT).size
            val alignedWordCount = finalWords.count { it.startMs >= 0L && it.endMs > it.startMs }
            val unknownTimingWordCount = (sourceWordCount - alignedWordCount).coerceAtLeast(0)
            val attributedWords = finalWords.filter { it.channel in FIRST_CHANNEL..LAST_CHANNEL }
            val unknownChannelWordCount = finalWords.count { it.channel == UNKNOWN_CHANNEL }
            val attributedChannels = attributedWords.map { it.channel }.distinct().sorted()
            val firstAttributedWords = finalWords
                .filter { it.channel in FIRST_CHANNEL..LAST_CHANNEL }
                .sortedBy { it.startMs }
                .take(LOGGED_WORD_LIMIT)
                .joinToString(" | ") { "${it.text}@${it.startMs}-${it.endMs}:ch${it.channel}" }
            val voiceRevisionCount = observedUpdates.count { it.isVoiceRevision }
            assertTrue("the delivered callback stream must include a real voice-only revision", voiceRevisionCount > 0)
            assertTrue("the final result must contain at least one attributed word", attributedWords.isNotEmpty())
            assertEquals(
                "every detected voice-only revision must preserve its reducer-visible text",
                voiceRevisionCount,
                preservedVoiceRevisionCount,
            )
            val progressAfterClose = session.progress
            val voiceAfterClose = session.voiceProgress
            assertEquals(audioDurationMs, progressAfterClose.capturedAudioMs)
            assertEquals(audioDurationMs, progressAfterClose.processedAudioMs)
            assertEquals(0L, progressAfterClose.pendingAudioMs)
            assertTrue("all reducer timings must remain within fixture audio", document.turns
                .filter { it.timingKnown }
                .all { it.startMs >= 0L && it.endMs > it.startMs && it.endMs <= audioDurationMs })

            Log.i(LOG_TAG, "phase=engine-handy-diar-integration readyMs=$readyMs " +
                "sourceAudioMs=$audioDurationMs endOfCaptureMs=${feed.endOfCaptureMs} " +
                "pipelineDrainMs=${feed.pipelineDrainMs} " +
                "feedCallMs=${feed.feedCallNanos / NANOS_PER_MILLISECOND} maxFeedCallMs=${feed.maxFeedCallMs} " +
                "maxAsrDeadlineLagMs=${feed.maxAsrDeadlineLagMs} " +
                "firstTextMs=$firstLiveTextMs firstAttributedUpdateMs=$firstAttributedLiveUpdateMs " +
                "firstVoiceRevisionMs=$firstLiveVoiceRevisionMs firstHypothesis=\"${firstText.get()}\" " +
                "liveUpdateCountBeforeFinish=$liveUpdateCountBeforeFinish totalUpdateCount=${observedUpdates.size} " +
                "finalText=\"$projectedText\" " +
                "finishCallMs=$finishCallMs finishAndCloseWaitMs=$closeWaitMs closeComplete=${session.closed.isDone} " +
                "beforeFinishCapturedAudioMs=${progressBeforeFinish.capturedAudioMs} " +
                "beforeFinishProcessedAudioMs=${progressBeforeFinish.processedAudioMs} " +
                "beforeFinishPendingAudioMs=${progressBeforeFinish.pendingAudioMs} " +
                "capturedAudioMs=${progressAfterClose.capturedAudioMs} processedAudioMs=${progressAfterClose.processedAudioMs} " +
                "pendingAudioMs=${progressAfterClose.pendingAudioMs} maxPendingAudioMs=${feed.maxPendingAudioMs} " +
                "queuedAudioMs=${progressAfterClose.queuedAudioMs} maxQueuedAudioMs=${feed.maxQueuedAudioMs} " +
                "nativeProcessingMs=${progressAfterClose.nativeProcessingMs} " +
                "processingCostRatio=${progressAfterClose.processingCostRatio} " +
                "voiceStateBeforeFinish=${voiceBeforeFinish.state} voiceStateAfterClose=${voiceAfterClose.state} " +
                "voiceUnavailableReason=${voiceAfterClose.unavailableReason} " +
                "voicePendingAudioMs=${voiceAfterClose.pendingAudioMs} maxVoicePendingAudioMs=${feed.maxVoicePendingAudioMs} " +
                "sourceLexicalWords=$sourceWordCount alignedWords=$alignedWordCount " +
                "unknownTimingWords=$unknownTimingWordCount attributedWords=${attributedWords.size} " +
                "unknownChannelWords=$unknownChannelWordCount attributedChannels=$attributedChannels " +
                "voiceRevisionCount=$voiceRevisionCount voiceRevisionTextPreserved=$preservedVoiceRevisionCount " +
                "reducerTurns=${document.turns.size} firstAttributedWords=\"$firstAttributedWords\" " +
                "projectionExact=true")
        } finally {
            if (!closed) {
                session.cancel()
                try {
                    session.closed.get(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {
                    // Preserve the original assertion or bridge failure as the test result.
                }
            }
        }
    }

    private fun feedPacedPcm(session: MeetingSession, pcm: ByteArray): FeedMetrics {
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        var offset = 0
        var maxFeedCallNanos = 0L
        var totalFeedCallNanos = 0L
        var maxDeadlineLagNanos = 0L
        var maxPendingAudioMs = 0L
        var maxQueuedAudioMs = 0L
        var maxVoicePendingAudioMs = 0L

        while (offset < pcm.size) {
            val endOffset = minOf(offset + PCM_BYTES_PER_20_MS, pcm.size)
            val chunk = pcm.copyOfRange(offset, endOffset)
            val deadlineNanos = startedAtNanos +
                (endOffset.toLong() * NANOS_PER_SECOND / PCM_BYTES_PER_SECOND).toLong()
            waitUntil(deadlineNanos)
            val callStarted = SystemClock.elapsedRealtimeNanos()
            assertTrue("MeetingEngine refused PCM block at byte offset $offset", session.acceptPcm16(chunk, chunk.size))
            val callNanos = SystemClock.elapsedRealtimeNanos() - callStarted
            totalFeedCallNanos += callNanos
            maxFeedCallNanos = maxOf(maxFeedCallNanos, callNanos)
            maxDeadlineLagNanos = maxOf(
                maxDeadlineLagNanos,
                (SystemClock.elapsedRealtimeNanos() - deadlineNanos).coerceAtLeast(0L),
            )
            offset = endOffset

            val progress = session.progress
            val voice = session.voiceProgress
            maxPendingAudioMs = maxOf(maxPendingAudioMs, progress.pendingAudioMs)
            maxQueuedAudioMs = maxOf(maxQueuedAudioMs, progress.queuedAudioMs)
            maxVoicePendingAudioMs = maxOf(maxVoicePendingAudioMs, voice.pendingAudioMs)
        }

        val endOfCaptureMs = elapsedMsSince(startedAtNanos)
        val pipelineDrainStarted = SystemClock.elapsedRealtimeNanos()
        session.checkpoint().get(CHECKPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val pipelineDrainMs = elapsedMsSince(pipelineDrainStarted)
        return FeedMetrics(
            endOfCaptureMs = endOfCaptureMs,
            pipelineDrainMs = pipelineDrainMs,
            feedCallNanos = totalFeedCallNanos,
            maxFeedCallMs = maxFeedCallNanos / NANOS_PER_MILLISECOND,
            maxAsrDeadlineLagMs = maxDeadlineLagNanos / NANOS_PER_MILLISECOND,
            maxPendingAudioMs = maxPendingAudioMs,
            maxQueuedAudioMs = maxQueuedAudioMs,
            maxVoicePendingAudioMs = maxVoicePendingAudioMs,
        )
    }

    private fun assertArtifact(file: File, expectedBytes: Long, expectedSha256: String) {
        assertTrue("model does not exist: ${file.absolutePath}", file.isFile)
        assertEquals(expectedBytes, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actualSha = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(expectedSha256, actualSha)
    }

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

    private fun lexicalWords(text: String): List<String> =
        LEXICAL_WORD_PATTERN.findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()

    private fun waitUntil(deadlineNanos: Long) {
        while (true) {
            val remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos <= 0L) return
            LockSupport.parkNanos(remainingNanos)
            check(!Thread.currentThread().isInterrupted) { "Interrupted while pacing fixture PCM" }
        }
    }

    private fun elapsedMsSince(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / NANOS_PER_MILLISECOND

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class ObservedHypothesis(val hypothesis: MeetingHypothesis, val isVoiceRevision: Boolean)

    private data class FeedMetrics(
        val endOfCaptureMs: Long,
        val pipelineDrainMs: Long,
        val feedCallNanos: Long,
        val maxFeedCallMs: Long,
        val maxAsrDeadlineLagMs: Long,
        val maxPendingAudioMs: Long,
        val maxQueuedAudioMs: Long,
        val maxVoicePendingAudioMs: Long,
    )

    private companion object {
        const val LOG_TAG = "MeetingEngineHandy"
        const val FIXTURE_ASSET = "meeting/synthetic-fr-ABCA-16k-mono.wav"
        const val FIXTURE_SHA256 = "196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9"
        const val HANDY_MODEL_NAME = "handy.gguf"
        const val HANDY_MODEL_BYTES = 751_094_240L
        const val HANDY_MODEL_SHA256 = "b94545b313b3223fda7b2857a52681da813935c2127643d1e9ff0c23d988089c"
        const val DIAR_MODEL_NAME = "diar.gguf"
        const val DIAR_MODEL_BYTES = 107_012_128L
        const val DIAR_MODEL_SHA256 = "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1"
        const val LANGUAGE = "fr-FR"
        const val RUN_ID = "engine-handy-fixture-run"
        const val SESSION_ID = "engine-handy-fixture-session"
        const val EXPECTED_TRANSCRIPT =
            "Bonjour, ouvrons la réunion de la semaine, je propose jeudi matin pour le suivi d'accord merci, nous reprenons jeudi."
        const val PCM_BYTES_PER_SECOND = 32_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PCM_BYTES_PER_20_MS = 640
        const val READY_TIMEOUT_MS = 60_000L
        const val CHECKPOINT_TIMEOUT_MS = 120_000L
        const val CLOSE_TIMEOUT_MS = 180_000L
        const val CLEANUP_TIMEOUT_MS = 15_000L
        const val FIRST_CHANNEL = 1
        const val LAST_CHANNEL = 8
        const val UNKNOWN_CHANNEL = 0
        const val LOGGED_WORD_LIMIT = 6
        val LEXICAL_WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-‐‑][\\p{L}\\p{M}\\p{N}]+)*")
    }
}
