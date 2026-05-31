package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_ARM_MIC = "com.uhama.whisperpin.ARM_MIC"
        @Volatile var micArmed = false
            private set
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING, MIC_UNARMED, LLM_PROCESSING }

    private val prefs by lazy { PersistencePrefs(this) }
    @Volatile private var state = State.MIC_UNARMED
    private var recordThread: Thread? = null
    private var button: ImageView? = null
    private var container: FrameLayout? = null
    private var pill: FrameLayout? = null
    private var wave: CursiveWaveView? = null
    private var params: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcm: java.io.ByteArrayOutputStream? = null
    private var local: LocalTranscriber? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        micArmed = false
        createChannel()
        if (!startForegroundSpecialUse()) return
        showButton()
        thread { local = TranscriptionEngine.loadLocal(this) }
        if (PostProcessPrompts.isEnabled(this) && LlmPostProcessor.isDownloaded(this))
            thread { LlmPostProcessor.ensureLoaded(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM_MIC) promoteMic()
        return START_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "WhisperPin", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperPin actif")
            .setContentText(
                when (state) {
                    State.MIC_UNARMED -> "Ouvre l'app pour activer le micro"
                    State.RECORDING -> "Enregistrement..."
                    State.TRANSCRIBING -> "Transcription..."
                    else -> "Appuie sur le bouton pour dicter"
                }
            )
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()

    private fun startForegroundSpecialUse(): Boolean {
        return try {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground specialUse echec: ${e.javaClass.simpleName} -> stopSelf")
            prefs.lastError = e.javaClass.simpleName
            stopSelf()
            false
        }
    }

    private fun promoteMic() {
        if (micArmed) return
        try {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            micArmed = true
            setState(State.IDLE)
            Log.i(TAG, "Mic arme")
        } catch (e: Exception) {
            Log.e(TAG, "promoteMic echec: ${e.javaClass.simpleName}")
            micArmed = false
            setState(State.MIC_UNARMED)
        }
    }

    private fun updateNotif() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun onTap() {
        when (state) {
            State.MIC_UNARMED -> { toast("Ouvre WhisperPin pour activer le micro"); openApp() }
            State.IDLE -> startRec()
            State.RECORDING -> stopRec()
            State.TRANSCRIBING, State.LLM_PROCESSING -> {}
        }
    }

    private fun startRec() {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: SecurityException) { toast("Mic refuse"); return }
        pcm = java.io.ByteArrayOutputStream()
        audioRecord!!.startRecording()
        setState(State.RECORDING)
        vibrate(20)
        val ar = audioRecord!!
        recordThread = thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) { pcm?.write(buf, 0, n); wave?.setLevel(rmsLevel(buf, n)) }
            }
        }
    }

    private fun rmsLevel(buf: ByteArray, n: Int): Float {
        var sum = 0.0; var count = 0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val v = s.toShort().toInt(); sum += (v * v).toDouble(); count++; i += 2
        }
        if (count == 0) return 0f
        val rms = Math.sqrt(sum / count) / 32768.0
        // boost comme DictAI pour réagir à la parole normale
        return (Math.sqrt(rms) * 4.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun stopRec() {
        setState(State.TRANSCRIBING)
        vibrate(20)
        recordThread?.join(800)
        recordThread = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        val data = pcm?.toByteArray() ?: ByteArray(0); pcm = null
        if (data.isEmpty()) { setState(State.IDLE); return }
        thread {
            val t0 = System.currentTimeMillis()
            val r = TranscriptionEngine.transcribe(this, data, local)
            val transcribeMs = System.currentTimeMillis() - t0
            var finalText = r.text?.let { Vocabulary.applyCorrections(this, it) }
            var llmMs = 0L
            if (!finalText.isNullOrBlank() &&
                PostProcessPrompts.isEnabled(this) && LlmPostProcessor.ready) {
                setState(State.LLM_PROCESSING)
                val t1 = System.currentTimeMillis()
                val pp = LlmPostProcessor.rewrite(this, PostProcessPrompts.fill(this, finalText))
                llmMs = System.currentTimeMillis() - t1
                if (!pp.isNullOrBlank()) finalText = pp
            }
            val outText = finalText
            val timing = if (llmMs > 0) "transcr ${transcribeMs}ms · LLM ${llmMs}ms" else "transcr ${transcribeMs}ms"
            Log.i(TAG, "Pipeline: $timing")
            main.post {
                if (!outText.isNullOrBlank()) {
                    copyToClipboard(outText)
                    val injected = WhisperAccessibilityService.controller?.inject(outText) ?: false
                    toast((if (injected) "Inséré" else "Copié") + " · $timing")
                } else toast("Erreur: ${r.error ?: "vide"}")
                setState(State.IDLE)
            }
        }
    }

    private fun setState(s: State) {
        state = s
        main.post {
            val color = when (s) {
                State.IDLE -> 0xDD1C1C1E.toInt()
                State.RECORDING -> 0xDDEF4444.toInt()
                State.TRANSCRIBING -> 0xDD6B6B6B.toInt()
                State.LLM_PROCESSING -> 0xDD3B6B8A.toInt()
                State.MIC_UNARMED -> 0xDD8A6D3B.toInt()
            }
            (button?.background as? GradientDrawable)?.setColor(color)
            showRecordingPill(s == State.RECORDING)
            updateNotif()
            scheduleCollapse()
        }
    }

    private fun showRecordingPill(recording: Boolean) {
        val dp = resources.displayMetrics.density
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = params ?: return
        if (recording) {
            button?.visibility = View.GONE
            pill?.visibility = View.VISIBLE
            wave?.start()
            lp.width = (210 * dp).toInt(); lp.height = (46 * dp).toInt()
        } else {
            wave?.stop()
            pill?.visibility = View.GONE
            button?.visibility = View.VISIBLE
            lp.width = (56 * dp).toInt(); lp.height = (56 * dp).toInt()
        }
        // garder le centre approximativement stable + clamp à l'écran
        val screenW = resources.displayMetrics.widthPixels
        lp.x = lp.x.coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
        try { wm.updateViewLayout(container, lp) } catch (_: Exception) {}
    }

    private fun vibrate(ms: Long) {
        // Défensif : une vibration ne doit JAMAIS crasher l'enregistrement
        // (ex: SecurityException si permission absente, ou vibreur indispo).
        try {
            val v = if (Build.VERSION.SDK_INT >= 31)
                getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate indispo: ${t.javaClass.simpleName}")
        }
    }

    // ---- Bouton : drag + long-press + position memorisee + repli bord ----

    private val collapse = Runnable { container?.animate()?.alpha(0.4f)?.setDuration(200)?.start() }
    private fun scheduleCollapse() { main.removeCallbacks(collapse); main.postDelayed(collapse, 3000) }
    private fun wake() { container?.animate()?.alpha(1f)?.setDuration(120)?.start(); scheduleCollapse() }

    private fun showButton() {
        if (container != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay non accorde -> pas de bouton")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val margin = (8 * dp).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // Bouton micro rond (look/comportement existants)
        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDD8A6D3B.toInt()) }
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }

        // Pilule : rounded-rect blanc cassé avec ombre douce + onde cursive
        val waveView = CursiveWaveView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (40 * dp).toInt(), Gravity.CENTER
            )
        }
        val pillView = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * dp
                setColor(0xF2FFFFFF.toInt())
                setStroke(1, 0xFFE5E2DB.toInt())
            }
            elevation = 4 * dp
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                (200 * dp).toInt(), (44 * dp).toInt(), Gravity.CENTER
            )
            visibility = View.GONE
            addView(waveView)
        }

        val frame = FrameLayout(this).apply {
            addView(img)
            addView(pillView)
        }

        // La fenêtre démarre à la taille du bouton rond (56dp).
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.buttonX >= 0) PersistencePrefs.clampX(prefs.buttonX, size, screenW) else screenW - size - margin
            y = if (prefs.buttonY >= 0) PersistencePrefs.clampY(prefs.buttonY, size, screenH) else screenH / 2
        }

        var downX = 0; var downY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        var pttFired = false
        val longPress = Runnable {
            // Maintenu 250ms, pas bougé, toujours IDLE → push-to-talk
            if (!moved && state == State.IDLE) {
                pttFired = true
                vibrate(20)
                startRec()
            }
        }

        frame.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = lp.x; downY = lp.y; touchX = ev.rawX; touchY = ev.rawY
                    moved = false; pttFired = false
                    wake()
                    if (state == State.IDLE) main.postDelayed(longPress, 250); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    if (abs(dx) + abs(dy) > 10 * dp) {
                        moved = true; main.removeCallbacks(longPress)
                        lp.x = PersistencePrefs.clampX((downX + dx).toInt(), lp.width, screenW)
                        lp.y = PersistencePrefs.clampY((downY + dy).toInt(), lp.height, screenH)
                        try { wm.updateViewLayout(container, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        lp.x = if (lp.x + lp.width / 2 > screenW / 2) screenW - lp.width - margin else margin
                        try { wm.updateViewLayout(container, lp) } catch (_: Exception) {}
                        prefs.buttonX = lp.x; prefs.buttonY = lp.y
                    } else if (pttFired) {
                        // Relâchement du push-to-talk → on arrête + transcrit
                        if (state == State.RECORDING) stopRec()
                    } else {
                        onTap()
                    }; true
                }
                else -> false
            }
        }
        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView echec: ${e.javaClass.simpleName}")
            return
        }
        container = frame; button = img; pill = pillView; wave = waveView; params = lp
        scheduleCollapse()
    }

    private fun openApp() = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun toast(s: String) { main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("whisperpin", text))
    }

    override fun onDestroy() {
        micArmed = false
        state = State.IDLE
        audioRecord?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        audioRecord = null
        main.removeCallbacksAndMessages(null)
        try { wave?.stop() } catch (_: Exception) {}
        try { container?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        container = null; button = null; pill = null; wave = null
        super.onDestroy()
    }
}
