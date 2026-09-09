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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.BaseInputConnection
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
        const val ACTION_OPEN_NOTES = "com.uhama.whisperpin.OPEN_NOTES"
        const val ACTION_ARM_MIC = "com.uhama.whisperpin.ARM_MIC"
        const val ACTION_PREPARE_LOCAL_FORMAT = "com.uhama.whisperpin.PREPARE_LOCAL_FORMAT"
        private const val DOUBLE_TAP_MS = 280L
        private const val RECORD_STOP_TIMEOUT_MS = 1_000L
        @Volatile var micArmed = false
            private set
    }

    private enum class State { IDLE, RECORDING, PAUSING, PAUSED, TRANSCRIBING, CANCELLING, MIC_UNARMED }

    private class ActiveDictationRun(
        val session: DictationAsrSession,
        val formatOptions: RecordingOptions,
        val cancellation: DictationCancellationCoordinator = DictationCancellationCoordinator(),
    ) {
        val captureGate = RecordingCaptureGate()
        @Volatile var pauseWorker: Thread? = null
        var resumeAfterPause = false
        var archiveAsNote = false
        var finishAfterPause = false
        var pendingPreview: Pair<String, String>? = null
        val finalPublication = DictationFinalPublicationGate(DOUBLE_TAP_MS)
        val completion = DictationRunCompletionGate()
        val cancellationWaitStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        var localFormatting: LocalFormattingSession? = null
        var formatOffer: Runnable? = null
        var formatOfferRequest: LocalFormatRequest? = null
        var stoppedAtMs = 0L
        var firstFormatVisible = false
        var formatStage: String? = null
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
    private var liveEditorChanging = false
    private val vocabularyTracker = VocabularyCorrectionTracker()
    private var vocabularySuggestion: VocabularyCorrectionTracker.Suggestion? = null
    private var vocabularyBanner: LinearLayout? = null
    private var vocabularySuggestionText: TextView? = null
    private var vocabularyOffer: Runnable? = null
    private val editableTranscript = EditableTranscript()
    private val localFormatter by lazy { LocalFormatEngine(this) }
    private var formatDialog: AlertDialog? = null
    private var livePanel: FrameLayout? = null
    private var liveScroll: ScrollView? = null
    private var tailFollower: TranscriptTailFollower? = null
    private var panelTitle: TextView? = null
    private var panelFormat: TextView? = null
    private var panelExpandButton: ImageButton? = null
    private var keyboardInset = 0
    private var panelExpanded = false
    private var panelHidden = false
    private val draftStore by lazy { DictationDraftStore(this) }
    private var recoveredDraft: String? = null
    private val notes by lazy { TranscriptNotes(AndroidTranscriptNoteStorage(this)) }
    private var activeNoteId: String? = null
    private var floatingMenu: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var liveParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcm: java.io.ByteArrayOutputStream? = null
    @Volatile private var asrEngine: DictationAsrEngine? = null
    @Volatile private var asrSession: DictationAsrSession? = null
    @Volatile private var activeRun: ActiveDictationRun? = null
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
        recoveredDraft = draftStore.load()
        activeNoteId = draftStore.noteId?.takeIf { notes.get(it) != null }
        recoveredDraft?.let { text ->
            editableTranscript.edit(text)
            updatingLiveText = true
            liveText?.setText(text)
            updatingLiveText = false
            panelHidden = !prefs.showTranscript
            setState(State.PAUSED)
            setLivePreviewVisible(true)
        }
        ensureLocalLoaded()
        warmLocalFormatter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM_MIC) {
            promoteMic()
            // Si le modèle local n'était pas dispo au démarrage (pas encore téléchargé),
            // on retente de le charger (un seul chargement à la fois, cf. ensureLocalLoaded).
            ensureLocalLoaded()
            warmLocalFormatter()
        }
        if (intent?.action == ACTION_PREPARE_LOCAL_FORMAT) warmLocalFormatter()
        if (intent?.action == ACTION_OPEN_NOTES) archiveOrShowNotes()
        return START_STICKY
    }

    private fun warmLocalFormatter() {
        if (BuildConfig.LOCAL_FORMAT_PROTOTYPE && prefs.formattingEngine == "local" &&
            GemmaModelStore(this).installedModel() != null) localFormatter.warm()
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
            setState(if (recoveredDraft != null) State.PAUSED else State.IDLE)
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
        dismissFloatingMenu()
        val requestedAt = SystemClock.uptimeMillis()
        val cloudRequested = prefs.formattingEngine == "cloud" && prefs.cloudCleanupEnabled
        val targetSensitive = cloudRequested && runCatching {
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
            localFormattingEnabled = prefs.formattingEngine == "local",
            numberStyle = prefs.numberStyle,
            lightTextCleanup = prefs.lightTextCleanup,
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
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(bufSize, SAMPLE_RATE * 2),
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
        val run = ActiveDictationRun(started.session, options)
        if (options.localFormattingEnabled && options.format.id in setOf("list", "email")) {
            localFormatter.warm()
            run.localFormatting = LocalFormattingSession(localFormatter.backend())
            run.cancellation.onCancel { run.localFormatting?.close() }
        }
        run.cancellation.onCancel { started.session.cancel() }
        val ar = recorder.audioRecord
        val recordingPcm = java.io.ByteArrayOutputStream()
        try {
            audioRecord = ar
            pcm = recordingPcm
            asrSession = started.session
            activeRun = run
            val restored = recoveredDraft
            resetVocabularyLearning()
            tailFollower?.reset()
            editableTranscript.clear()
            if (restored != null) editableTranscript.edit(restored)
            updatingLiveText = true
            liveText?.setText(restored.orEmpty())
            updatingLiveText = false
            liveText?.isEnabled = true
            liveText?.hint = "Écoute en cours…"
            if (restored == null) { panelHidden = !prefs.showTranscript; panelExpanded = false }
            recoveredDraft = null
            persistDraft(liveText?.text?.toString().orEmpty())
            setState(State.RECORDING)
            setLivePreviewVisible(true)
            vibrate(20)
            launchAudioReader(run, ar, recordingPcm, bufSize)
            Log.i(TAG, "event=audio_start outcome=ready elapsed_ms=${SystemClock.uptimeMillis() - requestedAt}")
        } catch (t: Throwable) {
            if (asrSession === started.session) asrSession = null
            if (audioRecord === ar) audioRecord = null
            recordThread = null
            pcm = null
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
            // Read 20 ms at a time; the recorder keeps its larger hardware buffer.
            val buf = ByteArray(minOf(bufferSize, SAMPLE_RATE / 50 * 2))
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
        persistDraft(liveText?.text?.toString().orEmpty())
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
                if (run.archiveAsNote || run.finishAfterPause) {
                    stopRec()
                } else if (run.resumeAfterPause) {
                    run.resumeAfterPause = false
                    resumeRec()
                }
            }
        }, "dictai-pause-rec")
        run.pauseWorker = worker
        worker.start()
    }

    private fun resumeRec() {
        if (state == State.PAUSED && activeRun == null && recoveredDraft != null) {
            if (!micArmed) { toast("Ouvrez l’application pour réactiver le micro et reprendre le brouillon."); openApp(); return }
            startRec()
            return
        }
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
        resetVocabularyLearning()
        releaseTranscriptFocus()
        activeRun?.let { run ->
            run.stoppedAtMs = SystemClock.elapsedRealtime()
            run.formatOffer?.let(main::removeCallbacks)
            run.formatOffer = null
            run.formatOfferRequest = null
        }
        setLivePreviewVisible(run.localFormatting != null)
        setState(State.TRANSCRIBING)
        run.formatStage = "Transcription…"
        currentAnchor?.let(::positionLivePanel)
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
                    options = run.formatOptions,
                )
                pcm = null
                asrSession = null
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
                            run.formatOptions,
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
        val localFormattingEnabled: Boolean = false,
        val numberStyle: NumberStyle = NumberStyle.DIGITS,
        val lightTextCleanup: Boolean = true,
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
        val resolvedText = editableTranscript.resolveFinal(r.text?.let { normalizeRecognizedText(it, capture.options) })
        val formatStarted = SystemClock.elapsedRealtime()
        val lightCleanupApplied = !resolvedText.isNullOrBlank() && capture.options.lightTextCleanup &&
            capture.options.format.id == "cleanup" && !editableTranscript.hasUserEdits()
        val localText = if (lightCleanupApplied) LightTextCleanup.apply(resolvedText!!, protectedVocabularyTerms(resolvedText)) else resolvedText
        if (run.cancellation.isCancelled) {
            return
        }
        if (run.archiveAsNote) {
            main.post {
                if (!isCurrentRun(run) || run.cancellation.isCancelled || localEngineLifecycle.isDestroyed()) return@post
                val text = localText ?: liveText?.text?.toString().orEmpty()
                val saved = notes.save(activeNoteId, text)
                completeRunOnMain(run)
                toast("Note enregistrée : ${saved.title}")
                showNotesOverlay()
            }
            return
        }
        var localDirect = false
        var localDiagnostic: LocalFinishDiagnostic? = null
        val localFormatted = if (!localText.isNullOrBlank() && capture.options.localFormattingEnabled &&
            capture.options.format.instructions.isNotBlank()) {
            val request = localFormatRequest(localText, capture.options, applyVocabulary = false)
            localDirect = request.directOutput() != null
            main.post {
                if (isCurrentRun(run) && !run.cancellation.isCancelled) {
                    run.formatStage = when {
                        run.localFormatting == null -> "Non disponible en local"
                        localDirect -> "Traitement local rapide…"
                        else -> "Gemma en cours…"
                    }
                    currentAnchor?.let(::positionLivePanel)
                }
            }
            val session = run.localFormatting
            session?.finish(request, 5_000L) { chunk ->
                val preview = request.previewOutput(chunk)
                if (preview != null) main.post {
                    if (isCurrentRun(run) && state == State.TRANSCRIBING && !run.cancellation.isCancelled) {
                        updatingLiveText = true
                        try { liveText?.setText(preview) } finally { updatingLiveText = false }
                        setLivePreviewVisible(true)
                        if (!run.firstFormatVisible && livePreviewVisible && liveText?.isShown == true) {
                            run.firstFormatVisible = true
                            Log.i(TAG, "event=format_first_visible stop_to_visible_ms=${SystemClock.elapsedRealtime() - run.stoppedAtMs}")
                        }
                    }
                }
            }.also { localDiagnostic = session?.lastFinish }
        } else null
        val cloudText = if (!capture.options.localFormattingEnabled && !localText.isNullOrBlank() && capture.options.cloudCleanupEnabled &&
            (!editableTranscript.hasUserEdits() || capture.options.format.instructions.isNotBlank())) {
            val credential = SecureCredentialStore(this).load()
            credential?.let {
                main.post {
                    if (isCurrentRun(run) && !run.cancellation.isCancelled) {
                        run.formatStage = "Cloud en cours…"
                        currentAnchor?.let(::positionLivePanel)
                    }
                }
                val request = localFormatRequest(localText, capture.options, applyVocabulary = false)
                request.acceptOutput(CloudCleanup().clean(
                    localText,
                    capture.options.language,
                    capture.options.cloudModel,
                    it,
                    run.cancellation,
                    request.instructions + if (request.protectedTerms.isEmpty()) "" else
                        " Preserve these spellings exactly: ${request.protectedTerms.joinToString(", ")}",
                ))
            }
        } else null
        if (run.cancellation.isCancelled) {
            return
        }
        val formatted = localFormatted ?: cloudText
        val formatOutcome = when {
            localFormatted != null && localDirect -> "Local appliqué · sans appel LLM"
            localFormatted != null -> "LLM local appliqué"
            cloudText != null -> "Cloud appliqué"
            capture.options.format.instructions.isNotBlank() -> "Format indisponible · texte conservé"
            else -> "Sans appel LLM"
        }
        main.post {
            if (isCurrentRun(run) && !run.cancellation.isCancelled) {
                run.formatStage = formatOutcome
                currentAnchor?.let(::positionLivePanel)
                if (localFormatted != null) toast("${capture.options.format.name} : $formatOutcome")
            }
        }
        if (capture.options.format.instructions.isNotBlank() && formatted == null && !localText.isNullOrBlank()) {
            toast("Mise en forme indisponible : texte conservé sans format.")
        }
        run.localFormatting?.close()
        val postprocessMs = SystemClock.elapsedRealtime() - formatStarted
        val localRuntime = if (capture.options.localFormattingEnabled) localFormatter.runtimeName() else "not-loaded"
        Log.i(TAG, "event=postprocess engine=${if (capture.options.localFormattingEnabled) "local" else "cloud_or_off"} " +
            "outcome=${if (formatted != null) "formatted" else "original"} elapsed_ms=${SystemClock.elapsedRealtime() - formatStarted}")
        // Local layout already starts from normalized source; do not rewrite it after validation.
        var finalText = localFormatted ?: cloudText?.let {
            NumberFormatting.apply(it, capture.options.language, capture.options.numberStyle, protectedVocabularyTerms(it))
        } ?: localText
        if (!finalText.isNullOrBlank() && !editableTranscript.hasUserEdits()) {
            finalText = FinalPunctuation.apply(finalText, capture.options.format.id)
        }
        if (!finalText.isNullOrBlank() && prefs.trailingSpace) finalText += " "
        val outText = finalText
        val source = if (capture.options.asrMode == DictationAsrMode.STREAMING) "stream" else "batch"
        Log.i(TAG, "event=transcription source=$source outcome=${if (outText.isNullOrBlank()) "empty_or_failure" else "success"} elapsedMs=$transcribeMs")
        main.post {
            if (!isCurrentRun(run) || localEngineLifecycle.isDestroyed()) return@post
            run.finalPublication.submit(SystemClock.uptimeMillis()) {
                val published = run.cancellation.publishIfActive {
                    if (!outText.isNullOrBlank()) {
                        activeNoteId?.let { notes.save(it, outText) }
                        Log.i(TAG, "event=dictation_publish stop_to_text_ms=${if (run.stoppedAtMs > 0) SystemClock.elapsedRealtime() - run.stoppedAtMs else -1}")
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
                        runCatching {
                            val diagnostic = PostprocessingDiagnostic.report(
                                version = BuildConfig.VERSION_NAME,
                                timestampMs = System.currentTimeMillis(),
                                formatId = capture.options.format.id,
                                requested = when {
                                    capture.options.localFormattingEnabled -> PostprocessingDiagnostic.Requested.LOCAL
                                    capture.options.cloudCleanupEnabled || capture.options.cloudSuppressedForSensitiveTarget -> PostprocessingDiagnostic.Requested.CLOUD
                                    else -> PostprocessingDiagnostic.Requested.OFF
                                },
                                applied = when {
                                    localFormatted != null && localDirect -> PostprocessingDiagnostic.Applied.LOCAL_DIRECT
                                    localFormatted != null -> PostprocessingDiagnostic.Applied.LOCAL_LLM
                                    cloudText != null -> PostprocessingDiagnostic.Applied.CLOUD
                                    else -> PostprocessingDiagnostic.Applied.ORIGINAL
                                },
                                local = localDiagnostic,
                                runtime = localRuntime,
                                postprocessMs = postprocessMs,
                                stopToPublicationMs = run.stoppedAtMs.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it },
                                finalText = outText,
                                injection = result,
                                cloudSuppressed = capture.options.cloudSuppressedForSensitiveTarget,
                                modelLoadMs = if (capture.options.localFormattingEnabled) localFormatter.lastLoadMs() else null,
                                lightTextCleanup = lightCleanupApplied,
                            )
                            prefs.recordPostprocessingDiagnostic(diagnostic,
                                formatRequested = capture.options.format.instructions.isNotBlank())
                        }.onFailure {
                            Log.w(TAG, "event=postprocess_diagnostic outcome=unavailable type=${it.javaClass.simpleName}")
                        }
                        Log.i(TAG, "event=dictation_insert_complete stop_to_insert_ms=${if (run.stoppedAtMs > 0) SystemClock.elapsedRealtime() - run.stoppedAtMs else -1}")
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
            DictationTapGestureCoordinator.Action.RESUME_RECORDING -> resumeRec()
            DictationTapGestureCoordinator.Action.STOP_RECORDING -> stopRec()
            DictationTapGestureCoordinator.Action.CANCEL_RECORDING -> {
                if (state == State.PAUSED || state == State.PAUSING) cancelPausedNote() else cancelRec()
            }
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
            joinUninterruptibly(run.pauseWorker)
            run.completion.awaitWorkerIfStarted()
            awaitSessionExit(run)
            if (!localEngineLifecycle.isDestroyed()) main.post { completeRunOnMain(run) }
        }
    }

    /** Only the current run may release the busy state; an old worker cannot reset a newer run. */
    private fun completeRunOnMain(run: ActiveDictationRun) {
        if (localEngineLifecycle.isDestroyed() || activeRun !== run) return
        run.formatOffer?.let(main::removeCallbacks)
        run.formatOffer = null
        run.formatOfferRequest = null
        run.localFormatting?.close()
        activeRun = null
        recoveredDraft = null
        activeNoteId = null
        draftStore.clear()
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
        val render = Runnable {
            if (state != s) return@Runnable
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
                State.RECORDING -> "Dictée en cours. Glisser vers le bas pour mettre en pause. Maintenir jusqu’à la vibration pour déplacer."
                else -> "Appuyer pour dicter. Glisser vers le haut pour les formats. Maintenir jusqu’à la vibration pour déplacer."
            }
            showRecordingPill(s == State.RECORDING)
            // Bordure lumineuse pendant la transcription.
            if (s == State.TRANSCRIBING) loader?.start() else loader?.stop()
            updateNotif()
            currentAnchor?.let(::positionLivePanel)
            // Tant qu'une dictée est active, la pastille reste pleinement allumée (jamais de dim).
            if (s == State.IDLE || s == State.MIC_UNARMED) {
                setLivePreviewVisible(false)
                scheduleCollapse()
            }
            else { main.removeCallbacks(collapse); container?.animate()?.alpha(1f)?.setDuration(120)?.start() }
        }
        if (Looper.myLooper() == main.looper) render.run() else main.post(render)
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
            val display = editableTranscript.update(normalizeRecognizedText(text, run.formatOptions))
            scheduleLocalFormatting(run, display)
            persistDraft(display)
            val editor = liveText ?: return@publishIfAllowed
            if (display.isNotEmpty()) editor.hint = "Touchez pour corriger pendant la dictée"
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
        if (!isCurrentRun(run) || state != State.RECORDING) return
        tailFollower?.changed()
    }

    private fun localFormatRequest(text: String, options: RecordingOptions, applyVocabulary: Boolean = true): LocalFormatRequest {
        val source = if (applyVocabulary) Vocabulary.applyCorrections(this, text).trim() else text.trim()
        val spellings = protectedVocabularyTerms(source)
        val numbers = when (options.numberStyle) {
            NumberStyle.DIGITS -> " Write quantities with digits."
            NumberStyle.WORDS -> " Spell out quantities in the transcript language."
            NumberStyle.UNCHANGED -> " Preserve the original representation of numbers."
        }
        val layout = if (options.localFormattingEnabled) when (options.format.id) {
            "list" -> LocalLayoutKind.LIST
            "email" -> LocalLayoutKind.EMAIL
            else -> null
        } else null
        return LocalFormatRequest(source, options.format.instructions + numbers, options.language.cleanupLanguageName, spellings, layout,
            validation = if (layout != null) LocalFormatValidation.GEMMA_PROJECTION else LocalFormatValidation.EXACT_LAYOUT,
            simpleEmailLayout = true)
    }

    private fun protectedVocabularyTerms(text: String): List<String> = Vocabulary.corrections(this)
        .map { it.second }.filter { it.isNotBlank() && it in text }.distinct().take(64)

    private fun normalizeRecognizedText(text: String, options: RecordingOptions): String {
        val corrected = Vocabulary.applyCorrections(this, text)
        return NumberFormatting.apply(corrected, options.language, options.numberStyle, protectedVocabularyTerms(corrected))
    }

    private fun scheduleVocabularySuggestion(resetTimer: Boolean = true) {
        if (!resetTimer && vocabularyOffer != null) return
        vocabularyOffer?.let(main::removeCallbacks)
        if (!isTranscriptEditable()) return
        val offer = Runnable {
            vocabularyOffer = null
            val editor = liveText ?: return@Runnable
            if (!isTranscriptEditable()) return@Runnable
            val composing = BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            val suggestion = vocabularyTracker.suggestion(
                editor.text.toString(), editor.selectionStart, editor.selectionEnd,
                composing, SystemClock.elapsedRealtime(),
            )
            vocabularySuggestion = suggestion
            vocabularySuggestionText?.text = suggestion?.let { "Mémoriser « ${it.from} » → « ${it.to} »" }.orEmpty()
            setVocabularySuggestionVisible(suggestion != null)
            if (composing) scheduleVocabularySuggestion()
        }
        vocabularyOffer = offer
        main.postDelayed(offer, vocabularyTracker.settleDelayMillis)
    }

    private fun setVocabularySuggestionVisible(visible: Boolean) {
        val banner = vocabularyBanner ?: return
        val next = if (visible) View.VISIBLE else View.GONE
        if (banner.visibility == next) return
        banner.visibility = next
        val dp = resources.displayMetrics.density
        liveScroll?.let { scroll ->
            scroll.layoutParams = (scroll.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = ((72 + if (visible) 64 else 0) * dp).toInt()
            }
        }
        currentAnchor?.let(::positionLivePanel)
    }

    private fun resetVocabularyLearning() {
        vocabularyOffer?.let(main::removeCallbacks)
        vocabularyOffer = null
        vocabularyTracker.reset()
        vocabularySuggestion = null
        setVocabularySuggestionVisible(false)
    }

    private fun saveVocabularySuggestion() {
        val suggestion = vocabularySuggestion ?: return
        val editor = liveText ?: return
        val current = vocabularyTracker.suggestion(editor.text.toString(), editor.selectionStart, editor.selectionEnd,
            BaseInputConnection.getComposingSpanStart(editor.text) >= 0, SystemClock.elapsedRealtime())
        if (current != suggestion || !vocabularyTracker.consume(suggestion, editor.text.toString())) {
            resetVocabularyLearning()
            return
        }
        when (Vocabulary.addCorrection(this, suggestion.from, suggestion.to)) {
            Vocabulary.AddResult.ADDED -> toast("Correction enregistrée dans Mon vocabulaire.")
            Vocabulary.AddResult.ALREADY_PRESENT -> toast("Cette correction est déjà enregistrée.")
            Vocabulary.AddResult.CONFLICT -> toast("Une correction existe déjà pour ce mot. Modifiez-la dans Mon vocabulaire.")
            Vocabulary.AddResult.INVALID -> toast("Cette correction ne peut pas être enregistrée.")
        }
        resetVocabularyLearning()
    }

    /** Prepare at natural pauses; an ASR revision or a user edit invalidates the old source key. */
    private fun scheduleLocalFormatting(run: ActiveDictationRun, text: String) {
        val formatter = run.localFormatting ?: return
        val options = run.formatOptions
        val request = text.takeIf { it.isNotBlank() }?.let {
            localFormatRequest(it, options, applyVocabulary = false)
        }
        if (request == run.formatOfferRequest) return
        run.formatOffer?.let(main::removeCallbacks)
        run.formatOffer = null
        run.formatOfferRequest = request
        if (request == null) return
        val offer = Runnable {
            if (isCurrentRun(run) && isTranscriptEditable() && !run.cancellation.isCancelled) formatter.offer(request)
        }
        run.formatOffer = offer
        main.postDelayed(offer, 1000L)
    }

    private fun setLivePreviewVisible(requested: Boolean) {
        val show = requested && !panelHidden
        val panel = livePanel ?: return
        if (livePreviewVisible == show && panel.visibility == if (show) View.VISIBLE else View.GONE) return
        livePreviewVisible = show
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            releaseTranscriptFocus()
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

    private fun releaseTranscriptFocus() {
        val panel = livePanel ?: return
        keyboardInset = 0
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(panel.windowToken, 0)
        liveText?.clearFocus()
        liveParams?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE }
        if (livePanelAdded) runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, liveParams)
        }
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

    private fun localFormatStatus(): String = when (localFormatter.runtimeName()) {
        "litert-lm-gpu-mtp-thinking-off" -> "Gemma prêt"
        "loading" -> "Gemma se prépare…"
        "model-missing" -> "Gemma à installer"
        "gpu-error", "loading-timeout", "cancellation-pending" -> "Gemma indisponible"
        else -> "Gemma prévu"
    }

    private fun positionLivePanel(anchor: Anchor) {
        val panel = livePanel ?: return
        val panelParams = liveParams ?: return
        if (!livePanelAdded) return
        val dp = resources.displayMetrics.density
        val fullScreen = screenRect()
        val screen = fullScreen.copy(height = (fullScreen.height - keyboardInset).coerceAtLeast(1))
        panelTitle?.text = when (state) {
            State.RECORDING -> "Écoute en cours"
            State.PAUSING, State.PAUSED -> "En pause · ↑ pour envoyer"
            State.TRANSCRIBING -> "Traitement…"
            else -> notes.get(activeNoteId)?.title ?: "Dictée"
        }
        val options = activeRun?.formatOptions
        val format = options?.format ?: PostProcessingFormats(this).selected()
        val engine = activeRun?.formatStage ?: when {
            options?.cloudSuppressedForSensitiveTarget == true -> "Cloud suspendu"
            options?.cloudCleanupEnabled == true -> "Cloud prévu"
            options == null && prefs.formattingEngine == "cloud" && prefs.cloudCleanupEnabled -> "Cloud prévu"
            format.instructions.isBlank() -> "Sans LLM"
            options?.localFormattingEnabled == true -> if (format.id in setOf("list", "email")) localFormatStatus() else "Non disponible en local"
            options?.cloudSuppressedForSensitiveTarget == true -> "Cloud suspendu"
            options?.cloudCleanupEnabled == true -> "Cloud prévu"
            options != null -> "Désactivé"
            prefs.formattingEngine == "local" -> localFormatStatus()
            prefs.formattingEngine == "cloud" -> "Cloud prévu"
            else -> "Désactivé"
        }
        panelFormat?.text = "${if (format.id == "cleanup") "Texte" else format.name} · $engine"
        panelExpandButton?.setImageResource(if (panelExpanded) R.drawable.ic_panel_restore else R.drawable.ic_panel_expand)
        panelExpandButton?.contentDescription = if (panelExpanded) "Réduire le panneau" else "Agrandir le panneau"
        val bounds = OverlayPlacement.panelBounds(
            anchor.edge,
            pillRect(params ?: return).let { rect ->
                if (rect.bottom > screen.bottom) rect.copy(y = (screen.bottom - rect.height).coerceAtLeast(screen.y)) else rect
            }, screen,
            ((if (panelExpanded) 600 else 312) * dp).toInt(),
            if (panelExpanded) (screen.height * 0.82f).toInt() else (liveText?.lineHeight ?: 20) * 3 +
                ((112 + if (vocabularyBanner?.visibility == View.VISIBLE) 64 else 0) * dp).toInt(),
            (6 * dp).toInt(),
        )
        panelParams.x = bounds.x
        panelParams.y = bounds.y
        panelParams.width = bounds.width
        panelParams.height = bounds.height
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

        val liveView = object : EditText(this) {
            override fun onSelectionChanged(start: Int, end: Int) {
                super.onSelectionChanged(start, end)
                if (liveText === this && !updatingLiveText && !liveEditorChanging && isTranscriptEditable()) {
                    tailFollower?.userInteraction()
                    vocabularyTracker.onSelectionChanged(text.toString(), start, end)
                    vocabularySuggestion = null
                    setVocabularySuggestionVisible(false)
                    scheduleVocabularySuggestion()
                }
            }
        }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(0xFFF4F4F4.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            hint = "Touchez pour corriger pendant la dictée"
            setHintTextColor(0xFFBBBBBB.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    liveEditorChanging = true
                    if (!updatingLiveText && isTranscriptEditable()) {
                        tailFollower?.userInteraction()
                        vocabularyTracker.beforeChange(s.toString(), start, count, after, selectionStart, selectionEnd)
                        vocabularySuggestion = null
                        setVocabularySuggestionVisible(false)
                    }
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!updatingLiveText && isTranscriptEditable()) {
                        editableTranscript.edit(s.toString())
                        activeRun?.let { scheduleLocalFormatting(it, s.toString()) }
                        if (recoveredDraft != null) recoveredDraft = s.toString()
                        persistDraft(s.toString())
                    }
                }
                override fun afterTextChanged(s: Editable?) {
                    if (!updatingLiveText && isTranscriptEditable()) {
                        vocabularyTracker.afterChange(s.toString(), SystemClock.elapsedRealtime())
                    } else vocabularyTracker.onProgrammaticTextChanged(s.toString())
                    liveEditorChanging = false
                    scheduleVocabularySuggestion(resetTimer = !updatingLiveText)
                }
            })
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> tailFollower?.touch(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tailFollower?.touch(false)
                }
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
        val scroll = object : ScrollView(this) {
            override fun requestChildRectangleOnScreen(child: View, rectangle: android.graphics.Rect, immediate: Boolean): Boolean {
                if (tailFollower?.followsTail == true) return false
                return super.requestChildRectangleOnScreen(child, rectangle, immediate)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> tailFollower?.touch(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tailFollower?.touch(false)
                }
                false
            }
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (10 * dp).toInt())
            addView(liveView, FrameLayout.LayoutParams(-1, -2))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tailFollower?.resized() }
        }
        tailFollower = TranscriptTailFollower(liveView, scroll) { state == State.RECORDING && activeRun != null }
        val livePanel = FrameLayout(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(0xFF1F1F25.toInt())
                setStroke((1.2f * dp).toInt(), ThemeTokens.GREEN)
            }
            addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = (72 * dp).toInt() })
        }
        livePanel.setOnApplyWindowInsetsListener { _, insets ->
            val nextInset = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            if (keyboardInset != nextInset) {
                keyboardInset = nextInset
                currentAnchor?.let(::positionLivePanel)
            }
            insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        panelTitle = TextView(this).apply {
            text = "Dictée"; textSize = 13f; setTextColor(0xFFF4F4F4.toInt()); gravity = Gravity.CENTER_VERTICAL
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        toolbar.addView(panelTitle, LinearLayout.LayoutParams(0, -1, 1f))
        fun panelIcon(icon: Int, label: String, action: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x4477CC99), null, null)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { action() }
        }
        val expand = panelIcon(R.drawable.ic_panel_expand, "Agrandir le panneau") {
            panelExpanded = !panelExpanded
            currentAnchor?.let(::positionLivePanel)
        }
        panelExpandButton = expand
        val hide = panelIcon(R.drawable.ic_panel_hide, "Masquer le panneau sans arrêter la dictée") {
            panelHidden = true
            setLivePreviewVisible(false)
            toast("Glissez la pastille vers le haut pour revoir le texte.")
        }
        toolbar.addView(expand, LinearLayout.LayoutParams((48 * dp).toInt(), -1))
        toolbar.addView(hide, LinearLayout.LayoutParams((48 * dp).toInt(), -1))
        livePanel.addView(toolbar, FrameLayout.LayoutParams(-1, (48 * dp).toInt(), Gravity.TOP))
        panelFormat = TextView(this).apply {
            textSize = 11f; setTextColor(ThemeTokens.GREEN)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        livePanel.addView(panelFormat, FrameLayout.LayoutParams(-1, (24 * dp).toInt()).apply { topMargin = (48 * dp).toInt() })

        val vocabRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        vocabularySuggestionText = TextView(this).apply {
            textSize = 12f
            setTextColor(ThemeTokens.GREEN)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { saveVocabularySuggestion() }
        }
        vocabRow.addView(vocabularySuggestionText, LinearLayout.LayoutParams(0, -1, 1f))
        vocabRow.addView(TextView(this).apply {
            text = "×"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(0xFFBBBBBB.toInt())
            contentDescription = "Ignorer cette suggestion de vocabulaire"
            setOnClickListener { resetVocabularyLearning() }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), -1))
        vocabularyBanner = vocabRow
        livePanel.addView(vocabRow, FrameLayout.LayoutParams(-1, (64 * dp).toInt()).apply { topMargin = (72 * dp).toInt() })

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
        var touchInterrupted = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val gestureMode = PillGestureMode(touchSlop)
        val notesGesture = VerticalSwipeGesture(touchSlop, maxOf(24 * dp, 3 * touchSlop))
        val formatGesture = VerticalSwipeGesture(touchSlop, maxOf(56 * dp, 3 * touchSlop))
        val pauseGesture = VerticalSwipeGesture(
            touchSlop, maxOf(56 * dp, 3 * touchSlop),
            direction = VerticalSwipeGesture.Direction.DOWN,
        )
        var gestureReady = false
        fun hideGestureHint() {
            gestureHint.visibility = View.GONE
            gestureReady = false
        }
        fun previewGesture(dx: Float, dy: Float) {
            val up = formatGesture.progress(dx, dy)
            val down = pauseGesture.progress(dx, dy)
            val left = notesGesture.progress(dy, dx)
            val progress = maxOf(up, down, left)
            gestureHint.visibility = if (progress > 0f) View.VISIBLE else View.GONE
            gestureHint.text = if (left > 0f) "← Notes" else if (up > 0f) { if (state == State.PAUSED || state == State.PAUSING) "↑ Envoyer" else if (isTranscriptEditable()) "↑ Texte" else "↑ Format" } else "↓ Pause"
            gestureHint.alpha = 0.35f + 0.65f * progress
            val ready = progress >= 1f
            if (ready && !gestureReady) pillView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            gestureReady = ready
        }
        val longPress = Runnable {
            if (!touchInterrupted && gestureMode.hold()) {
                formatGesture.cancel()
                pauseGesture.cancel()
                notesGesture.cancel()
                tapCoordinator.reset()
                gestureHint.text = "↕ Déplacer"
                gestureHint.alpha = 1f
                gestureHint.visibility = View.VISIBLE
                vibrate(35)
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
            if (!moved && abs(dx) + abs(dy) <= touchSlop) return
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
                    dismissFloatingMenu()
                    hideGestureHint()
                    downX = lp.x; downY = lp.y; touchX = ev.rawX; touchY = ev.rawY
                    moved = false; touchInterrupted = false
                    gestureMode.begin()
                    notesGesture.begin(state == State.IDLE || state == State.MIC_UNARMED || isTranscriptEditable())
                    formatGesture.begin(state == State.IDLE || state == State.MIC_UNARMED || isTranscriptEditable())
                    pauseGesture.begin(state == State.RECORDING)
                    wake()
                    main.removeCallbacks(longPress)
                    main.postDelayed(longPress, 400)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    touchInterrupted = true
                    main.removeCallbacks(longPress)
                    hideGestureHint()
                    formatGesture.cancel()
                    pauseGesture.cancel()
                    notesGesture.cancel()
                    tapCoordinator.reset()
                    if (gestureMode.mode == PillGestureMode.Mode.DRAG && moved) finishDrag()
                    gestureMode.cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchInterrupted) return@setOnTouchListener true
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    gestureMode.move(dx, dy)
                    if (gestureMode.mode == PillGestureMode.Mode.DRAG) {
                        updateDrag(dx, dy)
                    } else if (gestureMode.mode == PillGestureMode.Mode.SHORTCUT) {
                        main.removeCallbacks(longPress)
                        tapCoordinator.reset()
                        previewGesture(dx, dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    hideGestureHint()
                    if (touchInterrupted) return@setOnTouchListener true
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    gestureMode.move(dx, dy)
                    if (gestureMode.mode == PillGestureMode.Mode.DRAG) {
                        updateDrag(dx, dy)
                        if (moved) finishDrag()
                    } else if (gestureMode.mode == PillGestureMode.Mode.SHORTCUT) {
                        tapCoordinator.reset()
                        val selectNotes = notesGesture.release(dy, dx)
                        val selectFormat = formatGesture.release(dx, dy)
                        val pauseRecording = pauseGesture.release(dx, dy)
                        if (selectNotes) {
                            archiveOrShowNotes()
                        } else if (pauseRecording && state == State.RECORDING) {
                            pauseRec()
                        } else if (selectFormat && (state == State.PAUSED || state == State.PAUSING)) {
                            val run = activeRun
                            if (run != null) {
                                run.resumeAfterPause = false
                                run.finishAfterPause = true
                                if (state == State.PAUSED) stopRec()
                            } else exportOpenNote()
                        } else if (selectFormat && isTranscriptEditable()) {
                            panelHidden = false
                            setLivePreviewVisible(true)
                        } else if (selectFormat && (state == State.IDLE || state == State.MIC_UNARMED)) {
                            showFormatPicker()
                        }
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val surfaceState = when (state) {
                            State.IDLE -> DictationTapGestureCoordinator.SurfaceState.IDLE
                            State.RECORDING -> DictationTapGestureCoordinator.SurfaceState.RECORDING
                            State.TRANSCRIBING -> DictationTapGestureCoordinator.SurfaceState.TRANSCRIBING
                            State.PAUSED, State.PAUSING -> DictationTapGestureCoordinator.SurfaceState.PAUSED
                            State.CANCELLING -> DictationTapGestureCoordinator.SurfaceState.CANCELLING
                            State.MIC_UNARMED -> DictationTapGestureCoordinator.SurfaceState.MIC_UNARMED
                        }
                        handleTapDecision(tapCoordinator.onTap(surfaceState, now), now)
                    }
                    formatGesture.cancel()
                    pauseGesture.cancel()
                    notesGesture.cancel()
                    gestureMode.cancel()
                    true
                }
                else -> false
            }
        }
        pillView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                main.removeCallbacks(longPress)
                gestureMode.cancel()
            }
        })
        try {
            wm.addView(pillView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView echec: ${e.javaClass.simpleName}")
            return
        }
        container = pillView; pill = pillView; wave = waveView; loader = loaderView
        liveText = liveView; liveScroll = scroll; this.livePanel = livePanel; params = lp; liveParams = panelParams; currentAnchor = initialAnchor
        // Prepare the hidden editor window before the first microphone tap.
        try {
            wm.addView(livePanel, panelParams)
            livePanelAdded = true
            positionLivePanel(initialAnchor)
        } catch (e: Exception) {
            Log.w(TAG, "event=panel_prepare outcome=deferred type=${e.javaClass.simpleName}")
        }
        pillView.post { waveView.settle() } // dessine l'onde calme au repos
        scheduleCollapse()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        dismissFloatingMenu()
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

    private fun persistDraft(text: String) {
        draftStore.save(text)
        activeNoteId?.let { id ->
            if (notes.get(id)?.text != text) notes.save(id, text)
            panelTitle?.text = notes.get(id)?.title
        }
    }

    private fun clearOpenDraft() {
        tapCoordinator.reset()
        recoveredDraft = null
        activeNoteId = null
        draftStore.clear()
        setLivePreviewVisible(false)
        setState(if (micArmed) State.IDLE else State.MIC_UNARMED)
    }

    private fun cancelPausedNote() {
        tapCoordinator.reset()
        val run = activeRun
        if (run != null) {
            run.resumeAfterPause = false
            requestCancellation(run)
        } else {
            clearOpenDraft()
            toast("Dictée fermée. Les notes enregistrées sont conservées.")
        }
    }

    private fun archiveOrShowNotes() {
        tapCoordinator.reset()
        if (state == State.TRANSCRIBING || state == State.CANCELLING) {
            toast("Patientez jusqu’à la fin du traitement.")
            return
        }
        val run = activeRun
        if (run != null) {
            run.archiveAsNote = true
            run.resumeAfterPause = false
            if (state != State.PAUSING) stopRec()
        } else if (recoveredDraft != null) {
            notes.save(activeNoteId, liveText?.text?.toString().orEmpty())
            clearOpenDraft()
            showNotesOverlay()
        } else showNotesOverlay()
    }

    private fun openNote(note: TranscriptNote) {
        if (activeRun != null) return
        resetVocabularyLearning()
        dismissFloatingMenu()
        activeNoteId = note.id
        draftStore.noteId = note.id
        recoveredDraft = note.text
        editableTranscript.clear()
        editableTranscript.edit(note.text)
        updatingLiveText = true
        liveText?.setText(note.text)
        updatingLiveText = false
        liveText?.isEnabled = true
        liveText?.hint = "Écrivez ici, ou appuyez sur la pastille pour dicter"
        draftStore.save(note.text)
        panelHidden = false
        setState(State.PAUSED)
        setLivePreviewVisible(true)
    }

    private fun exportOpenNote() {
        if (!isTranscriptEditable()) return
        tapCoordinator.reset()
        val text = liveText?.text?.toString().orEmpty()
        val note = notes.save(activeNoteId, text)
        activeNoteId = note.id
        draftStore.noteId = note.id
        val run = activeRun
        if (run != null) {
            run.finishAfterPause = true
            if (state != State.PAUSING) stopRec()
            return
        }
        panelHidden = true
        setLivePreviewVisible(false)
        // Reserve export so a resume tap cannot race the delayed draft cleanup.
        setState(State.TRANSCRIBING)
        // Let focus return to the underlying app before resolving its text field.
        main.post {
            val result = runCatching {
                injectOrCopy(InjectionGateway.current(), text, { DictationClipboard.copy(this, it) })
            }.getOrDefault(InjectionResult.Failed)
            injectionFeedbackMessage(result)?.let(::toast)
            if (result != InjectionResult.Failed) clearOpenDraft()
            else {
                setState(State.PAUSED)
                panelHidden = false
                setLivePreviewVisible(true)
            }
        }
    }

    private data class MenuEntry(val label: String, val click: () -> Unit, val longClick: (() -> Unit)? = null)

    private fun dismissFloatingMenu() {
        floatingMenu?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        floatingMenu = null
    }

    private fun showFloatingMenu(title: String, entries: List<MenuEntry>, above: Boolean = false) {
        dismissFloatingMenu()
        val dp = resources.displayMetrics.density
        val screen = screenRect()
        val width = minOf((320 * dp).toInt(), screen.width)
        val height = minOf(((entries.size.coerceAtLeast(1) * 64 + 48) * dp).toInt(), (screen.height * .65f).toInt())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xFF1F1F25.toInt()); setStroke((dp).toInt(), ThemeTokens.GREEN) }
        }
        root.addView(TextView(this).apply {
            text = "$title   ×"; textSize = 16f; setTextColor(ThemeTokens.GREEN); gravity = Gravity.CENTER
            contentDescription = "$title. Fermer le menu"
            setOnClickListener { dismissFloatingMenu() }
        }, LinearLayout.LayoutParams(-1, (48 * dp).toInt()))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        entries.forEach { entry ->
            list.addView(TextView(this).apply {
                text = entry.label; textSize = 14f; setTextColor(0xFFF4F4F4.toInt()); gravity = Gravity.CENTER_VERTICAL
                minHeight = (64 * dp).toInt(); setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { entry.click() }
                entry.longClick?.let { action -> setOnLongClickListener { action(); true } }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bounds = FloatingMenuPlacement.bounds(pillRect(params ?: return), screen, width, height, (6 * dp).toInt(), above)
        val layout = WindowManager.LayoutParams(bounds.width, bounds.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = bounds.x; y = bounds.y }
        root.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismissFloatingMenu(); true } else false }
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, layout); floatingMenu = root }
        catch (_: Exception) { toast("Impossible d’afficher le menu flottant.") }
    }

    private fun showNotesOverlay() {
        val entries = mutableListOf(MenuEntry("＋ Nouvelle note", { openNote(notes.save(null, "")) }))
        notes.all().forEach { note ->
            val excerpt = note.text.replace(Regex("\\s+"), " ").take(90)
            entries += MenuEntry(note.title + if (excerpt.isBlank()) "" else "\n$excerpt", { openNote(note) }, {
                showFloatingMenu(note.title, listOf(
                    MenuEntry("Renommer", { renameNote(note) }),
                    MenuEntry("Supprimer", { notes.delete(note.id); showNotesOverlay() }),
                    MenuEntry("Retour aux notes", ::showNotesOverlay),
                ))
            })
        }
        showFloatingMenu("Mes notes", entries)
    }

    private fun renameNote(note: TranscriptNote) {
        dismissFloatingMenu()
        val input = EditText(this).apply { setText(note.title); setSingleLine(); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle("Renommer la note").setView(input)
            .setPositiveButton("Enregistrer") { _, _ -> notes.rename(note.id, input.text.toString()); showNotesOverlay() }
            .setNegativeButton("Annuler") { _, _ -> showNotesOverlay() }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { formatDialog = null }
        dialog.show()
    }

    private fun showFormatPicker() {
        val store = PostProcessingFormats(this)
        val selected = store.selected()
        showFloatingMenu("Format de la dictée", store.all().map { format ->
            MenuEntry((if (format.id == selected.id) "✓ " else "") + format.name, {
                store.select(format)
                dismissFloatingMenu()
                val local = prefs.formattingEngine == "local"
                val supported = format.id in setOf("list", "email")
                if (local && supported) localFormatter.warm()
                toast(when {
                    local && !supported && format.instructions.isNotBlank() -> "Le modèle local prend en charge les listes et les mails. Pour ce format, choisissez le cloud dans les réglages."
                    prefs.formattingEngine != "off" -> "Format sélectionné : ${format.name}"
                    else -> "Activez un moteur de post-traitement dans les réglages pour appliquer ce format."
                })
            })
        }, above = true)
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
        resetVocabularyLearning()
        localFormatter.close()
        dismissFloatingMenu()
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
        tailFollower?.reset(); tailFollower = null
        container = null; pill = null; wave = null; loader = null; pauseIndicator = null; liveText = null; liveScroll = null; panelExpandButton = null; panelTitle = null; panelFormat = null; livePanel = null; liveParams = null
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
