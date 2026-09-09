package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small in-app viewfinder. No external camera app, audio recording, model or full-size gallery. */
class NoteCameraActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object {
        @Volatile private var current: NoteCameraActivity? = null
        internal fun handles(id: String) = current?.let { it.captureId == id && !it.closing } == true
    }
    private val store by lazy { NoteImageStore(this) }
    private val worker = HandlerThread("dictai-camera")
    private lateinit var cameraHandler: Handler
    private lateinit var preview: TextureView
    private lateinit var shutter: Button
    private lateinit var status: TextView
    private var captureId = ""
    @Volatile private var closing = false
    @Volatile private var resumed = false
    @Volatile private var opening = false
    @Volatile private var processing = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = android.util.Size(640, 480)
    private var sensorOrientation = 90
    private var front = false
    private var autofocus = CameraMetadata.CONTROL_AF_MODE_OFF
    private val deadline = Runnable { fail("L’appareil photo ne répond pas. La note est conservée.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureId = savedInstanceState?.getString("captureId") ?: intent.getStringExtra("captureId").orEmpty()
        val capture = store.pending()?.takeIf { it.id == captureId && it.kind == NoteImageKind.CAMERA }
        if (capture == null || capture.complete) { finish(); return }
        current = this
        worker.start(); cameraHandler = Handler(worker.looper)
        setFinishOnTouchOutside(false)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt()) }
        status = TextView(this).apply { text = "Photo ${capture.number} · cadrer puis photographier"; textSize = 16f }
        root.addView(status)
        preview = TextureView(this).apply { surfaceTextureListener = this@NoteCameraActivity }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight()))
        shutter = Button(this).apply { text = "Prendre la photo"; isEnabled = false; setOnClickListener { capturePhoto() } }
        root.addView(shutter)
        root.addView(Button(this).apply { text = "Annuler"; setOnClickListener { finish() } })
        setContentView(root)
        window.setLayout(minOf((340*dp).toInt(), resources.displayMetrics.widthPixels - (24*dp).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { fail("Déverrouillez le téléphone pour prendre une photo."); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    private fun previewHeight(): Int {
        val dp = resources.displayMetrics.density
        return minOf((250*dp).toInt(), (resources.displayMetrics.heightPixels - 220*dp).toInt().coerceAtLeast((90*dp).toInt()))
    }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        if (!::preview.isInitialized) return
        val dp = resources.displayMetrics.density
        preview.layoutParams = preview.layoutParams.apply { height = previewHeight() }
        window.setLayout(minOf((340*dp).toInt(), resources.displayMetrics.widthPixels - (24*dp).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        preview.post { transformPreview() }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("captureId", captureId); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); resumed = true; if (::preview.isInitialized && preview.isAvailable) openCamera() }
    override fun onPause() {
        resumed = false
        if (::cameraHandler.isInitialized) cameraHandler.post { closeCamera() }
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        // No hidden camera session when the user changes apps or locks the phone.
        if (!isChangingConfigurations && !isFinishing) finish()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (preview.isAvailable) openCamera() }
            else fail("Photo annulée : accès à la caméra non autorisé. La note est conservée.")
        }
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = openCamera()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transformPreview()
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { cameraHandler.post { closeCamera() }; return true }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    private fun openCamera() {
        if (!resumed || closing || opening || !::cameraHandler.isInitialized ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        opening = true
        window.decorView.removeCallbacks(deadline); window.decorView.postDelayed(deadline, 8_000)
        cameraHandler.post {
            try {
                if (closing || !resumed) { opening = false; return@post }
                val manager = getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                    ?: manager.cameraIdList.firstOrNull() ?: error("No camera")
                val details = manager.getCameraCharacteristics(id)
                val sizes = details.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No sizes")
                val jpeg = NoteCameraGeometry.chooseSize(sizes.getOutputSizes(ImageFormat.JPEG).map { NoteCameraGeometry.Size(it.width, it.height) }, 2048)
                val pv = NoteCameraGeometry.chooseSize(sizes.getOutputSizes(SurfaceTexture::class.java).map { NoteCameraGeometry.Size(it.width, it.height) }, 1280, jpeg.width.toDouble()/jpeg.height)
                previewSize = android.util.Size(pv.width, pv.height)
                sensorOrientation = details.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                front = details.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val modes = details.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                autofocus = if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CameraMetadata.CONTROL_AF_MODE_OFF
                reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ source ->
                        val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                        try {
                            if (!closing && processing) {
                                val buffer = image.planes[0].buffer
                                require(buffer.remaining() in 1..40*1024*1024)
                                val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                                store.cameraFile(captureId).writeBytes(bytes)
                                store.importCamera(captureId)
                                runOnUiThread { finish() }
                            }
                        } catch (_: Exception) { fail("Photo non enregistrée. La note est conservée.") }
                        finally { image.close() }
                    }, cameraHandler)
                }
                @Suppress("MissingPermission")
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (closing || !resumed) { device.close(); opening = false; return }
                        camera = device
                        try {
                            val texture = preview.surfaceTexture ?: error("No preview")
                            texture.setDefaultBufferSize(pv.width, pv.height)
                            val surface = Surface(texture); previewSurface = surface
                            val photoSurface = reader?.surface ?: error("No image reader")
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(listOf(surface, photoSurface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    if (closing || !resumed || camera !== device) { configured.close(); return }
                                    session = configured
                                    try {
                                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface); set(CaptureRequest.CONTROL_AF_MODE, autofocus) }
                                        configured.setRepeatingRequest(request.build(), null, cameraHandler)
                                        runOnUiThread { if (!closing) { window.decorView.removeCallbacks(deadline); shutter.isEnabled = true; transformPreview() } }
                                    } catch (_: Exception) { fail("Aperçu photo indisponible.") }
                                }
                                override fun onConfigureFailed(configured: CameraCaptureSession) { configured.close(); fail("Aperçu photo indisponible.") }
                            }, cameraHandler)
                        } catch (_: Exception) { fail("Aperçu photo indisponible.") }
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); fail("Caméra interrompue. La note est conservée.") }
                    override fun onError(device: CameraDevice, error: Int) { device.close(); fail("Caméra indisponible. La note est conservée.") }
                }, cameraHandler)
            } catch (_: Exception) { fail("Impossible d’ouvrir la caméra. La note est conservée.") }
        }
    }

    @Suppress("DEPRECATION")
    private fun rotationDegrees() = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    private fun transformPreview() {
        if (!::preview.isInitialized || preview.width == 0 || preview.height == 0) return
        // TextureView already applies the sensor orientation. Undo its default stretching,
        // then account for display rotation while keeping the entire frame visible.
        val scale = NoteCameraGeometry.previewScale(previewSize.width, previewSize.height,
            sensorOrientation, rotationDegrees(), preview.width, preview.height)
        val matrix = Matrix()
        matrix.setScale(scale.first, scale.second, preview.width / 2f, preview.height / 2f)
        matrix.postRotate(-rotationDegrees().toFloat(), preview.width / 2f, preview.height / 2f)
        if (front) matrix.postScale(-1f, 1f, preview.width / 2f, preview.height / 2f)
        preview.setTransform(matrix)
    }
    private fun capturePhoto() {
        if (processing || closing) return
        processing = true; shutter.isEnabled = false; status.text = "Enregistrement de la photo…"
        window.decorView.removeCallbacks(deadline); window.decorView.postDelayed(deadline, 8_000)
        val rotation = NoteCameraGeometry.jpegRotation(sensorOrientation, rotationDegrees(), front)
        cameraHandler.post {
            try {
                val device = camera ?: error("Camera closed")
                val capture = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader?.surface ?: error("No reader")); set(CaptureRequest.JPEG_ORIENTATION, rotation)
                    set(CaptureRequest.CONTROL_AF_MODE, autofocus)
                }
                session?.capture(capture.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { fail("Photo interrompue. Réessayez.") }
                }, cameraHandler) ?: error("No session")
            } catch (_: Exception) { fail("Photo interrompue. La note est conservée.") }
        }
    }
    private fun fail(message: String) { store.fail(captureId, message); runOnUiThread { finish() } }
    override fun finish() {
        if (!closing) {
            closing = true
            if (NoteImage.validId(captureId)) store.fail(captureId, "Photo annulée. La note est conservée.")
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
        closing = true
        if (::cameraHandler.isInitialized) cameraHandler.post { closeCamera(); worker.quitSafely() }
        super.onDestroy()
    }
}
