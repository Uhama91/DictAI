package com.kafkasl.phonewhisper

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import androidx.core.content.FileProvider
import kotlin.concurrent.thread

/** Result owner survives rotation/process recreation while the system camera is in front. */
class NoteCameraActivity : Activity() {
    private val store by lazy { NoteImageStore(this) }
    private var launched = false
    private var processing = false
    private var captureId = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Photo pour la note…"; gravity = android.view.Gravity.CENTER })
        captureId = savedInstanceState?.getString("captureId") ?: intent.getStringExtra("captureId").orEmpty()
        val capture = store.pending()?.takeIf { it.id == captureId && it.kind == NoteImageKind.CAMERA }
        if (capture == null) { finish(); return }
        if (capture.complete) { notifyOverlay(); return }
        launched = savedInstanceState?.getBoolean("launched") ?: false
        if (!launched) {
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                store.fail(captureId, "Déverrouillez le téléphone pour joindre une photo à la note.")
                notifyOverlay()
                return
            }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.note_files", store.cameraFile(captureId))
                val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                camera.clipData = ClipData.newRawUri("Photo pour DictAI", uri)
                launched = true
                @Suppress("DEPRECATION")
                startActivityForResult(camera, 1)
            } catch (_: Exception) {
                store.fail(captureId, "Impossible d’ouvrir l’appareil photo.")
                notifyOverlay()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("captureId", captureId)
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform result callback retained to restore the pending system camera request")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || processing) return
        processing = true
        thread(name = "dictai-photo-import") {
            try {
                if (resultCode == RESULT_OK) store.importCamera(captureId)
                else store.fail(captureId, "Photo annulée.")
            } catch (_: Throwable) { store.fail(captureId, "Photo non enregistrée : espace ou format indisponible.") }
            runOnUiThread { notifyOverlay() }
        }
    }
    private fun notifyOverlay() {
        runCatching { startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_CAPTURE_RESULT)) }
        finish()
    }
}
