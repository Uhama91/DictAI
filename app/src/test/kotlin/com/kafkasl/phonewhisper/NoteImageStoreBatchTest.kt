package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Arrays

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteImageStoreBatchTest {
    private lateinit var store: NoteImageStore
    private val imageIds = mutableSetOf<String>()

    @Before
    fun setUp() {
        store = NoteImageStore(RuntimeEnvironment.getApplication())
        store.pending()?.let { store.clearPending(it.id) }
    }

    @After
    fun cleanUp() {
        store.pending()?.let { pending ->
            if (pending.batch && !pending.accepted) runCatching { store.cancelBatch(pending.id) }
            store.clearPending(pending.id)
            imageIds += pending.allImages.map { it.id }
        }
        imageIds.forEach(store::delete)
        imageIds.clear()
    }

    @Test
    fun batchStoresSeveralIndividuallyAddressableImagesUntilExplicitAcceptance() {
        val batch = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 4, maxImages = 3)

        storeImage(batch.id, bitmap(Color.RED))
        val afterFirst = requireNotNull(store.pending())
        assertFalse(afterFirst.complete)
        assertEquals(1, afterFirst.allImages.size)

        storeImage(batch.id, bitmap(Color.BLUE))
        val afterSecond = requireNotNull(store.pending())
        assertFalse(afterSecond.complete)
        assertEquals(2, afterSecond.allImages.size)
        assertEquals(listOf(4, 5), afterSecond.allImages.map { it.number })
        assertNotEquals(afterSecond.allImages[0].id, afterSecond.allImages[1].id)
        assertTrue(afterSecond.allImages.all {
            NoteImage.validId(it.id) && store.file(it.id).isFile && store.thumbnail(it.id).isFile &&
                store.thumbnail(it.id).length() > 0
        })
        val firstThumbnail = store.thumbnail(afterSecond.allImages[0].id).readBytes()
        val secondThumbnail = store.thumbnail(afterSecond.allImages[1].id).readBytes()
        assertTrue("each image UUID resolves to a JPEG thumbnail", firstThumbnail.take(2) == listOf(0xff.toByte(), 0xd8.toByte()))
        assertTrue("each image UUID resolves to a JPEG thumbnail", secondThumbnail.take(2) == listOf(0xff.toByte(), 0xd8.toByte()))
        assertFalse("different source images keep separate thumbnail contents", Arrays.equals(firstThumbnail, secondThumbnail))

        store.acceptBatch(batch.id)
        val accepted = requireNotNull(store.pending())
        assertTrue(accepted.complete)
        assertTrue(accepted.accepted)
        assertEquals(afterSecond.allImages, accepted.allImages)

        store.clearPending(batch.id)
        assertTrue("validated files become owned by the note draft", accepted.allImages.all {
            store.file(it.id).isFile && store.thumbnail(it.id).isFile
        })
    }

    @Test
    fun cancellationDeletesOnlyUnacceptedImagesOwnedByThatBatch() {
        val accepted = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 1, maxImages = 2)
        storeImage(accepted.id, bitmap())
        store.acceptBatch(accepted.id)
        val preservedImage = requireNotNull(store.pending()).allImages.single()
        store.clearPending(accepted.id)

        val cancelled = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 2, maxImages = 2)
        storeImage(cancelled.id, bitmap())
        val cancelledImage = requireNotNull(store.pending()).allImages.single()
        store.cancelBatch(cancelled.id)

        assertTrue("previously accepted image is untouched", store.file(preservedImage.id).isFile)
        assertFalse("cancelled session image is deleted", store.file(cancelledImage.id).exists())
        assertFalse("cancelled session thumbnail is deleted", store.thumbnail(cancelledImage.id).exists())
        assertTrue(requireNotNull(store.pending()).complete)
    }

    @Test
    fun removingAnImageFromUnacceptedBatchFreesItsSlotAndKeepsOtherFiles() {
        val batch = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 6, maxImages = 2)
        storeImage(batch.id, bitmap())
        storeImage(batch.id, bitmap())
        val images = requireNotNull(store.pending()).allImages

        store.removeBatchImage(batch.id, images.first().id)

        assertEquals(listOf(images.last()), requireNotNull(store.pending()).allImages)
        assertFalse(store.file(images.first().id).exists())
        assertTrue(store.file(images.last().id).isFile)
        storeImage(batch.id, bitmap())
        val updated = requireNotNull(NoteImageStore(RuntimeEnvironment.getApplication()).pending())
        assertEquals(listOf(7, 8), updated.allImages.map { it.number })
        assertEquals(2, updated.allImages.map { it.id }.distinct().size)
    }

    @Test
    fun acceptedBatchIsImmutableAndLateStoreAfterCancellationCannotResurrectItsFiles() {
        val accepted = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 8, maxImages = 2)
        storeImage(accepted.id, bitmap())
        store.acceptBatch(accepted.id)
        val acceptedImage = requireNotNull(store.pending()).allImages.single()
        assertThrows(IllegalStateException::class.java) {
            store.removeBatchImage(accepted.id, acceptedImage.id)
        }
        store.clearPending(accepted.id)

        val cancelled = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 9, maxImages = 1)
        storeImage(cancelled.id, bitmap())
        val cancelledImage = requireNotNull(store.pending()).allImages.single()
        store.cancelBatch(cancelled.id)
        store.store(cancelled.id, bitmap())

        assertTrue(store.file(acceptedImage.id).isFile)
        assertFalse(store.file(cancelledImage.id).exists())
        assertTrue(requireNotNull(store.pending()).allImages.isEmpty())
    }

    @Test
    fun retryClearsOnlyTheFailureAndKeepsTheBatchSessionAndImages() {
        val batch = store.beginBatch(NoteImageKind.SCREENSHOT, resume = false, number = 2, maxImages = 2)
        storeImage(batch.id, bitmap())
        val image = requireNotNull(store.pending()).allImages.single()

        store.fail(batch.id, "Capture momentanément indisponible")
        val recoverableFailure = requireNotNull(store.pending())
        assertFalse(recoverableFailure.complete)
        assertEquals("Capture momentanément indisponible", recoverableFailure.message)
        val retried = requireNotNull(store.retryBatch(batch.id))

        assertEquals(batch.id, retried.id)
        assertTrue(retried.message == null && retried.error == null)
        assertEquals(listOf(image), retried.allImages)
        assertTrue(store.file(image.id).isFile)
    }

    @Test
    fun legacySingleCaptureStillCompletesWithItsHistoricalIdentifier() {
        val capture = store.begin(TranscriptNote("legacy-camera", "Texte", "", 1L), NoteImageKind.CAMERA, resume = false)

        storeImage(capture.id, bitmap())

        val completed = requireNotNull(store.pending())
        assertTrue(completed.complete)
        assertEquals(capture.id, completed.image?.id)
        assertEquals(listOf(completed.image), completed.allImages)
    }

    private fun storeImage(id: String, bitmap: Bitmap) {
        try { store.store(id, bitmap) } finally { bitmap.recycle() }
        imageIds += store.pending()?.allImages.orEmpty().map { it.id }
    }

    private fun bitmap(color: Int = Color.GREEN) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
