package com.kafkasl.phonewhisper

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSettings
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/** Minimal window-level coverage for the pill's continuous format gesture and panel affordances. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceGestureRobolectricTest {
    private lateinit var context: android.content.Context
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private var previousSelected: String? = null
    private var previousCustom: String? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        val formatPrefs = context.getSharedPreferences("dictai_formats", android.content.Context.MODE_PRIVATE)
        previousSelected = formatPrefs.getString("selected", null)
        previousCustom = formatPrefs.getString("custom", null)
        formatPrefs.edit().clear().putString("selected", "cleanup").apply()
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        // onCreate may be prevented from starting an FGS by the host; showButton is idempotent
        // and lets this test exercise the actual WindowManager/view listener in either case.
        invoke(service, "showButton")
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        val formatPrefs = context.getSharedPreferences("dictai_formats", android.content.Context.MODE_PRIVATE)
        val edit = formatPrefs.edit().clear()
        previousSelected?.let { edit.putString("selected", it) }
        previousCustom?.let { edit.putString("custom", it) }
        edit.apply()
        ShadowSettings.reset()
    }

    @Test
    fun `upward pull opens menu and a held second move commits the next format`() {
        val pill = field<FrameLayout>(service, "pill")
        val formats = PostProcessingFormats(context)
        assertEquals("cleanup", formats.selected().id)

        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, -36f)
        assertNotNull(field<Any>(service, "formatMenuParams"))
        assertEquals(0, field<Int>(service, "formatMenuSelected"))
        send(pill, MotionEvent.ACTION_MOVE, 20f, -100f)
        send(pill, MotionEvent.ACTION_UP, 20f, -100f)

        assertEquals("corrected", formats.selected().id)
        assertTrue(field<Any?>(service, "formatMenuParams") == null)
    }

    @Test
    fun `cancelled reveal dismisses menu without changing the selected format`() {
        val pill = field<FrameLayout>(service, "pill")
        val formats = PostProcessingFormats(context)

        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, -36f)
        send(pill, MotionEvent.ACTION_CANCEL, 20f, -36f)

        assertEquals("cleanup", formats.selected().id)
        assertTrue(field<Any?>(service, "formatMenuParams") == null)
    }

    @Test
    fun `short reveal keeps the menu touchable for a normal tap`() {
        val pill = field<FrameLayout>(service, "pill")

        send(pill, MotionEvent.ACTION_DOWN, 20f, 20f)
        send(pill, MotionEvent.ACTION_MOVE, 20f, -36f)
        send(pill, MotionEvent.ACTION_UP, 20f, -36f)

        val layout = field<WindowManager.LayoutParams>(service, "formatMenuParams")
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
    }

    @Test
    fun `reduced panel exposes independent move and resize targets`() {
        invoke(service, "setLivePreviewVisible", true)
        val handles = field<Map<PanelResizeHandle, View>>(service, "panelResizeHandles")
        assertEquals(4, handles.size)
        assertTrue(handles.values.all { it.visibility == View.VISIBLE })

        val move = field<View>(service, "panelMoveHandle")
        val initial = field<Rect>(service, "panelBodyRect")
        assertNotNull(initial)
        send(move, MotionEvent.ACTION_DOWN, 100f, 100f)
        send(move, MotionEvent.ACTION_MOVE, 140f, 130f)
        send(move, MotionEvent.ACTION_UP, 140f, 130f)
        assertNotNull(OverlayPanelPrefs(context).load())

        val topLeft = handles.getValue(PanelResizeHandle.TOP_LEFT)
        val beforeResize = field<Rect>(service, "panelBodyRect")
        send(topLeft, MotionEvent.ACTION_DOWN, 0f, 100f)
        send(topLeft, MotionEvent.ACTION_MOVE, -24f, 76f)
        send(topLeft, MotionEvent.ACTION_UP, -24f, 76f)
        val afterResize = field<Rect>(service, "panelBodyRect")
        assertTrue(afterResize.width != beforeResize.width || afterResize.height != beforeResize.height)

        move.performLongClick()
        assertTrue(OverlayPanelPrefs(context).load() == null)
    }

    private fun send(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(
            0L,
            SystemClock.uptimeMillis(),
            action,
            x,
            y,
            0,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        method.invoke(target, *args)
    }
}
