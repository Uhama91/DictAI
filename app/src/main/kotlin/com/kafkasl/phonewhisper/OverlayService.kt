package com.kafkasl.phonewhisper

import android.app.Notification
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.doOnLayout
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
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
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

    private enum class State { IDLE, RECORDING, PAUSING, PAUSED, TRANSCRIBING, CANCELLING, MIC_UNARMED }

    private class ActiveDictationRun(
        val session: DictationAsrSession,
        val cancellation: DictationCancellationCoordinator = DictationCancellationCoordinator(),
    ) {
        val captureGate = RecordingCaptureGate()
        @Volatile var pauseWorker: Thread? = null
        var resumeAfterPause = false
        var pendingPreview: Pair<String, String>? = null
        val finalPublication = DictationFinalPublicationGate(DOUBLE_TAP_MS)
        val completion = DictationRunCompletionGate()
        val cancellationWaitStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val prefs by lazy { PersistencePrefs(this) }
    @Volatile private var state = State.MIC_UNARMED
    private var recordThread: Thread? = null
    private var container: View? = null
    private var pill: FrameLayout? = null
    private var wave: CursiveWaveView? = null
    private var pauseIndicator: TextView? = null
    private var loader: LoadingBorderView? = null
    private var liveText: EditText? = null
    private var updatingLiveText = false
    private val editableTranscript = EditableTranscript()
    private var formatDialog: AlertDialog? = null
    private var livePanel: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var liveParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcm: java.io.ByteArrayOutputStream? = null
    @Volatile private var asrEngine: DictationAsrEngine? = null
    @Volatile private var asrSession: DictationAsrSession? = null
    @Volatile private var activeRun: ActiveDictationRun? = null
    private var recordingOptions: RecordingOptions? = null
    @Volatile private var loadedModelName: String? = null
    private var baseButtonW = 0
    private var baseButtonH = 0
    private var livePanelW = 0
    private var livePanelH = 0
    private var currentAnchor: Anchor? = null
    private var livePanelAdded = false
    private var livePreviewVisible = false
    private val localLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val localEngineLifecycle = LocalEngineLifecycle()
    private val residentAsrEngine = ResidentEngine<DictationAsrEngine>()
    private val main = Handler(Looper.getMainLooper())
    private val tapCoordinator = DictationTapGestureCoordinator(DOUBLE_TAP_MS)

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

    /** Charge le modèle local hors thread principal; l'ancien moteur est fermé avant toute nouvelle ouverture. */
    private fun ensureLocalLoaded() {
        if (activeRun != null) return
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        when (LocalLoadStartGate.acquire(
            localLoading = localLoading,
            isDestroyed = localEngineLifecycle::isDestroyed,
            isLoaded = { residentAsrEngine.isLoaded(selectedModel) },
        )) {
            LocalLoadStartGate.Decision.START -> Unit
            LocalLoadStartGate.Decision.BUSY,
            LocalLoadStartGate.Decision.ALREADY_LOADED,
            LocalLoadStartGate.Decision.DESTROYED -> return
        }
        thread {
            try {
                asrSession?.cancelAndAwait()
                asrSession = null
                val loaded = residentAsrEngine.replace(selectedModel) {
                    DictationAsrEngineFactory.create(this, selectedModel)
                }
                val published = localEngineLifecycle.publishIfAlive {
                    asrEngine = loaded
                    loadedModelName = selectedModel
                }
                if (!published) residentAsrEngine.close()
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
                    State.PAUSING -> "Mise en pause…"
                    State.PAUSED -> "Dictée en pause — appuyez pour reprendre"
                    State.TRANSCRIBING -> "Transcription..."
                    State.CANCELLING -> "Annulation de la dictée…"
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

    private fun startRec() {
        val cloudRequested = prefs.cloudCleanupEnabled
        val targetSensitive = runCatching {
            InjectionGateway.current()?.isActiveTargetSensitive() ?: true
        }.getOrDefault(true)
        val cloudPolicy = CloudSensitiveTargetPolicy.snapshot(cloudRequested, targetSensitive)
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        when (RecordingStartGate.decide(
            localLoading = localLoading.get(),
            selectedModel = selectedModel,
            loadedModel = loadedModelName,
            hasAsrEngine = asrEngine != null,
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
        val engine = asrEngine ?: return
        val options = RecordingOptions(
            language = prefs.dictationLanguage,
            asrMode = engine.mode,
            cloudCleanupEnabled = cloudPolicy.cloudAllowed,
            cloudSuppressedForSensitiveTarget = cloudPolicy.suppressedForSensitiveTarget,
            cloudModel = prefs.cloudModel(),
            format = PostProcessingFormats(this).selected(),
        )
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val transaction = RecordingStartupTransaction(
            bufferSize = bufSize,
            createRecorder = {
                AndroidRecordingRecorder(
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
                    ),
                )
            },
            openSession = {
                engine.start(options.language) { committed, tentative ->
                    updateLivePreview(committed, tentative)
                }
            },
        )
        val started = when (val result = transaction.start()) {
            is RecordingStartupTransaction.Result.Started -> result
            is RecordingStartupTransaction.Result.Failed -> {
                val message = when (result.reason) {
                    RecordingStartupTransaction.Failure.CONSTRUCTION_FAILED,
                    RecordingStartupTransaction.Failure.START_FAILED -> "Accès au micro refusé"
                    RecordingStartupTransaction.Failure.SESSION_FAILED -> "Transcription locale indisponible."
                    else -> "Mic indisponible."
                }
                toast(message)
                Log.w(TAG, "event=audio_start outcome=${result.reason}")
                return
            }
        }
        val recorder = started.recorder as? AndroidRecordingRecorder
        if (recorder == null) {
            started.session.cancel()
            toast("Mic indisponible.")
            Log.w(TAG, "event=audio_start outcome=unexpected_recorder")
            return
        }
        if (activeRun != null) {
            started.session.cancel()
            toast("Dictée déjà en cours.")
            return
        }
        val run = ActiveDictationRun(started.session)
        run.cancellation.onCancel { started.session.cancel() }
        val ar = recorder.audioRecord
        val recordingPcm = java.io.ByteArrayOutputStream()
        try {
            audioRecord = ar
            pcm = recordingPcm
            recordingOptions = options
            asrSession = started.session
            activeRun = run
            editableTranscript.clear()
            updatingLiveText = true
            liveText?.setText("")
            updatingLiveText = false
            liveText?.isEnabled = true
            setLivePreviewVisible(false)
            setState(State.RECORDING)
            vibrate(20)
            launchAudioReader(run, ar, recordingPcm, bufSize)
        } catch (t: Throwable) {
            if (asrSession === started.session) asrSession = null
            if (audioRecord === ar) audioRecord = null
            recordThread = null
            pcm = null
            recordingOptions = null
            run.cancellation.cancel()
            if (activeRun === run) activeRun = null
            try { ar.stop() } catch (_: Throwable) {}
            try { ar.release() } catch (_: Throwable) {}
            setLivePreviewVisible(false)
            setState(State.IDLE)
            Log.w(TAG, "event=audio_start outcome=publication_failure type=${t.javaClass.simpleName}")
        }
    }

    private fun launchAudioReader(
        run: ActiveDictationRun,
        recorder: AudioRecord,
        recordingPcm: java.io.ByteArrayOutputStream,
        bufferSize: Int,
    ) {
        val reader = Thread({
            val buf = ByteArray(bufferSize)
            try {
                while (state == State.RECORDING && isCurrentRun(run)) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) run.captureGate.deliver {
                        recordingPcm.write(buf, 0, n)
                        run.session.acceptPcm16(buf, n)
                        wave?.setLevel(rmsLevel(buf, n))
                    }
                    if (n < 0 && state == State.RECORDING) error("audio_read_failed")
                }
            } catch (_: Throwable) {
                main.post {
                    if (isCurrentRun(run) && audioRecord === recorder && state == State.RECORDING) {
                        pauseRec()
                        toast("Micro interrompu : dictée conservée en pause.")
                    }
                }
            }
        }, "dictai-audio-reader")
        recordThread = reader
        reader.start()
    }

    private fun pauseRec() {
        if (state != State.RECORDING) return
        val run = activeRun ?: return
        tapCoordinator.reset()
        run.captureGate.pause()
        setState(State.PAUSING)
        setLivePreviewVisible(true)
        val recorder = audioRecord
        val reader = recordThread
        // Transfer ownership to the pause worker. Destruction waits for it before closing ASR.
        audioRecord = null
        recordThread = null
        val coordinator = RecordingStopCoordinator(
            recordThread = reader,
            stopRecorder = { recorder?.stop() },
            releaseRecorder = { recorder?.release() },
            snapshot = {},
        )
        val worker = Thread({
            if (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS) == RecordingStopCoordinator.Result.TimedOut) {
                Log.w(TAG, "event=audio_pause outcome=waiting_for_reader")
                coordinator.awaitExitThenRelease()
            }
            main.post {
                if (!isCurrentRun(run) || localEngineLifecycle.isDestroyed() || state != State.PAUSING) return@post
                setState(State.PAUSED)
                vibrate(20)
                if (run.resumeAfterPause) {
                    run.resumeAfterPause = false
                    resumeRec()
                }
            }
        }, "dictai-pause-rec")
        run.pauseWorker = worker
        worker.start()
    }

    private fun resumeRec() {
        val run = activeRun ?: return
        if (state == State.PAUSING) {
            run.resumeAfterPause = true
            return
        }
        if (state != State.PAUSED || localEngineLifecycle.isDestroyed()) return
        val recordingPcm = pcm ?: return
        var recorder: AudioRecord? = null
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(bufferSize > 0)
            val resumed = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize,
            )
            recorder = resumed
            check(resumed.state == AudioRecord.STATE_INITIALIZED)
            resumed.startRecording()
            check(resumed.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            audioRecord = resumed
            tapCoordinator.reset()
            run.captureGate.resume()
            setState(State.RECORDING)
            launchAudioReader(run, resumed, recordingPcm, bufferSize)
        } catch (_: Throwable) {
            run.captureGate.pause()
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
            audioRecord = null
            recordThread = null
            setState(State.PAUSED)
            toast("Reprise du micro impossible : texte conservé, réessayez.")
            return
        }
        // Render synchronously on main so an old queued preview cannot overtake a newer one.
        run.pendingPreview?.let { renderLivePreview(run, it.first, it.second) }
        scrollTranscriptToEnd(run)
        vibrate(20)
    }

    private fun isTranscriptEditable(): Boolean =
        state == State.RECORDING || state == State.PAUSING || state == State.PAUSED

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
        if (state != State.RECORDING && state != State.PAUSED) return
        val run = activeRun ?: return
        run.captureGate.pause()
        formatDialog?.dismiss()
        liveText?.isEnabled = false
        setLivePreviewVisible(false)
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
                    run = run,
                    pcm = pcm?.toByteArray() ?: ByteArray(0),
                    session = asrSession ?: run.session,
                    options = recordingOptions ?: RecordingOptions(
                        language = DictationLanguage.FRENCH,
                        asrMode = DictationAsrMode.BATCH,
                        cloudCleanupEnabled = false,
                        cloudSuppressedForSensitiveTarget = false,
                        cloudModel = CloudModelCatalog.default,
                    ),
                )
                pcm = null
                asrSession = null
                recordingOptions = null
            },
        )
        run.completion.markWorkerStarted()
        thread(name = "dictai-stop-rec") {
            try {
                when (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS)) {
                    RecordingStopCoordinator.Result.Stopped -> {
                        val stoppedCapture = capture ?: RecordingCapture(
                            run,
                            ByteArray(0), null,
                            RecordingOptions(
                                DictationLanguage.FRENCH,
                                DictationAsrMode.BATCH,
                                false,
                                false,
                                CloudModelCatalog.default,
                            ),
                        )
                        processStoppedRecording(stoppedCapture)
                    }
                    RecordingStopCoordinator.Result.TimedOut -> {
                        // The reader can no longer feed a result, but release/snapshot still wait for it safely.
                        run.session.cancel()
                        Log.w(TAG, "event=audio_stop outcome=timeout")
                        coordinator.awaitExitThenRelease()
                        awaitSessionExit(run)
                        main.post {
                            if (isCurrentRun(run) && !run.cancellation.isCancelled) {
                                toast("Transcription locale indisponible.")
                            }
                            completeRunOnMain(run)
                        }
                    }
                }
            } finally {
                run.completion.markWorkerDone()
            }
        }
    }

    private data class RecordingCapture(
        val run: ActiveDictationRun,
        val pcm: ByteArray,
        val session: DictationAsrSession?,
        val options: RecordingOptions,
    )

    /** Per-recording snapshot: changing settings while dictating cannot change that result. */
    private data class RecordingOptions(
        val language: DictationLanguage,
        val asrMode: DictationAsrMode,
        val cloudCleanupEnabled: Boolean,
        val cloudSuppressedForSensitiveTarget: Boolean,
        val cloudModel: CuratedCloudModel,
        val format: PostProcessingFormat = PostProcessingFormats.builtins.first(),
    )

    private fun processStoppedRecording(capture: RecordingCapture) {
        val run = capture.run
        if (run.cancellation.isCancelled) {
            return
        }
        if (capture.pcm.isEmpty()) capture.session?.cancel()
        val t0 = System.currentTimeMillis()
        val r = if (capture.pcm.isEmpty()) TranscriptionEngine.Result(null) else runCatching {
            capture.session?.finish(capture.pcm)
                ?: TranscriptionEngine.Result(null, "Transcription locale indisponible.")
        }.getOrElse { TranscriptionEngine.Result(null, "Transcription locale indisponible.") }
        val transcribeMs = System.currentTimeMillis() - t0
        awaitSessionExit(run)
        if (run.cancellation.isCancelled) {
            return
        }
        val localText = editableTranscript.resolveFinal(r.text?.let { Vocabulary.applyCorrections(this, it) })
        if (run.cancellation.isCancelled) {
            return
        }
        val cloudText = if (!localText.isNullOrBlank() && capture.options.cloudCleanupEnabled) {
            val credential = SecureCredentialStore(this).load()
            credential?.let {
                CloudCleanup().clean(
                    localText,
                    capture.options.language,
                    capture.options.cloudModel,
                    it,
                    run.cancellation,
                    capture.options.format.instructions,
                )
            }
        } else null
        if (run.cancellation.isCancelled) {
            return
        }
        if (capture.options.format.instructions.isNotBlank() && cloudText == null && !localText.isNullOrBlank()) {
            toast("Mise en forme indisponible : texte conservé sans format.")
        }
        var finalText = cloudText ?: localText
        if (!finalText.isNullOrBlank() && prefs.trailingSpace) finalText += " "
        val outText = finalText
        val source = if (capture.options.asrMode == DictationAsrMode.STREAMING) "stream" else "batch"
        Log.i(TAG, "event=transcription source=$source outcome=${if (outText.isNullOrBlank()) "empty_or_failure" else "success"} elapsedMs=$transcribeMs")
        main.post {
            if (!isCurrentRun(run) || localEngineLifecycle.isDestroyed()) return@post
            run.finalPublication.submit(SystemClock.uptimeMillis()) {
                val published = run.cancellation.publishIfActive {
                    if (!outText.isNullOrBlank()) {
                        val result = runCatching {
                            injectOrCopy(
                                controller = InjectionGateway.current(),
                                text = outText,
                                copyToClipboard = { DictationClipboard.copy(this, it) },
                            )
                        }.getOrElse {
                            Log.w(TAG, "event=injection outcome=failure type=${it.javaClass.simpleName}")
                            InjectionResult.Failed
                        }
                        injectionFeedbackMessage(result)?.let(::toast)
                    } else if (capture.options.asrMode != DictationAsrMode.STREAMING || r.error != null) {
                        toast("Erreur: ${r.error ?: "vide"}")
                    }
                }
                if (published) completeRunOnMain(run)
            }
        }
    }

    /** Annule l'enregistrement en cours SANS transcrire (ex. 2e tap d'un double-tap). */
    private fun cancelRec(showFeedback: Boolean = true) {
        if (state != State.RECORDING) return
        val run = activeRun ?: return
        run.captureGate.pause()
        // CANCELLING blocks a second AudioRecord until the current reader has fully exited.
        run.completion.markWorkerStarted()
        if (!requestCancellation(run, showFeedback)) {
            run.completion.markWorkerDone()
            return
        }
        val recorder = audioRecord
        val recordingThread = recordThread
        val coordinator = RecordingStopCoordinator(
            recordThread = recordingThread,
            stopRecorder = { recorder?.stop() },
            releaseRecorder = { recorder?.release() },
            snapshot = {
                if (audioRecord === recorder) audioRecord = null
                if (recordThread === recordingThread) recordThread = null
                // Discard only once AudioRecord.read() can no longer write to this session.
                asrSession = null
                pcm = null
                recordingOptions = null
            },
        )
        thread(name = "dictai-cancel-rec") {
            try {
                when (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS)) {
                    RecordingStopCoordinator.Result.Stopped -> awaitSessionExit(run)
                    RecordingStopCoordinator.Result.TimedOut -> {
                        Log.w(TAG, "event=audio_cancel outcome=timeout")
                        coordinator.awaitExitThenRelease()
                        awaitSessionExit(run)
                    }
                }
            } finally {
                run.completion.markWorkerDone()
            }
        }
    }

    private fun cancelProcessing() {
        if (state != State.TRANSCRIBING) return
        activeRun?.let(::requestCancellation)
    }

    private fun handleTapDecision(
        decision: DictationTapGestureCoordinator.Decision,
        atMs: Long,
    ) {
        when (decision.action) {
            DictationTapGestureCoordinator.Action.START_RECORDING -> startRec()
            DictationTapGestureCoordinator.Action.STOP_RECORDING -> stopRec()
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING -> cancelRec()
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING_AND_OPEN_APP -> {
                cancelRec()
                openApp()
            }
            DictationTapGestureCoordinator.Action.ARM_PROCESSING_WINDOW -> {
                activeRun?.let { armFinalPublicationWindow(it, atMs) }
            }
            DictationTapGestureCoordinator.Action.CANCEL_PROCESSING -> cancelProcessing()
            DictationTapGestureCoordinator.Action.PROMPT_MIC_SETUP_AND_OPEN_APP -> {
                toast("Ouvre WhisperPin pour activer le micro")
                openApp()
            }
            DictationTapGestureCoordinator.Action.NONE -> Unit
        }

        decision.timeout?.let { timeout ->
            main.postAtTime(
                {
                    // A Handler callback can be delivered a fraction early on a
                    // busy looper; resolving at the advertised deadline keeps
                    // the single-tap contract deterministic.
                    val now = SystemClock.uptimeMillis().coerceAtLeast(timeout.deadlineMs)
                    handleTapDecision(tapCoordinator.onTimeout(timeout, now), now)
                },
                timeout.deadlineMs,
            )
        }
    }

    private fun requestCancellation(run: ActiveDictationRun, showFeedback: Boolean = true): Boolean {
        if (!isCurrentRun(run)) return false
        run.finalPublication.cancel()
        if (!run.cancellation.cancel()) return false
        setLivePreviewVisible(false)
        setState(State.CANCELLING)
        if (showFeedback) toast("Dictée annulée")
        awaitCancellationCompletion(run)
        return true
    }

    private fun awaitSessionExit(run: ActiveDictationRun) {
        while (!localEngineLifecycle.isDestroyed() && !run.session.cancelAndAwait()) {
            // A cancellation timeout keeps the run busy; retry until the native worker exits.
        }
    }

    private fun awaitCancellationCompletion(run: ActiveDictationRun) {
        if (!run.cancellationWaitStarted.compareAndSet(false, true)) return
        thread(name = "dictai-await-cancel") {
            run.completion.awaitWorkerIfStarted()
            awaitSessionExit(run)
            if (!localEngineLifecycle.isDestroyed()) main.post { completeRunOnMain(run) }
        }
    }

    /** Only the current run may release the busy state; an old worker cannot reset a newer run. */
    private fun completeRunOnMain(run: ActiveDictationRun) {
        if (localEngineLifecycle.isDestroyed() || activeRun !== run) return
        activeRun = null
        if (asrSession === run.session) asrSession = null
        tapCoordinator.reset()
        setLivePreviewVisible(false)
        setState(State.IDLE)
    }

    private fun isCurrentRun(run: ActiveDictationRun): Boolean = activeRun === run

    private fun armFinalPublicationWindow(run: ActiveDictationRun, atMs: Long) {
        val window = run.finalPublication.armProcessingTap(atMs)
        main.postAtTime(
            {
                if (isCurrentRun(run) && !localEngineLifecycle.isDestroyed()) {
                    run.finalPublication.release(window, SystemClock.uptimeMillis())
                }
            },
            window.deadlineMs,
        )
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
            val paused = s == State.PAUSED || s == State.PAUSING
            pauseIndicator?.visibility = if (paused) View.VISIBLE else View.GONE
            wave?.visibility = if (paused) View.INVISIBLE else View.VISIBLE
            pill?.contentDescription = when (s) {
                State.PAUSED -> "Dictée en pause. Appuyer pour reprendre."
                State.PAUSING -> "Mise en pause de la dictée."
                State.RECORDING -> "Dictée en cours. Glisser brièvement vers le bas pour mettre en pause."
                else -> "Dicter"
            }
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
        val run = activeRun ?: return
        main.post { renderLivePreview(run, committed, tentative) }
    }

    private fun renderLivePreview(run: ActiveDictationRun, committed: String, tentative: String) {
        if (!isCurrentRun(run) || localEngineLifecycle.isDestroyed()) return
        val text = listOf(committed.trim(), tentative.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isBlank()) return
        run.pendingPreview = committed to tentative
        DictationPreviewPublicationGate.publishIfAllowed(
            isCurrentRun = isCurrentRun(run),
            isRecording = state == State.RECORDING,
            cancellation = run.cancellation,
        ) {
            val display = editableTranscript.update(text)
            val editor = liveText ?: return@publishIfAllowed
            val old = editor.text.toString()
            if (old != display) {
                updatingLiveText = true
                try {
                    // Preserve unchanged spans; automatic transcription always follows the new tail.
                    val prefix = old.commonPrefixWith(display).length
                    val suffix = old.drop(prefix).commonSuffixWith(display.drop(prefix)).length
                    editor.text.replace(prefix, old.length - suffix, display.substring(prefix, display.length - suffix))
                } finally { updatingLiveText = false }
            }
            setLivePreviewVisible(true)
            scrollTranscriptToEnd(run)
        }
    }

    private fun scrollTranscriptToEnd(run: ActiveDictationRun) {
        val editor = liveText ?: return
        if (!isCurrentRun(run) || state != State.RECORDING) return
        editor.setSelection(editor.length())
        editor.doOnLayout {
            if (isCurrentRun(run) && state == State.RECORDING) {
                editor.setSelection(editor.length())
                livePanel?.let { panel ->
                    panel.scrollTo(0, (editor.bottom + panel.paddingBottom - panel.height).coerceAtLeast(0))
                }
            }
        }
    }

    private fun setLivePreviewVisible(show: Boolean) {
        val panel = livePanel ?: return
        if (livePreviewVisible == show && panel.visibility == if (show) View.VISIBLE else View.GONE) return
        livePreviewVisible = show
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(panel.windowToken, 0)
            liveText?.clearFocus()
            liveParams?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE }
            if (livePanelAdded) runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, liveParams)
            }
            return
        }

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
            pauseIndicator = TextView(this@OverlayService).apply {
                text = "Ⅱ"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(0xFFD9A441.toInt())
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(pauseIndicator, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        val gestureHint = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF76561B.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(0xF2FFFFFF.toInt())
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        pillView.addView(gestureHint, FrameLayout.LayoutParams(-1, -1))

        val liveView = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(0xFFF4F4F4.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            hint = "Touchez pour corriger pendant la dictée"
            setHintTextColor(0xFFBBBBBB.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!updatingLiveText && isTranscriptEditable()) editableTranscript.edit(s.toString())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && isTranscriptEditable()) {
                    liveParams?.let { layout ->
                        layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(this@OverlayService.livePanel, layout)
                    }
                    requestFocus()
                    post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                }
                false
            }
            textSize = 14f
            setTextColor(0xFFF4F4F4.toInt())
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.96f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        var downX = 0; var downY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        var pttFired = false
        var touchInterrupted = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val formatGesture = VerticalSwipeGesture(touchSlop, maxOf(56 * dp, 3 * touchSlop))
        val pauseGesture = VerticalSwipeGesture(
            touchSlop, maxOf(56 * dp, 3 * touchSlop), maxDurationMs = 600,
            direction = VerticalSwipeGesture.Direction.DOWN,
        )
        val longPress = Runnable {
            // Maintenu 250ms, pas bougé, toujours IDLE → push-to-talk
            if (!moved && state == State.IDLE) {
                formatGesture.cancel()
                startRec()
                pttFired = state == State.RECORDING
            }
        }
        var returnAnimation: ValueAnimator? = null
        var gestureReady = false
        var latestDx = 0f
        var latestDy = 0f
        fun hideGestureHint() {
            gestureHint.visibility = View.GONE
            gestureReady = false
        }
        fun previewGesture(eventTime: Long) {
            val up = formatGesture.progress(latestDx, latestDy, eventTime)
            val down = pauseGesture.progress(latestDx, latestDy, eventTime)
            val progress = maxOf(up, down)
            gestureHint.visibility = if (progress > 0f) View.VISIBLE else View.GONE
            gestureHint.text = if (up > 0f) "↑ Format" else "↓ Pause"
            gestureHint.alpha = 0.35f + 0.65f * progress
            val ready = progress >= 1f
            if (ready && !gestureReady) pillView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            gestureReady = ready
        }
        val expireGestureHint = Runnable { previewGesture(SystemClock.uptimeMillis()) }
        fun returnToOrigin() {
            val fromX = lp.x
            val fromY = lp.y
            returnAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    if (container !== pillView) { cancel(); return@addUpdateListener }
                    val fraction = it.animatedValue as Float
                    lp.x = (fromX + (downX - fromX) * fraction).toInt()
                    lp.y = (fromY + (downY - fromY) * fraction).toInt()
                    updatePillLayout()
                }
                start()
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

        fun updateDrag(dx: Float, dy: Float) {
            if (pttFired || (!moved && abs(dx) + abs(dy) <= touchSlop)) return
            moved = true
            tapCoordinator.reset()
            main.removeCallbacks(longPress)
            val clamped = OverlayPlacement.clampPill(
                Point((downX + dx).toInt(), (downY + dy).toInt()),
                Rect(0, 0, lp.width, lp.height), screenRect(),
            )
            lp.x = clamped.x
            lp.y = clamped.y
            val dragAnchor = OverlayPlacement.snap(
                Point(lp.x, lp.y), Rect(0, 0, lp.width, lp.height), screenRect(),
            )
            updatePillLayout(dragAnchor)
        }

        pillView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    returnAnimation?.cancel()
                    main.removeCallbacks(expireGestureHint)
                    hideGestureHint()
                    latestDx = 0f; latestDy = 0f
                    downX = lp.x; downY = lp.y; touchX = ev.rawX; touchY = ev.rawY
                    moved = false; pttFired = false; touchInterrupted = false
                    formatGesture.begin(ev.eventTime, state == State.IDLE || state == State.MIC_UNARMED)
                    pauseGesture.begin(ev.eventTime, state == State.RECORDING)
                    wake()
                    main.postDelayed(expireGestureHint, 601)
                    if (state == State.IDLE) main.postDelayed(longPress, 250)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    touchInterrupted = true
                    main.removeCallbacks(expireGestureHint)
                    hideGestureHint()
                    formatGesture.cancel()
                    pauseGesture.cancel()
                    main.removeCallbacks(longPress)
                    tapCoordinator.reset()
                    if (moved) finishDrag()
                    moved = false
                    if (pttFired && state == State.RECORDING) cancelRec()
                    pttFired = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchInterrupted) return@setOnTouchListener true
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    latestDx = dx; latestDy = dy
                    previewGesture(ev.eventTime)
                    updateDrag(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    main.removeCallbacks(expireGestureHint)
                    hideGestureHint()
                    if (touchInterrupted) return@setOnTouchListener true
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    // Include the final coordinates, even when Android delivered few MOVE events.
                    updateDrag(dx, dy)
                    val selectFormat = formatGesture.release(dx, dy, ev.eventTime)
                    val pauseRecording = pauseGesture.release(dx, dy, ev.eventTime)
                    if (pauseRecording && state == State.RECORDING) {
                        returnToOrigin()
                        pauseRec()
                    } else if (!pttFired && selectFormat && (state == State.IDLE || state == State.MIC_UNARMED)) {
                        tapCoordinator.reset()
                        // A shortcut keeps the original placement; only a completed drag saves it.
                        returnToOrigin()
                        showFormatPicker()
                    } else if (pttFired) {
                        tapCoordinator.reset()
                        if (state == State.RECORDING) stopRec()
                    } else if (moved) {
                        finishDrag()
                    } else if (state == State.PAUSED || state == State.PAUSING) {
                        resumeRec()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val surfaceState = when (state) {
                            State.IDLE -> DictationTapGestureCoordinator.SurfaceState.IDLE
                            State.RECORDING -> DictationTapGestureCoordinator.SurfaceState.RECORDING
                            State.TRANSCRIBING -> DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING
                            State.CANCELLING, State.PAUSING, State.PAUSED -> DictationTapGestureCoordinator.SurfaceState.CANCELLING
                            State.MIC_UNARMED -> DictationTapGestureCoordinator.SurfaceState.MIC_UNARMED
                        }
                        handleTapDecision(tapCoordinator.onTap(surfaceState, now), now)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(expireGestureHint)
                    hideGestureHint()
                    main.removeCallbacks(longPress)
                    formatGesture.cancel()
                    pauseGesture.cancel()
                    if (moved) finishDrag()
                    if (pttFired) {
                        tapCoordinator.reset()
                        if (state == State.RECORDING) cancelRec()
                    }
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

    private fun showFormatPicker() {
        if (formatDialog?.isShowing == true) return
        val store = PostProcessingFormats(this)
        val formats = store.all()
        val selected = store.selected()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Format de la dictée")
            .setSingleChoiceItems(formats.map { it.name }.toTypedArray(), formats.indexOfFirst { it.id == selected.id }) { dialog, index ->
                val format = formats[index]
                store.select(format)
                dialog.dismiss()
                toast(if (prefs.cloudCleanupEnabled) "Format sélectionné : ${format.name}" else "Activez le nettoyage cloud dans les réglages pour appliquer ce format.")
            }
            .setNegativeButton("Fermer", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener { formatDialog = null }
        formatDialog = dialog
        dialog.show()
    }

    private fun openApp() = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun toast(s: String) { main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } }

    private class AndroidRecordingRecorder(
        val audioRecord: AudioRecord,
    ) : RecordingRecorder {
        override val isInitialized: Boolean
            get() = audioRecord.state == AudioRecord.STATE_INITIALIZED

        override val isRecording: Boolean
            get() = audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING

        override fun startRecording() = audioRecord.startRecording()
        override fun stop() = audioRecord.stop()
        override fun release() = audioRecord.release()
    }

    override fun onDestroy() {
        formatDialog?.dismiss()
        micArmed = false
        state = State.IDLE
        tapCoordinator.reset()
        val runToCancel = activeRun
        runToCancel?.captureGate?.pause()
        runToCancel?.finalPublication?.cancel()
        runToCancel?.cancellation?.cancel()
        activeRun = null
        val recorderToRelease = audioRecord
        val recordingThreadToJoin = recordThread
        try { recorderToRelease?.stop() } catch (_: Throwable) {}
        audioRecord = null
        val releaseResident = localEngineLifecycle.destroy {
            asrEngine = null
            loadedModelName = null
        }
        val sessionToCancel = asrSession ?: runToCancel?.session
        asrSession = null
        if (releaseResident) {
            dispatchResidentClose(close = {
                joinUninterruptibly(recordingThreadToJoin)
                joinUninterruptibly(runToCancel?.pauseWorker)
                runToCancel?.completion?.awaitWorkerIfStarted()
                try { recorderToRelease?.release() } catch (_: Throwable) {}
                while (sessionToCancel != null && !sessionToCancel.cancelAndAwait()) {
                    // Keep the resident engine alive until the cancelled native session really exits.
                }
                residentAsrEngine.close()
            })
        } else {
            sessionToCancel?.cancel()
            try { recorderToRelease?.release() } catch (_: Throwable) {}
        }
        main.removeCallbacksAndMessages(null)
        try { wave?.stop() } catch (_: Exception) {}
        try { loader?.stop() } catch (_: Exception) {}
        try { if (livePanelAdded) livePanel?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        try { container?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        livePanelAdded = false
        container = null; pill = null; wave = null; loader = null; pauseIndicator = null; liveText = null; livePanel = null; liveParams = null
        super.onDestroy()
    }

    private fun joinUninterruptibly(thread: Thread?) {
        var interrupted = false
        while (thread?.isAlive == true) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
