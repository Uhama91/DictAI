package com.kafkasl.phonewhisper

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class AccountSession(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    init {
        require(SyncDocument.isActor(userId)) { "Invalid account identity" }
        require(email.length in 1..320 && email.none { it.code < 32 }) { "Invalid account email" }
        require(listOf(accessToken, refreshToken).all { it.length in 1..16384 && it.none { char -> char.code < 32 } }) { "Invalid account credentials" }
        require(expiresAt > 0) { "Invalid account expiry" }
    }
    override fun toString() = "AccountSession(userId=$userId, tokens=redacted)"
}
internal data class AccountOAuthAttempt(val verifier: String, val state: String, val startedAt: Long) {
    override fun toString() = "AccountOAuthAttempt(redacted)"
}
internal class SupabaseAccountAuth(private val baseUrl: HttpUrl, private val publishableKey: String, client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    init {
        require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "A public account key is required" }
        require(baseUrl.scheme == "https" || baseUrl.host in setOf("localhost", "127.0.0.1", "::1")) { "HTTPS is required" }
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty() && baseUrl.query == null && baseUrl.fragment == null) {
            "Invalid account endpoint"
        }
    }

    fun newAttempt(now: Long): AccountOAuthAttempt {
        fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return AccountOAuthAttempt(nonce(), nonce(), now)
    }
    fun authorizeUrl(attempt: AccountOAuthAttempt): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("auth/v1/authorize").addQueryParameter("provider", "google")
        .addQueryParameter("redirect_to", CALLBACK + "?flow=" + attempt.state)
        .addQueryParameter("code_challenge", challenge(attempt.verifier))
        .addQueryParameter("code_challenge_method", "s256").addQueryParameter("prompt", "select_account").build()

    fun callbackCode(callback: String, attempt: AccountOAuthAttempt, now: Long): String {
        require(now >= attempt.startedAt && now - attempt.startedAt <= 600_000) { "Account connection expired" }
        val uri = try { URI(callback) } catch (_: Exception) { throw IllegalArgumentException("Invalid account callback") }
        require(uri.scheme == "com.uhama.whisperpin" && uri.host == "auth" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.path == "/callback" && uri.rawFragment == null) { "Invalid account callback" }
        val params = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { item ->
            val pair = item.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            require(key !in params) { "Duplicate account callback parameter" }
            params[key] = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(params["flow"] == attempt.state && params["error"] == null) { "Account connection refused" }
        return params["code"]?.takeIf { it.length in 1..2048 && it.all { char -> char.isLetterOrDigit() || char in "-_." } }
            ?: throw IllegalArgumentException("Account callback has no authorization code")
    }

    fun exchange(code: String, attempt: AccountOAuthAttempt, now: Long): AccountSession {
        require(now >= attempt.startedAt && now - attempt.startedAt <= 600_000) { "Account connection expired" }
        require(attempt.verifier.length in 43..128) { "Invalid account verifier" }
        return token("pkce", JSONObject().put("auth_code", code).put("code_verifier", attempt.verifier), now)
    }
    fun refresh(session: AccountSession, now: Long): AccountSession =
        token("refresh_token", JSONObject().put("refresh_token", session.refreshToken), now).also {
            require(it.userId == session.userId) { "Account identity changed during refresh" }
        }
    fun revoke(session: AccountSession) {
        val url = baseUrl.newBuilder().addPathSegments("auth/v1/logout").addQueryParameter("scope", "local").build()
        val request = Request.Builder().url(url).header("apikey", publishableKey)
            .header("Authorization", "Bearer " + session.accessToken).post("{}".toRequestBody(JSON)).build()
        http.newCall(request).execute().use { if (!it.isSuccessful) throw SyncHttpException(it.code) }
    }
    private fun token(grant: String, body: JSONObject, now: Long): AccountSession {
        val url = baseUrl.newBuilder().addPathSegments("auth/v1/token").addQueryParameter("grant_type", grant).build()
        val request = Request.Builder().url(url).header("apikey", publishableKey).post(body.toString().toRequestBody(JSON)).build()
        val raw = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncHttpException(response.code)
            val source = response.body?.source() ?: throw IOException("Account response missing")
            if (source.request(65537)) throw IOException("Account response too large")
            source.readUtf8()
        }
        try {
            var depth = 0
            var quoted = false
            var escaped = false
            raw.forEach { char ->
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 16) throw IOException("Account response too deeply nested") }
                    '}', ']' -> { depth--; if (depth < 0) throw IOException("Account response invalid") }
                }
            }
            if (depth != 0 || quoted) throw IOException("Account response invalid")
            val value = JSONObject(raw)
            val lifetime = value.getLong("expires_in")
            require(lifetime in 1..604800) { "Invalid account expiry" }
            val user = value.getJSONObject("user")
            return AccountSession(user.getString("id"), user.getString("email"), value.getString("access_token"),
                value.getString("refresh_token"), Math.addExact(now, lifetime * 1000))
        } catch (e: org.json.JSONException) {
            throw IOException("Account response invalid")
        }
    }
    companion object {
        const val CALLBACK = "com.uhama.whisperpin://auth/callback"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
