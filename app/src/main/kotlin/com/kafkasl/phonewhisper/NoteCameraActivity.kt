package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.util.Locale
import kotlin.math.roundToInt

/** In-app camera and local document scanner. Captures stay private until the user validates them. */
class NoteCameraActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object {
        @Volatile private var current: NoteCameraActivity? = null
        private const val REQUEST_SCANNER = 42
        internal fun handles(id: String) = current?.let { it.captureId == id && !it.closing } == true
    }

    private val store by lazy { NoteImageStore(this) }
    private val worker = HandlerThread("dictai-camera")
    private lateinit var cameraHandler: Handler
    private lateinit var preview: TextureView
    private lateinit var shutter: Button
    private lateinit var scan: Button
    private lateinit var expand: Button
    private lateinit var validate: Button
    private lateinit var cancel: Button
    private lateinit var status: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var zoom: SeekBar
    private lateinit var zoomMinus: Button
    private lateinit var zoomPlus: Button
    private lateinit var cameraRoot: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var galleryScroll: HorizontalScrollView
    private lateinit var gallery: LinearLayout
    private lateinit var primaryActions: LinearLayout
    private lateinit var secondaryActions: LinearLayout
    private lateinit var landscapeActions: LinearLayout
    private lateinit var scaleDetector: ScaleGestureDetector
    private val thumbnailBitmaps = mutableListOf<Bitmap>()
    private var captureId = ""
    private var batch = false
    @Volatile private var closing = false
    @Volatile private var resumed = false
    @Volatile private var opening = false
    @Volatile private var processing = false
    @Volatile private var scannerInFlight = false
    @Volatile private var scannerProcessing = false
    private var expanded = false
    private var landscapeLayout = false
    private var notice: String? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = android.util.Size(640, 480)
    private var sensorOrientation = 90
    private var front = false
    private var autofocus = CameraMetadata.CONTROL_AF_MODE_OFF
    private var supportsZoomRatio = false
    private var activeArray: NoteCameraGeometry.Rect? = null
    private var minimumZoom = 1f
    private var maximumZoom = 1f
    private var zoomRatio = 1f
    private val deadline = Runnable { cameraFailure("L’appareil photo ne répond pas.") }

    /** Theme.NoteCamera is a platform Activity; apply the persisted explicit mode to its context. */
    override fun attachBaseContext(newBase: Context) {
        val mode = ThemeModeStore.read(newBase)
        if (mode == ThemeMode.SYSTEM) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration().apply {
            uiMode = when (mode) {
                ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
                ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
                ThemeMode.SYSTEM -> Configuration.UI_MODE_NIGHT_UNDEFINED
            }
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureId = savedInstanceState?.getString("captureId") ?: intent.getStringExtra("captureId").orEmpty()
        expanded = savedInstanceState?.getBoolean("expanded") ?: false
        scannerInFlight = savedInstanceState?.getBoolean("scannerInFlight") ?: false
        val capture = store.pending()?.takeIf { it.id == captureId && it.kind in setOf(NoteImageKind.CAMERA, NoteImageKind.SCAN) }
        if (capture == null || capture.complete) { finish(); return }
        batch = capture.batch
        current = this
        worker.start()
        cameraHandler = Handler(worker.looper)
        setFinishOnTouchOutside(false)
        buildCameraViews(capture)
        setContentView(cameraRoot)
        applyCameraTheme()
        applyCameraLayout()
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            cameraFailure("Déverrouillez le téléphone pour prendre une photo.")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    private fun buildCameraViews(capture: PendingNoteCapture) {
        val dp = resources.displayMetrics.density
        cameraRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        preview = TextureView(this).apply { surfaceTextureListener = this@NoteCameraActivity }
        preview.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoomRatio(zoomRatio * detector.scaleFactor)
                return true
            }
        })
        previewFrame = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        status = TextView(this).apply {
            text = "Photo ${capture.number} · cadrer"; textSize = 17f
            runCatching { typeface = resources.getFont(R.font.caveat) }
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = (28 * dp).toInt()
        }
        controlPanel.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        zoomLabel = TextView(this).apply {
            textSize = 13f
            contentDescription = "Niveau de zoom"
            minHeight = (20 * dp).toInt()
        }
        controlPanel.addView(zoomLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        zoomMinus = smallButton("−", "Réduire le zoom") { setZoomRatio(zoomRatio - (maximumZoom - minimumZoom).coerceAtLeast(1f) / 10f) }
        zoom = SeekBar(this).apply {
            max = 100
            contentDescription = "Zoom de la caméra"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setZoomRatio(NoteCameraGeometry.zoomForProgress(progress, minimumZoom, maximumZoom), updateSlider = false)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        zoomPlus = smallButton("+", "Agrandir le zoom") { setZoomRatio(zoomRatio + (maximumZoom - minimumZoom).coerceAtLeast(1f) / 10f) }
        zoomRow.addView(zoomMinus, LinearLayout.LayoutParams((42 * dp).toInt(), (44 * dp).toInt()))
        zoomRow.addView(zoom, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f))
        zoomRow.addView(zoomPlus, LinearLayout.LayoutParams((42 * dp).toInt(), (44 * dp).toInt()))
        controlPanel.addView(zoomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        gallery = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        galleryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(gallery, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            visibility = View.GONE
        }
        controlPanel.addView(galleryScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (70 * dp).toInt()))

        primaryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondaryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        landscapeActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shutter = actionButton("Photo", green = true) { capturePhoto() }.apply {
            contentDescription = "Prendre la photo"
        }
        scan = actionButton("Scanner", green = false) { launchDocumentScanner() }
        expand = actionButton("Agrandir", green = false) {
            expanded = !expanded
            expand.text = if (expanded) "Réduire" else "Agrandir"
            applyCameraLayout()
        }
        validate = actionButton("Valider (0)", green = true) { validateBatch() }
        cancel = actionButton("Annuler", green = false) { cancelCapture() }
        primaryActions.addView(shutter, weightedAction())
        primaryActions.addView(scan, weightedAction())
        primaryActions.addView(expand, weightedAction())
        secondaryActions.addView(validate, weightedAction(1.5f))
        secondaryActions.addView(cancel, weightedAction())
        controlPanel.addView(primaryActions, actionRowParams(dp))
        controlPanel.addView(landscapeActions, actionRowParams(dp))
        controlPanel.addView(secondaryActions, actionRowParams(dp))
        cameraRoot.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            NoteCameraGeometry.previewHeight(resources.displayMetrics.heightPixels, dp, expanded, false)))
        cameraRoot.addView(controlPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setZoomRatio(1f)
        refreshBatchControls()
    }

    private fun weightedAction(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    private fun actionRowParams(dp: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (3 * dp).toInt()
    }
    private fun actionButton(label: String, green: Boolean, click: () -> Unit) = Button(this).apply {
        text = label
        minHeight = (46 * resources.displayMetrics.density).toInt()
        isAllCaps = false
        setPadding((4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt(), 0)
        setOnClickListener { click() }
        tag = if (green) "green" else "neutral"
    }
    private fun smallButton(label: String, description: String, click: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        minHeight = (44 * resources.displayMetrics.density).toInt()
        minWidth = (40 * resources.displayMetrics.density).toInt()
        setOnClickListener { click() }
    }

    private fun applyCameraTheme() {
        if (!::cameraRoot.isInitialized) return
        val colors = ThemeTokens.palette(this)
        val dp = resources.displayMetrics.density
        cameraRoot.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24f * dp
            setColor(colors.surface)
            setStroke(dp.toInt().coerceAtLeast(1), colors.stroke)
        }
        cameraRoot.outlineProvider = ViewOutlineProvider.BACKGROUND
        cameraRoot.clipToOutline = true
        controlPanel.setBackgroundColor(colors.surface)
        status.setTextColor(colors.ink)
        zoomLabel.setTextColor(colors.ink)
        previewFrame.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20f * dp
            setColor(colors.raised)
            setStroke(dp.toInt().coerceAtLeast(1), colors.stroke)
        }
        previewFrame.outlineProvider = ViewOutlineProvider.BACKGROUND
        previewFrame.clipToOutline = true
        listOf(shutter, scan, expand, validate, cancel, zoomMinus, zoomPlus).forEach { button ->
            val isGreen = button.tag == "green"
            button.backgroundTintList = null
            button.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f * dp
                setColor(if (isGreen) colors.green else colors.raised)
                setStroke(dp.toInt().coerceAtLeast(1), colors.stroke)
            }
            button.setTextColor(if (isGreen) colors.onGreen else colors.ink)
        }
        zoom.progressTintList = android.content.res.ColorStateList.valueOf(colors.green)
        zoom.thumbTintList = android.content.res.ColorStateList.valueOf(colors.green)
        window.statusBarColor = colors.bg
        window.navigationBarColor = colors.bg
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val night = ThemeModeStore.read(this) == ThemeMode.DARK ||
            (ThemeModeStore.read(this) == ThemeMode.SYSTEM &&
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        window.decorView.systemUiVisibility = if (night) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    private fun applyCameraLayout() = applyCameraLayout(resources.configuration)

    private fun applyCameraLayout(configuration: Configuration) {
        if (!::previewFrame.isInitialized) return
        val metrics = resources.displayMetrics
        val dp = metrics.density
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = NoteCameraGeometry.dialogWidth(metrics.widthPixels, dp, expanded, landscape)
        landscapeLayout = landscape
        galleryScroll.layoutParams = galleryScroll.layoutParams.apply {
            height = ((if (landscape) 50 else 70) * dp).toInt()
        }
        cameraRoot.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val rootPadding = ((if (landscape) 8 else 12) * dp).toInt()
        cameraRoot.setPadding(rootPadding, rootPadding, rootPadding, rootPadding)
        if (landscape) {
            val contentHeight = (metrics.heightPixels - (32 * dp).toInt()).coerceAtLeast((200 * dp).toInt())
            val panelWidth = (232 * dp).toInt().coerceAtMost((width * .42f).toInt()).coerceAtLeast((176 * dp).toInt())
            previewFrame.layoutParams = LinearLayout.LayoutParams(0, contentHeight - (16 * dp).toInt(), 1f).apply {
                rightMargin = (8 * dp).toInt()
            }
            controlPanel.layoutParams = LinearLayout.LayoutParams(panelWidth, contentHeight - (16 * dp).toInt())
            primaryActions.removeAllViews()
            primaryActions.addView(shutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            primaryActions.visibility = View.VISIBLE
            landscapeActions.visibility = View.VISIBLE
            landscapeActions.removeAllViews()
            landscapeActions.addView(scan, weightedAction())
            landscapeActions.addView(expand, weightedAction())
            window.setLayout(width, contentHeight)
        } else {
            primaryActions.visibility = View.VISIBLE
            landscapeActions.visibility = View.GONE
            landscapeActions.removeAllViews()
            primaryActions.removeAllViews()
            primaryActions.addView(shutter, weightedAction())
            primaryActions.addView(scan, weightedAction())
            primaryActions.addView(expand, weightedAction())
            previewFrame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                NoteCameraGeometry.previewHeight(metrics.heightPixels, dp, expanded, landscape)).apply {
                topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt()
            }
            controlPanel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val capture = store.pending()?.takeIf { it.id == captureId }
        renderThumbnails(capture?.allImages.orEmpty(), capture?.batch == true)
        preview.post { transformPreview() }
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        applyCameraTheme()
        applyCameraLayout(config)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("captureId", captureId)
        outState.putBoolean("expanded", expanded)
        outState.putBoolean("scannerInFlight", scannerInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (batch) store.retryBatch(captureId)
        if (::preview.isInitialized && preview.isAvailable && !scannerInFlight && !scannerProcessing &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked) openCamera()
        refreshBatchControls()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed && ::preview.isInitialized && preview.isAvailable &&
            !scannerInFlight && !scannerProcessing && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            else openCamera()
        }
    }

    override fun onPause() {
        resumed = false
        if (::cameraHandler.isInitialized) cameraHandler.post { closeCamera() }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // The scanner temporarily owns the foreground Activity. Keep this Activity as the owner
        // of its pending session until the scanner result arrives.
        if (scannerInFlight) return
        if (!batch && !isChangingConfigurations && !isFinishing) finish()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (preview.isAvailable) openCamera()
            } else cameraFailure("Accès caméra refusé. Vous pouvez encore utiliser le scanner.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCANNER) return
        scannerInFlight = false
        if (resultCode != RESULT_OK) {
            notice = "Scanner fermé. Les photos déjà prises sont conservées."
            refreshBatchControls()
            resumeViewfinder()
            return
        }
        val pages = runCatching { GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages.orEmpty() }.getOrDefault(emptyList())
        if (pages.isEmpty()) {
            notice = "Aucune page reçue. Les photos déjà prises sont conservées."
            refreshBatchControls()
            resumeViewfinder()
            return
        }
        val capture = store.pending()?.takeIf { it.id == captureId && !it.complete }
        val capacity = capture?.let { if (it.batch) it.maxImages - it.allImages.size else 1 - it.allImages.size } ?: 0
        val pageUris = pages.take(capacity.coerceAtLeast(0)).map { it.imageUri }
        if (pageUris.isEmpty()) {
            notice = "La série a atteint sa limite. Validez les images déjà prises."
            refreshBatchControls()
            resumeViewfinder()
            return
        }
        scannerProcessing = true
        notice = "Import des pages scannées…"
        refreshBatchControls()
        cameraHandler.post {
            var imported = 0
            pageUris.forEach { uri ->
                runCatching { store.importUri(captureId, uri, NoteImageKind.SCAN) }
                    .onSuccess { imported++ }
            }
            runOnUiThread {
                scannerProcessing = false
                notice = if (imported == pageUris.size) "$imported page(s) scannée(s). Vous pouvez continuer ou valider."
                else "$imported page(s) importée(s). Une page n’a pas pu être lue; la série est conservée."
                refreshBatchControls()
                if (!batch && imported > 0) finish() else resumeViewfinder()
            }
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = openCamera()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transformPreview()
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        cameraHandler.post { closeCamera() }
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun openCamera() {
        if (!resumed || closing || opening || scannerInFlight || scannerProcessing || !::cameraHandler.isInitialized ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        opening = true
        window.decorView.removeCallbacks(deadline)
        window.decorView.postDelayed(deadline, 8_000)
        cameraHandler.post {
            try {
                if (closing || !resumed) { opening = false; return@post }
                val manager = getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.firstOrNull() ?: error("No camera")
                val details = manager.getCameraCharacteristics(id)
                val sizes = details.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No sizes")
                val jpeg = NoteCameraGeometry.chooseSize(sizes.getOutputSizes(ImageFormat.JPEG).map {
                    NoteCameraGeometry.Size(it.width, it.height)
                }, 2048)
                val pv = NoteCameraGeometry.chooseSize(sizes.getOutputSizes(SurfaceTexture::class.java).map {
                    NoteCameraGeometry.Size(it.width, it.height)
                }, 1280, jpeg.width.toDouble() / jpeg.height)
                previewSize = android.util.Size(pv.width, pv.height)
                sensorOrientation = details.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                front = details.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val modes = details.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                autofocus = if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes)
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CameraMetadata.CONTROL_AF_MODE_OFF
                val range = details.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                supportsZoomRatio = range != null
                minimumZoom = if (range != null) range.lower.coerceAtLeast(0.1f) else 1f
                activeArray = details.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                    NoteCameraGeometry.Rect(it.left, it.top, it.right, it.bottom)
                }
                val maximumDigitalZoom = if (activeArray != null)
                    details.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f else 1f
                maximumZoom = maxOf(minimumZoom, range?.upper ?: maximumDigitalZoom)
                val lower = minimumZoom
                zoomRatio = zoomRatio.takeIf { it.isFinite() }?.coerceIn(lower, maximumZoom.coerceAtLeast(lower)) ?: lower
                runOnUiThread { if (!closing) updateZoomControls() }
                reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ source ->
                        val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                        try {
                            if (!closing && processing) {
                                val buffer = image.planes[0].buffer
                                require(buffer.remaining() in 1..40 * 1024 * 1024)
                                val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                                store.cameraFile(captureId).writeBytes(bytes)
                                store.importCamera(captureId)
                                runOnUiThread {
                                    window.decorView.removeCallbacks(deadline)
                                    processing = false
                                    notice = if (batch) "Photo ajoutée à la série." else null
                                    if (batch) refreshBatchControls() else finish()
                                }
                            }
                        } catch (_: Exception) { cameraFailure("Photo non enregistrée. Les images déjà prises sont conservées.") }
                        finally { image.close() }
                    }, cameraHandler)
                }
                @Suppress("MissingPermission")
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (closing || !resumed || scannerInFlight || scannerProcessing) {
                            device.close(); opening = false; return
                        }
                        camera = device
                        try {
                            val texture = preview.surfaceTexture ?: error("No preview")
                            texture.setDefaultBufferSize(pv.width, pv.height)
                            val surface = Surface(texture); previewSurface = surface
                            val photoSurface = reader?.surface ?: error("No image reader")
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(listOf(surface, photoSurface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    if (closing || !resumed || scannerInFlight || scannerProcessing || camera !== device) {
                                        configured.close(); return
                                    }
                                    session = configured
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, autofocus)
                                            applyZoom(this)
                                        }
                                        configured.setRepeatingRequest(request.build(), null, cameraHandler)
                                        runOnUiThread {
                                            if (!closing) {
                                                window.decorView.removeCallbacks(deadline)
                                                refreshBatchControls()
                                                transformPreview()
                                            }
                                        }
                                    } catch (_: Exception) { cameraFailure("Aperçu photo indisponible.") }
                                }
                                override fun onConfigureFailed(configured: CameraCaptureSession) {
                                    configured.close()
                                    cameraFailure("Aperçu photo indisponible.")
                                }
                            }, cameraHandler)
                        } catch (_: Exception) { cameraFailure("Aperçu photo indisponible.") }
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); cameraFailure("Caméra interrompue.") }
                    override fun onError(device: CameraDevice, error: Int) { device.close(); cameraFailure("Caméra indisponible.") }
                }, cameraHandler)
            } catch (_: Exception) { cameraFailure("Impossible d’ouvrir la caméra. Vous pouvez essayer le scanner.") }
        }
    }

    private fun cameraFailure(message: String) {
        opening = false
        processing = false
        if (scannerInFlight) return
        runOnUiThread { window.decorView.removeCallbacks(deadline) }
        if (!batch) {
            store.fail(captureId, message)
            runOnUiThread { notice = message; finish() }
        } else runOnUiThread {
            notice = message
            refreshBatchControls()
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (supportsZoomRatio) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        else activeArray?.let { active ->
            val crop = NoteCameraGeometry.cropForZoom(active, zoomRatio)
            builder.set(CaptureRequest.SCALER_CROP_REGION,
                android.graphics.Rect(crop.left, crop.top, crop.right, crop.bottom))
        }
    }

    private fun setZoomRatio(value: Float, updateSlider: Boolean = true) {
        val lower = minimumZoom.takeIf { supportsZoomRatio } ?: 1f
        val upper = maximumZoom.coerceAtLeast(lower)
        zoomRatio = if (value.isFinite()) value.coerceIn(lower, upper) else lower
        if (::zoomLabel.isInitialized) updateZoomControls(updateSlider)
        val activeSession = session
        val device = camera
        val surface = previewSurface
        if (activeSession != null && device != null && surface != null && ::cameraHandler.isInitialized) {
            cameraHandler.post {
                runCatching {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, autofocus)
                        applyZoom(this)
                    }
                    activeSession.setRepeatingRequest(request.build(), null, cameraHandler)
                }
            }
        }
    }

    /** Only called from the main thread; camera characteristics are read on cameraHandler. */
    private fun updateZoomControls(updateSlider: Boolean = true) {
        if (!::zoomLabel.isInitialized || !::zoom.isInitialized) return
        val lower = minimumZoom.takeIf { supportsZoomRatio } ?: 1f
        val upper = maximumZoom.coerceAtLeast(lower)
        zoomLabel.text = "Zoom · ${String.format(Locale.getDefault(), "%.1f", zoomRatio)}×"
        zoomMinus.isEnabled = zoomRatio > lower && !processing && !scannerProcessing
        zoomPlus.isEnabled = zoomRatio < upper && !processing && !scannerProcessing
        zoom.isEnabled = upper > lower && !processing && !scannerProcessing
        if (updateSlider) {
            val progress = if (upper == lower) 0 else ((zoomRatio - lower) / (upper - lower) * 100).roundToInt()
            zoom.progress = progress.coerceIn(0, 100)
        }
    }

    @Suppress("DEPRECATION")
    private fun rotationDegrees() = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun transformPreview() {
        if (!::preview.isInitialized || preview.width == 0 || preview.height == 0) return
        val scale = NoteCameraGeometry.previewScale(previewSize.width, previewSize.height,
            sensorOrientation, rotationDegrees(), preview.width, preview.height)
        val matrix = Matrix()
        matrix.setScale(scale.first, scale.second, preview.width / 2f, preview.height / 2f)
        matrix.postRotate(-rotationDegrees().toFloat(), preview.width / 2f, preview.height / 2f)
        if (front) matrix.postScale(-1f, 1f, preview.width / 2f, preview.height / 2f)
        preview.setTransform(matrix)
    }

    private fun capturePhoto() {
        if (processing || scannerInFlight || scannerProcessing || closing || !shutter.isEnabled) return
        processing = true
        notice = "Enregistrement de la photo…"
        refreshBatchControls()
        window.decorView.removeCallbacks(deadline)
        window.decorView.postDelayed(deadline, 8_000)
        val rotation = NoteCameraGeometry.jpegRotation(sensorOrientation, rotationDegrees(), front)
        cameraHandler.post {
            try {
                val device = camera ?: error("Camera closed")
                val capture = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader?.surface ?: error("No reader"))
                    set(CaptureRequest.JPEG_ORIENTATION, rotation)
                    set(CaptureRequest.CONTROL_AF_MODE, autofocus)
                    applyZoom(this)
                }
                session?.capture(capture.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        cameraFailure("Photo interrompue. Réessayez; les images déjà prises sont conservées.")
                    }
                }, cameraHandler) ?: error("No session")
            } catch (_: Exception) { cameraFailure("Photo interrompue. Réessayez; la série est conservée.") }
        }
    }

    private fun launchDocumentScanner() {
        if (scannerInFlight || scannerProcessing || processing || closing) return
        val capture = store.pending()?.takeIf { it.id == captureId && !it.complete } ?: return
        val capacity = if (capture.batch) capture.maxImages - capture.allImages.size else 1 - capture.allImages.size
        if (capacity <= 0) {
            notice = "La série a atteint sa limite. Validez les images déjà prises."
            refreshBatchControls()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(capacity)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        scannerInFlight = true
        notice = "Ouverture du scanner…"
        window.decorView.removeCallbacks(deadline)
        refreshBatchControls()
        if (::cameraHandler.isInitialized) {
            cameraHandler.post {
                closeCamera()
                runOnUiThread { if (scannerInFlight && !closing) startScannerTask(options) }
            }
        } else startScannerTask(options)
    }

    private fun startScannerTask(options: GmsDocumentScannerOptions) {
        runCatching {
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    runOnUiThread {
                        if (closing || isFinishing) { scannerInFlight = false; return@runOnUiThread }
                        try {
                            startIntentSenderForResult(sender, REQUEST_SCANNER, null, 0, 0, 0)
                        } catch (_: IntentSender.SendIntentException) {
                            scannerUnavailable("Scanner indisponible. Les photos déjà prises sont conservées.")
                        }
                    }
                }
                .addOnFailureListener {
                    scannerUnavailable("Scanner indisponible. Vérifiez les services Google; vos photos restent prêtes à valider.")
                }
        }.onFailure {
            scannerUnavailable("Scanner indisponible. Vérifiez les services Google; vos photos restent prêtes à valider.")
        }
    }

    private fun scannerUnavailable(message: String) {
        runOnUiThread {
            scannerInFlight = false
            notice = message
            refreshBatchControls()
            resumeViewfinder()
        }
    }

    private fun resumeViewfinder() {
        if (resumed && preview.isAvailable && !scannerInFlight && !scannerProcessing) openCamera()
    }

    private fun validateBatch() {
        if (!batch || processing || scannerInFlight || scannerProcessing) return
        runCatching { store.acceptBatch(captureId) }
            .onSuccess { finish() }
            .onFailure { notice = it.message ?: "Ajoutez une image avant de valider."; refreshBatchControls() }
    }

    private fun cancelCapture() {
        if (batch) runCatching { store.cancelBatch(captureId) }
        finish()
    }

    override fun onBackPressed() = cancelCapture()

    private fun refreshBatchControls() {
        if (!::status.isInitialized) return
        val capture = store.pending()?.takeIf { it.id == captureId }
        val images = capture?.allImages.orEmpty()
        renderThumbnails(images, capture?.batch == true)
        val capacity = capture?.let { if (it.batch) it.maxImages else 1 } ?: 0
        val canCapture = images.size < capacity && !processing && !scannerInFlight && !scannerProcessing
        shutter.isEnabled = canCapture && session != null
        scan.isEnabled = canCapture && !closing
        validate.visibility = if (capture?.batch == true) View.VISIBLE else View.GONE
        validate.text = "Valider (${images.size})"
        validate.isEnabled = capture?.batch == true && images.isNotEmpty() && !processing && !scannerInFlight && !scannerProcessing
        cancel.isEnabled = !processing && !scannerInFlight && !scannerProcessing
        val canAdjustCamera = session != null && !processing && !scannerInFlight && !scannerProcessing
        zoom.isEnabled = canAdjustCamera
        zoomMinus.isEnabled = canAdjustCamera
        zoomPlus.isEnabled = canAdjustCamera
        listOf(shutter, scan, validate, cancel, zoom, zoomMinus, zoomPlus).forEach {
            it.alpha = if (it.isEnabled) 1f else .45f
        }
        val description = notice ?: when {
            capture?.batch == true -> "${images.size} / ${capture.maxImages} image(s) · prenez d’autres photos ou scannez"
            else -> "Photo ${capture?.number ?: 1} · cadrer puis photographier"
        }
        status.text = description
    }

    private fun renderThumbnails(images: List<NoteImage>, removable: Boolean) {
        if (!::gallery.isInitialized) return
        thumbnailBitmaps.forEach { it.recycle() }
        thumbnailBitmaps.clear()
        gallery.removeAllViews()
        val dp = resources.displayMetrics.density
        images.forEach { image ->
            val cell = FrameLayout(this)
            val previewBitmap = runCatching { NoteImageStore.decode(store.thumbnail(image.id), 160) }.getOrNull()
            val imageView = ImageView(this).apply {
                contentDescription = "${image.kind.label} ${image.number}"
                scaleType = ImageView.ScaleType.CENTER_CROP
                previewBitmap?.let { setImageBitmap(it); thumbnailBitmaps += it }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 9f * dp
                    setColor(ThemeTokens.palette(this@NoteCameraActivity).raised)
                }
            }
            val thumbnailHeight = if (landscapeLayout) 40 else 58
            cell.addView(imageView, FrameLayout.LayoutParams((62 * dp).toInt(), (thumbnailHeight * dp).toInt()))
            if (removable) {
                val remove = Button(this).apply {
                    text = "×"
                    contentDescription = "Retirer l’image ${image.number}"
                    minWidth = (30 * dp).toInt(); minHeight = (30 * dp).toInt()
                    setPadding(0, 0, 0, 0)
                    isEnabled = !processing && !scannerInFlight && !scannerProcessing
                    setOnClickListener {
                        if (processing || scannerInFlight || scannerProcessing) return@setOnClickListener
                        store.removeBatchImage(captureId, image.id)
                        notice = "Image ${image.number} retirée de la série."
                        refreshBatchControls()
                    }
                    setTextColor(android.graphics.Color.WHITE)
                    background = android.graphics.drawable.ColorDrawable(0x99000000.toInt())
                }
                cell.addView(remove, FrameLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt(), android.view.Gravity.TOP or android.view.Gravity.END))
            }
            gallery.addView(cell, LinearLayout.LayoutParams((66 * dp).toInt(), ((thumbnailHeight + 4) * dp).toInt()).apply {
                marginEnd = (5 * dp).toInt()
            })
        }
        galleryScroll.visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fail(message: String) = cameraFailure(message)

    override fun finish() {
        if (!closing) {
            closing = true
            if (NoteImage.validId(captureId)) store.fail(captureId, "Capture interrompue. Les images déjà validées sont conservées.")
            runCatching { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_CAPTURE_RESULT)) }
        }
        super.finish()
    }

    private fun closeCamera() {
        session?.close(); session = null
        camera?.close(); camera = null
        previewSurface?.release(); previewSurface = null
        reader?.close(); reader = null
        opening = false
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (::preview.isInitialized) window.decorView.removeCallbacks(deadline)
        thumbnailBitmaps.forEach { it.recycle() }
        thumbnailBitmaps.clear()
        closing = true
        if (::cameraHandler.isInitialized) cameraHandler.post { closeCamera(); worker.quitSafely() }
        super.onDestroy()
    }
}
