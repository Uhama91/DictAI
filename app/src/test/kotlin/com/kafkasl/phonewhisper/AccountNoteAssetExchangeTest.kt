package com.kafkasl.phonewhisper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountNoteAssetExchangeTest {
    private val imageId = "11111111-1111-4111-8111-111111111111"
    private val noteId = "22222222-2222-4222-8222-222222222222"
    private val session = AccountSession("33333333-3333-4333-8333-333333333333", "a@example.test", "access", "refresh", 5000)
    private class Images(private val bytes: ByteArray) : AccountAttachmentTransport {
        var uploads = 0; var downloads = 0
        override fun upload(accessToken: String, userId: String, sha256: String, bytes: ByteArray) { uploads++ }
        override fun download(accessToken: String, userId: String, sha256: String): ByteArray { downloads++; return bytes }
    }
    @Test fun incomingOriginalGetsABoundedPreviewBeforeNoteImport() {
        val source = device(); val target = device(); val bytes = jpeg()
        val document = document(source, bytes)
        val store = PreferenceSyncStore(target, "a")
        val images = Images(bytes)
        AccountNoteAssetExchange(target, images, "backend/a", store::previewRemote).receive(session, listOf(document)) { true }
        assertArrayEquals(bytes, NoteImageStore(target).file(imageId).readBytes())
        val preview = NoteImageStore(target).thumbnail(imageId)
        assertTrue("A synchronized image needs a thumbnail in the notes UI", preview.isFile)
        val bitmap = BitmapFactory.decodeFile(preview.path)
        assertNotNull(bitmap)
        try { assertTrue(maxOf(bitmap.width, bitmap.height) <= 160) } finally { bitmap.recycle() }
        store.mergeRemote(listOf(document))
        assertEquals(imageId, AndroidTranscriptNoteStorage(target).all().single().images.single().id)
    }
    @Test fun obsoletePhotoFromAnOlderReplicaDoesNotDownload() {
        val source = device(); val target = device(); val bytes = jpeg()
        val old = document(source, bytes)
        val writer = PreferenceSyncStore(source, "a")
        AndroidTranscriptNoteStorage(source).remove(noteId)
        val deleted = writer.captureLocal()
        val images = Images(bytes); val store = PreferenceSyncStore(target, "a")
        AccountNoteAssetExchange(target, images, "backend/a", store::previewRemote).receive(session, listOf(old, deleted)) { true }
        assertEquals(0, images.downloads)
        assertFalse(NoteImageStore(target).file(imageId).exists())
    }
    @Test fun imageReceiptsPreventRepeatUploadsAndAreScopedToTheAccount() {
        val source = device(); val bytes = jpeg(); val doc = document(source, bytes); val images = Images(bytes)
        val first = AccountNoteAssetExchange(source, images, "backend/a") { doc }
        first.send(session, doc) { true }
        AccountNoteAssetExchange(source, images, "backend/a") { doc }.send(session, doc) { true }
        assertEquals(1, images.uploads)
        AccountNoteAssetExchange(source, images, "backend/b") { doc }.send(session, doc) { true }
        assertEquals(2, images.uploads)
    }
    @Test fun anInterruptedImportCanRecoverItsMissingOriginalBeforeResumingNotes() {
        val source = device(); val target = device(); val bytes = jpeg(); val incoming = document(source, bytes)
        val scope = java.security.MessageDigest.getInstance("SHA-256").digest("a".toByteArray())
            .joinToString("") { "%02x".format(it) }
        target.getSharedPreferences("dictai_sync_journal_$scope", Context.MODE_PRIVATE)
            .edit().putString("pending", incoming.toJson()).commit()
        val store = PreferenceSyncStore(target, "a", recoveryDeferred = true)
        assertThrows(IllegalArgumentException::class.java) { store.current() }
        assertNotNull(store.pendingDocument())
        val exchange = AccountNoteAssetExchange(target, Images(bytes), "backend/a", store::previewRemote)
        exchange.restorePending(session, store.pendingDocument()!!) { true }
        store.current()
        assertNull(store.pendingDocument())
        assertArrayEquals(bytes, NoteImageStore(target).file(imageId).readBytes())
        assertEquals(noteId, AndroidTranscriptNoteStorage(target).all().single().id)
    }
    private fun document(context: Context, bytes: ByteArray): SyncDocument {
        NoteImageStore(context).file(imageId).writeBytes(bytes)
        val image = NoteImage(imageId, 1, NoteImageKind.CAMERA, 10, 640, 480)
        AndroidTranscriptNoteStorage(context).put(TranscriptNote(noteId, "Photo", "[[Image 1]]", 20, images = listOf(image)))
        return PreferenceSyncStore(context, "a").captureLocal()
    }
    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        return try { bitmap.eraseColor(android.graphics.Color.BLUE); ByteArrayOutputStream().also {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
        }.toByteArray() } finally { bitmap.recycle() }
    }
    private fun device(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val id = "asset-${UUID.randomUUID()}"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("${id}_$name", mode)
        override fun getFilesDir() = File(baseContext.cacheDir, "$id-files").apply { mkdirs() }
    }
}
