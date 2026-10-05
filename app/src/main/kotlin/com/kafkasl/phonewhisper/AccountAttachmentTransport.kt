package com.kafkasl.phonewhisper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

internal interface AccountAttachmentTransport {
    fun upload(accessToken: String, userId: String, sha256: String, bytes: ByteArray)
    fun download(accessToken: String, userId: String, sha256: String): ByteArray
    fun upload(accessToken: String, userId: String, sha256: String, source: File) {
        val bytes = ByteArrayOutputStream()
        source.inputStream().use { copyVerifiedImage(it, sha256) { buffer, count -> bytes.write(buffer, 0, count) } }
        upload(accessToken, userId, sha256, bytes.toByteArray())
    }
    fun download(accessToken: String, userId: String, sha256: String, destination: File) {
        val bytes = download(accessToken, userId, sha256)
        publishAttachment(destination) { output -> output.write(bytes) }
    }
}

/** Create-only private originals. A repeated upload is accepted only after verifying existing bytes. */
internal class SupabaseAccountAttachmentTransport(
    private val baseUrl: HttpUrl,
    private val publishableKey: String,
    client: OkHttpClient = defaultClient(),
) : AccountAttachmentTransport {
    private val http = client.newBuilder()
        .connectTimeout(bounded(client.connectTimeoutMillis, 10_000), TimeUnit.MILLISECONDS)
        .readTimeout(bounded(client.readTimeoutMillis, 30_000), TimeUnit.MILLISECONDS)
        .writeTimeout(bounded(client.writeTimeoutMillis, 30_000), TimeUnit.MILLISECONDS)
        .callTimeout(bounded(client.callTimeoutMillis, 120_000), TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    init {
        require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "A publishable API key is required" }
        require(baseUrl.scheme == "https" || baseUrl.host in setOf("localhost", "127.0.0.1", "::1")) { "HTTPS is required" }
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty() && baseUrl.query == null && baseUrl.fragment == null) {
            "Invalid attachment endpoint"
        }
    }

    override fun upload(accessToken: String, userId: String, sha256: String, bytes: ByteArray) {
        identifiers(userId, sha256)
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "Invalid attachment size" }
        try { copyVerifiedImage(ByteArrayInputStream(bytes), sha256) { _, _ -> } }
        catch (_: IOException) { throw IllegalArgumentException("Invalid attachment bytes or digest") }
        post(accessToken, userId, sha256, bytes.toRequestBody(JPEG))
    }

    override fun upload(accessToken: String, userId: String, sha256: String, source: File) {
        identifiers(userId, sha256)
        require(source.isFile && source.length() in 1..MAX_IMAGE_BYTES.toLong()) { "Invalid attachment source" }
        val size = source.length()
        try { source.inputStream().use { copyVerifiedImage(it, sha256) { _, _ -> } } }
        catch (_: IOException) { throw IllegalArgumentException("Invalid attachment bytes or digest") }
        val body = object : RequestBody() {
            override fun contentType() = JPEG
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                source.inputStream().use { input ->
                    val written = copyVerifiedImage(input, sha256) { buffer, count -> sink.write(buffer, 0, count) }
                    if (written != size) throw IOException("Attachment source changed")
                }
            }
        }
        post(accessToken, userId, sha256, body)
    }

    override fun download(accessToken: String, userId: String, sha256: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        receive(accessToken, userId, sha256) { buffer, count -> bytes.write(buffer, 0, count) }
        return bytes.toByteArray()
    }

    override fun download(accessToken: String, userId: String, sha256: String, destination: File) {
        identifiers(userId, sha256)
        publishAttachment(destination) { output ->
            receive(accessToken, userId, sha256) { buffer, count -> output.write(buffer, 0, count) }
        }
    }

    private fun post(token: String, user: String, sha: String, body: RequestBody) {
        val request = authorized(endpoint(user, sha, false), token).header("x-upsert", "false")
            .post(body).build()
        val duplicate = http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> false
                response.code == 400 || response.code == 409 -> true
                else -> throw SyncHttpException(response.code)
            }
        }
        // Supabase standard uploads report duplicate paths as 400; other API versions may use 409.
        if (duplicate) receive(token, user, sha) { _, _ -> }
    }

    private fun receive(token: String, user: String, sha: String, write: (ByteArray, Int) -> Unit) {
        identifiers(user, sha)
        http.newCall(authorized(endpoint(user, sha, true), token).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw SyncHttpException(response.code)
            val body = response.body ?: throw IOException("Attachment response missing")
            if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("Attachment size limit exceeded")
            val type = body.contentType()
            if (type?.type != "image" || type.subtype != "jpeg") throw IOException("Attachment media type invalid")
            body.byteStream().use { copyVerifiedImage(it, sha, write) }
        }
    }

    private fun endpoint(user: String, sha: String, authenticated: Boolean): HttpUrl = baseUrl.newBuilder()
        .addPathSegments(if (authenticated) "storage/v1/object/authenticated" else "storage/v1/object")
        .addPathSegment(BUCKET).addPathSegment(user).addPathSegment("$sha.jpg").build()

    private fun authorized(url: HttpUrl, token: String): Request.Builder {
        require(token.length in 1..16384 && token.all { it.code in 0x21..0x7e }) { "Invalid attachment credential" }
        return Request.Builder().url(url).header("apikey", publishableKey).header("Authorization", "Bearer $token")
    }

    private fun identifiers(user: String, sha: String) {
        require(SyncDocument.isActor(user) && SHA.matches(sha)) { "Invalid attachment identifier" }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val BUCKET = "dictai-note-images"
        private val SHA = Regex("[0-9a-f]{64}")
        private val JPEG = "image/jpeg".toMediaType()
        private fun bounded(value: Int, maximum: Int): Long = (if (value > 0) minOf(value, maximum) else maximum).toLong()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}

/** Verify incrementally, including chunked responses whose content length is unknown. */
private fun copyVerifiedImage(input: InputStream, expectedSha: String, write: (ByteArray, Int) -> Unit): Long {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    val first = ByteArray(3)
    var count = 0
    var penultimate = -1
    var last = -1
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, SupabaseAccountAttachmentTransport.MAX_IMAGE_BYTES - count + 1))
        if (read < 0) break
        if (read == 0) continue
        if (count + read > SupabaseAccountAttachmentTransport.MAX_IMAGE_BYTES) throw IOException("Attachment size limit exceeded")
        for (index in 0 until minOf(read, (3 - count).coerceAtLeast(0))) first[count + index] = buffer[index]
        if (read >= 2) penultimate = buffer[read - 2].toInt() and 0xff else penultimate = last
        last = buffer[read - 1].toInt() and 0xff
        digest.update(buffer, 0, read)
        write(buffer, read)
        count += read
    }
    if (count < 5 || first[0] != 0xff.toByte() || first[1] != 0xd8.toByte() || first[2] != 0xff.toByte() ||
        penultimate != 0xff || last != 0xd9) throw IOException("Attachment JPEG data invalid")
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    if (hex != expectedSha) throw IOException("Attachment digest mismatch")
    return count.toLong()
}

/** Publish only after network completion and verification; never replace an original with a partial download. */
private fun publishAttachment(destination: File, write: (FileOutputStream) -> Unit) {
    val parent = destination.absoluteFile.parentFile ?: throw IOException("Attachment destination invalid")
    if (!parent.isDirectory) throw IOException("Attachment destination unavailable")
    val temporary = File.createTempFile("dictai-attachment-", ".part", parent)
    try {
        FileOutputStream(temporary).use { output -> write(output); output.fd.sync() }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
