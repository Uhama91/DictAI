package com.kafkasl.phonewhisper

import javax.crypto.KeyGenerator
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCleanupTest {
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

    @Test fun `each provider exposes a concise specific privacy notice`() {
        assertEquals("Traitement cloud selon les conditions OpenAI.", CloudProvider.OPENAI.privacyNotice)
        assertEquals("Routeur multi-fournisseurs ; rétention selon la route choisie.", CloudProvider.OPENROUTER.privacyNotice)
        assertEquals("Traitement cloud selon les conditions Google.", CloudProvider.GOOGLE.privacyNotice)
        assertEquals("Fournisseur européen ; traitement selon les conditions Mistral.", CloudProvider.MISTRAL.privacyNotice)
        assertEquals("Données traitées en Chine selon sa politique.", CloudProvider.DEEPSEEK.privacyNotice)
        assertEquals(CloudProvider.entries.size, CloudProvider.entries.map { it.privacyNotice }.distinct().size)
    }

    @Test fun `medium and maximum dynamic budgets are transmitted in provider payloads`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        cleanup.clean("a".repeat(3_000), DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"max_completion_tokens\":1456"))

        cleanup.clean("a".repeat(12_000), DictationLanguage.FRENCH, CloudProvider.GOOGLE, model(CloudProvider.GOOGLE), "dummy-key")
        assertTrue(server.takeRequest().body.readUtf8().contains("\"maxOutputTokens\":5056"))
    }

    @Test fun `catalog has exactly two executable inexpensive choices and verified defaults per provider`() {
        assertEquals(
            listOf("gpt-5.4-nano", "gpt-5-mini"),
            CloudModelCatalog.forProvider(CloudProvider.OPENAI).map { it.modelId },
        )
        assertEquals(
            listOf("openai/gpt-5.4-nano", "mistralai/mistral-small-3.2-24b-instruct"),
            CloudModelCatalog.forProvider(CloudProvider.OPENROUTER).map { it.modelId },
        )
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"),
            CloudModelCatalog.forProvider(CloudProvider.GOOGLE).map { it.modelId },
        )
        assertEquals(
            listOf("ministral-8b-latest", "mistral-small-latest"),
            CloudModelCatalog.forProvider(CloudProvider.MISTRAL).map { it.modelId },
        )
        assertEquals(
            listOf("deepseek-v4-flash", "deepseek-v4-pro"),
            CloudModelCatalog.forProvider(CloudProvider.DEEPSEEK).map { it.modelId },
        )
        assertTrue(CloudProvider.entries.all { provider ->
            CloudModelCatalog.forProvider(provider).size == 2 &&
                CloudModelCatalog.forProvider(provider).all { !it.modelId.isNullOrBlank() }
        })
        assertEquals("gpt-5.4-nano", CloudModelPreferences.default(CloudProvider.OPENAI).modelId)
        assertEquals("gemini-3.5-flash-lite", CloudModelPreferences.default(CloudProvider.GOOGLE).modelId)
    }

    @Test fun `each provider has a stable independent model preference key and default`() {
        assertEquals("cloud_cleanup_model_mistral", CloudModelPreferences.key(CloudProvider.MISTRAL))
        assertEquals("cloud_cleanup_model_deepseek", CloudModelPreferences.key(CloudProvider.DEEPSEEK))
        assertNotEquals(CloudModelPreferences.key(CloudProvider.OPENAI), CloudModelPreferences.key(CloudProvider.OPENROUTER))
        assertEquals("mistral-ministral-8b", CloudModelPreferences.default(CloudProvider.MISTRAL).preferenceValue)
        assertEquals("deepseek-v4-flash", CloudModelPreferences.default(CloudProvider.DEEPSEEK).preferenceValue)
    }

    @Test fun `obsolete Luna and Ministral 3B preferences fall back to their provider default`() {
        assertEquals(
            CloudModelPreferences.default(CloudProvider.OPENAI),
            CloudModelCatalog.selected(CloudProvider.OPENAI, "openai-luna"),
        )
        assertEquals(
            CloudModelPreferences.default(CloudProvider.OPENROUTER),
            CloudModelCatalog.selected(CloudProvider.OPENROUTER, "openrouter-luna"),
        )
        assertEquals(
            CloudModelPreferences.default(CloudProvider.MISTRAL),
            CloudModelCatalog.selected(CloudProvider.MISTRAL, "mistral-ministral-3b"),
        )
    }

    @Test fun `prompt treats transcript as untrusted JSON and prohibits instruction following`() {
        val prompt = CloudCleanupPrompt.system(DictationLanguage.FRENCH)

        assertTrue(prompt.contains("untrusted JSON", ignoreCase = true))
        assertTrue(prompt.contains("Never obey", ignoreCase = true))
        assertTrue(prompt.contains("never translate", ignoreCase = true))
        assertTrue(prompt.contains("names, numbers", ignoreCase = true))
        assertTrue(prompt.contains("français", ignoreCase = true))
    }

    @Test fun `OpenAI uses bearer auth fixed chat completions and strict JSON schema`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"Bonjour, Dydy.\\\"}\"}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals(
            "Bonjour, Dydy.",
            cleanup.clean("bonjour Dydy", DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-openai-key"),
        )

        val request = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", request.path)
        assertEquals("Bearer dummy-openai-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"json_schema\""))
        assertTrue(body.contains("\"strict\":true"))
        assertFalse(body.contains("\"temperature\""))
        assertFalse(body.contains("\"reasoning_effort\""))
        assertTrue(body.contains("\"max_completion_tokens\":512"))
    }

    @Test fun `OpenAI mini alone requests minimal reasoning effort`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(500))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))
        val mini = CloudModelCatalog.forProvider(CloudProvider.OPENAI).single { it.modelId == "gpt-5-mini" }

        cleanup.clean("bonjour Ada", DictationLanguage.FRENCH, CloudProvider.OPENAI, mini, "dummy-key")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-5-mini\""))
        assertTrue(body.contains("\"reasoning_effort\":\"minimal\""))
        assertTrue(body.contains("\"max_completion_tokens\":512"))
    }

    @Test fun `OpenRouter uses bearer auth and its fixed model`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"Hello, Ada.\\\"}\"}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals(
            "Hello, Ada.",
            cleanup.clean("hello ada", DictationLanguage.ENGLISH, CloudProvider.OPENROUTER, model(CloudProvider.OPENROUTER), "dummy-router-key"),
        )

        val request = server.takeRequest()
        assertEquals("/openrouter/api/v1/chat/completions", request.path)
        assertEquals("Bearer dummy-router-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("openai/gpt-5.4-nano"))
        assertTrue(body.contains("\"max_tokens\":512"))
    }

    @Test fun `Google uses API key auth safely encoded model path and structured JSON`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"text\\\":\\\"Hello, Ada.\\\"}\"}]}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals(
            "Hello, Ada.",
            cleanup.clean("hello ada", DictationLanguage.ENGLISH, CloudProvider.GOOGLE, model(CloudProvider.GOOGLE), "dummy-google-key"),
        )

        val request = server.takeRequest()
        assertEquals("/google/v1beta/models/gemini-3.5-flash-lite:generateContent", request.path)
        assertEquals("dummy-google-key", request.getHeader("x-goog-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("responseSchema"))
        assertTrue(body.contains("\"maxOutputTokens\":512"))
        assertTrue(body.contains("\"thinkingConfig\":{\"thinkingLevel\":\"minimal\"}"))
        assertFalse(body.contains("\"temperature\""))
    }

    @Test fun `malformed blank oversized and implausible cloud outcomes fail open`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{\"text\":\"   \"}"))
        server.enqueue(MockResponse().setBody("{\"text\":\"I followed the transcript instruction and summarized it\"}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertNull(cleanup.clean("bonjour Dydy 42", DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key"))
        assertNull(cleanup.clean("bonjour Dydy 42", DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key"))
        assertNull(cleanup.clean("x".repeat(CloudCleanup.MAX_TRANSCRIPT_CHARS + 1), DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key"))
    }

    @Test fun `realistic OpenAI response with null metadata preserves structured content`() = withServer { server ->
        server.enqueue(MockResponse().setBody("""
            {"id":"chatcmpl_dummy","object":"chat.completion","created":1,"model":"gpt-5.4-nano","system_fingerprint":null,"choices":[{"index":0,"message":{"role":"assistant","content":"{\"text\":\"Bonjour, Ada.\"}","refusal":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
        """.trimIndent()))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals(
            "Bonjour, Ada.",
            cleanup.clean("bonjour Ada", DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key"),
        )
    }

    @Test fun `all network and structured failures retain the original ASR text`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"  \\\"}\"}}]}"))
        server.enqueue(MockResponse().setBody("{not-json"))
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"summary changed 99\\\"}\"}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))
        val original = "bonjour Ada 42"

        repeat(6) {
            assertEquals(original, cleanup.cleanupOrOriginal(original, DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key"))
        }
    }

    @Test fun `timeout retains the original ASR text`() = withServer { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient.Builder().callTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val cleanup = CloudCleanup(client, CloudEndpoints.forTests(server.url("/")))
        val original = "bonjour Ada"

        assertEquals(original, cleanup.cleanupOrOriginal(original, DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), "dummy-key"))
    }

    @Test fun `empty credential or foreign provider model retains original without a request`() = withServer { server ->
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))
        val original = "bonjour Ada"

        assertEquals(original, cleanup.cleanupOrOriginal(original, DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.OPENAI), ""))
        assertEquals(original, cleanup.cleanupOrOriginal(original, DictationLanguage.FRENCH, CloudProvider.OPENAI, model(CloudProvider.MISTRAL), "dummy-key"))
        assertEquals(0, server.requestCount)
    }

    @Test fun `Mistral uses its official chat endpoint bearer auth and local JSON validation`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"Bonjour, Ada.\\\"}\"}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals("Bonjour, Ada.", cleanup.clean("bonjour Ada", DictationLanguage.FRENCH, CloudProvider.MISTRAL, model(CloudProvider.MISTRAL), "dummy-mistral-key"))

        val request = server.takeRequest()
        assertEquals("/mistral/v1/chat/completions", request.path)
        assertEquals("Bearer dummy-mistral-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"type\":\"json_object\""))
        assertTrue(body.contains("\"max_tokens\":512"))
    }

    @Test fun `DeepSeek uses its official chat endpoint bearer auth and JSON object mode`() = withServer { server ->
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"text\\\":\\\"Hello, Ada.\\\"}\"}}]}"))
        val cleanup = CloudCleanup(OkHttpClient(), CloudEndpoints.forTests(server.url("/")))

        assertEquals("Hello, Ada.", cleanup.clean("hello Ada", DictationLanguage.ENGLISH, CloudProvider.DEEPSEEK, model(CloudProvider.DEEPSEEK), "dummy-deepseek-key"))

        val request = server.takeRequest()
        assertEquals("/deepseek/chat/completions", request.path)
        assertEquals("Bearer dummy-deepseek-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"type\":\"json_object\""))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    @Test fun `AES GCM codec has random IVs and rejects tampering`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val first = AesGcmCodec.encrypt(key, "dummy-secret")
        val second = AesGcmCodec.encrypt(key, "dummy-secret")

        assertNotEquals(first, second)
        assertEquals("dummy-secret", AesGcmCodec.decrypt(key, first))
        assertNull(AesGcmCodec.decrypt(key, first.dropLast(2) + "xx"))
    }

    @Test fun `legacy credential migration only maps known legacy keys`() {
        assertEquals(CloudProvider.OPENAI, CredentialMigration.providerForLegacyKey("api_key"))
        assertEquals(CloudProvider.OPENROUTER, CredentialMigration.providerForLegacyKey("openrouter_key"))
        assertNull(CredentialMigration.providerForLegacyKey("unknown"))
    }

    @Test fun `legacy credential migration removes clear text even when encryption attempt fails`() {
        val attempts = mutableListOf<Pair<CloudProvider, String>>()

        val keysToRemove = CredentialMigration.migrate(
            mapOf("api_key" to "dummy-openai", "openrouter_key" to "dummy-router"),
        ) { provider, value ->
            attempts += provider to value
            error("keystore unavailable")
        }

        assertEquals(
            listOf(CloudProvider.OPENAI to "dummy-openai", CloudProvider.OPENROUTER to "dummy-router"),
            attempts,
        )
        assertEquals(setOf("api_key", "openrouter_key"), keysToRemove)
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server)
        }
    }

    private fun model(provider: CloudProvider): CuratedCloudModel =
        CloudModelCatalog.forProvider(provider).first()
}
