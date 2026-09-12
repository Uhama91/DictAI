package com.kafkasl.phonewhisper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.ViewGroup
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
import java.util.UUID

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

/**
 * Maps normalized PCM RMS to the visual wave only. The mapping leaves headroom above ordinary
 * speech instead of saturating as soon as RMS reaches -24 dBFS; PCM and transcription data are
 * untouched.
 */
internal fun visualWaveLevelFromRms(rms: Double): Float {
    // Keep silence quiet while giving ordinary syllables more visual travel than the installed
    // one-pole mapping. A higher full-scale reference leaves room above speech instead of
    // saturating around -24 dBFS; a smooth floor gate prevents tiny noise changes from jumping.
    val floor = 0.008
    val full = 0.24
    val normalized = ((rms - floor) / (full - floor)).coerceIn(0.0, 1.0)
    val floorFade = 0.02
    val gate = (normalized / floorFade).coerceIn(0.0, 1.0)
    val smoothGate = gate * gate * (3.0 - 2.0 * gate)
    val gated = normalized * smoothGate
    // A rational knee is concave but has a finite slope at the floor. It is visual only; the
    // PCM value sent to ASR is unchanged.
    val knee = 0.04
    return (gated * (1.0 + knee) / (gated + knee)).toFloat()
}

class OverlayService : Service() {

    private val overlayPalette: ThemePalette
        get() = ThemeTokens.palette(this)

    companion object {
        private const val TAG = "WhisperPin"
        private const val CHANNEL_ID = "whisperpin_overlay"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_CAPTURE_RESULT = "com.uhama.whisperpin.CAPTURE_RESULT"
        const val ACTION_EXPORT_CLOSED = "com.uhama.whisperpin.EXPORT_CLOSED"
        const val ACTION_EXPORT_BRIDGE_FOREGROUND = "com.uhama.whisperpin.EXPORT_BRIDGE_FOREGROUND"
        const val EXTRA_EXPORT_BRIDGE_TOKEN = "com.uhama.whisperpin.EXTRA_EXPORT_BRIDGE_TOKEN"
        const val ACTION_OPEN_NOTES = "com.uhama.whisperpin.OPEN_NOTES"
        const val ACTION_ARM_MIC = "com.uhama.whisperpin.ARM_MIC"
        const val ACTION_PREPARE_LOCAL_FORMAT = "com.uhama.whisperpin.PREPARE_LOCAL_FORMAT"
        const val ACTION_THEME_CHANGED = "com.uhama.whisperpin.THEME_CHANGED"
        private const val DOUBLE_TAP_MS = 280L
        private const val RECORD_STOP_TIMEOUT_MS = 1_000L
        @Volatile var micArmed = false
            private set
    }

    private enum class State { IDLE, RECORDING, PAUSING, PAUSED, TRANSCRIBING, CANCELLING, MIC_UNARMED }

    private class ActiveDictationRun(
        val session: DictationAsrSession,
        val formatOptions: RecordingOptions,
        val purpose: DictationPurpose,
        val cancellation: DictationCancellationCoordinator = DictationCancellationCoordinator(),
    ) {
        val captureGate = RecordingCaptureGate()
        @Volatile var pauseWorker: Thread? = null
        var resumeAfterPause = false
        var archiveAsNote = false
        var exportNote = false
        var reviewNoteInsertionAfterFinish = false
        var afterCompletion: (() -> Unit)? = null
        var automaticNoteShare = true
        var finishAfterPause = false
        var finishAfterCapture = false
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
        var startRequestedAtMs = 0L
        @Volatile var readerStartedAtMs = 0L
        @Volatile var firstAudioAtMs = 0L
    }

    private val prefs by lazy { PersistencePrefs(this) }
    @Volatile private var state = State.MIC_UNARMED
    private var recordThread: Thread? = null
    private var container: View? = null
    private var pill: FrameLayout? = null
    private var wave: CursiveWaveView? = null
    private var pauseIndicator: TextView? = null
    private var gestureHint: TextView? = null
    private var stateIndicator: OverlayStateIndicatorView? = null
    private var loader: LoadingBorderView? = null
    private var liveText: OverlayTranscriptEditor? = null
    private var updatingLiveText = false
    private var liveEditorChanging = false
    private val vocabularyTracker = VocabularyCorrectionTracker()
    private var vocabularySuggestion: VocabularyCorrectionTracker.Suggestion? = null
    private var vocabularyBanner: LinearLayout? = null
    private var vocabularySuggestionText: TextView? = null
    private var vocabularyOffer: Runnable? = null
    private var vocabularyDismiss: Runnable? = null
    private var mediaToolbar: LinearLayout? = null
    private val editableTranscript = EditableTranscript()
    private val localFormatter by lazy { LocalFormatEngine(this) }
    private var formatDialog: AlertDialog? = null
    private var livePanel: FrameLayout? = null
    /** Transparent envelope for the panel window; the rounded body clips its own children. */
    private var livePanelBody: FrameLayout? = null
    private var bubblePointer: OverlayBubblePointerView? = null
    private var liveScroll: ScrollView? = null
    private var tailFollower: TranscriptTailFollower? = null
    private var panelTitle: TextView? = null
    private var panelExpandButton: ImageButton? = null
    private var editorActionsRow: LinearLayout? = null
    private var keyboardInset = 0
    private var panelExpanded = false
    private var panelCompact = false
    private var panelTransitionAnimator: ValueAnimator? = null
    private var panelTransitionTarget: Rect? = null
    private var panelTransitionRequested = false
    private var lastPanelScreen: Rect? = null
    private var pillPositionBeforeKeyboard: Point? = null
    private var panelHidden = false
    private val draftStore by lazy { DictationDraftStore(this) }
    private var recoveredDraft: String? = null
    private val notes by lazy { TranscriptNotes(AndroidTranscriptNoteStorage(this)) }
    private var activeNoteId: String? = null
    private var purpose = DictationPurpose.MESSAGE
    private val noteInsertionGate = NoteInsertionGate()
    private var noteInsertionDialog: AlertDialog? = null
    private var noteInsertButton: TextView? = null
    private var noteDoneButton: TextView? = null
    private val imageStore by lazy { NoteImageStore(this) }
    private var captureWindowsHidden = false
    private var preserveImageClipboard = false
    private var imageDeliveryBusy = false
    private var exportAfterImageDelivery: String? = null
    private var archiveAfterImageDelivery = false
    private var imageStrip: LinearLayout? = null
    private var imageStripScroll: android.widget.HorizontalScrollView? = null
    private var mediaButtons = mutableListOf<ImageButton>()
    private var shownImageIds = emptyList<String>()

