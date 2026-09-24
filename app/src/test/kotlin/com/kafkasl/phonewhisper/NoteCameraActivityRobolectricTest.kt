package com.kafkasl.phonewhisper

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
    fun compactAndExpandedLayoutsUseTheSamePhoneAndKeepAllBatchControlsVisible() {
        PersistencePrefs(context).themeMode = ThemeMode.LIGHT
        val activity = createCameraBatchActivity(preloadImages = 2)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val controls = descendants(content)
        val labels = controls.filterIsInstance<TextView>().map { it.text.toString() }

        assertTrue(labels.any { it.contains("Scanner") })
        assertTrue(labels.any { it.contains("Valider") })
        assertTrue(labels.any { it.contains("Annuler") })
        assertTrue(controls.any { it is SeekBar && it.contentDescription.toString().contains("zoom", ignoreCase = true) })
        assertTrue(labels.any { it.contains("Agrandir") })
        render(activity, 360, 800, "camera-compact.png")
        val preview = activity.privateField<FrameLayout>("previewFrame")
        val compactPreviewHeight = preview.height
        assertBatchControlsWithin(activity, 360, 800)

        val expand = controls.filterIsInstance<Button>().first { it.text.toString().contains("Agrandir") }
        expand.performClick()
        assertTrue(descendants(content).filterIsInstance<TextView>().any { it.text.toString().contains("Réduire") })
        render(activity, 360, 800, "camera-expanded.png")
        assertTrue("expansion should enlarge the viewfinder on this same phone", preview.height > compactPreviewHeight)
        assertBatchControlsWithin(activity, 360, 800)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
    fun expandedControlsStayAccessibleOnANarrowShortScreenWithLargeText() {
        val activity = createCameraBatchActivity(preloadImages = 2)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        descendants(content).forEach { view ->
            when (view) {
                is Button -> view.textSize = 18f
                is TextView -> view.textSize = 17f
            }
        }
        render(activity, 320, 640, "camera-narrow-compact.png")
        assertBatchControlsWithin(activity, 320, 640)
        activity.privateField<Button>("expand").performClick()
        render(activity, 320, 640, "camera-narrow-expanded.png")
        assertBatchControlsWithin(activity, 320, 640)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeKeepsThePreviewAttachedAndAllActionsInsideTheShortViewport() {
        val activity = createCameraBatchActivity(preloadImages = 2)
        val parentBefore = activity.privateField<FrameLayout>("previewFrame").parent
        val landscape = Configuration(activity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        activity.onConfigurationChanged(landscape)
        val previewFrame = activity.privateField<FrameLayout>("previewFrame")
        val root = activity.privateField<LinearLayout>("cameraRoot")
        assertSame("resizing must preserve the TextureView surface parent", parentBefore, previewFrame.parent)
        assertEquals(LinearLayout.HORIZONTAL, root.orientation)
        render(activity, 800, 360, "camera-landscape.png")
        val compactWidth = activity.window.attributes.width
        assertTrue(previewFrame.height <= 360)
        assertBatchControlsWithin(activity, 800, 360)
        activity.privateField<Button>("expand").performClick()
        render(activity, 800, 360, "camera-landscape-expanded.png")
        assertTrue("Agrandir must widen the landscape camera modal", activity.window.attributes.width > compactWidth)
        assertSame("expansion keeps the TextureView surface attached", parentBefore, previewFrame.parent)
        assertBatchControlsWithin(activity, 800, 360)
    }

    @Test
    fun scannerHandoffDoesNotFinishTheCameraOrFailItsPendingBatch() {
        val capture = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 1, maxImages = 2)
        captureId = capture.id
        controller = Robolectric.buildActivity(
            NoteCameraActivity::class.java,
            Intent(context, NoteCameraActivity::class.java).putExtra("captureId", capture.id),
        )
        val activity = controller!!.create().start().resume().get()
        NoteCameraActivity::class.java.getDeclaredField("scannerInFlight").apply {
            isAccessible = true
            setBoolean(activity, true)
        }

        controller!!.pause().stop()

        assertTrue(NoteCameraActivity.handles(capture.id))
        assertFalse(requireNotNull(store.pending()).complete)
        assertTrue(requireNotNull(store.pending()).allImages.isEmpty())
    }

    private inline fun <reified T> NoteCameraActivity.privateField(name: String): T =
        NoteCameraActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(this@privateField) as T
        }

    private fun createCameraBatchActivity(preloadImages: Int = 2): NoteCameraActivity {
        val capture = store.beginBatch(NoteImageKind.CAMERA, resume = false, number = 1, maxImages = 3)
        captureId = capture.id
        repeat(preloadImages) {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (it % 2 == 0) android.graphics.Color.RED else android.graphics.Color.BLUE)
            }
            try { store.store(capture.id, bitmap) } finally { bitmap.recycle() }
        }
        controller = Robolectric.buildActivity(
            NoteCameraActivity::class.java,
            Intent(context, NoteCameraActivity::class.java).putExtra("captureId", capture.id),
        )
        return controller!!.create().get()
    }

    private fun assertBatchControlsWithin(activity: NoteCameraActivity, width: Int, height: Int) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val buttons = descendants(content).filterIsInstance<Button>()
        listOf("Scanner", "Valider", "Annuler").forEach { label ->
            val button = buttons.first { it.text.toString().contains(label, ignoreCase = true) }
            assertTrue("$label must be visible in the laid out hierarchy", isVisibleWithinParents(button) && button.width > 0 && button.height > 0)
            assertButtonInsideViewport(content, button, width, height)
        }
        val shutter = buttons.first { it.contentDescription.toString().contains("Prendre la photo", ignoreCase = true) }
        assertTrue("photo shutter must be visible", isVisibleWithinParents(shutter) && shutter.width > 0 && shutter.height > 0)
        assertButtonInsideViewport(content, shutter, width, height)
        val expander = buttons.first { it.text.toString().contains("Agrandir", ignoreCase = true) ||
            it.text.toString().contains("Réduire", ignoreCase = true) }
        assertTrue("expand control must remain visible in the laid out hierarchy",
            isVisibleWithinParents(expander) && expander.width > 0 && expander.height > 0)
        assertButtonInsideViewport(content, expander, width, height)
    }

    private fun assertButtonInsideViewport(content: ViewGroup, button: Button, width: Int, height: Int) {
        val bounds = android.graphics.Rect(0, 0, button.width, button.height)
        content.offsetDescendantRectToMyCoords(button, bounds)
        assertTrue("button left edge must be within ${width}px", bounds.left >= 0)
        assertTrue("button right edge must be within the ${content.width}px content", bounds.right <= minOf(width, content.width))
        assertTrue("button top must be within ${height}px", bounds.top >= 0)
        assertTrue("button bottom must be within the ${content.height}px content", bounds.bottom <= minOf(height, content.height))
    }

    private fun isVisibleWithinParents(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun render(activity: NoteCameraActivity, width: Int, height: Int, name: String) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, width, decor.measuredHeight.coerceAtMost(height))
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val root = activity.privateField<LinearLayout>("cameraRoot")
            val windowLayout = activity.window.attributes
            val modalWidth = windowLayout.width.takeIf { it in 1..width } ?: width
            val modalHeight = windowLayout.height.takeIf { it in 1..height } ?: height
            root.measure(
                View.MeasureSpec.makeMeasureSpec(modalWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(modalHeight, View.MeasureSpec.EXACTLY),
            )
            val left = (width - modalWidth) / 2
            root.layout(left, 0, left + modalWidth, modalHeight)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            val directory = java.io.File("build/reports/media").apply { mkdirs() }
            java.io.File(directory, name).outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
