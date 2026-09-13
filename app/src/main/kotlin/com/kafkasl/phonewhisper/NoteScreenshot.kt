package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object NoteScreenshot {
    /** Overlay windows have already been hidden and committed before this call. */
    fun capture(service: AccessibilityService, store: NoteImageStore, id: String,
        restoreWindows: () -> Unit, finished: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val timeout = Runnable {
            if (delivered.compareAndSet(false, true)) {
                restoreWindows()
                store.fail(id, "Capture d’écran indisponible. Réessayez.")
                finished()
            }
        }
        main.postDelayed(timeout, 3000)
        fun fail(message: String) {
            if (!delivered.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            restoreWindows()
            store.fail(id, message)
            finished()
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    if (!delivered.compareAndSet(false, true)) { result.hardwareBuffer.close(); return }
                    main.removeCallbacks(timeout)
                    restoreWindows()
                    thread(name = "dictai-screenshot-store") {
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?: error("Image indisponible")
                            try {
                                val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: error("Image indisponible")
                                try { store.store(id, bitmap) } finally { bitmap.recycle() }
                            } finally { wrapped.recycle() }
                        } catch (_: Throwable) { store.fail(id, "Capture non enregistrée : espace ou image indisponible.") }
                        finally { result.hardwareBuffer.close(); main.post { finished() } }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    fail(when (errorCode) {
                        2 -> "Réactivez le service d’accessibilité DictAI pour autoriser les captures demandées."
                        3 -> "Captures trop rapprochées. Réessayez."
                        6 -> "Cette application protège son écran contre les captures."
                        else -> "Capture d’écran indisponible ou contenu protégé."
                    })
                }
            })
        } catch (_: Exception) { fail("Capture indisponible : vérifiez le service d’accessibilité DictAI.") }
    }
}
