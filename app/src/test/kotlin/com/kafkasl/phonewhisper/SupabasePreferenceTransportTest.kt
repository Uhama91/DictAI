package com.kafkasl.phonewhisper

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SupabasePreferenceTransportTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val actor = "22222222-2222-4222-8222-222222222222"
    private val other = "33333333-3333-4333-8333-333333333333"
    private val token = "private-user-token"
    private val key = "sb_publishable_test"

    @Test fun `reads paged replicas with explicit user filter and complete count`() = withServer { server, transport ->
        server.enqueue(page("""[{"device_id":"$actor","document":{"schema":1}}]""", "0-0/2"))
        server.enqueue(page("""[{"device_id":"$other","document":{"schema":99}}]""", "1-1/2"))
        val replicas = transport.readReplicas(token, user)
        assertEquals(listOf(actor, other), replicas.map { it.actor })
        assertEquals(listOf(1, 99), replicas.map { JSONObject(it.document).getInt("schema") })
        repeat(2) { index ->
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertEquals(key, request.getHeader("apikey"))
            assertEquals("count=exact", request.getHeader("Prefer"))
            assertEquals("items", request.getHeader("Range-Unit"))
            assertEquals("$index-$index", request.getHeader("Range"))
            assertEquals("eq.$user", request.requestUrl!!.queryParameter("user_id"))
            assertEquals("device_id,document", request.requestUrl!!.queryParameter("select"))
            assertEquals("device_id.asc", request.requestUrl!!.queryParameter("order"))
            assertFalse(request.path!!.contains(token))
            assertFalse(request.path!!.contains(key))
        }
    }

    @Test fun `upserts only the supplied user and device with JSON document rather than a string`() = withServer { server, transport ->
        server.enqueue(MockResponse().setResponseCode(201))
        transport.writeReplica(token, user, actor, "{\"schema\":1}")
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("user_id,device_id", request.requestUrl!!.queryParameter("on_conflict"))
        assertEquals("resolution=merge-duplicates,return=minimal", request.getHeader("Prefer"))
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(key, request.getHeader("apikey"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(setOf("user_id", "device_id", "document"), body.keys().asSequence().toSet())
        assertEquals(user, body.getString("user_id"))
        assertEquals(actor, body.getString("device_id"))
        assertEquals(1, body.getJSONObject("document").getInt("schema"))
    }

    @Test fun `empty remote account is a complete empty list`() = withServer { server, transport ->
        server.enqueue(page("[]", "*/0"))
        assertEquals(emptyList<SyncReplica>(), transport.readReplicas(token, user))
    }

    @Test fun `supports note snapshots beyond the earlier preferences only one MiB limit`() = withServer { server, transport ->
        val document = JSONObject().put("note", "n".repeat(2 * 1024 * 1024)).toString()
        server.enqueue(page("""[{"device_id":"$actor","document":$document}]""", "0-0/1"))
        assertEquals(document, transport.readReplicas(token, user).single().document)
        server.enqueue(MockResponse().setResponseCode(201))
        transport.writeReplica(token, user, actor, document)
    }

    @Test fun `rejects cumulatively oversized accounts rather than retaining hundreds of MiB`() = withServer { server, transport ->
        val document = JSONObject().put("notes", "n".repeat(4 * 1024 * 1024)).toString()
        repeat(9) { index ->
            val device = "22222222-2222-4222-8222-" + index.toString().padStart(12, '0')
            server.enqueue(page("""[{"device_id":"$device","document":$document}]""", "$index-$index/9"))
        }
        assertThrows(IOException::class.java) { transport.readReplicas(token, user) }
        assertTrue(server.requestCount <= 9)
    }

    @Test fun `rejects missing exact count or a server truncated page`() {
        for (range in listOf(null, "0-0/*", "0-1/2", "1-1/2", "0-0/101")) withServer { server, transport ->
            val response = MockResponse().setBody("""[{"device_id":"$actor","document":{}}]""")
            if (range != null) response.addHeader("Content-Range", range)
            server.enqueue(response)
            expectIo { transport.readReplicas(token, user) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `rejects duplicate devices changing counts or malformed JSON rather than partial merge`() {
        withServer { server, transport ->
            repeat(2) { index -> server.enqueue(page("""[{"device_id":"$actor","document":{}}]""", "$index-$index/2")) }
            expectIo { transport.readReplicas(token, user) }
        }
        withServer { server, transport ->
            server.enqueue(page("""[{"device_id":"$actor","document":{}}]""", "0-0/2"))
            server.enqueue(page("""[{"device_id":"$other","document":{}}]""", "1-1/3"))
            expectIo { transport.readReplicas(token, user) }
        }
        for (body in listOf("{}", "not-json", """[{"device_id":"$actor","document":"{}"}]""", """[{"device_id":"invalid","document":{}}]""")) {
            withServer { server, transport ->
                server.enqueue(page(body, "0-0/1"))
                expectIo { transport.readReplicas(token, user) }
            }
        }
    }

    @Test fun `HTTP failures never expose response content or retry implicitly`() {
        for (code in listOf(401, 403, 429, 500)) withServer { server, transport ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("secret-server-body"))
            val failure = assertThrows(SyncHttpException::class.java) { transport.readReplicas(token, user) }
            assertEquals(code, failure.code)
            assertFalse(failure.toString().contains("secret-server-body"))
            assertFalse(failure.toString().contains(token))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `redirects are disabled even on an injected client`() = withServer { server, transport ->
        MockWebServer().use { destination ->
            destination.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/stolen")))
            assertEquals(302, assertThrows(SyncHttpException::class.java) { transport.readReplicas(token, user) }.code)
            assertEquals(0, destination.requestCount)
        }
    }

    @Test fun `rejects oversized documents and bounds streaming without content length`() = withServer { server, transport ->
        val large = """[{"device_id":"$actor","document":{"value":"${"x".repeat(SyncDocument.MAX_BYTES + 1)}"}}]"""
        server.enqueue(page(large, "0-0/1"))
        expectIo { transport.readReplicas(token, user) }
        server.enqueue(MockResponse().addHeader("Content-Range", "*/0")
            .setChunkedBody(" ".repeat(11 * 1024 * 1024) + "[]", 4096))
        expectIo { transport.readReplicas(token, user) }
    }

    @Test fun `invalid local data and identifiers never reach the network`() = withServer { server, transport ->
        for (document in listOf("not-json", "[]", "{\"large\":\"${"x".repeat(SyncDocument.MAX_BYTES + 1)}\"}")) {
            expectIo { transport.writeReplica(token, user, actor, document) }
        }
        assertThrows(IllegalArgumentException::class.java) { transport.readReplicas(token, "bad&user") }
        assertThrows(IllegalArgumentException::class.java) { transport.writeReplica(token, user, "1-1-1-1-1", "{}") }
        assertEquals(0, server.requestCount)
    }

    @Test fun `refuses privileged API keys and uses a bounded client`() = withServer { server, _ ->
        assertThrows(IllegalArgumentException::class.java) { SupabasePreferenceTransport(server.url("/"), "sb_secret_admin") }
        val client = SupabasePreferenceTransport.defaultClient()
        assertTrue(client.callTimeoutMillis in 1..30_000)
        assertTrue(client.connectTimeoutMillis in 1..10_000)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    private fun page(body: String, range: String) = MockResponse().addHeader("Content-Type", "application/json")
        .addHeader("Content-Range", range).setBody(body)
    private fun expectIo(action: () -> Unit) { assertThrows(IOException::class.java, action) }
    private fun withServer(action: (MockWebServer, SupabasePreferenceTransport) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            action(server, SupabasePreferenceTransport(server.url("/"), key, client))
        }
    }
}
