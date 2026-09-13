package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** The export preparation and preview surface that lives inside OverlayService.livePanel. */
internal class OverlayExportPanel(
    context: android.content.Context,
    private val note: TranscriptNote,
    private val onBack: () -> Unit,
    private val onFormat: (OverlayExportFormat) -> Unit,
    private val onSave: (OverlayExportResult) -> Unit,
    private val onShare: (OverlayExportResult) -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var colors = ThemeTokens.palette(context)
    private val formatButtons = linkedMapOf<OverlayExportFormat, TextView>()
    private val status = TextView(context)
    private val previewHost = FrameLayout(context)
    private val saveButton = Button(context)
    private val shareButton = Button(context)
    private var headerTitle: TextView? = null
    private var backButton: ImageButton? = null
    private var textPreview: TextView? = null
    private val pdfControls = mutableListOf<TextView>()
    private var currentResult: OverlayExportResult? = null
    private var pdfPreview: OverlayPdfPreviewView? = null
    private var pdfPageLabel: TextView? = null
    private var htmlPreview: WebView? = null

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        isClickable = true
        contentDescription = "Préparation et aperçu de l’export de ${note.title}"
        build()
        showPreparing(OverlayExportFormat.PDF)
    }

    private fun build() {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        }
        addView(column, LayoutParams(-1, -1))

        val header = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            contentDescription = "Retour à la note"
            isFocusable = true
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setOnClickListener { onBack() }
        }
        backButton = back
        header.addView(back, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        val title = TextView(context).apply {
            text = "Exporter la note"
            textSize = 20f
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            runCatching { typeface = resources.getFont(R.font.caveat) }
        }
        headerTitle = title
        header.addView(title, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))
        column.addView(header)

        val formats = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        OverlayExportFormat.entries.forEach { format ->
            val button = TextView(context).apply {
                text = format.label
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                minHeight = (48 * density).toInt()
                isFocusable = true
                setOnClickListener { onFormat(format) }
            }
            formatButtons[format] = button
            formats.addView(button, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                leftMargin = (2 * density).toInt(); rightMargin = (2 * density).toInt()
            })
        }
        column.addView(formats)

        status.apply {
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))

        previewHost.apply {
            background = cardBackground(colors.raised, 18f)
            clipToOutline = true
        }
        column.addView(previewHost, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = (4 * density).toInt(); bottomMargin = (8 * density).toInt()
        })

        val actions = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        saveButton.apply {
            text = "Enregistrer"
            minHeight = (48 * density).toInt()
            setOnClickListener { currentResult?.let(onSave) }
        }
        shareButton.apply {
            text = "Partager"
            minHeight = (48 * density).toInt()
            setOnClickListener { currentResult?.let(onShare) }
        }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply { rightMargin = (3 * density).toInt() })
        actions.addView(shareButton, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply { leftMargin = (3 * density).toInt() })
        column.addView(actions)
        refreshTheme(colors)
    }

    fun showPreparing(format: OverlayExportFormat) {
        disposePreview()
        currentResult = null
        textPreview = null
        pdfControls.clear()
        previewHost.removeAllViews()
        status.text = "Préparation de ${format.label}…\nLa note est conservée."
        formatButtons.forEach { (candidate, button) -> button.isEnabled = false; button.alpha = if (candidate == format) 1f else .55f }
        saveButton.isEnabled = false
        shareButton.isEnabled = false
    }

    fun showResult(result: OverlayExportResult) {
        currentResult = result
        formatButtons.forEach { (candidate, button) ->
            button.isEnabled = true
            button.alpha = 1f
            styleChoice(button, candidate == result.format)
        }
        saveButton.isEnabled = true
        shareButton.isEnabled = true
        status.text = "Aperçu · ${result.format.label} · ${note.images.size} image${if (note.images.size > 1) "s" else ""}"
        renderPreview(result)
    }

    fun showError(format: OverlayExportFormat, message: String) {
        disposePreview()
        currentResult = null
        previewHost.removeAllViews()
        textPreview = null
        pdfControls.clear()
        status.text = message
        formatButtons.forEach { (candidate, button) ->
            button.isEnabled = true
            button.alpha = 1f
            styleChoice(button, candidate == format)
        }
        saveButton.isEnabled = false
        shareButton.isEnabled = false
    }

    fun refreshTheme(next: ThemePalette = ThemeTokens.palette(context)) {
        colors = next
        background = cardBackground(colors.surface, 24f)
        clipToOutline = true
        headerTitle?.setTextColor(colors.ink)
        backButton?.apply {
            imageTintList = android.content.res.ColorStateList.valueOf(colors.ink)
            background = cardBackground(colors.surface, 14f)
        }
        status.setTextColor(colors.inkMuted)
        previewHost.background = cardBackground(colors.raised, 18f)
        formatButtons.forEach { (format, button) -> styleChoice(button, currentResult?.format == format) }
        listOf(saveButton, shareButton).forEach { button ->
            button.setTextColor(colors.onGreen)
            button.background = cardBackground(colors.green, 18f)
        }
        pdfPreview?.setTheme(colors)
        htmlPreview?.setBackgroundColor(colors.raised)
        pdfPageLabel?.setTextColor(colors.inkMuted)
        pdfControls.forEach { control ->
            control.setTextColor(colors.ink)
            control.background = cardBackground(colors.surface, 14f)
        }
        textPreview?.setTextColor(colors.ink)
    }

    private fun styleChoice(button: TextView, selected: Boolean) {
        button.setTextColor(if (selected) colors.onGreen else colors.ink)
        button.background = cardBackground(if (selected) colors.green else colors.surface, 16f)
    }

    private fun renderPreview(result: OverlayExportResult) {
        disposePreview()
        previewHost.removeAllViews()
        textPreview = null
        pdfControls.clear()
        when (result.format) {
            OverlayExportFormat.PDF -> renderPdf(result)
            OverlayExportFormat.HTML -> renderHtml(result)
            OverlayExportFormat.TEXT_IMAGES -> renderTextAndImages()
        }
    }

    private fun renderPdf(result: OverlayExportResult) {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pdfControls.clear()
        val pdf = OverlayPdfPreviewView(context).apply {
            setTheme(colors)
            setFile(result.primaryFile)
        }
        pdfPreview = pdf
        column.addView(pdf, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun control(label: String, description: String, action: () -> Unit): TextView = TextView(context).apply {
            text = label; textSize = 14f; gravity = android.view.Gravity.CENTER
            minHeight = (48 * density).toInt(); isFocusable = true; contentDescription = description
            setOnClickListener { action() }
            setTextColor(colors.ink); background = cardBackground(colors.surface, 14f)
        }
        controls.addView(control("‹", "Page précédente", pdf::previousPage).also(pdfControls::add), LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        pdfPageLabel = TextView(context).apply { textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(colors.inkMuted) }
        controls.addView(pdfPageLabel, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))
        controls.addView(control("›", "Page suivante", pdf::nextPage).also(pdfControls::add), LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        controls.addView(control("−", "Réduire l’aperçu", { pdf.zoomBy(.8f) }).also(pdfControls::add), LinearLayout.LayoutParams((44 * density).toInt(), (48 * density).toInt()))
        controls.addView(control("+", "Agrandir l’aperçu", { pdf.zoomBy(1.25f) }).also(pdfControls::add), LinearLayout.LayoutParams((44 * density).toInt(), (48 * density).toInt()))
        pdf.onPageChanged = { page, count -> pdfPageLabel?.text = "${page + 1} / $count" }
        pdf.onError = { message -> status.text = message }
        column.addView(controls, LinearLayout.LayoutParams(-1, (48 * density).toInt()))
        previewHost.addView(column, LayoutParams(-1, -1))
    }

    private fun renderHtml(result: OverlayExportResult) {
        val web = WebView(context).apply {
            setBackgroundColor(colors.raised)
            settings.javaScriptEnabled = false
            settings.allowContentAccess = false
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
            }
            loadUrl(result.primaryFile.toURI().toString())
        }
        htmlPreview = web
        previewHost.addView(web, LayoutParams(-1, -1))
    }

    private fun renderTextAndImages() {
        val scroll = ScrollView(context)
        val text = TextView(context).apply {
            textSize = 16f
            setTextColor(colors.ink)
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            val complete = NoteShareText.create(note)
            val suffix = buildString {
                if (complete.length > 12_000) append("\n\nAperçu tronqué ici ; le fichier exporté reste complet.")
                if (note.images.isNotEmpty()) append("\n\n${note.images.size} image${if (note.images.size > 1) "s" else ""} jointe${if (note.images.size > 1) "s" else "e"}.")
            }
            text = complete.take(12_000) + suffix
        }
        textPreview = text
        scroll.addView(text, ViewGroup.LayoutParams(-1, -2))
        previewHost.addView(scroll, LayoutParams(-1, -1))
    }

    private fun disposePreview() {
        pdfPreview?.dispose()
        pdfPreview = null
        htmlPreview?.stopLoading()
        htmlPreview?.destroy()
        htmlPreview = null
    }

    fun dispose() {
        disposePreview()
        pdfPageLabel = null
        textPreview = null
        pdfControls.clear()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    private fun cardBackground(color: Int, radiusDp: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            setStroke(density.toInt().coerceAtLeast(1), this@OverlayExportPanel.colors.stroke)
        }
}

/** Bounded, single-page PDF renderer used by the embedded preview. */
internal class OverlayPdfPreviewView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private val renderer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictai-pdf-preview").apply { isDaemon = true }
    }
    private var file: File? = null
    private var page = 0
    private var pageCount = 0
    private var bitmap: Bitmap? = null
    private var zoom = 1f
    private var fitScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var colors = ThemeTokens.LIGHT
    @Volatile private var renderToken = 0L
    @Volatile private var disposed = false
    var onPageChanged: ((Int, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        isFocusable = true
        setBackgroundColor(colors.raised)
    }

    fun setTheme(next: ThemePalette) { if (disposed) return; colors = next; setBackgroundColor(colors.raised); invalidate() }

    fun setFile(next: File) {
        if (disposed) return
        file = next
        page = 0
        zoom = 1f
        panX = 0f; panY = 0f
        post { renderPage() }
    }

    fun previousPage() { if (page > 0) { page--; panX = 0f; panY = 0f; renderPage() } }
    fun nextPage() { if (page + 1 < pageCount) { page++; panX = 0f; panY = 0f; renderPage() } }
    fun zoomBy(factor: Float) { zoom = (zoom * factor).coerceIn(.75f, 3f); clampPan(); invalidate() }

    private fun renderPage() {
        if (disposed) return
        val source = file ?: return
        if (width <= 0 || height <= 0 || !source.isFile) return
        val token = ++renderToken
        val requestedPage = page
        val targetWidth = width.coerceIn(280, 900)
        runCatching { renderer.execute {
            var rendered: Bitmap? = null
            var count = 0
            val failure = runCatching {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { document ->
                        count = document.pageCount.coerceAtMost(1000)
                        val safePage = requestedPage.coerceIn(0, (count - 1).coerceAtLeast(0))
                        if (count > 0) document.openPage(safePage).use { pdfPage ->
                            // Keep raster memory bounded; zoom scales this one page in the view.
                            val scale = targetWidth.toFloat() / pdfPage.width.toFloat()
                            val bitmapWidth = (pdfPage.width * scale).toInt().coerceAtLeast(1)
                            val bitmapHeight = (pdfPage.height * scale).toInt().coerceAtLeast(1)
                            rendered = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                            rendered!!.eraseColor(Color.WHITE)
                            pdfPage.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }.exceptionOrNull()
            main.post {
                if (token != renderToken || file != source || page != requestedPage) {
                    rendered?.recycle()
                    return@post
                }
                if (failure != null) {
                    rendered?.recycle()
                    pageCount = 0
                    onPageChanged?.invoke(0, 0)
                    onError?.invoke("Aperçu PDF indisponible. Le fichier reste prêt à enregistrer.")
                    invalidate()
                    return@post
                }
                val old = bitmap
                bitmap = rendered
                old?.recycle()
                pageCount = count
                page = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                fitScale = if (bitmap == null || bitmap!!.width == 0 || bitmap!!.height == 0) 1f else
                    minOf(width.toFloat() / bitmap!!.width, height.toFloat() / bitmap!!.height).coerceAtMost(1f)
                clampPan()
                onPageChanged?.invoke(page, pageCount)
                invalidate()
            }
        } }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { renderPage() }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        ++renderToken
        bitmap?.recycle()
        bitmap = null
        renderer.shutdownNow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val drawWidth = image.width * fitScale * zoom
        val drawHeight = image.height * fitScale * zoom
        val left = (width - drawWidth) / 2f + panX
        val top = (height - drawHeight) / 2f + panY
        canvas.drawBitmap(image, null, android.graphics.RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    private fun clampPan() {
        val image = bitmap ?: return
        val maxX = ((image.width * fitScale * zoom - width) / 2f).coerceAtLeast(0f)
        val maxY = ((image.height * fitScale * zoom - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> if (zoom > 1f) {
                panX += event.x - lastX; panY += event.y - lastY
                lastX = event.x; lastY = event.y; clampPan(); invalidate(); return true
            }
        }
        return true
    }
}
