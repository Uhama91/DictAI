package com.kafkasl.phonewhisper

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSessionStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: AccountSessionStore
    private val session = AccountSession("10000000-0000-4000-8000-000000000001", "teacher@example.test", "private-access-token", "private-refresh-token", 5000)
    @Before fun before() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("account-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = AccountSessionStore(prefs) { key }
    }
    @Test fun tokensAreEncryptedAndSurviveReopening() {
        assertTrue(store.saveSession(session, store.generation()))
        assertEquals(session, AccountSessionStore(prefs) { key }.session())
        assertFalse(prefs.all.toString().contains(session.accessToken))
        assertFalse(prefs.all.toString().contains(session.refreshToken))
    }
    @Test fun disconnectInvalidatesOldNetworkResponsesAndKeepsOtherPreferences() {
        val before = store.generation()
        store.saveSession(session, before)
        prefs.edit().putString("unrelated", "keep").commit()
        store.disconnect()
        assertNull(store.session())
        assertTrue(store.generation() > before)
        assertFalse(store.saveSession(session, before))
        assertEquals("keep", prefs.getString("unrelated", null))
    }
    @Test fun replacingAnAttemptInvalidatesPreviousOAuthResponse() {
        val first = AccountOAuthAttempt("a".repeat(43), "b".repeat(43), 1000)
        val second = AccountOAuthAttempt("c".repeat(43), "d".repeat(43), 2000)
        val oldGeneration = store.beginAttempt(first)
        val newGeneration = store.beginAttempt(second)
        assertTrue(newGeneration > oldGeneration)
        assertFalse(store.saveSession(session, oldGeneration))
        assertEquals(second, store.attempt())
        assertTrue(store.clearAttempt(newGeneration))
        assertNull(store.attempt())
    }
    @Test fun encryptedCorruptionIsReportedAndRetained() {
        store.saveSession(session, store.generation())
        val encryptedKeys = prefs.all.filterValues { it is String && it != "keep" }.keys
        assertTrue(encryptedKeys.isNotEmpty())
        encryptedKeys.forEach { prefs.edit().putString(it, "corrupt-ciphertext").commit() }
        assertThrows(IllegalStateException::class.java) { store.session() }
        assertTrue(prefs.all.values.contains("corrupt-ciphertext"))
    }
    @Test fun disconnectWorksEvenAfterKeystoreLoss() {
        store.saveSession(session, store.generation())
        val inaccessible = AccountSessionStore(prefs) { error("Keystore unavailable") }
        inaccessible.disconnect()
        assertNull(inaccessible.session())
    }
    @Test fun sessionAndAttemptToStringNeverExposeTokens() {
        assertFalse(session.toString().contains(session.accessToken))
        assertFalse(AccountOAuthAttempt("verifier", "state", 1000).toString().contains("verifier"))
    }
}
