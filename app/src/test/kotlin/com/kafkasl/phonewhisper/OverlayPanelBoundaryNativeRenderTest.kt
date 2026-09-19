package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
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

/** Native panel and pill scenes used to inspect the two horizontal boundary orientations. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayPanelBoundaryNativeRenderTest {
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        invoke(service, "showButton")
        invoke(service, "setLivePreviewVisible", true)
        field<OverlayTranscriptEditor>(service, "liveText").setText(
            "Une transcription lisible reste visible pendant le déplacement."
        )
        invoke(service, "layoutTranscriptRows", 268, 1f)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        ShadowSettings.reset()
    }

    @Test
    fun `native boundary scenes render text with pill on either side`() {
        val body = field<FrameLayout>(service, "livePanelBody")
        val pill = field<FrameLayout>(service, "pill")
        val screen = Rect(0, 0, 400, 850)
        val geometry = OverlayPanelGeometry(
            minWidth = 240,
            minHeight = 160,
            maxWidthFraction = .92f,
            maxHeightFraction = .75f,
            gap = 12,
        )
        val rightPill = Rect(326, 100, 74, 44)
        val rightInitial = Rect(32, 20, 240, 268)
        val rightResult = geometry.resize(
            rightInitial,
            PanelResizeHandle.BOTTOM_RIGHT,
            dx = 200f,
            dy = 0f,
            screen,
            rightPill,
        )
        assertEquals(32, rightResult.x)
        assertEquals(282, rightResult.width)
        assertEquals(268, rightResult.height)

        val leftPill = Rect(0, 100, 74, 44)
        val leftInitial = Rect(100, 20, 240, 268)
        val leftResult = geometry.resize(
            leftInitial,
            PanelResizeHandle.BOTTOM_RIGHT,
            dx = 200f,
            dy = 0f,
            screen,
            leftPill,
        )
        assertEquals(100, leftResult.x)
        assertEquals(300, leftResult.width)
        assertEquals(268, leftResult.height)

        // The render uses the actual rectangles accepted by the collision-aware resize helper,
        // so each PNG documents the fixed corner, preserved height, and 12dp pill gap.
        val panelLeftOutput = renderScene(
            body,
            pill,
            rightResult,
            rightPill,
            "overlay-boundary-panel-left.png",
        )
        val panelRightOutput = renderScene(
            body,
            pill,
            leftResult,
            leftPill,
            "overlay-boundary-panel-right.png",
        )

        assertTrue(panelLeftOutput.length() > 1_000L)
        assertTrue(panelRightOutput.length() > 1_000L)
    }

    private fun renderScene(
        body: FrameLayout,
        pill: FrameLayout,
        panel: Rect,
        pillRect: Rect,
        name: String,
    ): File {
        val width = 400
        val height = 320
        body.visibility = View.VISIBLE
        body.measure(
            View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panel.height, View.MeasureSpec.EXACTLY),
        )
        body.layout(0, 0, panel.width, panel.height)
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(pillRect.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pillRect.height, View.MeasureSpec.EXACTLY),
        )
        pill.layout(0, 0, pillRect.width, pillRect.height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(20, 24, 22))
            canvas.save()
            canvas.translate(panel.x.toFloat(), panel.y.toFloat())
            body.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.translate(pillRect.x.toFloat(), pillRect.y.toFloat())
            pill.draw(canvas)
            canvas.restore()
            val output = File("build/robolectric-renders/$name").apply { parentFile?.mkdirs() }
            output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            return output
        } finally {
            bitmap.recycle()
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
