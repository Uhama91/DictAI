package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/** Ensures AudioRecord is never released or read buffers snapshotted while its reader is alive. */
internal class RecordingStopCoordinator(
    private val recordThread: Thread?,
    private val stopRecorder: () -> Unit,
    private val releaseRecorder: () -> Unit,
    private val snapshot: () -> Unit,
) {
    sealed class Result {
        data object Stopped : Result()
        data object TimedOut : Result()
    }

    fun stopJoinRelease(timeoutMs: Long): Result {
        try { stopRecorder() } catch (_: Throwable) {}
        if (!joinFor(timeoutMs)) return Result.TimedOut
        releaseAndSnapshot()
        return Result.Stopped
    }

    /** Used after a bounded wait: it keeps waiting off-main until release is safe. */
    fun awaitExitThenRelease(): Result {
        var interrupted = false
        while (recordThread?.isAlive == true) {
            try { recordThread.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
        releaseAndSnapshot()
        return Result.Stopped
    }

    private fun joinFor(timeoutMs: Long): Boolean {
        return try {
            recordThread?.join(timeoutMs)
            recordThread?.isAlive != true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun releaseAndSnapshot() {
        try { releaseRecorder() } catch (_: Throwable) {}
        try { snapshot() } catch (_: Throwable) {}
    }
}

/** Acquires loading ownership before consulting the resident engine, whose lock may wrap a slow JNI open. */
internal object LocalLoadStartGate {
    enum class Decision { START, BUSY, ALREADY_LOADED, DESTROYED }

    fun acquire(
        localLoading: java.util.concurrent.atomic.AtomicBoolean,
        isDestroyed: () -> Boolean,
        isLoaded: () -> Boolean,
    ): Decision {
        if (!localLoading.compareAndSet(false, true)) return Decision.BUSY
        return try {
            when {
                isDestroyed() -> Decision.DESTROYED.also { localLoading.set(false) }
                isLoaded() -> Decision.ALREADY_LOADED.also { localLoading.set(false) }
                else -> Decision.START
            }
        } catch (t: Throwable) {
            localLoading.set(false)
            throw t
        }
    }
}

/** Serializes destruction with publication so a completed background open cannot revive the service. */
internal class LocalEngineLifecycle {
    private val lock = Any()
    private val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isDestroyed(): Boolean = destroyed.get()

    fun publishIfAlive(publish: () -> Unit): Boolean = synchronized(lock) {
        if (destroyed.get()) return false
        publish()
        true
    }

    fun destroy(clearPublishedEngine: () -> Unit): Boolean = synchronized(lock) {
        if (!destroyed.compareAndSet(false, true)) return false
        clearPublishedEngine()
        true
    }
}

internal fun dispatchResidentClose(
    close: () -> Unit,
    launch: ((() -> Unit) -> Unit) = { task ->
        thread(name = "dictai-release-local-engine") { task() }
    },
) {
    launch(close)
}

/** Pure guard so the overlay never starts AudioRecord while its selected engine is loading or absent. */
internal object RecordingStartGate {
    enum class Decision { START, LOADING, RELOAD_REQUIRED, UNAVAILABLE }

    fun decide(
        localLoading: Boolean,
        selectedModel: String,
        loadedModel: String?,
        hasBatchEngine: Boolean,
        hasStreamingEngine: Boolean,
    ): Decision = when {
        localLoading -> Decision.LOADING
        selectedModel != loadedModel -> Decision.RELOAD_REQUIRED
        LiveStreamingTranscriber.supports(selectedModel) && !hasStreamingEngine -> Decision.UNAVAILABLE
        !LiveStreamingTranscriber.supports(selectedModel) && !hasBatchEngine -> Decision.UNAVAILABLE
        else -> Decision.START
    }
}

class OverlayService : Service() {

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_ARM_MIC = "com.uhama.whisperpin.ARM_MIC"
        private const val DOUBLE_TAP_MS = 280L
        private const val RECORD_STOP_TIMEOUT_MS = 1_000L
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
    private var livePanel: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var liveParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcm: java.io.ByteArrayOutputStream? = null
    @Volatile private var local: LocalTranscriber? = null
    @Volatile private var streamingLocal: LiveStreamingTranscriber? = null
    private var liveSession: LiveStreamingTranscriber.Session? = null
    private var recordingOptions: RecordingOptions? = null
    @Volatile private var loadedModelName: String? = null
    private var baseButtonW = 0
    private var baseButtonH = 0
    private var livePanelW = 0
    private var livePanelH = 0
    private var currentAnchor: Anchor? = null
    private var livePanelAdded = false
    private var livePreviewVisible = false
    private val liveTranscriptBuffer = LiveTranscriptBuffer()
    private val localLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val localEngineLifecycle = LocalEngineLifecycle()
    private val residentLocalEngine = ResidentEngine<LoadedLocalEngine>()
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

    private class LoadedLocalEngine(
        val batch: LocalTranscriber?,
        val streaming: LiveStreamingTranscriber?,
    ) : java.io.Closeable {
        override fun close() {
            try {
                streaming?.close()
            } finally {
                localClose()
            }
        }

        private fun localClose() {
            batch?.close()
        }
    }

    /** Charge le modèle local hors thread principal; l'ancien moteur est fermé avant toute nouvelle ouverture. */
    private fun ensureLocalLoaded() {
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        when (LocalLoadStartGate.acquire(
            localLoading = localLoading,
            isDestroyed = localEngineLifecycle::isDestroyed,
            isLoaded = { residentLocalEngine.isLoaded(selectedModel) },
        )) {
            LocalLoadStartGate.Decision.START -> Unit
            LocalLoadStartGate.Decision.BUSY,
            LocalLoadStartGate.Decision.ALREADY_LOADED,
            LocalLoadStartGate.Decision.DESTROYED -> return
        }
        thread {
            try {
                liveSession?.cancelAndAwait()
                liveSession = null
                val loaded = residentLocalEngine.replace(selectedModel) {
                    val batch = TranscriptionEngine.loadLocal(this, selectedModel)
                    val streaming = TranscriptionEngine.loadStreamingLocal(this, selectedModel)
                    LoadedLocalEngine(batch, streaming).takeIf { batch != null || streaming != null }
                }
                val published = localEngineLifecycle.publishIfAlive {
                    local = loaded?.batch
                    streamingLocal = loaded?.streaming
                    loadedModelName = selectedModel
                }
                if (!published) residentLocalEngine.close()
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
        val cloudRequested = prefs.cloudCleanupEnabled
        val targetSensitive = runCatching {
            WhisperAccessibilityService.controller?.isActiveTargetSensitive() ?: true
        }.getOrDefault(true)
        val cloudPolicy = CloudSensitiveTargetPolicy.snapshot(cloudRequested, targetSensitive)
        val options = RecordingOptions(
            language = prefs.dictationLanguage,
            cloudCleanupEnabled = cloudPolicy.cloudAllowed,
            cloudSuppressedForSensitiveTarget = cloudPolicy.suppressedForSensitiveTarget,
            cloudProvider = prefs.cloudProvider,
            cloudModel = prefs.cloudModel(),
        )
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        when (RecordingStartGate.decide(
            localLoading = localLoading.get(),
            selectedModel = selectedModel,
            loadedModel = loadedModelName,
            hasBatchEngine = local != null,
            hasStreamingEngine = streamingLocal != null,
        )) {
            RecordingStartGate.Decision.START -> Unit
            RecordingStartGate.Decision.LOADING -> {
                toast("Chargement du modèle local…")
                return
            }
            RecordingStartGate.Decision.RELOAD_REQUIRED -> {
                ensureLocalLoaded()
                toast("Chargement du modèle local…")
                return
            }
            RecordingStartGate.Decision.UNAVAILABLE -> {
                toast("Modèle local indisponible.")
                return
            }
        }
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
        recordingOptions = options
        liveTranscriptBuffer.clear()
        liveSession = if (LiveStreamingTranscriber.supports(selectedModel)) {
            streamingLocal?.start(options.language) { committed, tentative ->
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
        val recorder = audioRecord
        val recordingThread = recordThread
        var capture: RecordingCapture? = null
        val coordinator = RecordingStopCoordinator(
            recordThread = recordingThread,
            stopRecorder = { recorder?.stop() },
            releaseRecorder = { recorder?.release() },
            snapshot = {
                if (audioRecord === recorder) audioRecord = null
                if (recordThread === recordingThread) recordThread = null
                capture = RecordingCapture(
                    pcm = pcm?.toByteArray() ?: ByteArray(0),
                    session = liveSession,
                    options = recordingOptions ?: RecordingOptions(
                        language = DictationLanguage.FRENCH,
                        cloudCleanupEnabled = false,
                        cloudSuppressedForSensitiveTarget = false,
                        cloudProvider = CloudProvider.OPENAI,
                        cloudModel = CloudModelCatalog.forProvider(CloudProvider.OPENAI).first(),
                    ),
                )
                pcm = null
                liveSession = null
                recordingOptions = null
            },
        )
        thread(name = "dictai-stop-rec") {
            when (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS)) {
                RecordingStopCoordinator.Result.Stopped -> {
                    val stoppedCapture = capture ?: RecordingCapture(
                        ByteArray(0), null,
                        RecordingOptions(
                            DictationLanguage.FRENCH,
                            false,
                            false,
                            CloudProvider.OPENAI,
                            CloudModelCatalog.forProvider(CloudProvider.OPENAI).first(),
                        ),
                    )
                    processStoppedRecording(stoppedCapture)
                }
                RecordingStopCoordinator.Result.TimedOut -> {
                    // The reader can no longer feed a result, but release/snapshot still wait for it safely.
                    liveSession?.cancel()
                    Log.w(TAG, "event=audio_stop outcome=timeout")
                    main.post {
                        toast("Transcription locale indisponible.")
                        setLivePreviewVisible(false)
                    }
                    coordinator.awaitExitThenRelease()
                    main.post { setState(State.IDLE) }
                }
            }
        }
    }

    private data class RecordingCapture(
        val pcm: ByteArray,
        val session: LiveStreamingTranscriber.Session?,
        val options: RecordingOptions,
    )

    /** Per-recording snapshot: changing settings while dictating cannot change that result. */
    private data class RecordingOptions(
        val language: DictationLanguage,
        val cloudCleanupEnabled: Boolean,
        val cloudSuppressedForSensitiveTarget: Boolean,
        val cloudProvider: CloudProvider,
        val cloudModel: CuratedCloudModel,
    )

    private fun processStoppedRecording(capture: RecordingCapture) {
        if (capture.pcm.isEmpty()) {
            capture.session?.cancel()
            main.post {
                setLivePreviewVisible(false)
                setState(State.IDLE)
            }
            return
        }
        val t0 = System.currentTimeMillis()
        val streamingResult = capture.session?.finish()
        val r = when {
            capture.session == null -> TranscriptionEngine.transcribe(this, capture.pcm, local)
            streamingResult is LiveStreamingTranscriber.Finalization.Success ->
                TranscriptionEngine.Result(streamingResult.text)
            streamingResult is LiveStreamingTranscriber.Finalization.Empty ->
                TranscriptionEngine.Result(null)
            streamingResult is LiveStreamingTranscriber.Finalization.Timeout ->
                TranscriptionEngine.Result(null, "Transcription locale expirée.")
            else -> TranscriptionEngine.Result(null, "Transcription locale indisponible.")
        }
        val transcribeMs = System.currentTimeMillis() - t0
        val localText = r.text?.let { Vocabulary.applyCorrections(this, it) }
        val cloudText = if (!localText.isNullOrBlank() && capture.options.cloudCleanupEnabled) {
            val credential = SecureCredentialStore(this).load(capture.options.cloudProvider)
            credential?.let {
                CloudCleanup().clean(localText, capture.options.language, capture.options.cloudProvider, capture.options.cloudModel, it)
            }
        } else null
        var finalText = cloudText ?: localText
        if (!finalText.isNullOrBlank() && prefs.trailingSpace) finalText += " "
        val outText = finalText
        val source = if (capture.session != null) "stream" else "batch"
        val timing = "transcr ${transcribeMs}ms"
        Log.i(TAG, "event=transcription source=$source outcome=${if (outText.isNullOrBlank()) "empty_or_failure" else "success"} elapsedMs=$transcribeMs")
        main.post {
            if (!outText.isNullOrBlank()) {
                copyToClipboard(outText)
                val injected = WhisperAccessibilityService.controller?.inject(outText) ?: false
                val cleanupFeedback = when {
                    capture.options.cloudSuppressedForSensitiveTarget ->
                        " · cloud désactivé (champ sensible)"
                    capture.options.cloudCleanupEnabled && cloudText != null -> " · nettoyage appliqué"
                    capture.options.cloudCleanupEnabled -> " · texte local conservé"
                    else -> ""
                }
                toast((if (injected) "Inséré" else "Copié") + cleanupFeedback + " · $timing")
            } else if (streamingResult !is LiveStreamingTranscriber.Finalization.Empty) {
                toast("Erreur: ${r.error ?: "vide"}")
            }
            setLivePreviewVisible(false)
            setState(State.IDLE)
        }
    }

    /** Annule l'enregistrement en cours SANS transcrire (ex. 2e tap d'un double-tap). */
    private fun cancelRec() {
        if (state != State.RECORDING) return
        // TRANSCRIBING blocks a second AudioRecord until the first reader has fully exited.
        setState(State.TRANSCRIBING)
        val recorder = audioRecord
        val recordingThread = recordThread
        liveSession?.cancel()
        setLivePreviewVisible(false)
        val coordinator = RecordingStopCoordinator(
            recordThread = recordingThread,
            stopRecorder = { recorder?.stop() },
            releaseRecorder = { recorder?.release() },
            snapshot = {
                if (audioRecord === recorder) audioRecord = null
                if (recordThread === recordingThread) recordThread = null
                // Discard only once AudioRecord.read() can no longer write to this session.
                liveSession = null
                pcm = null
                recordingOptions = null
            },
        )
        thread(name = "dictai-cancel-rec") {
            when (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS)) {
                RecordingStopCoordinator.Result.Stopped -> main.post { setState(State.IDLE) }
                RecordingStopCoordinator.Result.TimedOut -> {
                    Log.w(TAG, "event=audio_cancel outcome=timeout")
                    coordinator.awaitExitThenRelease()
                    main.post { setState(State.IDLE) }
                }
            }
        }
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
        val text = liveTranscriptBuffer.render(committed, tentative)
        if (text.isBlank()) return
        main.post {
            liveText?.text = text
            livePanel?.post { livePanel?.fullScroll(View.FOCUS_DOWN) }
            setLivePreviewVisible(true)
        }
    }

    private fun setLivePreviewVisible(show: Boolean) {
        val panel = livePanel ?: return
        if (livePreviewVisible == show && panel.visibility == if (show) View.VISIBLE else View.GONE) return
        livePreviewVisible = show
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val panelParams = liveParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!livePanelAdded) {
            try {
                wm.addView(panel, panelParams)
                livePanelAdded = true
            } catch (e: Exception) {
                Log.w(TAG, "add live panel echec: ${e.javaClass.simpleName}")
                livePreviewVisible = false
                panel.visibility = View.GONE
                return
            }
        }
        positionLivePanel(currentAnchor ?: return)
    }

    private fun screenRect(): Rect = try {
        val metrics = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        Rect(
            bounds.left + insets.left,
            bounds.top + insets.top,
            (bounds.width() - insets.left - insets.right).coerceAtLeast(1),
            (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
        )
    } catch (_: Exception) {
        Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun pillRect(lp: WindowManager.LayoutParams): Rect = Rect(lp.x, lp.y, lp.width, lp.height)

    private fun positionLivePanel(anchor: Anchor) {
        val panel = livePanel ?: return
        val panelParams = liveParams ?: return
        if (!livePanelAdded) return
        val point = OverlayPlacement.panelPosition(
            anchor.edge, pillRect(params ?: return), Rect(0, 0, panelParams.width, panelParams.height),
            screenRect(), (6 * resources.displayMetrics.density).toInt(),
        )
        panelParams.x = point.x
        panelParams.y = point.y
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, panelParams) } catch (_: Exception) {}
    }

    private fun updatePillLayout(anchorForPanel: Anchor? = currentAnchor) {
        val lp = params ?: return
        val view = container ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, lp) } catch (_: Exception) {}
        anchorForPanel?.let(::positionLivePanel)
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
        baseButtonW = pillW
        baseButtonH = pillH
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
            addView(waveView)
            addView(loaderView)
        }

        val liveView = TextView(this).apply {
            textSize = 14f
            setTextColor(ThemeTokens.INK)
            includeFontPadding = false
            gravity = Gravity.START
            setLineSpacing(2 * dp, 1.0f)
        }
        val panelHPadding = (20 * dp).toInt()
        val safeScreen = screenRect()
        livePanelW = min((312 * dp).toInt(), (safeScreen.width - (16 * dp).toInt()).coerceAtLeast(1))
        livePanelH = liveView.lineHeight * 3 + panelHPadding
        val livePanel = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((18 * dp).toInt())
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * dp
                setColor(0xFF1F1F25.toInt())
                setStroke((1.2f * dp).toInt(), ThemeTokens.GREEN)
            }
            addView(liveView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // La fenêtre interactive ne contient que la pastille et garde sa taille fixe.
        val lp = WindowManager.LayoutParams(
            pillW, pillH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val initialAnchor = prefs.loadAnchor(pillW, pillH, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val initialPosition = OverlayPlacement.pillPosition(initialAnchor, Rect(0, 0, pillW, pillH), screenRect())
        lp.x = initialPosition.x
        lp.y = initialPosition.y
        val panelParams = WindowManager.LayoutParams(
            livePanelW, livePanelH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.8f
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
        fun finishDrag() {
            val screen = screenRect()
            val anchor = OverlayPlacement.snap(Point(lp.x, lp.y), Rect(0, 0, lp.width, lp.height), screen)
            val snapped = OverlayPlacement.pillPosition(anchor, Rect(0, 0, lp.width, lp.height), screen)
            currentAnchor = anchor
            lp.x = snapped.x
            lp.y = snapped.y
            updatePillLayout()
            prefs.buttonX = lp.x
            prefs.buttonY = lp.y
            prefs.saveAnchor(anchor)
        }

        pillView.setOnTouchListener { _, ev ->
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
                        val screen = screenRect()
                        val clamped = OverlayPlacement.clampPill(
                            Point((downX + dx).toInt(), (downY + dy).toInt()),
                            Rect(0, 0, lp.width, lp.height),
                            screen,
                        )
                        lp.x = clamped.x
                        lp.y = clamped.y
                        val dragAnchor = OverlayPlacement.snap(Point(lp.x, lp.y), Rect(0, 0, lp.width, lp.height), screen)
                        updatePillLayout(dragAnchor)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        finishDrag()
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
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (moved) finishDrag()
                    if (pttFired && state == State.RECORDING) stopRec()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(pillView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView echec: ${e.javaClass.simpleName}")
            return
        }
        container = pillView; pill = pillView; wave = waveView; loader = loaderView
        liveText = liveView; this.livePanel = livePanel; params = lp; liveParams = panelParams; currentAnchor = initialAnchor
        pillView.post { waveView.settle() } // dessine l'onde calme au repos
        scheduleCollapse()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lp = params ?: return
        val dp = resources.displayMetrics.density
        baseButtonW = (74 * dp).toInt()
        baseButtonH = (44 * dp).toInt()
        lp.width = baseButtonW
        lp.height = baseButtonH
        val panelParams = liveParams
        val text = liveText
        if (panelParams != null && text != null) {
            val safeScreen = screenRect()
            livePanelW = min((312 * dp).toInt(), (safeScreen.width - (16 * dp).toInt()).coerceAtLeast(1))
            livePanelH = text.lineHeight * 3 + (20 * dp).toInt()
            panelParams.width = livePanelW
            panelParams.height = livePanelH
        }
        val anchor = currentAnchor ?: prefs.loadAnchor(baseButtonW, baseButtonH, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        currentAnchor = anchor
        val point = OverlayPlacement.pillPosition(anchor, Rect(0, 0, lp.width, lp.height), screenRect())
        lp.x = point.x
        lp.y = point.y
        updatePillLayout()
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
        val releaseResident = localEngineLifecycle.destroy {
            local = null
            streamingLocal = null
            loadedModelName = null
        }
        liveSession?.cancel()
        liveSession = null
        if (releaseResident) {
            dispatchResidentClose(close = { residentLocalEngine.close() })
        }
        audioRecord?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        audioRecord = null
        main.removeCallbacksAndMessages(null)
        try { wave?.stop() } catch (_: Exception) {}
        try { loader?.stop() } catch (_: Exception) {}
        try { if (livePanelAdded) livePanel?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        try { container?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        livePanelAdded = false
        container = null; pill = null; wave = null; loader = null; liveText = null; livePanel = null; liveParams = null
        super.onDestroy()
    }
}
