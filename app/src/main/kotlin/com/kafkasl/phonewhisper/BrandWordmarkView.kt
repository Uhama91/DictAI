package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * TextView used by the home wordmark.
 *
 * Caveat's capital I has a right overhang that is wider than its advance width. Android's
 * regular wrap-content measurement follows the advance and can clip the rounded end of the
 * top stroke. This view reserves that overhang explicitly while retaining normal TextView
 * font scaling and accessibility behaviour.
 */
class BrandWordmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : TextView(context, attrs, defStyleAttr) {

    private val textBounds = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val value = text?.toString().orEmpty()
        if (value.isEmpty()) return

        val advance = paint.measureText(value)
        paint.getTextBounds(value, 0, value.length, textBounds)
        val rightOverhang = max(0f, textBounds.right - advance)
        // Keep a small anti-aliasing buffer so the final rounded pixel remains visible at all
        // densities. This is separate from the optional layout padding used for the wave gap.
        val safety = 2f * resources.displayMetrics.density
        val desiredWidth = measuredWidth + ceil(rightOverhang + safety).toInt()

        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val measuredWidthWithOverhang = when (mode) {
            View.MeasureSpec.EXACTLY -> size
            View.MeasureSpec.AT_MOST -> minOf(desiredWidth, size)
            else -> desiredWidth
        }
        if (measuredWidthWithOverhang > measuredWidth) {
            setMeasuredDimension(measuredWidthWithOverhang, measuredHeight)
        }
    }
}
