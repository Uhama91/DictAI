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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_ARM_MIC = "com.uhama.whisperpin.ARM_MIC"
        private const val DOUBLE_TAP_MS = 280L
        @Volatile var micArmed = false
            private set
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING, MIC_UNARMED }

    private val prefs by lazy { PersistencePrefs(this) }
    @Volatile private var state = State.MIC_UNARMED
    private var recordThread: Thread? = null
    private var container: View? = null
    private var pill: FrameLayout? = null
    private var wave: CursiveWaveView? = null
    private var loader: LoadingBorderView? = null
    private var liveText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcm: java.io.ByteArrayOutputStream? = null
    private var local: LocalTranscriber? = null
    private var streamingLocal: LiveStreamingTranscriber? = null
    private var liveSession: LiveStreamingTranscriber.Session? = null
    private var loadedModelName: String? = null
    private var baseButtonW = 0
    private var baseButtonH = 0
    private var liveWindowW = 0
    private var liveWindowH = 0
    private var livePreviewVisible = false
    private val localLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        micArmed = false
        createChannel()
        if (!startForegroundSpecialUse()) return
        showButton()
        ensureLocalLoaded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM_MIC) {
            promoteMic()
            // Si le modèle local n'était pas dispo au démarrage (pas encore téléchargé),
            // on retente de le charger (un seul chargement à la fois, cf. ensureLocalLoaded).
            ensureLocalLoaded()
        }
        return START_STICKY
    }

    /** Charge le modèle local hors thread principal, garanti une seule fois à la fois. */
    private fun ensureLocalLoaded() {
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        if (selectedModel == loadedModelName && (local != null || streamingLocal != null)) return
        if (!localLoading.compareAndSet(false, true)) return
        thread {
            try {
                val batch = TranscriptionEngine.loadLocal(this)
                val streaming = TranscriptionEngine.loadStreamingLocal(this)
                local = batch
                streamingLocal = streaming
                loadedModelName = selectedModel
            }
            finally { localLoading.set(false) }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "WhisperPin", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DictAI actif")
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
            State.TRANSCRIBING -> {}
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
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        liveSession = if (LiveStreamingTranscriber.supports(selectedModel)) {
            streamingLocal?.start { committed, tentative ->
                updateLivePreview(committed, tentative)
            }
        } else null
        setLivePreviewVisible(false)
        setState(State.RECORDING)
        vibrate(20)
        val ar = audioRecord!!
        recordThread = thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) {
                    pcm?.write(buf, 0, n)
                    liveSession?.acceptPcm16(buf, n)
                    wave?.setLevel(rmsLevel(buf, n))
                }
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
        val session = liveSession
        liveSession = null
        if (data.isEmpty()) {
            session?.cancel()
            setLivePreviewVisible(false)
            setState(State.IDLE)
            return
        }
        thread {
            val t0 = System.currentTimeMillis()
            val liveText = session?.finish()
            val r = if (!liveText.isNullOrBlank()) {
                TranscriptionEngine.Result(liveText)
            } else {
                TranscriptionEngine.transcribe(this, data, local)
            }
            val transcribeMs = System.currentTimeMillis() - t0
            var finalText = r.text?.let { Vocabulary.applyCorrections(this, it) }
            if (!finalText.isNullOrBlank() && prefs.trailingSpace) finalText += " "
            val outText = finalText
            val timing = "transcr ${transcribeMs}ms"
            Log.i(TAG, "Pipeline: $timing")
            main.post {
                if (!outText.isNullOrBlank()) {
                    copyToClipboard(outText)
                    val injected = WhisperAccessibilityService.controller?.inject(outText) ?: false
                    toast((if (injected) "Inséré" else "Copié") + " · $timing")
                } else toast("Erreur: ${r.error ?: "vide"}")
                setLivePreviewVisible(false)
                setState(State.IDLE)
            }
        }
    }

    /** Annule l'enregistrement en cours SANS transcrire (ex. 2e tap d'un double-tap). */
    private fun cancelRec() {
        if (state != State.RECORDING) return
        setState(State.IDLE) // fait sortir la boucle (state est @Volatile)
        // stop() AVANT le join : débloque immédiatement AudioRecord.read() → le thread sort vite.
        try { audioRecord?.stop() } catch (_: Exception) {}
        recordThread?.join(300); recordThread = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        liveSession?.cancel()
        liveSession = null
        pcm = null
        setLivePreviewVisible(false)
    }

    private fun setState(s: State) {
        state = s
        main.post {
            // Le micro a disparu : on signale l'état via la bordure de la pastille.
            // Ambre + plus épais si le micro n'est pas encore armé (setup requis), neutre sinon.
            val px = resources.displayMetrics.density
            (pill?.background as? GradientDrawable)?.setStroke(
                ((if (s == State.MIC_UNARMED) 2f else 1f) * px).toInt(),
                if (s == State.MIC_UNARMED) 0xFFD9A441.toInt() else 0xFFE5E2DB.toInt()
            )
            showRecordingPill(s == State.RECORDING)
            // Bordure lumineuse pendant la transcription.
            if (s == State.TRANSCRIBING) loader?.start() else loader?.stop()
            updateNotif()
            // Tant qu'une dictée est active, la pastille reste pleinement allumée (jamais de dim).
            if (s == State.IDLE || s == State.MIC_UNARMED) {
                setLivePreviewVisible(false)
                scheduleCollapse()
            }
            else { main.removeCallbacks(collapse); container?.animate()?.alpha(1f)?.setDuration(120)?.start() }
        }
    }

    private fun updateLivePreview(committed: String, tentative: String) {
        val text = listOf(committed, tentative).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (text.isBlank()) return
        main.post {
            liveText?.text = if (text.length > 520) "..." + text.takeLast(520) else text
            setLivePreviewVisible(true)
        }
    }

    private fun setLivePreviewVisible(show: Boolean) {
        val tv = liveText ?: return
        if (livePreviewVisible == show && tv.visibility == if (show) View.VISIBLE else View.GONE) return
        val wasVisible = livePreviewVisible
        livePreviewVisible = show
        tv.visibility = if (show) View.VISIBLE else View.GONE
        resizeOverlay(
            if (show) liveWindowW else baseButtonW,
            if (show) liveWindowH else baseButtonH,
            show,
            wasVisible,
        )
    }

    private fun resizeOverlay(width: Int, height: Int, showLive: Boolean, wasLive: Boolean) {
        val lp = params ?: return
        val view = container ?: return
        if (width <= 0 || height <= 0) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val wasRight = lp.x + (if (lp.width > 0) lp.width else width) / 2 >= screenW / 2
        val liveDelta = liveWindowH - baseButtonH
        if (showLive && !wasLive) lp.y -= liveDelta
        if (!showLive && wasLive) lp.y += liveDelta
        lp.width = width
        lp.height = height
        lp.x = if (wasRight) screenW - width else 0
        lp.y = PersistencePrefs.clampY(lp.y, height, screenH)
        updateFrameGravity()
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    private fun updateFrameGravity() {
        val frame = container as? LinearLayout ?: return
        val lp = params ?: return
        val screenW = resources.displayMetrics.widthPixels
        frame.gravity = if (lp.x + lp.width / 2 >= screenW / 2) Gravity.END else Gravity.START
    }

    /** La pastille est permanente : on anime juste l'onde pendant l'enregistrement, calme sinon. */
    private fun showRecordingPill(recording: Boolean) {
        if (recording) wave?.start()
        else { wave?.stop(); wave?.settle() }
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
    private fun scheduleCollapse() {
        main.removeCallbacks(collapse)
        // Dim auto seulement au repos : jamais pendant enregistrement / transcription.
        if (state == State.IDLE || state == State.MIC_UNARMED) main.postDelayed(collapse, 3000)
    }
    private fun wake() { container?.animate()?.alpha(1f)?.setDuration(120)?.start(); scheduleCollapse() }

    private fun showButton() {
        if (container != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay non accorde -> pas de bouton")
            return
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        // Le bouton EST la pastille d'ondulation (plus aucun logo micro). Court horizontalement.
        val pillW = (74 * dp).toInt()
        val pillH = (44 * dp).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val panelGap = (6 * dp).toInt()
        val panelH = (118 * dp).toInt()
        val panelW = min((312 * dp).toInt(), screenW - (16 * dp).toInt())
        baseButtonW = pillW
        baseButtonH = pillH
        liveWindowW = panelW
        liveWindowH = pillH + panelGap + panelH

        // Pastille : rounded-rect blanc cassé + onde cursive, TOUJOURS visible (= le bouton).
        // Au repos l'onde est calme (figée), pendant l'enregistrement elle réagit à la voix.
        // Onde : pleine largeur, SANS padding → les ondulations touchent les bords blancs.
        val waveView = CursiveWaveView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (32 * dp).toInt(), Gravity.CENTER
            )
        }
        // Bordure lumineuse de chargement (cachée au repos).
        val loaderView = LoadingBorderView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
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
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                pillW, pillH
            )
            addView(waveView)
            addView(loaderView)
        }

        val liveView = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(ThemeTokens.INK)
            includeFontPadding = false
            maxLines = 5
            gravity = Gravity.BOTTOM or Gravity.START
            setLineSpacing(2 * dp, 1.0f)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(0xF21F1F25.toInt())
                setStroke((1.2f * dp).toInt(), ThemeTokens.GREEN)
            }
            layoutParams = LinearLayout.LayoutParams(panelW, panelH).apply {
                bottomMargin = panelGap
            }
        }

        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(liveView)
            addView(pillView)
        }

        // La fenêtre démarre à la taille du bouton rond (56dp).
        val lp = WindowManager.LayoutParams(
            pillW, pillH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Toujours plaqué contre un bord (jamais au centre) : on re-colle au bord le plus proche.
            val rawX = if (prefs.buttonX >= 0) PersistencePrefs.clampX(prefs.buttonX, pillW, screenW) else screenW - pillW
            x = if (rawX + pillW / 2 >= screenW / 2) screenW - pillW else 0
            y = if (prefs.buttonY >= 0) PersistencePrefs.clampY(prefs.buttonY, pillH, screenH) else screenH / 2
        }

        var downX = 0; var downY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        var pttFired = false
        var lastTapAt = 0L
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
                        updateFrameGravity()
                        try { wm.updateViewLayout(container, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        // Snap flush au bord le plus proche (collé, sans marge).
                        lp.x = if (lp.x + lp.width / 2 > screenW / 2) screenW - lp.width else 0
                        updateFrameGravity()
                        try { wm.updateViewLayout(container, lp) } catch (_: Exception) {}
                        prefs.buttonX = lp.x; prefs.buttonY = lp.y
                    } else if (pttFired) {
                        // Relâchement du push-to-talk → on arrête + transcrit
                        if (state == State.RECORDING) stopRec()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        if (state == State.RECORDING && now - lastTapAt < DOUBLE_TAP_MS) {
                            // 2e tap rapide : l'enregistrement vient d'être lancé par le 1er tap → ouvrir l'app
                            lastTapAt = 0L
                            cancelRec()
                            openApp()
                        } else {
                            // tap normal ; on ne mémorise l'instant que si CE tap démarre un enregistrement
                            val starting = state == State.IDLE
                            onTap()
                            lastTapAt = if (starting) now else 0L
                        }
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
        container = frame; pill = pillView; wave = waveView; loader = loaderView; liveText = liveView; params = lp
        frame.post { waveView.settle() } // dessine l'onde calme au repos
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
        liveSession?.cancel()
        liveSession = null
        audioRecord?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        audioRecord = null
        main.removeCallbacksAndMessages(null)
        try { wave?.stop() } catch (_: Exception) {}
        try { loader?.stop() } catch (_: Exception) {}
        try { container?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        container = null; pill = null; wave = null; loader = null; liveText = null
        super.onDestroy()
    }
}
