package com.kafkasl.phonewhisper

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

internal class MediaBlocksHostActivity : Activity()

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayTranscriptEditorImageBlocksRobolectricTest {
    private fun projection(): TranscriptImageProjection {
        val image = NoteImage("00000000-0000-0000-0000-000000000001", 1,
            NoteImageKind.CAMERA, 1L, 640, 480)
        return TranscriptImageBlocks.fromDraft(
            "Avant\uFFFC\nAprès\nFin",
            listOf(DraftImageCapture(image.id, 5, image, groupId = "block-a")),
        )
    }

    @Test fun verticalLongPressDragKeepsScrollViewFromTakingTheGesture() {
        val activityController = Robolectric.buildActivity(MediaBlocksHostActivity::class.java).setup()
        val context = activityController.get()
        var intercepted = false
        var parentDisallowed = false
        val scroll = object : ScrollView(context) {
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                if (disallowIntercept) parentDisallowed = true
                super.requestDisallowInterceptTouchEvent(disallowIntercept)
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                val result = super.onInterceptTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE && result) intercepted = true
                return result
            }
        }
        val editor = OverlayTranscriptEditor(context).apply {
            canEdit = { true }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(18f)
            setPadding(0, 0, 0, 0)
            background = null
            acquireWindow = {}
        }
        val imageRenderer = TranscriptImageBlockRenderer(context)
        val projection = projection()
        imageRenderer.render(editor, projection)
        var moved: Pair<String, Int>? = null
        editor.imageBlockAtEditorOffset = { offset -> imageRenderer.blockAtEditorOffset(editor, offset)?.id }
        editor.imageBlockRange = { id -> imageRenderer.blockRange(editor, id) }
        editor.moveImageBlockToEditorOffset = { id, offset -> moved = id to offset; true }
        scroll.addView(editor, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (240 * context.resources.displayMetrics.density).toInt(),
        ))
        val width = (300 * context.resources.displayMetrics.density).toInt()
        val height = (120 * context.resources.displayMetrics.density).toInt()
        context.setContentView(scroll)
        ShadowLooper.idleMainLooper()
        scroll.measure(exact(width), exact(height))
        scroll.layout(0, 0, width, height)
        editor.measure(exact(width), exact((240 * context.resources.displayMetrics.density).toInt()))
        editor.layout(0, 0, width, (240 * context.resources.displayMetrics.density).toInt())
        val range = requireNotNull(imageRenderer.blockRange(editor, "block-a"))
        val line = editor.layout.getLineForOffset(range.first)
        val downX = editor.layout.getPrimaryHorizontal(range.first) + 2f
        val downY = editor.layout.getLineBaseline(line).toFloat()
        assertEquals("block-a", imageRenderer.blockAtEditorOffset(editor,
            editor.getOffsetForPosition(downX, downY))?.id)
        val targetOffset = editor.getOffsetForPosition(width - 8f, editor.height - 8f)
        assertTrue("the vertical drop point should be past the image", targetOffset > range.second)

        val downTime = SystemClock.uptimeMillis()
        assertTrue("the editor is attached so its native long-press timer is active", editor.isAttachedToWindow)
        dispatch(scroll, MotionEvent.ACTION_DOWN, downTime, downTime, downX, downY)
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout().toLong() + 1)
        assertTrue("long press asks its parent not to intercept", parentDisallowed)
        val moveTime = downTime + ViewConfiguration.getLongPressTimeout() + 20L
        dispatch(scroll, MotionEvent.ACTION_MOVE, downTime, moveTime, width - 8f, editor.height - 8f)
        dispatch(scroll, MotionEvent.ACTION_UP, downTime, moveTime + 20L, width - 8f, editor.height - 8f)

        assertFalse("ScrollView must not steal the vertical drag", intercepted)
        assertEquals("block-a", moved?.first)
        assertEquals(targetOffset, moved?.second)
        activityController.pause().stop().destroy()
    }

    @Test fun selectedBlockCanMoveToASeparateCaretThroughItsAccessibilityAction() {
        val activityController = Robolectric.buildActivity(MediaBlocksHostActivity::class.java).setup()
        val context = activityController.get()
        val editor = OverlayTranscriptEditor(context).apply {
            canEdit = { true }
            setTextSize(18f)
            setPadding(0, 0, 0, 0)
            background = null
            acquireWindow = {}
        }
        val renderer = TranscriptImageBlockRenderer(context)
        renderer.render(editor, projection())
        editor.imageBlockAtEditorOffset = { offset -> renderer.blockAtEditorOffset(editor, offset)?.id }
        editor.imageBlockRange = { id -> renderer.blockRange(editor, id) }
        var moved: Pair<String, Int>? = null
        editor.moveImageBlockToEditorOffset = { id, offset -> moved = id to offset; true }
        val width = (300 * context.resources.displayMetrics.density).toInt()
        val height = (160 * context.resources.displayMetrics.density).toInt()
        context.setContentView(editor, ViewGroup.LayoutParams(width, height))
        ShadowLooper.idleMainLooper()
        editor.measure(exact(width), exact(height))
        editor.layout(0, 0, width, height)
        val range = requireNotNull(renderer.blockRange(editor, "block-a"))
        val line = editor.layout.getLineForOffset(range.first)
        val x = editor.layout.getPrimaryHorizontal(range.first) + 2f
        val y = editor.layout.getLineBaseline(line).toFloat()
        val downTime = SystemClock.uptimeMillis()
        dispatch(editor, MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        dispatch(editor, MotionEvent.ACTION_UP, downTime, downTime + 20L, x, y)
        assertEquals(range.first to range.second, editor.selectionStart to editor.selectionEnd)

        val distantCaret = editor.length()
        editor.setSelection(distantCaret)
        assertTrue(editor.performAccessibilityAction(0x01020001, null))
        assertEquals("block-a" to distantCaret, moved)
        activityController.pause().stop().destroy()
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun dispatch(view: View, action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
