package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCredentialStoreInstrumentedTest {
    @Test
    fun saves_reads_from_a_new_store_instance_and_deletes_an_OpenRouter_credential() {
        val context = IsolatedPrefsContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val credential = "instrumented-openrouter-credential"
        try {
            val firstStore = SecureCredentialStore(context)

            assertEquals(CredentialSaveResult.Saved, firstStore.save(credential))
            assertTrue(firstStore.has())
            assertEquals(credential, SecureCredentialStore(context).load())
            assertTrue(firstStore.delete())
            assertFalse(SecureCredentialStore(context).has())
            assertNull(SecureCredentialStore(context).load())
        } finally {
            context.clearIsolatedPreferences()
        }
    }

    @Test
    fun delete_removes_the_legacy_plaintext_credential_so_it_cannot_be_migrated_again() {
        val context = IsolatedPrefsContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val store = SecureCredentialStore(context)
            assertEquals(CredentialSaveResult.Saved, store.save("encrypted-credential"))
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
                .putString(LEGACY_OPENROUTER_KEY, "legacy-credential")
                .commit()

            assertTrue(store.delete())

            assertFalse(context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).contains(CREDENTIAL_KEY))
            assertFalse(context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).contains(LEGACY_OPENROUTER_KEY))
            assertNull(SecureCredentialStore(context).load())
        } finally {
            context.clearIsolatedPreferences()
        }
    }

    @Test
    fun corrupted_ciphertext_returns_null_without_deleting_the_secure_slot() {
        val context = IsolatedPrefsContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val securePrefs = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        try {
            securePrefs.edit().putString(CREDENTIAL_KEY, "not-a-valid-envelope").commit()

            assertNull(SecureCredentialStore(context).load())

            assertEquals("not-a-valid-envelope", securePrefs.getString(CREDENTIAL_KEY, null))
        } finally {
            context.clearIsolatedPreferences()
        }
    }

    private class IsolatedPrefsContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$name.$TEST_SUFFIX", mode)

        fun clearIsolatedPreferences() {
            listOf("whisperpin_secure", "phonewhisper").forEach { name ->
                getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    private companion object {
        const val TEST_SUFFIX = "secure-credential-instrumented-test"
        const val SECURE_PREFS = "whisperpin_secure"
        const val LEGACY_PREFS = "phonewhisper"
        const val CREDENTIAL_KEY = "credential_openrouter"
        const val LEGACY_OPENROUTER_KEY = "openrouter_key"
    }
}
