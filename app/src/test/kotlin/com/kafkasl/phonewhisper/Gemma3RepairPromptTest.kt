package com.kafkasl.phonewhisper

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gemma3RepairPromptTest {
    @Test
    fun pythonTokenizerGoldensMatchGemma3NativeEnvelopeAsUtf8() {
        val fixtures = readFixtures()
        assertEquals(
            setOf(
                "accent-and-spacing",
                "quotes-newlines-protected-duplicates",
                "python-whitespace-strip-table",
            ),
            fixtures.map { it.id }.toSet(),
        )

        fixtures.forEach { fixture ->
            val actual = Gemma3RepairPrompt.build(
                source = fixture.source,
                protectedTerms = fixture.protectedTerms,
            )
            assertArrayEquals(
                "Rendered UTF-8 prompt mismatch for ${fixture.id}",
                fixture.expectedPrompt.toByteArray(StandardCharsets.UTF_8),
                actual.toByteArray(StandardCharsets.UTF_8),
            )
            assertTrue("Missing Gemma 3 BOS/user prefix for ${fixture.id}",
                actual.startsWith(NATIVE_PREFIX))
            assertTrue("Missing Gemma 3 end-of-turn/model suffix for ${fixture.id}",
                actual.endsWith(NATIVE_SUFFIX))
            assertEquals(1, actual.split("<bos>").size - 1)
            assertTrue("Golden must include tokenizer IDs for ${fixture.id}",
                fixture.tokenIds.isNotEmpty())
        }
    }

    @Test
    fun finalTextOnlyApiPreservesProtectedTermOrderSpacingAndDuplicates() {
        val fixture = readFixtures().single {
            it.id == "quotes-newlines-protected-duplicates"
        }
        val actual = Gemma3RepairPrompt.build(fixture.source, fixture.protectedTerms)
        val first = actual.indexOf("  Dr. Zoé ")
        val second = actual.indexOf("Été")
        val third = actual.lastIndexOf("  Dr. Zoé ")

        assertTrue(first >= 0)
        assertTrue(first < second)
        assertTrue(second < third)
        assertTrue(actual.contains("<transcription>\nZoé a dit : « demain, enfin aujourd'hui ! »\r\n\nEt c'est tout.\n</transcription>"))
    }

    @Test
    fun pythonStripSemanticsRejectEmptyAndUnicodeWhitespaceOnlySources() {
        assertThrows<IllegalArgumentException> { Gemma3RepairPrompt.build("") }
        assertThrows<IllegalArgumentException> {
            Gemma3RepairPrompt.build("\u001c\u00a0\u0085\t\r\n\u3000\u001f")
        }

        val fixture = readFixtures().single { it.id == "python-whitespace-strip-table" }
        val actual = Gemma3RepairPrompt.build(fixture.source)
        assertTrue(actual.contains("<transcription>\nDéjà là !\n</transcription>"))
    }

    @Test
    fun invalidProtectedTermsAreRejectedLikePythonSource() {
        listOf("", " ", "\u00a0", "\u0085\t").forEach { term ->
            assertThrows<IllegalArgumentException>("blank term ${term.toCharArray().contentToString()}") {
                Gemma3RepairPrompt.build("texte", listOf(term))
            }
        }
        listOf("line\nfeed", "carriage\rreturn", "<tag>", "left<", "right>").forEach { term ->
            assertThrows<IllegalArgumentException>("unsafe term") {
                Gemma3RepairPrompt.build("texte", listOf(term))
            }
        }
    }

    private data class Fixture(
        val id: String,
        val source: String,
        val protectedTerms: List<String>,
        val expectedPrompt: String,
        val tokenIds: List<Int>,
    )

    private fun readFixtures(): List<Fixture> =
        checkNotNull(javaClass.getResourceAsStream("/gemma3-repair-prompt/fixtures.tsv"))
            .bufferedReader(StandardCharsets.US_ASCII)
            .useLines { lines -> lines.filter { it.isNotBlank() }.map(::parseFixture).toList() }

    private fun parseFixture(line: String): Fixture {
        val fields = line.split('\t')
        require(fields.size == 5) { "Expected five tab-separated Gemma 3 fixture fields" }
        return Fixture(
            id = fields[0],
            source = decode(fields[1]),
            protectedTerms = if (fields[2].isEmpty()) {
                emptyList()
            } else {
                fields[2].split(',').map(::decode)
            },
            expectedPrompt = decode(fields[3]),
            tokenIds = fields[4].split(',').map(String::toInt),
        )
    }

    private fun decode(value: String): String =
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

    private inline fun <reified T : Throwable> assertThrows(
        message: String = "Expected ${T::class.java.simpleName}",
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return
            throw AssertionError("$message but got $throwable", throwable)
        }
        throw AssertionError(message)
    }

    private companion object {
        const val NATIVE_PREFIX = "<bos><start_of_turn>user\n"
        const val NATIVE_SUFFIX = "<end_of_turn>\n<start_of_turn>model\n"
    }
}
