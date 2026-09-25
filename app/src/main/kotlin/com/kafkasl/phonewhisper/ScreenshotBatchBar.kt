package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** A compact, non-focusable overlay controller for screenshot batches. */
internal class ScreenshotBatchBar(context: Context) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val status = TextView(context)
    private val thumbs = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val thumbScroll = HorizontalScrollView(context)
    val captureButton: TextView
    val acceptButton: TextView
    val cancelButton: TextView
    var onCapture: (() -> Unit)? = null
    var onAccept: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onRemoveImage: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(0xF022342B.toInt())
            setStroke(dp(1), 0xFF69C995.toInt())
        }
        elevation = dp(8).toFloat()

        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        status.setTextColor(Color.WHITE)
        status.textSize = 12f
        status.maxLines = 2
        actions.addView(status, LayoutParams(0, dp(42), 1f))
        captureButton = action("Capturer") {
            contentDescription = "Capturer une autre image"
            onClickListener { this@ScreenshotBatchBar.onCapture?.invoke() }
        }
        acceptButton = action("Valider") {
            contentDescription = "Valider la série et l’insérer dans le texte"
            onClickListener { this@ScreenshotBatchBar.onAccept?.invoke() }
        }
        cancelButton = action("Annuler") {
            contentDescription = "Annuler la série et supprimer ses images"
            onClickListener { this@ScreenshotBatchBar.onCancel?.invoke() }
        }
        actions.addView(captureButton, LayoutParams(dp(82), dp(42)))
        actions.addView(acceptButton, LayoutParams(dp(72), dp(42)))
        actions.addView(cancelButton, LayoutParams(dp(76), dp(42)))
        addView(actions, LayoutParams(-1, dp(42)))

        thumbScroll.isHorizontalScrollBarEnabled = false
        thumbScroll.visibility = GONE
        thumbScroll.addView(thumbs, LayoutParams(-2, dp(38)))
        addView(thumbScroll, LayoutParams(-1, dp(38)))
    }

    fun bind(pending: PendingNoteCapture) {
        captureButton.text = "Capturer"
        captureButton.contentDescription = "Capturer une autre image"
        captureButton.layoutParams = (captureButton.layoutParams as? LayoutParams)?.apply { width = dp(82) }
        acceptButton.visibility = VISIBLE
        cancelButton.visibility = VISIBLE
        val count = pending.allImages.size
        status.text = pending.message ?: pending.error ?: "Captures : $count / ${pending.maxImages}"
        val canChange = pending.batch && !pending.accepted
        captureButton.isEnabled = canChange && count < pending.maxImages
        acceptButton.isEnabled = canChange && count > 0
        cancelButton.isEnabled = canChange
        thumbs.removeAllViews()
        pending.allImages.forEach { image ->
            val chip = FrameLayout(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF40584A.toInt()) }
                contentDescription = "Retirer l’image ${image.number} de la série"
                isFocusable = true
                setOnClickListener { onRemoveImage?.invoke(image.id) }
            }
            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = chip.contentDescription
                tag = image.id
            }
            chip.addView(preview, FrameLayout.LayoutParams(-1, -1))
            chip.addView(TextView(context).apply {
                text = "${image.number} ×"
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(0xBB000000.toInt())
            }, FrameLayout.LayoutParams(-2, dp(16), Gravity.BOTTOM or Gravity.END))
            thumbs.addView(chip, LayoutParams(dp(40), dp(32)).apply { marginEnd = dp(4) })
            thread(name = "dictai-screenshot-batch-thumb") {
                val bitmap: Bitmap? = runCatching {
                    NoteImageStore.decode(NoteImageStore(context).thumbnail(image.id), 120)
                }.getOrNull()
                preview.post {
                    if (preview.tag == image.id && preview.parent != null) preview.setImageBitmap(bitmap)
                    else bitmap?.recycle()
                }
            }
        }
        thumbScroll.visibility = if (count > 0) VISIBLE else GONE
    }

    /** Accepted captures are immutable; only durable delivery may be retried. */
    fun showDeliveryRetry() {
        status.text = "Série conservée · ajout au texte en attente"
        captureButton.text = "Réessayer l’ajout des images"
        captureButton.contentDescription = "Réessayer l’ajout des images au texte"
        captureButton.layoutParams = (captureButton.layoutParams as? LayoutParams)?.apply { width = dp(148) }
        captureButton.isEnabled = true
        acceptButton.visibility = GONE
        cancelButton.visibility = GONE
        acceptButton.isEnabled = false
        cancelButton.isEnabled = false
    }

    private fun action(label: String, init: TextView.() -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFF31483A.toInt())
        }
        isFocusable = true
        init()
    }

    private fun TextView.onClickListener(action: () -> Unit) = setOnClickListener { action() }
    private fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(1)
}
