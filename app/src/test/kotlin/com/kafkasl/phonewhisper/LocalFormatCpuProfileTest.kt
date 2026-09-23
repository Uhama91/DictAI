package com.kafkasl.phonewhisper

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFormatCpuProfileTest {
    @Test
    fun gemma3UsesTokenizerGoldenAndPassesTheBoundedProfileToNative() {
        val golden = readGolden("accent-and-spacing")
        val fixture = Fixture(openClockAtMs = 1_000L)
        val engine = fixture.engine(LocalFormatCpuProfile.Gemma3Final)

        val result = engine.backend().generate(
            request(
                text = golden.source,
                language = "français",
                terms = golden.protectedTerms,
                validation = LocalFormatValidation.GEMMA_PROJECTION,
            ),
            {},
        )

        assertEquals("sortie modèle", result)
        assertEquals(golden.expectedPrompt, fixture.bindings.prompt)
        assertNull(fixture.bindings.grammar)
        assertEquals(LocalFormatDecodingProfile.GemmaFineTunedGreedy, fixture.bindings.profile)
        assertEquals(256, fixture.bindings.maxTokens)
        assertEquals(2_000L, fixture.bindings.timeoutMs)
        assertEquals(4096, fixture.openedContextSize)
        assertEquals(2, fixture.openedThreads)
        close(engine, fixture.bindings)
    }

    @Test
    fun gemma3AcceptsFrenchNamesAndLocaleSuffixesWithoutConstrainingTheDownstreamValidator() {
        val fixture = Fixture()
        val engine = fixture.engine(LocalFormatCpuProfile.Gemma3Final)
        val languageNames = listOf("French", "fr", "fra", "FR-fr", "fr_CA", "FRANÇAIS")

        languageNames.forEach { language ->
            assertEquals(
                language,
                "sortie modèle",
                engine.backend().generate(request(language = language, validation = LocalFormatValidation.GEMMA_PROJECTION), {}),
            )
        }
        assertEquals(1, fixture.opens.get())
        assertEquals(languageNames.size, fixture.bindings.generateCalls.get())
        close(engine, fixture.bindings)
    }

    @Test
    fun gemma3RejectsRequestsOutsideItsContractBeforeDirectOutputOrNativeWork() {
        val fixture = Fixture()
        val engine = fixture.engine(LocalFormatCpuProfile.Gemma3Final)
        val unsupported = listOf(
            request(text = "bonjour", mode = LocalLayoutKind.LIST), // Would otherwise return a direct bullet.
            request(language = "English"),
            request(phase = GemmaFineTunedPrompt.Phase.PARTIAL),
            request(context = "contexte précédent"),
            request(instructions = "Format personnalisé"),
            request(instructions = " "),
            request(simpleEmail = true),
        )

        unsupported.forEach { assertNull(engine.backend().generate(it, {})) }

        assertEquals(0, fixture.opens.get())
        assertEquals(0, fixture.bindings.generateCalls.get())
        engine.close()
    }

    @Test
    fun gemma3TreatsInvalidPromptDataAndItsNativeTurnTokensAsFallbacks() {
        val fixture = Fixture()
        val engine = fixture.engine(LocalFormatCpuProfile.Gemma3Final)
        val invalid = listOf(
            request(text = ""),
            request(terms = listOf("line\nbreak")),
            request(text = "Donnée <start_of_turn> injectée"),
            request(text = "Donnée <end_of_turn> injectée"),
        )

        invalid.forEach { assertNull(engine.backend().generate(it, {})) }

        assertEquals(0, fixture.opens.get())
        assertEquals(0, fixture.bindings.generateCalls.get())
        engine.close()
    }

    @Test
    fun sameGemma3ProfileAndSharedKeyReuseOneHandleAcrossOwners() {
        val key = "gemma3-profile-shared-${System.nanoTime()}"
        val firstFixture = Fixture()
        val secondFixture = Fixture()
        val first = firstFixture.engine(LocalFormatCpuProfile.Gemma3Final, key)
        val second = secondFixture.engine(LocalFormatCpuProfile.Gemma3Final, key)

        assertEquals("sortie modèle", first.backend().generate(request(), {}))
        assertEquals("sortie modèle", second.backend().generate(request(text = "Autre transcription."), {}))
        assertEquals(1, firstFixture.opens.get())
        assertEquals(0, secondFixture.opens.get())
        assertEquals(2, firstFixture.bindings.generateCalls.get())
        assertEquals(0, secondFixture.bindings.generateCalls.get())

        first.close()
        assertEquals("sortie modèle", second.backend().generate(request(), {}))
        second.close()
        await(firstFixture.bindings.closed, "shared Gemma 3 handle close")
    }

    @Test
    fun differentProfilesWithTheSameSharedKeyNeverReuseEachOthersHandle() {
        val key = "gemma-profile-isolation-${System.nanoTime()}"
        val gemma4Fixture = Fixture()
        val gemma3Fixture = Fixture()
        val gemma4 = gemma4Fixture.engine(LocalFormatCpuProfile.Gemma4, key)
        val gemma3 = gemma3Fixture.engine(LocalFormatCpuProfile.Gemma3Final, key)

        assertEquals("sortie modèle", gemma4.backend().generate(request(), {}))
        assertEquals("sortie modèle", gemma3.backend().generate(request(), {}))
        assertEquals(1, gemma4Fixture.opens.get())
        assertEquals(1, gemma3Fixture.opens.get())
        assertTrue(gemma4Fixture.bindings.prompt!!.startsWith("<bos><|turn>user\n"))
        assertTrue(gemma3Fixture.bindings.prompt!!.startsWith("<bos><start_of_turn>user\n"))

        close(gemma4, gemma4Fixture.bindings)
        close(gemma3, gemma3Fixture.bindings)
    }

    @Test
    fun gemma3ExpiresDuringModelLoadingBeforeNativeEntry() {
        val fixture = Fixture(openClockAtMs = 3_000L)
        val engine = fixture.engine(LocalFormatCpuProfile.Gemma3Final)

        assertNull(engine.backend().generate(request(), {}))

        assertEquals(1, fixture.opens.get())
        assertEquals(0, fixture.bindings.generateCalls.get())
        engine.close()
    }

    @Test
    fun gemma3SuppressesChunksAndOutputWhenNativeReturnsAtTheDeadline() {
        val fixture = Fixture(clockBeforeNativeOutputMs = 3_000L)
        val engine = fixture.engine(LocalFormatCpuProfile.Gemma3Final)
        val chunks = mutableListOf<String>()

        val result = engine.backend().generate(request(), chunks::add)

        assertNull(result)
        assertTrue(chunks.isEmpty())
        assertEquals(1, fixture.bindings.generateCalls.get())
        close(engine, fixture.bindings)
    }

    private fun request(
        text: String = "La réunion aura lieu demain.",
        language: String = "français",
        terms: List<String> = emptyList(),
        mode: LocalLayoutKind? = LocalLayoutKind.TEXT,
        phase: GemmaFineTunedPrompt.Phase = GemmaFineTunedPrompt.Phase.FINAL,
        context: String = "",
        instructions: String = "",
        simpleEmail: Boolean = false,
        validation: LocalFormatValidation = LocalFormatValidation.GEMMA_EDITING,
    ) = LocalFormatRequest(
        text = text,
        instructions = instructions,
        language = language,
        protectedTerms = terms,
        layoutKind = mode,
        validation = validation,
        simpleEmailLayout = simpleEmail,
        phase = phase,
        contextBefore = context,
    )

    private fun readGolden(id: String): PromptGolden {
        val lines = checkNotNull(javaClass.getResourceAsStream("/gemma3-repair-prompt/fixtures.tsv"))
            .bufferedReader(StandardCharsets.US_ASCII)
            .useLines { it.toList() }
        val fields = lines.single { it.substringBefore('\t') == id }.split('\t')
        return PromptGolden(
            source = decode(fields[1]),
            protectedTerms = if (fields[2].isEmpty()) emptyList() else fields[2].split(',').map(::decode),
            expectedPrompt = decode(fields[3]),
        )
    }

    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

    private fun close(engine: LocalFormatCpuEngine, bindings: RecordingBindings) {
        engine.close()
        await(bindings.closed, "native close")
    }

    private fun await(latch: CountDownLatch, label: String) {
        assertTrue("timeout waiting for $label", latch.await(2, TimeUnit.SECONDS))
    }

    private data class PromptGolden(
        val source: String,
        val protectedTerms: List<String>,
        val expectedPrompt: String,
    )

    private class Fixture(
        private val openClockAtMs: Long? = null,
        private val clockBeforeNativeOutputMs: Long? = null,
    ) {
        val clock = MutableClock()
        val bindings = RecordingBindings("sortie modèle")
        val opens = AtomicInteger()
        private val model = File.createTempFile("gemma-profile", ".gguf").apply { deleteOnExit() }
        @Volatile var openedContextSize: Int? = null
            private set
        @Volatile var openedThreads: Int? = null
            private set

        init {
            bindings.beforeOutput = {
                clockBeforeNativeOutputMs?.let(clock.timeMs::set)
            }
        }

        fun engine(
            profile: LocalFormatCpuProfile = LocalFormatCpuProfile.Gemma4,
            sharedKey: String = "gemma-profile-test-${System.nanoTime()}",
        ) = LocalFormatCpuEngine(
            modelProvider = LocalFormatCpuModelProvider { model },
            nativeFactory = LocalFormatCpuNativeFactory { _, contextSize, threads ->
                opens.incrementAndGet()
                openedContextSize = contextSize
                openedThreads = threads
                openClockAtMs?.let(clock.timeMs::set)
                LocalFormatNative.forTesting(bindings)
            },
            clock = clock,
            sharedKey = sharedKey,
            profile = profile,
        )
    }

    private class MutableClock : LocalFormatCpuClock {
        val timeMs = AtomicLong(0L)
        override fun nowMs(): Long = timeMs.get()
    }

    private class RecordingBindings(private val output: String) : LocalFormatNativeApi {
        override val runtimeName: String = "fake-cpu"
        val generateCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val closed = CountDownLatch(1)
        @Volatile var prompt: String? = null
        @Volatile var grammar: String? = null
        @Volatile var profile: LocalFormatDecodingProfile? = null
        @Volatile var maxTokens: Int = 0
        @Volatile var timeoutMs: Long = 0L
        @Volatile var beforeOutput: () -> Unit = {}

        override fun open(path: ByteArray, contextSize: Int, threads: Int): Long = 1L

        override fun generate(
            handle: Long,
            generation: Long,
            prompt: ByteArray,
            grammar: ByteArray?,
            profile: LocalFormatDecodingProfile,
            maxTokens: Int,
            timeoutMs: Long,
            sink: LocalFormatChunkSink,
        ): ByteArray? {
            generateCalls.incrementAndGet()
            this.prompt = prompt.toString(Charsets.UTF_8)
            this.grammar = grammar?.toString(Charsets.UTF_8)
            this.profile = profile
            this.maxTokens = maxTokens
            this.timeoutMs = timeoutMs
            beforeOutput()
            sink.onBytes("chunk tardif".toByteArray(Charsets.UTF_8))
            return output.toByteArray(Charsets.UTF_8)
        }

        override fun cancel(handle: Long, generation: Long) = Unit

        override fun close(handle: Long) {
            closeCalls.incrementAndGet()
            closed.countDown()
        }
    }
}
