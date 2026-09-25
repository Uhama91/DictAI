package com.kafkasl.phonewhisper.meeting

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kafkasl.phonewhisper.BuildConfig
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingNativeBridgeInstrumentedTest {
    @Test
    fun prototypeIdentityAndProviderAuthorityAreIsolated() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue("Meeting test BuildConfig must be enabled", BuildConfig.MEETING_PROTOTYPE)
        assertEquals("com.uhama.whisperpin.meetingtest", target.packageName)
        assertEquals(
            "DictAI Réunion — test",
            target.packageManager.getApplicationLabel(target.applicationInfo).toString(),
        )

        val provider = target.packageManager.resolveContentProvider(
            "${target.packageName}.note_files",
            PackageManager.GET_META_DATA,
        )
        assertNotNull("FileProvider authority must follow the isolated applicationId", provider)
        assertEquals("${target.packageName}.note_files", provider?.authority)
    }

    @Test
    fun unknownPositiveHandleIsRejectedWithoutDereferencingAnAddress() {
        val bridge = JniMeetingNative()

        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(8675309L, byteArrayOf(0, 0), 2)
        }
    }

    @Test
    fun closeOnAnUnknownHandleIsIdempotent() {
        val bridge = JniMeetingNative()

        bridge.close(8675309L)
        bridge.close(8675309L)
    }

    @Test
    fun missingModelPathFailsBeforeLoadingWeights() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingAsr = context.cacheDir.resolve("meeting-missing-${System.nanoTime()}.gguf")
            .absolutePath
        val missingDiar = context.cacheDir.resolve("meeting-diar-missing-${System.nanoTime()}.gguf")
            .absolutePath

        assertThrows(IllegalArgumentException::class.java) {
            JniMeetingNative().open(missingAsr, missingDiar, "fr")
        }
    }

    @Test
    fun facadeRejectsMalformedPcmBeforeNativeDispatch() {
        val bridge = JniMeetingNative()

        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(1L, byteArrayOf(1, 2, 3), 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.acceptPcm16(1L, byteArrayOf(1, 2), 4)
        }
    }

    @Test
    fun syntheticFrenchFixtureStreamsThroughJniAlongsideExistingRuntimes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val models = target.filesDir.resolve("meeting-t4e-models")
        val asrModel = models.resolve(ASR_MODEL_NAME)
        val diarModel = models.resolve(DIAR_MODEL_NAME)
        assertArtifact(asrModel, ASR_MODEL_BYTES, ASR_MODEL_SHA256)
        assertArtifact(diarModel, DIAR_MODEL_BYTES, DIAR_MODEL_SHA256)

        val fixture = instrumentation.context.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        assertEquals(FIXTURE_BYTES, fixture.size.toLong())
        assertEquals(FIXTURE_SHA256, sha256(fixture))
        val pcm = extractMono16KhzPcm16(fixture)
        val totalAudioMs = pcm.size * 1000L / PCM_BYTES_PER_SECOND
        assertTrue("fixture must have complete PCM16 samples", pcm.isNotEmpty() && pcm.size % 2 == 0)

        try {
            val bridge = JniMeetingNative()
            val cycleOne = runFullFixture(bridge, asrModel, diarModel, pcm, totalAudioMs)
            val cycleTwo = runSecondSession(bridge, asrModel, diarModel, pcm)
            assertNotEquals("closed native handles must not be reused", cycleOne.handle, cycleTwo.handle)
            assertTrue("a new native session must restart its utterance sequence", cycleTwo.utteranceIds.contains(1L))
            Log.i(
                LOG_TAG,
                "status=pass abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"} " +
                    "fixtureMs=$totalAudioMs fixtureBytes=${pcm.size} " +
                    "modelsLoadedInAppPrivateFiles=true preFinishTags=${cycleOne.tagsBeforeFinish.size} " +
                    "stableSequence=${cycleOne.stableSequence.joinToString(",")} " +
                    "preFinishPushedMs=${cycleOne.pushedAtStableReturnMs} " +
                    "warmupMs=${cycleOne.warmupMs} streamCallMs=${cycleOne.streamCallMs} " +
                    "streamWallMs=${cycleOne.streamWallMs} finishMs=${cycleOne.finishMs} " +
                    "secondWarmupMs=${cycleTwo.warmupMs} diarSpeakers=8 " +
                    "oldRuntimeLibrariesLoaded=$OLD_RUNTIME_LIBRARIES_COUNT " +
                    "accentedUtf8=true handlesDistinct=true utteranceIdsRestart=true",
            )
        } finally {
            models.deleteRecursively()
        }
    }

    private fun runFullFixture(
        bridge: MeetingNativeBridge,
        asrModel: File,
        diarModel: File,
        pcm: ByteArray,
        totalAudioMs: Long,
    ): CycleResult {
        val revisions = mutableMapOf<Long, Long>()
        val latest = mutableMapOf<Long, MeetingNativeUpdate>()
        val tagsBeforeFinish = mutableSetOf<Int>()
        val responseText = StringBuilder()
        var wordBeforeEof = false
        var taggedWordBeforeEof = false
        var stableAbcaBeforeEof = false
        var pushedAtStableReturnMs = -1L
        var acceptCallNs = 0L
        val openStart = SystemClock.elapsedRealtime()
        val handle = bridge.open(asrModel.absolutePath, diarModel.absolutePath, "fr")
        val warmupMs = SystemClock.elapsedRealtime() - openStart
        assertTrue("native open must return a positive registry handle", handle > 0)
        var pushedBytes = 0
        var finishMs = 0L
        val streamStart = SystemClock.elapsedRealtime()

        try {
            while (pushedBytes < pcm.size) {
                val count = minOf(PUSH_CHUNK_BYTES, pcm.size - pushedBytes)
                val chunk = pcm.copyOfRange(pushedBytes, pushedBytes + count)
                val callStart = SystemClock.elapsedRealtimeNanos()
                val updates = bridge.acceptPcm16(handle, chunk, chunk.size)
                acceptCallNs += SystemClock.elapsedRealtimeNanos() - callStart
                pushedBytes += count
                val pushedMs = pushedBytes * 1000L / PCM_BYTES_PER_SECOND
                recordUpdates(updates, revisions, latest, responseText, pushedMs, totalAudioMs)

                if (pushedMs < totalAudioMs) {
                    wordBeforeEof = wordBeforeEof || updates.any { it.words.isNotEmpty() }
                    taggedWordBeforeEof = taggedWordBeforeEof || updates.any { result ->
                        result.words.any { it.channel > 0 }
                    }
                    updates.forEach { result ->
                        result.words.filter { it.channel > 0 }.forEach { tagsBeforeFinish.add(it.channel) }
                    }
                    val stableSequence = stableSpeakerSequence(latest.values)
                    if (isAbca(stableSequence)) {
                        stableAbcaBeforeEof = true
                        pushedAtStableReturnMs = pushedMs
                    }
                }
            }

            assertTrue("JNI must return words before the fixture is fully submitted", wordBeforeEof)
            assertTrue("JNI must return a positive speaker tag before EOF", taggedWordBeforeEof)
            assertTrue("at least three distinct tags must be observed before finish", tagsBeforeFinish.size >= 3)
            val stableSequence = stableSpeakerSequence(latest.values)
            assertTrue("stable speaker order must return to its first tag as A-B-C-A before finish", stableAbcaBeforeEof)
            assertTrue("latest pre-finish stable sequence must be A-B-C-A", isAbca(stableSequence))
            assertGlobalTimestamps(latest.values, totalAudioMs)
            assertFrenchUtf8(responseText.toString())

            val streamCallMs = acceptCallNs / 1_000_000L
            val streamWallMs = SystemClock.elapsedRealtime() - streamStart
            val finishStart = SystemClock.elapsedRealtime()
            val finishUpdates = bridge.finish(handle)
            finishMs = SystemClock.elapsedRealtime() - finishStart
            recordUpdates(
                finishUpdates,
                revisions,
                latest,
                responseText,
                totalAudioMs,
                totalAudioMs,
            )
            assertThrows(IllegalStateException::class.java) {
                bridge.acceptPcm16(handle, byteArrayOf(0, 0), 2)
            }

            Log.i(
                LOG_TAG,
                "cycle=1 status=pass audioMs=$totalAudioMs pushedBeforeFinish=true " +
                    "wordBeforeEof=true speakerTagBeforeEof=true stableAbcaBeforeEof=true " +
                    "tags=${tagsBeforeFinish.sorted().joinToString(",")} " +
                    "pushedAtStableReturnMs=$pushedAtStableReturnMs warmupMs=$warmupMs " +
                "acceptCallMs=$streamCallMs streamWallMs=$streamWallMs finishMs=$finishMs " +
                    "handlePositive=true acceptAfterFinish=refused",
            )
            return CycleResult(
                handle,
                tagsBeforeFinish,
                stableSequence,
                pushedAtStableReturnMs,
                warmupMs,
                streamCallMs,
                finishMs,
                streamWallMs = streamWallMs,
            )
        } finally {
            bridge.close(handle)
            bridge.close(handle)
        }
    }

    private fun runSecondSession(
        bridge: MeetingNativeBridge,
        asrModel: File,
        diarModel: File,
        pcm: ByteArray,
    ): CycleResult {
        val revisions = mutableMapOf<Long, Long>()
        val latest = mutableMapOf<Long, MeetingNativeUpdate>()
        val responseText = StringBuilder()
        val openStart = SystemClock.elapsedRealtime()
        val handle = bridge.open(asrModel.absolutePath, diarModel.absolutePath, "fr")
        val warmupMs = SystemClock.elapsedRealtime() - openStart
        assertTrue(handle > 0)
        val feedBytes = minOf(pcm.size.toLong(), SECOND_SESSION_FEED_MS * PCM_BYTES_PER_SECOND / 1000L).toInt()
        var pushedBytes = 0
        var acceptCallNs = 0L
        try {
            while (pushedBytes < feedBytes) {
                val count = minOf(PUSH_CHUNK_BYTES, feedBytes - pushedBytes)
                val chunk = pcm.copyOfRange(pushedBytes, pushedBytes + count)
                val callStart = SystemClock.elapsedRealtimeNanos()
                val updates = bridge.acceptPcm16(handle, chunk, chunk.size)
                acceptCallNs += SystemClock.elapsedRealtimeNanos() - callStart
                pushedBytes += count
                recordUpdates(
                    updates,
                    revisions,
                    latest,
                    responseText,
                    pushedBytes * 1000L / PCM_BYTES_PER_SECOND,
                    SECOND_SESSION_FEED_MS,
                )
            }
            val finishStart = SystemClock.elapsedRealtime()
            val finishUpdates = bridge.finish(handle)
            val finishMs = SystemClock.elapsedRealtime() - finishStart
            recordUpdates(
                finishUpdates,
                revisions,
                latest,
                responseText,
                SECOND_SESSION_FEED_MS,
                SECOND_SESSION_FEED_MS,
            )
            assertThrows(IllegalStateException::class.java) {
                bridge.acceptPcm16(handle, byteArrayOf(0, 0), 2)
            }
            val utteranceIds = latest.keys
            assertTrue("fresh session utterance IDs must begin at one", utteranceIds.contains(1L))
            Log.i(
                LOG_TAG,
                "cycle=2 status=pass audioMs=${pushedBytes * 1000L / PCM_BYTES_PER_SECOND} " +
                    "warmupMs=$warmupMs acceptCallMs=${acceptCallNs / 1_000_000L} finishMs=$finishMs " +
                    "utteranceIds=${utteranceIds.sorted().joinToString(",")} handlePositive=true",
            )
            return CycleResult(
                handle,
                emptySet(),
                emptyList(),
                -1,
                warmupMs,
                acceptCallNs / 1_000_000L,
                finishMs,
                utteranceIds,
                streamWallMs = 0,
            )
        } finally {
            bridge.close(handle)
            bridge.close(handle)
        }
    }

    private fun recordUpdates(
        updates: List<MeetingNativeUpdate>,
        revisions: MutableMap<Long, Long>,
        latest: MutableMap<Long, MeetingNativeUpdate>,
        responseText: StringBuilder,
        pushedMs: Long,
        totalAudioMs: Long,
    ) {
        updates.forEach { update ->
            assertTrue("JNI utterance IDs must be positive", update.utteranceId > 0)
            assertTrue("JNI revisions must be positive", update.revision > 0)
            assertTrue("native audio clock cannot be negative", update.audioProcessedMs >= 0)
            assertTrue(
                "native audio clock cannot exceed submitted audio",
                update.audioProcessedMs <= pushedMs + 500,
            )
            val previousRevision = revisions[update.utteranceId]
            assertTrue(
                "revisions must increase for each utterance",
                previousRevision == null || update.revision > previousRevision,
            )
            revisions[update.utteranceId] = update.revision
            val previous = latest[update.utteranceId]
            if (previous == null || update.revision > previous.revision) latest[update.utteranceId] = update
            responseText.append(update.transcript)
            update.words.forEach { word ->
                assertTrue("global word start must be nonnegative", word.startMs >= 0)
                assertTrue("word end must not precede its start", word.endMs >= word.startMs)
                assertTrue(
                    "word timestamps must remain on the fixture's absolute timeline",
                    word.endMs <= totalAudioMs + 500,
                )
                responseText.append(word.text)
            }
        }
    }

    private fun stableSpeakerSequence(updates: Collection<MeetingNativeUpdate>): List<Int> {
        val tags = updates.asSequence()
            .filter { it.isFinal }
            .flatMap { update ->
                update.words.asSequence()
                    .filter { it.channel > 0 && it.endMs <= update.stableSpeakerThroughMs }
                    .map { word -> word.startMs to word.channel }
            }
            .sortedBy { it.first }
            .map { it.second }
        val sequence = mutableListOf<Int>()
        tags.forEach { tag -> if (sequence.lastOrNull() != tag) sequence += tag }
        return sequence
    }

    private fun isAbca(sequence: List<Int>): Boolean =
        sequence.size == 4 && sequence[0] == 1 && sequence[3] == 1 &&
            sequence[0] != sequence[1] && sequence[0] != sequence[2] && sequence[1] != sequence[2]

    private fun assertGlobalTimestamps(updates: Collection<MeetingNativeUpdate>, totalAudioMs: Long) {
        val finalWords = updates.asSequence()
            .filter { it.isFinal }
            .flatMap { update ->
                update.words.asSequence().filter {
                    it.channel > 0 && it.endMs <= update.stableSpeakerThroughMs
                }
            }
            .toList()
        assertTrue("stable speaker-tagged words must be returned", finalWords.isNotEmpty())
        val earliest = finalWords.minOf { it.startMs }
        val latest = finalWords.maxOf { it.startMs }
        assertTrue("first turn must remain near the start of the audio", earliest < 1_000)
        assertTrue("last turn must retain its global offset, not restart near zero", latest >= 9_000)
        assertTrue("last-turn timestamp must remain within the fixture", latest < totalAudioMs)
    }

    private fun assertFrenchUtf8(text: String) {
        assertFalse("JNI text must not contain Unicode replacement characters", text.contains('\uFFFD'))
        assertFalse("JNI text must not show common UTF-8 mojibake", text.contains("Ã") || text.contains("Â"))
        assertTrue("synthetic French accent must survive the JNI conversion", text.any { it in "éàèùûôâîç" })
    }

    private fun assertArtifact(file: File, expectedBytes: Long, expectedSha: String) {
        assertTrue("test model must exist in the isolated target package", file.isFile)
        assertEquals("test model size must match the pinned artifact", expectedBytes, file.length())
        assertEquals("test model identity must match the pinned artifact", expectedSha, sha256(file))
    }

    private fun sha256(file: File): String = file.inputStream().use { input -> sha256Stream(input) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun extractMono16KhzPcm16(wav: ByteArray): ByteArray {
        require(wav.size >= 12 && ascii(wav, 0, 4) == "RIFF" && ascii(wav, 8, 4) == "WAVE")
        var sampleFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1
        var offset = 12
        while (offset + 8 <= wav.size) {
            val chunkName = ascii(wav, offset, 4)
            val chunkSize = ByteBuffer.wrap(wav, offset + 4, 4).slice()
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            require(chunkSize <= wav.size - offset - 8L) { "WAV chunk exceeds fixture bounds" }
            val bodyOffset = offset + 8
            if (chunkName == "fmt ") {
                require(chunkSize >= 16)
                val format = ByteBuffer.wrap(wav, bodyOffset, chunkSize.toInt()).slice()
                    .order(ByteOrder.LITTLE_ENDIAN)
                sampleFormat = format.short.toInt() and 0xFFFF
                channels = format.short.toInt() and 0xFFFF
                sampleRate = format.int
                format.int // byte rate
                format.short // block alignment
                bitsPerSample = format.short.toInt() and 0xFFFF
            } else if (chunkName == "data") {
                dataOffset = bodyOffset
                dataSize = chunkSize.toInt()
                break
            }
            offset = bodyOffset + chunkSize.toInt() + (chunkSize.toInt() and 1)
        }
        require(sampleFormat == 1 && channels == 1 && sampleRate == 16_000 && bitsPerSample == 16)
        require(dataOffset >= 0 && dataSize > 0 && dataSize % 2 == 0)
        return wav.copyOfRange(dataOffset, dataOffset + dataSize)
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, StandardCharsets.US_ASCII)

    private data class CycleResult(
        val handle: Long,
        val tagsBeforeFinish: Set<Int>,
        val stableSequence: List<Int>,
        val pushedAtStableReturnMs: Long,
        val warmupMs: Long,
        val streamCallMs: Long,
        val finishMs: Long,
        val utteranceIds: Set<Long> = emptySet(),
        val streamWallMs: Long = 0,
    )

    companion object {
        private const val LOG_TAG = "MeetingT4E"
        private const val FIXTURE_ASSET = "meeting/synthetic-fr-ABCA-16k-mono.wav"
        private const val FIXTURE_BYTES = 409_046L
        private const val FIXTURE_SHA256 = "196439ff592c6e6a794c1914804dd996c428f378ab7dd0ff372bfb55baa521d9"
        private const val ASR_MODEL_NAME = "nemotron-3.5-asr-streaming-0.6b.q8_0.gguf"
        private const val ASR_MODEL_BYTES = 741_548_352L
        private const val ASR_MODEL_SHA256 = "a5c435f294eea8f88ce68dd27b8c3bfea7f777cb2fbba04fcd30eaa555f429ae"
        private const val DIAR_MODEL_NAME = "Nemotron-3-Diarization.q8_0.gguf"
        private const val DIAR_MODEL_BYTES = 107_012_128L
        private const val DIAR_MODEL_SHA256 = "08456d9e22cd9a323c0364d98375f3746d6e68507ebb705cd46438c534c7a3a1"
        private const val PUSH_CHUNK_BYTES = 5_120
        private const val SECOND_SESSION_FEED_MS = 6_000L
        private const val PCM_BYTES_PER_SECOND = 32_000L
        private val OLD_RUNTIME_LIBRARIES = listOf(
            "libggml-base.so" to "28fb26bbcd77b709989cbd9300c882031d61a16cdcace2d58e3aa399ac10a9e2",
            "libggml-cpu.so" to "f6d0c48bca18f4138bd5d232a0ac3504f96abeaa22c5598014729db1d3fd22d5",
            "libggml.so" to "a041d176dfb8129a200bd6eb1cef9403481b8f31901c1db70bcb4b8ac923399b",
            "libonnxruntime.so" to "994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533",
            "libsherpa-onnx-c-api.so" to "d8b54c7814aaaaaf014793323a06d5a0578614f36ef7321fab7f394e9bba0eab",
            "libsherpa-onnx-cxx-api.so" to "5c55b5fc02057565fe90f26c2f9ace67b7a6676a56d2dcac51fd7c004abe5d0c",
            "libsherpa-onnx-jni.so" to "a79ff75fbe1c3813cc239037b458a7828298a90a5b77f5314056508eefdf72bc",
            "libtranscribe.so" to "e7878a83c00e70a11ac8bc05d6ba43685fb0edea865dcdd81f52e79442a5c4e4",
            "libtranscribe_jni.so" to "32e543d9f18ad3bcae32ab83622fc45f83589e9433960bc1a650007ea5d1954e",
        )
        private var OLD_RUNTIME_LIBRARIES_COUNT = 0

        private fun sha256Stream(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }

        @BeforeClass
        @JvmStatic
        fun loadExistingRuntimesBeforeAnyMeetingBridgeUse() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue("T4e must run on the selected arm64 AVD", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
            val apkFile = File(context.applicationInfo.sourceDir)
            ZipFile(apkFile).use { apk ->
                OLD_RUNTIME_LIBRARIES.forEach { (libraryName, expectedSha256) ->
                    val entry = apk.getEntry("lib/arm64-v8a/$libraryName")
                        ?: throw AssertionError("existing Dictation runtime library must be packaged: $libraryName")
                    val actualSha256 = apk.getInputStream(entry).use { input -> sha256Stream(input) }
                    assertEquals(
                        "existing runtime bytes must remain unchanged: $libraryName",
                        expectedSha256,
                        actualSha256,
                    )
                }
            }
            OLD_RUNTIME_LIBRARIES.forEach { (libraryName, _) ->
                System.loadLibrary(libraryName.removePrefix("lib").removeSuffix(".so"))
            }
            OLD_RUNTIME_LIBRARIES_COUNT = OLD_RUNTIME_LIBRARIES.size
        }
    }
}
