package com.kafkasl.phonewhisper.meeting

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Content-free CPU comparison for the real Android native ports. Drive one scenario per invocation. */
@RunWith(AndroidJUnit4::class)
class MeetingCpuComparisonInstrumentedTest {
    @Test(timeout = 420_000L)
    fun cpu_comparison_records_load_inference_backlog_and_final_text_digest() {
        val args = InstrumentationRegistry.getArguments()
        val scenario = Scenario.parse(args.getString("scenario") ?: "diar-only")
        val priority = PriorityProfile.parse(args.getString("priority") ?: "background")
        val diarThreads = args.getString("threads")?.toIntOrNull() ?: 1
        val chunkFrames = args.getString("chunkFrames")?.toIntOrNull() ?: DEFAULT_CHUNK_FRAMES
        if (scenario != Scenario.HANDY_ONLY) require(diarThreads in 1..4) { "threads must be between 1 and 4" }
        if (scenario != Scenario.HANDY_ONLY) {
            require(chunkFrames == 0 || chunkFrames == 50 || chunkFrames == 100) {
                "chunkFrames must be 0, 50, or 100"
            }
        }
        val readyTimeoutMs = args.getString("readyTimeoutMs")?.toLongOrNull() ?: DEFAULT_READY_TIMEOUT_MS
        require(readyTimeoutMs in 1_000L..MAX_READY_TIMEOUT_MS) { "readyTimeoutMs must be between 1000 and 120000" }

        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDirectory = File("/data/local/tmp/dictai-conversation-20260927")
        val handyModel = modelDirectory.resolve(HANDY_MODEL_NAME)
        val diarModel = modelDirectory.resolve(DIAR_MODEL_NAME)
        if (scenario != Scenario.DIAR_ONLY) assertArtifactSize(handyModel, HANDY_MODEL_BYTES)
        if (scenario != Scenario.HANDY_ONLY) assertArtifactSize(diarModel, DIAR_MODEL_BYTES)

        val input = readInputPcm(args.getString("audio") ?: "synthetic", args.getString("audioPath"))
        val requestedDurationMs = args.getString("durationMs")?.toLongOrNull() ?: input.durationMs
        require(requestedDurationMs in 1L..input.durationMs) { "durationMs must fit the staged audio" }
        val selectedByteCount = (requestedDurationMs * PCM_BYTES_PER_SECOND / 1_000L).toInt()
        require(selectedByteCount % PCM_BYTES_PER_20_MS == 0) { "duration must end on a 20 ms boundary" }
        val pcm = input.pcm.copyOf(selectedByteCount)
        val audioDurationMs = pcm.size * 1_000L / PCM_BYTES_PER_SECOND
        assertEquals(requestedDurationMs, audioDurationMs)
        val audioSha = args.getString("audioSha256") ?: input.sha256
        require(audioSha.matches(Regex("[0-9a-f]{64}"))) { "audioSha256 must be supplied for staged PCM" }

        val runId = "cpu-${scenario.argument}-${SystemClock.elapsedRealtime()}"
        val runCache = File(target.cacheDir, "meeting-cpu-comparison/$runId")
        check(runCache.mkdirs() || runCache.isDirectory) { "Cannot prepare private meeting benchmark cache" }
        val metrics = NativeMetrics(audioDurationMs)
        val transcript = TranscriptDigestCollector(runId)
        val feedStartedAtNanos = AtomicLong(0L)
        val native = when (scenario) {
            Scenario.DIAR_ONLY -> DiarOnlyBridge(diarThreads, priority, chunkFrames, metrics)
            Scenario.HANDY_ONLY -> HandyOnlyBridge(metrics)
            Scenario.BOTH -> createProductionBridge(
                diarThreads = diarThreads,
                priority = priority,
                chunkFrames = chunkFrames,
                metrics = metrics,
                diarizationSpoolRoot = File(runCache, "diarization-spool"),
            )
        }
        val engine = MeetingEngine(
            asrPath = handyModel.absolutePath,
            diarPath = diarModel.absolutePath,
            cacheDir = File(runCache, "engine-spool"),
            native = native,
        )

        val pssAtStartKb = Debug.getPss()
        Log.i(LOG_TAG, "phase=prepared runId=$runId scenario=${scenario.argument} " +
            "audioKind=${input.kind} audioMs=$audioDurationMs audioBytes=${pcm.size} audioSha256=$audioSha " +
            "diarThreads=${if (scenario == Scenario.HANDY_ONLY) "na" else diarThreads} " +
            "chunkFrames=${if (scenario == Scenario.HANDY_ONLY) "na" else chunkFrames} " +
            "priority=${if (scenario == Scenario.HANDY_ONLY) "na" else priority.argument} " +
            "diarModelBytes=${if (scenario == Scenario.HANDY_ONLY) "na" else diarModel.length()} " +
            "handyModelBytes=${if (scenario == Scenario.DIAR_ONLY) "na" else handyModel.length()} " +
            "captureChunkMs=20 pssKb=$pssAtStartKb")

        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val loadStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val session = engine.start(
            runId = runId,
            language = LANGUAGE,
            onReady = { ready.countDown() },
            onUpdate = { hypothesis ->
                val feedStart = feedStartedAtNanos.get()
                if (feedStart > 0L && hypothesis.transcript.isNotBlank()) {
                    metrics.noteText(hypothesis, SystemClock.elapsedRealtimeNanos() - feedStart)
                }
                transcript.apply(hypothesis)
            },
            onFailure = { message ->
                failure.compareAndSet(null, message)
                ready.countDown()
            },
        )

        var closed = false
        try {
            awaitReady(ready, failure, session, metrics, loadStartedAtNanos, readyTimeoutMs)
            val modelReadyMs = elapsedMsSince(loadStartedAtNanos)
            Log.i(LOG_TAG, "phase=models_ready runId=$runId scenario=${scenario.argument} " +
                "readyMs=$modelReadyMs handyOpenMs=${metrics.snapshot().handyOpenMs} " +
                "diarOpenMs=${metrics.snapshot().diarOpenMs} pssKb=${Debug.getPss()} " +
                "androidDiarPriority=${metrics.snapshot().androidPriority} " +
                "javaDiarPriority=${metrics.snapshot().javaPriority}")

            val pssAfterLoadKb = Debug.getPss()
            val feed = feedPacedPcm(
                session, pcm, audioDurationMs, feedStartedAtNanos, metrics, scenario,
                pssAtStartKb, pssAfterLoadKb,
            )
            val finishStartedAt = SystemClock.elapsedRealtimeNanos()
            session.finish()
            val finishCallMs = elapsedMsSince(finishStartedAt)
            awaitClosed(session, metrics, scenario, audioDurationMs)
            closed = true

            failure.get()?.let { throw AssertionError("native benchmark session failed: $it") }
            val finalProgress = session.progress
            assertEquals("all source PCM must be admitted", audioDurationMs, finalProgress.capturedAudioMs)
            assertEquals("all admitted PCM must drain before finalization", audioDurationMs, finalProgress.processedAudioMs)
            assertEquals(0L, finalProgress.pendingAudioMs)

            val finalText = transcript.projectedText()
            if (scenario != Scenario.DIAR_ONLY) {
                assertTrue("Handy must publish final text", finalText.isNotBlank())
                assertTrue("Handy must publish live text during capture", metrics.snapshot().firstTextMs >= 0L)
            }
            val finalTextSha = if (finalText.isBlank()) "none" else sha256(finalText.toByteArray(StandardCharsets.UTF_8))
            val finalMetrics = metrics.snapshot()
            val voice = session.voiceProgress
            val stableSpeakerThroughMs = when {
                voice.state == MeetingVoiceState.ACTIVE ->
                    (finalProgress.capturedAudioMs - voice.pendingAudioMs).coerceAtLeast(0L)
                else -> finalMetrics.diarStableThroughMs
            }
            if (scenario != Scenario.HANDY_ONLY) {
                assertEquals("diarization must accept every source PCM byte", pcm.size.toLong(), finalMetrics.diarAcceptedBytes)
                assertEquals("diarization must mark every final frame stable", finalMetrics.diarTotalFrames, finalMetrics.diarStableFrames)
                assertTrue(
                    "final stable frame coverage must match source audio within one PCM block",
                    kotlin.math.abs(finalMetrics.diarStableThroughMs - audioDurationMs) <= PCM_BLOCK_MS,
                )
            }
            val drainMs = elapsedMsSince(finishStartedAt) - finishCallMs
            val captureAcceptMs = feed.captureAcceptNanos / NANOS_PER_MILLISECOND
            val handyAcceptMs = finalMetrics.handyAcceptNanos / NANOS_PER_MILLISECOND
            val diarAcceptMs = finalMetrics.diarAcceptNanos / NANOS_PER_MILLISECOND
            val modelAcceptMs = handyAcceptMs + diarAcceptMs
            Log.i(LOG_TAG, "phase=result runId=$runId scenario=${scenario.argument} " +
                "audioMs=$audioDurationMs acceptedAudioMs=${finalProgress.capturedAudioMs} " +
                "diarThreads=${if (scenario == Scenario.HANDY_ONLY) "na" else diarThreads} " +
                "chunkFrames=${if (scenario == Scenario.HANDY_ONLY) "na" else chunkFrames} " +
                "engineConsumedAudioMs=${finalProgress.processedAudioMs} " +
                "diarNativeAcceptedAudioMs=${bytesToAudioMs(finalMetrics.diarAcceptedBytes)} " +
                "voiceState=${voice.state} voiceUnavailableReason=${voice.unavailableReason} " +
                "diarFrameStableThroughMs=${finalMetrics.diarStableThroughMs} " +
                "diarStableSpeakerThroughMs=$stableSpeakerThroughMs diarPendingMs=${voice.pendingAudioMs} " +
                "diarFrames=${finalMetrics.diarTotalFrames} diarStableFrames=${finalMetrics.diarStableFrames} " +
                "firstTextMs=${finalMetrics.firstTextMs} firstAttributedUpdateMs=${finalMetrics.firstAttributedTextMs} " +
                "captureAcceptWallMs=$captureAcceptMs captureAcceptRtf=${rtf(captureAcceptMs, audioDurationMs)} " +
                "handyAcceptWallMs=$handyAcceptMs handyAcceptRtf=${rtf(handyAcceptMs, audioDurationMs)} " +
                "diarAcceptWallMs=$diarAcceptMs diarAcceptRtf=${rtf(diarAcceptMs, audioDurationMs)} " +
                "combinedModelAcceptRtf=${rtf(modelAcceptMs, audioDurationMs)} " +
                "firstHalfHandyAcceptMs=${finalMetrics.firstHalfHandyNanos / NANOS_PER_MILLISECOND} " +
                "secondHalfHandyAcceptMs=${finalMetrics.secondHalfHandyNanos / NANOS_PER_MILLISECOND} " +
                "firstHalfDiarAcceptMs=${finalMetrics.firstHalfDiarNanos / NANOS_PER_MILLISECOND} " +
                "secondHalfDiarAcceptMs=${finalMetrics.secondHalfDiarNanos / NANOS_PER_MILLISECOND} " +
                "captureElapsedMs=${feed.captureElapsedMs} finishCallMs=$finishCallMs drainMs=$drainMs " +
                "engineNativeProcessingMs=${finalProgress.nativeProcessingMs} " +
                "engineProcessingCostRatio=${finalProgress.processingCostRatio} " +
                "maxPendingAudioMs=${feed.maxPendingAudioMs} maxQueuedAudioMs=${feed.maxQueuedAudioMs} " +
                "maxVoicePendingAudioMs=${feed.maxVoicePendingAudioMs} " +
                "pssAtStartKb=${feed.pssAtStartKb} pssAfterLoadKb=${feed.pssAfterLoadKb} " +
                "pssAfterCaptureKb=${feed.pssAfterCaptureKb} pssAfterDrainKb=${Debug.getPss()} " +
                "handyFinishMs=${finalMetrics.handyFinishMs} diarFinishMs=${finalMetrics.diarFinishMs} " +
                "transcriptChars=${finalText.length} transcriptWordCount=${lexicalWordCount(finalText)} " +
                "projectedTextSha256=$finalTextSha " +
                "rawHandyTextBytes=${finalMetrics.rawHandyTextBytes} rawHandyTextSha256=${finalMetrics.rawHandyTextSha256} " +
                "updates=${transcript.updateCount()} audioSha256=$audioSha")
        } finally {
            if (!closed) {
                session.cancel()
                try {
                    session.closed.get(CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {
                    // Keep the original benchmark failure as the test result.
                }
            }
        }
    }

    private fun awaitReady(
        ready: CountDownLatch,
        failure: AtomicReference<String?>,
        session: MeetingSession,
        metrics: NativeMetrics,
        loadStartedAtNanos: Long,
        readyTimeoutMs: Long,
    ) {
        var elapsedMs = 0L
        while (elapsedMs < readyTimeoutMs) {
            if (ready.await(PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
            elapsedMs = elapsedMsSince(loadStartedAtNanos)
            val progress = session.progress
            val voice = session.voiceProgress
            val stats = metrics.snapshot()
            Log.i(LOG_TAG, "phase=load_progress elapsedMs=$elapsedMs pssKb=${Debug.getPss()} " +
                "handyOpenMs=${stats.handyOpenMs} diarOpenMs=${stats.diarOpenMs} " +
                "engineCapturedAudioMs=${progress.capturedAudioMs} voiceState=${voice.state}")
            failure.get()?.let { throw AssertionError("session failed during model preparation: $it") }
        }
        assertTrue("MeetingEngine readiness exceeded $readyTimeoutMs ms", ready.count == 0L)
        failure.get()?.let { throw AssertionError("session failed before readiness: $it") }
    }

    private fun feedPacedPcm(
        session: MeetingSession,
        pcm: ByteArray,
        audioDurationMs: Long,
        feedStartedAtNanos: AtomicLong,
        metrics: NativeMetrics,
        scenario: Scenario,
        pssAtStartKb: Long,
        pssAfterLoadKb: Long,
    ): FeedMetrics {
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        feedStartedAtNanos.set(startedAtNanos)
        var offset = 0
        var captureAcceptNanos = 0L
        var maxPendingAudioMs = 0L
        var maxQueuedAudioMs = 0L
        var maxVoicePendingAudioMs = 0L
        var nextProgressMs = PROGRESS_INTERVAL_MS

        while (offset < pcm.size) {
            val endOffset = minOf(offset + PCM_BYTES_PER_20_MS, pcm.size)
            val chunk = pcm.copyOfRange(offset, endOffset)
            val deadlineNanos = startedAtNanos + endOffset.toLong() * NANOS_PER_SECOND / PCM_BYTES_PER_SECOND
            waitUntil(deadlineNanos)
            val callStartedAt = SystemClock.elapsedRealtimeNanos()
            assertTrue("MeetingEngine refused source PCM at byte $offset", session.acceptPcm16(chunk, chunk.size))
            captureAcceptNanos += SystemClock.elapsedRealtimeNanos() - callStartedAt
            offset = endOffset

            val sourceAudioMs = offset * 1_000L / PCM_BYTES_PER_SECOND
            val progress = session.progress
            val voice = session.voiceProgress
            maxPendingAudioMs = maxOf(maxPendingAudioMs, progress.pendingAudioMs)
            maxQueuedAudioMs = maxOf(maxQueuedAudioMs, progress.queuedAudioMs)
            maxVoicePendingAudioMs = maxOf(maxVoicePendingAudioMs, voice.pendingAudioMs)
            if (sourceAudioMs >= nextProgressMs) {
                val stats = metrics.snapshot()
                val stableSpeakerThroughMs = if (voice.state == MeetingVoiceState.ACTIVE) {
                    (progress.capturedAudioMs - voice.pendingAudioMs).coerceAtLeast(0L)
                } else {
                    stats.diarStableThroughMs
                }
                Log.i(LOG_TAG, "phase=feed_progress sourceAudioMs=$sourceAudioMs " +
                    "acceptedAudioMs=${progress.capturedAudioMs} engineProcessedAudioMs=${progress.processedAudioMs} " +
                    "enginePendingAudioMs=${progress.pendingAudioMs} handyNativeAcceptedAudioMs=${bytesToAudioMs(stats.handyAcceptedBytes)} " +
                    "diarNativeAcceptedAudioMs=${bytesToAudioMs(stats.diarAcceptedBytes)} " +
                    "voiceState=${voice.state} diarFrameStableThroughMs=${stats.diarStableThroughMs} " +
                    "diarStableSpeakerThroughMs=$stableSpeakerThroughMs diarPendingMs=${voice.pendingAudioMs} " +
                    "diarFrames=${stats.diarTotalFrames} diarStableFrames=${stats.diarStableFrames} " +
                    "handyAcceptWallMs=${stats.handyAcceptNanos / NANOS_PER_MILLISECOND} " +
                    "diarAcceptWallMs=${stats.diarAcceptNanos / NANOS_PER_MILLISECOND} " +
                    "engineNativeProcessingMs=${progress.nativeProcessingMs} " +
                    "maxPendingAudioMs=$maxPendingAudioMs maxQueuedAudioMs=$maxQueuedAudioMs " +
                    "maxVoicePendingAudioMs=$maxVoicePendingAudioMs pssKb=${Debug.getPss()} scenario=${scenario.argument}")
                nextProgressMs += PROGRESS_INTERVAL_MS
            }
        }
        val pssAtCaptureEndKb = Debug.getPss()
        return FeedMetrics(
            captureElapsedMs = elapsedMsSince(startedAtNanos),
            captureAcceptNanos = captureAcceptNanos,
            maxPendingAudioMs = maxPendingAudioMs,
            maxQueuedAudioMs = maxQueuedAudioMs,
            maxVoicePendingAudioMs = maxVoicePendingAudioMs,
            pssAtStartKb = pssAtStartKb,
            pssAfterLoadKb = pssAfterLoadKb,
            pssAfterCaptureKb = pssAtCaptureEndKb,
        )
    }

    private fun awaitClosed(session: MeetingSession, metrics: NativeMetrics, scenario: Scenario, audioDurationMs: Long) {
        val drainStartedAt = SystemClock.elapsedRealtimeNanos()
        while (!session.closed.isDone) {
            try {
                session.closed.get(PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                val elapsedMs = elapsedMsSince(drainStartedAt)
                val progress = session.progress
                val voice = session.voiceProgress
                Log.i(LOG_TAG, "phase=drain_progress elapsedMs=$elapsedMs scenario=${scenario.argument} " +
                    "audioMs=$audioDurationMs capturedAudioMs=${progress.capturedAudioMs} " +
                    "processedAudioMs=${progress.processedAudioMs} pendingAudioMs=${progress.pendingAudioMs} " +
                    "diarAcceptedAudioMs=${bytesToAudioMs(metrics.snapshot().diarAcceptedBytes)} " +
                    "voiceState=${voice.state} voicePendingAudioMs=${voice.pendingAudioMs} pssKb=${Debug.getPss()}")
                assertTrue("MeetingEngine drain exceeded $DRAIN_TIMEOUT_MS ms", elapsedMs < DRAIN_TIMEOUT_MS)
            }
        }
        session.closed.get(1L, TimeUnit.SECONDS)
    }

    private fun createProductionBridge(
        diarThreads: Int,
        priority: PriorityProfile,
        chunkFrames: Int,
        metrics: NativeMetrics,
        diarizationSpoolRoot: File,
    ): MeetingNativeBridge = HandyMeetingNativeBridge(
        handyFactory = { path, language -> openMeasuredHandy(path, language, metrics) },
        diarizationFactory = { path -> openMeasuredDiarization(path, diarThreads, chunkFrames, metrics) },
        setDiarWorkerPriority = { applyDiarizationPriority(priority, metrics) },
        workerFactory = { runnable, name ->
            Thread(runnable, name).apply {
                isDaemon = true
                this.priority = priority.javaPriority
            }
        },
        diarizationSpoolRoot = diarizationSpoolRoot,
    )

    private fun openMeasuredHandy(path: String, language: String, metrics: NativeMetrics): HandyAsrPort {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val opened = HandyMeetingNative.open(path, language)
        metrics.noteHandyOpen(SystemClock.elapsedRealtimeNanos() - startedAt)
        return MeasuredHandyPort(opened, metrics)
    }

    private fun openMeasuredDiarization(
        path: String,
        threads: Int,
        chunkFrames: Int,
        metrics: NativeMetrics,
    ): DiarizationSessionPort {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val opened = DiarizationNative.open(path, threads, chunkFrames)
        metrics.noteDiarOpen(SystemClock.elapsedRealtimeNanos() - startedAt)
        return MeasuredDiarizationPort(opened, metrics)
    }

    private fun applyDiarizationPriority(priority: PriorityProfile, metrics: NativeMetrics) {
        Thread.currentThread().priority = priority.javaPriority
        Process.setThreadPriority(priority.androidPriority)
        metrics.noteDiarizationWorkerPriority(
            androidPriority = Process.getThreadPriority(Process.myTid()),
            javaPriority = Thread.currentThread().priority,
        )
    }

    private fun assertArtifactSize(file: File, expectedBytes: Long) {
        assertTrue("model does not exist: ${file.absolutePath}", file.isFile)
        assertEquals(expectedBytes, file.length())
    }

    private fun readInputPcm(kind: String, devicePath: String?): InputPcm {
        return when (kind) {
            "synthetic" -> {
                val wav = InstrumentationRegistry.getInstrumentation().context.assets
                    .open(SYNTHETIC_FR_ASSET).use { it.readBytes() }
                val digest = sha256(wav)
                assertEquals(SYNTHETIC_FR_WAV_SHA256, digest)
                InputPcm("synthetic-fr", extractMono16KhzPcm16(wav), digest)
            }
            "raw" -> {
                require(!devicePath.isNullOrBlank()) { "audioPath is required for raw PCM" }
                val file = File(devicePath)
                assertTrue("staged PCM does not exist: $devicePath", file.isFile)
                val pcm = file.readBytes()
                require(pcm.isNotEmpty() && pcm.size % PCM_BYTES_PER_20_MS == 0) {
                    "staged PCM must be non-empty and aligned to 20 ms chunks"
                }
                InputPcm("private-staged-pcm", pcm, sha256FromArgumentsOnly(kind))
            }
            else -> throw IllegalArgumentException("audio must be synthetic or raw")
        }
    }

    private fun sha256FromArgumentsOnly(kind: String): String {
        val sha = InstrumentationRegistry.getArguments().getString("audioSha256")
        require(!sha.isNullOrBlank() && sha.matches(Regex("[0-9a-f]{64}"))) {
            "audioSha256 is required for $kind input; the runner verifies it before instrumentation"
        }
        return sha
    }

    private fun extractMono16KhzPcm16(wav: ByteArray): ByteArray {
        require(wav.size >= 44 && ascii(wav, 0, 4) == "RIFF" && ascii(wav, 8, 4) == "WAVE")
        var offset = 12
        var format = -1
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var dataStart = -1
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
                dataStart = body
                dataLength = length
                break
            }
            offset = body + length + (length and 1)
        }
        require(format == 1 && channels == 1 && sampleRate == SAMPLE_RATE_HZ && bits == 16)
        require(dataStart >= 0 && dataLength > 0 && dataLength % 2 == 0)
        return wav.copyOfRange(dataStart, dataStart + dataLength)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun waitUntil(deadlineNanos: Long) {
        while (true) {
            val remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos <= 0L) return
            LockSupport.parkNanos(remainingNanos)
            check(!Thread.currentThread().isInterrupted) { "Interrupted while pacing meeting PCM" }
        }
    }

    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private fun bytesToAudioMs(bytes: Long): Long = bytes.coerceAtLeast(0L) / PCM_BYTES_PER_MILLISECOND

    private fun rtf(wallMs: Long, audioMs: Long): String =
        if (audioMs <= 0L) "na" else "%.4f".format(java.util.Locale.ROOT, wallMs.toDouble() / audioMs)

    private fun lexicalWordCount(text: String): Int = LEXICAL_WORD_PATTERN.findAll(text).count()

    private enum class Scenario(val argument: String) {
        DIAR_ONLY("diar-only"),
        HANDY_ONLY("handy-only"),
        BOTH("both");

        companion object {
            fun parse(value: String): Scenario = entries.firstOrNull { it.argument == value }
                ?: throw IllegalArgumentException("scenario must be diar-only, handy-only, or both")
        }
    }

    private enum class PriorityProfile(
        val argument: String,
        val androidPriority: Int,
        val javaPriority: Int,
    ) {
        BACKGROUND("background", Process.THREAD_PRIORITY_BACKGROUND, Thread.MIN_PRIORITY),
        NORMAL("normal", Process.THREAD_PRIORITY_DEFAULT, Thread.NORM_PRIORITY);

        companion object {
            fun parse(value: String): PriorityProfile = entries.firstOrNull { it.argument == value }
                ?: throw IllegalArgumentException("priority must be background or normal")
        }
    }

    private data class InputPcm(val kind: String, val pcm: ByteArray, val sha256: String) {
        val durationMs: Long get() = pcm.size * 1_000L / PCM_BYTES_PER_SECOND
    }

    private data class FeedMetrics(
        val captureElapsedMs: Long,
        val captureAcceptNanos: Long,
        val maxPendingAudioMs: Long,
        val maxQueuedAudioMs: Long,
        val maxVoicePendingAudioMs: Long,
        val pssAtStartKb: Long,
        val pssAfterLoadKb: Long,
        val pssAfterCaptureKb: Long,
    )

    private data class HandyState(
        val handy: HandyAsrPort,
        val assembler: MeetingHandyTranscriptAssembler,
        var acceptedBytes: Long = 0L,
    )

    private data class NativeMetricsSnapshot(
        val handyOpenMs: Long,
        val diarOpenMs: Long,
        val handyAcceptNanos: Long,
        val handyAcceptedBytes: Long,
        val firstHalfHandyNanos: Long,
        val secondHalfHandyNanos: Long,
        val handyFinishMs: Long,
        val diarAcceptNanos: Long,
        val diarAcceptedBytes: Long,
        val firstHalfDiarNanos: Long,
        val secondHalfDiarNanos: Long,
        val diarFinishMs: Long,
        val diarStableThroughMs: Long,
        val diarTotalFrames: Long,
        val diarStableFrames: Long,
        val androidPriority: Int,
        val javaPriority: Int,
        val firstTextMs: Long,
        val firstAttributedTextMs: Long,
        val rawHandyTextBytes: Int,
        val rawHandyTextSha256: String,
    )

    private class NativeMetrics(private val audioDurationMs: Long) {
        private val halfBytes = audioDurationMs * PCM_BYTES_PER_SECOND / 2_000L
        private var handyOpenMs = -1L
        private var diarOpenMs = -1L
        private var handyAcceptNanos = 0L
        private var handyAcceptedBytes = 0L
        private var firstHalfHandyNanos = 0L
        private var secondHalfHandyNanos = 0L
        private var handyFinishMs = -1L
        private var diarAcceptNanos = 0L
        private var diarAcceptedBytes = 0L
        private var firstHalfDiarNanos = 0L
        private var secondHalfDiarNanos = 0L
        private var diarFinishMs = -1L
        private var diarStableThroughMs = 0L
        private var diarTotalFrames = 0L
        private var diarStableFrames = 0L
        private var androidPriority = Int.MIN_VALUE
        private var javaPriority = Int.MIN_VALUE
        private var firstTextMs = -1L
        private var firstAttributedTextMs = -1L
        private var rawHandyTextBytes = 0
        private var rawHandyTextSha256 = "none"

        @Synchronized fun noteHandyOpen(nanos: Long) { handyOpenMs = nanos / NANOS_PER_MILLISECOND }
        @Synchronized fun noteDiarOpen(nanos: Long) { diarOpenMs = nanos / NANOS_PER_MILLISECOND }
        @Synchronized fun noteHandyFinish(nanos: Long) { handyFinishMs = nanos / NANOS_PER_MILLISECOND }
        @Synchronized fun noteDiarFinish(nanos: Long) { diarFinishMs = nanos / NANOS_PER_MILLISECOND }

        @Synchronized
        fun noteHandyAccept(bytes: Int, nanos: Long) {
            val prior = handyAcceptedBytes
            handyAcceptedBytes += bytes
            handyAcceptNanos += nanos
            val firstBytes = (halfBytes - prior).coerceIn(0L, bytes.toLong())
            firstHalfHandyNanos += nanos * firstBytes / bytes.coerceAtLeast(1)
            secondHalfHandyNanos += nanos - nanos * firstBytes / bytes.coerceAtLeast(1)
        }

        @Synchronized
        fun noteDiarAccept(bytes: Int, nanos: Long) {
            val prior = diarAcceptedBytes
            diarAcceptedBytes += bytes
            diarAcceptNanos += nanos
            val firstBytes = (halfBytes - prior).coerceIn(0L, bytes.toLong())
            firstHalfDiarNanos += nanos * firstBytes / bytes.coerceAtLeast(1)
            secondHalfDiarNanos += nanos - nanos * firstBytes / bytes.coerceAtLeast(1)
        }

        @Synchronized
        fun noteDiarWindow(window: DiarizationFrameWindow) {
            diarStableFrames = maxOf(diarStableFrames, window.stableFrameCount)
            diarTotalFrames = maxOf(diarTotalFrames, window.totalFrameCount)
            val stableMs = (window.stableFrameCount * window.secondsPerFrame * 1_000.0).roundToLong()
            diarStableThroughMs = maxOf(diarStableThroughMs, stableMs)
        }

        @Synchronized
        fun noteDiarizationWorkerPriority(androidPriority: Int, javaPriority: Int) {
            this.androidPriority = androidPriority
            this.javaPriority = javaPriority
        }

        @Synchronized
        fun noteRawHandyText(bytes: ByteArray) {
            rawHandyTextBytes = bytes.size
            rawHandyTextSha256 = sha256(bytes)
        }

        @Synchronized
        fun noteText(hypothesis: MeetingHypothesis, elapsedNanos: Long) {
            val elapsedMs = elapsedNanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND
            if (hypothesis.transcript.isNotBlank() && firstTextMs < 0L) firstTextMs = elapsedMs
            if (hypothesis.words.any { it.channel in 1..8 } && firstAttributedTextMs < 0L) {
                firstAttributedTextMs = elapsedMs
            }
        }

        @Synchronized
        fun snapshot() = NativeMetricsSnapshot(
            handyOpenMs, diarOpenMs, handyAcceptNanos, handyAcceptedBytes,
            firstHalfHandyNanos, secondHalfHandyNanos, handyFinishMs,
            diarAcceptNanos, diarAcceptedBytes, firstHalfDiarNanos, secondHalfDiarNanos,
            diarFinishMs, diarStableThroughMs, diarTotalFrames, diarStableFrames,
            androidPriority, javaPriority, firstTextMs, firstAttributedTextMs,
            rawHandyTextBytes, rawHandyTextSha256,
        )
    }

    private class MeasuredHandyPort(
        private val delegate: HandyAsrPort,
        private val metrics: NativeMetrics,
    ) : HandyAsrPort {
        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            delegate.acceptPcm16(buffer, lengthBytes)
            metrics.noteHandyAccept(lengthBytes, SystemClock.elapsedRealtimeNanos() - startedAt)
        }

        override fun snapshot(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow =
            delegate.snapshot(firstTokenIndex, maxTokens)

        override fun finish(firstTokenIndex: Int, maxTokens: Int): HandyTokenWindow {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val final = delegate.finish(firstTokenIndex, maxTokens)
            metrics.noteHandyFinish(SystemClock.elapsedRealtimeNanos() - startedAt)
            metrics.noteRawHandyText(final.fullTextUtf8)
            return final
        }

        override fun close() = delegate.close()
    }

    private inner class MeasuredDiarizationPort(
        private val delegate: DiarizationNative,
        private val metrics: NativeMetrics,
    ) : DiarizationSessionPort {
        private var acceptedBytes = 0L
        private var nextSampleMs = PROGRESS_INTERVAL_MS

        override fun acceptPcm16(buffer: ByteArray, lengthBytes: Int) {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            delegate.acceptPcm16(buffer, lengthBytes)
            val elapsed = SystemClock.elapsedRealtimeNanos() - startedAt
            metrics.noteDiarAccept(lengthBytes, elapsed)
            acceptedBytes += lengthBytes
            val processedAudioMs = bytesToAudioMs(acceptedBytes)
            if (processedAudioMs >= nextSampleMs) {
                snapshot(0L)
                nextSampleMs = (processedAudioMs / PROGRESS_INTERVAL_MS + 1L) * PROGRESS_INTERVAL_MS
            }
        }

        override fun snapshot(firstFrameIndex: Long, maxFrames: Int): DiarizationFrameWindow {
            val window = delegate.snapshot(firstFrameIndex, maxFrames)
            metrics.noteDiarWindow(window)
            return window
        }

        override fun finish() {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            delegate.finish()
            metrics.noteDiarFinish(SystemClock.elapsedRealtimeNanos() - startedAt)
        }

        override fun close() = delegate.close()
    }

    private inner class DiarOnlyBridge(
        private val threads: Int,
        private val priority: PriorityProfile,
        private val chunkFrames: Int,
        private val metrics: NativeMetrics,
    ) : MeetingNativeBridge {
        private val nextHandle = AtomicLong(1L)
        private val sessions = java.util.concurrent.ConcurrentHashMap<Long, DiarizationSessionPort>()
        private val admittedBytes = AtomicLong(0L)

        override fun open(asrPath: String, diarPath: String, language: String): Long {
            applyDiarizationPriority(priority, metrics)
            val handle = nextHandle.getAndIncrement()
            sessions[handle] = openMeasuredDiarization(diarPath, threads, chunkFrames, metrics)
            return handle
        }

        override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> {
            requireSession(handle).acceptPcm16(buffer, length)
            return emptyList()
        }

        override fun finish(handle: Long): List<MeetingNativeUpdate> {
            val diar = requireSession(handle)
            diar.finish()
            diar.snapshot(0L)
            return emptyList()
        }

        override fun close(handle: Long) { sessions.remove(handle)?.close() }

        override fun onPcmCaptured(handle: Long, buffer: ByteArray, length: Int) {
            admittedBytes.addAndGet(length.toLong())
        }

        override fun voiceProgress(handle: Long): MeetingVoiceProgress {
            val stats = metrics.snapshot()
            val admittedAudioMs = bytesToAudioMs(admittedBytes.get())
            return MeetingVoiceProgress(
                state = MeetingVoiceState.ACTIVE,
                pendingAudioMs = (admittedAudioMs - stats.diarStableThroughMs).coerceIn(0L, admittedAudioMs),
            )
        }

        private fun requireSession(handle: Long) = sessions[handle]
            ?: throw IllegalStateException("Diar-only benchmark handle is not active")
    }

    private inner class HandyOnlyBridge(private val metrics: NativeMetrics) : MeetingNativeBridge {
        private val nextHandle = AtomicLong(1L)
        private val sessions = java.util.concurrent.ConcurrentHashMap<Long, HandyState>()

        override fun open(asrPath: String, diarPath: String, language: String): Long {
            val handy = openMeasuredHandy(asrPath, language, metrics)
            val handle = nextHandle.getAndIncrement()
            sessions[handle] = HandyState(handy, MeetingHandyTranscriptAssembler())
            return handle
        }

        override fun acceptPcm16(handle: Long, buffer: ByteArray, length: Int): List<MeetingNativeUpdate> {
            val state = requireSession(handle)
            state.handy.acceptPcm16(buffer, length)
            state.acceptedBytes += length
            val window = state.handy.snapshot(state.assembler.nextSnapshotFirstTokenIndex)
            return state.assembler.update(window, bytesToAudioMs(state.acceptedBytes), isFinal = false)
        }

        override fun finish(handle: Long): List<MeetingNativeUpdate> {
            val state = requireSession(handle)
            val window = state.handy.finish(state.assembler.nextSnapshotFirstTokenIndex)
            return state.assembler.update(window, bytesToAudioMs(state.acceptedBytes), isFinal = true)
        }

        override fun close(handle: Long) { sessions.remove(handle)?.handy?.close() }

        private fun requireSession(handle: Long) = sessions[handle]
            ?: throw IllegalStateException("Handy-only benchmark handle is not active")

    }

    private class TranscriptDigestCollector(runId: String) {
        private val lock = Any()
        private val reducer = MeetingTranscriptReducer("cpu-comparison", runId)
        private var updates = 0

        fun apply(hypothesis: MeetingHypothesis) = synchronized(lock) {
            reducer.apply(hypothesis)
            updates++
        }

        fun projectedText(): String = synchronized(lock) {
            val document = reducer.snapshot()
            MeetingProjection.rows(document).joinToString(" ") { it.body.trim() }.trim()
        }

        fun updateCount(): Int = synchronized(lock) { updates }
    }

    private companion object {
        const val LOG_TAG = "MeetingCpuBench"
        const val SYNTHETIC_FR_ASSET = "meeting/synthetic-fr-ABCA-16k-mono.wav"
        const val SYNTHETIC_FR_WAV_SHA256 = "196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9"
        const val HANDY_MODEL_NAME = "handy.gguf"
        const val HANDY_MODEL_BYTES = 751_094_240L
        const val DIAR_MODEL_NAME = "diar.gguf"
        const val DIAR_MODEL_BYTES = 107_012_128L
        const val LANGUAGE = "fr-FR"
        const val SAMPLE_RATE_HZ = 16_000
        const val PCM_BYTES_PER_SECOND = 32_000L
        const val PCM_BYTES_PER_MILLISECOND = 32L
        const val PCM_BYTES_PER_20_MS = 640
        const val PCM_BLOCK_MS = 20L
        const val DEFAULT_CHUNK_FRAMES = 0
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PROGRESS_INTERVAL_MS = 5_000L
        const val DEFAULT_READY_TIMEOUT_MS = 60_000L
        const val MAX_READY_TIMEOUT_MS = 120_000L
        const val DRAIN_TIMEOUT_MS = 180_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
        val LEXICAL_WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-‐‑][\\p{L}\\p{M}\\p{N}]+)*")
    }
}
