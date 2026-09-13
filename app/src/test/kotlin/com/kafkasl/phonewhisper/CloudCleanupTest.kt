package com.kafkasl.phonewhisper

import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCleanupTest {
    @Test fun `selected formatting instructions travel with the cleanup request`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))
        assertNull(cleanup.clean("acheter pommes et poires", DictationLanguage.FRENCH,
            CloudModelCatalog.default, "dummy-key", null, "Use a bullet list."))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("Use a bullet list."))
        assertTrue(body.contains("acheter pommes et poires"))
    }

    @Test fun `French and English map to the native and cleanup locales`() {
        assertEquals("fr", DictationLanguage.FRENCH.nemotronLanguage)
        assertEquals("en", DictationLanguage.ENGLISH.nemotronLanguage)
        assertEquals("fr-FR", DictationLanguage.FRENCH.transcribeCppLanguage)
        assertEquals("en-US", DictationLanguage.ENGLISH.transcribeCppLanguage)
        assertEquals("français", DictationLanguage.FRENCH.cleanupLanguageName)
        assertEquals("English", DictationLanguage.ENGLISH.cleanupLanguageName)
    }

    @Test fun `dynamic output budget covers short medium and maximum transcripts`() {
        assertEquals(512, CloudCleanup.outputTokenBudget(30))
        assertEquals(1_456, CloudCleanup.outputTokenBudget(3_000))
        assertEquals(5_056, CloudCleanup.outputTokenBudget(12_000))
        assertEquals(5_120, CloudCleanup.outputTokenBudget(Int.MAX_VALUE))
    }

    @Test fun `default cloud client uses bounded connection and long call timeouts`() {
        val client = CloudCleanup.defaultClient()

        assertEquals(45_000, client.callTimeoutMillis)
        assertEquals(5_000, client.connectTimeoutMillis)
    }

    @Test fun `OpenRouter catalogue contains only the five verified structured-output models`() {
        assertEquals(
            listOf(
                "mistralai/mistral-small-3.2-24b-instruct",
                "openai/gpt-5.4-nano",
                "google/gemini-3.1-flash-lite",
                "deepseek/deepseek-v4-flash-0731",
                "qwen/qwen3.5-flash-02-23",
            ),
            CloudModelCatalog.all.map { it.modelId },
        )
        assertEquals("mistralai/mistral-small-3.2-24b-instruct", CloudModelCatalog.default.modelId)
    }

    @Test fun `OpenRouter model preferences migrate only when the new value is absent`() {
        assertEquals(
            "mistral-small-3-2",
            CloudCleanupPreferencesMigration.modelValue(null, "openrouter-mistral-small-3-2"),
        )
        assertEquals(
            "gpt-5-4-nano",
            CloudCleanupPreferencesMigration.modelValue(null, "openrouter-gpt-5-4-nano"),
        )
        assertEquals(
            "mistral-small-3-2",
            CloudCleanupPreferencesMigration.modelValue("mistral-small-3-2", "openrouter-gpt-5-4-nano"),
        )
        assertEquals(
            setOf(
                "cloud_cleanup_provider",
                "cloud_cleanup_model_openai",
                "cloud_cleanup_model_openrouter",
                "cloud_cleanup_model_google",
                "cloud_cleanup_model_mistral",
                "cloud_cleanup_model_deepseek",
            ),
            CloudCleanupPreferencesMigration.obsoleteKeys,
        )
    }

    @Test fun `OpenRouter sends bearer auth strict structured output and required parameters`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"Bonjour, Ada.\\\"}\"}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals(
            "Bonjour, Ada.",
            cleanup.clean("bonjour Ada", DictationLanguage.FRENCH, CloudModelCatalog.default, "dummy-router-key"),
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/chat/completions", request.path)
        assertEquals("Bearer dummy-router-key", request.getHeader("Authorization"))
        assertNull(request.getHeader("x-goog-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"mistralai/mistral-small-3.2-24b-instruct\""))
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_schema\""))
        assertTrue(body.contains("\"strict\":true"))
        assertTrue(body.contains("\"provider\":{\"require_parameters\":true}"))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertFalse(body.contains("\"reasoning\""))
    }

    @Test fun `reasoning is disabled only for catalogue models that support it`() = withServer { server ->
        CloudModelCatalog.all.forEach { server.enqueue(MockResponse().setResponseCode(500)) }
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        CloudModelCatalog.all.forEach { model ->
            cleanup.clean("bonjour Ada", DictationLanguage.FRENCH, model, "dummy-router-key")
            val body = server.takeRequest().body.readUtf8()
            assertEquals(model.reasoningCanBeDisabled, body.contains("\"reasoning\":{\"enabled\":false}"))
        }
    }

    @Test fun `network malformed and implausible cleanup outcomes retain local ASR text`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"summary changed 99\\\"}\"}}]}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutClient = OkHttpClient.Builder()
            .callTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val cleanup = CloudCleanup(shortTimeoutClient, CloudEndpoints.forTests(server.url("/")))
        val original = "bonjour Ada 42"

        repeat(4) {
            assertEquals(original, cleanup.cleanupOrOriginal(original, DictationLanguage.FRENCH, CloudModelCatalog.default, "dummy-key"))
        }
    }

    @Test fun `cancelling an in-flight cleanup aborts the okhttp call`() = withServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cleanup = CloudCleanup(
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
            CloudEndpoints.forTests(server.url("/")),
        )
        val cancellation = DictationCancellationCoordinator()
        val result = AtomicReference<String?>("unexpected")
        val finished = CountDownLatch(1)
        Thread {
            result.set(
                cleanup.clean(
                    "bonjour Ada",
                    DictationLanguage.FRENCH,
                    CloudModelCatalog.default,
                    "dummy-key",
                    cancellation,
                ),
            )
            finished.countDown()
        }.start()

        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertTrue(cancellation.cancel())
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertNull(result.get())
    }

    @Test fun `blank credential and oversize transcript make no request`() = withServer { server ->
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))
        val original = "bonjour Ada"

        assertEquals(original, cleanup.cleanupOrOriginal(original, DictationLanguage.FRENCH, CloudModelCatalog.default, ""))
        assertNull(cleanup.clean("x".repeat(CloudCleanup.MAX_TRANSCRIPT_CHARS + 1), DictationLanguage.FRENCH, CloudModelCatalog.default, "dummy-key"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `AES GCM codec has random IVs and rejects tampering`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val first = AesGcmCodec.encrypt(key, "dummy-secret")
        val second = AesGcmCodec.encrypt(key, "dummy-secret")

        assertNotEquals(first, second)
        assertEquals("dummy-secret", AesGcmCodec.decrypt(key, first))
        assertNull(AesGcmCodec.decrypt(key, first.dropLast(2) + "xx"))
    }

    @Test fun `AES GCM encryption lets the cipher generate the IV required by AndroidKeyStore`() {
        val provider = CallerIvRejectingGcmProvider()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        val stored = AesGcmCodec.encrypt(key, "dummy-secret") {
            Cipher.getInstance("AES/GCM/NoPadding", provider)
        }

        assertTrue(stored.startsWith("AAECAwQFBgcICQoL:"))
    }

    @Test fun `plaintext OpenRouter credential is retained until encrypted save succeeds`() {
        assertFalse(
            CredentialMigration.migrateOpenRouter("legacy-key", hasSecureValue = false) {
                CredentialSaveResult.Failed
            },
        )
        assertTrue(
            CredentialMigration.migrateOpenRouter("legacy-key", hasSecureValue = false) {
                CredentialSaveResult.Saved
            },
        )
        assertTrue(CredentialMigration.migrateOpenRouter("legacy-key", hasSecureValue = true) { error("must not save") })
        assertTrue(CredentialMigration.purgeLegacyApiKey)
        assertEquals(
            setOf("credential_openai", "credential_google", "credential_mistral", "credential_deepseek"),
            CredentialMigration.obsoleteSecurePreferenceKeys,
        )
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server)
        }
    }

    private class CallerIvRejectingGcmProvider : Provider(NAME, 1.0, "test provider") {
        init {
            putService(
                Service(
                    this,
                    "Cipher",
                    "AES",
                    CallerIvRejectingGcmCipher::class.java.name,
                    emptyList(),
                    emptyMap(),
                ),
            )
            put("Cipher.AES SupportedModes", "GCM")
            put("Cipher.AES SupportedPaddings", "NoPadding")
        }

        companion object {
            const val NAME = "CallerIvRejectingGcm"
        }
    }

    class CallerIvRejectingGcmCipher : CipherSpi() {
        override fun engineSetMode(mode: String) = Unit
        override fun engineSetPadding(padding: String) = Unit
        override fun engineGetBlockSize(): Int = 16
        override fun engineGetOutputSize(inputLen: Int): Int = inputLen
        override fun engineGetIV(): ByteArray = ByteArray(12) { it.toByte() }
        override fun engineGetParameters(): AlgorithmParameters? = null
        override fun engineInit(opmode: Int, key: Key, random: SecureRandom) {
            require(opmode == Cipher.ENCRYPT_MODE)
        }
        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameterSpec,
            random: SecureRandom,
        ) {
            throw InvalidAlgorithmParameterException("caller supplied IV rejected")
        }
        override fun engineInit(
            opmode: Int,
            key: Key,
            params: AlgorithmParameters,
            random: SecureRandom,
        ) {
            throw InvalidAlgorithmParameterException("caller supplied parameters rejected")
        }
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray =
            input.copyOfRange(inputOffset, inputOffset + inputLen)
        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int = throw UnsupportedOperationException()
        override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray =
            input.copyOfRange(inputOffset, inputOffset + inputLen)
        override fun engineDoFinal(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int = throw UnsupportedOperationException()
    }
}
