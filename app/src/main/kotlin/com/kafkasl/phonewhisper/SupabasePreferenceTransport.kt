package com.kafkasl.phonewhisper

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal data class SyncReplica(val actor: String, val document: String) {
    override fun toString() = "SyncReplica(actor=$actor, document=redacted)"
}
internal class SyncHttpException(val code: Int) : IOException("Preference sync HTTP $code")

internal interface PreferenceReplicaTransport {
    fun readReplicas(accessToken: String, userId: String): List<SyncReplica>
    fun writeReplica(accessToken: String, userId: String, actor: String, json: String)
}

/** Each installation writes its own snapshot. Account authorization is also enforced by server RLS. */
internal class SupabasePreferenceTransport(
    baseUrl: HttpUrl,
    private val publishableKey: String,
    client: OkHttpClient = defaultClient(),
) : PreferenceReplicaTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val endpoint = baseUrl.newBuilder().addPathSegments("rest/v1/dictai_preference_replicas").build()

    init {
        require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "A publishable API key is required" }
        require(baseUrl.scheme == "https" || baseUrl.host in setOf("localhost", "127.0.0.1", "::1")) { "HTTPS is required" }
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty() && baseUrl.query == null && baseUrl.fragment == null) {
            "Invalid preference sync endpoint"
        }
    }

    override fun readReplicas(accessToken: String, userId: String): List<SyncReplica> {
        requireUuid(userId)
        val replicas = mutableListOf<SyncReplica>()
        val seen = mutableSetOf<String>()
        var total: Int? = null
        var receivedBytes = 0
        do {
            val offset = replicas.size
            val end = minOf(offset + PAGE_SIZE - 1, MAX_REPLICAS - 1)
            val url = endpoint.newBuilder().addQueryParameter("select", "device_id,document")
                .addQueryParameter("user_id", "eq.$userId").addQueryParameter("order", "device_id.asc").build()
            val request = authorized(url, accessToken).header("Prefer", "count=exact")
                .header("Range-Unit", "items").header("Range", "$offset-$end").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SyncHttpException(response.code)
                // Bound the whole account as well as individual pages before retaining or parsing it.
                val bytes = readBounded(response.body, minOf(MAX_PAGE_BYTES, MAX_REMOTE_BYTES - receivedBytes))
                receivedBytes += bytes.size
                val rows = parseJson(bytes.toString(Charsets.UTF_8)) as? JSONArray ?: invalid()
                val count = verifyRange(response.header("Content-Range"), offset, end, rows.length())
                if (total != null && count != total) invalid()
                total = count
                for (index in 0 until rows.length()) {
                    val row = rows.opt(index) as? JSONObject ?: invalid()
                    val actor = row.opt("device_id") as? String ?: invalid()
                    if (!UUID_PATTERN.matches(actor) || !seen.add(actor)) invalid()
                    val document = row.opt("document") as? JSONObject ?: invalid()
                    val json = document.toString()
                    if (json.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) exceeded()
                    replicas += SyncReplica(actor, json)
                }
            }
        } while (replicas.size < total!!)
        return replicas
    }

    override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) {
        requireUuid(userId)
        requireUuid(actor)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) exceeded()
        val document = parseJson(json) as? JSONObject ?: invalid()
        if (document.toString().toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) exceeded()
        val body = JSONObject().put("user_id", userId).put("device_id", actor).put("document", document)
        val url = endpoint.newBuilder().addQueryParameter("on_conflict", "user_id,device_id").build()
        val request = authorized(url, accessToken).header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncHttpException(response.code)
        }
    }

    private fun authorized(url: HttpUrl, token: String): Request.Builder {
        require(token.isNotBlank() && token.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid preference sync credential" }
        return Request.Builder().url(url).header("apikey", publishableKey).header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
    }

    /** Require exact counts so a configured server row limit never produces a misleading complete merge. */
    private fun verifyRange(header: String?, offset: Int, requestedEnd: Int, rows: Int): Int {
        if (header == "*/0" && offset == 0 && rows == 0) return 0
        val match = header?.let { RANGE_PATTERN.matchEntire(it) } ?: invalid()
        val (startText, endText, totalText) = match.destructured
        val start = startText.toIntOrNull() ?: invalid()
        val end = endText.toIntOrNull() ?: invalid()
        val total = totalText.toIntOrNull() ?: invalid()
        if (total > MAX_REPLICAS) exceeded()
        if (total <= 0 || start != offset || end < start || end > requestedEnd || end >= total || rows != end - start + 1) invalid()
        return total
    }

    private fun readBounded(body: ResponseBody?, limit: Int): ByteArray {
        val responseBody = body ?: invalid()
        if (responseBody.contentLength() > limit) exceeded()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        responseBody.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (read == -1) break
                if (output.size() + read > limit) exceeded()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun parseJson(raw: String): Any? {
        // Bound parser recursion before org.json sees untrusted JSON (the v1 envelope uses six levels).
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 16) invalid()
                '}', ']' -> if (--depth < 0) invalid()
            }
        }
        if (quoted || depth != 0) invalid()
        return try {
            val tokener = JSONTokener(raw)
            val parsed = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') invalid()
            parsed
        } catch (_: JSONException) { invalid() }
    }

    private fun requireUuid(value: String) { require(UUID_PATTERN.matches(value)) { "Invalid preference sync identifier" } }
    private fun invalid(): Nothing = throw IOException("Invalid preference sync response")
    private fun exceeded(): Nothing = throw IOException("Preference sync limit exceeded")

    companion object {
        private const val MAX_DOCUMENT_BYTES = SyncDocument.MAX_BYTES
        private const val MAX_REPLICAS = 100
        private const val PAGE_SIZE = 1
        private const val MAX_PAGE_BYTES = PAGE_SIZE * (MAX_DOCUMENT_BYTES + 4096)
        private const val MAX_REMOTE_BYTES = 32 * 1024 * 1024
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val RANGE_PATTERN = Regex("([0-9]+)-([0-9]+)/([0-9]+)")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
