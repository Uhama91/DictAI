package com.kafkasl.phonewhisper.meeting

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Streams the public AMI reference through the existing standalone diarization JNI port. */
@RunWith(AndroidJUnit4::class)
class MeetingAmiDiarizationInstrumentedTest {
    @Test(timeout = 240_000L)
    fun test5_streams_ami_60s_and_scores_upstream_frame_der() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtureDir = File("/data/local/tmp/dictai-meeting-ami-test5")
        val wavFile = fixtureDir.resolve(AMI_WAV_NAME)
        val rttmFile = fixtureDir.resolve(AMI_RTTM_NAME)
        assertTrue("AMI WAV must be staged on the test emulator", wavFile.isFile)
        assertTrue("AMI RTTM must be staged on the test emulator", rttmFile.isFile)
        assertEquals(AMI_WAV_BYTES, wavFile.length())
        assertEquals(AMI_RTTM_BYTES, rttmFile.length())
        assertEquals(AMI_WAV_SHA256, sha256(wavFile))
        assertEquals(AMI_RTTM_SHA256, sha256(rttmFile))

        val pcm = extractMono16KhzPcm16(wavFile.readBytes())
        assertEquals(PCM_BYTES_PER_SECOND * AMI_AUDIO_SECONDS, pcm.size.toLong())
        val reference = parseRttm(rttmFile.readText(StandardCharsets.UTF_8))
        assertTrue("the AMI RTTM must contain reference speaker intervals", reference.isNotEmpty())
        val diarModel = File("/data/local/tmp/dictai-conversation-20260927/$DIAR_MODEL_NAME")
        assertTrue("the pinned diarization model must be staged", diarModel.isFile)
        assertEquals(DIAR_MODEL_BYTES, diarModel.length())
        assertEquals(DIAR_MODEL_SHA256, sha256(diarModel))