    private val exportController by lazy { OverlayExportController(this, main) }
    private var exportPanel: OverlayExportPanel? = null
    private var exportNoteId: String? = null
    private val exportAccessibilityPrevious = mutableMapOf<View, Int>()
    private data class ExportResume(
        val liveVisible: Boolean,
        val panelHidden: Boolean,
        val panelExpanded: Boolean,
        val purpose: DictationPurpose,
        val activeNoteId: String?,
        val recoveredDraft: String?,
        val selectionStart: Int,
        val selectionEnd: Int,
        val fromNotesMenu: Boolean,
    )
    private var exportResume: ExportResume? = null
    private var exportBridgeHidden = false
    private var exportBridgeToken: String? = null
    private var bridgeWasLiveVisible = false
    private var bridgeWasContainerVisible = false

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
    private var panelEdge: Edge? = null
    private var bubblePointerLength = 0
    private var bubblePointerTargetX = Float.NaN
    private var bubblePointerTargetY = Float.NaN
    private var livePanelAdded = false
    private var livePreviewVisible = false
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private val localLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val localEngineLifecycle = LocalEngineLifecycle()
    private val residentAsrEngine = ResidentEngine<DictationAsrEngine>()
    private val main = Handler(Looper.getMainLooper())
    private val tapCoordinator = DictationTapGestureCoordinator(DOUBLE_TAP_MS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        micArmed = false
        createChannel()
        if (!startForegroundSpecialUse()) return
        showButton()
        purpose = draftStore.purpose
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
        recoverPendingImage()
        refreshNoteImages()
        ensureLocalLoaded()
        warmLocalFormatter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_RESULT) finishPendingImage()
        if (intent?.action == ACTION_THEME_CHANGED) refreshOverlayTheme()
        if (intent?.action == ACTION_EXPORT_CLOSED && state == State.PAUSED &&
            activeNoteId != null && activeNoteId == intent.getStringExtra("noteId")) {
            panelHidden = false
            setLivePreviewVisible(true)
        }
        val bridgeToken = intent?.getStringExtra(EXTRA_EXPORT_BRIDGE_TOKEN)
        if (intent?.action == ACTION_EXPORT_BRIDGE_FOREGROUND &&
            !bridgeToken.isNullOrBlank() && bridgeToken == exportBridgeToken
        ) {
            restoreAfterExportBridge()
        }
        if (intent?.action == ACTION_ARM_MIC) {
            promoteMic()
            wake()
            // Si le modèle local n'était pas dispo au démarrage (pas encore téléchargé),
            // on retente de le charger (un seul chargement à la fois, cf. ensureLocalLoaded).
            ensureLocalLoaded()
            warmLocalFormatter()
        }
        if (intent?.action == ACTION_PREPARE_LOCAL_FORMAT) warmLocalFormatter()
        if (intent?.action == ACTION_OPEN_NOTES) {
            if (exportPanel != null) {
                closeNoteExport()
                showNotesOverlay()
                return START_STICKY
            }
            archiveOrShowNotes()
        }
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
        val requestedAt = SystemClock.elapsedRealtime()
        val selectedFormat = PostProcessingFormats(this).selected()
        val cloudRequested = prefs.formattingEngine == "cloud" && prefs.cloudCleanupEnabled && selectedFormat.usesLanguageModel
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
            format = selectedFormat,
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
        invalidateNoteInsertion()
        val run = ActiveDictationRun(started.session, options, purpose)
        if (options.localFormattingEnabled && options.format.localLayoutKind != null) {
            localFormatter.warm()
            run.localFormatting = LocalFormattingSession(localFormatter.backend())
            run.cancellation.onCancel { run.localFormatting?.close() }
        }
        run.cancellation.onCancel { started.session.cancel() }
        run.startRequestedAtMs = requestedAt
        val ar = recorder.audioRecord
        val recordingPcm = java.io.ByteArrayOutputStream()
        var readerThread: Thread? = null
        try {
            audioRecord = ar
            pcm = recordingPcm
            asrSession = started.session
            activeRun = run
            // Publish the recording state and start draining AudioRecord before
            // draft persistence and panel layout work.  The hardware buffer can
            // otherwise fill while the main thread prepares the overlay, which
            // is most visible as missing words at the beginning of a sentence.
            // setState also refreshes the notification, note thumbnails and
            // panel layout synchronously on the main looper.  Publish the state
            // field first so the reader can drain AudioRecord during that work.
            state = State.RECORDING
            readerThread = launchAudioReader(run, ar, recordingPcm, bufSize)
            setState(State.RECORDING)
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
            setLivePreviewVisible(true)
            vibrate(20)
            Log.i(TAG, "event=audio_start outcome=ready elapsed_ms=${SystemClock.elapsedRealtime() - requestedAt}")
        } catch (t: Throwable) {
            run.captureGate.pause()
            if (asrSession === started.session) asrSession = null
            if (audioRecord === ar) audioRecord = null
            recordThread = null
            pcm = null
            run.cancellation.cancel()
            val coordinator = RecordingStopCoordinator(
                recordThread = readerThread,
                stopRecorder = { ar.stop() },
                releaseRecorder = { ar.release() },
                snapshot = {},
            )
            // Keep the run busy until both AudioRecord and the native session
            // have exited; a second tap must not open another microphone first.
            setState(State.CANCELLING)
            thread(name = "dictai-start-failure-stop") {
                if (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS) == RecordingStopCoordinator.Result.TimedOut) {
                    coordinator.awaitExitThenRelease()
                }
                awaitSessionExit(run)
                main.post {
                    if (localEngineLifecycle.isDestroyed() || activeRun !== run) return@post
                    activeRun = null
                    setLivePreviewVisible(false)
                    setState(State.IDLE)
                }
            }
            Log.w(TAG, "event=audio_start outcome=publication_failure type=${t.javaClass.simpleName}")
        }
    }

    private fun launchAudioReader(
        run: ActiveDictationRun,
        recorder: AudioRecord,
        recordingPcm: java.io.ByteArrayOutputStream,
        bufferSize: Int,
    ): Thread {
        val reader = Thread({
            run.readerStartedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "event=audio_reader_start startup_ms=${run.readerStartedAtMs - run.startRequestedAtMs}")
            // Read 20 ms at a time; the recorder keeps its larger hardware buffer.
            val buf = ByteArray(minOf(bufferSize, SAMPLE_RATE / 50 * 2))
            var firstFrame = true
            try {
                while (state == State.RECORDING && isCurrentRun(run)) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        if (firstFrame) {
                            firstFrame = false
                            run.firstAudioAtMs = SystemClock.elapsedRealtime()
                            Log.i(TAG, "event=audio_first_frame startup_ms=${run.firstAudioAtMs - run.startRequestedAtMs} bytes=$n peak=${peakAmplitude(buf, n)}")
                        }
                        run.captureGate.deliver {
                            recordingPcm.write(buf, 0, n)
                            run.session.acceptPcm16(buf, n)
                            wave?.setLevel(rmsLevel(buf, n))
                        }
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
        return reader
    }

    private fun peakAmplitude(buf: ByteArray, n: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val sample = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(sample))
            i += 2
        }
        return peak
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
                if (run.archiveAsNote || run.finishAfterPause || run.finishAfterCapture) {
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
        invalidateNoteInsertion()
        releaseTranscriptFocus()
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
        var readerThread: Thread? = null
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
            run.startRequestedAtMs = SystemClock.elapsedRealtime()
            state = State.RECORDING
            readerThread = launchAudioReader(run, resumed, recordingPcm, bufferSize)
            setState(State.RECORDING)
        } catch (_: Throwable) {
            run.captureGate.pause()
            recorder?.let { resumed ->
                val coordinator = RecordingStopCoordinator(
                    recordThread = readerThread,
                    stopRecorder = { resumed.stop() },
                    releaseRecorder = { resumed.release() },
                    snapshot = {},
                )
                setState(State.PAUSING)
                thread(name = "dictai-resume-failure-stop") {
                    if (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS) == RecordingStopCoordinator.Result.TimedOut) {
                        coordinator.awaitExitThenRelease()
                    }
                    main.post {
                        if (localEngineLifecycle.isDestroyed() || activeRun !== run || state != State.PAUSING) return@post
                        setState(State.PAUSED)
                    }
                }
            }
            audioRecord = null
            recordThread = null
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
        return visualWaveLevelFromRms(rms)
    }

    private fun stopRec() {
        if (state != State.RECORDING && state != State.PAUSED) return
        val run = activeRun ?: return
        if (imageDeliveryBusy || imageStore.pending() != null) {
            run.finishAfterCapture = true
            if (state == State.RECORDING) pauseRec()
            return
        }
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
        val resolvedText = editableTranscript.resolveFinal(r.text) { normalizeRecognizedText(it, capture.options) }
        val formatStarted = SystemClock.elapsedRealtime()
        val prepared = if (!run.archiveAsNote && resolvedText != null)
            prepareCorrectedText(resolvedText, capture.options) else null
        val lightCleanupApplied = !resolvedText.isNullOrBlank() && capture.options.lightTextCleanup &&
            capture.options.format.id == "cleanup" && !editableTranscript.hasUserEdits()
        val localText = if (lightCleanupApplied) LightTextCleanup.apply(resolvedText, protectedVocabularyTerms(resolvedText))
            else prepared?.text ?: resolvedText
        if (run.cancellation.isCancelled) {
            return
        }
        if (run.archiveAsNote) {
            main.post {
                if (!isCurrentRun(run) || run.cancellation.isCancelled || localEngineLifecycle.isDestroyed()) return@post
                val text = localText ?: liveText?.text?.toString().orEmpty()
                val saved = saveNoteWithCaptures(text)
                run.afterCompletion = {
                    toast("Note enregistrée : ${saved.title}")
                    showNotesOverlay()
                }
                completeRunOnMain(run)
            }
            return
        }
        var localDirect = false
        var localDiagnostic: LocalFinishDiagnostic? = null
        val localFormatted = if (!localText.isNullOrBlank() && capture.options.localFormattingEnabled &&
            capture.options.format.usesLanguageModel) {
            val request = localFormatRequest(localText, capture.options, applyVocabulary = false).let {
                if (editableTranscript.hasUserEdits()) it.copy(validation = LocalFormatValidation.GEMMA_PROJECTION) else it
            }
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
            session?.finish(request, request.finalWaitMs()) { chunk ->
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
        val cloudText = if (capture.options.format.usesLanguageModel && !capture.options.localFormattingEnabled && !localText.isNullOrBlank() && capture.options.cloudCleanupEnabled &&
            (!editableTranscript.hasUserEdits() || capture.options.format.usesLanguageModel)) {
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
            (prepared?.removed ?: 0) > 0 -> "Hésitations retirées · correction indisponible"
            capture.options.format.usesLanguageModel || localDiagnostic != null -> "Traitement indisponible · texte conservé"
            else -> "Sans appel LLM"
        }
        main.post {
            if (isCurrentRun(run) && !run.cancellation.isCancelled) {
                run.formatStage = formatOutcome
                currentAnchor?.let(::positionLivePanel)
                if (localFormatted != null) toast("${capture.options.format.name} : $formatOutcome")
            }
        }
        if (capture.options.format.usesLanguageModel && formatted == null && !localText.isNullOrBlank()) {
            toast(if ((prepared?.removed ?: 0) > 0) "Mise en forme indisponible : hésitations retirées, texte conservé."
                else "Mise en forme indisponible : texte conservé sans format.")
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
        if (!finalText.isNullOrBlank() && !editableTranscript.hasUserEdits() && NoteImageMarkers.markers(finalText).isEmpty()) {
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
                    // This is the publication boundary, including late capture/pause callbacks.
                    // A note can only reach an external field through the explicit confirmation below.
                    val destination = NoteInteractionPolicy.destination(
                        if (purpose == DictationPurpose.NOTE) purpose else run.purpose,
                        archive = run.archiveAsNote, export = run.exportNote,
                    )
                    if (destination != NoteInteractionPolicy.Destination.MESSAGE) {
                        val saved = saveNoteWithCaptures(outText ?: liveText?.text?.toString().orEmpty())
                        run.afterCompletion = {
                            if (destination == NoteInteractionPolicy.Destination.NOTE_LIST) showNotesOverlay()
                            else {
                                openNote(saved)
                                if (destination == NoteInteractionPolicy.Destination.NOTE_EXPORT)
                                    launchNoteExport(saved, automatic = run.automaticNoteShare)
                                else if (run.reviewNoteInsertionAfterFinish) confirmNoteInsertion(saved)
                            }
                        }
                    } else if (!outText.isNullOrBlank()) {
                        activeNoteId?.let { notes.save(it, outText) }
                        Log.i(TAG, "event=dictation_publish stop_to_text_ms=${if (run.stoppedAtMs > 0) SystemClock.elapsedRealtime() - run.stoppedAtMs else -1}")
                        val result = runCatching {
                            injectOrCopy(
                                controller = InjectionGateway.current(),
                                text = outText,
                                copyToClipboard = { DictationClipboard.copy(this, it) },
                                preserveClipboardOnDirectInsert = preserveImageClipboard,
                            )
                        }.getOrElse {
                            Log.w(TAG, "event=injection outcome=failure type=${it.javaClass.simpleName}")
                            InjectionResult.Failed
                        }
                        if (result != InjectionResult.Failed) preserveImageClipboard = false
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
                                hesitationsRemoved = prepared?.removed ?: 0,
                            )
                            prefs.recordPostprocessingDiagnostic(diagnostic,
                                formatRequested = capture.options.format.usesLanguageModel)
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
        when (NoteInteractionPolicy.tap(purpose, decision.action)) {
            DictationTapGestureCoordinator.Action.START_RECORDING -> startRec()
            DictationTapGestureCoordinator.Action.RESUME_RECORDING -> resumeRec()
            DictationTapGestureCoordinator.Action.STOP_RECORDING -> stopRec()
            DictationTapGestureCoordinator.Action.PAUSE_RECORDING -> pauseRec()
            DictationTapGestureCoordinator.Action.SAVE_AND_CLOSE_NOTE -> archiveOrShowNotes()
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
        if (imageDeliveryBusy || imageStore.pending() != null) {
            main.postDelayed({ completeRunOnMain(run) }, 60)
            return
        }
        run.formatOffer?.let(main::removeCallbacks)
        run.formatOffer = null
        run.formatOfferRequest = null
        run.localFormatting?.close()
        activeRun = null
        archiveAfterImageDelivery = false
        recoveredDraft = null
        activeNoteId = null
        purpose = DictationPurpose.MESSAGE
        draftStore.clear()
        refreshNoteImages()
        if (asrSession === run.session) asrSession = null
        tapCoordinator.reset()
        setLivePreviewVisible(false)
        setState(State.IDLE)
        run.afterCompletion?.also { run.afterCompletion = null }?.invoke()
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
                if (s == State.MIC_UNARMED) overlayPalette.red else overlayPalette.inkMuted
            )
            val paused = s == State.PAUSED || s == State.PAUSING
            pauseIndicator?.visibility = if (paused) View.VISIBLE else View.GONE
            wave?.visibility = if (paused) View.INVISIBLE else View.VISIBLE
            stateIndicator?.setVisualState(when (s) {
                State.RECORDING -> OverlayStateIndicatorView.VisualState.RECORDING
                State.PAUSED, State.PAUSING -> OverlayStateIndicatorView.VisualState.PAUSED
                State.TRANSCRIBING -> OverlayStateIndicatorView.VisualState.PROCESSING
                else -> OverlayStateIndicatorView.VisualState.IDLE
            })
            pill?.contentDescription = when (s) {
                State.PAUSED -> if (purpose == DictationPurpose.NOTE) "Note en pause. Appuyer pour dicter dans la note." else "Dictée en pause. Appuyer pour reprendre."
                State.PAUSING -> "Mise en pause de la dictée."
                State.RECORDING -> if (purpose == DictationPurpose.NOTE) "Dictée dans la note. Appuyer pour mettre en pause." else "Dictée de message en cours. Appuyer pour insérer. Glisser vers le bas pour mettre en pause."
                else -> "Appuyer pour dicter. Glisser vers le haut pour les formats. Maintenir jusqu’à la vibration pour déplacer."
            }
            showRecordingPill(s == State.RECORDING)
            // Bordure lumineuse pendant la transcription.
            if (s == State.TRANSCRIBING) loader?.start() else loader?.stop()
            updateNotif()
            refreshNoteImages()
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

    private fun overlayWithAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255) and 0xFF) shl 24)

    private fun overlayCardBackground(
        color: Int,
        stroke: Int? = null,
        radiusDp: Float = 24f,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(color)
        stroke?.let {
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), it)
        }
    }

    private fun overlayActionBackground(colors: ThemePalette): android.graphics.drawable.RippleDrawable =
        android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(overlayWithAlpha(colors.green, 0x44)),
            overlayCardBackground(colors.raised, radiusDp = 18f),
            null,
        )

    private fun overlayDialogContext(): android.content.Context {
        val mode = ThemeModeStore.read(this)
        val config = Configuration(resources.configuration)
        val mask = Configuration.UI_MODE_NIGHT_MASK
        config.uiMode = (config.uiMode and mask.inv()) or when (mode) {
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.SYSTEM -> config.uiMode and mask
        }
        return android.view.ContextThemeWrapper(createConfigurationContext(config), R.style.Theme_PhoneWhisper)
    }

    /** Recolours already attached overlay views without recreating the pill or dictation panel. */
    private fun refreshOverlayTheme() {
        val refresh = Runnable {
            val colors = overlayPalette

            pill?.background = overlayCardBackground(colors.surface, colors.stroke, 22f)
            gestureHint?.apply {
                setTextColor(colors.inkMuted)
                background = overlayCardBackground(colors.raised, radiusDp = 22f)
            }
            wave?.setStrokeColor(colors.ink)
            wave?.invalidate()
            pauseIndicator?.setTextColor(colors.pauseInk)

            liveText?.apply {
                setTextColor(colors.ink)
                setHintTextColor(colors.inkMuted)
            }
            livePanelBody?.background = overlayCardBackground(colors.surface, colors.stroke, 24f)
            bubblePointer?.setColors(colors.surface, colors.stroke)
            panelTitle?.setTextColor(colors.ink)

            panelExpandButton?.parent?.let { parent ->
                if (parent is android.view.ViewGroup) {
                    for (index in 0 until parent.childCount) {
                        (parent.getChildAt(index) as? ImageButton)?.apply {
                            imageTintList = ColorStateList.valueOf(colors.ink)
                            background = android.graphics.drawable.RippleDrawable(
                                ColorStateList.valueOf(overlayWithAlpha(colors.green, 0x55)), null, null,
                            )
                        }
                    }
                }
            }

            editorActionsRow?.let { actions ->
                for (index in 0 until actions.childCount) {
                    (actions.getChildAt(index) as? TextView)?.apply {
                        setTextColor(colors.green)
                        background = overlayActionBackground(colors)
                    }
                }
            }
            listOfNotNull(noteInsertButton, noteDoneButton).forEach { it.setTextColor(colors.green) }

            mediaButtons.forEach { button ->
                button.imageTintList = ColorStateList.valueOf(colors.ink)
                button.background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(overlayWithAlpha(colors.green, 0x55)), null, null,
                )
            }
            vocabularyBanner?.apply {
                background = overlayCardBackground(colors.raised, radiusDp = 12f)
                for (index in 0 until childCount) {
                    (getChildAt(index) as? TextView)?.setTextColor(
                        if (getChildAt(index) === vocabularySuggestionText) colors.green else colors.inkMuted,
                    )
                }
            }
            refreshImageStripTheme(colors)
            refreshOverlayMenu(floatingMenu, colors, root = true)
            refreshOverlayDialog(formatDialog, colors)
            refreshOverlayDialog(noteInsertionDialog, colors)
            exportPanel?.refreshTheme(colors)
            // Reapply state-dependent border, visibility and accessibility text after recolour.
            setState(state)
            livePanel?.invalidate()
            container?.invalidate()
        }
        if (Looper.myLooper() == main.looper) refresh.run() else main.post(refresh)
    }

    private fun refreshImageStripTheme(colors: ThemePalette) {
        val group = imageStrip ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is TextView) {
                child.setTextColor(colors.inkMuted)
                continue
            }
            val frame = child as? android.view.ViewGroup ?: continue
            for (badgeIndex in 0 until frame.childCount) {
                (frame.getChildAt(badgeIndex) as? TextView)?.apply {
                    // Number badges sit over arbitrary captured pixels; keep the black/white
                    // treatment together so theme recolouring cannot reduce their contrast.
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(0xBB000000.toInt())
                }
            }
        }
    }

    private fun refreshOverlayMenu(view: View?, colors: ThemePalette, root: Boolean = false) {
        view ?: return
        when (val background = view.background) {
            is GradientDrawable -> background.setColor(if (root) colors.surface else colors.raised)
            is android.graphics.drawable.RippleDrawable -> {
                (background.getDrawable(0) as? GradientDrawable)?.setColor(colors.raised)
            }
        }
        if (view is TextView) {
            val sizeSp = view.textSize / resources.displayMetrics.scaledDensity
            view.setTextColor(when {
                sizeSp >= 16f -> colors.ink
                sizeSp <= 12.5f -> colors.green
                else -> colors.inkMuted
            })
        }
        (view as? android.view.ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) refreshOverlayMenu(group.getChildAt(index), colors)
        }
    }

    private fun refreshOverlayDialog(dialog: AlertDialog?, colors: ThemePalette) {
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(overlayCardBackground(colors.surface, colors.stroke, 24f))
        val content = window.decorView.findViewById<android.view.View>(android.R.id.content) ?: return
        retintOverlayDialogView(content, colors)
    }

    private fun retintOverlayDialogView(view: View, colors: ThemePalette) {
        when (view) {
            is EditText -> {
                view.setTextColor(colors.ink)
                view.setHintTextColor(colors.inkMuted)
            }
            is android.widget.Button -> view.setTextColor(colors.green)
            is TextView -> {
                val sizeSp = view.textSize / resources.displayMetrics.scaledDensity
                view.setTextColor(if (sizeSp >= 16f) colors.ink else colors.inkMuted)
            }
        }
        (view as? android.view.ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) retintOverlayDialogView(group.getChildAt(index), colors)
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
            val display = editableTranscript.update(text) { normalizeRecognizedText(it, run.formatOptions) }
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
                    val selectionStart = editor.selectionStart
                    val selectionEnd = editor.selectionEnd
                    editor.text.replace(prefix, old.length - suffix, display.substring(prefix, display.length - suffix))
                    preserveEditorSelection(
                        editor,
                        oldStart = prefix,
                        oldEnd = old.length - suffix,
                        newEnd = display.length - suffix,
                        selectionStart = selectionStart,
                        selectionEnd = selectionEnd,
                    )
                } finally { updatingLiveText = false }
            }
            setLivePreviewVisible(true)
            scrollTranscriptToEnd(run)
        }
    }

    /** Keep a correction's caret stable when a later ASR revision replaces the tail. */
    private fun preserveEditorSelection(
        editor: EditText,
        oldStart: Int,
        oldEnd: Int,
        newEnd: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        TranscriptSelectionMapping.afterReplacement(
            selectionStart, selectionEnd, oldStart, oldEnd, newEnd, editor.length(),
        )?.let { mapped -> runCatching { editor.setSelection(mapped.start, mapped.end) } }
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
        val hasImageReferences = NoteImageMarkers.markers(source).isNotEmpty()
        val layout = if (options.localFormattingEnabled || hasImageReferences)
            options.format.localLayoutKind ?: if (hasImageReferences) LocalLayoutKind.TEXT else null else null
        return LocalFormatRequest(source, options.format.instructions + numbers + if (NoteImageMarkers.markers(source).isEmpty()) "" else NoteImageMarkers.INSTRUCTIONS, options.language.cleanupLanguageName, spellings, layout,
            validation = if (layout != null) LocalFormatValidation.GEMMA_EDITING else LocalFormatValidation.EXACT_LAYOUT,
            simpleEmailLayout = true)
    }

    private fun protectedVocabularyTerms(text: String): List<String> = Vocabulary.corrections(this)
        .map { it.second }.filter { it.isNotBlank() && it in text }.distinct().take(64) + NoteImageMarkers.markers(text)

    private fun prepareCorrectedText(text: String, options: RecordingOptions): CorrectedTextPreparation.Result =
        CorrectedTextPreparation.prepare(text, options.format.id, protectedVocabularyTerms(text),
            editableTranscript.manualProtection(text))

    private fun normalizeRecognizedText(text: String, options: RecordingOptions): String {
        val corrected = Vocabulary.applyCorrections(this, text)
        return NumberFormatting.apply(corrected, options.language, options.numberStyle, protectedVocabularyTerms(corrected))
    }

    private fun scheduleVocabularySuggestion(resetTimer: Boolean = true) {
        if (!resetTimer && vocabularyOffer != null) return
        vocabularyOffer?.let(main::removeCallbacks)
        vocabularyOffer = null
        if (!isTranscriptEditable() || !vocabularyTracker.hasPending) return
        val offer = Runnable {
            vocabularyOffer = null
            val editor = liveText ?: return@Runnable
            if (!isTranscriptEditable()) return@Runnable
            val composing = BaseInputConnection.getComposingSpanStart(editor.text) >= 0
            val suggestion = vocabularyTracker.suggestion(
                editor.text.toString(), editor.selectionStart, editor.selectionEnd,
                composing, SystemClock.elapsedRealtime(),
            )
            val previous = vocabularySuggestion
            vocabularySuggestion = suggestion
            vocabularySuggestionText?.text = suggestion?.let { "Enregistrer dans le vocabulaire\n« ${it.from} » → « ${it.to} »" }.orEmpty()
            setVocabularySuggestionVisible(suggestion != null)
            if (suggestion != null && (suggestion != previous || vocabularyDismiss == null)) {
                vocabularyDismiss?.let(main::removeCallbacks)
                vocabularyDismiss = Runnable { resetVocabularyLearning() }.also { main.postDelayed(it, 12_000L) }
            }
            if (composing && suggestion == null) scheduleVocabularySuggestion()
        }
        vocabularyOffer = offer
        main.postDelayed(offer, vocabularyTracker.settleDelayMillis)
    }

    private fun setVocabularySuggestionVisible(visible: Boolean) {
        vocabularyBanner?.visibility = if (visible && !panelCompact) View.VISIBLE else View.GONE
        // Reuse the media toolbar's row: the offer stays reachable in a small overlay without
        // covering the corrected text, resizing the editor or moving the caret under the finger.
        mediaToolbar?.visibility = if (panelCompact || visible) View.GONE else View.VISIBLE
    }

    private fun resetVocabularyLearning() {
        vocabularyOffer?.let(main::removeCallbacks)
        vocabularyOffer = null
        vocabularyDismiss?.let(main::removeCallbacks)
        vocabularyDismiss = null
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
            localFormatRequest(prepareCorrectedText(it, options).text, options, applyVocabulary = false)
        }
        if (request == run.formatOfferRequest) return
        run.formatOffer?.let(main::removeCallbacks)
        run.formatOffer = null
        run.formatOfferRequest = request
        if (request == null || request.directOutput() != null || editableTranscript.hasUserEdits()) return
        val offer = Runnable {
            if (isCurrentRun(run) && isTranscriptEditable() && !run.cancellation.isCancelled) formatter.offer(request)
        }
        run.formatOffer = offer
        main.postDelayed(offer, 1000L)
    }

    private fun setLivePreviewVisible(requested: Boolean) {
        val show = requested && !panelHidden && !captureWindowsHidden
        val panel = livePanel ?: return
        if (livePreviewVisible == show && panel.visibility == if (show) View.VISIBLE else View.GONE) return
        livePreviewVisible = show
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            cancelPanelTransition()
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
        lastPanelScreen = null
        liveText?.endEditing()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(panel.windowToken, 0)
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

    /** Resolve the IME top in display coordinates before placing the overlay panel. */
    private fun screenAboveKeyboard(fullScreen: Rect): Rect {
        var imeTop: Int? = null
        try {
            val metrics = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics
            val imeBottom = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            if (imeBottom > 0) imeTop = metrics.bounds.bottom - imeBottom
        } catch (_: Throwable) {
            // The visible display frame below remains the OEM fallback.
        }
        val visibleFrameBottom = livePanel?.let { panel ->
            val frame = android.graphics.Rect()
            panel.getWindowVisibleDisplayFrame(frame)
            frame.bottom.takeIf { it > fullScreen.y && it < fullScreen.bottom }
        }
        return OverlayPlacement.screenAboveKeyboard(fullScreen, imeTop, visibleFrameBottom)
    }

    private fun localFormatStatus(): String = when (localFormatter.runtimeName()) {
        "litert-lm-gpu-mtp-thinking-off" -> "Gemma prêt"
        "loading" -> "Gemma se prépare…"
        "model-missing" -> "Gemma à installer"
        "gpu-error", "loading-timeout", "cancellation-pending" -> "Gemma indisponible"
        else -> "Gemma prévu"
    }

    /** Keep the transcript area usable when the IME leaves only a short panel. */
    private fun layoutTranscriptRows(panelHeight: Int, dp: Float) {
        val toolbarHeight = (48 * dp).toInt()
        // The selected format belongs in the toolbar. Keeping a second status row
        // made the compact overlay feel like two headers and pushed the editor down.
        val formatHeight = 0
        val mediaHeight = (48 * dp).toInt()
        val actionsHeight = if (purpose == DictationPurpose.NOTE) (48 * dp).toInt() else 0
        val minimumTextHeight = maxOf((72 * dp).toInt(), (liveText?.lineHeight ?: (20 * dp).toInt()) * 2 + (20 * dp).toInt())
        val layout = OverlayPlacement.transcriptPanelLayout(
            panelHeight, toolbarHeight, formatHeight, mediaHeight, actionsHeight, minimumTextHeight,
        )
        panelCompact = layout.compact

        val suggestion = vocabularySuggestion != null
        mediaToolbar?.visibility = if (layout.showMedia && !suggestion) View.VISIBLE else View.GONE
        vocabularyBanner?.visibility = if (layout.showMedia && suggestion) View.VISIBLE else View.GONE

        val actions = editorActionsRow
        val showActions = purpose == DictationPurpose.NOTE && layout.showActions
        actions?.visibility = if (showActions) View.VISIBLE else View.GONE
        val showMedia = layout.showMedia
        val bottomBarHeight = (if (showMedia) mediaHeight else 0) + (if (showActions) actionsHeight else 0)
        if (showActions) {
            (actions?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val top = panelHeight - bottomBarHeight
                if (lp.topMargin != top || lp.bottomMargin != 0) {
                    lp.topMargin = top
                    lp.bottomMargin = 0
                    actions.layoutParams = lp
                }
            }
        }

        val mediaTop = panelHeight - mediaHeight
        listOfNotNull(mediaToolbar, vocabularyBanner).forEach { row ->
            (row.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.topMargin != mediaTop || lp.bottomMargin != 0) {
                    lp.topMargin = mediaTop
                    lp.bottomMargin = 0
                    row.layoutParams = lp
                }
            }
        }

        (liveScroll?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val top = toolbarHeight
            if (lp.topMargin != top || lp.bottomMargin != bottomBarHeight) {
                lp.topMargin = top
                lp.bottomMargin = bottomBarHeight
                liveScroll?.layoutParams = lp
            }
        }
    }

    private fun repositionPanelIfVisibleScreenChanged() {
        val anchor = currentAnchor ?: return
        val nextScreen = screenAboveKeyboard(screenRect())
        if (nextScreen != lastPanelScreen) positionLivePanel(anchor)
    }

    /**
     * Keep the panel's pointer aimed at the pill that is really on screen. Overlay windows using
     * ADJUST_NOTHING can leave the pill below an opened IME, so temporarily lift that same window
     * instead of feeding positionLivePanel a fabricated rectangle. The user's anchor is restored
     * when the safe display returns and is never overwritten by this temporary adjustment.
     */
    private fun pillRectForPanel(fullScreen: Rect, screen: Rect): Rect {
        val lp = params ?: return Rect(0, 0, 0, 0)
        val pill = Rect(lp.x, lp.y, lp.width, lp.height)
        val keyboardVisible = screen.bottom < fullScreen.bottom
        val needsLift = pill.bottom > screen.bottom && keyboardVisible
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = container
        if (needsLift) {
            if (pillPositionBeforeKeyboard == null) pillPositionBeforeKeyboard = Point(lp.x, lp.y)
            val liftedY = (screen.bottom - lp.height).coerceAtLeast(screen.y)
            if (lp.y != liftedY) {
                lp.y = liftedY
                if (view != null) runCatching { wm.updateViewLayout(view, lp) }
            }
        } else if (!keyboardVisible) {
            val previous = pillPositionBeforeKeyboard
            if (previous != null) {
                val restored = OverlayPlacement.clampPill(
                    previous,
                    Rect(0, 0, lp.width, lp.height),
                    fullScreen,
                )
                if (lp.x != restored.x || lp.y != restored.y) {
                    lp.x = restored.x
                    lp.y = restored.y
                    if (view != null) runCatching { wm.updateViewLayout(view, lp) }
                }
                pillPositionBeforeKeyboard = null
            }
        }
        return Rect(lp.x, lp.y, lp.width, lp.height)
    }

    private fun positionLivePanel(anchor: Anchor) {
        val panel = livePanel ?: return
        val panelParams = liveParams ?: return
        if (!livePanelAdded) return
        val dp = resources.displayMetrics.density
        val fullScreen = screenRect()
        val screen = screenAboveKeyboard(fullScreen)
        val screenChanged = screen != lastPanelScreen
        lastPanelScreen = screen
        val inNote = purpose == DictationPurpose.NOTE
        val format = activeRun?.formatOptions?.format ?: PostProcessingFormats(this).selected()
        val formatLabel = if (format.id == "cleanup") "Texte sans LLM" else format.name
        panelTitle?.apply {
            text = formatLabel
            contentDescription = "Format choisi : $formatLabel"
        }
        noteInsertButton?.visibility = if (inNote) View.VISIBLE else View.GONE
        noteDoneButton?.visibility = if (inNote) View.VISIBLE else View.GONE
        listOfNotNull(noteInsertButton, noteDoneButton).forEach {
            it.isEnabled = isTranscriptEditable()
            it.alpha = if (it.isEnabled) 1f else .45f
        }
        panelExpandButton?.setImageResource(if (panelExpanded) R.drawable.ic_panel_restore else R.drawable.ic_panel_expand)
        panelExpandButton?.contentDescription = if (panelExpanded) "Réduire le panneau" else "Agrandir le panneau"
        val actualPill = pillRectForPanel(fullScreen, screen)
        val pointerLength = bubblePointerLength.coerceAtMost((12 * dp).toInt().coerceAtLeast(0))
        val panelGap = maxOf((6 * dp).toInt(), pointerLength + (2 * dp).toInt())
        val desiredWidth = ((if (panelExpanded) 600 else 312) * dp).toInt()
        val desiredHeight = if (panelExpanded) (screen.height - (12 * dp).toInt()).coerceAtLeast(1) else
            (liveText?.lineHeight ?: 20) * 4 + (188 * dp).toInt()
        val edge = OverlayPlacement.viablePanelEdge(
            anchor.edge, actualPill, screen, desiredWidth, desiredHeight, panelGap,
        )
        val bodyBounds = OverlayPlacement.panelBounds(
            edge,
            actualPill,
            screen,
            desiredWidth,
            desiredHeight,
            panelGap,
        )
        val bounds = OverlayPlacement.bubbleEnvelope(bodyBounds, edge, pointerLength).window
        val edgeChanged = panelEdge != null && panelEdge != edge
        val pointerTargetX = (actualPill.centerX - bounds.x).toFloat()
        val pointerTargetY = (actualPill.centerY - bounds.y).toFloat()
        val pointerChanged = bubblePointerTargetX != pointerTargetX || bubblePointerTargetY != pointerTargetY
        val boundsChanged = panelParams.x != bounds.x || panelParams.y != bounds.y ||
            panelParams.width != bounds.width || panelParams.height != bounds.height
        if (!screenChanged && panelTransitionAnimator != null && panelTransitionTarget == bounds && !pointerChanged) {
            // launchNoteExport adds its child after the first reposition. Keep the
            // already running resize instead of snapping to the same target.
            panelTransitionRequested = false
            return
        }
        if (screenChanged || boundsChanged || pointerChanged || edgeChanged) {
            val animate = panelTransitionRequested && boundsChanged && !edgeChanged &&
                panel.visibility == View.VISIBLE && livePanelAdded && panelAnimationsAllowed()
            panelTransitionRequested = false
            if (animate) {
                animatePanelBounds(panel, panelParams, bounds, edge, pointerLength, dp)
            } else {
                cancelPanelTransition()
                applyPanelBounds(panel, panelParams, bounds, edge, pointerLength, dp)
            }
        }
    }

    /** Apply panel geometry in one place so repositioning can cancel a transition safely. */
    private fun applyPanelBounds(
        panel: View,
        panelParams: WindowManager.LayoutParams,
        bounds: Rect,
        edge: Edge,
        pointerLength: Int,
        dp: Float,
    ) {
        panelParams.x = bounds.x
        panelParams.y = bounds.y
        panelParams.width = bounds.width
        panelParams.height = bounds.height
        val envelope = OverlayPlacement.bubbleEnvelope(
            Rect(0, 0,
                when (edge) {
                    Edge.LEFT, Edge.RIGHT -> (bounds.width - pointerLength).coerceAtLeast(1)
                    else -> bounds.width
                },
                when (edge) {
                    Edge.TOP, Edge.BOTTOM -> (bounds.height - pointerLength).coerceAtLeast(1)
                    else -> bounds.height
                }),
            edge,
            pointerLength,
        )
        val bodyLayout = livePanelBody?.layoutParams as? FrameLayout.LayoutParams
        bodyLayout?.apply {
            width = when (edge) {
                Edge.LEFT, Edge.RIGHT -> (bounds.width - pointerLength).coerceAtLeast(1)
                else -> bounds.width
            }
            height = when (edge) {
                Edge.TOP, Edge.BOTTOM -> (bounds.height - pointerLength).coerceAtLeast(1)
                else -> bounds.height
            }
            leftMargin = envelope.bodyOffsetX
            topMargin = envelope.bodyOffsetY
            livePanelBody?.layoutParams = this
        }
        val pointerTargetX = params?.let { it.x + it.width / 2 - bounds.x }?.toFloat() ?: 0f
        val pointerTargetY = params?.let { it.y + it.height / 2 - bounds.y }?.toFloat() ?: 0f
        bubblePointer?.setGeometry(
            edge = edge,
            bodyOffsetX = envelope.bodyOffsetX,
            bodyOffsetY = envelope.bodyOffsetY,
            bodyWidth = bodyLayout?.width ?: bounds.width,
            bodyHeight = bodyLayout?.height ?: bounds.height,
            pointerLength = pointerLength,
            targetX = pointerTargetX,
            targetY = pointerTargetY,
        )
        bubblePointerTargetX = pointerTargetX
        bubblePointerTargetY = pointerTargetY
        panelEdge = edge
        layoutTranscriptRows(
            when (edge) {
                Edge.TOP, Edge.BOTTOM -> (bounds.height - pointerLength).coerceAtLeast(1)
                else -> bounds.height
            },
            dp,
        )
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, panelParams) } catch (_: Exception) {}
    }

    private fun animatePanelBounds(
        panel: View,
        panelParams: WindowManager.LayoutParams,
        target: Rect,
        edge: Edge,
        pointerLength: Int,
        dp: Float,
    ) {
        val from = Rect(panelParams.x, panelParams.y, panelParams.width, panelParams.height)
        val to = target.copy()
        cancelPanelTransition()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        panelTransitionAnimator = animator
        panelTransitionTarget = to
        panel.alpha = 0.88f
        animator.duration = 180L
        animator.addUpdateListener { valueAnimator ->
            if (panelTransitionAnimator !== animator || !livePanelAdded || panel.visibility != View.VISIBLE) return@addUpdateListener
            val fraction = valueAnimator.animatedFraction
            applyPanelBounds(
                panel,
                panelParams,
                Rect(
                    lerp(from.x, to.x, fraction),
                    lerp(from.y, to.y, fraction),
                    lerp(from.width, to.width, fraction),
                    lerp(from.height, to.height, fraction),
                ),
                edge,
                pointerLength,
                dp,
            )
            panel.alpha = 0.88f + 0.12f * fraction
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (panelTransitionAnimator !== animator) return
                panelTransitionAnimator = null
                panelTransitionTarget = null
                applyPanelBounds(panel, panelParams, to, edge, pointerLength, dp)
                panel.alpha = 1f
            }

            override fun onAnimationCancel(animation: Animator) {
                if (panelTransitionAnimator === animator) {
                    panelTransitionAnimator = null
                    panelTransitionTarget = null
                    panel.alpha = 1f
                }
            }
        })
        animator.start()
    }

    private fun cancelPanelTransition() {
        panelTransitionRequested = false
        panelTransitionAnimator?.cancel()
        panelTransitionAnimator = null
        panelTransitionTarget = null
        livePanel?.alpha = 1f
    }

    private fun requestPanelTransition() {
        panelTransitionRequested = true
    }

    private fun panelAnimationsAllowed(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }.getOrDefault(true)

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).toInt()

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
                setColor(overlayPalette.surface)
                setStroke(1, overlayPalette.stroke)
            }
            elevation = 4 * dp
            setPadding(0, 0, 0, 0)
            addView(waveView)
            addView(loaderView)
            pauseIndicator = TextView(this@OverlayService).apply {
                text = "Ⅱ"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(overlayPalette.pauseInk)
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
            setTextColor(overlayPalette.inkMuted)
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(overlayPalette.raised)
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        this.gestureHint = gestureHint
        pillView.addView(gestureHint, FrameLayout.LayoutParams(-1, -1))

        val liveView = object : OverlayTranscriptEditor(this) {
            override fun onSelectionChanged(start: Int, end: Int) {
                super.onSelectionChanged(start, end)
                if (liveText === this && !updatingLiveText && !liveEditorChanging && isTranscriptEditable()) {
                    tailFollower?.userInteraction()
                    vocabularyTracker.onSelectionChanged(text.toString(), start, end)
                    scheduleVocabularySuggestion(resetTimer = false)
                }
            }
        }.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(overlayPalette.ink)
            background = null
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
            hint = "Touchez pour corriger pendant la dictée"
            setHintTextColor(overlayPalette.inkMuted)
            canEdit = { isTranscriptEditable() }
            acquireWindow = {
                liveParams?.let { layout ->
                    if (layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                        layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        runCatching { wm.updateViewLayout(this@OverlayService.livePanel, layout) }
                    }
                }
            }
            finishEditing = { releaseTranscriptFocus() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    liveEditorChanging = true
                    if (!updatingLiveText && isTranscriptEditable()) {
                        tailFollower?.userInteraction()
                        vocabularyTracker.beforeChange(s.toString(), start, count, after, selectionStart, selectionEnd)
                        vocabularySuggestion = null
                        vocabularyDismiss?.let(main::removeCallbacks)
                        vocabularyDismiss = null
                        setVocabularySuggestionVisible(false)
                    }
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!updatingLiveText && isTranscriptEditable()) {
                        invalidateNoteInsertion()
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
                false
            }
            textSize = 16f
            includeFontPadding = false
            gravity = Gravity.START
            setLineSpacing(4 * dp, 1.0f)
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
        tailFollower = TranscriptTailFollower(liveView, scroll, editing = { liveView.isEditing }) { state == State.RECORDING && activeRun != null }
        val panelBody = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(overlayPalette.surface)
                setStroke(dp.toInt().coerceAtLeast(1), overlayPalette.stroke)
            }
            clipToOutline = true
            elevation = 8 * dp
            addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = (48 * dp).toInt() })
        }
        val pointer = OverlayBubblePointerView(this).apply {
            setColors(overlayPalette.surface, overlayPalette.stroke)
        }
        val livePanel = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            elevation = 8 * dp
            addView(panelBody, FrameLayout.LayoutParams(livePanelW, livePanelH))
            // The decorative tail is above the body's border only at its short attachment area;
            // this lets the shared surface hide the otherwise straight seam without changing
            // the body's clipping for transcript content.
            addView(pointer, FrameLayout.LayoutParams(-1, -1))
        }
        livePanel.setOnApplyWindowInsetsListener { _, insets ->
            val nextInset = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            keyboardInset = nextInset
            // ADJUST_NOTHING overlays can receive 0 both before and after the
            // IME moves. Re-evaluate the absolute metrics/frame on every inset
            // dispatch; positionLivePanel only updates WindowManager when the
            // calculated geometry actually changed.
            repositionPanelIfVisibleScreenChanged()
            insets
        }
        livePanel.viewTreeObserver.addOnGlobalLayoutListener {
            repositionPanelIfVisibleScreenChanged()
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        panelTitle = TextView(this).apply {
            text = "Texte sans LLM"; textSize = 17f; setTextColor(overlayPalette.ink); gravity = Gravity.CENTER_VERTICAL
            runCatching { typeface = resources.getFont(R.font.caveat) }
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        toolbar.addView(panelTitle, LinearLayout.LayoutParams(0, -1, 1f))
        val stateMark = OverlayStateIndicatorView(this).apply {
            setVisualState(OverlayStateIndicatorView.VisualState.IDLE)
            contentDescription = "État de la dictée"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        stateIndicator = stateMark
        toolbar.addView(stateMark, LinearLayout.LayoutParams((40 * dp).toInt(), (48 * dp).toInt()))
        fun panelIcon(icon: Int, label: String, action: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(overlayPalette.ink)
            contentDescription = label
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(overlayWithAlpha(overlayPalette.green, 0x55)), null, null,
            )
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { action() }
        }
        val expand = panelIcon(R.drawable.ic_panel_expand, "Agrandir le panneau") {
            requestPanelTransition()
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
        panelBody.addView(toolbar, FrameLayout.LayoutParams(-1, (48 * dp).toInt(), Gravity.TOP))
        val editorActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        fun editorAction(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(overlayPalette.green)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(overlayWithAlpha(overlayPalette.green, 0x44)),
                GradientDrawable().apply { cornerRadius = 18 * dp; setColor(overlayPalette.raised) }, null)
            setOnClickListener { action() }
            editorActions.addView(this, LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply {
                leftMargin = (3 * dp).toInt(); rightMargin = (3 * dp).toInt()
            })
        }
        noteInsertButton = editorAction("Insérer…", ::requestNoteInsertion)
        noteDoneButton = editorAction("Terminer", ::archiveOrShowNotes)
        editorActionsRow = editorActions
        panelBody.addView(editorActions, FrameLayout.LayoutParams(-1, (48 * dp).toInt()))

        val mediaRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        mediaToolbar = mediaRow
        listOf(
            Triple(R.drawable.ic_note_screenshot, "Capturer l’écran, enregistrer dans Photos et copier", { captureNoteImage(NoteImageKind.SCREENSHOT) }),
            Triple(R.drawable.ic_note_camera, "Prendre une photo, enregistrer dans Photos et copier", { captureNoteImage(NoteImageKind.CAMERA) }),
            Triple(R.drawable.ic_note_share, "Partager ou exporter la note avec ses images", { exportNoteWithImages(automatic = false) }),
        ).forEach { (icon, label, action) ->
            val button = panelIcon(icon, label, action)
            mediaButtons += button
            mediaRow.addView(button, LinearLayout.LayoutParams((48 * dp).toInt(), -1))
        }
        imageStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val stripScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(imageStrip, android.view.ViewGroup.LayoutParams(-2, -1))
        }
        imageStripScroll = stripScroll
        mediaRow.addView(stripScroll, LinearLayout.LayoutParams(0, -1, 1f))
        panelBody.addView(mediaRow, FrameLayout.LayoutParams(-1, (48 * dp).toInt()))

        val vocabRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(overlayPalette.raised) }
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        vocabularySuggestionText = TextView(this).apply {
            textSize = 12f
            setTextColor(overlayPalette.green)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { saveVocabularySuggestion() }
        }
        vocabRow.addView(vocabularySuggestionText, LinearLayout.LayoutParams(0, -1, 1f))
        vocabRow.addView(TextView(this).apply {
            text = "×"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(overlayPalette.inkMuted)
            contentDescription = "Ignorer cette suggestion de vocabulaire"
            setOnClickListener { resetVocabularyLearning() }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), -1))
        vocabularyBanner = vocabRow
        panelBody.addView(vocabRow, FrameLayout.LayoutParams(-1, (48 * dp).toInt()))

        // La fenêtre interactive ne contient que la pastille et garde sa taille fixe.
        val lp = WindowManager.LayoutParams(
            pillW, pillH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
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
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            // IME insets are handled by positionLivePanel; do not resize the window twice.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
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
            gestureHint.text = if (left > 0f) "← Notes" else if (up > 0f) {
                when {
                    purpose == DictationPurpose.NOTE -> "↑ Note"
                    panelHidden && isTranscriptEditable() -> "↑ Texte"
                    state == State.PAUSED || state == State.PAUSING -> "↑ Insérer"
                    isTranscriptEditable() -> "↑ Texte"
                    else -> "↑ Format"
                }
            } else "↓ Pause"
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
            pillPositionBeforeKeyboard = null
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
            // The pointer follows the live window coordinates and the provisional edge while a
            // drag is in progress; waiting for ACTION_UP would leave it attached to the old side.
            currentAnchor = dragAnchor
            updatePillLayout(dragAnchor)
        }

        pillView.setOnTouchListener { _, ev ->
            if (exportPanel != null) return@setOnTouchListener true
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
                            if (purpose == DictationPurpose.NOTE || panelHidden) {
                                panelHidden = false
                                setLivePreviewVisible(true)
                            }
                            else if (run != null) {
                                run.resumeAfterPause = false
                                run.finishAfterPause = true
                                if (state == State.PAUSED) stopRec()
                            } else insertPausedMessage()
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
        liveText = liveView
        liveScroll = scroll
        this.livePanel = livePanel
        livePanelBody = panelBody
        bubblePointer = pointer
        bubblePointerLength = (12 * dp).toInt().coerceAtLeast(1)
        params = lp
        liveParams = panelParams
        currentAnchor = initialAnchor
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
        val nextNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val themeChanged = nextNightMode != Configuration.UI_MODE_NIGHT_UNDEFINED && nextNightMode != lastNightMode
        lastNightMode = nextNightMode
        super.onConfigurationChanged(newConfig)
        // A night-mode transition should keep an open notes/format menu and the active
        // transcript intact. Other configuration changes still dismiss transient menus before
        // their old geometry is used again.
        if (!themeChanged) dismissFloatingMenu()
        cancelPanelTransition()
        // A saved temporary keyboard lift belongs to the old display geometry. Rebuild from the
        // normalized anchor after rotation/density changes instead of restoring stale pixels.
        pillPositionBeforeKeyboard = null
        val lp = params ?: return
        val dp = resources.displayMetrics.density
        baseButtonW = (74 * dp).toInt()
        baseButtonH = (44 * dp).toInt()
        lp.width = baseButtonW
        lp.height = baseButtonH
        val panelParams = liveParams
        val text = liveText
        lastPanelScreen = null
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
        if (themeChanged) refreshOverlayTheme()
    }

    private fun captureNoteImage(kind: NoteImageKind) {
        if (!isTranscriptEditable() || imageStore.pending() != null || imageDeliveryBusy) return
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            toast("Déverrouillez le téléphone pour capturer une image.")
            return
        }
        if (kind == NoteImageKind.SCREENSHOT && WhisperAccessibilityService.connected == null) {
            toast("Activez le service d’accessibilité DictAI pour capturer l’écran.")
            return
        }
        val pending = runCatching { imageStore.beginClipboard(kind, state == State.RECORDING, NoteImage.nextNumber(
            notes.get(activeNoteId)?.images.orEmpty() + draftStore.captures().mapNotNull { it.image }, liveText?.text?.toString().orEmpty())) }.getOrElse {
            toast("Capture indisponible : vérifiez l’espace de stockage."); return
        }
        draftStore.save(liveText?.text?.toString().orEmpty())
        draftStore.reserveCapture(pending.id, notes.get(activeNoteId)?.images?.size ?: 0)
        refreshNoteImages()
        releaseTranscriptFocus()
        dismissFloatingMenu()
        formatDialog?.dismiss()
        if (kind == NoteImageKind.CAMERA) {
            if (state == State.RECORDING) pauseRec()
            launchCameraWhenPaused(pending)
        } else {
            captureWindowsHidden = true
            container?.visibility = View.INVISIBLE
            setLivePreviewVisible(false)
            // Let compositor and IME consume the hide before taking the display snapshot.
            main.postDelayed({
                if (imageStore.pending()?.let { it.id == pending.id && !it.complete } != true) return@postDelayed
                val accessibility = WhisperAccessibilityService.connected
                if (localEngineLifecycle.isDestroyed()) {
                    imageStore.fail(pending.id, "Capture interrompue.")
                    return@postDelayed
                }
                if (accessibility == null) {
                    restoreCaptureWindows()
                    imageStore.fail(pending.id, "Service de capture indisponible.")
                    finishPendingImage()
                } else NoteScreenshot.capture(accessibility, imageStore, pending.id,
                    { if (imageStore.pending()?.id == pending.id) restoreCaptureWindows() }, ::finishPendingImage)
            }, 250)
        }
    }

    private fun launchCameraWhenPaused(pending: PendingNoteCapture) {
        if (localEngineLifecycle.isDestroyed() || imageStore.pending()?.id != pending.id) return
        if (state == State.PAUSING) { main.postDelayed({ launchCameraWhenPaused(pending) }, 60); return }
        if (state != State.PAUSED) {
            imageStore.fail(pending.id, "Photo interrompue : note conservée.")
            finishPendingImage()
            return
        }
        try {
            captureWindowsHidden = true
            // The in-app viewfinder owns camera access while visible; keep the pill available.
            container?.visibility = View.VISIBLE
            setLivePreviewVisible(false)
            startActivity(Intent(this, NoteCameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("captureId", pending.id))
        } catch (_: Exception) {
            restoreCaptureWindows()
            imageStore.fail(pending.id, "Impossible d’ouvrir l’appareil photo.")
            finishPendingImage()
        }
    }

    private fun restoreCaptureWindows() {
        if (localEngineLifecycle.isDestroyed()) return
        captureWindowsHidden = false
        container?.visibility = View.VISIBLE
        setLivePreviewVisible(isTranscriptEditable())
    }

    private fun recoverPendingImage() {
        val pending = imageStore.pending() ?: return
        if (!pending.complete && pending.kind == NoteImageKind.SCREENSHOT)
            imageStore.fail(pending.id, "Capture interrompue : texte conservé.")
        if (!pending.complete && pending.kind == NoteImageKind.CAMERA && !NoteCameraActivity.handles(pending.id))
            imageStore.fail(pending.id, "Photo interrompue : texte conservé.")
        // A surviving in-app viewfinder may still finish its pending capture after service recovery.
        finishPendingImage()
    }

    private fun finishPendingImage() {
        if (localEngineLifecycle.isDestroyed() || imageDeliveryBusy) return
        val pending = imageStore.pending()?.takeIf { it.complete } ?: return
        restoreCaptureWindows()
        // Complete an old in-flight contextual capture after an update without losing its image.
        if (!pending.clipboardOnly) {
            val note = notes.get(pending.noteId)
            if (note == null) imageStore.delete(pending.id)
            else if (pending.image != null && note.images.none { it.id == pending.id } && note.images.size < NoteImage.MAX_IMAGES)
                notes.save(note.id, note.text, note.images + pending.image)
            else if (pending.image == null) {
                val text = NoteImageMarkers.remove(note.text, pending.number)
                notes.save(note.id, text)
                if (activeNoteId == note.id) replaceNoteText(text)
            }
        }
        imageStore.clearPending(pending.id)
        refreshNoteImages()
        if (pending.image != null) {
            draftStore.completeCapture(pending.image)
            if (activeNoteId != null && pending.clipboardOnly) {
                val saved = saveNoteWithCaptures(liveText?.text?.toString().orEmpty())
                replaceNoteText(saved.text)
            }
            val retained = draftStore.captures().any { it.id == pending.id } ||
                notes.get(activeNoteId)?.images?.any { it.id == pending.id } == true || !pending.clipboardOnly
            copyCapturedImage(pending.image, discardSource = !retained, saveInGallery = true)
        } else {
            draftStore.removeCapture(pending.id)
            pending.error?.let(::toast)
        }
        if (activeRun?.finishAfterCapture == true && state == State.PAUSED) stopRec()
        else if (pending.kind == NoteImageKind.CAMERA && pending.resumeListening && !archiveAfterImageDelivery && activeRun != null && state == State.PAUSED)
            resumeRec()
        if (pending.image == null) finishImageDelivery()
    }

    private fun copyCapturedImage(image: NoteImage, discardSource: Boolean = false, saveInGallery: Boolean = false) {
        imageDeliveryBusy = true
        refreshNoteImages()
        thread(name = "dictai-image-clipboard") {
            // Copy the already resized JPEG bytes: no numbering, second encoding or LLM.
            val gallery = if (saveInGallery) runCatching { CapturedImageGallery.save(this, image) } else null
            val prepared = gallery?.getOrNull()?.let { Result.success(it) }
                ?: runCatching { NoteImagePaste.prepare(this, image) }
            if (discardSource && prepared.isSuccess) imageStore.delete(image.id)
            main.post {
                if (localEngineLifecycle.isDestroyed()) return@post
                val copied = prepared.getOrNull()?.let { NoteImagePaste.copy(this, it) } == true
                if (copied) preserveImageClipboard = true
                val saved = gallery?.isSuccess == true
                NoteImagePaste.recordCopy(this, image.kind, copied, saved)
                toast(when {
                    saved && copied -> "Image enregistrée dans Photos → DictAI et copiée."
                    saved -> "Image enregistrée dans Photos → DictAI. Copie au presse-papier indisponible."
                    saveInGallery && copied -> "Image copiée ; enregistrement dans Photos impossible. Vérifiez l’espace disponible."
                    copied -> "Image copiée. Collez-la depuis Gboard."
                    else -> "Impossible de copier l’image. Vérifiez l’espace disponible puis réessayez."
                })
                if (saved && discardSource) toast("La note est limitée à 10 images ; cette capture reste disponible dans Photos → DictAI.")
                finishImageDelivery()
            }
        }
    }

    private fun finishImageDelivery() {
        imageDeliveryBusy = false
        refreshNoteImages()
        if (archiveAfterImageDelivery) {
            archiveAfterImageDelivery = false
            archiveOrShowNotes()
            return
        }
        if (activeRun?.finishAfterCapture == true && state == State.PAUSED) stopRec()
        val queuedNote = exportAfterImageDelivery
        exportAfterImageDelivery = null
        if (queuedNote != null && activeRun == null && activeNoteId == queuedNote && state == State.PAUSED) insertPausedMessage()
    }

    private fun replaceNoteText(text: String) {
        resetVocabularyLearning()
        editableTranscript.anchor(text)
        updatingLiveText = true
        try { liveText?.setText(text) } finally { updatingLiveText = false }
        if (recoveredDraft != null || activeRun == null) recoveredDraft = text
        persistDraft(text)
        tailFollower?.changed()
    }

    private fun refreshNoteImages() {
        val images = notes.get(activeNoteId)?.images.orEmpty() + draftStore.captures().mapNotNull { it.image }
        val pending = imageStore.pending()
        val editable = isTranscriptEditable() && !imageDeliveryBusy
        val showStrip = images.isNotEmpty() || pending != null
        imageStripScroll?.visibility = if (showStrip) View.VISIBLE else View.GONE
        mediaButtons.forEach { button ->
            val current = button.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            current.width = if (showStrip) (48 * resources.displayMetrics.density).toInt() else 0
            current.weight = if (showStrip) 0f else 1f
            button.layoutParams = current
        }
        mediaButtons.forEach { button ->
            button.isEnabled = editable && pending == null
            button.alpha = if (button.isEnabled) 1f else .4f
        }
        val strip = imageStrip ?: return
        val ids = images.map { "${it.id}:${it.number}" } + listOfNotNull(pending?.id)
        if (shownImageIds == ids && strip.childCount > 0) return
        shownImageIds = ids
        strip.removeAllViews()
        val dp = resources.displayMetrics.density
        if (images.isEmpty() || pending != null) strip.addView(TextView(this).apply {
            text = if (pending != null) "Capture… ×" else ""; textSize = 11f; setTextColor(overlayPalette.inkMuted)
            minWidth = (48 * dp).toInt(); minHeight = (40 * dp).toInt()
            contentDescription = if (pending != null) "Annuler la capture en attente" else ""
            if (pending != null) setOnClickListener {
                imageStore.fail(pending.id, "Capture annulée.")
                finishPendingImage()
            }
        })
        images.forEach { image ->
            val frame = FrameLayout(this)
            val thumb = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = "Image ${image.number}, ${image.kind.label}. Appuyer pour voir, maintenir pour retirer."
                setOnClickListener { previewNoteImage(image) }
                setOnLongClickListener {
                    if (imageStore.pending() == null && !imageDeliveryBusy && isTranscriptEditable()) showFloatingMenu("Image ${image.number}", listOf(
                        MenuEntry("Copier l’image", {
                            dismissFloatingMenu()
                            copyCapturedImage(image)
                        }),
                        MenuEntry("Retirer cette image de la note", { removeNoteImage(image) }),
                        MenuEntry("Retour", ::dismissFloatingMenu),
                    ))
                    true
                }
            }
            frame.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            frame.addView(TextView(this).apply {
                text = image.number.toString(); textSize = 10f; setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0xBB000000.toInt()); setPadding((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            strip.addView(frame, LinearLayout.LayoutParams((44 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (4 * dp).toInt() })
            thread(name = "dictai-note-thumbnail") {
                val bitmap = runCatching { NoteImageStore.decode(imageStore.thumbnail(image.id), 160) }.getOrNull()
                main.post { if (!localEngineLifecycle.isDestroyed() && shownImageIds.any { it.startsWith(image.id + ":") }) thumb.setImageBitmap(bitmap) else bitmap?.recycle() }
            }
        }
    }

    private fun previewNoteImage(image: NoteImage) {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        val dialogContext = overlayDialogContext()
        val view = android.widget.ImageView(dialogContext).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            minimumHeight = (200 * resources.displayMetrics.density).toInt()
        }
        val dialog = AlertDialog.Builder(dialogContext).setTitle("Image ${image.number} · ${image.kind.label}")
            .setView(view).setPositiveButton("Fermer", null).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { view.setImageDrawable(null); if (formatDialog === dialog) formatDialog = null }
        dialog.show()
        thread(name = "dictai-note-preview") {
            val bitmap = runCatching { NoteImageStore.decode(imageStore.file(image.id), 1600) }.getOrNull()
            main.post { if (dialog.isShowing) view.setImageBitmap(bitmap) else bitmap?.recycle() }
        }
    }

    private fun removeNoteImage(image: NoteImage) {
        if (!isTranscriptEditable() || imageDeliveryBusy || imageStore.pending() != null) return
        val note = notes.get(activeNoteId)
        if (note == null || note.images.none { it.id == image.id }) {
            draftStore.removeCapture(image.id)
            dismissFloatingMenu()
            refreshNoteImages()
            return
        }
        dismissFloatingMenu()
        val text = NoteImageMarkers.remove(liveText?.text?.toString().orEmpty(), image.number)
        notes.save(note.id, text, note.images.filter { it.id != image.id })
        replaceNoteText(text)
        imageStore.delete(image.id)
        refreshNoteImages()
    }

    private fun exportNoteWithImages(automatic: Boolean = true) {
        if (!isTranscriptEditable() || imageStore.pending() != null) return
        val run = activeRun
        if (run != null) {
            run.exportNote = true
            run.automaticNoteShare = automatic
            run.finishAfterPause = true
            if (state != State.PAUSING) stopRec()
        } else {
            val note = saveNoteWithCaptures(liveText?.text?.toString().orEmpty())
            replaceNoteText(note.text)
            activeNoteId = note.id
            draftStore.noteId = note.id
            launchNoteExport(note, automatic = automatic)
        }
    }

    private fun isDeviceUnlockedForExport(): Boolean {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            toast("Déverrouillez le téléphone pour exporter la note.")
            return false
        }
        return true
    }

    private fun launchNoteExport(note: TranscriptNote, automatic: Boolean = false) {
        if (exportPanel != null) return
        if (!isDeviceUnlockedForExport()) return
        val fromNotesMenu = floatingMenu != null
        dismissFloatingMenu()
        val editor = liveText
        exportResume = ExportResume(
            liveVisible = livePreviewVisible,
            panelHidden = panelHidden,
            panelExpanded = panelExpanded,
            purpose = purpose,
            activeNoteId = activeNoteId,
            recoveredDraft = recoveredDraft,
            selectionStart = editor?.selectionStart ?: -1,
            selectionEnd = editor?.selectionEnd ?: -1,
            fromNotesMenu = fromNotesMenu,
        )
        exportNoteId = note.id
        releaseTranscriptFocus()
        panelHidden = false
        requestPanelTransition()
        panelExpanded = true
        setLivePreviewVisible(true)
        val export = OverlayExportPanel(
            this,
            note,
            onBack = ::closeNoteExport,
            onFormat = { format -> prepareNoteExport(note, format) },
            onSave = { result -> openNoteExportBridge(result, note, share = false) },
            onShare = { result -> openNoteExportBridge(result, note, share = true) },
        )
        exportPanel = export
        livePanelBody?.let { host ->
            for (index in 0 until host.childCount) {
                val child = host.getChildAt(index)
                exportAccessibilityPrevious[child] = child.importantForAccessibility
                child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        livePanelBody?.addView(export, FrameLayout.LayoutParams(-1, -1))
        currentAnchor?.let(::positionLivePanel)
        prepareNoteExport(note, OverlayExportFormat.PDF, automatic)
    }

    private fun prepareNoteExport(
        note: TranscriptNote,
        format: OverlayExportFormat,
        automaticShare: Boolean = false,
    ) {
        val panel = exportPanel ?: return
        if (exportNoteId != note.id) return
        panel.showPreparing(format)
        exportController.prepare(note, format) { result ->
            if (exportPanel !== panel || exportNoteId != note.id) {
                result.getOrNull()?.directory?.deleteRecursively()
                return@prepare
            }
            result.fold(
                onSuccess = { ready ->
                    panel.showResult(ready)
                    if (automaticShare) openNoteExportBridge(ready, note, share = true)
                },
                onFailure = {
                    panel.showError(format, "Export impossible. La note est conservée ; réessayez.")
                },
            )
        }
    }

    private fun openNoteExportBridge(result: OverlayExportResult, note: TranscriptNote, share: Boolean) {
        if (!isDeviceUnlockedForExport()) return
        val action = if (share) "share" else "save"
        val bridgeToken = UUID.randomUUID().toString()
        exportBridgeToken = bridgeToken
        hideForExportBridge()
        val shareText = if (result.format == OverlayExportFormat.TEXT_IMAGES) NoteShareText.create(note)
            .takeIf { it.length <= 80_000 } else null
        val bridgeIntent = Intent(this, NoteExportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("bridgeAction", action)
            .putExtra("exportDirectory", result.directory.absolutePath)
            .putExtra("exportFormat", result.format.name)
            .putExtra(NoteExportActivity.EXTRA_BRIDGE_TOKEN, bridgeToken)
        shareText?.let { bridgeIntent.putExtra("noteText", it) }
        runCatching {
            startActivity(bridgeIntent)
        }.onFailure {
            if (exportBridgeToken == bridgeToken) exportBridgeToken = null
            restoreAfterExportBridge()
            toast("Sélecteur de fichiers indisponible. La note est conservée.")
        }
    }

    private fun hideForExportBridge() {
        if (exportBridgeHidden) return
        exportBridgeHidden = true
        bridgeWasLiveVisible = livePreviewVisible
        bridgeWasContainerVisible = container?.visibility == View.VISIBLE
        container?.visibility = View.INVISIBLE
        livePanel?.visibility = View.GONE
        livePreviewVisible = false
        releaseTranscriptFocus()
    }

    private fun restoreAfterExportBridge() {
        if (!exportBridgeHidden) return
        exportBridgeToken = null
        exportBridgeHidden = false
        container?.visibility = if (bridgeWasContainerVisible) View.VISIBLE else View.GONE
        livePreviewVisible = bridgeWasLiveVisible
        livePanel?.visibility = if (bridgeWasLiveVisible) View.VISIBLE else View.GONE
        if (bridgeWasLiveVisible) currentAnchor?.let(::positionLivePanel)
    }

    private fun closeNoteExport() {
        val panel = exportPanel ?: return
        exportController.invalidate()
        panel.dispose()
        panel.parent?.let { (it as? ViewGroup)?.removeView(panel) }
        exportAccessibilityPrevious.forEach { (view, previous) -> view.importantForAccessibility = previous }
        exportAccessibilityPrevious.clear()
        exportPanel = null
        exportNoteId = null
        exportBridgeToken = null
        val resume = exportResume
        exportResume = null
        restoreAfterExportBridge()
        if (resume == null) {
            panelExpanded = false
            setLivePreviewVisible(false)
            return
        }
        panelExpanded = resume.panelExpanded
        panelHidden = resume.panelHidden
        purpose = resume.purpose
        activeNoteId = resume.activeNoteId
        recoveredDraft = resume.recoveredDraft
        if (resume.liveVisible) {
            requestPanelTransition()
            setLivePreviewVisible(true)
        } else setLivePreviewVisible(false)
        currentAnchor?.let(::positionLivePanel)
        val editor = liveText
        if (resume.selectionStart >= 0 && resume.selectionEnd >= 0 && editor != null) {
            editor.post {
                val start = resume.selectionStart.coerceIn(0, editor.length())
                val end = resume.selectionEnd.coerceIn(0, editor.length())
                runCatching { editor.setSelection(start, end) }
            }
        }
        if (resume.fromNotesMenu) showNotesOverlay()
    }

    /** Explicit note action promotes the draft's images and inserts their positional references. */
    private fun saveNoteWithCaptures(text: String): TranscriptNote {
        purpose = DictationPurpose.NOTE
        draftStore.purpose = purpose
        draftStore.save(text)
        val content = DraftImageContext.materialize(text, notes.get(activeNoteId)?.images.orEmpty(), draftStore.captures())
        val note = notes.save(activeNoteId, content.text, content.images)
        draftStore.detachCaptures(content.attachedIds)
        activeNoteId = note.id
        draftStore.noteId = note.id
        draftStore.save(note.text)
        return note
    }

    private fun persistDraft(text: String) {
        draftStore.purpose = purpose
        draftStore.save(text)
        activeNoteId?.let { id ->
            if (notes.get(id)?.text != text) notes.save(id, text)
        }
    }

    private fun clearOpenDraft() {
        invalidateNoteInsertion()
        exportAfterImageDelivery = null
        archiveAfterImageDelivery = false
        tapCoordinator.reset()
        recoveredDraft = null
        activeNoteId = null
        purpose = DictationPurpose.MESSAGE
        draftStore.clear()
        refreshNoteImages()
        setLivePreviewVisible(false)
        setState(if (micArmed) State.IDLE else State.MIC_UNARMED)
    }

    private fun cancelPausedNote() {
        if (localEngineLifecycle.isDestroyed()) return
        if (imageDeliveryBusy) { main.postDelayed({ cancelPausedNote() }, 60); return }
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
        invalidateNoteInsertion()
        tapCoordinator.reset()
        // The intent changes immediately, even when a photo or final ASR result is pending.
        if (activeRun != null || recoveredDraft != null) {
            purpose = DictationPurpose.NOTE
            draftStore.purpose = purpose
            activeRun?.let { it.archiveAsNote = true; it.reviewNoteInsertionAfterFinish = false }
        }
        if (imageStore.pending() != null || imageDeliveryBusy) { archiveAfterImageDelivery = true; return }
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
            saveNoteWithCaptures(liveText?.text?.toString().orEmpty())
            clearOpenDraft()
            showNotesOverlay()
        } else showNotesOverlay()
    }

    private fun openNote(note: TranscriptNote) {
        if (activeRun != null) return
        invalidateNoteInsertion()
        releaseTranscriptFocus()
        resetVocabularyLearning()
        dismissFloatingMenu()
        purpose = DictationPurpose.NOTE
        draftStore.purpose = purpose
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
        refreshNoteImages()
        draftStore.save(note.text)
        panelHidden = false
        setState(State.PAUSED)
        setLivePreviewVisible(true)
    }

    private fun insertPausedMessage() {
        if (purpose == DictationPurpose.NOTE) { requestNoteInsertion(); return }
        if (!isTranscriptEditable()) return
        if (imageStore.pending() != null) { toast("Terminez la capture en cours."); return }
        if (imageDeliveryBusy) { exportAfterImageDelivery = activeNoteId; return }
        tapCoordinator.reset()
        val text = liveText?.text?.toString().orEmpty()
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
            if (localEngineLifecycle.isDestroyed() || purpose != DictationPurpose.MESSAGE || state != State.TRANSCRIBING) return@post
            val result = runCatching {
                injectOrCopy(InjectionGateway.current(), text, { DictationClipboard.copy(this, it) }, preserveClipboardOnDirectInsert = preserveImageClipboard)
            }.getOrDefault(InjectionResult.Failed)
            injectionFeedbackMessage(result)?.let(::toast)
            if (result != InjectionResult.Failed) {
                preserveImageClipboard = false
                clearOpenDraft()
            }
            else {
                setState(State.PAUSED)
                panelHidden = false
                setLivePreviewVisible(true)
            }
        }
    }

    private fun invalidateNoteInsertion() {
        noteInsertionGate.invalidate()
        noteInsertionDialog?.dismiss()
        noteInsertionDialog = null
    }

    private fun requestNoteInsertion() {
        if (purpose != DictationPurpose.NOTE || !isTranscriptEditable()) return
        if (imageDeliveryBusy || imageStore.pending() != null) { toast("Terminez la capture avant d’insérer le texte."); return }
        invalidateNoteInsertion()
        tapCoordinator.reset()
        activeRun?.let { run ->
            // Finishing prepares a review. It never grants permission to insert its later result.
            run.reviewNoteInsertionAfterFinish = true
            run.resumeAfterPause = false
            run.finishAfterPause = true
            if (state != State.PAUSING) stopRec()
            return
        }
        val note = saveNoteWithCaptures(liveText?.text?.toString().orEmpty())
        replaceNoteText(note.text)
        confirmNoteInsertion(note)
    }

    private fun confirmNoteInsertion(note: TranscriptNote) {
        if (purpose != DictationPurpose.NOTE || activeRun != null || state != State.PAUSED) return
        invalidateNoteInsertion()
        val request = noteInsertionGate.request(note.id, note.text) ?: run { toast("La note ne contient pas de texte à insérer."); return }
        releaseTranscriptFocus()
        var approved = false
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle("Insérer le texte ?")
            .setMessage("Le texte ci-dessous sera déposé dans le champ de l’application ouverte. Votre note restera enregistrée. Pour transmettre les images, utilisez l’export.\n\n${note.text}")
            .setNegativeButton("Rester dans la note", null)
            .setPositiveButton("Insérer le texte") { _, _ ->
                approved = true
                // Reserve this one operation while focus returns to the underlying application.
                liveText?.isEnabled = false
                setState(State.TRANSCRIBING)
                releaseTranscriptFocus()
                main.post {
                    if (localEngineLifecycle.isDestroyed() || purpose != DictationPurpose.NOTE ||
                        activeRun != null || state != State.TRANSCRIBING) { noteInsertionGate.invalidate(); return@post }
                    val text = noteInsertionGate.consume(request, activeNoteId, liveText?.text?.toString().orEmpty())
                    if (text != null) {
                        val result = runCatching {
                            injectOrCopy(InjectionGateway.current(), text, { DictationClipboard.copy(this, it) },
                                preserveClipboardOnDirectInsert = preserveImageClipboard)
                        }.getOrDefault(InjectionResult.Failed)
                        if (result != InjectionResult.Failed) preserveImageClipboard = false
                        toast(injectionFeedbackMessage(result) ?: "Texte inséré. Votre note est conservée.")
                    }
                    liveText?.isEnabled = true
                    setState(State.PAUSED)
                    panelHidden = false
                    setLivePreviewVisible(true)
                }
            }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener {
            if (!approved) noteInsertionGate.invalidate()
            if (noteInsertionDialog === dialog) noteInsertionDialog = null
        }
        noteInsertionDialog = dialog
        dialog.show()
    }

    private data class MenuEntry(
        val label: String, val click: () -> Unit, val longClick: (() -> Unit)? = null,
        val subtitle: String? = null, val metadata: String? = null,
        val trailingAction: (() -> Unit)? = null, val enabled: Boolean = true,
    )

    private fun dismissFloatingMenu() {
        floatingMenu?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        floatingMenu = null
    }

    private fun showFloatingMenu(title: String, entries: List<MenuEntry>, above: Boolean = false) {
        dismissFloatingMenu()
        releaseTranscriptFocus()
        val dp = resources.displayMetrics.density
        val screen = screenRect()
        val width = minOf((320 * dp).toInt(), screen.width)
        val height = minOf(((entries.sumOf { if (it.metadata != null) 144 else 64 }.coerceAtLeast(64) + 56) * dp).toInt(), (screen.height * .65f).toInt())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(overlayPalette.surface); setStroke(dp.toInt().coerceAtLeast(1), overlayPalette.stroke) }
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (6 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "$title   ×"; textSize = 21f; setTextColor(overlayPalette.ink); gravity = Gravity.CENTER
            runCatching { typeface = resources.getFont(R.font.caveat) }
            contentDescription = "$title. Fermer le menu"
            setOnClickListener { dismissFloatingMenu() }
        }, LinearLayout.LayoutParams(-1, (50 * dp).toInt()))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = if (entry.trailingAction == null) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (56 * dp).toInt()
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(overlayWithAlpha(overlayPalette.green, 0x44)),
                    GradientDrawable().apply { cornerRadius = 18 * dp; setColor(overlayPalette.raised) }, null)
                contentDescription = listOfNotNull(entry.label, entry.subtitle, entry.metadata).joinToString(". ")
                isFocusable = true
                isEnabled = entry.enabled
                alpha = if (entry.enabled) 1f else .62f
                if (entry.enabled) setOnClickListener { entry.click() }
                entry.longClick?.let { action -> setOnLongClickListener { action(); true } }
            }
            val textHost = if (entry.trailingAction == null) row else LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun line(value: String, size: Float, color: Int, bold: Boolean = false, spacing: Int = 0) {
                textHost.addView(TextView(this).apply {
                    text = value; textSize = size; setTextColor(color)
                    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (spacing * dp).toInt() })
            }
            line(entry.label, 16f, overlayPalette.ink, bold = entry.metadata != null)
            entry.subtitle?.takeIf { it.isNotBlank() }?.let { line(it, 14f, overlayPalette.inkMuted, spacing = 6) }
            entry.metadata?.let { line(it, 12f, overlayPalette.green, spacing = 8) }
            entry.trailingAction?.let { action ->
                row.addView(textHost, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = "⋮"; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(overlayPalette.ink)
                    contentDescription = "Actions pour ${entry.label}"
                    isClickable = true; isFocusable = true
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = (4 * dp).toInt(); rightMargin = (4 * dp).toInt(); bottomMargin = (6 * dp).toInt()
            })
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
        if (imageStore.pending() != null) { toast("Terminez la capture en cours."); return }
        val entries = mutableListOf(MenuEntry("＋ Nouvelle note", { openNote(notes.save(null, "")) }))
        if (notes.all().isEmpty()) entries += MenuEntry(
            "Aucune note enregistrée", {}, subtitle = "Les notes apparaîtront ici après Terminer.", enabled = false,
        )
        notes.all().forEach { note ->
            val excerpt = note.text.replace(Regex("\\s+"), " ").take(140)
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(note.updatedAt))
            val metadata = "Modifiée le $date" + if (note.images.isEmpty()) "" else " · ${note.images.size} image${if (note.images.size > 1) "s" else ""}"
            val actions = {
                showFloatingMenu(note.title, listOf(
                    MenuEntry("Partager / exporter · texte et images", { launchNoteExport(note) }),
                    MenuEntry("Renommer", { renameNote(note) }),
                    MenuEntry("Supprimer", { notes.delete(note.id); showNotesOverlay() }),
                    MenuEntry("Retour aux notes", ::showNotesOverlay),
                ))
            }
            entries += MenuEntry(
                note.title, { openNote(note) }, actions,
                subtitle = excerpt.ifBlank { "Note vide · Touchez pour écrire" }, metadata = metadata,
                trailingAction = actions,
            )
        }
        showFloatingMenu("Mes notes", entries)
    }

    private fun renameNote(note: TranscriptNote) {
        dismissFloatingMenu()
        val dialogContext = overlayDialogContext()
        val input = EditText(dialogContext).apply { setText(note.title); setSingleLine(); selectAll() }
        val dialog = AlertDialog.Builder(dialogContext).setTitle("Renommer la note").setView(input)
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
                val supported = format.localLayoutKind != null
                if (local && supported) localFormatter.warm()
                toast(when {
                    !format.usesLanguageModel -> "Texte sans LLM"
                    local && !supported && format.usesLanguageModel -> "Le modèle local prend en charge le texte corrigé, les listes et les mails. Pour ce format, choisissez le cloud dans les réglages."
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
        invalidateNoteInsertion()
        resetVocabularyLearning()
        cancelPanelTransition()
        exportController.close()
        exportPanel?.let { panel -> panel.dispose(); panel.parent?.let { (it as? ViewGroup)?.removeView(panel) } }
        exportAccessibilityPrevious.forEach { (view, previous) -> view.importantForAccessibility = previous }
        exportAccessibilityPrevious.clear()
        exportPanel = null
        exportNoteId = null
        exportBridgeToken = null
        exportResume = null
        restoreAfterExportBridge()
        mediaButtons.clear()
        imageStrip = null
        imageStripScroll = null
        mediaToolbar = null
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
        container = null; pill = null; wave = null; loader = null; pauseIndicator = null; gestureHint = null; stateIndicator = null; liveText = null; liveScroll = null; panelExpandButton = null; panelTitle = null; imageStripScroll = null; livePanelBody = null; bubblePointer = null; livePanel = null; liveParams = null; panelEdge = null; pillPositionBeforeKeyboard = null
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
