package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GemmaModelStoreTest {
    @Test fun `verified download is installed and incomplete receipt is rejected`() = withFixture { fixture ->
        fixture.server.enqueue(fixture.response())
        val events = fixture.download()
        assertTrue(events.last() is GemmaInstallState.Installed)
        val installed = checkNotNull(fixture.store().installedModel())
        assertArrayEquals(fixture.bytes, installed.readBytes())
        assertEquals("identity", fixture.server.takeRequest().getHeader("Accept-Encoding"))
        File(installed.parentFile, "integrity").writeText("incomplete")
        assertNull(fixture.store().installedModel())
    }

    @Test fun `wrong hash is never published and a retry downloads a clean copy`() = withFixture { fixture ->
        fixture.server.enqueue(fixture.response(ByteArray(fixture.bytes.size) { 7 }))
        assertTrue(fixture.download().last() is GemmaInstallState.Error)
        assertNull(fixture.store().installedModel())
        assertEquals(0L, fixture.store().partialSizeBytes())
        fixture.server.enqueue(fixture.response())
        assertTrue(fixture.download().last() is GemmaInstallState.Installed)
        fixture.server.takeRequest()
        assertNull(fixture.server.takeRequest().getHeader("Range"))
    }

    @Test fun `recreated store resumes cancelled transfer with exact range and etag`() = withFixture { fixture ->
        val offset = fixture.pauseInitialDownload()
        fixture.server.enqueue(fixture.rangeResponse(offset))
        assertTrue(fixture.download().last() is GemmaInstallState.Installed)
        val resumed = fixture.server.takeRequest()
        assertEquals("bytes=$offset-", resumed.getHeader("Range"))
        assertEquals("\"fixture-v1\"", resumed.getHeader("If-Range"))
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `server full response to range safely replaces partial bytes`() = withFixture { fixture ->
        val offset = fixture.pauseInitialDownload()
        fixture.server.enqueue(fixture.response().setHeader("ETag", "\"new-copy\""))
        assertTrue(fixture.download().last() is GemmaInstallState.Installed)
        assertEquals("bytes=$offset-", fixture.server.takeRequest().getHeader("Range"))
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `mismatched etag on partial response is rejected before append`() = withFixture { fixture ->
        val offset = fixture.pauseInitialDownload()
        fixture.server.enqueue(fixture.rangeResponse(offset).setHeader("ETag", "\"different\""))
        val events = fixture.download()
        assertTrue(events.last() is GemmaInstallState.Error)
        assertNull(fixture.store().installedModel())
        assertEquals(0L, fixture.store().partialSizeBytes())
    }

    @Test fun `wrong content range cannot append or publish`() = withFixture { fixture ->
        val offset = fixture.pauseInitialDownload()
        fixture.server.enqueue(fixture.rangeResponse(offset).setHeader("Content-Range", "bytes 0-${fixture.bytes.lastIndex}/${fixture.bytes.size}"))
        assertTrue(fixture.download().last() is GemmaInstallState.Error)
        assertNull(fixture.store().installedModel())
        assertEquals(0L, fixture.store().partialSizeBytes())
    }

    @Test fun `wrong range total is rejected even when start matches`() = withFixture { fixture ->
        val offset = fixture.pauseInitialDownload()
        fixture.server.enqueue(fixture.rangeResponse(offset).setHeader("Content-Range", "bytes $offset-${fixture.bytes.lastIndex}/${fixture.bytes.size + 1}"))
        assertTrue(fixture.download().last() is GemmaInstallState.Error)
        assertNull(fixture.store().installedModel())
    }

    @Test fun `partial transfer without strong etag restarts safely`() = withFixture { fixture ->
        fixture.pauseInitialDownload("W/\"weak-tag\"")
        fixture.server.enqueue(fixture.response())
        assertTrue(fixture.download().last() is GemmaInstallState.Installed)
        assertNull(fixture.server.takeRequest().getHeader("Range"))
        assertEquals(2, fixture.server.requestCount)
    }

    @Test fun `wrong advertised size and encoded response are rejected`() = withFixture { fixture ->
        fixture.server.enqueue(fixture.response(fixture.bytes.copyOfRange(0, fixture.bytes.size / 2)))
        assertTrue(fixture.download().last() is GemmaInstallState.Error)
        assertNull(fixture.store().installedModel())
        fixture.server.enqueue(fixture.response().setHeader("Content-Encoding", "gzip"))
        assertTrue(fixture.download().last() is GemmaInstallState.Error)
        assertNull(fixture.store().installedModel())
    }

    @Test fun `cancel during verification keeps complete bytes and retry needs no request`() = withFixture { fixture ->
        fixture.server.enqueue(fixture.response())
        val token = GemmaDownloadCancellation()
        val states = mutableListOf<GemmaInstallState>()
        fixture.store().download(token) { state ->
            states += state
            if (state is GemmaInstallState.Verifying) token.cancel()
        }
        assertTrue(states.last() is GemmaInstallState.Paused)
        assertNull(fixture.store().installedModel())
        assertEquals(fixture.bytes.size.toLong(), fixture.store().partialSizeBytes())
        assertTrue(fixture.download().last() is GemmaInstallState.Installed)
        assertEquals(1, fixture.server.requestCount)
    }

    @Test fun `changed installed size or mtime invalidates lightweight receipt`() = withFixture { fixture ->
        fixture.server.enqueue(fixture.response())
        fixture.download()
        val file = checkNotNull(fixture.store().installedModel())
        assertTrue(file.setLastModified(file.lastModified() + 60_000L))
        assertNull(fixture.store().installedModel())
        file.writeBytes(byteArrayOf(1, 2))
        assertNull(fixture.store().installedModel())
    }

    @Test fun `already installed model avoids network and hashing progress`() = withFixture { fixture ->
        fixture.server.enqueue(fixture.response())
        fixture.download()
        val states = fixture.download()
        assertEquals(1, states.size)
        assertTrue(states.single() is GemmaInstallState.Installed)
        assertEquals(1, fixture.server.requestCount)
    }

    @Test fun `cancelled operation and server failures reveal no private url`() = withFixture { fixture ->
        val token = GemmaDownloadCancellation().apply { cancel() }
        val states = mutableListOf<GemmaInstallState>()
        fixture.store().download(token, states::add)
        assertTrue(states.single() is GemmaInstallState.Paused)
        assertEquals(0, fixture.server.requestCount)
        fixture.server.enqueue(MockResponse().setResponseCode(403))
        val failure = fixture.download().last() as GemmaInstallState.Error
        assertFalse(failure.message.contains("http"))
        assertFalse(failure.message.contains("fixture"))
    }

    private class Fixture(val root: File, val server: MockWebServer) {
        val bytes = ByteArray(256 * 1024) { ((it * 31) % 251).toByte() }
        private val artifact = GemmaModelArtifact(server.url("/fixture.litertlm?private=never-log").toString(),
            "fixture.litertlm", bytes.size.toLong(), sha256(bytes))
        private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        fun store() = GemmaModelStore(root, artifact, client)
        fun response(body: ByteArray = bytes) = MockResponse().setBody(Buffer().write(body)).setHeader("ETag", "\"fixture-v1\"")
        fun rangeResponse(offset: Long) = response(bytes.copyOfRange(offset.toInt(), bytes.size))
            .setResponseCode(206).setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        fun download(): List<GemmaInstallState> = mutableListOf<GemmaInstallState>().also { events ->
            store().download(GemmaDownloadCancellation(), events::add)
        }
        fun pauseInitialDownload(tag: String = "\"fixture-v1\""): Long {
            server.enqueue(response().setHeader("ETag", tag))
            val token = GemmaDownloadCancellation()
            val events = mutableListOf<GemmaInstallState>()
            store().download(token) { state ->
                events += state
                if (state is GemmaInstallState.Downloading && state.bytes > 0L) token.cancel()
            }
            assertTrue(events.last() is GemmaInstallState.Paused)
            val offset = store().partialSizeBytes()
            assertTrue(offset in 1 until bytes.size.toLong())
            assertNull(store().installedModel())
            server.takeRequest()
            return offset
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("gemma-model-store-test").toFile()
        val server = MockWebServer()
        server.start()
        try { block(Fixture(root, server)) } finally { server.shutdown(); root.deleteRecursively() }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
