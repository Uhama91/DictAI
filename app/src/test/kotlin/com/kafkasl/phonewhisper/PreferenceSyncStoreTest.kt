package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferenceSyncStoreTest {
    private lateinit var phone: Context
    private lateinit var tablet: Context
    private val account = "user-a"
    private val remoteActor = "00000000-0000-0000-0000-000000000001"
    private val formatId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Before fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        phone = DeviceContext(application, "phone-${UUID.randomUUID()}")
        tablet = DeviceContext(application, "tablet-${UUID.randomUUID()}")
    }

    @Test fun unpersistedDefaultsDoNotDefeatAnExistingRemoteChoice() {
        val store = PreferenceSyncStore(tablet, account)
        assertTrue(store.captureLocal().liveValues().isEmpty())
        store.mergeRemote(listOf(remote(mapOf("pref:dictation_language" to "en", "pref:trailing_space" to "true"))))
        assertEquals("en", prefs(tablet).getString("dictation_language", null))
        assertTrue(prefs(tablet).getBoolean("trailing_space", false))
        assertEquals(mapOf("pref:dictation_language" to "en", "pref:trailing_space" to "true"), store.captureLocal().liveValues())
    }

    @Test fun exportsAnExplicitAllowlistWithoutSecretsModelsPermissionsOrGeometry() {
        prefs(phone).edit().putString("dictation_language", "en").putString("number_style", "WORDS")
            .putBoolean("show_transcript", false).putBoolean("light_text_cleanup", false)
            .putBoolean("trailing_space", true).putString("theme_mode", "dark")
            .putString("formatting_engine", "cloud").putBoolean("cloud_cleanup_enabled", true)
            .putString("cloud_cleanup_model", "gpt-5-4-nano")
            .putString("vocab_raw", "didi => Dydy")
            .putString("last_error", "private diagnostic").putInt("btn_x", 99)
            .putBoolean("onb_complete", true).commit()
        phone.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE).edit()
            .putString("model_name", "private-model").putString("openrouter_key", "private-api-key").commit()
        phone.getSharedPreferences("whisperpin_secure", Context.MODE_PRIVATE).edit()
            .putString("credential_openrouter", "encrypted-secret").commit()
        phone.getSharedPreferences("dictation_draft", Context.MODE_PRIVATE).edit().putString("text", "private draft").commit()
        val values = PreferenceSyncStore(phone, account).captureLocal().liveValues()
        assertEquals("en", values["pref:dictation_language"])
        assertEquals("WORDS", values["pref:number_style"])
        assertEquals("false", values["pref:show_transcript"])
        assertEquals("true", values["pref:trailing_space"])
        assertEquals("false", values["pref:light_text_cleanup"])
        assertEquals("dark", values["pref:theme_mode"])
        assertEquals("cloud", values["pref:formatting_engine"])
        assertEquals("gpt-5-4-nano", values["pref:cloud_cleanup_model"])
        assertEquals("didi => Dydy", SyncVocabulary.render(values))
        assertFalse(values.containsKey("pref:cloud_cleanup_enabled"))
        val exported = values.toString()
        for (secret in listOf("private", "secret", "btn_x", "onb_complete", "model_name")) assertFalse(exported.contains(secret))
    }

    @Test fun vocabularyAndFormatsMergeThenDeletionsSurviveAnOlderRemoteDocument() {
        prefs(phone).edit().putString("vocab_raw", "didi => Dydy").commit()
        formats(phone).edit().putString("custom", JSONArray().put(JSONObject().put("id", formatId)
            .put("name", "Compte rendu").put("instructions", "Conserver tous les faits.")).toString())
            .putString("selected", formatId).commit()
        val source = PreferenceSyncStore(phone, account)
        val initial = source.captureLocal()
        val target = PreferenceSyncStore(tablet, account)
        target.mergeRemote(listOf(initial))
        assertEquals("didi => Dydy", prefs(tablet).getString("vocab_raw", null))
        assertEquals("Compte rendu", PostProcessingFormats(tablet).custom().single().name)
        assertEquals(formatId, PostProcessingFormats(tablet).selected().id)
        prefs(phone).edit().putString("vocab_raw", "").commit()
        formats(phone).edit().putString("custom", "[]").putString("selected", "cleanup").commit()
        target.mergeRemote(listOf(source.captureLocal(), initial))
        assertEquals("", prefs(tablet).getString("vocab_raw", ""))
        assertTrue(PostProcessingFormats(tablet).custom().isEmpty())
        assertEquals("cleanup", PostProcessingFormats(tablet).selected().id)
        assertFalse(target.captureLocal().liveValues().containsKey("format:$formatId"))
    }

    @Test fun localChangesMadeAfterFetchRemainInTheMergedDocument() {
        val store = PreferenceSyncStore(phone, account)
        val fetched = remote(mapOf("pref:dictation_language" to "en"))
        prefs(phone).edit().putBoolean("trailing_space", true).commit()
        val merged = store.mergeRemote(listOf(fetched))
        assertEquals("en", merged.liveValues()["pref:dictation_language"])
        assertEquals("true", merged.liveValues()["pref:trailing_space"])
        assertTrue(prefs(phone).getBoolean("trailing_space", false))
    }

    @Test fun anInvalidRemoteDocumentNeverPartiallyAppliesValidFields() {
        prefs(phone).edit().putString("dictation_language", "fr").commit()
        val store = PreferenceSyncStore(phone, account)
        val before = store.captureLocal().toJson()
        val badDocuments = listOf(
            mapOf("pref:dictation_language" to "en", "pref:api_key" to "secret"),
            mapOf("pref:dictation_language" to "en", "pref:trailing_space" to "yes"),
            mapOf("pref:dictation_language" to "en", "pref:theme_mode" to "sepia"),
            mapOf("pref:dictation_language" to "en", "pref:cloud_cleanup_model" to "untrusted-model"),
            mapOf("pref:dictation_language" to "en", "format:$formatId" to "{\"name\":\"\",\"instructions\":\"x\"}"),
        )
        for (values in badDocuments) {
            assertThrows(IllegalArgumentException::class.java) { store.mergeRemote(listOf(remote(values))) }
            assertEquals("fr", prefs(phone).getString("dictation_language", null))
            assertEquals(before, store.current().toJson())
            assertFalse(prefs(phone).contains("api_key"))
        }
    }

    @Test fun actorAndJournalSurviveReopeningAndArePartitionedByAccount() {
        prefs(phone).edit().putString("dictation_language", "en").commit()
        val first = PreferenceSyncStore(phone, account)
        val captured = first.captureLocal()
        val reopened = PreferenceSyncStore(phone, account)
        assertEquals(first.actor, reopened.actor)
        UUID.fromString(reopened.actor)
        assertEquals(captured.toJson(), reopened.current().toJson())
        val otherAccount = PreferenceSyncStore(phone, "user-b")
        assertNotEquals(first.actor, otherAccount.actor)
        assertTrue(otherAccount.current().liveValues().isEmpty())
    }

    @Test fun unknownKeysAreRejectedEvenWhenTheirEntryIsADeletion() {
        val store = PreferenceSyncStore(phone, account)
        val malformed = SyncDocument(mapOf("pref:api_key" to SyncEntry(null, SyncStamp(1, remoteActor))))
        assertThrows(IllegalArgumentException::class.java) { store.mergeRemote(listOf(malformed)) }
        assertTrue(store.current().liveValues().isEmpty())
        assertFalse(prefs(phone).contains("api_key"))
    }

    @Test fun pendingRecoveryCompletesAnInterruptedApplyBeforeCapturingLocalChanges() {
        val store = PreferenceSyncStore(phone, account)
        val old = store.captureLocal()
        val pending = remote(mapOf("pref:dictation_language" to "en", "pref:theme_mode" to "dark"))
        journal(phone).edit().putString("current", old.toJson()).putString("pending", pending.toJson()).commit()
        // Simulate a crash between the two preference files or their individual writes.
        prefs(phone).edit().putString("dictation_language", "en").commit()
        val reopened = PreferenceSyncStore(phone, account)
        assertEquals("dark", prefs(phone).getString("theme_mode", null))
        assertEquals(pending.toJson(), reopened.captureLocal().toJson())
        assertFalse(journal(phone).contains("pending"))
        assertEquals(pending.toJson(), journal(phone).getString("current", null))
    }

    @Test fun identicalVocabularySemanticsDoNotRewriteTheExistingRawText() {
        val raw = "# Mes noms\n  DIDI  => Dydy\n\n"
        prefs(phone).edit().putString("vocab_raw", raw).commit()
        val store = PreferenceSyncStore(phone, account)
        val initial = store.captureLocal()
        store.mergeRemote(listOf(initial))
        assertEquals(raw, prefs(phone).getString("vocab_raw", null))
    }

    @Test fun capturingMergedDuplicateVocabularyDoesNotInventLocalEdits() {
        // Independent replicas can validly contribute ordinary and ambiguous-group IDs.
        val mixed = SyncVocabulary.entries("didi => Dydy") +
            SyncVocabulary.entries("didi => Dydy\ndidi => Didier")
        val received = remote(mixed)
        val store = PreferenceSyncStore(phone, account)
        store.mergeRemote(listOf(received))
        assertEquals(3, prefs(phone).getString("vocab_raw", "").orEmpty().lines().size)
        assertEquals(received.toJson(), store.captureLocal().toJson())
        assertEquals(received.toJson(), PreferenceSyncStore(phone, account).captureLocal().toJson())
    }

    @Test fun aFailedPreferenceCommitIsRetriedDurablyEvenIfItUpdatedTheMemoryMap() {
        var commits = 0
        val failingDevice = object : ContextWrapper(phone) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = super.getSharedPreferences(name, mode)
                if (name != "whisperpin") return actual
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                commits++
                                if (commits == 1) {
                                    // Android may expose the edited memory map even when disk commit fails.
                                    editor.apply()
                                    return false
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val store = PreferenceSyncStore(failingDevice, account)
        assertThrows(IllegalStateException::class.java) {
            store.mergeRemote(listOf(remote(mapOf("pref:dictation_language" to "en"))))
        }
        assertTrue(journal(phone).contains("pending"))
        assertEquals("en", prefs(phone).getString("dictation_language", null))
        val reopened = PreferenceSyncStore(failingDevice, account)
        assertTrue("Recovery must retry the source-file commit before clearing the receipt", commits >= 2)
        assertEquals("en", reopened.captureLocal().liveValues()["pref:dictation_language"])
        assertFalse(journal(phone).contains("pending"))
    }

    @Test fun aFailedJournalCommitIsRetriedBeforeReturningAnUploadDocument() {
        var currentCommits = 0
        val failingDevice = object : ContextWrapper(phone) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = super.getSharedPreferences(name, mode)
                if (!name.startsWith("dictai_sync_journal_")) return actual
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        var writesCurrent = false
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                if (key == "current") writesCurrent = true
                                return this
                            }
                            override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                            override fun commit(): Boolean {
                                if (writesCurrent || actual.contains("current")) {
                                    currentCommits++
                                    if (currentCommits == 1) { editor.apply(); return false }
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        prefs(phone).edit().putString("dictation_language", "en").commit()
        val store = PreferenceSyncStore(failingDevice, account)
        assertThrows(IllegalStateException::class.java) { store.captureLocal() }
        assertEquals("en", store.captureLocal().liveValues()["pref:dictation_language"])
        assertTrue("Returning an upload document requires a durable journal retry", currentCommits >= 2)
    }

    private fun remote(values: Map<String, String>) = SyncDocument(emptyMap()).record(values, remoteActor)
    private fun prefs(context: Context) = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
    private fun formats(context: Context) = context.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
    private fun journal(context: Context): SharedPreferences {
        val scope = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences("dictai_sync_journal_$scope", Context.MODE_PRIVATE)
    }
    private class DeviceContext(base: Context, private val device: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("${device}_$name", mode)
    }
}
