package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.io.FileOutputStream

/** Native geometry and render diagnostic for minimum-height panel corner targets. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayCornerHandlesDiagnosticTest {
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        invoke(service, "showButton")
        invoke(service, "setLivePreviewVisible", true)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        ShadowSettings.reset()
    }

    @Test
    fun `minimum reduced panel gives each vertical corner pair a distinct touch band`() {
        val body = field<FrameLayout>(service, "livePanelBody")
        invoke(service, "layoutTranscriptRows", 160, 1f)
        val handles = field<Map<PanelResizeHandle, View>>(service, "panelResizeHandles")
        assertTrue(handles.values.all { it.visibility == View.VISIBLE })
        render(body, 240, 160, "overlay-corner-targets-reduced-after-fix")

        val topLeft = targetRect(handles.getValue(PanelResizeHandle.TOP_LEFT))
        val topRight = targetRect(handles.getValue(PanelResizeHandle.TOP_RIGHT))
        val bottomLeft = targetRect(handles.getValue(PanelResizeHandle.BOTTOM_LEFT))
        val bottomRight = targetRect(handles.getValue(PanelResizeHandle.BOTTOM_RIGHT))

        assertFalse("left corner handles overlap: $topLeft / $bottomLeft", intersects(topLeft, bottomLeft))
        assertFalse("right corner handles overlap: $topRight / $bottomRight", intersects(topRight, bottomRight))
        assertTrue(topLeft.left == 0 && topLeft.right <= 36)
        assertTrue(topRight.right == 240 && topRight.left >= 204)
        assertTrue(bottomLeft.left == 0 && bottomLeft.right <= 36)
        assertTrue(bottomRight.right == 240 && bottomRight.left >= 204)
        assertDispatchesTo(body, handles.getValue(PanelResizeHandle.TOP_LEFT), topLeft, PanelResizeHandle.TOP_LEFT)
        assertDispatchesTo(body, handles.getValue(PanelResizeHandle.TOP_RIGHT), topRight, PanelResizeHandle.TOP_RIGHT)
        assertDispatchesTo(body, handles.getValue(PanelResizeHandle.BOTTOM_LEFT), bottomLeft, PanelResizeHandle.BOTTOM_LEFT)
        assertDispatchesTo(body, handles.getValue(PanelResizeHandle.BOTTOM_RIGHT), bottomRight, PanelResizeHandle.BOTTOM_RIGHT)
    }

    @Test
    fun `standard note panel keeps corner targets apart and renders the note rows`() {
        setField(service, "purpose", DictationPurpose.NOTE)
        val body = field<FrameLayout>(service, "livePanelBody")
        invoke(service, "layoutTranscriptRows", 268, 1f)
        val handles = field<Map<PanelResizeHandle, View>>(service, "panelResizeHandles")
        assertTrue(handles.values.all { it.visibility == View.VISIBLE })
        render(body, 312, 268, "overlay-corner-targets-standard-note-after-fix")

        val topLeft = targetRect(handles.getValue(PanelResizeHandle.TOP_LEFT))
        val bottomLeft = targetRect(handles.getValue(PanelResizeHandle.BOTTOM_LEFT))
        val topRight = targetRect(handles.getValue(PanelResizeHandle.TOP_RIGHT))
        val bottomRight = targetRect(handles.getValue(PanelResizeHandle.BOTTOM_RIGHT))

        assertFalse(intersects(topLeft, bottomLeft))
        assertFalse(intersects(topRight, bottomRight))
    }

    @Test
    fun `corner targets shrink below minimum then disappear and reappear after the keyboard leaves`() {
        val body = field<FrameLayout>(service, "livePanelBody")
        val handles = field<Map<PanelResizeHandle, View>>(service, "panelResizeHandles")

        invoke(service, "layoutTranscriptRows", 120, 1f)
        assertTrue(handles.values.all { it.visibility == View.VISIBLE })
        render(body, 240, 120, "overlay-corner-targets-keyboard-120")
        val compactHeight = targetRect(handles.getValue(PanelResizeHandle.TOP_LEFT)).height()
        assertEquals(12, compactHeight)
        assertFalse(intersects(
            targetRect(handles.getValue(PanelResizeHandle.TOP_LEFT)),
            targetRect(handles.getValue(PanelResizeHandle.BOTTOM_LEFT)),
        ))

        invoke(service, "layoutTranscriptRows", 96, 1f)
        assertTrue(handles.values.all { it.visibility == View.GONE })

        invoke(service, "layoutTranscriptRows", 160, 1f)
        assertTrue(handles.values.all { it.visibility == View.VISIBLE })
        render(body, 240, 160, "overlay-corner-targets-reduced-after-fix")
        assertEquals(32, targetRect(handles.getValue(PanelResizeHandle.TOP_LEFT)).height())

        setField(service, "purpose", DictationPurpose.NOTE)
        invoke(service, "layoutTranscriptRows", 268, 1f)
        assertTrue(handles.values.all { it.visibility == View.VISIBLE })
        render(body, 312, 268, "overlay-corner-targets-standard-note-after-fix")
        assertEquals(36, targetRect(handles.getValue(PanelResizeHandle.TOP_LEFT)).height())
    }

    private fun targetRect(view: View): Rect = Rect().also(view::getHitRect)

    private fun assertDispatchesTo(
        body: View,
        handle: View,
        bounds: Rect,
        expected: PanelResizeHandle,
    ) {
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        assertTrue(send(body, MotionEvent.ACTION_DOWN, x, y))
        assertEquals(expected, field<PanelResizeHandle?>(service, "panelGestureHandle"))
        assertTrue(send(body, MotionEvent.ACTION_UP, x, y))
        assertEquals(handle, field<Map<PanelResizeHandle, View>>(service, "panelResizeHandles").getValue(expected))
    }

    private fun send(view: View, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)
        return try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun render(body: View, width: Int, height: Int, name: String) {
        body.visibility = View.VISIBLE
        body.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        body.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            body.draw(Canvas(bitmap))
            val output = File("build/robolectric-renders/$name.png")
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun intersects(first: Rect, second: Rect): Boolean =
        first.left < second.right && first.right > second.left &&
            first.top < second.bottom && first.bottom > second.top

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            set(target, value)
        }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        method.invoke(target, *args)
    }
}
