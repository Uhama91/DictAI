package com.kafkasl.phonewhisper

import java.util.Base64
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaFineTunedPromptTest {
    @Test
    fun pythonFixturesMatchKotlinPromptAsUtf8AndKeepSourceLast() {
        val fixtures = readFixtures()
        val pairs = fixtures.map { "${it.mode}/${it.phase}" }.toSet()
        assertEquals(
            setOf(
                "corrected/partial",
                "corrected/final",
                "list/partial",
                "list/final",
                "email/partial",
                "email/final",
            ),
            pairs,
        )

        fixtures.forEach { fixture ->
            val actual = GemmaFineTunedPrompt.build(
                source = fixture.source,
                mode = mode(fixture.mode),
                phase = phase(fixture.phase),
                contextBefore = fixture.contextBefore,
                protectedTerms = fixture.protectedTerms,
            )
            val expected = fixture.expectedPrompt
            assertArrayEquals(
                "UTF-8 prompt mismatch for ${fixture.id}",
                expected.toByteArray(StandardCharsets.UTF_8),
                actual.toByteArray(StandardCharsets.UTF_8),
            )

            val lines = actual.split('\n')
            assertTrue("source-last suffix missing for ${fixture.id}",
                lines.last().startsWith("source_json={\"source\":"))
            assertFalse(lines.dropLast(1).any { it.startsWith("source_json=") })
            assertTrue(lines.indexOfFirst { it.startsWith("context_before_json=") }
                < lines.indexOfFirst { it.startsWith("protected_terms_json=") })
            assertTrue(lines.indexOfFirst { it.startsWith("protected_terms_json=") }
                < lines.indexOfFirst { it.startsWith("source_json=") })
            assertFalse(actual.contains("<|"))
            assertFalse(actual.contains("<think>"))
        }
    }

    @Test
    fun nativeEnvelopeMatchesFrozenHfRenderedPromptsAsUtf8() {
        val userFixtures = readFixtures().associateBy { it.id }
        val envelopeFixtures = readEnvelopeFixtures()
        assertEquals(6, envelopeFixtures.size)
        assertEquals(
            setOf(
                "text-partial-empty",
                "text-final-context",
                "list-partial-quoted",
                "list-final-separators",
                "email-partial-controls",
                "email-final-escaped",
            ),
            envelopeFixtures.map { it.id }.toSet(),
        )

        envelopeFixtures.forEach { fixture ->
            val userFixture = checkNotNull(userFixtures[fixture.id])
            assertEquals(userFixture.mode, fixture.mode)
            assertEquals(userFixture.phase, fixture.phase)

            val userPrompt = GemmaFineTunedPrompt.build(
                source = userFixture.source,
                mode = mode(userFixture.mode),
                phase = phase(userFixture.phase),
                contextBefore = userFixture.contextBefore,
                protectedTerms = userFixture.protectedTerms,
            )
            assertArrayEquals(
                "USER portion changed for ${fixture.id}",
                userFixture.expectedPrompt.toByteArray(StandardCharsets.UTF_8),
                userPrompt.toByteArray(StandardCharsets.UTF_8),
            )

            val actual = GemmaFineTunedPrompt.buildNativeEnvelope(userPrompt)
            assertArrayEquals(
                "native envelope mismatch for ${fixture.id}",
                fixture.expectedEnvelope.toByteArray(StandardCharsets.UTF_8),
                actual.toByteArray(StandardCharsets.UTF_8),
            )
            assertEquals(
                userPrompt,
                fixture.expectedEnvelope.removePrefix(NATIVE_PREFIX)
                    .removeSuffix(NATIVE_SUFFIX),
            )
            assertTrue(actual.startsWith(NATIVE_PREFIX))
            assertTrue(actual.endsWith(NATIVE_SUFFIX))
            assertEquals(1, actual.split("<bos>").size - 1)
            assertFalse(actual.contains("<|turn>system"))
            assertFalse(actual.contains("<|think|>"))
        }
    }

    private data class Fixture(
        val id: String,
        val mode: String,
        val phase: String,
        val source: String,
        val contextBefore: String,
        val protectedTerms: List<String>,
        val expectedPrompt: String,
    )

    private fun readFixtures(): List<Fixture> {
        checkNotNull(javaClass.getResourceAsStream("/gemma4-prompt-parity/fixtures.jsonl")).use { }
        return checkNotNull(javaClass.getResourceAsStream("/gemma4-prompt-parity/fixtures.tsv"))
            .bufferedReader(StandardCharsets.US_ASCII)
            .useLines { lines -> lines.filter { it.isNotBlank() }.map(::parseFixture).toList() }
    }

    private data class EnvelopeFixture(
        val id: String,
        val mode: String,
        val phase: String,
        val expectedEnvelope: String,
    )

    private fun readEnvelopeFixtures(): List<EnvelopeFixture> =
        checkNotNull(javaClass.getResourceAsStream("/gemma4-prompt-parity/envelope_fixtures.tsv"))
            .bufferedReader(StandardCharsets.US_ASCII)
            .useLines { lines ->
                lines.filter { it.isNotBlank() }.map(::parseEnvelopeFixture).toList()
            }

    private fun parseEnvelopeFixture(line: String): EnvelopeFixture {
        val fields = line.split('\t')
        require(fields.size == 4) { "Expected four tab-separated envelope fixture fields" }
        return EnvelopeFixture(
            id = fields[0],
            mode = fields[1],
            phase = fields[2],
            expectedEnvelope = decode(fields[3]),
        )
    }

    private fun parseFixture(line: String): Fixture {
        val fields = line.split('\t')
        require(fields.size == 7) { "Expected seven tab-separated fixture fields" }
        return Fixture(
            id = fields[0],
            mode = fields[1],
            phase = fields[2],
            source = decode(fields[3]),
            contextBefore = decode(fields[4]),
            protectedTerms = if (fields[5].isEmpty()) {
                emptyList()
            } else {
                fields[5].split(',').map(::decode)
            },
            expectedPrompt = decode(fields[6]),
        )
    }

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

    private fun mode(value: String): GemmaFineTunedPrompt.Mode = when (value) {
        "corrected" -> GemmaFineTunedPrompt.Mode.TEXT
        "list" -> GemmaFineTunedPrompt.Mode.LIST
        "email" -> GemmaFineTunedPrompt.Mode.EMAIL
        else -> error("Unknown fixture mode: $value")
    }

    private fun phase(value: String): GemmaFineTunedPrompt.Phase = when (value) {
        "partial" -> GemmaFineTunedPrompt.Phase.PARTIAL
        "final" -> GemmaFineTunedPrompt.Phase.FINAL
        else -> error("Unknown fixture phase: $value")
    }

    private companion object {
        const val NATIVE_PREFIX = "<bos><|turn>user\n"
        const val NATIVE_SUFFIX = "<turn|>\n<|turn>model\n"
    }

}
