package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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
class AccountNotesSyncStoreTest {
    private lateinit var phone: Context
    private lateinit var tablet: Context
    private val account = "notes-user"
    private val noteId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val folderId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val imageId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val image = NoteImage(imageId, 3, NoteImageKind.SCAN, 15, 1200, 1800)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        phone = DeviceContext(app, "notes-phone-${UUID.randomUUID()}")
        tablet = DeviceContext(app, "notes-tablet-${UUID.randomUUID()}")
    }

    @Test fun completeNotesFoldersAndOriginalAttachmentDescriptorsTravelTogether() {
        storage(phone).putFolder(NoteFolder(folderId, "École", 10, 12))
        val original = TranscriptNote(noteId, "Préparation", "Avant\n\n[[Image 3]]\n\nAprès", 20,
            renamed = true, images = listOf(image), folderId = folderId, folderChoicePrompted = true)
        storage(phone).put(original)
        val bytes = "original JPEG bytes".toByteArray()
        AccountNotesSyncStore.imageFile(phone, imageId).writeBytes(bytes)
        NoteImageStore(phone).thumbnail(imageId).writeText("private thumbnail")
        val source = PreferenceSyncStore(phone, account)
        val document = source.captureLocal()
        assertTrue(document.liveValues().containsKey("note:$noteId"))
        assertTrue(document.liveValues().containsKey("folder:$folderId"))
        assertEquals(hash(bytes), AccountNotesSyncStore.assets(document)[imageId])
        assertEquals(bytes.size.toLong(), AccountNotesSyncStore.assetDescriptors(document).single().size)
        // The network coordinator publishes verified originals before applying note metadata.
        AccountNotesSyncStore.imageFile(tablet, imageId).writeBytes(bytes)
        PreferenceSyncStore(tablet, account).mergeRemote(listOf(document))
        assertEquals(original, storage(tablet).all().single())
        assertEquals(NoteFolder(folderId, "École", 10, 12), storage(tablet).allFolders().single())
        assertFalse(prefs(tablet, "transcript_notes").getString(noteId, "").orEmpty().contains("_seen"))
    }

    @Test fun longNoteTextIsPreservedWithoutTruncation() {
        val text = "Une réunion avec tous les détails.\n".repeat(1000)
        storage(phone).put(note(text, 20))
        val source = PreferenceSyncStore(phone, account).captureLocal()
        PreferenceSyncStore(tablet, account).mergeRemote(listOf(source))
        assertEquals(text, storage(tablet).all().single().text)
    }

    @Test fun deletingAFolderUnfilesNotesWithoutRemovingTheirContent() {
        storage(phone).putFolder(NoteFolder(folderId, "École", 10))
        storage(phone).put(note("Contenu conservé", 20).copy(folderId = folderId))
        val source = PreferenceSyncStore(phone, account)
        val initial = source.captureLocal()
        val target = PreferenceSyncStore(tablet, account)
        target.mergeRemote(listOf(initial))
        TranscriptNotes(storage(phone), now = { 30 }).deleteFolder(folderId)
        target.mergeRemote(listOf(source.captureLocal(), initial))
        val received = storage(tablet).all().single()
        assertEquals("Contenu conservé", received.text)
        assertNull(received.folderId)
        assertTrue(storage(tablet).allFolders().isEmpty())
    }

    @Test fun concurrentEditsConvergeWithOneDeterministicPreservedVersion() {
        storage(phone).put(note("Texte initial", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        val initial = a.captureLocal()
        b.mergeRemote(listOf(initial))
        storage(phone).put(note("Version téléphone", 20))
        storage(tablet).put(note("Version tablette", 21))
        val aEdit = a.captureLocal()
        val bEdit = b.captureLocal()
        val mergedA = a.mergeRemote(listOf(bEdit))
        val mergedB = b.mergeRemote(listOf(aEdit, mergedA))
        a.mergeRemote(listOf(mergedB, initial))
        assertEquals(setOf("Version téléphone", "Version tablette"), storage(phone).all().map { it.text }.toSet())
        assertEquals(2, storage(phone).all().size)
        assertEquals(storage(phone).all().sortedBy { it.id }, storage(tablet).all().sortedBy { it.id })
        assertEquals(1, storage(phone).all().count { it.title.contains("version conservée") })
        assertEquals(a.current().toJson(), b.mergeRemote(listOf(a.captureLocal())).toJson())
    }

    @Test fun aSequentialEditUsesCausalMetadataInsteadOfCreatingAConflictCopy() {
        storage(phone).put(note("Texte initial", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        storage(tablet).put(note("Texte corrigé ensuite", 20))
        a.mergeRemote(listOf(b.captureLocal()))
        assertEquals(listOf("Texte corrigé ensuite"), storage(phone).all().map { it.text })
        assertEquals(1, storage(phone).all().size)
    }

    @Test fun noteDeletionsSurviveStaleRemoteReplicas() {
        storage(phone).put(note("À supprimer", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        val initial = a.captureLocal()
        b.mergeRemote(listOf(initial))
        storage(phone).remove(noteId)
        val removed = a.captureLocal()
        b.mergeRemote(listOf(removed, initial))
        assertTrue(storage(tablet).all().isEmpty())
        assertNull(b.captureLocal().entries["note:$noteId"]!!.value)
    }

    @Test fun deletingANotePreservesAConcurrentOfflineEditEvenAfterItsEditorWasClosed() {
        storage(phone).put(note("Texte initial", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        val initial = a.captureLocal()
        b.mergeRemote(listOf(initial))
        storage(tablet).put(note("Travail hors ligne conservé", 20))
        val offlineEdit = b.captureLocal()
        // Unrelated preference changes advance the deleting device's Lamport clock.
        prefs(phone, "whisperpin").edit().putString("theme_mode", "dark").putBoolean("trailing_space", true).commit()
        a.captureLocal()
        prefs(phone, "whisperpin").edit().putBoolean("trailing_space", false).commit()
        a.captureLocal()
        storage(phone).remove(noteId)
        val deletion = a.captureLocal()
        assertEquals(deletion.entries["note:$noteId"]!!.stamp, deletion.entries["deletion:$noteId"]?.stamp)
        val result = b.mergeRemote(listOf(deletion, initial))
        assertNull(result.entries["note:$noteId"]!!.value)
        val saved = storage(tablet).all().single()
        assertEquals("Travail hors ligne conservé", saved.text)
        assertTrue(saved.title.contains("version conservée"))
        a.mergeRemote(listOf(offlineEdit, result, initial))
        assertEquals(storage(tablet).all(), storage(phone).all())
    }

    @Test fun twoOpenEditorsKeepTheirBodiesWithoutGeneratingTheSamePreservedVersionRepeatedly() {
        storage(phone).put(note("Texte téléphone", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        listOf(phone, tablet).forEach { context ->
            prefs(context, "dictation_draft").edit().putString("note_id", noteId).putString("purpose", "NOTE").commit()
        }
        storage(tablet).put(note("Texte tablette", 20))
        repeat(6) {
            a.mergeRemote(listOf(b.captureLocal()))
            b.mergeRemote(listOf(a.captureLocal()))
        }
        assertEquals("Texte téléphone", storage(phone).all().first { it.id == prefs(phone, "dictation_draft").getString("note_id", null) }.text)
        assertEquals("Texte tablette", storage(tablet).all().first { it.id == prefs(tablet, "dictation_draft").getString("note_id", null) }.text)
        assertTrue(storage(phone).all().size <= 3)
        assertTrue(storage(tablet).all().size <= 3)
        assertTrue(storage(phone).all().filter { it.id != noteId }.groupBy { it.text }.values.all { it.size == 1 })
        assertTrue(storage(tablet).all().filter { it.id != noteId }.groupBy { it.text }.values.all { it.size == 1 })
        assertEquals(a.current().toJson(), b.current().toJson())
    }

    @Test fun aConcurrentDeletionWinsWithoutLosingTheEditEvenWhenItsClockIsLower() {
        storage(phone).put(note("Texte initial", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        prefs(tablet, "whisperpin").edit().putBoolean("trailing_space", true).putBoolean("show_transcript", true).commit()
        b.captureLocal()
        storage(tablet).put(note("Édition hors ligne", 20))
        val edit = b.captureLocal()
        storage(phone).remove(noteId)
        val deletion = a.captureLocal()
        assertTrue(deletion.entries["note:$noteId"]!!.stamp.counter < edit.entries["note:$noteId"]!!.stamp.counter)

        val merged = b.mergeRemote(listOf(deletion))
        assertNull(merged.entries["note:$noteId"]!!.value)
        assertFalse(storage(tablet).all().any { it.id == noteId })
        assertEquals(listOf("Édition hors ligne"), storage(tablet).all().map { it.text })
        a.mergeRemote(listOf(merged, edit))
        assertEquals(a.current().toJson(), b.captureLocal().toJson())
    }

    @Test fun deletingAPreservedVersionSurvivesSeeingItsOriginalConflictAgain() {
        storage(phone).put(note("Initial", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        storage(phone).put(note("Téléphone", 20))
        storage(tablet).put(note("Tablette", 21))
        val originalA = a.captureLocal()
        val originalB = b.captureLocal()
        val merged = a.mergeRemote(listOf(originalB))
        b.mergeRemote(listOf(merged))
        val preserved = storage(phone).all().single { it.id != noteId }
        storage(phone).remove(preserved.id)
        val deleted = a.captureLocal()

        b.mergeRemote(listOf(deleted, originalA, originalB))
        a.mergeRemote(listOf(b.captureLocal(), originalA, originalB))
        assertEquals(1, storage(phone).all().size)
        assertFalse(storage(phone).all().any { it.id == preserved.id })
        assertNull(a.current().entries["note:${preserved.id}"]!!.value)
        assertEquals(a.current().toJson(), b.current().toJson())
    }

    @Test fun receivingAnEditOfAnOpenNotePreservesTheLiveDraftAndTheIncomingVersion() {
        storage(phone).put(note("Texte en cours", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        prefs(phone, "dictation_draft").edit().putString("note_id", noteId).putString("purpose", "NOTE")
            .putString("text", "Texte en cours").commit()
        storage(tablet).put(note("Correction distante", 20))
        a.mergeRemote(listOf(b.captureLocal()))
        assertEquals("Correction distante", storage(phone).all().first { it.id == noteId }.text)
        assertEquals(setOf("Texte en cours", "Correction distante"), storage(phone).all().map { it.text }.toSet())
        assertEquals("Texte en cours", prefs(phone, "dictation_draft").getString("text", null))
        val detached = prefs(phone, "dictation_draft").getString("note_id", null)
        assertNotEquals(noteId, detached)
        assertEquals("Texte en cours", storage(phone).all().first { it.id == detached }.text)
    }

    @Test fun deletingAnOpenNoteDetachesItsDraftToAPreservedCopy() {
        storage(phone).put(note("Brouillon protégé", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        prefs(phone, "dictation_draft").edit().putString("note_id", noteId).putString("purpose", "NOTE")
            .putString("text", "Brouillon protégé").commit()
        storage(tablet).remove(noteId)
        a.mergeRemote(listOf(b.captureLocal()))
        assertFalse(storage(phone).all().any { it.id == noteId })
        val copy = storage(phone).all().single()
        assertEquals("Brouillon protégé", copy.text)
        assertEquals(copy.id, prefs(phone, "dictation_draft").getString("note_id", null))
        assertEquals("Brouillon protégé", prefs(phone, "dictation_draft").getString("text", null))
        assertNull(a.current().entries["note:$noteId"]!!.value)
    }

    @Test fun malformedLocalNotesStopSynchronizationWithoutSilentlyDroppingData() {
        prefs(phone, "transcript_notes").edit().putString(noteId, "{broken").commit()
        assertThrows(IllegalArgumentException::class.java) { PreferenceSyncStore(phone, account).captureLocal() }
        assertEquals("{broken", prefs(phone, "transcript_notes").getString(noteId, null))
    }

    @Test fun malformedIncomingNotesCannotPartiallyApplyPreferences() {
        prefs(phone, "whisperpin").edit().putString("dictation_language", "fr").commit()
        val invalid = SyncDocument().record(mapOf("pref:dictation_language" to "en", "note:$noteId" to "{}"),
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        assertThrows(IllegalArgumentException::class.java) { PreferenceSyncStore(phone, account).mergeRemote(listOf(invalid)) }
        assertEquals("fr", prefs(phone, "whisperpin").getString("dictation_language", null))
        assertTrue(storage(phone).all().isEmpty())
    }

    @Test fun excessivelyNestedNoteJsonIsRejectedBeforePlatformJsonParsing() {
        val nested = "{\"nested\":".repeat(5000) + "0" + "}".repeat(5000)
        val hostile = "{\"id\":\"$noteId\",\"title\":\"Note\",\"text\":\"Texte\",\"updated\":1,\"unknown\":$nested}"
        val document = SyncDocument().record(mapOf("note:$noteId" to hostile), "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        assertThrows(IllegalArgumentException::class.java) { PreferenceSyncStore(phone, account).validateIncoming(listOf(document)) }
    }

    @Test fun excessivelyNestedLocalFormatJsonIsRejectedBeforePlatformJsonParsing() {
        val nested = "{\"nested\":".repeat(5000) + "0" + "}".repeat(5000)
        prefs(phone, "dictai_formats").edit().putString("custom",
            "[{\"id\":\"$noteId\",\"name\":\"Format\",\"instructions\":\"Texte\",\"unknown\":$nested}]").commit()
        assertThrows(IllegalArgumentException::class.java) { PreferenceSyncStore(phone, account).captureLocal() }
    }

    @Test fun savedCollectionsCanReloadWithoutResavingTheIncomingNote() {
        val model = TranscriptNotes(storage(phone))
        storage(phone).put(note("Reçu depuis la tablette", 17))
        model.reloadFromStorage()
        assertEquals("Reçu depuis la tablette", model.get(noteId)?.text)
        assertEquals(17L, model.get(noteId)?.updatedAt)
    }

    @Test fun pendingNotesCanExposeTheirAssetsBeforeRecoveryWhenAnOriginalFileWasLost() {
        storage(phone).put(note("Note reçue avant interruption", 17).copy(images = listOf(image)))
        val bytes = "original JPEG bytes".toByteArray()
        AccountNotesSyncStore.imageFile(phone, imageId).writeBytes(bytes)
        val incoming = PreferenceSyncStore(phone, account).captureLocal()
        PreferenceSyncStore(tablet, account)
        val scope = hash(account.toByteArray())
        val journal = prefs(tablet, "dictai_sync_journal_$scope")
        journal.edit().putString("pending", incoming.toJson()).commit()

        val reopened = PreferenceSyncStore(tablet, account, recoveryDeferred = true)
        assertEquals(incoming, reopened.pendingDocument())
        assertTrue(storage(tablet).all().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { reopened.captureLocal() }
        assertEquals(incoming.toJson(), journal.getString("pending", null))
        AccountNotesSyncStore.imageFile(tablet, imageId).writeBytes(bytes)
        assertEquals(incoming, reopened.current())
        assertNull(reopened.pendingDocument())
        assertEquals("Note reçue avant interruption", storage(tablet).all().single().text)
    }

    @Test fun recoveringAFrozenPendingImportPreservesAnEditMadeAndClosedWhileItsImagesWereRestored() {
        storage(phone).put(note("Ancien texte", 10))
        val a = PreferenceSyncStore(phone, account)
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(a.captureLocal()))
        storage(tablet).put(note("Version distante en attente", 20))
        val pending = a.previewRemote(listOf(b.captureLocal()))
        val journal = prefs(phone, "dictai_sync_journal_${hash(account.toByteArray())}")
        journal.edit().putString("pending", pending.toJson()).commit()
        // An open editor autosaves this text, then closes before the pending import can recover.
        storage(phone).put(note("Nouvelle édition pendant récupération", 30))
        val reopened = PreferenceSyncStore(phone, account, recoveryDeferred = true)

        val recovered = reopened.current()
        assertNull(reopened.pendingDocument())
        assertEquals("Version distante en attente", storage(phone).all().first { it.id == noteId }.text)
        assertEquals(setOf("Version distante en attente", "Nouvelle édition pendant récupération"), storage(phone).all().map { it.text }.toSet())
        assertEquals(2, storage(phone).all().size)
        assertEquals(recovered, reopened.captureLocal())
    }

    @Test fun aFailedFinalReceiptRetainsPendingBeforeCapturingAnEditFromThePreviousEditorCache() {
        var failedReceipt = false
        val failingDevice = object : ContextWrapper(phone) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = super.getSharedPreferences(name, mode)
                if (!name.startsWith("dictai_sync_journal_")) return actual
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        var removesPending = false
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                            override fun remove(key: String): SharedPreferences.Editor {
                                editor.remove(key)
                                if (key == "pending") removesPending = true
                                return this
                            }
                            override fun commit(): Boolean {
                                if (removesPending && !failedReceipt) {
                                    failedReceipt = true
                                    editor.apply() // Android can update memory even when the durable receipt fails.
                                    return false
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        storage(phone).put(note("Texte local précédent", 10))
        val a = PreferenceSyncStore(failingDevice, account)
        val initial = a.captureLocal()
        val b = PreferenceSyncStore(tablet, account)
        b.mergeRemote(listOf(initial))
        storage(tablet).put(note("Version distante non acquittée", 20))
        val remote = b.captureLocal()

        assertThrows(IllegalStateException::class.java) { a.mergeRemote(listOf(remote)) }
        val journal = prefs(phone, "dictai_sync_journal_${hash(account.toByteArray())}")
        assertTrue(journal.contains("pending"))
        assertEquals(initial.toJson(), journal.getString("current", null))
        // The UI has not received the applied callback because the merge threw.
        storage(phone).put(note("Nouvelle saisie depuis le cache précédent", 30))
        val recovered = a.captureLocal()
        assertFalse(journal.contains("pending"))
        assertEquals(setOf("Version distante non acquittée", "Nouvelle saisie depuis le cache précédent"), storage(phone).all().map { it.text }.toSet())
        val saved = storage(phone).all().single { it.id != noteId }
        val history = JSONObject(recovered.entries["note:${saved.id}"]!!.value!!).getJSONObject("_seen")
        assertTrue(history.optLong(b.actor, 0) < remote.entries["note:$noteId"]!!.stamp.counter)
    }

    private fun note(text: String, time: Long) = TranscriptNote(noteId, "Note", text, time)
    private fun storage(context: Context) = AndroidTranscriptNoteStorage(context)
    private fun prefs(context: Context, name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class DeviceContext(base: Context, private val id: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("${id}_$name", mode)
        override fun getFilesDir(): File = File(baseContext.cacheDir, "$id-files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseContext.cacheDir, "$id-cache").apply { mkdirs() }
    }
}