        val target = instrumentation.targetContext
        val installedApk = File(target.applicationInfo.sourceDir)
        val packageInfo = target.packageManager.getPackageInfo(target.packageName, 0)
        val openStartNanos = SystemClock.elapsedRealtimeNanos()
        val diar = DiarizationNative.open(diarModel.absolutePath)
        val openMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - openStartNanos)
        val pssAfterOpenKb = Debug.getPss().toLong()
        var closed = false
        try {
            val progress = mutableListOf<AmiDiarProgress>()
            val streamStartNanos = SystemClock.elapsedRealtimeNanos()
            var nextCheckpointAudioMs = CHECKPOINT_INTERVAL_SECONDS * 1_000L
            var offset = 0
            var acceptCallWallNanos = 0L
            var maxAcceptCallWallNanos = 0L
            var maxDeadlineLagNanos = 0L
            var totalSnapshotWallNanos = 0L
            while (offset < pcm.size) {
                val endOffset = min(offset + PCM_BYTES_PER_20_MS, pcm.size)
                val chunk = pcm.copyOfRange(offset, endOffset)
                val deadlineNanos = streamStartNanos +
                    (endOffset.toLong() * NANOS_PER_SECOND / PCM_BYTES_PER_SECOND)
                waitUntil(deadlineNanos)
                val acceptStartNanos = SystemClock.elapsedRealtimeNanos()
                diar.acceptPcm16(chunk, chunk.size)
                val acceptNanos = SystemClock.elapsedRealtimeNanos() - acceptStartNanos
                acceptCallWallNanos += acceptNanos
                maxAcceptCallWallNanos = max(maxAcceptCallWallNanos, acceptNanos)
                offset = endOffset
                maxDeadlineLagNanos = max(
                    maxDeadlineLagNanos,
                    (SystemClock.elapsedRealtimeNanos() - deadlineNanos).coerceAtLeast(0L),
                )

                val processedAudioMs = offset.toLong() * 1_000L / PCM_BYTES_PER_SECOND
                if (processedAudioMs >= nextCheckpointAudioMs) {
                    val snapshotStartNanos = SystemClock.elapsedRealtimeNanos()
                    val window = diar.snapshot(firstFrameIndex = 0L)
                    val snapshotWallNanos = SystemClock.elapsedRealtimeNanos() - snapshotStartNanos
                    totalSnapshotWallNanos += snapshotWallNanos
                    progress += AmiDiarProgress(
                        processedAudioMs = processedAudioMs,
                        elapsedWallMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - streamStartNanos),
                        acceptCallWallMs = nanosToMs(acceptCallWallNanos),
                        snapshotWallMs = nanosToMs(snapshotWallNanos),
                        totalFrames = window.totalFrameCount,
                        stableFrames = window.stableFrameCount,
                        retainedFirstFrame = window.firstFrameIndex,
                        retainedFrameCount = window.probabilities.size / window.speakerCount,
                        pssKb = Debug.getPss().toLong(),
                    )
                    nextCheckpointAudioMs += CHECKPOINT_INTERVAL_SECONDS * 1_000L
                }
            }

            val endOfFeedMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - streamStartNanos)
            val finishStartNanos = SystemClock.elapsedRealtimeNanos()
            diar.finish()
            val finishMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - finishStartNanos)
            val finalSnapshotStartNanos = SystemClock.elapsedRealtimeNanos()
            val finalWindow = diar.snapshot(firstFrameIndex = 0L)
            val finalSnapshotMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - finalSnapshotStartNanos)
            val pssBeforeCloseKb = Debug.getPss().toLong()

            assertEquals("the final probability window must retain the whole reference timeline",
                0L, finalWindow.firstFrameIndex)
            assertEquals("the final probability window must expose every produced frame",
                finalWindow.totalFrameCount,
                finalWindow.probabilities.size.toLong() / finalWindow.speakerCount)
            assertTrue("pinned diarizer frame cadence should be 10 ms",
                abs(finalWindow.secondsPerFrame - UPSTREAM_SCORE_FRAME_SECONDS) <= 1.0e-6)
            assertTrue("frame probabilities should cover the full 60-second PCM",
                abs(finalWindow.totalFrameCount * finalWindow.secondsPerFrame - AMI_AUDIO_SECONDS) <= 0.02)
            assertEquals("all frames should be stable after finish",
                finalWindow.totalFrameCount, finalWindow.stableFrameCount)

            val hypothesis = segmentsFromProbabilities(finalWindow)
            val scores = upstreamFrameDiarizationErrors(
                reference = reference,
                hypothesis = hypothesis,
            )
            val closeStartNanos = SystemClock.elapsedRealtimeNanos()
            diar.close()
            closed = true
            val closeMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - closeStartNanos)
            val pssAfterCloseKb = Debug.getPss().toLong()
            val appJniEntries = listOf(
                APK_MEETING_JNI_ENTRY,
                APK_TRANSCRIBE_JNI_ENTRY,
            ).joinToString("\n") { apkEntryIdentity(installedApk, it) }
            val output = File(target.cacheDir, "meeting-test5/ami-diarization-${SystemClock.elapsedRealtime()}")
            check(output.mkdirs()) { "Could not create AMI evidence directory ${output.absolutePath}" }

            File(output, "reference.rttm").writeBytes(rttmFile.readBytes())
            File(output, "hypothesis.rttm").writeText(
                hypothesis.joinToString(separator = "", transform = ::asRttmLine),
                StandardCharsets.UTF_8,
            )
            File(output, "diarization-frames.tsv").writeText(frameProbabilitiesTsv(finalWindow), StandardCharsets.UTF_8)
            File(output, "progress.tsv").writeText(progressTsv(progress), StandardCharsets.UTF_8)
            val summary = buildString {
                appendLine("format=meeting-test5-ami-diarization-v1")
                appendLine("fixture=AMI EN2002d excerpt, public 60-second WAV; no Handy ASR loaded")
                appendLine("wav=$AMI_WAV_NAME bytes=${wavFile.length()} sha256=${sha256(wavFile)}")
                appendLine("rttm=$AMI_RTTM_NAME bytes=${rttmFile.length()} sha256=${sha256(rttmFile)}")
                appendLine("pcmBytes=${pcm.size} audioSeconds=$AMI_AUDIO_SECONDS sampleRateHz=16000 channels=1 format=PCM16LE")
                appendLine("diarModel=$DIAR_MODEL_NAME bytes=${diarModel.length()} sha256=${sha256(diarModel)}")
                appendLine("sourceRevision=$NEMO_SOURCE_REVISION")
                appendLine("upstreamScorer=model_smoke.py sha256=$MODEL_SMOKE_SHA256 function=diarization_errors")
                appendLine("upstreamScorerSemantics=frame-level DER and speaker confusion, no collar, best speaker mapping")
                appendLine("hypothesisDerivation=diar_segments_from_probs with pinned native default segmentation values; " +
                    "score is diarizer-only and does not evaluate the app's per-word channel classifier")
                appendLine("segmentation.onset=$SEGMENT_ONSET offset=$SEGMENT_OFFSET padOnsetSec=$SEGMENT_PAD_ONSET " +
                    "padOffsetSec=$SEGMENT_PAD_OFFSET minDurationOnSec=$SEGMENT_MIN_DURATION_ON " +
                    "minDurationOffSec=$SEGMENT_MIN_DURATION_OFF")
                appendLine("frameSeconds=${finalWindow.secondsPerFrame} totalFrames=${finalWindow.totalFrameCount} " +
                    "stableFrames=${finalWindow.stableFrameCount} speakers=${finalWindow.speakerCount}")
                appendLine("referenceSegments=${reference.size} referenceSpeakers=${reference.map { it.speaker }.distinct().size}")
                appendLine("hypothesisSegments=${hypothesis.size} hypothesisSpeakers=${hypothesis.map { it.speaker }.distinct().size}")
                appendLine("referenceSpeakerFrames=${scores.referenceSpeakerFrames} " +
                    "missFrames=${scores.missFrames} falseAlarmFrames=${scores.falseAlarmFrames} " +
                    "confusionFrames=${scores.confusionFrames} bestMappedOverlapFrames=${scores.bestMappedOverlapFrames}")
                appendLine("scoreFrameStepSeconds=$UPSTREAM_SCORE_FRAME_SECONDS DER=${scores.der} confusion=${scores.confusionRate}")
                appendLine("openMs=$openMs endOfFeedWallMs=$endOfFeedMs acceptCallWallMs=${nanosToMs(acceptCallWallNanos)} " +
                    "maxAcceptCallWallMs=${nanosToMs(maxAcceptCallWallNanos)} maxDeadlineLagMs=${nanosToMs(maxDeadlineLagNanos)}")
                appendLine("finishMs=$finishMs finalSnapshotMs=$finalSnapshotMs closeMs=$closeMs " +
                    "checkpointSnapshotWallMs=${nanosToMs(totalSnapshotWallNanos)} closeComplete=$closed")
                appendLine("pssAfterOpenKb=$pssAfterOpenKb pssBeforeCloseKb=$pssBeforeCloseKb pssAfterCloseKb=$pssAfterCloseKb")
                appendLine("package=${target.packageName} versionName=${packageInfo.versionName} " +
                    "versionCode=${packageInfo.longVersionCode} installedApkSha256=${sha256(installedApk)}")
                appendLine("jniEntriesBegin")
                appendLine(appJniEntries)
                appendLine("jniEntriesEnd")
                appendLine("progressSamples=${progress.size} progressFile=progress.tsv hypothesisFile=hypothesis.rttm " +
                    "framesFile=diarization-frames.tsv")
            }
            File(output, "summary.txt").writeText(summary, StandardCharsets.UTF_8)

            Log.i(LOG_TAG, "phase=ami-diarization-summary audioSeconds=$AMI_AUDIO_SECONDS " +
                "openMs=$openMs endOfFeedWallMs=$endOfFeedMs acceptCallWallMs=${nanosToMs(acceptCallWallNanos)} " +
                "maxAcceptCallWallMs=${nanosToMs(maxAcceptCallWallNanos)} " +
                "maxDeadlineLagMs=${nanosToMs(maxDeadlineLagNanos)} finishMs=$finishMs " +
                "frames=${finalWindow.totalFrameCount} stableFrames=${finalWindow.stableFrameCount} " +
                "hypothesisSegments=${hypothesis.size} hypothesisSpeakers=${hypothesis.map { it.speaker }.distinct().size} " +
                "DER=${scores.der} confusion=${scores.confusionRate} " +
                "missFrames=${scores.missFrames} falseAlarmFrames=${scores.falseAlarmFrames} " +
                "confusionFrames=${scores.confusionFrames} outputDir=${output.absolutePath}")
            progress.forEach { sample ->
                Log.i(LOG_TAG, "phase=ami-diarization-progress processedAudioMs=${sample.processedAudioMs} " +
                    "elapsedWallMs=${sample.elapsedWallMs} acceptCallWallMs=${sample.acceptCallWallMs} " +
                    "snapshotWallMs=${sample.snapshotWallMs} totalFrames=${sample.totalFrames} " +
                    "stableFrames=${sample.stableFrames} retainedFirstFrame=${sample.retainedFirstFrame} " +
                    "retainedFrameCount=${sample.retainedFrameCount} pssKb=${sample.pssKb}")
            }
            assertTrue("AMI evidence must contain the RTTM score and full probability timeline",
                File(output, "summary.txt").isFile && File(output, "hypothesis.rttm").isFile &&
                    File(output, "diarization-frames.tsv").isFile && File(output, "progress.tsv").isFile)
        } finally {
            if (!closed) {
                diar.close()
                closed = true
            }
            assertTrue("native diarizer handle should be closed", closed)
        }
    }

    private fun parseRttm(contents: String): List<SpeakerSegment> = contents.lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.trim().split(Regex("\\s+"))
            require(fields.size >= 8 && fields[0] == "SPEAKER") { "Invalid RTTM row: $line" }
            val startSec = fields[3].toDouble()
            SpeakerSegment(fields[7], startSec, startSec + fields[4].toDouble())
        }
        .toList()

    /** Mirrors pinned `diar_segments_from_probs` default hysteresis/padding/merge/drop order. */
    private fun segmentsFromProbabilities(window: DiarizationFrameWindow): List<SpeakerSegment> {
        val localFrameCount = window.probabilities.size / window.speakerCount
        val durationSec = localFrameCount * window.secondsPerFrame
        val segments = mutableListOf<SpeakerSegment>()
        repeat(window.speakerCount) { speaker ->
            val perSpeaker = mutableListOf<SpeakerSegment>()
            var active = false
            var startFrame = 0
            repeat(localFrameCount) { frame ->
                val probability = window.probabilities[frame * window.speakerCount + speaker]
                if (!active && probability > SEGMENT_ONSET) {
                    active = true
                    startFrame = frame
                } else if (active && probability < SEGMENT_OFFSET) {
                    active = false
                    perSpeaker += SpeakerSegment(
                        speaker = "hyp-$speaker",
                        startSec = max(0.0, startFrame * window.secondsPerFrame - SEGMENT_PAD_ONSET),
                        endSec = min(durationSec, frame * window.secondsPerFrame + SEGMENT_PAD_OFFSET),
                    )
                }
            }
            if (active) {
                perSpeaker += SpeakerSegment(
                    speaker = "hyp-$speaker",
                    startSec = max(0.0, startFrame * window.secondsPerFrame - SEGMENT_PAD_ONSET),
                    endSec = durationSec,
                )
            }

            val merged = mutableListOf<SpeakerSegment>()
            perSpeaker.forEach { segment ->
                val previous = merged.lastOrNull()
                if (previous != null && segment.startSec - previous.endSec < SEGMENT_MIN_DURATION_OFF) {
                    merged[merged.lastIndex] = previous.copy(endSec = max(previous.endSec, segment.endSec))
                } else {
                    merged += segment
                }
            }
            segments += merged.filter { it.endSec - it.startSec >= SEGMENT_MIN_DURATION_ON }
        }
        return segments.sortedBy { it.startSec }
    }

    /** Port of `model_smoke.py:diarization_errors`, using round-to-even frame boundaries. */
    private fun upstreamFrameDiarizationErrors(
        reference: List<SpeakerSegment>,
        hypothesis: List<SpeakerSegment>,
    ): AmiDiarScore {
        val referenceFrames = speakerFrames(reference, UPSTREAM_SCORE_FRAME_SECONDS)
        val hypothesisFrames = speakerFrames(hypothesis, UPSTREAM_SCORE_FRAME_SECONDS)
        val allFrames = (referenceFrames.values.flatten() + hypothesisFrames.values.flatten()).toSet()
        val referenceSpeakers = referenceFrames.keys.toList()
        val hypothesisSpeakers = hypothesisFrames.keys.toList()
        val paddedHypothesis: List<String?> = hypothesisSpeakers +
            List((referenceSpeakers.size - hypothesisSpeakers.size).coerceAtLeast(0)) { null }
        var bestMappedOverlap = 0
        val used = BooleanArray(paddedHypothesis.size)

        fun search(refIndex: Int, mappedOverlap: Int) {
            if (refIndex == referenceSpeakers.size) {
                bestMappedOverlap = max(bestMappedOverlap, mappedOverlap)
                return
            }
            for (candidateIndex in paddedHypothesis.indices) {
                if (used[candidateIndex]) continue
                used[candidateIndex] = true
                val hypSpeaker = paddedHypothesis[candidateIndex]
                val overlap = if (hypSpeaker == null) 0 else {
                    referenceFrames.getValue(referenceSpeakers[refIndex])
                        .intersect(hypothesisFrames.getValue(hypSpeaker)).size
                }
                search(refIndex + 1, mappedOverlap + overlap)
                used[candidateIndex] = false
            }
        }
        search(refIndex = 0, mappedOverlap = 0)

        var miss = 0
        var falseAlarm = 0
        var overlap = 0
        allFrames.forEach { frame ->
            val refCount = referenceFrames.values.count { frame in it }
            val hypCount = hypothesisFrames.values.count { frame in it }
            miss += (refCount - hypCount).coerceAtLeast(0)
            falseAlarm += (hypCount - refCount).coerceAtLeast(0)
            overlap += min(refCount, hypCount)
        }
        val referenceSpeakerFrames = referenceFrames.values.sumOf { it.size }
        require(referenceSpeakerFrames > 0) { "RTTM has no scored reference frames" }
        val confusion = overlap - bestMappedOverlap
        return AmiDiarScore(
            referenceSpeakerFrames = referenceSpeakerFrames,
            missFrames = miss,
            falseAlarmFrames = falseAlarm,
            confusionFrames = confusion,
            bestMappedOverlapFrames = bestMappedOverlap,
            der = (miss + falseAlarm + confusion).toDouble() / referenceSpeakerFrames,
            confusionRate = confusion.toDouble() / referenceSpeakerFrames,
        )
    }

    private fun speakerFrames(
        segments: List<SpeakerSegment>,
        secondsPerFrame: Double,
    ): LinkedHashMap<String, Set<Int>> {
        val frames = linkedMapOf<String, MutableSet<Int>>()
        segments.forEach { segment ->
            val startFrame = round(segment.startSec / secondsPerFrame).toInt()
            val endFrame = round(segment.endSec / secondsPerFrame).toInt()
            frames.getOrPut(segment.speaker) { linkedSetOf() }.addAll(startFrame until endFrame)
        }
        return LinkedHashMap(frames.mapValues { it.value.toSet() })
    }

    private fun asRttmLine(segment: SpeakerSegment): String =
        "SPEAKER ami_en2002d_2132 1 ${"%.3f".format(java.util.Locale.ROOT, segment.startSec)} " +
            "${"%.3f".format(java.util.Locale.ROOT, segment.endSec - segment.startSec)} " +
            "<NA> <NA> ${segment.speaker} <NA> <NA>\n"

    private fun frameProbabilitiesTsv(window: DiarizationFrameWindow): String = buildString {
        append("frameIndex\tstartSec\tendSec\tstable")
        repeat(window.speakerCount) { append("\tp").append(it) }
        append('\n')
        val frameCount = window.probabilities.size / window.speakerCount
        repeat(frameCount) { frame ->
            val frameIndex = window.firstFrameIndex + frame
            append(frameIndex).append('\t')
                .append(frameIndex * window.secondsPerFrame).append('\t')
                .append((frameIndex + 1L) * window.secondsPerFrame).append('\t')
                .append(frameIndex < window.stableFrameCount)
            repeat(window.speakerCount) { speaker ->
                val value = window.probabilities[frame * window.speakerCount + speaker]
                append('\t').append(value.toRawBits().toUInt().toString(16).padStart(8, '0'))
                    .append(',').append(value)
            }
            append('\n')
        }
    }

    private fun progressTsv(progress: List<AmiDiarProgress>): String = buildString {
        appendLine("processedAudioMs\telapsedWallMs\tacceptCallWallMs\tsnapshotWallMs\ttotalFrames\tstableFrames\tretainedFirstFrame\tretainedFrameCount\tpssKb")
        progress.forEach { sample ->
            append(sample.processedAudioMs).append('\t').append(sample.elapsedWallMs).append('\t')
                .append(sample.acceptCallWallMs).append('\t').append(sample.snapshotWallMs).append('\t')
                .append(sample.totalFrames).append('\t').append(sample.stableFrames).append('\t')
                .append(sample.retainedFirstFrame).append('\t').append(sample.retainedFrameCount).append('\t')
                .append(sample.pssKb).append('\n')
        }
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
            val chunkName = ascii(wav, offset, 4)
            val length = ByteBuffer.wrap(wav, offset + 4, 4).slice().order(ByteOrder.LITTLE_ENDIAN).int
            require(length >= 0 && offset + 8L + length <= wav.size)
            val body = offset + 8
            if (chunkName == "fmt ") {
                val fmt = ByteBuffer.wrap(wav, body, length).slice().order(ByteOrder.LITTLE_ENDIAN)
                format = fmt.short.toInt() and 0xffff
                channels = fmt.short.toInt() and 0xffff
                sampleRate = fmt.int
                fmt.int
                fmt.short
                bits = fmt.short.toInt() and 0xffff
            } else if (chunkName == "data") {
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

    private fun waitUntil(deadlineNanos: Long) {
        while (true) {
            val remaining = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            if (remaining > 1_000_000L) LockSupport.parkNanos(remaining - 500_000L)
            else Thread.yield()
        }
    }

    private fun apkEntryIdentity(apk: File, entryName: String): String = ZipFile(apk).use { zip ->
        val entry = checkNotNull(zip.getEntry(entryName)) { "Installed APK lacks $entryName" }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        "$entryName bytes=${entry.size} sha256=${digest.digest().toHex()}"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun nanosToMs(nanos: Long): Long = nanos / 1_000_000L

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private data class SpeakerSegment(val speaker: String, val startSec: Double, val endSec: Double)

    private data class AmiDiarScore(
        val referenceSpeakerFrames: Int,
        val missFrames: Int,
        val falseAlarmFrames: Int,
        val confusionFrames: Int,
        val bestMappedOverlapFrames: Int,
        val der: Double,
        val confusionRate: Double,
    )

    private data class AmiDiarProgress(
        val processedAudioMs: Long,
        val elapsedWallMs: Long,
        val acceptCallWallMs: Long,
        val snapshotWallMs: Long,
        val totalFrames: Long,
        val stableFrames: Long,
        val retainedFirstFrame: Long,
        val retainedFrameCount: Int,
        val pssKb: Long,
    )

    private companion object {
        const val LOG_TAG = "MeetingAmiDiar"
        const val AMI_WAV_NAME = "ami_en2002d_2132.wav"
        const val AMI_RTTM_NAME = "ami_en2002d_2132.rttm"
        const val AMI_WAV_BYTES = 1_920_044L
        const val AMI_RTTM_BYTES = 1_472L
        const val AMI_WAV_SHA256 = "f00f92e53115a4a6724aec0ddaf9c675df6d5fabd3a489b43fffe56ba52a3ae9"
        const val AMI_RTTM_SHA256 = "40751c9fc0bb934f741789ce3fafccd2c1f9d7c112585b8b40ac177a9de13b2d"
        const val AMI_AUDIO_SECONDS = 60L
        const val PCM_BYTES_PER_SECOND = 32_000L
        const val PCM_BYTES_PER_20_MS = 640
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val CHECKPOINT_INTERVAL_SECONDS = 10L
        const val UPSTREAM_SCORE_FRAME_SECONDS = 0.01
        const val DIAR_MODEL_NAME = "diar.gguf"
        const val DIAR_MODEL_BYTES = 107_012_128L
        const val DIAR_MODEL_SHA256 = "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1"
        const val NEMO_SOURCE_REVISION = "97a15afa5caa9bce5baaa86c1184103877af4101"
        const val MODEL_SMOKE_SHA256 = "f7feeb2b71d9912c411dcb446e5bb2f9422cbc2b2c89fb9b2246217f28ff61e1"
        const val SEGMENT_ONSET = 0.641f
        const val SEGMENT_OFFSET = 0.561f
        const val SEGMENT_PAD_ONSET = 0.229
        const val SEGMENT_PAD_OFFSET = 0.079
        const val SEGMENT_MIN_DURATION_ON = 0.511
        const val SEGMENT_MIN_DURATION_OFF = 0.296
        const val APK_MEETING_JNI_ENTRY = "lib/arm64-v8a/libdictai_meeting.so"
        const val APK_TRANSCRIBE_JNI_ENTRY = "lib/arm64-v8a/libtranscribe_jni.so"
    }
}
