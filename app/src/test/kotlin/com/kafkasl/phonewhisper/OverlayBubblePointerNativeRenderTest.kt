package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Canvas proof that the decorative tail renders on each screen edge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayBubblePointerNativeRenderTest {
    @Test
    fun pointerRendersToTheRealPillDirectionOnAllEdges() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(android.app.Activity::class.java)
        val activity = controller.create().start().resume().visible().get()
        val root = FrameLayout(activity).apply { setBackgroundColor(ThemeTokens.palette(context).bg) }
        activity.setContentView(root)

        val cellWidth = 190
        val cellHeight = 124
        val bodyWidth = 150
        val bodyHeight = 82
        val pointerLength = 12
        val edges = Edge.values()
        val pointerRegions = ArrayList<IntArray>()
        edges.forEachIndexed { index, edge ->
            val envelope = OverlayPlacement.bubbleEnvelope(Rect(0, 0, bodyWidth, bodyHeight), edge, pointerLength)
            val cell = FrameLayout(activity)
            val pointer = OverlayBubblePointerView(activity)
            pointer.setColors(ThemeTokens.palette(context).surface, ThemeTokens.palette(context).stroke)
            pointer.setGeometry(
                edge = edge,
                bodyOffsetX = envelope.bodyOffsetX,
                bodyOffsetY = envelope.bodyOffsetY,
                bodyWidth = bodyWidth,
                bodyHeight = bodyHeight,
                pointerLength = pointerLength,
                targetX = when (edge) {
                    Edge.TOP, Edge.BOTTOM -> if (index % 2 == 0) 36f else 126f
                    else -> 0f
                },
                targetY = when (edge) {
                    Edge.LEFT, Edge.RIGHT -> if (index % 2 == 0) 24f else 66f
                    else -> 0f
                },
            )
            val body = View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(ThemeTokens.palette(context).surface)
                    setStroke(1, ThemeTokens.palette(context).stroke)
                }
                clipToOutline = true
            }
            cell.addView(body, FrameLayout.LayoutParams(bodyWidth, bodyHeight).apply {
                leftMargin = envelope.bodyOffsetX
                topMargin = envelope.bodyOffsetY
            })
            // Match production z-order: the pointer can cover only its small body overlap, which
            // removes the straight border seam while keeping all transcript children clipped by
            // the rounded body.
            cell.addView(pointer, FrameLayout.LayoutParams(envelope.window.width, envelope.window.height))
            val cellX = (index % 2) * cellWidth
            val cellY = (index / 2) * cellHeight
            pointerRegions += when (edge) {
                Edge.LEFT -> intArrayOf(cellX, cellY, cellX + pointerLength, cellY + envelope.window.height)
                Edge.RIGHT -> intArrayOf(cellX + bodyWidth, cellY, cellX + bodyWidth + pointerLength, cellY + envelope.window.height)
                Edge.TOP -> intArrayOf(cellX, cellY, cellX + envelope.window.width, cellY + pointerLength)
                Edge.BOTTOM -> intArrayOf(cellX, cellY + bodyHeight, cellX + envelope.window.width, cellY + bodyHeight + pointerLength)
            }
            root.addView(cell, FrameLayout.LayoutParams(cellWidth, cellHeight).apply {
                leftMargin = cellX
                topMargin = cellY
            })
        }

        val width = cellWidth * 2
        val height = cellHeight * 2
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, width, height)
        val output = File("build/robolectric-renders", "bubble-pointer.png").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            assertTrue("Native pointer render should contain opaque body pixels", pixels.count { Color.alpha(it) > 200 } > 4_000)
            val bg = ThemeTokens.palette(context).bg
            pointerRegions.forEach { region ->
                var changed = 0
                for (y in region[1] until region[3]) for (x in region[0] until region[2]) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) != Color.red(bg) || Color.green(pixel) != Color.green(bg) || Color.blue(pixel) != Color.blue(bg)) {
                        changed++
                    }
                }
                assertTrue("Pointer should render pixels outside the rounded body", changed > 0)
            }
            assertTrue("Native pointer render should be written", output.length() > 1_000L)
        } finally {
            bitmap.recycle()
            runCatching { controller.pause().stop().destroy() }
        }
    }
}
