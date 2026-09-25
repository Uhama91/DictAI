package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the actual Camera2 viewfinder and repeated still capture on the configured emulator. */
@RunWith(AndroidJUnit4::class)
class NoteCameraCaptureAndroidTest {
    @Test
    fun cameraCapturesTwoPrivateImagesAcrossExpansionAndValidatesOnlyOnTap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = NoteImageStore(context)
        assumeTrue("camera test requires an unused private capture slot", store.pending() == null)
        grantCameraPermission(context)
        val batch = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 3, maxImages = 3)
        var scenario: ActivityScenario<NoteCameraActivity>? = null
        var images: List<NoteImage> = emptyList()
        try {
            val intent = Intent(context, NoteCameraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("captureId", batch.id)
            scenario = ActivityScenario.launch(intent)

            assertTrue("Camera2 preview session should become ready", await(20_000) {
                var ready = false
                scenario!!.onActivity { ready = field<Button>(it, "shutter").isEnabled }
                ready
            })

            scenario!!.onActivity { field<Button>(it, "shutter").performClick() }
            assertTrue("first still should be persisted", awaitImageCount(store, batch.id, 1))
            assertFalse("capture is staged until explicit validation", requireNotNull(store.pending()).complete)
            assertFalse(requireNotNull(store.pending()).accepted)

            var zoomChanged = false
            var zoomAvailable = false
            scenario!!.onActivity { activity ->
                val seek = field<SeekBar>(activity, "zoom")
                if (seek.isEnabled) {
                    zoomAvailable = true
                    val label = field<TextView>(activity, "zoomLabel").text.toString()
                    field<Button>(activity, "zoomPlus").performClick()
                    zoomChanged = field<TextView>(activity, "zoomLabel").text.toString() != label
                }
                field<Button>(activity, "expand").performClick()
            }
            // The AVD's rear camera advertises zoom ratio support; if another emulator omits it,
            // the capture cycle still runs and exercises the crop fallback.
            if (zoomAvailable) assertTrue("zoom control must alter the requested camera ratio", zoomChanged)
            scenario!!.onActivity { activity ->
                assertTrue("viewfinder remains available after in-place expansion", field<android.view.TextureView>(activity, "preview").isAvailable)
                field<Button>(activity, "shutter").performClick()
            }
            assertTrue("second still should be persisted", awaitImageCount(store, batch.id, 2))
            val staged = requireNotNull(store.pending())
            assertFalse(staged.complete)
            assertFalse(staged.accepted)
            images = staged.allImages
            assertEquals(2, images.size)
            assertNotEquals(images[0].id, images[1].id)
            images.forEach { image ->
                val jpeg = store.file(image.id)
                assertTrue("each image has its own private JPEG", jpeg.isFile && jpeg.length() > 4)
                val header = jpeg.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte()) }
                assertTrue("image is JPEG encoded", header.contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
                assertTrue("each image has its own thumbnail", store.thumbnail(image.id).isFile)
            }

            var acceptedAtValidationTap = false
            scenario!!.onActivity { activity ->
                field<Button>(activity, "validate").performClick()
                acceptedAtValidationTap = store.pending()?.accepted == true
            }
            assertTrue("the batch becomes accepted only after the validation tap", acceptedAtValidationTap)
            assertTrue("both accepted files survive Activity finish", images.all { store.file(it.id).isFile })
        } finally {
            scenario?.close()
            store.pending()?.takeIf { it.id == batch.id }?.let { pending ->
                if (!pending.accepted) runCatching { store.cancelBatch(batch.id) }
                store.clearPending(batch.id)
            }
            images.forEach { store.delete(it.id) }
        }
    }

    private fun awaitImageCount(store: NoteImageStore, id: String, count: Int): Boolean = await(15_000) {
        store.pending()?.let { it.id == id && it.allImages.size >= count } == true
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    private fun grantCameraPermission(context: Context) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.CAMERA}",
        )
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
        assertTrue("camera permission grant failed: $output", output.isBlank())
    }

    private inline fun <reified T> field(activity: NoteCameraActivity, name: String): T =
        NoteCameraActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(activity) as T
        }
}
