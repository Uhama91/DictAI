package com.kafkasl.phonewhisper

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AccountAttachmentTransportTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val token = "private-user-token"
    private val key = "sb_publishable_test"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
    private val sha get() = hash(jpeg)

    @Test fun `uploads immutable JPEG with credentials only in headers`() = withServer { server, transport ->
        server.enqueue(MockResponse().setResponseCode(200))
        transport.upload(token, user, sha, jpeg)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/storage/v1/object/dictai-note-images/$user/$sha.jpg", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(key, request.getHeader("apikey"))
        assertEquals("false", request.getHeader("x-upsert"))
        assertEquals("image/jpeg", request.getHeader("Content-Type"))
        assertArrayEquals(jpeg, request.body.readByteArray())
        assertFalse(request.path!!.contains(token))
        assertFalse(request.path!!.contains(key))
    }

    @Test fun `downloads private images and verifies content hash`() = withServer { server, transport ->
        server.enqueue(image(jpeg))
        assertArrayEquals(jpeg, transport.download(token, user, sha))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/storage/v1/object/authenticated/dictai-note-images/$user/$sha.jpg", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(key, request.getHeader("apikey"))
        assertNull(request.requestUrl!!.query)
    }

    @Test fun `duplicate upload succeeds only after authenticated hash verification`() {
        for (code in listOf(400, 409)) withServer { server, transport ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("untrusted-private-error"))
            server.enqueue(image(jpeg))
            transport.upload(token, user, sha, jpeg)
            assertEquals(2, server.requestCount)
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        }
    }

    @Test fun `duplicate with different remote content is never accepted or overwritten`() = withServer { server, transport ->
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(image(jpeg.copyOf().also { it[4] = 99 }))
        assertThrows(IOException::class.java) { transport.upload(token, user, sha, jpeg) }
        assertEquals(2, server.requestCount)
    }

    @Test fun `HTTP failures reveal neither response data nor credentials and do not retry`() {
        for (code in listOf(401, 403, 429, 500)) withServer { server, transport ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("sensitive-note-error"))
            val error = assertThrows(SyncHttpException::class.java) { transport.upload(token, user, sha, jpeg) }
            assertEquals(code, error.code)
            assertFalse(error.toString().contains("sensitive-note-error"))
            assertFalse(error.toString().contains(token))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `rejects oversized length and chunked streams without content length`() = withServer { server, transport ->
        val large = ByteArray(SupabaseAccountAttachmentTransport.MAX_IMAGE_BYTES + 1)
        server.enqueue(image(large))
        assertThrows(IOException::class.java) { transport.download(token, user, hash(large)) }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setChunkedBody(Buffer().write(large), 8192))
        assertThrows(IOException::class.java) { transport.download(token, user, hash(large)) }
    }

    @Test fun `invalid local bytes hashes and path identifiers never reach network`() = withServer { server, transport ->
        assertThrows(IllegalArgumentException::class.java) { transport.upload(token, user, "../escape", jpeg) }
        assertThrows(IllegalArgumentException::class.java) { transport.download(token, "invalid/user", sha) }
        assertThrows(IllegalArgumentException::class.java) { transport.upload(token, user, "a".repeat(64), jpeg) }
        assertThrows(IllegalArgumentException::class.java) { transport.upload(token, user, hash(byteArrayOf()), byteArrayOf()) }
        val tooLarge = ByteArray(SupabaseAccountAttachmentTransport.MAX_IMAGE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { transport.upload(token, user, hash(tooLarge), tooLarge) }
        val nonJpeg = "not an image".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { transport.upload(token, user, hash(nonJpeg), nonJpeg) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `rejects wrong hash JPEG signature and media type from downloads`() {
        for (response in listOf(image(jpeg.copyOf().also { it[4] = 99 }), image(byteArrayOf(1, 2, 3)),
            image(jpeg).setHeader("Content-Type", "text/html"), image(byteArrayOf()))) withServer { server, transport ->
            server.enqueue(response)
            assertThrows(IOException::class.java) { transport.download(token, user, sha) }
        }
    }

    @Test fun `file transfer preserves bytes and failed downloads preserve destination`() = withServer { server, transport ->
        val source = File.createTempFile("dictai-attachment-source", ".jpg").apply { writeBytes(jpeg) }
        val destination = File.createTempFile("dictai-attachment-dest", ".jpg").apply { writeText("local-original") }
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            transport.upload(token, user, sha, source)
            assertArrayEquals(jpeg, server.takeRequest(1, TimeUnit.SECONDS)!!.body.readByteArray())
            server.enqueue(image(jpeg.copyOf().also { it[4] = 99 }))
            assertThrows(IOException::class.java) { transport.download(token, user, sha, destination) }
            assertEquals("local-original", destination.readText())
            server.enqueue(image(jpeg))
            transport.download(token, user, sha, destination)
            assertArrayEquals(jpeg, destination.readBytes())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun `redirects never forward authentication even with injected client`() = withServer { server, transport ->
        MockWebServer().use { destination ->
            destination.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/stolen")))
            assertEquals(302, assertThrows(SyncHttpException::class.java) { transport.download(token, user, sha) }.code)
            assertEquals(0, destination.requestCount)
        }
    }

    @Test fun `refuses privileged keys insecure hosts and unbounded default clients`() = withServer { server, _ ->
        assertThrows(IllegalArgumentException::class.java) { SupabaseAccountAttachmentTransport(server.url("/"), "sb_secret_admin") }
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseAccountAttachmentTransport(server.url("/").newBuilder().host("example.com").build(), key)
        }
        val client = SupabaseAccountAttachmentTransport.defaultClient()
        assertTrue(client.callTimeoutMillis in 1..120_000)
        assertTrue(client.connectTimeoutMillis in 1..10_000)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    private fun image(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun withServer(action: (MockWebServer, SupabaseAccountAttachmentTransport) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            action(server, SupabaseAccountAttachmentTransport(server.url("/"), key,
                OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()))
        }
    }
}
