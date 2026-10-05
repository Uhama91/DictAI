package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import org.json.JSONObject

internal class AccountSessionStore internal constructor(private val prefs: SharedPreferences, private val keyProvider: () -> SecretKey) {
    constructor(context: Context) : this(context.getSharedPreferences(backendStoreName(), Context.MODE_PRIVATE), ::accountKey)
    fun generation(): Long = synchronized(LOCK) { prefs.getLong("generation", 0) }
    fun session(): AccountSession? = synchronized(LOCK) {
        decrypted("session")?.let {
            try {
                AccountSession(it.getString("userId"), it.getString("email"), it.getString("accessToken"),
                    it.getString("refreshToken"), it.getLong("expiresAt"))
            } catch (_: Exception) { throw IllegalStateException("Stored account session invalid") }
        }
    }
    fun attempt(): AccountOAuthAttempt? = synchronized(LOCK) {
        decrypted("attempt")?.let {
            try { AccountOAuthAttempt(it.getString("verifier"), it.getString("state"), it.getLong("startedAt")) }
            catch (_: Exception) { throw IllegalStateException("Stored account connection invalid") }
        }
    }
    fun beginAttempt(attempt: AccountOAuthAttempt): Long = synchronized(LOCK) {
        val next = Math.addExact(generation(), 1)
        val value = JSONObject().put("verifier", attempt.verifier).put("state", attempt.state).put("startedAt", attempt.startedAt)
        check(prefs.edit().putLong("generation", next).putString("attempt", encrypted(value)).commit()) { "Account connection storage failed" }
        next
    }
    fun clearAttempt(expectedGeneration: Long): Boolean = synchronized(LOCK) {
        generation() == expectedGeneration && prefs.edit().remove("attempt").commit()
    }
    fun saveSession(session: AccountSession, expectedGeneration: Long): Boolean = synchronized(LOCK) {
        if (generation() != expectedGeneration) return false
        val value = JSONObject().put("userId", session.userId).put("email", session.email)
            .put("accessToken", session.accessToken).put("refreshToken", session.refreshToken).put("expiresAt", session.expiresAt)
        check(prefs.edit().putString("session", encrypted(value)).commit()) { "Account session storage failed" }
        true
    }
    fun disconnect() = synchronized(LOCK) {
        check(prefs.edit().remove("session").remove("attempt").putLong("generation", Math.addExact(generation(), 1)).commit()) {
            "Account disconnection storage failed"
        }
    }
    private fun encrypted(value: JSONObject): String = AesGcmCodec.encrypt(keyProvider(), value.toString())
    private fun decrypted(key: String): JSONObject? {
        val stored = prefs.getString(key, null) ?: return null
        val raw = AesGcmCodec.decrypt(keyProvider(), stored) ?: throw IllegalStateException("Stored account credentials unavailable")
        return try { JSONObject(raw) } catch (_: Exception) { throw IllegalStateException("Stored account credentials invalid") }
    }
    companion object {
        private val LOCK = Any()
        private const val ALIAS = "dictai.account.session.aes.v1"
        // A build switching hosting projects must never send the previous project's refresh token.
        private fun backendStoreName(): String = "dictai_account_secure_" + MessageDigest.getInstance("SHA-256")
            .digest(BuildConfig.SYNC_SUPABASE_URL.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun accountKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            check(!store.containsAlias(ALIAS)) { "Account keystore alias invalid" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}
