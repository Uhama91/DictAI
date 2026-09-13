package com.kafkasl.phonewhisper

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.TextureView
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.android.controller.ActivityController
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.assertThrows

/**
 * Exercises the native viewfinder construction off-device. TextureView must stay undecorated:
 * Android throws UnsupportedOperationException when a TextureView receives a background, so the
 * rounded surface belongs to the FrameLayout wrapper instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteCameraActivityRobolectricTest {
    private lateinit var context: android.content.Context
    private lateinit var store: NoteImageStore
    private var captureId: String? = null
    private var controller: ActivityController<NoteCameraActivity>? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = NoteImageStore(context)
        store.pending()?.let { store.clearPending(it.id) }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        controller = null
        captureId?.let(store::clearPending)
        PersistencePrefs(context).themeMode = ThemeMode.SYSTEM
    }

    @Test
    fun viewfinderDecoratesWrapperWithoutSettingTextureViewBackground() {
        PersistencePrefs(context).themeMode = ThemeMode.LIGHT
        val capture = store.begin(
            TranscriptNote("camera-test", "Note", "", 1L),
            NoteImageKind.CAMERA,
            resume = false,
        )
        captureId = capture.id
        controller = Robolectric.buildActivity(
            NoteCameraActivity::class.java,
            Intent(context, NoteCameraActivity::class.java).putExtra("captureId", capture.id),
        )
        val activity = controller!!.create().get()

        val preview = activity.privateField<TextureView>("preview")
        val wrapper = activity.privateField<FrameLayout>("previewFrame")
        assertNull("TextureView must remain undecorated on Android", preview.background)
        assertNotNull("The wrapper owns the rounded viewfinder decoration", wrapper.background)
        assertTrue(wrapper.clipToOutline)
    }

    @Test
    fun explicitThemeReconfigurationKeepsTheWrapperAndTextureViewSafe() {
        PersistencePrefs(context).themeMode = ThemeMode.DARK
        val capture = store.begin(
            TranscriptNote("camera-theme-test", "Note", "", 1L),
            NoteImageKind.CAMERA,
            resume = false,
        )
        captureId = capture.id
        controller = Robolectric.buildActivity(
            NoteCameraActivity::class.java,
            Intent(context, NoteCameraActivity::class.java).putExtra("captureId", capture.id),
        )
        val activity = controller!!.create().get()

        val config = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        activity.onConfigurationChanged(config)

        val preview = activity.privateField<TextureView>("preview")
        val wrapper = activity.privateField<FrameLayout>("previewFrame")
        assertNull(preview.background)
        assertNotNull(wrapper.background)
    }

    @Test
    fun androidTextureViewRejectsBackgroundThatCausedTheOriginalCrash() {
        val texture = TextureView(context)
        assertThrows(UnsupportedOperationException::class.java) {
            texture.background = GradientDrawable()
        }
    }

    private inline fun <reified T> NoteCameraActivity.privateField(name: String): T =
        NoteCameraActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(this@privateField) as T
        }
}
