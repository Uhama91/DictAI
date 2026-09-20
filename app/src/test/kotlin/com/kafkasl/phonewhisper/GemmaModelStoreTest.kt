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
    @Test fun `artifact descriptor exposes ordered multipart parts`() {
        val partsField = GemmaModelArtifact::class.java.declaredFields
            .firstOrNull { it.name == "parts" }
        assertNotNull("GemmaModelArtifact must expose optional multipart parts", partsField)
    }

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

    @Test fun `multipart download reconstructs exact bytes and verifies each part`() = withMultipartFixture { fixture ->
        fixture.enqueueAllParts()
        val states = fixture.download()
        assertTrue(states.last() is GemmaInstallState.Installed)
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
        assertEquals("/part-0", fixture.server.takeRequest().path)
        assertEquals("/part-1", fixture.server.takeRequest().path)
        assertEquals("/part-2", fixture.server.takeRequest().path)
    }

    @Test fun `multipart interruption inside a part resumes only that part`() = withMultipartFixture { fixture ->
        fixture.enqueueFullPart(0)
        fixture.enqueueFullPart(1)
        val token = GemmaDownloadCancellation()
        val states = mutableListOf<GemmaInstallState>()
        fixture.store().download(token) { state ->
            states += state
            if (state is GemmaInstallState.Downloading && state.bytes > fixture.partBytes[0].size) token.cancel()
        }
        assertTrue(states.last() is GemmaInstallState.Paused)
        assertEquals("/part-0", fixture.server.takeRequest().path)
        assertEquals("/part-1", fixture.server.takeRequest().path)
        val offset = fixture.store().partialSizeBytes() - fixture.partBytes[0].size
        assertTrue(offset in 1 until fixture.partBytes[1].size.toLong())

        fixture.enqueueRangePart(1, offset)
        fixture.enqueueFullPart(2)
        val resumed = mutableListOf<GemmaInstallState>()
        fixture.store().download(GemmaDownloadCancellation(), resumed::add)
        assertTrue(resumed.last() is GemmaInstallState.Installed)
        val request = fixture.server.takeRequest()
        assertEquals("/part-1", request.path)
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals("\"part-1\"", request.getHeader("If-Range"))
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `multipart 200 after range replaces active bytes and keeps verified prefix`() = withMultipartFixture { fixture ->
        fixture.pauseInsidePartOne()
        fixture.enqueueFullPart(1, "\"part-1-new\"")
        fixture.enqueueFullPart(2)
        val states = fixture.download()
        assertTrue(states.last() is GemmaInstallState.Installed)
        val request = fixture.server.takeRequest()
        assertEquals("/part-1", request.path)
        assertTrue(request.getHeader("Range")!!.startsWith("bytes="))
        assertEquals("\"part-1\"", request.getHeader("If-Range"))
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `multipart 200 replacement remains resumable after cancellation`() = withMultipartFixture { fixture ->
        fixture.pauseInsidePartOne()
        fixture.enqueueFullPart(1, "\"part-1-new\"")
        val token = GemmaDownloadCancellation()
        val paused = mutableListOf<GemmaInstallState>()
        fixture.store().download(token) { state ->
            paused += state
            if (state is GemmaInstallState.Downloading && state.bytes > fixture.partBytes[0].size) token.cancel()
        }
        assertTrue(paused.last() is GemmaInstallState.Paused)
        val replacement = fixture.server.takeRequest()
        assertEquals("/part-1", replacement.path)
        assertTrue(replacement.getHeader("Range")!!.startsWith("bytes="))
        assertEquals("\"part-1\"", replacement.getHeader("If-Range"))
        val offset = fixture.store().partialSizeBytes() - fixture.partBytes[0].size
        assertTrue(offset in 1 until fixture.partBytes[1].size.toLong())

        fixture.enqueueRangePart(1, offset, "\"part-1-new\"")
        fixture.enqueueFullPart(2)
        val resumed = fixture.download()
        assertTrue(resumed.last() is GemmaInstallState.Installed)
        val request = fixture.server.takeRequest()
        assertEquals("/part-1", request.path)
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals("\"part-1-new\"", request.getHeader("If-Range"))
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `missing etag restarts only active part without discarding verified prefix`() = withMultipartFixture { fixture ->
        fixture.enqueueFullPart(0)
        fixture.enqueueFullPart(1, null)
        val token = GemmaDownloadCancellation()
        fixture.store().download(token) { state ->
            if (state is GemmaInstallState.Downloading && state.bytes > fixture.partBytes[0].size) token.cancel()
        }
        fixture.server.takeRequest()
        fixture.server.takeRequest()
        fixture.enqueueFullPart(1, "\"part-1-new\"")
        fixture.enqueueFullPart(2)
        val states = fixture.download()
        assertTrue(states.last() is GemmaInstallState.Installed)
        val request = fixture.server.takeRequest()
        assertEquals("/part-1", request.path)
        assertNull(request.getHeader("Range"))
        assertNull(request.getHeader("If-Range"))
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `multipart interruption between parts does not redownload completed part`() = withMultipartFixture { fixture ->
        fixture.enqueueFullPart(0)
        val token = GemmaDownloadCancellation()
        fixture.store().download(token) { state ->
            if (state is GemmaInstallState.Downloading && state.bytes == fixture.partBytes[0].size.toLong()) token.cancel()
        }
        assertEquals(fixture.partBytes[0].size.toLong(), fixture.store().partialSizeBytes())
        assertEquals("/part-0", fixture.server.takeRequest(2, TimeUnit.SECONDS)?.path)
        fixture.enqueueFullPart(1)
        fixture.enqueueFullPart(2)
        val resumed = fixture.download()
        assertTrue(resumed.last() is GemmaInstallState.Installed)
        assertEquals("/part-1", fixture.server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("/part-2", fixture.server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `mismatched multipart range is rejected without appending`() = withMultipartFixture { fixture ->
        fixture.pauseInsidePartOne()
        val offset = fixture.store().partialSizeBytes() - fixture.partBytes[0].size
        fixture.server.enqueue(
            fixture.rangeResponse(1, offset)
                .setHeader("Content-Range", "bytes 0-${fixture.partBytes[1].lastIndex}/${fixture.partBytes[1].size}"),
        )
        val states = fixture.download()
        assertTrue(states.last() is GemmaInstallState.Error)
        assertEquals(fixture.partBytes[0].size.toLong(), fixture.store().partialSizeBytes())
        assertNull(fixture.store().installedModel())
        val request = fixture.server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
    }

    @Test fun `changed multipart etag is rejected without appending`() = withMultipartFixture { fixture ->
        fixture.pauseInsidePartOne()
        val offset = fixture.store().partialSizeBytes() - fixture.partBytes[0].size
        fixture.server.enqueue(
            fixture.rangeResponse(1, offset).setHeader("ETag", "\"different-part\"")
        )
        val states = fixture.download()
        assertTrue(states.last() is GemmaInstallState.Error)
        assertEquals(fixture.partBytes[0].size.toLong(), fixture.store().partialSizeBytes())
        assertNull(fixture.store().installedModel())
    }

    @Test fun `multipart 416 resets only active part and retry drops stale range`() = withMultipartFixture { fixture ->
        fixture.pauseInsidePartOne()
        val prefix = fixture.partBytes[0].size.toLong()
        val offset = fixture.store().partialSizeBytes() - prefix
        fixture.enqueueRangeNotSatisfiable()
        val failed = fixture.download()
        assertTrue(failed.last() is GemmaInstallState.Error)
        val rejected = fixture.server.takeRequest()
        assertEquals("/part-1", rejected.path)
        assertEquals("bytes=$offset-", rejected.getHeader("Range"))
        assertEquals(prefix, fixture.store().partialSizeBytes())

        fixture.enqueueFullPart(1)
        fixture.enqueueFullPart(2)
        val resumed = fixture.download()
        assertTrue(resumed.last() is GemmaInstallState.Installed)
        val activeRetry = fixture.server.takeRequest()
        assertEquals("/part-1", activeRetry.path)
        assertNull(activeRetry.getHeader("Range"))
        assertNull(activeRetry.getHeader("If-Range"))
        assertEquals("/part-2", fixture.server.takeRequest().path)
        assertArrayEquals(fixture.bytes, checkNotNull(fixture.store().installedModel()).readBytes())
    }

    @Test fun `multipart receipt rejects a changed active part descriptor`() = withMultipartFixture { fixture ->
        fixture.pauseInsidePartOne()
        val changed = fixture.artifact.copy(
            parts = fixture.artifact.parts.mapIndexed { index, part ->
                if (index == 1) part.copy(url = fixture.server.url("/different-part").toString()) else part
            },
        )
        fixture.enqueueFullPart(0)
        fixture.enqueueFullPart(1)
        fixture.enqueueFullPart(2)
        val states = mutableListOf<GemmaInstallState>()
        GemmaModelStore(fixture.root, changed, fixture.client)
            .download(GemmaDownloadCancellation(), states::add)
        assertTrue(states.last() is GemmaInstallState.Installed)
        val restart = fixture.server.takeRequest()
        assertEquals("/part-0", restart.path)
        assertNull(restart.getHeader("Range"))
        assertEquals("/different-part", fixture.server.takeRequest().path)
        assertEquals("/part-2", fixture.server.takeRequest().path)
        assertArrayEquals(fixture.bytes, checkNotNull(GemmaModelStore(fixture.root, changed, fixture.client).installedModel()).readBytes())
    }

    @Test fun `bad part hash is never published`() = withMultipartFixture { fixture ->
        fixture.enqueueAllParts()
        val badArtifact = fixture.artifact.copy(
            parts = fixture.artifact.parts.mapIndexed { index, part ->
                if (index == 1) part.copy(sha256 = "0".repeat(64)) else part
            },
        )
        val states = mutableListOf<GemmaInstallState>()
        GemmaModelStore(fixture.root, badArtifact, fixture.client).download(GemmaDownloadCancellation(), states::add)
        assertTrue(states.last() is GemmaInstallState.Error)
        assertNull(GemmaModelStore(fixture.root, badArtifact, fixture.client).installedModel())
        assertEquals(0L, GemmaModelStore(fixture.root, badArtifact, fixture.client).partialSizeBytes())
    }

    @Test fun `bad final hash is never published after all parts verify`() = withMultipartFixture { fixture ->
        fixture.enqueueAllParts()
        val badArtifact = fixture.artifact.copy(sha256 = "f".repeat(64))
        val states = mutableListOf<GemmaInstallState>()
        GemmaModelStore(fixture.root, badArtifact, fixture.client).download(GemmaDownloadCancellation(), states::add)
        assertTrue(states.last() is GemmaInstallState.Error)
        assertNull(GemmaModelStore(fixture.root, badArtifact, fixture.client).installedModel())
        assertEquals(0L, GemmaModelStore(fixture.root, badArtifact, fixture.client).partialSizeBytes())
    }

    @Test fun `invalid multipart sizes are rejected before any request`() = withMultipartFixture { fixture ->
        val invalidArtifact = fixture.artifact.copy(parts = fixture.artifact.parts.dropLast(1))
        val states = mutableListOf<GemmaInstallState>()
        GemmaModelStore(fixture.root, invalidArtifact, fixture.client)
            .download(GemmaDownloadCancellation(), states::add)
        assertTrue(states.single() is GemmaInstallState.Error)
        assertEquals(0, fixture.server.requestCount)
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

    private class MultipartFixture(val root: File, val server: MockWebServer) {
        val bytes = ByteArray(192 * 1024) { ((it * 17 + 11) % 251).toByte() }
        val partBytes = listOf(
            bytes.copyOfRange(0, 64 * 1024),
            bytes.copyOfRange(64 * 1024, 128 * 1024),
            bytes.copyOfRange(128 * 1024, bytes.size),
        )
        val artifact = GemmaModelArtifact(
            server.url("/unused-final-url").toString(),
            "fixture-multipart.litertlm",
            bytes.size.toLong(),
            sha256(bytes),
            partBytes.mapIndexed { index, part ->
                GemmaModelArtifactPart(server.url("/part-$index").toString(), part.size.toLong(), sha256(part))
            },
        )
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

        fun store() = GemmaModelStore(root, artifact, client)
        fun response(index: Int, etag: String? = "\"part-$index\"") =
            MockResponse().setBody(Buffer().write(partBytes[index])).apply {
                if (etag != null) setHeader("ETag", etag)
            }
        fun enqueueFullPart(index: Int, etag: String? = "\"part-$index\"") = server.enqueue(response(index, etag))
        fun enqueueAllParts() { (0 until partBytes.size).forEach(::enqueueFullPart) }
        fun enqueueRangeNotSatisfiable() = server.enqueue(MockResponse().setResponseCode(416))
        fun rangeResponse(index: Int, offset: Long, etag: String = "\"part-$index\"") = MockResponse()
            .setResponseCode(206)
            .setBody(Buffer().write(partBytes[index].copyOfRange(offset.toInt(), partBytes[index].size)))
            .setHeader("Content-Range", "bytes $offset-${partBytes[index].lastIndex}/${partBytes[index].size}")
            .setHeader("ETag", etag)
        fun enqueueRangePart(index: Int, offset: Long, etag: String = "\"part-$index\"") = server.enqueue(rangeResponse(index, offset, etag))
        fun pauseInsidePartOne() {
            enqueueFullPart(0)
            enqueueFullPart(1)
            val token = GemmaDownloadCancellation()
            store().download(token) { state ->
                if (state is GemmaInstallState.Downloading && state.bytes > partBytes[0].size) token.cancel()
            }
            assertEquals("/part-0", server.takeRequest().path)
            assertEquals("/part-1", server.takeRequest().path)
            val offset = store().partialSizeBytes() - partBytes[0].size
            assertTrue(offset in 1 until partBytes[1].size.toLong())
        }
        fun download(): List<GemmaInstallState> = mutableListOf<GemmaInstallState>().also { states ->
            store().download(GemmaDownloadCancellation(), states::add)
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("gemma-model-store-test").toFile()
        val server = MockWebServer()
        server.start()
        try { block(Fixture(root, server)) } finally { server.shutdown(); root.deleteRecursively() }
    }

    private fun withMultipartFixture(block: (MultipartFixture) -> Unit) {
        val root = Files.createTempDirectory("gemma-model-store-multipart-test").toFile()
        val server = MockWebServer()
        server.start()
        try { block(MultipartFixture(root, server)) } finally { server.shutdown(); root.deleteRecursively() }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
