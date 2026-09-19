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
import org.robolectric.shadows.ShadowLooper
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
        val paramsBeforeResize = field<WindowManager.LayoutParams>(service, "params")
        val pillBeforeResize = Rect(paramsBeforeResize.x, paramsBeforeResize.y, paramsBeforeResize.width, paramsBeforeResize.height)
        send(topLeft, MotionEvent.ACTION_DOWN, 0f, 100f)
        send(topLeft, MotionEvent.ACTION_MOVE, -24f, 76f)
        send(topLeft, MotionEvent.ACTION_UP, -24f, 76f)
        val afterResize = field<Rect>(service, "panelBodyRect")
        assertTrue(afterResize.width != beforeResize.width || afterResize.height != beforeResize.height)
        val paramsAfterResize = field<WindowManager.LayoutParams>(service, "params")
        assertEquals(pillBeforeResize, Rect(paramsAfterResize.x, paramsAfterResize.y, paramsAfterResize.width, paramsAfterResize.height))

        move.performLongClick()
        assertTrue(OverlayPanelPrefs(context).load() == null)
    }

    @Test
    fun `panel move listener keeps the accepted boundary and responds to the next reverse delta`() {
        invoke(service, "setLivePreviewVisible", true)
        val text = field<OverlayTranscriptEditor>(service, "liveText")
        text.setText("Texte de test conservé")
        val move = field<View>(service, "panelMoveHandle")
        val params = field<WindowManager.LayoutParams>(service, "params")
        val initialPill = Rect(params.x, params.y, params.width, params.height)
        val initialBody = field<Rect>(service, "panelBodyRect")
        assertNotNull(initialBody)
        val direction = if (initialBody.centerX < initialPill.centerX) 1f else -1f

        send(move, MotionEvent.ACTION_DOWN, 4f, 4f)
        val acceptedStart = field<Rect>(service, "reducedPanelRect")
        val farX = 4f + direction * 1_000f
        send(move, MotionEvent.ACTION_MOVE, farX, 4f)
        val atBoundary = field<Rect>(service, "reducedPanelRect")
        assertEquals(acceptedStart.width, atBoundary.width)
        assertEquals(acceptedStart.height, atBoundary.height)
        assertTrue(!intersectsWithGap(atBoundary, initialPill, gap = 12))

        send(move, MotionEvent.ACTION_MOVE, farX - direction * 24f, 4f)
        val afterReverse = field<Rect>(service, "reducedPanelRect")
        assertTrue(afterReverse.x != atBoundary.x || afterReverse.y != atBoundary.y)
        send(move, MotionEvent.ACTION_UP, farX - direction * 24f, 4f)

        assertEquals("Texte de test conservé", text.text.toString())
        assertEquals(acceptedStart.width, field<Rect>(service, "reducedPanelRect").width)
        assertEquals(acceptedStart.height, field<Rect>(service, "reducedPanelRect").height)
    }

    @Test
    fun `pill drag listener stops at visible custom panel and keeps panel and pill size`() {
        invoke(service, "setLivePreviewVisible", true)
        val move = field<View>(service, "panelMoveHandle")
        send(move, MotionEvent.ACTION_DOWN, 4f, 4f)
        send(move, MotionEvent.ACTION_UP, 4f, 4f)
        invoke(service, "positionLivePanel", field<Anchor>(service, "currentAnchor"))

        val pillView = field<FrameLayout>(service, "pill")
        val params = field<WindowManager.LayoutParams>(service, "params")
        val bodyBefore = field<Rect>(service, "panelBodyRect")
        val pillBefore = Rect(params.x, params.y, params.width, params.height)
        val edge = OverlayPanelGeometry(240, 160, .92f, .75f, gap = 12).pointerEdge(bodyBefore, pillBefore)
        val target = when (edge) {
            Edge.LEFT -> 1_000f to 20f
            Edge.RIGHT -> -1_000f to 20f
            Edge.TOP -> 20f to 1_000f
            Edge.BOTTOM -> 20f to -1_000f
        }

        send(pillView, MotionEvent.ACTION_DOWN, 20f, 20f)
        ShadowLooper.idleMainLooper(500L)
        send(pillView, MotionEvent.ACTION_MOVE, target.first, target.second)
        val pillAtBoundary = Rect(params.x, params.y, params.width, params.height)
        val bodyAtBoundary = field<Rect>(service, "panelBodyRect")
        assertEquals(74, pillAtBoundary.width)
        assertEquals(44, pillAtBoundary.height)
        assertEquals("before=$bodyBefore after=$bodyAtBoundary", bodyBefore.width, bodyAtBoundary.width)
        assertEquals("before=$bodyBefore after=$bodyAtBoundary", bodyBefore.height, bodyAtBoundary.height)
        assertTrue(!intersectsWithGap(bodyBefore, pillAtBoundary, gap = 12))
        when (edge) {
            Edge.LEFT -> assertTrue(pillAtBoundary.right <= bodyBefore.x - 12)
            Edge.RIGHT -> assertTrue(pillAtBoundary.x >= bodyBefore.right + 12)
            Edge.TOP -> assertTrue(pillAtBoundary.bottom <= bodyBefore.y - 12)
            Edge.BOTTOM -> assertTrue(pillAtBoundary.y >= bodyBefore.bottom + 12)
        }

        send(pillView, MotionEvent.ACTION_UP, target.first, target.second)
        val pillAfter = Rect(params.x, params.y, params.width, params.height)
        assertEquals(74, pillAfter.width)
        assertEquals(44, pillAfter.height)
        val bodyAfter = field<Rect>(service, "panelBodyRect")
        assertTrue(!intersectsWithGap(bodyAfter, pillAfter, gap = 12))
        val finalAnchor = field<Anchor>(service, "currentAnchor")
        val screen = invokeValue(service, "screenRect") as Rect
        val roundTrip = OverlayPlacement.pillPosition(finalAnchor, Rect(0, 0, 74, 44), screen)
        assertEquals(roundTrip.x, pillAfter.x)
        assertEquals(roundTrip.y, pillAfter.y)
        assertEquals(finalAnchor, PersistencePrefs(context).loadAnchor(74, 44, screen.width, screen.height))
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

    private fun invokeValue(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun intersectsWithGap(first: Rect, second: Rect, gap: Int): Boolean {
        val expanded = Rect(second.x - gap, second.y - gap, second.width + gap * 2, second.height + gap * 2)
        return first.x < expanded.right && first.right > expanded.x &&
            first.y < expanded.bottom && first.bottom > expanded.y
    }
}
