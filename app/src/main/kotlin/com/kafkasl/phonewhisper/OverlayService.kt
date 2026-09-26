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
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import com.kafkasl.phonewhisper.meeting.MeetingAudioRecord
import com.kafkasl.phonewhisper.meeting.MeetingDocumentRead
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnership
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipClaim
import com.kafkasl.phonewhisper.meeting.MeetingDraftOwnershipRequest
import com.kafkasl.phonewhisper.meeting.MeetingEngine
import com.kafkasl.phonewhisper.meeting.MeetingImageAnchor
import com.kafkasl.phonewhisper.meeting.MeetingImageBatch
import com.kafkasl.phonewhisper.meeting.MeetingImageDelivery
import com.kafkasl.phonewhisper.meeting.MeetingImageMutation
import com.kafkasl.phonewhisper.meeting.MeetingImageRecovery
import com.kafkasl.phonewhisper.meeting.MeetingImageSource
import com.kafkasl.phonewhisper.meeting.MeetingImageSourceKind
import com.kafkasl.phonewhisper.meeting.MeetingMainDispatcher
import com.kafkasl.phonewhisper.meeting.MeetingMicrophoneFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailability
import com.kafkasl.phonewhisper.meeting.MeetingModelAvailabilityPort
import com.kafkasl.phonewhisper.meeting.MeetingModelCatalog
import com.kafkasl.phonewhisper.meeting.MeetingModelStore
import com.kafkasl.phonewhisper.meeting.MeetingModelStoreState
import com.kafkasl.phonewhisper.meeting.MeetingNativeAdmission
import com.kafkasl.phonewhisper.meeting.MeetingNativeReservationPort
import com.kafkasl.phonewhisper.meeting.MeetingNotePublisherPort
import com.kafkasl.phonewhisper.meeting.MeetingPanelActions
import com.kafkasl.phonewhisper.meeting.MeetingPanelAnchor
import com.kafkasl.phonewhisper.meeting.MeetingPanelChoicesRequest
import com.kafkasl.phonewhisper.meeting.MeetingPanelController
import com.kafkasl.phonewhisper.meeting.MeetingPanelDialogHost
import com.kafkasl.phonewhisper.meeting.MeetingPanelDocumentAction
import com.kafkasl.phonewhisper.meeting.MeetingPanelSessionCommand
import com.kafkasl.phonewhisper.meeting.MeetingPanelStatus
import com.kafkasl.phonewhisper.meeting.MeetingPanelTextInputRequest
import com.kafkasl.phonewhisper.meeting.MeetingRecordingController
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPhase
import com.kafkasl.phonewhisper.meeting.MeetingRecordingPorts
import com.kafkasl.phonewhisper.meeting.MeetingRecordingState
import com.kafkasl.phonewhisper.meeting.MeetingSessionFactoryPort
import com.kafkasl.phonewhisper.meeting.MeetingDocument
import com.kafkasl.phonewhisper.meeting.MeetingHypothesis
import com.kafkasl.phonewhisper.meeting.MeetingProjection

/** Ensures AudioRecord is never released or read buffers snapshotted while its reader is alive. */
internal class RecordingStopCoordinator(
    private val recordThread: Thread?,
    private val stopRecorder: () -> Unit,
    private val releaseRecorder: () -> Unit,
    private val snapshot: () -> Unit,
) {
    @Volatile
    var recorderReleaseConfirmed: Boolean = false
        private set

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
        recorderReleaseConfirmed = try {
            releaseRecorder()
            true
        } catch (_: Throwable) {
            false
        }
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

    /** Native-only fixture seams. UI, draft-note publication and focus plumbing remain real. */
    internal data class MeetingTestOverrides(
        val draftOwnership: MeetingDraftOwnership,
        val draftFile: File,
        val modelStore: MeetingModelStore,
        val modelAvailability: MeetingModelAvailabilityPort? = null,
        val reservation: MeetingNativeReservationPort,
        val sessionFactory: MeetingSessionFactoryPort,
        val microphoneFactory: MeetingMicrophoneFactoryPort,
    )

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
        private const val MEETING_NOTE_PUBLICATION_MAX_FLUSH_ATTEMPTS = 3
        private val meetingTestFactoryLock = Any()
        private var pendingMeetingTestFactory: ((OverlayService) -> MeetingTestOverrides?)? = null
        @Volatile var micArmed = false
            private set

        /** One-shot instrumentation hook; never exposed through an Intent or persistent setting. */
        internal fun setMeetingTestOverridesFactoryForTest(
            factory: (OverlayService) -> MeetingTestOverrides?,
        ): Boolean =
            synchronized(meetingTestFactoryLock) {
                if (pendingMeetingTestFactory != null) false
                else {
                    pendingMeetingTestFactory = factory
                    true
                }
            }

        internal fun clearMeetingTestOverridesFactoryForTest(): Unit = synchronized(meetingTestFactoryLock) {
            pendingMeetingTestFactory = null
        }

        private fun consumeMeetingTestOverridesFactory(): ((OverlayService) -> MeetingTestOverrides?)? =
            synchronized(meetingTestFactoryLock) { pendingMeetingTestFactory.also { pendingMeetingTestFactory = null } }
    }

    private enum class State { IDLE, RECORDING, PAUSING, PAUSED, TRANSCRIBING, CANCELLING, MIC_UNARMED }

    private class ActiveDictationRun(
        val session: DictationAsrSession,
        val formatOptions: RecordingOptions,
        val purpose: DictationPurpose,
        val lease: TranscriptionRunLease,
        val cancellation: DictationCancellationCoordinator = DictationCancellationCoordinator(),
    ) {
        val captureGate = RecordingCaptureGate()
        val leaseReleased = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var nativeCloseUncertain = false
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
    private var lastNotifiedContent: String? = null
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
    private var imageIdsBeforeTextEdit: Set<String> = emptySet()
    private val vocabularyTracker = VocabularyCorrectionTracker()
    private var vocabularySuggestion: VocabularyCorrectionTracker.Suggestion? = null
    private var vocabularyBanner: LinearLayout? = null
    private var vocabularySuggestionText: TextView? = null
    private var vocabularyOffer: Runnable? = null
    private var vocabularyDismiss: Runnable? = null
    private var mediaToolbar: LinearLayout? = null
    private val editableTranscript = EditableTranscript()
    private val localFormatterLazy = lazy { LocalFormatEngine(this) }
    private val localFormatter: LocalFormatEngine get() = localFormatterLazy.value
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
    private sealed class NotesView {
        data object Root : NotesView()
        data object Unfiled : NotesView()
        data class Folder(val id: String) : NotesView()
    }
    private var notesView: NotesView = NotesView.Root
    private var pendingFolderChoiceNoteId: String? = null
    private var purpose = DictationPurpose.MESSAGE
    private val noteInsertionGate = NoteInsertionGate()
    private var noteInsertionDialog: AlertDialog? = null
    private var noteInsertButton: TextView? = null
    private var noteDoneButton: TextView? = null
    private val imageStore by lazy { NoteImageStore(this) }
    private val imageBlockRenderer by lazy { TranscriptImageBlockRenderer(this) }
    private var captureWindowsHidden = false
    private var preserveImageClipboard = false
    private var imageDeliveryBusy = false
    private var screenshotBatchBar: ScreenshotBatchBar? = null
    private var screenshotBatchBarAdded = false
    private var screenshotBatchId: String? = null
    private var acceptedBatchRetry: PendingNoteCapture? = null
    private var meetingImageResumeContext: MeetingImageResumeContext? = null
    @Volatile private var meetingImageRecoveryRequest: MeetingDraftOwnershipRequest? = null
    private var screenshotBatchParams: WindowManager.LayoutParams? = null
    private var screenshotCaptureBusy = false
    private var exportAfterImageDelivery: String? = null
    private var archiveAfterImageDelivery = false
    private var messageAfterImageDelivery = false
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
    private var formatMenuRows = emptyList<TextView>()
    private var formatMenuFormats = emptyList<PostProcessingFormat>()
    private var transcriptionModeRows = emptyList<TextView>()
    private var formatMenuSelected = -1
    private var formatMenuParams: WindowManager.LayoutParams? = null
    private var formatMenuSwipeMode = false
    private var params: WindowManager.LayoutParams? = null
    private var liveParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var resumeAudioRecordFactory: ((Int) -> AudioRecord)? = null
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
    private val panelPrefs by lazy { OverlayPanelPrefs(this) }
    private var reducedPanelRect: Rect? = null
    private var reducedPanelRectScreen: Rect? = null
    private var customPanelPlacement = false
    private var panelBodyRect: Rect? = null
    private var panelMoveHandle: View? = null
    private val panelResizeHandles = mutableMapOf<PanelResizeHandle, View>()
    private var panelGestureStartRect: Rect? = null
    private var panelGestureStartScreen: Rect? = null
    private var panelGestureStartX = 0f
    private var panelGestureStartY = 0f
    private var panelGestureLastX = 0f
    private var panelGestureLastY = 0f
    private var panelGestureHandle: PanelResizeHandle? = null
    private var bubblePointerLength = 0
    private var bubblePointerTargetX = Float.NaN
    private var bubblePointerTargetY = Float.NaN
    private var livePanelAdded = false
    private var livePreviewVisible = false
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private val localLoading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val localEngineLifecycle = LocalEngineLifecycle()
    private val transcriptionModes by lazy { TranscriptionModeCoordinator.process(applicationContext) }
    private val residentAsrEngine by lazy { ResidentEngine<DictationAsrEngine>(transcriptionModes) }
    private var transcriptionModeSubscription: AutoCloseable? = null
    private var meetingTestOverrides: MeetingTestOverrides? = null
    private var meetingDraftClaimRequest: MeetingDraftOwnershipRequest? = null
    private var meetingDraftClaim: MeetingDraftOwnershipClaim? = null
    private var meetingRecordingController: MeetingRecordingController? = null
    private var meetingPanelController: MeetingPanelController? = null
    private var meetingModelStore: MeetingModelStore? = null
    private var meetingModelListener: ((MeetingModelStoreState) -> Unit)? = null
    private var meetingDraftFile: File? = null
    private var meetingOpenGeneration = 0L
    private var meetingReplacementGeneration = 0L
    private var meetingReplacementOperation: MeetingReplacementOperation? = null
    private var meetingModeTransferInProgress = false
    private var meetingNotePublicationGeneration = 0L
    private var meetingNotePublicationOperation: MeetingNotePublicationOperation? = null
    private var meetingNotePublicationError: MeetingNotePublicationError? = null
    private var meetingDocumentActionGeneration = 0L
    private var meetingDocumentActionOperation: MeetingDocumentActionOperation? = null
    private var meetingDocumentActionError: MeetingDocumentActionError? = null
    private var meetingImageMutationGeneration = 0L
    private var meetingImageMutationOperation: MeetingImageMutationOperation? = null
    private var meetingImageMutationError: MeetingImageMutationError? = null
    private var meetingControllerState: MeetingRecordingState? = null
    private var meetingOpaqueMessage: TextView? = null
    private var meetingSurfaceOpen = false
    private var dictationPanelVisibilityBeforeMeeting = emptyList<Pair<View, Int>>()
    private var dictationPanelTitleBeforeMeeting: CharSequence? = null
    private var dictationStateVisibilityBeforeMeeting: Int = View.VISIBLE
    private var meetingDialogOpen = false
    private var meetingFinishPromptPending = false
    private var meetingDocumentRestored = false
    private val meetingDialogs = linkedSetOf<AlertDialog>()
    private var startMeetingAfterClaimLanguage: String? = null

    private data class MeetingReplacementOperation(
        val generation: Long,
        val modeGeneration: Long,
        val sessionId: String,
        val runId: String,
        val controller: MeetingRecordingController,
        val panel: MeetingPanelController,
        val claim: MeetingDraftOwnershipClaim,
        val path: File,
        val nextNote: TranscriptNote? = null,
        var destinationChanged: Boolean = false,
        var dictationSelectionGeneration: Long? = null,
        var savedMeetingNote: TranscriptNote? = null,
    )
    private data class MeetingNotePublicationOperation(
        val generation: Long,
        val sessionId: String,
        val runId: String,
        val controller: MeetingRecordingController,
    )
    private data class MeetingNotePublicationError(
        val sessionId: String,
        val message: String,
    )
    private data class MeetingDocumentActionOperation(
        val generation: Long,
        val action: MeetingPanelDocumentAction,
        val sessionId: String,
        val runId: String,
        val controller: MeetingRecordingController,
        val panel: MeetingPanelController,
        val initialPhase: MeetingRecordingPhase,
        val mode: TranscriptionMode,
        val modeGeneration: Long,
        val poisonedDocumentOnly: Boolean,
        val anchor: MeetingImageAnchor?,
    )
    private data class MeetingImageResumeContext(
        val pendingId: String,
        val sessionId: String,
        val runId: String,
        val controller: MeetingRecordingController,
    )
    private data class MeetingDocumentActionError(
        val sessionId: String,
        val runId: String,
        val action: MeetingPanelDocumentAction,
        val anchor: MeetingImageAnchor?,
        val pendingId: String?,
        val message: String,
    )
    private data class MeetingImageMenuContext(
        val sessionId: String,
        val runId: String,
        val controller: MeetingRecordingController,
        val panel: MeetingPanelController,
        val mode: TranscriptionMode,
        val modeGeneration: Long,
        val poisonedDocumentOnly: Boolean,
        val anchor: MeetingImageAnchor?,
    )
    private data class MeetingImageMutationOperation(
        val generation: Long,
        val context: MeetingImageMenuContext,
        val imageId: String,
        val previousRecyclerVisibility: Int,
    )
    private data class MeetingImageMutationError(
        val sessionId: String,
        val runId: String,
        val imageId: String,
        val mutation: ((MeetingDocument, List<NoteImage>) -> MeetingImageMutation)?,
        val message: String,
        val removedImageIds: Set<String> = emptySet(),
    )
    private val main = Handler(Looper.getMainLooper())
    private val tapCoordinator = DictationTapGestureCoordinator(DOUBLE_TAP_MS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        consumeMeetingTestOverridesFactory()?.let { factory ->
            val overrides = factory(this)
            if (overrides != null) check(installMeetingTestOverrides(overrides)) {
                "Meeting test overrides must be installed before the service owns a meeting resource"
            }
        }
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        micArmed = false
        createChannel()
        if (!startForegroundSpecialUse()) return
        transcriptionModeSubscription = transcriptionModes.subscribe { snapshot ->
            main.post {
                if (!localEngineLifecycle.isDestroyed()) {
                    val current = transcriptionModes.snapshot()
                    if (current.generation != snapshot.generation || current.mode != snapshot.mode) return@post
                    if (current.mode == TranscriptionMode.DICTATION && meetingSurfaceOpen) {
                        beginDictationModeTransfer(current)
                    } else if (current.mode != TranscriptionMode.MEETING) {
                        cancelMeetingNotePublication()
                    }
                    applyTranscriptionMode(current.mode)
                }
            }
        }
        showButton()
        purpose = draftStore.purpose
        recoveredDraft = draftStore.load()
        activeNoteId = draftStore.noteId?.takeIf { notes.get(it) != null }
        recoveredDraft?.let { text ->
            val projection = storedTranscriptProjection(text)
            recoveredDraft = projection.rawText()
            editableTranscript.edit(projection.rawText())
            renderTranscriptProjection(projection, projection.rawText().length, projection.rawText().length)
            panelHidden = !prefs.showTranscript
            setState(State.PAUSED)
            setLivePreviewVisible(true)
        }
        recoverPendingImage()
        refreshNoteImages()
        if (transcriptionModes.snapshot().mode == TranscriptionMode.DICTATION) {
            ensureLocalLoaded()
            warmLocalFormatter()
        }
    }

    /** Installs test-only native boundaries before this service owns any meeting resource. */
    internal fun installMeetingTestOverrides(overrides: MeetingTestOverrides): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (meetingTestOverrides != null || meetingSurfaceOpen || meetingDraftClaimRequest != null ||
            meetingDraftClaim != null || meetingRecordingController != null || activeRun != null
        ) return false
        meetingTestOverrides = overrides
        meetingModelStore = overrides.modelStore
        meetingDraftFile = overrides.draftFile
        return true
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
            if (transcriptionModes.snapshot().mode == TranscriptionMode.DICTATION) {
                // ARM_MIC is sent by a visible Activity. Meeting still arms only the FGS; it
                // does not warm Dictation ASR or its local formatter.
                ensureLocalLoaded()
                warmLocalFormatter()
            }
        }
        if (intent?.action == ACTION_PREPARE_LOCAL_FORMAT &&
            transcriptionModes.snapshot().mode == TranscriptionMode.DICTATION
        ) warmLocalFormatter()
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
        if (!meetingSurfaceOpen && !meetingModeTransferInProgress && meetingReplacementOperation == null &&
            transcriptionModes.snapshot().mode == TranscriptionMode.DICTATION &&
            BuildConfig.LOCAL_FORMAT_PROTOTYPE && prefs.formattingEngine == "local" &&
            GemmaModelStore(this).installedModel() != null) localFormatter.warm()
    }

    /** Charge le modèle local hors thread principal; l'ancien moteur est fermé avant toute nouvelle ouverture. */
    private fun ensureLocalLoaded() {
        val requestedMode = transcriptionModes.snapshot()
        if (requestedMode.mode != TranscriptionMode.DICTATION || meetingSurfaceOpen ||
            meetingModeTransferInProgress || meetingReplacementOperation != null
        ) return
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
                val beforeOpen = transcriptionModes.snapshot()
                if (localEngineLifecycle.isDestroyed() ||
                    beforeOpen.mode != TranscriptionMode.DICTATION ||
                    beforeOpen.generation != requestedMode.generation
                ) return@thread
                asrSession?.cancelAndAwait()
                asrSession = null
                asrEngine = null
                loadedModelName = null
                val loaded = residentAsrEngine.replace(selectedModel) {
                    DictationAsrEngineFactory.create(this, selectedModel)
                }
                if (loaded == null) return@thread
                val afterOpen = transcriptionModes.snapshot()
                if (localEngineLifecycle.isDestroyed() ||
                    afterOpen.mode != TranscriptionMode.DICTATION ||
                    afterOpen.generation != requestedMode.generation
                ) {
                    closeResidentAfterRejectedLoad()
                    return@thread
                }
                val published = localEngineLifecycle.publishIfAlive {
                    asrEngine = loaded
                    loadedModelName = selectedModel
                }
                if (!published) closeResidentAfterRejectedLoad()
            } catch (failure: Throwable) {
                // A mode switch or an uncertain native close may reject a load. Never let that
                // worker exception escape into Android's process uncaught-exception handler.
                asrEngine = null
                loadedModelName = null
                val current = transcriptionModes.snapshot()
                if (!localEngineLifecycle.isDestroyed() &&
                    current.mode == TranscriptionMode.DICTATION &&
                    current.generation == requestedMode.generation
                ) {
                    main.post {
                        if (!localEngineLifecycle.isDestroyed() &&
                            transcriptionModes.snapshot().let {
                                it.mode == TranscriptionMode.DICTATION && it.generation == requestedMode.generation
                            }
                        ) toast("Modèle local indisponible.")
                    }
                }
                Log.w(TAG, "Chargement local indisponible: ${failure.javaClass.simpleName}")
            }
            finally { localLoading.set(false) }
        }
    }

    private fun closeResidentAfterRejectedLoad() {
        try {
            residentAsrEngine.close()
        } catch (failure: Throwable) {
            // ResidentEngine reports uncertain closure to the coordinator; keep the UI message
            // generic and do not retain a reference to a possibly closed engine.
            Log.w(TAG, "Fermeture du moteur local incertaine: ${failure.javaClass.simpleName}")
        } finally {
            asrEngine = null
            loadedModelName = null
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "DictAI", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DictAI actif")
            .setContentText(notificationContentText())
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()

    private fun notificationContentText(): String {
        val meetingState = if (transcriptionModes.snapshot().mode == TranscriptionMode.MEETING) {
            meetingControllerState
        } else null
        if (meetingState != null && meetingState.phase in setOf(
                MeetingRecordingPhase.FINISHED,
                MeetingRecordingPhase.DOCUMENT,
            ) && (meetingState.saveError != null || meetingNotePublicationErrorFor(meetingState.document) != null)
        ) {
            return "Transcription à enregistrer — ouvrez la réunion"
        }
        if (state == State.MIC_UNARMED) return "Ouvre l'app pour activer le micro"
        if (meetingState != null || transcriptionModes.snapshot().mode == TranscriptionMode.MEETING) {
            return when (meetingState?.phase) {
                MeetingRecordingPhase.PREPARING -> "Préparation de la réunion…"
                MeetingRecordingPhase.LISTENING -> "Réunion en cours"
                MeetingRecordingPhase.PAUSING -> "Mise en pause de la réunion…"
                MeetingRecordingPhase.PAUSED -> "Réunion en pause — appuyez pour reprendre"
                MeetingRecordingPhase.FINALIZING -> "Enregistrement de la transcription…"
                MeetingRecordingPhase.CLOSING -> "Fermeture de la réunion…"
                MeetingRecordingPhase.FINISHED -> "Réunion terminée"
                MeetingRecordingPhase.DOCUMENT,
                MeetingRecordingPhase.MODEL_UNAVAILABLE,
                MeetingRecordingPhase.ERROR,
                null -> "Mode Réunion — ouvrez le panneau"
            }
        }
        return when (state) {
            State.MIC_UNARMED -> "Ouvre l'app pour activer le micro"
            State.RECORDING -> "Enregistrement..."
            State.PAUSING -> "Mise en pause…"
            State.PAUSED -> "Dictée en pause — appuyez pour reprendre"
            State.TRANSCRIBING -> "Transcription..."
            State.CANCELLING -> "Annulation de la dictée…"
            State.IDLE -> "Appuie sur le bouton pour dicter"
        }
    }

    private fun startForegroundSpecialUse(): Boolean {
        return try {
            val notification = buildNotification()
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            lastNotifiedContent = notificationContentText()
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
            val notification = buildNotification()
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            lastNotifiedContent = notificationContentText()
            micArmed = true
            setState(if (recoveredDraft != null) State.PAUSED else State.IDLE)
            Log.i(TAG, "Mic arme")
        } catch (e: Exception) {
            Log.e(TAG, "promoteMic echec: ${e.javaClass.simpleName}")
            micArmed = false
            setState(State.MIC_UNARMED)
        }
    }

    private fun applyTranscriptionMode(mode: TranscriptionMode) {
        wave?.setMeetingMode(mode == TranscriptionMode.MEETING)
        pill?.contentDescription = when (mode) {
            TranscriptionMode.DICTATION -> "Pastille Dictée"
            TranscriptionMode.MEETING -> "Pastille Réunion"
        }
        transcriptionModeRows.forEach { row ->
            val rowMode = row.tag as? TranscriptionMode ?: return@forEach
            val selected = rowMode == mode
            row.text = (if (selected) "✓ " else "") + rowMode.label()
            row.contentDescription = if (selected) "${rowMode.label()}, sélectionné" else rowMode.label()
            row.stateDescription = if (selected) "Sélectionné" else "Non sélectionné"
            row.isSelected = selected
            row.setTextColor(if (selected) overlayPalette.green else overlayPalette.ink)
            row.background = if (selected) overlayActionBackground(overlayPalette)
            else overlayCardBackground(overlayPalette.raised, radiusDp = 16f)
        }
        updateNotif()
    }

    private fun beginDictationModeTransfer(snapshot: TranscriptionModeSnapshot) {
        val latest = transcriptionModes.snapshot()
        if (latest.mode != TranscriptionMode.DICTATION || latest.generation != snapshot.generation ||
            latest.activeRunMode != null || latest.poisoned || !meetingSurfaceOpen
        ) return

        meetingModeTransferInProgress = true
        cancelMeetingNotePublication()
        invalidateMeetingExportReturn()

        val replacement = meetingReplacementOperation
        if (replacement != null) {
            recordMeetingDestinationChange(replacement, latest)
            return
        }

        val controller = meetingRecordingController
        if (controller != null) {
            startNewMeetingAfterSaving(controller, dictationSelectionGeneration = latest.generation)
            if (meetingReplacementOperation == null) {
                meetingModeTransferInProgress = false
                rollbackDictationModeSelection(latest.generation)
                toast("Impossible de transférer le brouillon de réunion.")
            }
            return
        }

        val noteToRestore = activeNoteId?.let(notes::get)
        meetingOpenGeneration += 1
        meetingDraftClaimRequest?.cancel()
        meetingDraftClaimRequest = null
        val claim = meetingDraftClaim
        if (claim == null) {
            finishMeetingModeTransfer(noteToRestore)
            return
        }
        claim.relinquishLatest().whenComplete { _, failure ->
            main.post {
                if (!meetingModeTransferInProgress) return@post
                if (failure != null) {
                    meetingModeTransferInProgress = false
                    rollbackDictationModeSelection(latest.generation)
                    toast("Impossible de libérer le brouillon de réunion.")
                    return@post
                }
                meetingDraftClaim = null
                finishMeetingModeTransfer(noteToRestore)
            }
        }
    }

    private fun recordMeetingDestinationChange(
        operation: MeetingReplacementOperation,
        snapshot: TranscriptionModeSnapshot,
    ) {
        operation.destinationChanged = true
        if (snapshot.mode == TranscriptionMode.DICTATION) {
            operation.dictationSelectionGeneration = snapshot.generation
            meetingModeTransferInProgress = true
            cancelMeetingNotePublication()
            invalidateMeetingExportReturn()
        }
    }

    /** Invalidate export continuations without restoring their stale Meeting identity snapshot. */
    private fun invalidateMeetingExportReturn() {
        if (exportPanel != null || exportResume != null || exportBridgeHidden) exportController.invalidate()
        exportPanel?.let { panel ->
            panel.dispose()
            panel.parent?.let { (it as? ViewGroup)?.removeView(panel) }
        }
        exportAccessibilityPrevious.forEach { (view, previous) -> view.importantForAccessibility = previous }
        exportAccessibilityPrevious.clear()
        exportPanel = null
        exportNoteId = null
        exportResume = null
        if (exportBridgeHidden) restoreAfterExportBridge() else exportBridgeToken = null
    }

    private fun rollbackDictationModeSelection(generation: Long) {
        val current = transcriptionModes.snapshot()
        if (current.mode == TranscriptionMode.DICTATION && current.generation == generation &&
            current.activeRunMode == null && !current.poisoned
        ) {
            transcriptionModes.changeMode(TranscriptionMode.MEETING)
        }
        val latest = transcriptionModes.snapshot()
        if (latest.generation == current.generation || latest.mode == TranscriptionMode.MEETING) {
            applyTranscriptionMode(latest.mode)
        }
    }

    private fun finishMeetingModeTransfer(noteToRestore: TranscriptNote?) {
        meetingOpenGeneration += 1
        meetingReplacementGeneration += 1
        meetingReplacementOperation = null
        meetingRecordingController = null
        meetingDraftClaim = null
        meetingDraftFile = null
        meetingControllerState = null
        meetingDocumentRestored = false
        restoreDictationPanelAfterMeeting()
        meetingModeTransferInProgress = false
        restoreDictationDraftContext()

        // Restoration may synchronously trigger another coordinator choice (for example, an
        // external screen reselecting Meeting while the Dictation draft identity is restored).
        // The durable Meeting note is already safe, so settle according to the newest choice.
        val current = transcriptionModes.snapshot()
        applyTranscriptionMode(current.mode)
        if (current.mode == TranscriptionMode.MEETING && current.activeRunMode == null) {
            noteToRestore?.let(::openMeetingNote)
        } else if (current.mode == TranscriptionMode.DICTATION && current.activeRunMode == null && micArmed) {
            ensureLocalLoaded()
            warmLocalFormatter()
        }
    }

    private fun restoreDictationDraftContext() {
        val storedNoteId = draftStore.noteId
        val note = storedNoteId?.let(notes::get)?.takeIf { it.meeting == null && it.meetingRaw == null }
        if (storedNoteId != null && note == null) draftStore.noteId = null
        activeNoteId = note?.id
        purpose = if (note != null) DictationPurpose.NOTE else DictationPurpose.MESSAGE
        draftStore.purpose = purpose

        val text = draftStore.load().orEmpty()
        val projection = storedTranscriptProjection(text)
        recoveredDraft = projection.rawText().takeIf { it.isNotEmpty() }
        editableTranscript.clear()
        editableTranscript.edit(projection.rawText())
        renderTranscriptProjection(projection, projection.rawText().length, projection.rawText().length)
        liveText?.isEnabled = true
        liveText?.hint = "Écrivez ici, ou appuyez sur la pastille pour dicter"
        panelHidden = false
        setState(if (projection.rawText().isNotEmpty()) State.PAUSED else if (micArmed) State.IDLE else State.MIC_UNARMED)
        setLivePreviewVisible(true)
        refreshNoteImages()
    }

    private fun updateNotif() {
        val content = notificationContentText()
        if (lastNotifiedContent == content) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        lastNotifiedContent = content
    }

    private fun startRec() {
        val modeSnapshot = transcriptionModes.snapshot()
        if (modeSnapshot.mode != TranscriptionMode.DICTATION || meetingSurfaceOpen ||
            meetingModeTransferInProgress || meetingReplacementOperation != null
        ) {
            toast("Terminez le transfert du document de réunion avant de dicter.")
            return
        }
        dismissFloatingMenu()
        val requestedAt = SystemClock.elapsedRealtime()
        val selectedFormat = PostProcessingFormats(this).selected()
        val cloudRequested = prefs.formattingEngine == "cloud" && prefs.cloudCleanupEnabled && selectedFormat.usesLanguageModel
        val targetSensitive = cloudRequested && runCatching {
            InjectionGateway.current()?.isActiveTargetSensitive() ?: true
        }.getOrDefault(true)
        val cloudPolicy = CloudSensitiveTargetPolicy.snapshot(cloudRequested, targetSensitive)
        val selectedModel = TranscriptionEngine.selectedModelName(this)
        val residentReady = try {
            residentAsrEngine.isLoaded(selectedModel)
        } catch (_: Throwable) {
            false
        }
        if (!residentReady) {
            // Service fields may outlive a resident close. They are not proof that the native
            // engine is still usable; clear them and take the ordinary asynchronous reload path.
            asrEngine = null
            loadedModelName = null
            ensureLocalLoaded()
            toast("Chargement du modèle local…")
            return
        }
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
        if (activeRun != null) {
            toast("Dictée déjà en cours.")
            return
        }
        val lease = transcriptionModes.reserveRun(TranscriptionMode.DICTATION)
        if (lease == null) {
            toast("Un autre enregistrement est en cours ou indisponible.")
            return
        }
        val engine = try {
            // A loaded resident is returned by replace. A missing resident must not be rebuilt
            // from the legacy field, which may point at an already closed native object.
            residentAsrEngine.replace(selectedModel, lease) { null }
        } catch (t: Throwable) {
            val poisoned = transcriptionModes.snapshot().poisoned
            if (!poisoned) {
                releaseUnstartedDictationLease(lease)
                asrEngine = null
                loadedModelName = null
                ensureLocalLoaded()
            }
            toast("Transcription locale indisponible.")
            Log.w(TAG, "event=audio_start outcome=resident_unavailable type=${t.javaClass.simpleName}")
            return
        }
        if (engine == null) {
            releaseUnstartedDictationLease(lease)
            asrEngine = null
            loadedModelName = null
            if (!transcriptionModes.snapshot().poisoned) {
                ensureLocalLoaded()
                toast("Chargement du modèle local…")
            } else {
                toast("Modèle local indisponible.")
            }
            return
        }
        asrEngine = engine
        loadedModelName = selectedModel
        val options = try {
            RecordingOptions(
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
        } catch (t: Throwable) {
            releaseUnstartedDictationLease(lease)
            toast("Dictée indisponible.")
            Log.w(TAG, "event=audio_start outcome=options_failure type=${t.javaClass.simpleName}")
            return
        }
        val bufSize = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (t: Throwable) {
            releaseUnstartedDictationLease(lease)
            toast("Mic indisponible.")
            Log.w(TAG, "event=audio_start outcome=buffer_query_failure type=${t.javaClass.simpleName}")
            return
        }
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
                if (result.recorderReleaseConfirmed && result.reason != RecordingStartupTransaction.Failure.SESSION_FAILED) {
                    releaseUnstartedDictationLease(lease)
                }
                else transcriptionModes.reportUncertainClose()
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
            thread(name = "dictai-unexpected-recorder-cleanup") {
                started.session.cancel()
                var recorderSafe = true
                try { started.recorder.stop() } catch (_: Throwable) {}
                try { started.recorder.release() } catch (_: Throwable) { recorderSafe = false }
                var sessionSafe = true
                try {
                    while (!started.session.cancelAndAwait()) Unit
                } catch (_: Throwable) {
                    sessionSafe = false
                }
                if (recorderSafe && sessionSafe) releaseUnstartedDictationLease(lease)
                else transcriptionModes.reportUncertainClose()
            }
            toast("Mic indisponible.")
            Log.w(TAG, "event=audio_start outcome=unexpected_recorder")
            return
        }
        invalidateNoteInsertion()
        val run = ActiveDictationRun(started.session, options, purpose, lease)
        activeRun = run
        val ar = recorder.audioRecord
        val recordingPcm = java.io.ByteArrayOutputStream()
        var readerThread: Thread? = null
        try {
            run.cancellation.onCancel { started.session.cancel() }
            if (options.localFormattingEnabled && options.format.localLayoutKind != null) {
                localFormatter.warm()
                run.localFormatting = LocalFormattingSession(localFormatter.backend())
                run.cancellation.onCancel { run.localFormatting?.close() }
            }
            run.startRequestedAtMs = requestedAt
            audioRecord = ar
            pcm = recordingPcm
            asrSession = started.session
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
            val restoredProjection = storedTranscriptProjection(restored.orEmpty())
            renderTranscriptProjection(restoredProjection, restoredProjection.rawText().length, restoredProjection.rawText().length)
            liveText?.isEnabled = true
            liveText?.hint = "Écoute en cours…"
            if (restored == null) { panelHidden = !prefs.showTranscript; panelExpanded = false }
            recoveredDraft = null
            persistDraft(rawTranscriptText())
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
            run.completion.markWorkerStarted()
            thread(name = "dictai-start-failure-stop") {
                try {
                    if (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS) == RecordingStopCoordinator.Result.TimedOut) {
                        coordinator.awaitExitThenRelease()
                    }
                    val cleanupSafe = coordinator.recorderReleaseConfirmed && awaitSessionExit(run)
                    if (!cleanupSafe) markDictationCloseUncertain(run)
                    else if (!localEngineLifecycle.isDestroyed()) main.post {
                        if (activeRun !== run) return@post
                        activeRun = null
                        setLivePreviewVisible(false)
                        setState(State.IDLE)
                        releaseDictationRunLease(run)
                    }
                } finally {
                    run.completion.markWorkerDone()
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
        persistDraft(rawTranscriptText())
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
            if (!coordinator.recorderReleaseConfirmed) {
                markDictationCloseUncertain(run)
            } else {
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
        if (!transcriptionModes.isCurrentRun(run.lease)) {
            toast("Reprise indisponible : le moteur de transcription est indisponible.")
            return
        }
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
            check(transcriptionModes.isCurrentRun(run.lease)) {
                "Dictation lease is no longer usable"
            }
            val resumed = resumeAudioRecordFactory?.invoke(bufferSize) ?: AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize,
            )
            recorder = resumed
            check(resumed.state == AudioRecord.STATE_INITIALIZED)
            check(transcriptionModes.isCurrentRun(run.lease)) {
                "Dictation lease is no longer usable"
            }
            resumed.startRecording()
            check(transcriptionModes.isCurrentRun(run.lease)) {
                "Dictation lease is no longer usable"
            }
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
                val cleanupWorker = Thread({
                    if (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS) == RecordingStopCoordinator.Result.TimedOut) {
                        coordinator.awaitExitThenRelease()
                    }
                    if (!coordinator.recorderReleaseConfirmed) {
                        markDictationCloseUncertain(run)
                    } else {
                        main.post {
                            if (localEngineLifecycle.isDestroyed() || activeRun !== run || state != State.PAUSING) return@post
                            setState(State.PAUSED)
                        }
                    }
                }, "dictai-resume-failure-stop")
                // onDestroy joins this worker before releasing the resident ASR or the run lease.
                run.pauseWorker = cleanupWorker
                cleanupWorker.start()
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
        if (imageDeliveryBusy || hasImageCaptureInFlight()) {
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
                        if (!coordinator.recorderReleaseConfirmed) {
                            markDictationCloseUncertain(run)
                            run.session.cancel()
                            awaitSessionExit(run)
                        } else {
                            val stoppedCapture = capture ?: RecordingCapture(
                                run,
                                ByteArray(0), null,
                                run.formatOptions,
                            )
                            processStoppedRecording(stoppedCapture)
                        }
                    }
                    RecordingStopCoordinator.Result.TimedOut -> {
                        // The reader can no longer feed a result, but release/snapshot still wait for it safely.
                        run.session.cancel()
                        Log.w(TAG, "event=audio_stop outcome=timeout")
                        coordinator.awaitExitThenRelease()
                        val cleanupSafe = coordinator.recorderReleaseConfirmed && awaitSessionExit(run)
                        if (!cleanupSafe) {
                            markDictationCloseUncertain(run)
                        } else {
                            main.post {
                                if (isCurrentRun(run) && !run.cancellation.isCancelled) {
                                    toast("Transcription locale indisponible.")
                                }
                                completeRunOnMain(run)
                            }
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
        if (!awaitSessionExit(run)) return
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
                val text = localText ?: rawTranscriptText()
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
                        val projection = projectionForText(preview)
                        renderTranscriptProjection(projection)
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
                        val saved = saveNoteWithCaptures(outText ?: rawTranscriptText())
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
                if (coordinator.stopJoinRelease(RECORD_STOP_TIMEOUT_MS) == RecordingStopCoordinator.Result.TimedOut) {
                    Log.w(TAG, "event=audio_cancel outcome=timeout")
                    coordinator.awaitExitThenRelease()
                }
                if (!coordinator.recorderReleaseConfirmed) markDictationCloseUncertain(run)
                awaitSessionExit(run)
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
                toast("Ouvre DictAI pour activer le micro")
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

    private fun awaitSessionExit(run: ActiveDictationRun): Boolean {
        return try {
            while (!run.session.cancelAndAwait()) {
                // A cancellation timeout keeps the run busy; retry until the native worker exits.
            }
            true
        } catch (_: Throwable) {
            markDictationCloseUncertain(run)
            false
        }
    }

    private fun markDictationCloseUncertain(run: ActiveDictationRun?) {
        run?.nativeCloseUncertain = true
        transcriptionModes.reportUncertainClose()
        Log.w(TAG, "event=dictation_close outcome=uncertain")
    }

    private fun releaseUnstartedDictationLease(lease: TranscriptionRunLease) {
        try {
            lease.close()
        } catch (_: Throwable) {
            transcriptionModes.reportUncertainClose()
        }
    }

    private fun releaseDictationRunLease(run: ActiveDictationRun) {
        if (run.nativeCloseUncertain || !run.leaseReleased.compareAndSet(false, true)) return
        try {
            run.lease.close()
        } catch (_: Throwable) {
            markDictationCloseUncertain(run)
        }
    }

    private fun awaitCancellationCompletion(run: ActiveDictationRun) {
        if (!run.cancellationWaitStarted.compareAndSet(false, true)) return
        thread(name = "dictai-await-cancel") {
            joinUninterruptibly(run.pauseWorker)
            run.completion.awaitWorkerIfStarted()
            if (awaitSessionExit(run) && !localEngineLifecycle.isDestroyed()) {
                main.post { completeRunOnMain(run) }
            }
        }
    }

    /** Only the current run may release the busy state; an old worker cannot reset a newer run. */
    private fun completeRunOnMain(run: ActiveDictationRun) {
        if (localEngineLifecycle.isDestroyed() || activeRun !== run || run.nativeCloseUncertain) return
        if (imageDeliveryBusy || hasImageCaptureInFlight()) {
            main.postDelayed({ completeRunOnMain(run) }, 60)
            return
        }
        run.formatOffer?.let(main::removeCallbacks)
        run.formatOffer = null
        run.formatOfferRequest = null
        try {
            run.localFormatting?.close()
        } catch (_: Throwable) {
            markDictationCloseUncertain(run)
            return
        }
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
        try {
            run.afterCompletion?.also { run.afterCompletion = null }?.invoke()
        } finally {
            releaseDictationRunLease(run)
        }
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
                else -> "Appuyer pour dicter. Glisser vers le haut pour choisir un mode ou un format. Maintenir jusqu’à la vibration pour déplacer."
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
            (panelMoveHandle as? TextView)?.setTextColor(colors.green)
            panelResizeHandles.values.forEach { (it as? TextView)?.setTextColor(colors.green) }

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
            meetingPanelController?.refreshTheme()
            refreshOverlayMenu(floatingMenu, colors, root = true)
            updateFormatMenuHighlight(formatMenuSelected, haptic = false)
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
            val editor = liveText ?: return@publishIfAllowed
            if (display.isNotEmpty()) editor.hint = "Touchez pour corriger pendant la dictée"
            val oldProjection = imageBlockRenderer.read(editor)
            val old = oldProjection.rawText()
            if (old != display) {
                updatingLiveText = true
                try {
                    val prefix = old.commonPrefixWith(display).length
                    val suffix = old.drop(prefix).commonSuffixWith(display.drop(prefix)).length
                    val selectionStart = oldProjection.rawOffsetForEditor(editor.selectionStart.coerceAtLeast(0))
                    val selectionEnd = oldProjection.rawOffsetForEditor(editor.selectionEnd.coerceAtLeast(0))
                    val blocks = oldProjection.blocks.map { block ->
                        val moved = DraftImageContext.move(
                            listOf(DraftImageCapture(block.id, block.rawOffset)),
                            old,
                            display,
                        ).single()
                        block.copy(rawOffset = moved.offset)
                    }
                    val nextProjection = TranscriptImageBlocks.fromBlocks(display, blocks)
                    val mappedSelection = TranscriptSelectionMapping.afterReplacement(
                        selectionStart, selectionEnd, prefix, old.length - suffix,
                        display.length - suffix, display.length,
                    )
                    renderTranscriptProjection(
                        nextProjection,
                        mappedSelection?.start,
                        mappedSelection?.end,
                    )
                } finally { updatingLiveText = false }
            }
            persistDraft(display)
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
            val projection = imageBlockRenderer.read(editor)
            val suggestion = vocabularyTracker.suggestion(
                projection.rawText(),
                projection.rawOffsetForEditor(editor.selectionStart.coerceAtLeast(0)),
                projection.rawOffsetForEditor(editor.selectionEnd.coerceAtLeast(0)),
                composing,
                SystemClock.elapsedRealtime(),
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
        val projection = imageBlockRenderer.read(editor)
        val raw = projection.rawText()
        val start = projection.rawOffsetForEditor(editor.selectionStart.coerceAtLeast(0))
        val end = projection.rawOffsetForEditor(editor.selectionEnd.coerceAtLeast(0))
        val current = vocabularyTracker.suggestion(raw, start, end,
            BaseInputConnection.getComposingSpanStart(editor.text) >= 0, SystemClock.elapsedRealtime())
        if (current != suggestion || !vocabularyTracker.consume(suggestion, raw)) {
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
        if (meetingSurfaceOpen) {
            layoutMeetingPanelRows(panelHeight, dp, toolbarHeight)
            return
        }
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

        // The corner handles sit in the reduced panel's edge bands. Keep editor content out of
        // those bands so a drag beginning at a corner cannot steal a text-selection gesture.
        val handleGutter = if (!panelExpanded) (36 * dp).toInt() else (12 * dp).toInt()
        val bottomPadding = if (!panelExpanded) maxOf((36 * dp).toInt(), (10 * dp).toInt()) else (10 * dp).toInt()
        liveScroll?.setPadding(handleGutter, 0, handleGutter, bottomPadding)

        // Keep corner resize targets in dedicated gutters. The toolbar owns the upper corners
        // (move/state/reset/expand/hide) and the media/actions rows own the lower corners; a
        // handle laid directly on either row would steal those buttons' touch targets.
        val topGutter = toolbarHeight
        val bottomGutter = maxOf(bottomBarHeight, toolbarHeight)
        val handleHeight = min(
            (36 * dp).toInt(),
            ((panelHeight - topGutter - bottomGutter).coerceAtLeast(0) / 2),
        )
        val handlesVisible = handleHeight > 0 && livePreviewVisible && !panelExpanded && exportPanel == null
        panelResizeHandles.forEach { (handle, view) ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.apply {
                val topHandle = handle == PanelResizeHandle.TOP_LEFT || handle == PanelResizeHandle.TOP_RIGHT
                topMargin = if (topHandle) topGutter else 0
                bottomMargin = if (topHandle) 0 else bottomGutter
                height = handleHeight
                view.layoutParams = this
                view.visibility = if (handlesVisible) View.VISIBLE else View.GONE
            }
        }
    }

    private fun layoutMeetingPanelRows(panelHeight: Int, dp: Float, hostToolbarHeight: Int) {
        val meetingPanel = meetingPanelController?.view
        val sideGutter = ((if (panelExpanded) 12 else 36) * dp).toInt()
        meetingPanel?.recyclerView?.let { rows ->
            if (rows.paddingLeft != sideGutter || rows.paddingRight != sideGutter) {
                rows.setPadding(sideGutter, rows.paddingTop, sideGutter, rows.paddingBottom)
            }
        }

        // The MeetingPanelView has its own 48 dp command row. Keep resize targets in
        // the recycler's side gutters and leave the editor's vertical measure untouched.
        val topGutter = hostToolbarHeight + (48 * dp).toInt()
        val handleHeight = min(
            (36 * dp).toInt(),
            ((panelHeight - topGutter).coerceAtLeast(0) / 2),
        )
        val handlesVisible = handleHeight > 0 && livePreviewVisible && !panelExpanded && exportPanel == null
        panelResizeHandles.forEach { (handle, view) ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.apply {
                val topHandle = handle == PanelResizeHandle.TOP_LEFT || handle == PanelResizeHandle.TOP_RIGHT
                topMargin = if (topHandle) topGutter else 0
                bottomMargin = 0
                height = handleHeight
                view.layoutParams = this
                view.visibility = if (handlesVisible) View.VISIBLE else View.GONE
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

    private fun panelGeometry(): OverlayPanelGeometry {
        val dp = resources.displayMetrics.density
        return OverlayPanelGeometry(
            minWidth = (240 * dp).toInt().coerceAtLeast(1),
            minHeight = (160 * dp).toInt().coerceAtLeast(1),
            maxWidthFraction = .92f,
            maxHeightFraction = .75f,
            gap = (12 * dp).toInt().coerceAtLeast(1),
        )
    }

    /** Load the user's reduced rectangle in the current safe viewport, including an IME resize. */
    private fun reducedPanelRectFor(
        screen: Rect,
        pill: Rect,
        geometry: OverlayPanelGeometry,
    ): Rect? {
        if (!customPanelPlacement) return null
        val saved = panelPrefs.load()
        val previousScreen = reducedPanelRectScreen
        val current = reducedPanelRect
        val next = when {
            current == null && saved != null -> geometry.denormalize(saved, screen, pill)
            current == null -> return null
            previousScreen == null || previousScreen == screen -> geometry.bound(current, screen, pill)
            else -> geometry.denormalize(
                saved ?: geometry.normalize(current, previousScreen),
                screen,
                pill,
            )
        }
        reducedPanelRect = next
        reducedPanelRectScreen = screen
        return next
    }

    /** Apply a reduced rectangle while keeping its decorative pointer inside the same viewport. */
    private fun applyReducedPanelRect(
        screen: Rect,
        pill: Rect,
        geometry: OverlayPanelGeometry,
        pointerLength: Int,
        dp: Float,
    ): Boolean {
        val panel = livePanel ?: return false
        val panelParams = liveParams ?: return false
        val raw = reducedPanelRectFor(screen, pill, geometry) ?: return false
        var body = raw
        var edge = geometry.pointerEdge(body, pill)
        repeat(2) {
            val safePointerLength = geometry.pointerLengthInside(body, edge, screen, pointerLength)
            body = geometry.reservePointer(body, edge, screen, safePointerLength)
            body = geometry.bound(body, screen, pill)
            edge = geometry.pointerEdge(body, pill)
        }
        val safePointerLength = geometry.pointerLengthInside(body, edge, screen, pointerLength)
        reducedPanelRect = body
        val bounds = OverlayPlacement.bubbleEnvelope(body, edge, safePointerLength).window
        applyPanelBounds(panel, panelParams, bounds, edge, safePointerLength, dp)
        return true
    }

    private fun panelBodyFromCurrentLayout(): Rect? = panelBodyRect ?: run {
        val window = liveParams ?: return null
        val body = livePanelBody ?: return null
        val layout = body.layoutParams as? FrameLayout.LayoutParams ?: return null
        Rect(
            window.x + layout.leftMargin,
            window.y + layout.topMargin,
            layout.width.coerceAtLeast(1),
            layout.height.coerceAtLeast(1),
        )
    }

    private fun beginPanelGesture(rawX: Float, rawY: Float, handle: PanelResizeHandle?): Boolean {
        if (panelExpanded || !livePreviewVisible || exportPanel != null) return false
        val panel = panelBodyFromCurrentLayout() ?: return false
        val screen = screenAboveKeyboard(screenRect())
        val pill = pillRectForPanel(screenRect(), screen)
        val geometry = panelGeometry()
        if (!customPanelPlacement) customPanelPlacement = true
        reducedPanelRect = geometry.bound(reducedPanelRect ?: panel, screen, pill)
        reducedPanelRectScreen = screen
        panelGestureStartRect = reducedPanelRect
        panelGestureStartScreen = screen
        panelGestureStartX = rawX
        panelGestureStartY = rawY
        panelGestureLastX = rawX
        panelGestureLastY = rawY
        panelGestureHandle = handle
        return true
    }

    private fun updatePanelGesture(rawX: Float, rawY: Float): Boolean {
        val start = panelGestureStartRect ?: return false
        val screen = screenAboveKeyboard(screenRect())
        val pill = pillRectForPanel(screenRect(), screen)
        val dx = rawX - panelGestureLastX
        val dy = rawY - panelGestureLastY
        panelGestureLastX = rawX
        panelGestureLastY = rawY
        val geometry = panelGeometry()
        val current = reducedPanelRect ?: start
        reducedPanelRect = if (panelGestureHandle == null) {
            geometry.move(current, dx, dy, screen, pill)
        } else {
            geometry.resize(current, panelGestureHandle!!, dx, dy, screen, pill)
        }
        reducedPanelRectScreen = screen
        applyReducedPanelRect(
            screen,
            pill,
            geometry,
            bubblePointerLength,
            resources.displayMetrics.density,
        )
        return true
    }

    private fun endPanelGesture(cancel: Boolean) {
        val start = panelGestureStartRect
        val screen = screenAboveKeyboard(screenRect())
        val pill = pillRectForPanel(screenRect(), screen)
        val geometry = panelGeometry()
        if (cancel && start != null) {
            reducedPanelRect = start
            reducedPanelRectScreen = panelGestureStartScreen ?: screen
            applyReducedPanelRect(
                screen,
                pill,
                geometry,
                bubblePointerLength,
                resources.displayMetrics.density,
            )
        } else {
            reducedPanelRect?.let { panelPrefs.save(geometry.normalize(it, screen)) }
        }
        panelGestureStartRect = null
        panelGestureStartScreen = null
        panelGestureLastX = 0f
        panelGestureLastY = 0f
        panelGestureHandle = null
    }

    private fun resetPanelPlacement() {
        if (panelExpanded) return
        customPanelPlacement = false
        reducedPanelRect = null
        reducedPanelRectScreen = null
        panelGestureStartRect = null
        panelGestureStartScreen = null
        panelGestureLastX = 0f
        panelGestureLastY = 0f
        panelGestureHandle = null
        panelPrefs.clear()
        currentAnchor?.let(::positionLivePanel)
    }

    private fun updatePanelAffordances() {
        val visible = livePreviewVisible && !panelExpanded && exportPanel == null
        panelMoveHandle?.visibility = if (visible) View.VISIBLE else View.GONE
        panelResizeHandles.values.forEach { handle -> handle.visibility = if (visible) View.VISIBLE else View.GONE }
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
        if (!meetingSurfaceOpen) {
            val format = activeRun?.formatOptions?.format ?: PostProcessingFormats(this).selected()
            val formatLabel = if (format.id == "cleanup") "Texte sans LLM" else format.name
            panelTitle?.apply {
                text = formatLabel
                contentDescription = "Format choisi : $formatLabel"
            }
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
        updatePanelAffordances()
        if (!panelExpanded && customPanelPlacement) {
            cancelPanelTransition()
            if (applyReducedPanelRect(screen, actualPill, panelGeometry(), pointerLength, dp)) return
        }
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
        panelBodyRect = Rect(
            bounds.x + envelope.bodyOffsetX,
            bounds.y + envelope.bodyOffsetY,
            bodyLayout?.width?.coerceAtLeast(1) ?: bounds.width,
            bodyLayout?.height?.coerceAtLeast(1) ?: bounds.height,
        )
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
            setMeetingMode(transcriptionModes.snapshot().mode == TranscriptionMode.MEETING)
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

        val liveView = object : OverlayTranscriptEditor(this@OverlayService) {
            override fun onSelectionChanged(start: Int, end: Int) {
                super.onSelectionChanged(start, end)
                if (liveText === this && !updatingLiveText && !liveEditorChanging && isTranscriptEditable()) {
                    tailFollower?.userInteraction()
                    val projection = imageBlockRenderer.read(this)
                    vocabularyTracker.onSelectionChanged(
                        projection.rawText(),
                        projection.rawOffsetForEditor(start),
                        projection.rawOffsetForEditor(end),
                    )
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
            imageBlockAtEditorOffset = { offset -> imageBlockRenderer.blockAtEditorOffset(this, offset)?.id }
            imageBlockRange = { blockId -> imageBlockRenderer.blockRange(this, blockId) }
            moveImageBlockToEditorOffset = { blockId, editorOffset ->
                val projection = imageBlockRenderer.read(this)
                val caret = projection.caretForEditor(editorOffset)
                val moved = TranscriptImageBlocks.moveToCaret(projection, blockId, caret)
                if (moved == projection) false else {
                    val movedBlock = moved.blocks.firstOrNull { it.id == blockId }
                    val afterBlock = movedBlock?.let {
                        TranscriptImageCaret(
                            it.rawOffset,
                            moved.blocks.takeWhile { candidate -> candidate.id != it.id }
                                .count { candidate -> candidate.rawOffset == it.rawOffset } + 1,
                        )
                    }
                    renderTranscriptProjection(moved, caret = afterBlock)
                    persistDraft(moved.rawText())
                    tailFollower?.changed()
                    true
                }
            }
            onProtectedImageClipboard = { toast("Pour déplacer une image, faites un appui long puis glissez-la au curseur.") }
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
                        val oldProjection = imageBlockRenderer.read(this@apply)
                        imageIdsBeforeTextEdit = oldProjection.blocks.flatMap { it.images }.map { it.id }.toSet()
                        val raw = oldProjection.rawText()
                        val rawStart = TranscriptImageBlocks.rawText(s?.subSequence(0, start.coerceIn(0, s.length)) ?: "").length
                        val rawCount = TranscriptImageBlocks.rawText(s?.subSequence(start.coerceIn(0, s.length),
                            (start + count).coerceIn(0, s.length)) ?: "").length
                        vocabularyTracker.beforeChange(raw, rawStart, rawCount, after,
                            oldProjection.rawOffsetForEditor(selectionStart), oldProjection.rawOffsetForEditor(selectionEnd))
                        vocabularySuggestion = null
                        vocabularyDismiss?.let(main::removeCallbacks)
                        vocabularyDismiss = null
                        setVocabularySuggestionVisible(false)
                    }
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!updatingLiveText && isTranscriptEditable()) {
                        invalidateNoteInsertion()
                        val projection = imageBlockRenderer.read(this@apply)
                        val raw = projection.rawText()
                        editableTranscript.edit(raw)
                        activeRun?.let { scheduleLocalFormatting(it, raw) }
                        if (recoveredDraft != null) recoveredDraft = raw
                        persistDraft(raw)
                        val visibleImageIds = projection.blocks.flatMap { it.images }.map { it.id }.toSet()
                        if (visibleImageIds != imageIdsBeforeTextEdit) refreshNoteImages()
                        imageIdsBeforeTextEdit = visibleImageIds
                        if (s?.contains(TranscriptImageBlocks.OBJECT_REPLACEMENT) == true &&
                            projection.editorText != s.toString()) {
                            val selection = projection.caretForEditor(selectionEnd.coerceAtLeast(0))
                            post { if (!updatingLiveText) renderTranscriptProjection(projection, selection.rawOffset, selection.rawOffset) }
                        }
                    }
                }
                override fun afterTextChanged(s: Editable?) {
                    if (!updatingLiveText && isTranscriptEditable()) {
                        vocabularyTracker.afterChange(TranscriptImageBlocks.rawText(s ?: ""), SystemClock.elapsedRealtime())
                    } else vocabularyTracker.onProgrammaticTextChanged(TranscriptImageBlocks.rawText(s ?: ""))
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
            // The tail is drawn after the body and must remain on the same Z plane so its
            // short overlap hides the body's straight stroke without adding a rectangular
            // elevation shadow of its own.
            elevation = panelBody.elevation
            outlineProvider = null
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
        var moveResetTriggered = false
        lateinit var moveResetRunnable: Runnable
        val moveHandle = TextView(this).apply {
            text = "⠿"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(overlayPalette.green)
            contentDescription = "Déplacer la fenêtre de texte. Appui long pour réinitialiser la taille et la position"
            isFocusable = true
            setOnLongClickListener {
                resetPanelPlacement()
                true
            }
            moveResetRunnable = Runnable {
                if (panelGestureStartRect != null) {
                    moveResetTriggered = true
                    performLongClick()
                }
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        moveResetTriggered = false
                        main.removeCallbacks(moveResetRunnable)
                        val accepted = beginPanelGesture(event.rawX, event.rawY, null)
                        if (accepted) main.postDelayed(moveResetRunnable, 550L)
                        accepted
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (moveResetTriggered) true else {
                            main.removeCallbacks(moveResetRunnable)
                            updatePanelGesture(event.rawX, event.rawY)
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        main.removeCallbacks(moveResetRunnable)
                        endPanelGesture(cancel = true)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(moveResetRunnable)
                        endPanelGesture(cancel = moveResetTriggered)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(moveResetRunnable)
                        endPanelGesture(cancel = true)
                        true
                    }
                    else -> false
                }
            }
        }
        panelMoveHandle = moveHandle
        toolbar.addView(moveHandle, LinearLayout.LayoutParams((40 * dp).toInt(), (48 * dp).toInt()))
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
            if (meetingSurfaceOpen) {
                meetingPanelController?.endEditing()
                meetingRecordingController?.flushDraft()
            }
            panelHidden = true
            setLivePreviewVisible(false)
            toast(if (meetingSurfaceOpen) "Glissez la pastille vers le haut pour revoir la réunion." else "Glissez la pastille vers le haut pour revoir le texte.")
        }.apply { tag = "overlay-hide-panel" }
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
            Triple(R.drawable.ic_note_screenshot, "Capturer une série d’images de l’écran", { captureNoteImage(NoteImageKind.SCREENSHOT) }),
            Triple(R.drawable.ic_note_camera, "Prendre plusieurs photos ou scanner des documents", { captureNoteImage(NoteImageKind.CAMERA) }),
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

        fun resizeHandle(handle: PanelResizeHandle, glyph: String, label: String, gravity: Int) {
            val view = TextView(this).apply {
                text = glyph
                textSize = 18f
                this.gravity = Gravity.CENTER
                setTextColor(overlayPalette.green)
                contentDescription = label
                isFocusable = true
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> beginPanelGesture(event.rawX, event.rawY, handle)
                        MotionEvent.ACTION_MOVE -> updatePanelGesture(event.rawX, event.rawY)
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            endPanelGesture(cancel = true)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            endPanelGesture(cancel = false)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            endPanelGesture(cancel = true)
                            true
                        }
                        else -> false
                    }
                }
            }
            panelResizeHandles[handle] = view
            panelBody.addView(view, FrameLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt(), gravity))
        }
        resizeHandle(PanelResizeHandle.TOP_LEFT, "⌜", "Redimensionner depuis le coin supérieur gauche", Gravity.TOP or Gravity.START)
        resizeHandle(PanelResizeHandle.TOP_RIGHT, "⌝", "Redimensionner depuis le coin supérieur droit", Gravity.TOP or Gravity.END)
        resizeHandle(PanelResizeHandle.BOTTOM_RIGHT, "⌟", "Redimensionner depuis le coin inférieur droit", Gravity.BOTTOM or Gravity.END)
        resizeHandle(PanelResizeHandle.BOTTOM_LEFT, "⌞", "Redimensionner depuis le coin inférieur gauche", Gravity.BOTTOM or Gravity.START)

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
        var dragLastRawX = 0f; var dragLastRawY = 0f
        var touchInterrupted = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val gestureMode = PillGestureMode(touchSlop)
        val notesGesture = VerticalSwipeGesture(touchSlop, maxOf(24 * dp, 3 * touchSlop))
        val formatGesture = VerticalSwipeGesture(touchSlop, maxOf(56 * dp, 3 * touchSlop))
        val formatSwipe = FormatSwipeGesture(
            touchSlop = touchSlop,
            openDistance = maxOf(56 * dp, 3 * touchSlop),
            selectionActivationDistance = maxOf(24 * dp, touchSlop),
            selectionStep = maxOf(56 * dp, 3 * touchSlop),
        )
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
            } else if (transcriptionModes.snapshot().mode == TranscriptionMode.MEETING) {
                when (meetingPillPhase()) {
                    MeetingPillInteraction.Phase.PAUSED -> "↓ Enregistrer"
                    MeetingPillInteraction.Phase.LISTENING -> "Mettre en pause pour enregistrer"
                    else -> "↓ Réunion"
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

        fun reducedPanelObstacle(): Rect? = if (
            livePreviewVisible && !panelExpanded && customPanelPlacement && exportPanel == null
        ) panelBodyFromCurrentLayout() else null

        fun accessiblePillDrop(current: Rect, screen: Rect): Pair<Anchor, Rect>? {
            val obstacle = reducedPanelObstacle() ?: run {
                val anchor = OverlayPlacement.snap(Point(current.x, current.y), Rect(0, 0, current.width, current.height), screen)
                val point = OverlayPlacement.pillPosition(anchor, Rect(0, 0, current.width, current.height), screen)
                return anchor to Rect(point.x, point.y, current.width, current.height)
            }
            val pillSize = Rect(0, 0, current.width, current.height)
            val verticalOffset = if (screen.height <= current.height) 0f
            else ((current.y - screen.y).toFloat() / (screen.height - current.height)).coerceIn(0f, 1f)
            val horizontalOffset = if (screen.width <= current.width) 0f
            else ((current.x - screen.x).toFloat() / (screen.width - current.width)).coerceIn(0f, 1f)
            data class Drop(val anchor: Anchor, val rect: Rect, val distance: Int)
            val geometry = panelGeometry()
            val candidates = listOf(
                Anchor(Edge.LEFT, verticalOffset),
                Anchor(Edge.TOP, horizontalOffset),
                Anchor(Edge.RIGHT, verticalOffset),
                Anchor(Edge.BOTTOM, horizontalOffset),
            ).mapNotNull { anchor ->
                val point = OverlayPlacement.pillPosition(anchor, pillSize, screen)
                val target = Rect(point.x, point.y, current.width, current.height)
                val reached = geometry.move(
                    current,
                    (target.x - current.x).toFloat(),
                    (target.y - current.y).toFloat(),
                    screen,
                    obstacle,
                )
                target.takeIf { reached == it }?.let {
                    Drop(anchor, it, abs(it.x - current.x) + abs(it.y - current.y))
                }
            }
            return candidates.minWithOrNull(compareBy<Drop> { it.distance })?.let { it.anchor to it.rect }
        }

        fun finishDrag() {
            val screen = screenRect()
            val pillSize = Rect(0, 0, lp.width, lp.height)
            val current = Rect(lp.x, lp.y, lp.width, lp.height)
            val drop = accessiblePillDrop(current, screen)
            pillPositionBeforeKeyboard = null
            if (drop != null) {
                val (finalAnchor, final) = drop
                currentAnchor = finalAnchor
                lp.x = final.x
                lp.y = final.y
                prefs.saveAnchor(finalAnchor)
            }
            updatePillLayout()
            prefs.buttonX = lp.x
            prefs.buttonY = lp.y
        }

        fun updateDrag(rawX: Float, rawY: Float) {
            val stepDx = rawX - dragLastRawX
            val stepDy = rawY - dragLastRawY
            val totalDx = rawX - touchX
            val totalDy = rawY - touchY
            dragLastRawX = rawX
            dragLastRawY = rawY
            if (!moved && abs(totalDx) + abs(totalDy) <= touchSlop) return
            moved = true
            tapCoordinator.reset()
            main.removeCallbacks(longPress)
            val screen = screenRect()
            val pillSize = Rect(0, 0, lp.width, lp.height)
            val current = Rect(lp.x, lp.y, lp.width, lp.height)
            val panelObstacle = reducedPanelObstacle()
            val next = if (panelObstacle != null) {
                panelGeometry().move(current, stepDx, stepDy, screen, panelObstacle)
            } else {
                val clamped = OverlayPlacement.clampPill(
                    Point((downX + totalDx).toInt(), (downY + totalDy).toInt()),
                    pillSize,
                    screen,
                )
                Rect(clamped.x, clamped.y, lp.width, lp.height)
            }
            lp.x = next.x
            lp.y = next.y
            val dragAnchor = OverlayPlacement.snap(
                Point(lp.x, lp.y), pillSize, screen,
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
                    dragLastRawX = ev.rawX; dragLastRawY = ev.rawY
                    moved = false; touchInterrupted = false
                    gestureMode.begin()
                    notesGesture.begin(state == State.IDLE || state == State.MIC_UNARMED || isTranscriptEditable())
                    formatGesture.begin(state == State.IDLE || state == State.MIC_UNARMED || isTranscriptEditable())
                    val formatStore = PostProcessingFormats(this@OverlayService)
                    val formatItems = formatStore.all()
                    val selectedFormatIndex = formatItems.indexOfFirst { it.id == formatStore.selected().id }
                        .coerceAtLeast(0)
                    formatSwipe.begin(
                        enabled = (state == State.IDLE || state == State.MIC_UNARMED) &&
                            transcriptionModes.snapshot().mode == TranscriptionMode.DICTATION,
                        initialIndex = selectedFormatIndex,
                        itemCount = formatItems.size,
                    )
                    val meetingPhase = if (transcriptionModes.snapshot().mode == TranscriptionMode.MEETING) {
                        meetingPillPhase()
                    } else null
                    pauseGesture.begin(
                        state == State.RECORDING || meetingPhase == MeetingPillInteraction.Phase.LISTENING ||
                            meetingPhase == MeetingPillInteraction.Phase.PAUSED,
                    )
                    wake()
                    main.removeCallbacks(longPress)
                    main.postDelayed(longPress, 400)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    touchInterrupted = true
                    main.removeCallbacks(longPress)
                    hideGestureHint()
                    formatSwipe.cancel()
                    if (formatMenuParams != null) dismissFloatingMenu()
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
                        updateDrag(ev.rawX, ev.rawY)
                    } else if (gestureMode.mode == PillGestureMode.Mode.SHORTCUT) {
                        main.removeCallbacks(longPress)
                        tapCoordinator.reset()
                        val formatUpdate = if (state == State.IDLE || state == State.MIC_UNARMED) {
                            if (transcriptionModes.snapshot().mode == TranscriptionMode.DICTATION) {
                                formatSwipe.move(dx, dy)
                            } else null
                        } else null
                        if (formatUpdate?.opened != true) previewGesture(dx, dy)
                        if (formatUpdate != null) {
                            val update = formatUpdate
                            if (update.openedNow) {
                                showFormatPicker(swipeMode = true, touchable = false)
                                hideGestureHint()
                            }
                        }
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
                        updateDrag(ev.rawX, ev.rawY)
                        if (moved) finishDrag()
                    } else if (transcriptionModes.snapshot().mode == TranscriptionMode.MEETING) {
                        meetingGestureForRelease(dx, dy, gestureMode.mode == PillGestureMode.Mode.WAITING)
                            ?.let(::dispatchMeetingPillGesture)
                    } else if (gestureMode.mode == PillGestureMode.Mode.SHORTCUT) {
                        tapCoordinator.reset()
                        val formatResult = formatSwipe.release(dx, dy)
                        if (formatResult.action == FormatSwipeGesture.ReleaseAction.COMMIT ||
                            formatResult.action == FormatSwipeGesture.ReleaseAction.OPEN_MENU
                        ) {
                            // A swipe reveals the common menu but never selects an item; selection
                            // is an explicit tap. Materialize it here for a fast DOWN/UP reveal.
                            if (formatMenuParams == null) showFormatPicker(swipeMode = true, touchable = true)
                            enableFormatMenuTouch()
                        } else {
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
                    formatSwipe.cancel()
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
        applyTranscriptionMode(transcriptionModes.snapshot().mode)
        liveText = liveView
        liveScroll = scroll
        this.livePanel = livePanel
        livePanelBody = panelBody
        bubblePointer = pointer
        bubblePointerLength = (12 * dp).toInt().coerceAtLeast(1)
        params = lp
        liveParams = panelParams
        currentAnchor = initialAnchor
        customPanelPlacement = panelPrefs.load() != null
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

    /** Canonical projection source for draft recovery when the editor view is not yet attached. */
    private fun storedTranscriptProjection(textOverride: String? = null): TranscriptImageProjection {
        val supplied = textOverride ?: draftStore.load().orEmpty()
        val note = activeNoteId?.let(notes::get)
        val legacy = if (note != null)
            TranscriptImageBlocks.fromLegacyNoteDraft(supplied, note.images)
        else null
        val raw = TranscriptImageBlocks.rawText(legacy?.rawText() ?: supplied)
        val base = if (note != null) {
            val saved = legacy ?: TranscriptImageBlocks.fromNote(note.text, note.images)
            val mapped = if (saved.rawText() == raw) saved else {
                val offsets = mutableMapOf<Int, Int>()
                val shifted = saved.blocks.map { block ->
                    val anchor = DraftImageContext.move(
                        listOf(DraftImageCapture(block.id, block.rawOffset)),
                        saved.rawText(),
                        raw,
                    ).single().offset
                    val order = offsets.getOrDefault(anchor, 0)
                    offsets[anchor] = order + 1
                    block.copy(rawOffset = anchor, orderAtOffset = order)
                }
                TranscriptImageBlocks.fromBlocks(raw, shifted)
            }
            mapped
        } else TranscriptImageBlocks.fromBlocks(raw, emptyList())
        return TranscriptImageBlocks.combine(base, draftStore.captures())
    }

    private fun currentTranscriptProjection(): TranscriptImageProjection =
        liveText?.let(imageBlockRenderer::read) ?: storedTranscriptProjection()

    private fun projectionForText(text: String): TranscriptImageProjection {
        val raw = TranscriptImageBlocks.rawText(text)
        val current = liveText?.let(imageBlockRenderer::read)
        if (current != null && current.rawText() == raw) return current
        val stored = storedTranscriptProjection()
        val source = current ?: stored
        val before = current?.rawText() ?: draftStore.load().orEmpty()
        val shifted = source.blocks.map { block ->
            val anchor = DraftImageContext.move(
                listOf(DraftImageCapture(block.id, block.rawOffset)),
                before,
                raw,
            ).single().offset
            block.copy(rawOffset = anchor)
        }
        return TranscriptImageBlocks.combine(TranscriptImageBlocks.fromBlocks(raw, shifted), draftStore.captures())
    }

    private fun renderTranscriptProjection(
        projection: TranscriptImageProjection,
        selectionStartRaw: Int? = null,
        selectionEndRaw: Int? = selectionStartRaw,
        caret: TranscriptImageCaret? = null,
    ) {
        val editor = liveText ?: return
        val start = caret?.let(projection::editorOffsetForCaret)
            ?: selectionStartRaw?.let(projection::editorOffsetForRaw)
        val end = caret?.let(projection::editorOffsetForCaret)
            ?: selectionEndRaw?.let(projection::editorOffsetForRaw)
        updatingLiveText = true
        try { imageBlockRenderer.render(editor, projection, start, end) }
        finally { updatingLiveText = false }
    }

    private fun rawTranscriptText(): String =
        liveText?.let { imageBlockRenderer.read(it).rawText() } ?: TranscriptImageBlocks.rawText(draftStore.load().orEmpty())

    private fun noteImagesIn(projection: TranscriptImageProjection): List<NoteImage> =
        projection.blocks.flatMap { it.images }.distinctBy { it.id }

    private fun captureNoteImage(kind: NoteImageKind) {
        if (!isTranscriptEditable() || hasImageCaptureInFlight() || imageDeliveryBusy) return
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            toast("Déverrouillez le téléphone pour capturer une image.")
            return
        }
        if (kind == NoteImageKind.SCREENSHOT && WhisperAccessibilityService.connected == null) {
            toast("Activez le service d’accessibilité DictAI pour capturer l’écran.")
            return
        }
        val projection = currentTranscriptProjection()
        val rawText = projection.rawText()
        val visibleImages = projection.blocks.flatMap { it.images }.distinctBy { it.id }
        val remaining = (NoteImage.MAX_IMAGES - visibleImages.size).coerceAtLeast(0)
        if (remaining == 0) { toast("La note est limitée à ${NoteImage.MAX_IMAGES} images."); return }
        val editor = liveText
        val selection = editor?.selectionEnd?.takeIf { it >= 0 } ?: (editor?.length() ?: 0)
        val caret = projection.caretForEditor(selection)
        val pending = runCatching { imageStore.beginBatch(
            kind,
            resume = state == State.RECORDING,
            number = NoteImage.nextNumber(visibleImages, rawText),
            maxImages = remaining,
        ) }.getOrElse {
            toast("Capture indisponible : vérifiez l’espace de stockage."); return
        }
        draftStore.save(rawText)
        val existingNoteImages = notes.get(activeNoteId)?.images?.size ?: 0
        if (!draftStore.reserveCapture(pending.id, existingNoteImages, caret.rawOffset, caret.orderAtOffset)) {
            runCatching { imageStore.cancelBatch(pending.id) }
            if (!clearPendingSafely(pending.id)) {
                toast("La session de capture reste récupérable; réessayez après redémarrage du service.")
                return
            }
            toast("La note est limitée à ${NoteImage.MAX_IMAGES} images.")
            return
        }
        screenshotBatchId = if (kind == NoteImageKind.SCREENSHOT) pending.id else null
        refreshNoteImages()
        releaseTranscriptFocus()
        dismissFloatingMenu()
        formatDialog?.dismiss()
        if (kind == NoteImageKind.CAMERA) {
            if (state == State.RECORDING) pauseRec()
            launchCameraWhenPaused(pending)
        } else {
            if (state == State.RECORDING) pauseRec()
            showScreenshotBatchWhenPaused(pending)
        }
    }

    private fun showScreenshotBatchWhenPaused(pending: PendingNoteCapture) {
        if (localEngineLifecycle.isDestroyed() || imageStore.pending()?.id != pending.id) return
        if (state == State.PAUSING) { main.postDelayed({ showScreenshotBatchWhenPaused(pending) }, 60); return }
        if (state != State.PAUSED) {
            imageStore.fail(pending.id, "Capture interrompue : texte conservé.")
            cancelScreenshotBatch(pending.id, showMessage = true)
            return
        }
        captureWindowsHidden = true
        container?.visibility = View.GONE
        setLivePreviewVisible(false)
        showScreenshotBatchBar(pending)
    }

    private fun showScreenshotBatchBar(pending: PendingNoteCapture) {
        if (localEngineLifecycle.isDestroyed() || pending.id != screenshotBatchId) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bar = screenshotBatchBar ?: ScreenshotBatchBar(this).also { screenshotBatchBar = it }
        bar.bind(pending)
        bar.contentDescription = null
        bar.cancelButton.contentDescription = "Annuler la série et supprimer ses images"
        val invalidMeetingReservation = pending.meetingCapture &&
            (pending.meetingAnchorInvalid || pending.meetingAnchor == null)
        if (invalidMeetingReservation) {
            if (pending.accepted) bar.showDeliveryRetry()
            bar.contentDescription = "Impossible de rattacher les images à la réunion. Annulez la réservation ou gardez-la en attente."
            bar.captureButton.isEnabled = false
            bar.acceptButton.isEnabled = false
            bar.cancelButton.visibility = View.VISIBLE
            bar.cancelButton.isEnabled = true
            bar.cancelButton.contentDescription = if (pending.accepted) {
                "Annuler la réservation Réunion. Les images acceptées seront conservées."
            } else {
                "Annuler la réservation de réunion."
            }
            bar.onCapture = null
            bar.onAccept = null
            bar.onCancel = { cancelScreenshotBatch(pending.id) }
            bar.onRemoveImage = null
        } else if (pending.accepted) {
            bar.showDeliveryRetry()
            bar.onCapture = { retryAcceptedBatchDelivery(pending) }
            bar.onAccept = null
            bar.onCancel = null
            bar.onRemoveImage = null
        } else {
            bar.onCapture = { captureScreenshotInBatch(pending.id) }
            bar.onAccept = { acceptScreenshotBatch(pending.id) }
            bar.onCancel = { cancelScreenshotBatch(pending.id) }
            bar.onRemoveImage = { imageId ->
                runCatching { imageStore.removeBatchImage(pending.id, imageId) }
                imageStore.pending()?.takeIf { it.id == pending.id }?.let(::showScreenshotBatchBar)
            }
        }
        val bounds = screenRect()
        val lp = screenshotBatchParams ?: WindowManager.LayoutParams(
            min((360 * resources.displayMetrics.density).toInt(), (bounds.width - 16).coerceAtLeast(1)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * resources.displayMetrics.density).toInt()
            windowAnimations = 0
        }.also { screenshotBatchParams = it }
        if (!screenshotBatchBarAdded) runCatching { wm.addView(bar, lp); screenshotBatchBarAdded = true }
        else runCatching { wm.updateViewLayout(bar, lp) }
    }

    private fun hideScreenshotBatchBar() {
        val bar = screenshotBatchBar ?: return
        if (!screenshotBatchBarAdded) return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(bar) }
        screenshotBatchBarAdded = false
    }

    private fun clearPendingSafely(sessionId: String): Boolean = runCatching {
        imageStore.clearPending(sessionId)
        true
    }.onFailure { error ->
        Log.w(TAG, "event=image_batch_clear outcome=retry type=${error.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun rememberMeetingImageResumeContext(
        pending: PendingNoteCapture,
        operation: MeetingDocumentActionOperation,
    ) {
        val anchor = pending.meetingAnchor ?: return
        if (anchor.sessionId != operation.sessionId || operation.runId != operation.controller.state.document.runId) return
        meetingImageResumeContext = MeetingImageResumeContext(
            pendingId = pending.id,
            sessionId = operation.sessionId,
            runId = operation.runId,
            controller = operation.controller,
        )
    }

    private fun clearMeetingImageResumeContext(pendingId: String) {
        if (meetingImageResumeContext?.pendingId == pendingId) meetingImageResumeContext = null
    }

    private fun hasImageCaptureInFlight(): Boolean = imageStore.pending() != null || acceptedBatchRetry != null

    private fun captureScreenshotInBatch(sessionId: String) {
        if (screenshotCaptureBusy) return
        val pending = imageStore.retryBatch(sessionId)?.takeIf { it.id == sessionId && !it.accepted } ?: return
        if (pending.allImages.size >= pending.maxImages) { showScreenshotBatchBar(pending); return }
        val accessibility = WhisperAccessibilityService.connected
        if (accessibility == null) { toast("Service de capture indisponible."); return }
        screenshotCaptureBusy = true
        hideScreenshotBatchBar()
        container?.visibility = View.GONE
        setLivePreviewVisible(false)
        main.postDelayed({
            if (imageStore.pending()?.let { it.id == sessionId && !it.accepted } != true || localEngineLifecycle.isDestroyed()) {
                screenshotCaptureBusy = false
                return@postDelayed
            }
            val currentAccessibility = WhisperAccessibilityService.connected
            if (currentAccessibility == null) {
                imageStore.fail(sessionId, "Service de capture indisponible. Réessayez.")
                screenshotCaptureBusy = false
                imageStore.pending()?.let(::showScreenshotBatchBar)
            } else NoteScreenshot.capture(currentAccessibility, imageStore, sessionId,
                restoreWindows = {
                    main.post {
                        screenshotCaptureBusy = false
                        imageStore.pending()?.takeIf { it.id == sessionId && !it.accepted }?.let(::showScreenshotBatchBar)
                    }
                },
                finished = {
                    screenshotCaptureBusy = false
                    if (imageStore.pending()?.id == sessionId) imageStore.pending()?.let(::showScreenshotBatchBar)
                })
        }, 250)
    }

    private fun acceptScreenshotBatch(sessionId: String) {
        if (screenshotBatchId != sessionId || screenshotCaptureBusy) return
        val pending = imageStore.pending()?.takeIf { it.id == sessionId && it.batch } ?: return
        if (pending.allImages.isEmpty()) { toast("Prenez au moins une capture avant de valider."); return }
        runCatching { imageStore.acceptBatch(sessionId) }
            .onFailure { toast(it.message ?: "Impossible de valider cette série."); return }
        hideScreenshotBatchBar()
        finishPendingImage()
    }

    private fun cancelScreenshotBatch(sessionId: String, showMessage: Boolean = false) {
        if (screenshotBatchId != sessionId) return
        val pending = imageStore.pending()?.takeIf { it.id == sessionId } ?: return
        val invalidAcceptedMeetingReservation = pending.accepted && pending.meetingCapture &&
            (pending.meetingAnchorInvalid || pending.meetingAnchor == null)
        if (pending.accepted && !invalidAcceptedMeetingReservation) return
        if (pending.meetingCapture) {
            cancelMeetingImageBatch(pending, showMessage)
            return
        }
        if (runCatching { imageStore.cancelBatch(sessionId) }.isFailure) {
            restoreAfterImageBatch(null)
            val retry = imageStore.pending()?.takeIf { it.id == sessionId }
            screenshotBatchId = retry?.id
            retry?.let(::showScreenshotBatchBar)
            toast("Impossible de terminer l’annulation. La série reste récupérable.")
            return
        }
        if (!clearPendingSafely(sessionId)) {
            restoreAfterImageBatch(null)
            val retry = imageStore.pending()?.takeIf { it.id == sessionId }
            screenshotBatchId = retry?.id
            retry?.let(::showScreenshotBatchBar)
            toast("Annulation enregistrée; réessayez pour terminer le nettoyage de la série.")
            return
        }
        draftStore.forgetCaptures(setOf(sessionId))
        hideScreenshotBatchBar()
        screenshotBatchId = null
        restoreAfterImageBatch(null)
        refreshNoteImages()
        if (pending.resumeListening && activeRun != null && state == State.PAUSED && !archiveAfterImageDelivery)
            resumeRec()
        if (showMessage) toast("Capture annulée. Le texte est conservé.")
    }

    private fun cancelMeetingImageBatch(pending: PendingNoteCapture, showMessage: Boolean) {
        val sessionId = pending.meetingAnchor?.sessionId.orEmpty()
        if (runCatching { imageStore.cancelBatch(pending.id) }.isFailure) {
            retainMeetingImageBatch(pending, "Impossible de terminer l’annulation. La série reste récupérable.")
            return
        }
        if (!clearPendingSafely(pending.id)) {
            val retry = imageStore.pending()?.takeIf { it.id == pending.id } ?: pending
            retainMeetingImageBatch(retry, "Annulation enregistrée; réessayez pour terminer le nettoyage de la série.")
            return
        }
        acceptedBatchRetry = null
        screenshotBatchId = null
        hideScreenshotBatchBar()
        restoreAfterMeetingImageBatch(sessionId)
        resumeMeetingImageIfStillOwned(pending)
        if (showMessage) toast("Capture annulée. La réunion est conservée.")
    }

    private fun restoreAfterImageBatch(caret: TranscriptImageCaret?) {
        captureWindowsHidden = false
        container?.visibility = View.VISIBLE
        val projection = storedTranscriptProjection()
        renderTranscriptProjection(projection, caret = caret)
        persistDraft(projection.rawText())
        setLivePreviewVisible(isTranscriptEditable())
        screenshotBatchId = null
        refreshNoteImages()
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
        if (pending.meetingCapture) {
            if (pending.complete) {
                finishPendingImage()
            } else if (pending.kind == NoteImageKind.CAMERA && NoteCameraActivity.handles(pending.id)) {
                // A surviving meeting viewfinder may finish its reservation. Never route it
                // through Dictation's pause/camera-recovery path after a service recreation.
                return
            } else {
                screenshotBatchId = pending.takeIf { it.batch }?.id
                if (pending.batch) showScreenshotBatchBar(pending)
                else retainMeetingImageBatch(pending, "La capture Réunion reste à vérifier. Les données sont conservées.")
            }
            return
        }
        if (pending.batch) {
            if (pending.accepted) { finishPendingImage(); return }
            if (pending.error != null) { finishPendingImage(); return }
            if (pending.kind == NoteImageKind.SCREENSHOT) {
                screenshotBatchId = pending.id
                if (state != State.PAUSED) setState(State.PAUSED)
                showScreenshotBatchWhenPaused(pending)
            } else if (!NoteCameraActivity.handles(pending.id)) {
                if (state != State.PAUSED) setState(State.PAUSED)
                launchCameraWhenPaused(pending)
            }
            return
        }
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
        if (pending.meetingCapture && !pending.batch) {
            retainMeetingImageBatch(pending, "La capture Réunion doit être vérifiée. Les données restent conservées.")
            return
        }
        if (pending.batch) {
            if (pending.meetingCapture) {
                if (pending.meetingAnchorInvalid || pending.meetingAnchor == null) {
                    retainMeetingImageBatch(pending, "L’emplacement de la capture Réunion doit être vérifié. La série reste conservée.")
                    return
                }
                if (!pending.accepted) {
                    if (!clearPendingSafely(pending.id)) {
                        retainMeetingImageBatch(pending, "Le nettoyage de la capture a échoué. La session reste récupérable.")
                        return
                    }
                    hideScreenshotBatchBar()
                    screenshotBatchId = null
                    restoreAfterMeetingImageBatch(pending.meetingAnchor.sessionId)
                    resumeMeetingImageIfStillOwned(pending)
                    pending.error?.let(::toast)
                    finishImageDelivery()
                    return
                }
                deliverAcceptedBatch(pending)
                return
            }
            if (!pending.accepted) {
                // An explicit cancel/failure has no accepted media. Remove only its reservation.
                if (!clearPendingSafely(pending.id)) {
                    restoreAfterImageBatch(null)
                    val retry = imageStore.pending()?.takeIf { it.id == pending.id }
                    screenshotBatchId = retry?.id
                    retry?.let(::showScreenshotBatchBar)
                    toast("Le nettoyage de la capture a échoué. La session reste récupérable.")
                    return
                }
                draftStore.forgetCaptures(setOf(pending.id))
                hideScreenshotBatchBar()
                screenshotBatchId = null
                restoreAfterImageBatch(null)
                pending.error?.let(::toast)
                if (pending.resumeListening && activeRun != null && state == State.PAUSED && !archiveAfterImageDelivery)
                    resumeRec()
                finishImageDelivery()
                return
            }
            deliverAcceptedBatch(pending)
            return
        }
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
        if (!clearPendingSafely(pending.id))
            toast("La capture est conservée; le nettoyage sera réessayé au prochain démarrage.")
        refreshNoteImages()
        if (pending.image != null) {
            draftStore.completeCapture(pending.image)
            if (activeNoteId != null && pending.clipboardOnly) {
                val saved = saveNoteWithCaptures(rawTranscriptText())
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

    private fun retryAcceptedBatchDelivery(fallback: PendingNoteCapture) {
        if (imageDeliveryBusy || localEngineLifecycle.isDestroyed()) return
        val persistent = imageStore.pending()
        val pending = if (persistent != null) {
            persistent.takeIf { it.id == fallback.id && it.accepted }
        } else {
            acceptedBatchRetry?.takeIf { it.id == fallback.id && it.accepted }
        } ?: return
        deliverAcceptedBatch(pending)
    }

    private fun deliverAcceptedBatch(pending: PendingNoteCapture) {
        if (pending.meetingCapture) {
            deliverAcceptedMeetingBatch(pending)
            return
        }
        val captures = draftStore.captures()
        val anchor = captures.firstOrNull { it.id == pending.id }
            ?: captures.firstOrNull { it.groupId == pending.id }
        val anchorAfter = anchor?.let { TranscriptImageCaret(it.offset, it.orderAtOffset + 1) }
        val existingNoteImages = notes.get(activeNoteId)?.images?.size ?: 0
        val committed = runCatching {
            draftStore.completeBatch(pending.id, pending.allImages, existingNoteImages)
        }.getOrDefault(false)
        if (!committed) {
            acceptedBatchRetry = pending
            restoreAfterImageBatch(anchorAfter)
            screenshotBatchId = pending.id
            showScreenshotBatchBar(pending)
            toast("La série n’a pas pu être enregistrée durablement. Réessayez l’ajout au texte.")
            return
        }
        // Keep the accepted session until the full image batch is durable in the draft.
        if (!clearPendingSafely(pending.id)) {
            acceptedBatchRetry = pending
            restoreAfterImageBatch(anchorAfter)
            screenshotBatchId = pending.id
            val retry = imageStore.pending()?.takeIf { it.id == pending.id && it.accepted } ?: pending
            showScreenshotBatchBar(retry)
            toast("Images conservées dans le brouillon. Réessayez l’ajout des images pour terminer.")
            return
        }
        acceptedBatchRetry = null
        hideScreenshotBatchBar()
        screenshotBatchId = null
        restoreAfterImageBatch(anchorAfter)
        val count = pending.allImages.size
        toast(if (count == 1) "1 image ajoutée au texte." else "$count images ajoutées au texte.")
        saveAcceptedBatchToGallery(pending)
    }

    private fun deliverAcceptedMeetingBatch(pending: PendingNoteCapture) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { deliverAcceptedMeetingBatch(pending) }
            return
        }
        val anchor = pending.meetingAnchor
        if (!pending.accepted || pending.meetingAnchorInvalid || anchor == null) {
            retainMeetingImageBatch(pending, "L’emplacement de la capture Réunion doit être vérifié. La série reste conservée.")
            return
        }
        val currentController = meetingRecordingController
        if (currentController != null || meetingDraftClaim != null || meetingDraftClaimRequest != null || meetingSurfaceOpen) {
            val controller = currentController?.takeIf {
                meetingSurfaceOpen && it.state.document.sessionId == anchor.sessionId
            }
            if (controller == null) {
                retainMeetingImageBatch(pending, "Une autre réunion est ouverte. La série reste conservée.")
                return
            }
            val savedNote = runCatching { notes.get(anchor.sessionId) }.getOrElse {
                retainMeetingImageBatch(pending, "Le document Réunion ne peut pas être vérifié. La série reste conservée.")
                return
            }
            val source = MeetingImageRecovery.select(
                anchor.sessionId,
                controller.state.document,
                MeetingDocumentRead.Absent,
                savedNote,
            )?.takeIf { it.kind == MeetingImageSourceKind.LIVE }
            if (source == null) {
                retainMeetingImageBatch(pending, "Le document Réunion n’est pas disponible. La série reste conservée.")
                return
            }
            deliverMeetingImageSource(pending, source, controller = controller)
            return
        }

        startTemporaryMeetingImageDelivery(pending, anchor.sessionId)
    }

    private fun startTemporaryMeetingImageDelivery(pending: PendingNoteCapture, sessionId: String) {
        imageDeliveryBusy = true
        val owner = meetingTestOverrides?.draftOwnership ?: MeetingDraftOwnership.processWide
        val path = meetingTestOverrides?.draftFile ?: File(filesDir, "meeting/meeting-draft.json")
        val request = try {
            owner.claim(path)
        } catch (_: Throwable) {
            imageDeliveryBusy = false
            retainMeetingImageBatch(pending, "Le brouillon Réunion ne peut pas être vérifié. La série reste conservée.")
            return
        }
        meetingImageRecoveryRequest = request
        val deliveryMain = Handler(Looper.getMainLooper())
        request.future.whenComplete { claim, claimFailure ->
            if (claimFailure != null || claim == null) {
                deliveryMain.post {
                    if (meetingImageRecoveryRequest === request) meetingImageRecoveryRequest = null
                    if (localEngineLifecycle.isDestroyed()) return@post
                    imageDeliveryBusy = false
                    retainMeetingImageBatch(pending, "Le brouillon Réunion ne peut pas être ouvert. La série reste conservée.")
                }
                return@whenComplete
            }
            deliveryMain.post {
                if (meetingImageRecoveryRequest === request) meetingImageRecoveryRequest = null
                if (localEngineLifecycle.isDestroyed()) {
                    runCatching { claim.relinquishLatest() }
                    return@post
                }
                if (!isPendingAcceptedMeetingBatch(pending)) {
                    imageDeliveryBusy = false
                    claim.relinquishLatest()
                    return@post
                }
                beginTemporaryMeetingImageDelivery(pending, sessionId, claim)
            }
        }
    }

    private fun beginTemporaryMeetingImageDelivery(
        pending: PendingNoteCapture,
        sessionId: String,
        claim: MeetingDraftOwnershipClaim,
    ) {
        val draftRead = claim.snapshot?.let(MeetingDocumentRead::Ready) ?: claim.recoveredDocument
        if (draftRead is MeetingDocumentRead.Ready && draftRead.document.sessionId != sessionId) {
            releaseTemporaryMeetingClaim(claim) {
                if (localEngineLifecycle.isDestroyed()) return@releaseTemporaryMeetingClaim
                imageDeliveryBusy = false
                retainMeetingImageBatch(pending, "Le brouillon appartient à une autre réunion. La série reste conservée.")
            }
            return
        }
        if (draftRead is MeetingDocumentRead.Invalid || draftRead is MeetingDocumentRead.Unsupported) {
            releaseTemporaryMeetingClaim(claim) {
                if (localEngineLifecycle.isDestroyed()) return@releaseTemporaryMeetingClaim
                imageDeliveryBusy = false
                retainMeetingImageBatch(pending, "Le brouillon Réunion reste protégé. La série est conservée.")
            }
            return
        }
        val savedNote = try {
            notes.get(sessionId)
        } catch (_: Throwable) {
            releaseTemporaryMeetingClaim(claim) {
                if (localEngineLifecycle.isDestroyed()) return@releaseTemporaryMeetingClaim
                imageDeliveryBusy = false
                retainMeetingImageBatch(pending, "Le document Réunion ne peut pas être vérifié. La série reste conservée.")
            }
            return
        }
        val source = MeetingImageRecovery.select(sessionId, null, draftRead, savedNote)
        if (source == null) {
            releaseTemporaryMeetingClaim(claim) {
                if (localEngineLifecycle.isDestroyed()) return@releaseTemporaryMeetingClaim
                imageDeliveryBusy = false
                retainMeetingImageBatch(pending, "La réunion d’origine n’est pas disponible. La série reste conservée.")
            }
            return
        }
        deliverMeetingImageSource(pending, source, temporaryClaim = claim)
    }

    private fun deliverMeetingImageSource(
        pending: PendingNoteCapture,
        source: MeetingImageSource,
        controller: MeetingRecordingController? = null,
        temporaryClaim: MeetingDraftOwnershipClaim? = null,
    ) {
        val anchor = pending.meetingAnchor
        if (!pending.accepted || pending.meetingAnchorInvalid || anchor == null) {
            temporaryClaim?.let { releaseTemporaryMeetingClaim(it) }
            imageDeliveryBusy = false
            retainMeetingImageBatch(pending, "L’emplacement de la capture Réunion doit être vérifié. La série reste conservée.")
            return
        }
        if ((controller == null) == (temporaryClaim == null)) {
            temporaryClaim?.let { releaseTemporaryMeetingClaim(it) }
            imageDeliveryBusy = false
            retainMeetingImageBatch(pending, "La livraison Réunion ne peut pas confirmer son propriétaire. La série reste conservée.")
            return
        }
        if (controller == null && source.kind == MeetingImageSourceKind.LIVE) {
            temporaryClaim?.let { releaseTemporaryMeetingClaim(it) }
            imageDeliveryBusy = false
            retainMeetingImageBatch(pending, "Une session Réunion vivante doit rester propriétaire de son document.")
            return
        }

        imageDeliveryBusy = true
        val deliveryMain = Handler(Looper.getMainLooper())
        var temporaryDocument = source.document
        val delivery = MeetingImageDelivery(
            applyDocument = { sessionId, document ->
                check(!localEngineLifecycle.isDestroyed()) { "Le service est fermé" }
                check(isPendingAcceptedMeetingBatch(pending)) { "La réservation de capture a changé" }
                if (controller != null) {
                    check(isLiveMeetingImageController(controller, sessionId)) { "La réunion n’est plus ouverte" }
                    applyMeetingImageDocument(controller, document)
                } else {
                    val claim = requireNotNull(temporaryClaim)
                    check(document.sessionId == sessionId)
                    claim.writer.updateSnapshot(document)
                    claim.rememberSnapshot(document)
                    temporaryDocument = document
                }
            },
            flushDraft = { sessionId, document ->
                check(!localEngineLifecycle.isDestroyed()) { "Le service est fermé" }
                check(isPendingAcceptedMeetingBatch(pending)) { "La réservation de capture a changé" }
                if (controller != null) {
                    check(isLiveMeetingImageController(controller, sessionId)) { "La réunion n’est plus ouverte" }
                    controller.flushDraft()
                } else {
                    val claim = requireNotNull(temporaryClaim)
                    check(document.sessionId == sessionId)
                    claim.writer.updateSnapshot(document)
                    claim.rememberSnapshot(document)
                    temporaryDocument = document
                    claim.writer.flush()
                }
            },
            saveMeeting = { sessionId, document, images ->
                check(!localEngineLifecycle.isDestroyed()) { "Le service est fermé" }
                check(isPendingAcceptedMeetingBatch(pending)) { "La réservation de capture a changé" }
                check(Looper.myLooper() == Looper.getMainLooper()) { "Les notes Réunion appartiennent au thread principal" }
                if (controller != null) check(isLiveMeetingImageController(controller, sessionId)) { "La réunion n’est plus ouverte" }
                else check(document.sessionId == sessionId && temporaryClaim != null)
                notes.saveMeeting(sessionId, document, images)
            },
            clearPending = { id ->
                check(!localEngineLifecycle.isDestroyed()) { "Le service est fermé" }
                check(id == pending.id && isPendingAcceptedMeetingBatch(pending)) { "La réservation de capture a changé" }
                imageStore.clearPending(id)
            },
            resolveMeetingBatchImages = { id, images ->
                check(id == pending.id && isPendingAcceptedMeetingBatch(pending)) { "La réservation de capture a changé" }
                imageStore.resolveMeetingBatchImages(id, images)
            },
            mainExecutor = Executor { runnable ->
                if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
                else check(deliveryMain.post(runnable)) { "La livraison Réunion ne peut plus rejoindre le thread principal" }
            },
            currentDocument = { sessionId ->
                when {
                    controller != null && isLiveMeetingImageController(controller, sessionId) -> controller.state.document
                    controller == null && temporaryDocument.sessionId == sessionId -> temporaryDocument
                    else -> null
                }
            },
            currentImages = { sessionId ->
                notes.get(sessionId)?.takeIf { it.meetingRaw == null && it.meeting?.sessionId == sessionId }?.images
            },
            onAnchorRestored = { sessionId ->
                if (controller != null && isLiveMeetingImageController(controller, sessionId)) {
                    renderMeetingState(controller.state)
                }
            },
        )
        val completion = try {
            delivery.deliver(pending, source)
        } catch (failure: Throwable) {
            CompletableFuture<MeetingImageMutation>().also { it.completeExceptionally(failure) }
        }
        completion.whenComplete { _, failure ->
            val finish: (Throwable?) -> Unit = { releaseFailure ->
                deliveryMain.post {
                    if (localEngineLifecycle.isDestroyed()) return@post
                    if (failure != null || releaseFailure != null) {
                        imageDeliveryBusy = false
                        retainMeetingImageBatch(pending, "L’ajout des images à la réunion a échoué. Réessayez.")
                        return@post
                    }
                    completeMeetingImageDelivery(pending, anchor.sessionId, controller != null)
                }
            }
            if (temporaryClaim != null) releaseTemporaryMeetingClaim(temporaryClaim, finish)
            else finish(null)
        }
    }

    private fun releaseTemporaryMeetingClaim(
        claim: MeetingDraftOwnershipClaim,
        afterRelease: ((Throwable?) -> Unit)? = null,
    ) {
        val release = runCatching { claim.relinquishLatest() }.getOrElse { failure ->
            CompletableFuture<Unit>().also { it.completeExceptionally(failure) }
        }
        if (afterRelease != null) {
            release.whenComplete { _, failure ->
                Handler(Looper.getMainLooper()).post { afterRelease(failure) }
            }
        }
    }

    private fun isPendingAcceptedMeetingBatch(pending: PendingNoteCapture): Boolean {
        val expectedAnchor = pending.meetingAnchor
        if (!pending.batch || !pending.accepted || pending.meetingAnchorInvalid || expectedAnchor == null ||
            pending.noteId != expectedAnchor.sessionId
        ) return false
        fun matches(candidate: PendingNoteCapture): Boolean =
            candidate.id == pending.id && candidate.batch && candidate.accepted &&
                !candidate.meetingAnchorInvalid && candidate.noteId == pending.noteId &&
                candidate.meetingAnchor == expectedAnchor && candidate.meetingAnchor?.sessionId == candidate.noteId

        val persistent = imageStore.pending()
        return if (persistent != null) matches(persistent) else acceptedBatchRetry?.let(::matches) == true
    }

    private fun completeMeetingImageDelivery(pending: PendingNoteCapture, sessionId: String, live: Boolean) {
        acceptedBatchRetry = null
        hideScreenshotBatchBar()
        screenshotBatchId = null
        if (live) restoreAfterMeetingImageBatch(sessionId) else restoreAfterMeetingImageBatch("")
        resumeMeetingImageIfStillOwned(pending)
        val count = pending.allImages.size
        toast(if (count == 1) "1 image ajoutée à la réunion." else "$count images ajoutées à la réunion.")
        saveAcceptedBatchToGallery(pending)
    }

    private fun resumeMeetingImageIfStillOwned(pending: PendingNoteCapture) {
        val context = meetingImageResumeContext?.takeIf { it.pendingId == pending.id }
        clearMeetingImageResumeContext(pending.id)
        if (!pending.resumeListening || context == null) return
        val controller = context.controller
        val document = controller.state.document
        val mode = transcriptionModes.snapshot()
        if (!isLiveMeetingImageController(controller, context.sessionId) ||
            document.runId != context.runId || controller.state.phase != MeetingRecordingPhase.PAUSED ||
            mode.mode != TranscriptionMode.MEETING || mode.poisoned || mode.activeRunMode != TranscriptionMode.MEETING
        ) return
        controller.resume().whenComplete { _, failure ->
            Handler(Looper.getMainLooper()).post {
                if (localEngineLifecycle.isDestroyed() ||
                    !isLiveMeetingImageController(controller, context.sessionId) ||
                    controller.state.document.runId != context.runId
                ) return@post
                if (failure != null) toast("La réunion reste en pause. Reprenez-la depuis le panneau.")
                else renderMeetingState(controller.state)
            }
        }
    }

    private fun isLiveMeetingImageController(controller: MeetingRecordingController, sessionId: String): Boolean =
        meetingSurfaceOpen && meetingRecordingController === controller &&
            controller.state.document.sessionId == sessionId

    private fun applyMeetingImageDocument(controller: MeetingRecordingController, document: MeetingDocument) {
        check(controller.state.document.sessionId == document.sessionId) { "La réunion a changé pendant la livraison" }
        var current = controller.state.document
        val requestedDocumentTurn = document.turns.firstOrNull { it.utteranceId == 0L }
        if (requestedDocumentTurn != null && current.turns.none { it.id == requestedDocumentTurn.id }) {
            val created = controller.ensureDocumentTurn()
            check(created.id == requestedDocumentTurn.id) { "Le tour documentaire a changé" }
            current = controller.state.document
        }
        document.turns.forEach { requested ->
            val live = current.turns.firstOrNull { it.id == requested.id } ?: return@forEach
            val desiredBody = requested.editedText ?: requested.recognizedText
            val liveBody = live.editedText ?: live.recognizedText
            if (desiredBody != liveBody) controller.editTurn(requested.id, desiredBody)
        }
    }

    private fun retainMeetingImageBatch(pending: PendingNoteCapture, message: String) {
        acceptedBatchRetry = pending.takeIf { it.accepted } ?: acceptedBatchRetry
        screenshotBatchId = pending.id
        val invalidMeetingReservation = pending.batch && pending.meetingCapture &&
            (pending.meetingAnchorInvalid || pending.meetingAnchor == null)
        if (pending.allImages.isNotEmpty() || invalidMeetingReservation) showScreenshotBatchBar(pending)
        toast(message)
    }

    private fun restoreAfterMeetingImageBatch(sessionId: String) {
        captureWindowsHidden = false
        hideScreenshotBatchBar()
        screenshotBatchId = null
        container?.visibility = View.VISIBLE
        val controller = meetingRecordingController?.takeIf { isLiveMeetingImageController(it, sessionId) }
        if (controller != null) {
            setLivePreviewVisible(true)
            renderMeetingState(controller.state)
        } else setLivePreviewVisible(isTranscriptEditable())
        refreshNoteImages()
    }

    private fun acceptedBatchImageIds(): Set<String> = buildSet {
        imageStore.pending()?.takeIf { it.batch && it.accepted }?.allImages?.forEach { add(it.id) }
        acceptedBatchRetry?.allImages?.forEach { add(it.id) }
    }

    private fun saveAcceptedBatchToGallery(pending: PendingNoteCapture) {
        imageDeliveryBusy = true
        refreshNoteImages()
        thread(name = "dictai-batch-gallery") {
            val failed = pending.allImages.filter { image ->
                runCatching { CapturedImageGallery.save(this, image) }.isFailure
            }
            main.post {
                if (localEngineLifecycle.isDestroyed()) return@post
                if (failed.isNotEmpty() && !pending.meetingCapture && purpose == DictationPurpose.MESSAGE) {
                    val originalPurpose = purpose
                    val note = saveNoteWithCaptures(rawTranscriptText())
                    purpose = originalPurpose
                    draftStore.purpose = originalPurpose
                    draftStore.noteId = note.id
                    toast("Une partie des images n’a pas pu être copiée dans Photos. La série reste conservée dans la note « " + note.title + " ».")
                } else if (failed.isNotEmpty()) {
                    toast("Certaines images n’ont pas pu être copiées dans Photos; elles restent dans la note.")
                }
                if (activeRun?.finishAfterCapture == true && state == State.PAUSED) stopRec()
                else if (pending.resumeListening && activeRun != null && state == State.PAUSED && !archiveAfterImageDelivery)
                    resumeRec()
                finishImageDelivery()
            }
        }
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
        if (messageAfterImageDelivery) {
            messageAfterImageDelivery = false
            insertPausedMessage()
        }
    }

    private fun replaceNoteText(text: String) {
        resetVocabularyLearning()
        val images = notes.get(activeNoteId)?.images.orEmpty() + draftStore.captures().mapNotNull { it.image }
        val projection = if (NoteImageMarkers.numbers(text).isNotEmpty()) {
            TranscriptImageBlocks.combine(TranscriptImageBlocks.fromNote(text, images), draftStore.captures())
        } else projectionForText(text)
        editableTranscript.anchor(projection.rawText())
        renderTranscriptProjection(projection, projection.rawText().length, projection.rawText().length)
        if (recoveredDraft != null || activeRun == null) recoveredDraft = projection.rawText()
        persistDraft(projection.rawText())
        tailFollower?.changed()
    }

    private fun refreshNoteImages() {
        val note = notes.get(activeNoteId)
        val noteImages = note?.let {
            val byNumber = it.images.associateBy { image -> image.number }
            NoteImageMarkers.readingOrder(it).mapNotNull(byNumber::get).let { ordered ->
                val orderedNumbers = ordered.map { image -> image.number }.toSet()
                ordered + it.images.filter { image -> image.number !in orderedNumbers }
            }
        }.orEmpty()
        val images = (noteImages + draftStore.captures().mapNotNull { it.image }).distinctBy { it.id }
        val pending = imageStore.pending()
        val acceptedRetry = pending?.takeIf { it.batch && it.accepted } ?: acceptedBatchRetry
        val stripPending = pending ?: acceptedBatchRetry
        val editable = isTranscriptEditable() && !imageDeliveryBusy
        val showStrip = images.isNotEmpty() || stripPending != null
        imageStripScroll?.visibility = if (showStrip) View.VISIBLE else View.GONE
        mediaButtons.forEach { button ->
            val current = button.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            current.width = if (showStrip) (48 * resources.displayMetrics.density).toInt() else 0
            current.weight = if (showStrip) 0f else 1f
            button.layoutParams = current
        }
        mediaButtons.forEach { button ->
            button.isEnabled = editable && pending == null && acceptedBatchRetry == null
            button.alpha = if (button.isEnabled) 1f else .4f
        }
        val strip = imageStrip ?: return
        val pendingKey = stripPending?.let {
            "${it.id}:accepted=${it.accepted}:message=${it.message.orEmpty()}:error=${it.error.orEmpty()}"
        }
        val ids = images.map { "${it.id}:${it.number}" } + listOfNotNull(pendingKey)
        if (shownImageIds == ids && strip.childCount > 0) return
        shownImageIds = ids
        strip.removeAllViews()
        val dp = resources.displayMetrics.density
        if (images.isEmpty() || stripPending != null) strip.addView(TextView(this).apply {
            text = when {
                acceptedRetry != null -> "Ajout… ↻"
                stripPending != null -> "Capture… ×"
                else -> ""
            }; textSize = 11f; setTextColor(overlayPalette.inkMuted)
            minWidth = (48 * dp).toInt(); minHeight = (40 * dp).toInt()
            contentDescription = when {
                acceptedRetry != null -> "Réessayer l’ajout des images au texte"
                stripPending != null -> "Annuler la capture en attente"
                else -> ""
            }
            if (acceptedRetry != null) setOnClickListener { retryAcceptedBatchDelivery(acceptedRetry) }
            else if (pending != null) setOnClickListener {
                imageStore.fail(pending.id, "Capture annulée.")
                finishPendingImage()
            }
        })
        images.forEach { image ->
            val frame = FrameLayout(this)
            val thumb = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = "Image ${image.number}, ${image.kind.label}. Appuyer pour voir, maintenir pour les actions, dont Monter et Descendre."
                setOnClickListener { previewNoteImage(image) }
                setOnLongClickListener {
                    if (!hasImageCaptureInFlight() && !imageDeliveryBusy && isTranscriptEditable()) showFloatingMenu("Image ${image.number}", listOf(
                        MenuEntry("Copier l’image", {
                            dismissFloatingMenu()
                            copyCapturedImage(image)
                        }),
                        MenuEntry("Monter l’image", { moveNoteImage(image, NoteImageMove.UP) }),
                        MenuEntry("Descendre l’image", { moveNoteImage(image, NoteImageMove.DOWN) }),
                        MenuEntry("Déplacer au curseur", { moveImageBlockToCurrentCaret(image) }),
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

    private fun moveNoteImage(image: NoteImage, direction: NoteImageMove) {
        if (!isTranscriptEditable() || imageDeliveryBusy || hasImageCaptureInFlight()) return
        // Flush the editor before reordering. The persisted note can lag behind liveText while
        // an edit callback is queued; moving from that stale value would silently overwrite it.
        val currentText = rawTranscriptText().ifEmpty { notes.get(activeNoteId)?.text.orEmpty() }
        val note = saveNoteWithCaptures(currentText)
        val currentImage = note.images.firstOrNull { it.id == image.id }
            ?: note.images.firstOrNull { it.number == image.number }
            ?: return
        val moved = NoteImageMarkers.move(note, currentImage.number, direction)
        dismissFloatingMenu()
        if (moved.text == note.text) {
            toast(if (direction == NoteImageMove.UP) "Image déjà en haut." else "Image déjà en bas.")
            return
        }
        notes.save(note.id, moved.text, note.images)
        replaceNoteText(moved.text)
        refreshNoteImages()
    }

    private fun moveImageBlockToCurrentCaret(image: NoteImage) {
        if (!isTranscriptEditable() || imageDeliveryBusy || hasImageCaptureInFlight()) return
        val projection = currentTranscriptProjection()
        val block = projection.blocks.firstOrNull { candidate -> candidate.images.any { it.id == image.id } } ?: return
        val editor = liveText ?: return
        val caret = projection.caretForEditor(editor.selectionEnd.coerceAtLeast(0))
        val moved = TranscriptImageBlocks.moveToCaret(projection, block.id, caret)
        dismissFloatingMenu()
        if (moved == projection) {
            toast("L’image est déjà à cet emplacement.")
            return
        }
        val movedBlock = moved.blocks.firstOrNull { it.id == block.id }
        val caretAfter = movedBlock?.let { target ->
            TranscriptImageCaret(target.rawOffset,
                moved.blocks.takeWhile { it.id != target.id }.count { it.rawOffset == target.rawOffset } + 1)
        }
        renderTranscriptProjection(moved, caret = caretAfter)
        persistDraft(moved.rawText())
        refreshNoteImages()
    }

    private fun removeNoteImage(image: NoteImage) {
        if (!isTranscriptEditable() || imageDeliveryBusy || hasImageCaptureInFlight()) return
        val note = notes.get(activeNoteId)
        val projection = currentTranscriptProjection()
        val block = projection.blocks.firstOrNull { candidate -> candidate.images.any { it.id == image.id } }
            ?: return
        val updated = TranscriptImageBlocks.removeImage(projection, block.id, image.id)
        dismissFloatingMenu()
        draftStore.forgetCaptures(setOf(image.id))
        if (note != null) notes.save(note.id, updated.serializedNoteText(), note.images.filterNot { it.id == image.id })
        renderTranscriptProjection(updated)
        persistDraft(updated.rawText())
        refreshNoteImages()
    }

    private fun exportNoteWithImages(automatic: Boolean = true) {
        if (!isTranscriptEditable() || hasImageCaptureInFlight()) return
        val run = activeRun
        if (run != null) {
            run.exportNote = true
            run.automaticNoteShare = automatic
            run.finishAfterPause = true
            if (state != State.PAUSING) stopRec()
        } else {
            val note = saveNoteWithCaptures(rawTranscriptText())
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
        val raw = TranscriptImageBlocks.rawText(text)
        draftStore.save(raw)
        val projection = projectionForText(raw)
        val attachedIds = noteImagesIn(projection).map { it.id }.toSet()
        draftStore.syncVisibleCaptures(attachedIds)
        val note = notes.save(activeNoteId, projection.serializedNoteText(), noteImagesIn(projection))
        draftStore.detachCaptures(draftStore.captures().mapNotNull { it.image?.id }
            .toSet() - acceptedBatchImageIds())
        activeNoteId = note.id
        draftStore.noteId = note.id
        draftStore.save(raw)
        recoveredDraft = raw
        // A location is requested once after an explicit archive. Autosaves of
        // this note never set the pending flag again, and an export can finish
        // before the list is shown without losing the note.
        if (notes.folders().isNotEmpty() && notes.needsInitialFolderChoice(note.id)) {
            pendingFolderChoiceNoteId = note.id
        }
        return note
    }

    private fun persistDraft(text: String) {
        val raw = TranscriptImageBlocks.rawText(text)
        draftStore.purpose = purpose
        draftStore.save(raw)
        val projection = projectionForText(raw)
        draftStore.syncProjection(projection.blocks)
        activeNoteId?.let { id ->
            val images = noteImagesIn(projection)
            notes.save(id, projection.serializedNoteText(), images)
            if (purpose == DictationPurpose.NOTE) {
                draftStore.detachCaptures(draftStore.captures().mapNotNull { it.image?.id }
                    .toSet() - acceptedBatchImageIds())
            }
        }
        if (recoveredDraft != null || activeRun == null) recoveredDraft = raw
    }

    private fun clearOpenDraft() {
        if (hasImageCaptureInFlight()) {
            toast("Terminez l’ajout des images en attente avant de fermer le brouillon.")
            return
        }
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
        if (hasImageCaptureInFlight()) {
            toast("Terminez l’ajout des images en attente avant de fermer le brouillon.")
            return
        }
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
        if (hasImageCaptureInFlight() || imageDeliveryBusy) { archiveAfterImageDelivery = true; return }
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
            saveNoteWithCaptures(rawTranscriptText())
            clearOpenDraft()
            showNotesOverlay()
        } else showNotesOverlay()
    }

    private fun openNote(note: TranscriptNote) {
        val modeState = transcriptionModes.snapshot()
        if (activeRun != null || modeState.activeRunMode != null || hasImageCaptureInFlight() || imageDeliveryBusy) return
        if (meetingReplacementOperation != null) return
        if (note.meeting != null || note.meetingRaw != null) {
            openMeetingNote(note)
            return
        }
        // A flat note must never evict a live structured document. The user can first finish or
        // explicitly detach that document from the Meeting panel.
        if (meetingSurfaceOpen && meetingRecordingController != null) {
            toast("Terminez ou enregistrez la réunion avant d’ouvrir une autre note.")
            return
        }
        invalidateNoteInsertion()
        releaseTranscriptFocus()
        resetVocabularyLearning()
        dismissFloatingMenu()
        purpose = DictationPurpose.NOTE
        draftStore.purpose = purpose
        activeNoteId = note.id
        draftStore.noteId = note.id
        val projection = TranscriptImageBlocks.combine(
            TranscriptImageBlocks.fromNote(note.text, note.images),
            draftStore.captures(),
        )
        recoveredDraft = projection.rawText()
        editableTranscript.clear()
        editableTranscript.edit(projection.rawText())
        renderTranscriptProjection(projection, projection.rawText().length, projection.rawText().length)
        liveText?.isEnabled = true
        liveText?.hint = "Écrivez ici, ou appuyez sur la pastille pour dicter"
        refreshNoteImages()
        persistDraft(projection.rawText())
        panelHidden = false
        setState(State.PAUSED)
        setLivePreviewVisible(true)
    }

    private fun openMeetingNote(note: TranscriptNote) {
        val latestModeState = transcriptionModes.snapshot()
        val editableDocument = editableMeetingDocument(note)
        if (activeRun != null || latestModeState.activeRunMode != null || hasImageCaptureInFlight() || imageDeliveryBusy) return
        if (meetingSurfaceOpen && meetingRecordingController?.state?.document?.sessionId != null) {
            val current = meetingRecordingController
            if (editableDocument?.sessionId == current?.state?.document?.sessionId) {
                panelHidden = false
                setLivePreviewVisible(true)
                return
            }
            if (current != null && (note.meeting != null || note.meetingRaw != null)) {
                startNewMeetingAfterSaving(current, note)
                return
            }
            toast("Terminez ou enregistrez la réunion avant d’ouvrir une autre note.")
            return
        }
        if (meetingDraftClaim != null || meetingDraftClaimRequest != null) {
            toast("Le brouillon de réunion est déjà ouvert.")
            return
        }

        invalidateNoteInsertion()
        dismissFloatingMenu()
        releaseTranscriptFocus()
        resetVocabularyLearning()

        val canSelectMeeting = when {
            latestModeState.mode == TranscriptionMode.MEETING -> true
            latestModeState.poisoned -> false
            else -> transcriptionModes.changeMode(TranscriptionMode.MEETING)
        }
        if (canSelectMeeting) applyTranscriptionMode(TranscriptionMode.MEETING)
        val afterSelection = transcriptionModes.snapshot()
        if (afterSelection.activeRunMode != null || activeRun != null) return
        val blockedOnlyByPoison = !canSelectMeeting && afterSelection.poisoned

        activeNoteId = note.id
        purpose = DictationPurpose.NOTE
        panelHidden = false
        setLivePreviewVisible(true)

        val document = editableDocument
        if (document == null) {
            meetingSurfaceOpen = true
            ensureMeetingPanelView(createEditor = false)
            showOpaqueMeetingNote(note, meetingNoteReadOnlyReason(note))
            return
        }
        if (!canSelectMeeting && !blockedOnlyByPoison) {
            meetingSurfaceOpen = true
            ensureMeetingPanelView(createEditor = false)
            showOpaqueMeetingNote(note, TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE)
            return
        }

        meetingSurfaceOpen = true
        ensureMeetingPanelView()
        val store = meetingModelStoreForPanel()
        attachMeetingModelListener(store)
        store.refresh()
        startMeetingAfterClaimLanguage = null
        claimMeetingDraft(note)
    }

    private fun editableMeetingDocument(note: TranscriptNote): MeetingDocument? =
        note.meeting?.takeIf { note.meetingRaw == null && note.id == it.sessionId }

    private fun meetingNoteReadOnlyReason(note: TranscriptNote): String? = when {
        note.meetingRaw != null -> null
        note.meeting != null && note.id != note.meeting.sessionId ->
            "L’identifiant de cette réunion ne correspond pas à sa session."
        else -> null
    }

    private fun showOpaqueMeetingNote(note: TranscriptNote, reason: String? = null) {
        val fallback = note.meeting?.takeIf { note.meetingRaw == null }?.let { document ->
            val imageNumbers = note.images.mapTo(mutableSetOf()) { it.number }
            MeetingProjection.text(document, imageNumbers)
        } ?: note.text
        val explanation = reason ?: when (com.kafkasl.phonewhisper.meeting.MeetingDocumentJson.decode(note.meetingRaw)) {
            is MeetingDocumentRead.Unsupported -> "Cette réunion utilise une version plus récente."
            is MeetingDocumentRead.Invalid -> "Le contenu structuré de cette réunion est invalide."
            else -> "Cette réunion n’est pas disponible en édition structurée."
        }
        ensureMeetingPanelView(createEditor = false)
        val body = livePanelBody ?: return
        showOpaqueText(body, "$explanation\nCette note reste en lecture seule.\n\n$fallback")
    }

    private fun showOpaqueText(body: FrameLayout, text: String) {
        removeOpaquePresentation()
        val message = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(overlayPalette.ink)
            setPadding(meetingDp(16), meetingDp(12), meetingDp(16), meetingDp(12))
            background = overlayCardBackground(overlayPalette.surface, overlayPalette.stroke, 16f)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setLineSpacing(meetingDp(3).toFloat(), 1f)
        }
        meetingOpaqueMessage = message
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(message, FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = meetingDp(48)
        })
        panelResizeHandles.values.forEach(View::bringToFront)
    }

    private fun removeOpaquePresentation() {
        val message = meetingOpaqueMessage ?: return
        val parent = message.parent as? ViewGroup ?: run {
            meetingOpaqueMessage = null
            return
        }
        if (parent is ScrollView) {
            (parent.parent as? ViewGroup)?.removeView(parent)
        } else {
            parent.removeView(message)
        }
        meetingOpaqueMessage = null
    }

    private fun insertPausedMessage() {
        if (purpose == DictationPurpose.NOTE) { requestNoteInsertion(); return }
        if (!isTranscriptEditable()) return
        if (hasImageCaptureInFlight()) { toast("Terminez l’ajout des images en attente."); return }
        if (imageDeliveryBusy) { messageAfterImageDelivery = true; return }
        tapCoordinator.reset()
        val text = rawTranscriptText()
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
        if (imageDeliveryBusy || hasImageCaptureInFlight()) { toast("Terminez l’ajout des images avant d’insérer le texte."); return }
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
        val note = saveNoteWithCaptures(rawTranscriptText())
        replaceNoteText(note.text)
        confirmNoteInsertion(note)
    }

    private fun confirmNoteInsertion(note: TranscriptNote) {
        if (purpose != DictationPurpose.NOTE || activeRun != null || state != State.PAUSED) return
        invalidateNoteInsertion()
        val rawNoteText = TranscriptImageBlocks.fromNote(note.text, note.images).rawText()
        val request = noteInsertionGate.request(note.id, rawNoteText)
            ?: run { toast("La note ne contient pas de texte à insérer."); return }
        releaseTranscriptFocus()
        var approved = false
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle("Insérer le texte ?")
            .setMessage("Le texte ci-dessous sera déposé dans le champ de l’application ouverte. Votre note restera enregistrée. Pour transmettre les images, utilisez l’export.\n\n$rawNoteText")
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
                    val text = noteInsertionGate.consume(request, activeNoteId, rawTranscriptText())
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
        formatMenuRows = emptyList()
        formatMenuFormats = emptyList()
        transcriptionModeRows = emptyList()
        formatMenuSelected = -1
        formatMenuParams = null
        formatMenuSwipeMode = false
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

    private fun showNotesOverlay(view: NotesView = NotesView.Root) {
        if (hasImageCaptureInFlight()) { toast("Terminez l’ajout des images en attente."); return }
        val actualView = when (view) {
            is NotesView.Folder -> if (notes.getFolder(view.id) == null) NotesView.Root else view
            else -> view
        }
        notesView = actualView
        val entries = mutableListOf<MenuEntry>()
        when (actualView) {
            NotesView.Root -> {
                entries += MenuEntry("＋ Nouvelle note", ::createNewNoteFromNotes)
                entries += MenuEntry("＋ Nouveau dossier", { createFolderDialog(NotesView.Root) })
                val folders = notes.folders()
                if (folders.isEmpty()) {
                    appendNoteEntries(entries, notes.all(), NotesView.Root)
                } else {
                    folders.forEach { folder ->
                        val count = notes.all(folder.id).size
                        val actions = { showFolderActions(folder) }
                        entries += MenuEntry(
                            folder.name,
                            { showNotesOverlay(NotesView.Folder(folder.id)) },
                            longClick = actions,
                            subtitle = if (count == 0) "Dossier vide" else "$count note${if (count > 1) "s" else ""}",
                            trailingAction = actions,
                        )
                    }
                    val unfiled = notes.all(null)
                    val actions = { showNotesOverlay(NotesView.Unfiled) }
                    entries += MenuEntry(
                        "Sans dossier", actions,
                        subtitle = if (unfiled.isEmpty()) "Aucune note" else "${unfiled.size} note${if (unfiled.size > 1) "s" else ""}",
                    )
                }
            }
            NotesView.Unfiled -> {
                entries += MenuEntry("← Mes dossiers", { showNotesOverlay(NotesView.Root) })
                entries += MenuEntry("＋ Nouvelle note", { createBlankNote(folderId = null, classify = true) })
                appendNoteEntries(entries, notes.all(null), NotesView.Unfiled)
            }
            is NotesView.Folder -> {
                val folder = notes.getFolder(actualView.id) ?: return showNotesOverlay(NotesView.Root)
                entries += MenuEntry("← Mes dossiers", { showNotesOverlay(NotesView.Root) })
                entries += MenuEntry("＋ Nouvelle note", { createBlankNote(folder.id, classify = true) })
                appendNoteEntries(entries, notes.all(folder.id), actualView)
            }
        }
        if (entries.size == 2 && notes.all().isEmpty() && actualView == NotesView.Root) {
            entries += MenuEntry("Aucune note enregistrée", {}, subtitle = "Les notes apparaîtront ici après Terminer.", enabled = false)
        }
        val title = when (actualView) {
            NotesView.Root -> "Mes notes"
            NotesView.Unfiled -> "Sans dossier"
            is NotesView.Folder -> notes.getFolder(actualView.id)?.name ?: "Mes notes"
        }
        showFloatingMenu(title, entries)
        maybePromptFolderChoice()
    }

    private fun appendNoteEntries(entries: MutableList<MenuEntry>, values: List<TranscriptNote>, returnView: NotesView) {
        if (values.isEmpty()) {
            entries += MenuEntry(
                "Aucune note dans ce dossier", {}, subtitle = "Les notes apparaîtront ici après Terminer.", enabled = false,
            )
            return
        }
        values.forEach { note ->
            val excerpt = note.text.replace(Regex("\\s+"), " ").take(140)
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(note.updatedAt))
            val metadata = "Modifiée le $date" + if (note.images.isEmpty()) "" else " · ${note.images.size} image${if (note.images.size > 1) "s" else ""}"
            val actions = { showNoteActions(note, returnView) }
            entries += MenuEntry(
                note.title, { openNote(note) }, actions,
                subtitle = excerpt.ifBlank { "Note vide · Touchez pour écrire" }, metadata = metadata,
                trailingAction = actions,
            )
        }
    }

    private fun showNoteActions(note: TranscriptNote, returnView: NotesView) {
        showFloatingMenu(note.title, listOf(
            MenuEntry("Partager / exporter · texte et images", { launchNoteExport(note) }),
            MenuEntry("Renommer", { renameNote(note, returnView) }),
            MenuEntry("Déplacer vers…", { chooseNoteFolder(note, returnView) }),
            MenuEntry("Supprimer", { notes.delete(note.id); showNotesOverlay(returnView) }),
            MenuEntry("Retour aux notes", { showNotesOverlay(returnView) }),
        ))
    }

    private fun showFolderActions(folder: NoteFolder) {
        showFloatingMenu(folder.name, listOf(
            MenuEntry("Renommer le dossier", { renameFolderDialog(folder) }),
            MenuEntry("Supprimer le dossier", { confirmDeleteFolder(folder) }),
            MenuEntry("Retour aux dossiers", { showNotesOverlay(NotesView.Root) }),
        ))
    }

    private fun renameNote(note: TranscriptNote, returnView: NotesView = notesView) {
        dismissFloatingMenu()
        val dialogContext = overlayDialogContext()
        val input = EditText(dialogContext).apply { setText(note.title); setSingleLine(); selectAll() }
        val dialog = AlertDialog.Builder(dialogContext).setTitle("Renommer la note").setView(input)
            .setPositiveButton("Enregistrer") { _, _ -> notes.rename(note.id, input.text.toString()); showNotesOverlay(returnView) }
            .setNegativeButton("Annuler") { _, _ -> showNotesOverlay(returnView) }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { formatDialog = null }
        dialog.show()
    }

    private fun createFolderDialog(returnView: NotesView) {
        dismissFloatingMenu()
        val dialogContext = overlayDialogContext()
        val input = EditText(dialogContext).apply {
            hint = "Nom du dossier"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(dialogContext).setTitle("Nouveau dossier").setView(input)
            .setPositiveButton("Créer") { _, _ ->
                val folder = notes.createFolder(input.text.toString())
                if (folder == null) toast("Nom vide ou dossier déjà existant.")
                showNotesOverlay(returnView)
            }
            .setNegativeButton("Annuler") { _, _ -> showNotesOverlay(returnView) }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { formatDialog = null }
        dialog.show()
    }

    private fun renameFolderDialog(folder: NoteFolder) {
        dismissFloatingMenu()
        val dialogContext = overlayDialogContext()
        val input = EditText(dialogContext).apply { setText(folder.name); setSingleLine(); selectAll() }
        val dialog = AlertDialog.Builder(dialogContext).setTitle("Renommer le dossier").setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                if (notes.renameFolder(folder.id, input.text.toString()) == null) toast("Nom vide ou dossier déjà existant.")
                showNotesOverlay(NotesView.Root)
            }
            .setNegativeButton("Annuler") { _, _ -> showNotesOverlay(NotesView.Root) }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { formatDialog = null }
        dialog.show()
    }

    private fun confirmDeleteFolder(folder: NoteFolder) {
        dismissFloatingMenu()
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle("Supprimer « ${folder.name} » ?")
            .setMessage("Les notes resteront conservées dans « Sans dossier ».")
            .setNegativeButton("Annuler") { _, _ -> showNotesOverlay(NotesView.Root) }
            .setPositiveButton("Supprimer") { _, _ -> notes.deleteFolder(folder.id); showNotesOverlay(NotesView.Root) }
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { formatDialog = null }
        dialog.show()
    }

    private fun createNewNoteFromNotes() {
        if (notes.folders().isEmpty()) createBlankNote(folderId = null, classify = false)
        else showFolderChoiceDialog(
            title = "Où ranger la nouvelle note ?",
            cancelLabel = "Sans dossier",
            onChoice = { folderId -> createBlankNote(folderId, classify = true) },
            onCancel = { createBlankNote(folderId = null, classify = true) },
        )
    }

    private fun createBlankNote(folderId: String?, classify: Boolean) {
        val note = notes.save(null, "", folderId = folderId)
        val classified = if (classify) notes.chooseFolder(note.id, folderId) ?: note else note
        openNote(classified)
    }

    private fun chooseNoteFolder(note: TranscriptNote, returnView: NotesView) {
        showFolderChoiceDialog(
            title = "Déplacer « ${note.title} »",
            cancelLabel = "Annuler",
            onChoice = { folderId ->
                notes.chooseFolder(note.id, folderId)
                showNotesOverlay(returnView)
            },
        )
    }

    private fun showFolderChoiceDialog(
        title: String,
        cancelLabel: String,
        onChoice: (String?) -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        val folders = notes.folders()
        val labels = listOf("Sans dossier") + folders.map { it.name } + "＋ Nouveau dossier"
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, index ->
                when {
                    index == 0 -> onChoice(null)
                    index <= folders.lastIndex + 1 -> onChoice(folders[index - 1].id)
                    else -> createFolderDialogForChoice(onChoice, onCancel)
                }
            }
            .setNegativeButton(cancelLabel) { _, _ -> onCancel?.invoke() }
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { if (formatDialog === dialog) formatDialog = null }
        dialog.show()
    }

    private fun createFolderDialogForChoice(onChoice: (String?) -> Unit, onCancel: (() -> Unit)? = null) {
        val dialogContext = overlayDialogContext()
        val input = EditText(dialogContext).apply { hint = "Nom du dossier"; setSingleLine() }
        val dialog = AlertDialog.Builder(dialogContext).setTitle("Nouveau dossier").setView(input)
            .setPositiveButton("Créer") { _, _ ->
                val folder = notes.createFolder(input.text.toString())
                if (folder == null) toast("Nom vide ou dossier déjà existant.") else onChoice(folder.id)
            }
            .setNegativeButton("Annuler") { _, _ -> onCancel?.invoke() }.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        formatDialog = dialog
        dialog.setOnDismissListener { if (formatDialog === dialog) formatDialog = null }
        dialog.show()
    }

    private fun maybePromptFolderChoice() {
        val noteId = pendingFolderChoiceNoteId ?: return
        if (!notes.needsInitialFolderChoice(noteId)) {
            pendingFolderChoiceNoteId = null
            return
        }
        val note = notes.get(noteId) ?: run { pendingFolderChoiceNoteId = null; return }
        showFolderChoiceDialog(
            title = "Où ranger « ${note.title} » ?",
            cancelLabel = "Plus tard",
            onChoice = { folderId ->
                notes.chooseFolder(note.id, folderId)
                pendingFolderChoiceNoteId = null
                showNotesOverlay(notesView)
            },
        )
    }

    private fun showFormatPicker(swipeMode: Boolean = false, touchable: Boolean = true) {
        val store = PostProcessingFormats(this)
        val formats = store.all()
        if (formats.isEmpty()) return
        dismissFloatingMenu()
        releaseTranscriptFocus()
        val dp = resources.displayMetrics.density
        val screen = screenRect()
        val selected = formats.indexOfFirst { it.id == store.selected().id }.coerceAtLeast(0)
        val width = minOf((320 * dp).toInt(), screen.width)
        val height = minOf(((formats.size * 64 + 112) * dp).toInt(), (screen.height * .65f).toInt())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(overlayPalette.surface)
                setStroke(dp.toInt().coerceAtLeast(1), overlayPalette.stroke)
            }
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (6 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "Mode de transcription   ×"
            textSize = 19f
            setTextColor(overlayPalette.ink)
            gravity = Gravity.CENTER
            runCatching { typeface = resources.getFont(R.font.caveat) }
            contentDescription = "Mode de transcription. Fermer le menu"
            setOnClickListener { dismissFloatingMenu() }
        }, LinearLayout.LayoutParams(-1, (50 * dp).toInt()))
        val selectedMode = transcriptionModes.snapshot().mode
        val modeRows = TranscriptionMode.entries.map { mode ->
            TextView(this).apply {
                text = mode.label()
                textSize = 16f
                gravity = Gravity.CENTER
                minHeight = (48 * dp).toInt()
                isFocusable = true
                isClickable = true
                tag = mode
                setOnClickListener { selectTranscriptionMode(mode) }
            }
        }
        val modeSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(modeRows[0], LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply {
                rightMargin = (4 * dp).toInt()
            })
            addView(modeRows[1], LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply {
                leftMargin = (4 * dp).toInt()
            })
        }
        root.addView(modeSelector, LinearLayout.LayoutParams(-1, (52 * dp).toInt()))
        transcriptionModeRows = modeRows
        applyTranscriptionMode(selectedMode)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val displayIndices = if (swipeMode) formats.indices.reversed() else formats.indices
        val rows = mutableListOf<TextView>()
        displayIndices.forEach { index ->
            val format = formats[index]
            val row = TextView(this).apply {
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                minHeight = (56 * dp).toInt()
                isFocusable = true
                isClickable = true
                tag = index
                contentDescription = format.name
                setOnClickListener { selectFormat(index) }
            }
            rows += row
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = (4 * dp).toInt()
                rightMargin = (4 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
            })
        }
        if (selectedMode == TranscriptionMode.DICTATION) {
            root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val meetingPanelAccess = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(this@OverlayService).apply {
                    text = "Réunion sélectionnée. Le micro ne démarre qu’après votre action."
                    textSize = 15f
                    setTextColor(overlayPalette.inkMuted)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(TextView(this@OverlayService).apply {
                    text = "Ouvrir la réunion"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    minHeight = (48 * dp).toInt()
                    isFocusable = true
                    isClickable = true
                    contentDescription = "Ouvrir le panneau de réunion"
                    background = overlayActionBackground(overlayPalette)
                    setTextColor(overlayPalette.green)
                    setOnClickListener { openMeetingPanel() }
                }, LinearLayout.LayoutParams(-1, (52 * dp).toInt()))
            }
            root.addView(meetingPanelAccess, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val bounds = FloatingMenuPlacement.bounds(
            pillRect(params ?: return), screen, width, height, (6 * dp).toInt(), above = true,
        )
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val layout = WindowManager.LayoutParams(
            bounds.width, bounds.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.x
            y = bounds.y
        }
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissFloatingMenu()
                true
            } else false
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, layout)
            floatingMenu = root
            formatMenuRows = rows
            formatMenuFormats = formats
            transcriptionModeRows = modeRows
            formatMenuSelected = selected
            formatMenuParams = layout
            formatMenuSwipeMode = swipeMode
            updateFormatMenuHighlight(selected, haptic = false)
            if (swipeMode) {
                root.post {
                    val row = rows.firstOrNull { it.tag == selected } ?: return@post
                    val scroll = row.parent?.parent as? ScrollView ?: return@post
                    scroll.smoothScrollTo(0, row.top.coerceAtLeast(0))
                }
            }
        } catch (_: Exception) {
            toast("Impossible d’afficher le menu flottant.")
        }
    }

    private fun selectFormat(index: Int) {
        if (transcriptionModes.snapshot().mode != TranscriptionMode.DICTATION) return
        val format = formatMenuFormats.getOrNull(index)
            ?: PostProcessingFormats(this).all().getOrNull(index)
            ?: return
        PostProcessingFormats(this).select(format)
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
    }

    private fun selectTranscriptionMode(mode: TranscriptionMode) {
        val before = transcriptionModes.snapshot()
        if (meetingReplacementOperation != null) {
            toast("Patientez pendant le transfert du brouillon de réunion.")
            return
        }
        if (before.mode == mode) return
        if (!transcriptionModes.changeMode(mode)) {
            val message = if (before.poisoned) TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE
            else "Terminez la session en cours avant de changer de mode."
            toast(message)
            return
        }
        dismissFloatingMenu()
        if (mode == TranscriptionMode.DICTATION && micArmed) {
            ensureLocalLoaded()
            warmLocalFormatter()
        }
    }

    private fun TranscriptionMode.label(): String = when (this) {
        TranscriptionMode.DICTATION -> "Dictée"
        TranscriptionMode.MEETING -> "Réunion"
    }

    private fun updateFormatMenuHighlight(index: Int, haptic: Boolean = true) {
        if (formatMenuRows.isEmpty()) return
        val bounded = index.coerceIn(0, (formatMenuFormats.size - 1).coerceAtLeast(0))
        val changed = formatMenuSelected != bounded
        formatMenuSelected = bounded
        formatMenuRows.forEach { row ->
            val selected = row.tag == bounded
            val format = formatMenuFormats.getOrNull(row.tag as? Int ?: -1)
            row.text = (if (selected) "✓ " else "") + (format?.name ?: "")
            row.setTextColor(if (selected) overlayPalette.green else overlayPalette.ink)
            row.background = if (selected) overlayActionBackground(overlayPalette)
            else overlayCardBackground(overlayPalette.raised, radiusDp = 18f)
            row.isSelected = selected
        }
        if (changed && haptic) {
            pill?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (formatMenuSwipeMode) {
                val row = formatMenuRows.firstOrNull { it.tag == bounded }
                if (row != null) row.post {
                    val scroll = row.parent?.parent as? ScrollView ?: return@post
                    val centered = row.top - (scroll.height - row.height) / 2
                    scroll.smoothScrollTo(0, centered.coerceAtLeast(0))
                }
            }
        }
    }

    private fun enableFormatMenuTouch() {
        val menu = floatingMenu ?: return
        val layout = formatMenuParams ?: return
        if (layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) return
        layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(menu, layout) }
    }

    private fun openApp() = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun openMeetingPanel() {
        dismissFloatingMenu()
        if (transcriptionModes.snapshot().mode != TranscriptionMode.MEETING) {
            toast("Choisissez d’abord le mode Réunion.")
            return
        }
        meetingSurfaceOpen = true
        ensureMeetingPanelView()
        panelHidden = false
        setLivePreviewVisible(true)
        if (meetingDraftClaim == null && meetingDraftClaimRequest == null) claimMeetingDraft()
        meetingModelStoreForPanel().let { store ->
            attachMeetingModelListener(store)
            store.refresh()
        }
    }

    private fun ensureMeetingPanelView(createEditor: Boolean = true) {
        val body = livePanelBody ?: return
        if (createEditor) removeOpaquePresentation()
        meetingPanelController?.let { controller ->
            if (createEditor) {
                controller.view.visibility = View.VISIBLE
                controller.view.bringToFront()
                panelMoveHandle?.bringToFront()
                panelResizeHandles.values.forEach(View::bringToFront)
            }
            return
        }
        if (meetingOpaqueMessage != null && !createEditor) return
        if (createEditor) removeOpaquePresentation()
        if (dictationPanelVisibilityBeforeMeeting.isEmpty()) {
            dictationPanelVisibilityBeforeMeeting = listOfNotNull(
                liveScroll,
                editorActionsRow,
                mediaToolbar,
                vocabularyBanner,
            ).map { it to it.visibility }
            dictationPanelTitleBeforeMeeting = panelTitle?.text
            dictationStateVisibilityBeforeMeeting = stateIndicator?.visibility ?: View.VISIBLE
        }
        liveScroll?.visibility = View.GONE
        editorActionsRow?.visibility = View.GONE
        mediaToolbar?.visibility = View.GONE
        vocabularyBanner?.visibility = View.GONE
        panelTitle?.apply {
            text = "Réunion"
            contentDescription = "Mode Réunion"
        }
        stateIndicator?.visibility = View.GONE
        livePanelBody?.findViewWithTag<View>("overlay-hide-panel")?.contentDescription = "Masquer le panneau de réunion sans arrêter l’écoute"

        if (!createEditor) return
        val controller = MeetingPanelController(
            context = this,
            dialogHost = meetingDialogHost(),
            actions = meetingPanelActions(),
            onAcquireWindow = ::acquireMeetingWindow,
            onReleaseWindow = ::releaseMeetingWindow,
        )
        meetingPanelController = controller
        body.addView(
            controller.view,
            FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = meetingDp(48)
            },
        )
        controller.view.bringToFront()
        panelMoveHandle?.bringToFront()
        panelResizeHandles.values.forEach(View::bringToFront)
        renderOpaqueMessage()
    }

    private fun restoreDictationPanelAfterMeeting() {
        cancelMeetingNotePublication()
        meetingPanelController?.let { controller ->
            (controller.view.parent as? ViewGroup)?.removeView(controller.view)
            controller.dispose()
        }
        meetingPanelController = null
        removeOpaquePresentation()
        dictationPanelVisibilityBeforeMeeting.forEach { (view, visibility) -> view.visibility = visibility }
        dictationPanelVisibilityBeforeMeeting = emptyList()
        dictationPanelTitleBeforeMeeting?.let { panelTitle?.text = it }
        dictationPanelTitleBeforeMeeting = null
        stateIndicator?.visibility = dictationStateVisibilityBeforeMeeting
        livePanelBody?.findViewWithTag<View>("overlay-hide-panel")?.contentDescription = "Masquer le panneau sans arrêter la dictée"
        meetingSurfaceOpen = false
    }

    private fun claimMeetingDraft(restoredNote: TranscriptNote? = null) {
        val owner = meetingTestOverrides?.draftOwnership ?: MeetingDraftOwnership.processWide
        val file = meetingTestOverrides?.draftFile ?: File(filesDir, "meeting/meeting-draft.json")
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        meetingDraftFile = file
        val generation = ++meetingOpenGeneration
        val request = owner.claim(file)
        meetingDraftClaimRequest = request
        request.future.whenComplete { claim, failure ->
            main.post {
                if (generation != meetingOpenGeneration || !meetingSurfaceOpen || meetingDraftClaimRequest !== request) {
                    if (claim != null) runCatching { claim.relinquishLatest() }
                    return@post
                }
                meetingDraftClaimRequest = null
                if (failure != null || claim == null) {
                    showMeetingClaimError()
                    return@post
                }
                meetingDraftClaim = claim
                when (val recovered = claim.snapshot?.let { MeetingDocumentRead.Ready(it) } ?: claim.recoveredDocument) {
                    MeetingDocumentRead.Absent -> createMeetingController(claim, restoredNote?.meeting)
                    is MeetingDocumentRead.Ready -> {
                        val requested = restoredNote?.meeting
                        if (requested == null || requested.sessionId == recovered.document.sessionId) {
                            // A matching durable draft is newer than the note projection and
                            // remains the source of truth after an interrupted session.
                            createMeetingController(claim, recovered.document)
                        } else {
                            meetingDraftClaim = null
                            claim.relinquishLatest().whenComplete { _, _ ->
                                main.post {
                                    if (generation == meetingOpenGeneration && meetingSurfaceOpen) {
                                        showMeetingClaimError()
                                    }
                                }
                            }
                        }
                    }
                    is MeetingDocumentRead.Unsupported,
                    is MeetingDocumentRead.Invalid -> {
                        if (restoredNote == null) showOpaqueMeetingDraft(claim)
                        else {
                            meetingDraftClaim = null
                            claim.relinquishLatest().whenComplete { _, _ ->
                                main.post {
                                    if (generation == meetingOpenGeneration && meetingSurfaceOpen) {
                                        showOpaqueMeetingNote(restoredNote)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createMeetingController(claim: MeetingDraftOwnershipClaim, restored: MeetingDocument? = null) {
        if (!meetingSurfaceOpen || meetingPanelController == null) return
        val ports = meetingRecordingPorts()
        lateinit var created: MeetingRecordingController
        var callbackAttached = false
        val onStateChanged: (MeetingRecordingState) -> Unit = { next ->
            val update = Runnable {
                if (callbackAttached && meetingRecordingController === created && meetingSurfaceOpen) {
                    meetingControllerState = next
                    renderMeetingState(next)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) update.run() else main.post(update)
        }
        created = if (restored == null) {
            MeetingRecordingController.createNew(
                sessionId = UUID.randomUUID().toString(),
                runId = UUID.randomUUID().toString(),
                draftClaim = claim,
                ports = ports,
                onStateChanged = onStateChanged,
            )
        } else {
            MeetingRecordingController.restored(
                document = restored,
                draftClaim = claim,
                ports = ports,
                onStateChanged = onStateChanged,
            )
        }
        meetingRecordingController = created
        meetingPanelController?.view?.recyclerView?.visibility = View.VISIBLE
        callbackAttached = true
        meetingDocumentRestored = restored != null
        meetingControllerState = created.state
        renderMeetingState(created.state)
        if (restored == null) {
            startMeetingAfterClaimLanguage?.let { language ->
                startMeetingAfterClaimLanguage = null
                created.start(language)
            }
        }
    }

    private fun meetingRecordingPorts(): MeetingRecordingPorts {
        val overrides = meetingTestOverrides
        val modelStore = meetingModelStoreForPanel()
        val availability = overrides?.modelAvailability ?: MeetingModelAvailabilityPort {
            when (modelStore.currentState) {
                is MeetingModelStoreState.Ready -> MeetingModelAvailability.READY
                is MeetingModelStoreState.Downloading,
                MeetingModelStoreState.Checking -> MeetingModelAvailability.DOWNLOADING
                MeetingModelStoreState.Missing,
                is MeetingModelStoreState.Error -> MeetingModelAvailability.MISSING
            }
        }
        val reservation = overrides?.reservation ?: MeetingNativeAdmission(
            coordinator = transcriptionModes,
            closeResidentDictation = ::closeResidentDictationForMeeting,
            reserveFormatter = if (BuildConfig.LOCAL_FORMAT_PROTOTYPE) {
                { LocalFormatEngine.reserveForMeeting(applicationContext) }
            } else null,
        )
        val sessions = overrides?.sessionFactory ?: object : MeetingSessionFactoryPort {
            override fun start(
                runId: String,
                language: String,
                onReady: () -> Unit,
                onUpdate: (MeetingHypothesis) -> Unit,
                onFailure: (String) -> Unit,
            ): com.kafkasl.phonewhisper.meeting.MeetingSession {
                val paths = (modelStore.currentState as? MeetingModelStoreState.Ready)?.paths
                    ?: throw IllegalStateException("Modèles Réunion indisponibles")
                return MeetingEngine(paths.asrPath, paths.diarizationPath)
                    .start(runId, language, onReady, onUpdate, onFailure)
            }
        }
        val microphone = overrides?.microphoneFactory ?: MeetingAudioRecord(readinessCheck = {
            !localEngineLifecycle.isDestroyed() && micArmed &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        })
        return MeetingRecordingPorts(
            modelAvailability = availability,
            reservation = reservation,
            sessionFactory = sessions,
            microphoneFactory = microphone,
            notePublisher = object : MeetingNotePublisherPort {
                override fun save(document: MeetingDocument): CompletableFuture<Unit> = try {
                    notes.saveMeeting(document.sessionId, document, notes.get(document.sessionId)?.images)
                    CompletableFuture.completedFuture(Unit)
                } catch (_: Throwable) {
                    CompletableFuture<Unit>().also {
                        it.completeExceptionally(IllegalStateException("La note de réunion n’a pas pu être enregistrée."))
                    }
                }

                override fun hasAttachments(sessionId: String): Boolean =
                    notes.get(sessionId)?.images?.isNotEmpty() == true

                override fun hasSavedNote(sessionId: String): Boolean =
                    notes.get(sessionId)?.meeting?.sessionId == sessionId
            },
            mainDispatcher = MeetingMainDispatcher { task -> main.post(task) },
            focusedEdit = com.kafkasl.phonewhisper.meeting.MeetingFocusedEditPort {
                meetingPanelController?.flushFocusedEdit() ?: false
            },
        )
    }

    private fun closeResidentDictationForMeeting(): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        thread(name = "dictai-close-resident-for-meeting") {
            try {
                check(activeRun == null) { "Terminez la dictée avant de changer de mode." }
                residentAsrEngine.close()
                asrEngine = null
                loadedModelName = null
                completion.complete(Unit)
            } catch (_: Throwable) {
                transcriptionModes.reportUncertainClose()
                completion.completeExceptionally(IllegalStateException(TRANSCRIPTION_MODE_UNAVAILABLE_MESSAGE))
            }
        }
        return completion
    }

    private fun meetingModelStoreForPanel(): MeetingModelStore = meetingModelStore ?: run {
        val store = MeetingModelStore.shared(applicationContext)
        meetingModelStore = store
        store
    }

    private fun attachMeetingModelListener(store: MeetingModelStore) {
        if (meetingModelListener != null) return
        val listener: (MeetingModelStoreState) -> Unit = { _ ->
            main.post {
                if (meetingSurfaceOpen && meetingModelStore === store) {
                    renderMeetingState(
                        state = meetingRecordingController?.state ?: meetingControllerState,
                        modelState = store.currentState,
                    )
                }
            }
        }
        meetingModelListener = listener
        store.addListener(listener)
    }

    private fun meetingDialogHost() = object : MeetingPanelDialogHost {
        override fun showChoices(request: MeetingPanelChoicesRequest, onChoice: (String?) -> Unit) {
            var completed = false
            var selectedId: String? = null
            fun completeAfterDismiss() {
                if (completed) return
                completed = true
                onChoice(selectedId)
            }
            lateinit var dialog: AlertDialog
            dialog = AlertDialog.Builder(overlayDialogContext())
                .setTitle(request.title)
                .setItems(request.choices.map { it.label }.toTypedArray()) { _, index ->
                    selectedId = request.choices.getOrNull(index)?.id
                    // Deliver only from the dismiss callback, after the tracked-dialog guard has
                    // been cleared. Commands such as FINISH may synchronously open another dialog.
                    dialog.dismiss()
                }
                .create()
            if (!showMeetingOverlayDialog(dialog, onDismiss = ::completeAfterDismiss)) completeAfterDismiss()
        }

        override fun showTextInput(request: MeetingPanelTextInputRequest, onSubmit: (String?) -> Unit) {
            val input = EditText(overlayDialogContext()).apply {
                setText(request.initialText)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSelection(text.length)
            }
            var completed = false
            fun complete(value: String?) {
                if (completed) return
                completed = true
                onSubmit(value)
            }
            val dialog = AlertDialog.Builder(overlayDialogContext())
                .setTitle(request.title)
                .setView(input)
                .setPositiveButton("Valider") { _, _ -> complete(input.text?.toString()) }
                .setNegativeButton("Annuler") { _, _ -> complete(null) }
                .setOnCancelListener { complete(null) }
                .create()
            if (!showMeetingOverlayDialog(dialog)) complete(null)
        }
    }

    private fun showMeetingOverlayDialog(dialog: AlertDialog): Boolean = showMeetingOverlayDialog(dialog, null)

    private fun showMeetingOverlayDialog(dialog: AlertDialog, onDismiss: (() -> Unit)?): Boolean {
        if (localEngineLifecycle.isDestroyed()) return false
        return try {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            meetingDialogs += dialog
            meetingDialogOpen = true
            dialog.setOnDismissListener {
                meetingDialogs -= dialog
                meetingDialogOpen = meetingDialogs.isNotEmpty()
                onDismiss?.invoke()
            }
            dialog.show()
            true
        } catch (failure: RuntimeException) {
            dialog.setOnDismissListener(null)
            dialog.setOnCancelListener(null)
            meetingDialogs -= dialog
            meetingDialogOpen = meetingDialogs.isNotEmpty()
            when (failure) {
                is android.view.WindowManager.BadTokenException,
                is SecurityException,
                is WindowManager.InvalidDisplayException -> false
                else -> throw failure
            }
        }
    }

    private fun meetingPanelActions() = MeetingPanelActions(
        edit = { turnId, text ->
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                meetingRecordingController?.editTurn(turnId, text)
        },
        assign = { turnId, participantId ->
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                meetingRecordingController?.assignTurn(turnId, participantId)
        },
        rename = { participantId, name ->
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                meetingRecordingController?.renameParticipant(participantId, name)
        },
        setIgnored = { participantId, ignored ->
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                meetingRecordingController?.setParticipantIgnored(participantId, ignored)
        },
        download = {
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                meetingModelStoreForPanel().download()
        },
        retrySave = {
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null) {
                val controller = meetingRecordingController
                val document = controller?.state?.document
                val documentActionError = document?.let(::meetingDocumentActionErrorFor)
                val imageMutationError = document?.let(::meetingImageMutationErrorFor)
                if (document != null && documentActionError != null) {
                    retryMeetingDocumentAction(documentActionError)
                } else if (imageMutationError != null) {
                    retryMeetingImageMutation(imageMutationError)
                } else if (controller != null && meetingNotePublicationErrorFor(controller.state.document) != null) {
                    saveOpenMeetingNote(controller)
                } else {
                    controller?.retrySave()
                }
            }
        },
        sessionCommand = { command ->
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                handleMeetingPanelCommand(command)
        },
        imageAction = { anchor, image ->
            if (meetingReplacementOperation == null && meetingImageMutationOperation == null)
                showMeetingImageActions(image, anchor)
        },
        reviewPassages = {
            if (meetingImageMutationOperation == null) reviewMeetingPassages()
        },
        documentAction = ::handleMeetingDocumentAction,
    )

    private fun handleMeetingDocumentAction(
        action: MeetingPanelDocumentAction,
        requestedAnchor: MeetingPanelAnchor?,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { handleMeetingDocumentAction(action, requestedAnchor) }
            return
        }
        if (action !in setOf(
                MeetingPanelDocumentAction.COPY,
                MeetingPanelDocumentAction.EXPORT,
                MeetingPanelDocumentAction.PHOTO,
                MeetingPanelDocumentAction.SCREENSHOT,
            )
        ) return
        if (action == MeetingPanelDocumentAction.SCREENSHOT && WhisperAccessibilityService.connected == null) {
            toast("Activez le service d’accessibilité DictAI pour capturer l’écran.")
            return
        }
        val controller = meetingRecordingController ?: return
        val panel = meetingPanelController ?: return
        val initial = controller.state
        val modeSnapshot = transcriptionModes.snapshot()
        val poisonedDocumentOnly = action in setOf(
            MeetingPanelDocumentAction.COPY,
            MeetingPanelDocumentAction.EXPORT,
        ) && modeSnapshot.mode == TranscriptionMode.DICTATION &&
            modeSnapshot.poisoned && modeSnapshot.activeRunMode == null &&
            meetingDocumentRestored && initial.phase == MeetingRecordingPhase.DOCUMENT
        val modeAllowsDocumentAction = modeSnapshot.mode == TranscriptionMode.MEETING &&
            !modeSnapshot.poisoned || poisonedDocumentOnly
        if (!meetingSurfaceOpen || meetingReplacementOperation != null ||
            meetingDocumentActionOperation != null || meetingImageMutationOperation != null ||
            hasImageCaptureInFlight() || imageDeliveryBusy ||
            !isStableMeetingDocumentActionPhase(initial.phase) || !modeAllowsDocumentAction
        ) return
        panel.flushFocusedEdit()
        val selectedPanelAnchor = requestedAnchor ?: panel.captureAnchor()
        val isImageCapture = action == MeetingPanelDocumentAction.PHOTO ||
            action == MeetingPanelDocumentAction.SCREENSHOT
        if (isImageCapture) panel.endEditing()
        var document = controller.state.document
        val imageAnchor = if (isImageCapture) {
            val captured = selectedPanelAnchor?.let { selected ->
                val turn = document.turns.firstOrNull { it.id == selected.turnId }
                val body = turn?.let { it.editedText ?: it.recognizedText }
                if (turn != null && body != null && selected.serializedOffset in 0..body.length) {
                    MeetingImageAnchor(document.sessionId, turn.id, selected.serializedOffset)
                } else null
            }
            captured ?: run {
                val documentTurn = document.turns.firstOrNull { it.utteranceId == 0L }
                    ?: controller.ensureDocumentTurn()
                document = controller.state.document
                MeetingImageAnchor.capture(document, documentTurn.id, 0)
            }
        } else null
        if (isImageCapture && imageAnchor != null) {
            val target = document.turns.firstOrNull { it.id == imageAnchor.turnId } ?: return
            // Freeze the exact body that the serialized offset refers to before a live run is
            // paused; later ASR revisions must not move that anchor to different text.
            controller.editTurn(target.id, target.editedText ?: target.recognizedText)
            document = controller.state.document
        }
        val operation = MeetingDocumentActionOperation(
            generation = ++meetingDocumentActionGeneration,
            action = action,
            sessionId = document.sessionId,
            runId = document.runId,
            controller = controller,
            panel = panel,
            initialPhase = initial.phase,
            mode = modeSnapshot.mode,
            modeGeneration = modeSnapshot.generation,
            poisonedDocumentOnly = poisonedDocumentOnly,
            anchor = imageAnchor,
        )
        meetingDocumentActionOperation = operation
        if (isImageCapture && initial.phase == MeetingRecordingPhase.LISTENING) {
            controller.pause().whenComplete { _, failure ->
                main.post {
                    if (meetingDocumentActionOperation !== operation) return@post
                    if (failure != null) {
                        failMeetingDocumentAction(operation, "Impossible de mettre la réunion en pause.")
                    } else if (!ownsMeetingDocumentAction(operation)) {
                        abandonMeetingDocumentAction(operation)
                    } else {
                        flushMeetingDocumentAction(operation)
                    }
                }
            }
            return
        }
        flushMeetingDocumentAction(operation)
    }

    private fun flushMeetingDocumentAction(operation: MeetingDocumentActionOperation) {
        if (!ownsMeetingDocumentAction(operation)) {
            abandonMeetingDocumentAction(operation)
            return
        }
        val flush = try {
            operation.controller.flushDraft()
        } catch (_: Throwable) {
            failMeetingDocumentAction(operation, "Échec de sauvegarde")
            return
        }
        // flushDraft synchronously submits this exact immutable state before returning its barrier.
        val snapshot = operation.controller.state.document
        if (snapshot.sessionId != operation.sessionId || snapshot.runId != operation.runId) {
            abandonMeetingDocumentAction(operation)
            return
        }
        flush.whenComplete { _, failure ->
            main.post {
                if (!ownsMeetingDocumentAction(operation)) {
                    abandonMeetingDocumentAction(operation)
                } else if (failure != null) {
                    failMeetingDocumentAction(operation, "Échec de sauvegarde")
                } else {
                    performMeetingDocumentAction(operation, snapshot)
                }
            }
        }
    }

    private fun performMeetingDocumentAction(operation: MeetingDocumentActionOperation, snapshot: MeetingDocument) {
        if (!ownsMeetingDocumentAction(operation)) {
            abandonMeetingDocumentAction(operation)
            return
        }
        val images = try {
            notes.get(operation.sessionId)?.images.orEmpty()
        } catch (_: Throwable) {
            failMeetingDocumentAction(operation, "Échec de sauvegarde")
            return
        }
        val imageNumbers = images.mapTo(mutableSetOf()) { it.number }
        val projectedText = MeetingProjection.text(snapshot, imageNumbers)
        when (operation.action) {
            MeetingPanelDocumentAction.COPY -> {
                if (projectedText.isBlank()) {
                    completeMeetingDocumentAction(operation)
                    toast("Aucune transcription à copier.")
                    return
                }
                val copied = DictationClipboard.copy(this, projectedText)
                completeMeetingDocumentAction(operation)
                if (copied) toast("Réunion copiée.") else toast("Impossible de copier la réunion.")
            }
            MeetingPanelDocumentAction.EXPORT -> {
                val existingMeeting = try {
                    notes.get(operation.sessionId)?.meeting?.sessionId == operation.sessionId
                } catch (_: Throwable) {
                    failMeetingDocumentAction(operation, "Échec de sauvegarde")
                    return
                }
                if (projectedText.isBlank() && images.isEmpty()) {
                    if (!existingMeeting) {
                        completeMeetingDocumentAction(operation)
                        toast("Aucune transcription à exporter.")
                        return
                    }
                    try {
                        notes.saveMeeting(operation.sessionId, snapshot, images)
                    } catch (_: Throwable) {
                        failMeetingDocumentAction(operation, "Échec de sauvegarde")
                        return
                    }
                    completeMeetingDocumentAction(operation)
                    toast("Réunion vide enregistrée.")
                    return
                }
                val note = try {
                    notes.saveMeeting(operation.sessionId, snapshot, images)
                } catch (_: Throwable) {
                    failMeetingDocumentAction(operation, "Échec de sauvegarde")
                    return
                }
                completeMeetingDocumentAction(operation)
                launchNoteExport(note)
            }
            MeetingPanelDocumentAction.PHOTO -> {
                val anchor = operation.anchor
                if (anchor == null) {
                    failMeetingDocumentAction(operation, "Impossible de préparer l’emplacement de la photo.")
                    return
                }
                if (images.size >= NoteImage.MAX_IMAGES) {
                    failMeetingDocumentAction(operation, "La réunion contient déjà dix images.")
                    return
                }
                try {
                    notes.saveMeeting(operation.sessionId, snapshot, images)
                } catch (_: Throwable) {
                    failMeetingDocumentAction(operation, "Échec de sauvegarde")
                    return
                }
                if (!ownsMeetingDocumentAction(operation)) {
                    abandonMeetingDocumentAction(operation)
                    return
                }
                val pending = try {
                    imageStore.beginMeetingBatch(
                        anchor = anchor,
                        kind = NoteImageKind.CAMERA,
                        resume = operation.initialPhase == MeetingRecordingPhase.LISTENING,
                        number = (images.maxOfOrNull { it.number } ?: 0) + 1,
                    )
                } catch (_: Throwable) {
                    failMeetingDocumentAction(operation, "Impossible de préparer la photo.")
                    return
                }
                rememberMeetingImageResumeContext(pending, operation)
                try {
                    captureWindowsHidden = true
                    container?.visibility = View.VISIBLE
                    setLivePreviewVisible(false)
                    startActivity(
                        Intent(this, NoteCameraActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("captureId", pending.id),
                    )
                    completeMeetingDocumentAction(operation)
                } catch (_: Throwable) {
                    clearMeetingImageResumeContext(pending.id)
                    restoreCaptureWindows()
                    runCatching { imageStore.cancelBatch(pending.id) }
                    clearPendingSafely(pending.id)
                    failMeetingDocumentAction(operation, "Impossible d’ouvrir l’appareil photo.")
                }
            }
            MeetingPanelDocumentAction.SCREENSHOT -> {
                val anchor = operation.anchor
                if (anchor == null) {
                    failMeetingDocumentAction(operation, "Impossible de préparer l’emplacement de la capture.")
                    return
                }
                if (images.size >= NoteImage.MAX_IMAGES) {
                    failMeetingDocumentAction(operation, "La réunion contient déjà dix images.")
                    return
                }
                try {
                    notes.saveMeeting(operation.sessionId, snapshot, images)
                } catch (_: Throwable) {
                    failMeetingDocumentAction(operation, "Échec de sauvegarde")
                    return
                }
                if (!ownsMeetingDocumentAction(operation)) {
                    abandonMeetingDocumentAction(operation)
                    return
                }
                val pending = try {
                    imageStore.beginMeetingBatch(
                        anchor = anchor,
                        kind = NoteImageKind.SCREENSHOT,
                        resume = operation.initialPhase == MeetingRecordingPhase.LISTENING,
                        number = (images.maxOfOrNull { it.number } ?: 0) + 1,
                    )
                } catch (_: Throwable) {
                    failMeetingDocumentAction(operation, "Impossible de préparer la série de captures.")
                    return
                }
                rememberMeetingImageResumeContext(pending, operation)
                screenshotBatchId = pending.id
                completeMeetingDocumentAction(operation)
                captureWindowsHidden = true
                container?.visibility = View.GONE
                setLivePreviewVisible(false)
                showScreenshotBatchBar(pending)
            }
        }
    }

    private fun ownsMeetingDocumentAction(operation: MeetingDocumentActionOperation): Boolean {
        val modeSnapshot = transcriptionModes.snapshot()
        val sameModeContext = modeSnapshot.mode == operation.mode &&
            modeSnapshot.generation == operation.modeGeneration && when {
                operation.poisonedDocumentOnly -> modeSnapshot.poisoned &&
                    modeSnapshot.activeRunMode == null && meetingDocumentRestored &&
                    operation.action in setOf(MeetingPanelDocumentAction.COPY, MeetingPanelDocumentAction.EXPORT)
                else -> !modeSnapshot.poisoned && modeSnapshot.mode == TranscriptionMode.MEETING
            }
        return meetingDocumentActionOperation === operation &&
            meetingDocumentActionGeneration == operation.generation &&
            !localEngineLifecycle.isDestroyed() && meetingSurfaceOpen &&
            meetingPanelController === operation.panel && meetingRecordingController === operation.controller &&
            meetingReplacementOperation == null &&
            sameModeContext &&
            operation.controller.state.let { state ->
                isStableMeetingDocumentActionPhase(state.phase) &&
                    state.document.let { it.sessionId == operation.sessionId && it.runId == operation.runId }
            }
    }

    private fun isStableMeetingDocumentActionPhase(phase: MeetingRecordingPhase): Boolean =
        phase !in setOf(
            MeetingRecordingPhase.PREPARING,
            MeetingRecordingPhase.PAUSING,
            MeetingRecordingPhase.FINALIZING,
            MeetingRecordingPhase.CLOSING,
        )

    private fun completeMeetingDocumentAction(operation: MeetingDocumentActionOperation) {
        if (meetingDocumentActionOperation !== operation) return
        meetingDocumentActionOperation = null
        meetingDocumentActionError = null
        if (meetingRecordingController === operation.controller) renderMeetingState(operation.controller.state)
    }

    private fun failMeetingDocumentAction(operation: MeetingDocumentActionOperation, message: String) {
        if (meetingDocumentActionOperation !== operation) return
        meetingDocumentActionOperation = null
        meetingDocumentActionError = MeetingDocumentActionError(
            operation.sessionId,
            operation.runId,
            operation.action,
            operation.anchor,
            pendingId = null,
            message = message,
        )
        if (meetingRecordingController === operation.controller) renderMeetingState(operation.controller.state)
        toast(message)
    }

    private fun abandonMeetingDocumentAction(operation: MeetingDocumentActionOperation) {
        if (meetingDocumentActionOperation === operation) meetingDocumentActionOperation = null
    }

    private fun meetingDocumentActionErrorFor(document: MeetingDocument): MeetingDocumentActionError? =
        meetingDocumentActionError?.takeIf { it.sessionId == document.sessionId && it.runId == document.runId }

    private fun retryMeetingDocumentAction(error: MeetingDocumentActionError) {
        if (meetingDocumentActionOperation != null || meetingReplacementOperation != null) return
        meetingDocumentActionError = null
        handleMeetingDocumentAction(error.action, error.anchor?.let { MeetingPanelAnchor(it.turnId, it.offsetUtf16) })
    }

    private fun reviewMeetingPassages() {
        if (meetingReplacementOperation != null) return
        val document = meetingRecordingController?.state?.document ?: return
        val passages = document.turns
            .filter { it.utteranceId > 0L && !it.attributionStable }
            .mapNotNull { turn ->
                val recognized = turn.recognizedText.takeIf(String::isNotBlank) ?: "(Aucun texte reconnu)"
                val preserved = when (val edited = turn.editedText) {
                    null -> turn.recognizedText.takeIf(String::isNotBlank) ?: "(Aucun texte reconnu)"
                    "" -> "(Texte supprimé)"
                    else -> edited
                }
                "Texte reconnu : $recognized\nTexte conservé : $preserved"
            }
        val message = passages.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            ?: "Aucun passage signalé pour le moment."
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle("Passages à vérifier")
            .setMessage(message)
            .setPositiveButton("Fermer", null)
            .create()
        showMeetingOverlayDialog(dialog)
    }

    private fun meetingGestureForRelease(
        dx: Float,
        dy: Float,
        isTap: Boolean,
    ): MeetingPillInteraction.Gesture? {
        if (isTap) return MeetingPillInteraction.Gesture.TAP
        val threshold = maxOf(meetingDp(56), 3 * android.view.ViewConfiguration.get(this).scaledTouchSlop)
        return when {
            dy <= -threshold && -dy > kotlin.math.abs(dx) -> MeetingPillInteraction.Gesture.SWIPE_UP
            dy >= threshold && dy > kotlin.math.abs(dx) -> MeetingPillInteraction.Gesture.SWIPE_DOWN
            else -> null
        }
    }

    private fun meetingPillPhase(): MeetingPillInteraction.Phase {
        if (meetingReplacementOperation != null) return MeetingPillInteraction.Phase.CLOSING
        val state = meetingRecordingController?.state ?: meetingControllerState
        if (state == null) {
            return when (meetingModelStoreForPanel().currentState) {
                is MeetingModelStoreState.Ready -> MeetingPillInteraction.Phase.READY
                is MeetingModelStoreState.Downloading,
                MeetingModelStoreState.Checking -> MeetingPillInteraction.Phase.PREPARING
                MeetingModelStoreState.Missing,
                is MeetingModelStoreState.Error -> MeetingPillInteraction.Phase.MODEL_UNAVAILABLE
            }
        }
        return when (state.phase) {
            MeetingRecordingPhase.MODEL_UNAVAILABLE,
            MeetingRecordingPhase.DOCUMENT -> if (meetingDocumentRestored) {
                MeetingPillInteraction.Phase.RESTORED
            } else when (meetingModelStoreForPanel().currentState) {
                is MeetingModelStoreState.Ready -> MeetingPillInteraction.Phase.READY
                is MeetingModelStoreState.Downloading,
                MeetingModelStoreState.Checking -> MeetingPillInteraction.Phase.PREPARING
                MeetingModelStoreState.Missing,
                is MeetingModelStoreState.Error -> MeetingPillInteraction.Phase.MODEL_UNAVAILABLE
            }
            MeetingRecordingPhase.PREPARING -> MeetingPillInteraction.Phase.PREPARING
            MeetingRecordingPhase.LISTENING -> MeetingPillInteraction.Phase.LISTENING
            MeetingRecordingPhase.PAUSING -> MeetingPillInteraction.Phase.PAUSING
            MeetingRecordingPhase.PAUSED -> MeetingPillInteraction.Phase.PAUSED
            MeetingRecordingPhase.FINALIZING -> MeetingPillInteraction.Phase.FINALIZING
            MeetingRecordingPhase.CLOSING -> MeetingPillInteraction.Phase.CLOSING
            MeetingRecordingPhase.FINISHED -> MeetingPillInteraction.Phase.FINISHED
            MeetingRecordingPhase.ERROR -> MeetingPillInteraction.Phase.ERROR
        }
    }

    private fun meetingPillHasDocumentContent(controller: MeetingRecordingController?): Boolean {
        controller ?: return false
        val document = controller.state.document
        val note = try {
            notes.get(document.sessionId)
        } catch (_: Throwable) {
            // Keep the save/open action reachable; its writer will report a recoverable failure.
            return true
        }
        return note?.meeting?.sessionId == document.sessionId ||
            hasMeetingContent(document, note?.images.orEmpty())
    }

    private fun dispatchMeetingPillGesture(gesture: MeetingPillInteraction.Gesture) {
        val snapshot = transcriptionModes.snapshot()
        if (snapshot.mode != TranscriptionMode.MEETING || meetingReplacementOperation != null) return
        tapCoordinator.reset()
        val controller = meetingRecordingController
        val intent = MeetingPillInteraction.resolve(
            phase = meetingPillPhase(),
            gesture = gesture,
            context = MeetingPillInteraction.Context(
                hasDocumentContent = meetingPillHasDocumentContent(controller),
                dialogOpen = meetingDialogOpen || meetingFinishPromptPending,
            ),
        )
        when (intent) {
            MeetingPillInteraction.Intent.NONE -> Unit
            MeetingPillInteraction.Intent.START_NEW -> {
                if (controller != null) {
                    handleMeetingPanelCommand(MeetingPanelSessionCommand.START)
                } else {
                    startMeetingAfterClaimLanguage = prefs.dictationLanguage.nemotronLanguage
                    openMeetingPanel()
                }
            }
            MeetingPillInteraction.Intent.PAUSE -> controller?.pause()
            MeetingPillInteraction.Intent.RESUME -> controller?.resume()
            MeetingPillInteraction.Intent.PROMPT_FINISH -> controller?.let(::confirmMeetingFinish)
            MeetingPillInteraction.Intent.SAVE_OPEN_NOTE -> controller?.let(::saveOpenMeetingNote)
            MeetingPillInteraction.Intent.SHOW_PAUSE_HINT -> toast("Mettre en pause pour enregistrer")
            MeetingPillInteraction.Intent.OPEN_MODE_MENU -> showFormatPicker(swipeMode = false, touchable = true)
            MeetingPillInteraction.Intent.SHOW_MEETING_PANEL -> openMeetingPanel()
        }
    }

    private fun saveOpenMeetingNote(controller: MeetingRecordingController) {
        if (meetingReplacementOperation != null || meetingNotePublicationOperation != null ||
            meetingRecordingController !== controller
        ) return
        val initial = controller.state
        if (initial.phase !in setOf(MeetingRecordingPhase.FINISHED, MeetingRecordingPhase.DOCUMENT)) return
        meetingPanelController?.flushFocusedEdit()
        val operation = MeetingNotePublicationOperation(
            generation = ++meetingNotePublicationGeneration,
            sessionId = initial.document.sessionId,
            runId = initial.document.runId,
            controller = controller,
        )
        meetingNotePublicationOperation = operation
        flushMeetingNoteBeforePublication(operation, attempt = 0)
    }

    private fun flushMeetingNoteBeforePublication(
        operation: MeetingNotePublicationOperation,
        attempt: Int,
    ) {
        if (!ownsMeetingNotePublication(operation)) {
            abandonMeetingNotePublication(operation)
            return
        }
        val controller = operation.controller
        if (controller.state.phase !in setOf(MeetingRecordingPhase.FINISHED, MeetingRecordingPhase.DOCUMENT)) {
            abandonMeetingNotePublication(operation)
            return
        }
        meetingPanelController?.flushFocusedEdit()
        val flush = try {
            controller.flushDraft()
        } catch (_: Throwable) {
            failMeetingNotePublication(operation)
            return
        }
        // flushDraft publishes its focused editor value to the writer synchronously before
        // returning. This immutable document is the exact generation the returned future fences.
        val barrierSnapshot = controller.state.document
        if (barrierSnapshot.sessionId != operation.sessionId || barrierSnapshot.runId != operation.runId) {
            abandonMeetingNotePublication(operation)
            return
        }
        flush.whenComplete { _, failure ->
            main.post {
                if (!ownsMeetingNotePublication(operation)) {
                    abandonMeetingNotePublication(operation)
                    return@post
                }
                if (failure != null) {
                    failMeetingNotePublication(operation)
                    return@post
                }
                val latest = controller.state.document
                if (latest != barrierSnapshot) {
                    if (attempt + 1 < MEETING_NOTE_PUBLICATION_MAX_FLUSH_ATTEMPTS) {
                        flushMeetingNoteBeforePublication(operation, attempt + 1)
                    } else {
                        failMeetingNotePublication(operation, "Des modifications sont encore en cours.")
                    }
                    return@post
                }
                publishOpenMeetingSnapshot(operation, barrierSnapshot)
            }
        }
    }

    private fun ownsMeetingNotePublication(operation: MeetingNotePublicationOperation): Boolean =
        meetingNotePublicationOperation === operation &&
        meetingNotePublicationGeneration == operation.generation &&
            meetingSurfaceOpen && meetingRecordingController === operation.controller &&
            isSameMeetingRun(operation.controller, operation.sessionId, operation.runId) &&
            transcriptionModes.snapshot().let {
                it.mode == TranscriptionMode.MEETING && it.activeRunMode == null
            }

    private fun publishOpenMeetingSnapshot(
        operation: MeetingNotePublicationOperation,
        document: MeetingDocument,
    ) {
        if (!ownsMeetingNotePublication(operation) ||
            operation.controller.state.phase !in setOf(MeetingRecordingPhase.FINISHED, MeetingRecordingPhase.DOCUMENT)
        ) {
            abandonMeetingNotePublication(operation)
            return
        }
        try {
            val previous = notes.get(operation.sessionId)
            if (previous == null && !hasMeetingContent(document, emptyList())) {
                completeMeetingNotePublication(operation)
                return
            }
            notes.saveMeeting(operation.sessionId, document, previous?.images.orEmpty())
            completeMeetingNotePublication(operation)
            toast("Réunion enregistrée.")
        } catch (_: Throwable) {
            failMeetingNotePublication(operation)
        }
    }

    private fun completeMeetingNotePublication(operation: MeetingNotePublicationOperation) {
        if (meetingNotePublicationOperation !== operation) return
        meetingNotePublicationOperation = null
        if (meetingNotePublicationError?.sessionId == operation.sessionId) meetingNotePublicationError = null
        if (meetingRecordingController === operation.controller) renderMeetingState(operation.controller.state)
    }

    private fun failMeetingNotePublication(
        operation: MeetingNotePublicationOperation,
        detail: String? = null,
    ) {
        if (!ownsMeetingNotePublication(operation)) {
            abandonMeetingNotePublication(operation)
            return
        }
        meetingNotePublicationOperation = null
        meetingNotePublicationError = MeetingNotePublicationError(
            operation.sessionId,
            detail ?: "Échec de sauvegarde",
        )
        renderMeetingState(operation.controller.state)
        toast("Échec de sauvegarde. Réessayez depuis le panneau de réunion.")
    }

    private fun meetingNotePublicationErrorFor(document: MeetingDocument): String? =
        meetingNotePublicationError?.takeIf { it.sessionId == document.sessionId }?.message

    private fun cancelMeetingNotePublication() {
        meetingNotePublicationGeneration += 1
        meetingNotePublicationOperation = null
    }

    private fun abandonMeetingNotePublication(operation: MeetingNotePublicationOperation) {
        if (meetingNotePublicationOperation !== operation) return
        meetingNotePublicationGeneration += 1
        meetingNotePublicationOperation = null
    }

    private fun handleMeetingPanelCommand(command: MeetingPanelSessionCommand) {
        if (meetingReplacementOperation != null) return
        val controller = meetingRecordingController ?: return
        when (command) {
            MeetingPanelSessionCommand.START -> {
                if (!meetingSurfaceOpen || meetingRecordingController !== controller) return
                if (transcriptionModes.snapshot().activeRunMode != null) return
                when (controller.state.phase) {
                    MeetingRecordingPhase.FINISHED -> startNewMeetingAfterSaving(controller)
                    MeetingRecordingPhase.DOCUMENT,
                    MeetingRecordingPhase.MODEL_UNAVAILABLE,
                    MeetingRecordingPhase.ERROR -> {
                        if (meetingDocumentRestored) startNewMeetingAfterSaving(controller)
                        else controller.start(prefs.dictationLanguage.nemotronLanguage)
                    }
                    MeetingRecordingPhase.PREPARING,
                    MeetingRecordingPhase.LISTENING,
                    MeetingRecordingPhase.PAUSING,
                    MeetingRecordingPhase.PAUSED,
                    MeetingRecordingPhase.FINALIZING,
                    MeetingRecordingPhase.CLOSING -> Unit
                }
            }
            MeetingPanelSessionCommand.PAUSE -> controller.pause()
            MeetingPanelSessionCommand.RESUME -> controller.resume()
            MeetingPanelSessionCommand.FINISH -> confirmMeetingFinish(controller)
            MeetingPanelSessionCommand.CANCEL -> controller.cancel()
            MeetingPanelSessionCommand.CANCEL_DOWNLOAD -> meetingModelStoreForPanel().cancelDownload()
        }
    }

    private fun confirmMeetingFinish(controller: MeetingRecordingController) {
        if (meetingDialogOpen || meetingFinishPromptPending) return
        val initial = controller.state
        if (initial.phase != MeetingRecordingPhase.LISTENING && initial.phase != MeetingRecordingPhase.PAUSED) return
        val sessionId = initial.document.sessionId
        val runId = initial.document.runId
        meetingFinishPromptPending = true
        controller.flushDraft().whenComplete { _, failure ->
            main.post {
                meetingFinishPromptPending = false
                if (failure != null) {
                    toast("Échec de sauvegarde. Réessayez avant de terminer la réunion.")
                    return@post
                }
                if (!isSameMeetingRun(controller, sessionId, runId) ||
                    controller.state.phase !in setOf(MeetingRecordingPhase.LISTENING, MeetingRecordingPhase.PAUSED)
                ) return@post

                val dialog = AlertDialog.Builder(overlayDialogContext())
                    .setTitle("Enregistrer la transcription ?")
                    .setMessage("La transcription sera enregistrée dans une note.")
                    .setNegativeButton("Continuer la réunion") { _, _ -> }
                    .setPositiveButton("Enregistrer et terminer") { _, _ ->
                        if (isSameMeetingRun(controller, sessionId, runId) &&
                            controller.state.phase in setOf(MeetingRecordingPhase.LISTENING, MeetingRecordingPhase.PAUSED)
                        ) controller.finish()
                    }
                    .create()
                showMeetingOverlayDialog(dialog)
            }
        }
    }

    private fun isSameMeetingRun(
        controller: MeetingRecordingController,
        sessionId: String,
        runId: String,
    ): Boolean = meetingSurfaceOpen && meetingRecordingController === controller &&
        controller.state.document.sessionId == sessionId && controller.state.document.runId == runId

    private fun isRecordableMeetingController(controller: MeetingRecordingController): Boolean =
        controller.state.phase == MeetingRecordingPhase.DOCUMENT ||
            controller.state.phase == MeetingRecordingPhase.MODEL_UNAVAILABLE ||
            controller.state.phase == MeetingRecordingPhase.ERROR

    private fun startNewMeetingAfterSaving(
        previous: MeetingRecordingController,
        nextNote: TranscriptNote? = null,
        dictationSelectionGeneration: Long? = null,
    ) {
        if (!meetingSurfaceOpen || meetingRecordingController !== previous || meetingReplacementOperation != null) return
        if (hasImageCaptureInFlight() || imageDeliveryBusy) {
            toast("Terminez l’ajout des images en attente avant de commencer une nouvelle réunion.")
            return
        }
        val initial = previous.state
        val modeState = transcriptionModes.snapshot()
        val canReplace = initial.phase == MeetingRecordingPhase.FINISHED || initial.phase in setOf(
            MeetingRecordingPhase.DOCUMENT,
            MeetingRecordingPhase.MODEL_UNAVAILABLE,
            MeetingRecordingPhase.ERROR,
        )
        val modeAllowsTransfer = modeState.mode == TranscriptionMode.MEETING ||
            (dictationSelectionGeneration != null && modeState.mode == TranscriptionMode.DICTATION &&
                modeState.generation == dictationSelectionGeneration)
        if (!canReplace || !modeAllowsTransfer || modeState.activeRunMode != null) return
        val claim = meetingDraftClaim ?: return
        val path = meetingDraftFile ?: return
        val panel = meetingPanelController ?: return
        val document = initial.document
        val operation = MeetingReplacementOperation(
            generation = ++meetingReplacementGeneration,
            modeGeneration = modeState.generation,
            sessionId = document.sessionId,
            runId = document.runId,
            controller = previous,
            panel = panel,
            claim = claim,
            path = path,
            nextNote = nextNote,
            destinationChanged = dictationSelectionGeneration != null,
            dictationSelectionGeneration = dictationSelectionGeneration,
        )
        cancelMeetingNotePublication()
        panel.flushFocusedEdit()
        panel.endEditing()
        meetingReplacementOperation = operation
        panel.view.recyclerView.visibility = View.GONE
        renderMeetingState(previous.state)

        fun recover(message: String, reclaimReleasedOwnership: Boolean = false) {
            if (meetingReplacementOperation !== operation) return
            meetingReplacementOperation = null
            meetingModeTransferInProgress = false
            if (!meetingSurfaceOpen) return
            operation.dictationSelectionGeneration?.let(::rollbackDictationModeSelection)
            if (reclaimReleasedOwnership) {
                meetingRecordingController = null
                meetingDraftClaim = null
                meetingControllerState = null
                meetingDocumentRestored = true
                claimMeetingDraft()
            } else {
                panel.view.recyclerView.visibility = View.VISIBLE
                if (meetingRecordingController === previous) renderMeetingState(previous.state)
            }
            applyTranscriptionMode(transcriptionModes.snapshot().mode)
            toast(message)
        }

        fun ownsOperation(): Boolean {
            if (meetingReplacementOperation !== operation ||
                meetingReplacementGeneration != operation.generation ||
                !meetingSurfaceOpen || meetingRecordingController !== previous ||
                previous.state.document.sessionId != operation.sessionId ||
                previous.state.document.runId != operation.runId
            ) return false
            val current = transcriptionModes.snapshot()
            if (current.generation != operation.modeGeneration) recordMeetingDestinationChange(operation, current)
            return current.activeRunMode == null
        }

        previous.flushDraft().whenComplete { _, flushFailure ->
            main.post {
                if (!ownsOperation()) return@post
                if (flushFailure != null) {
                    recover("Échec de sauvegarde. Réessayez avant de commencer une nouvelle réunion.")
                    return@post
                }
                if (hasImageCaptureInFlight() || imageDeliveryBusy) {
                    recover("Terminez l’ajout des images en attente avant de commencer une nouvelle réunion.")
                    return@post
                }
                val latestDocument = previous.state.document
                if (latestDocument.sessionId != operation.sessionId || latestDocument.runId != operation.runId) {
                    recover("La réunion a changé. Rouvrez-la avant de continuer.")
                    return@post
                }
                try {
                    val existingNote = notes.get(operation.sessionId)
                    val images = existingNote?.images.orEmpty()
                    val existingMeeting = existingNote?.meeting?.sessionId == operation.sessionId
                    operation.savedMeetingNote = if (existingMeeting || hasMeetingContent(latestDocument, images)) {
                        notes.saveMeeting(operation.sessionId, latestDocument, images)
                    } else existingNote
                } catch (_: Throwable) {
                    recover("Échec de sauvegarde. Réessayez avant de commencer une nouvelle réunion.")
                    return@post
                }
                val afterSaveMode = transcriptionModes.snapshot()
                if (operation.destinationChanged && afterSaveMode.mode == TranscriptionMode.MEETING) {
                    meetingReplacementOperation = null
                    meetingReplacementGeneration += 1
                    meetingModeTransferInProgress = false
                    panel.view.recyclerView.visibility = View.VISIBLE
                    if (meetingRecordingController === previous) renderMeetingState(previous.state)
                    applyTranscriptionMode(afterSaveMode.mode)
                    return@post
                }
                previous.destroy().whenComplete { _, closeFailure ->
                    main.post {
                        if (!ownsOperation()) return@post
                        if (closeFailure != null) {
                            recover("Impossible de fermer le brouillon de réunion.", reclaimReleasedOwnership = true)
                            return@post
                        }
                        claim.relinquish(previous.state.document).whenComplete { _, relinquishFailure ->
                            main.post {
                                if (!ownsOperation()) return@post
                                if (relinquishFailure != null) {
                                    recover("Impossible de libérer le brouillon de réunion.", reclaimReleasedOwnership = true)
                                    return@post
                                }
                                val owner = meetingTestOverrides?.draftOwnership ?: MeetingDraftOwnership.processWide
                                owner.clear(path, operation.sessionId).whenComplete { _, clearFailure ->
                                    main.post {
                                        if (!ownsOperation()) return@post
                                        if (clearFailure != null) {
                                            recover("Impossible d’effacer le brouillon de la réunion précédente.", reclaimReleasedOwnership = true)
                                            return@post
                                        }
                                        if (operation.destinationChanged) {
                                            finishMeetingModeTransfer(
                                                operation.savedMeetingNote ?: notes.get(operation.sessionId),
                                            )
                                            return@post
                                        }
                                        meetingReplacementOperation = null
                                        if (!meetingSurfaceOpen) return@post
                                        if (meetingRecordingController !== previous) return@post
                                        meetingRecordingController = null
                                        meetingDraftClaim = null
                                        meetingControllerState = null
                                        val targetNote = operation.nextNote
                                        if (targetNote != null) {
                                            activeNoteId = targetNote.id
                                            purpose = DictationPurpose.NOTE
                                            meetingDocumentRestored = editableMeetingDocument(targetNote) != null
                                            startMeetingAfterClaimLanguage = null
                                            if (editableMeetingDocument(targetNote) != null) {
                                                claimMeetingDraft(targetNote)
                                            } else {
                                                showOpaqueMeetingNote(targetNote, meetingNoteReadOnlyReason(targetNote))
                                            }
                                        } else {
                                            activeNoteId = null
                                            meetingDocumentRestored = false
                                            startMeetingAfterClaimLanguage = prefs.dictationLanguage.nemotronLanguage
                                            claimMeetingDraft()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasMeetingContent(document: MeetingDocument, images: List<NoteImage>): Boolean =
        images.isNotEmpty() || document.turns.any { turn ->
            (turn.editedText ?: turn.recognizedText).isNotBlank()
        }

    private fun renderMeetingState(
        state: MeetingRecordingState?,
        modelState: MeetingModelStoreState = meetingModelStoreForPanel().currentState,
    ) {
        val panel = meetingPanelController ?: return
        if (state == null) return
        val status = when (state.phase) {
            MeetingRecordingPhase.PREPARING -> MeetingPanelStatus(MeetingPanelStatus.Phase.LOADING)
            MeetingRecordingPhase.LISTENING -> MeetingPanelStatus(MeetingPanelStatus.Phase.LISTENING)
            MeetingRecordingPhase.PAUSING -> MeetingPanelStatus(MeetingPanelStatus.Phase.PAUSING)
            MeetingRecordingPhase.PAUSED -> MeetingPanelStatus(MeetingPanelStatus.Phase.PAUSED)
            MeetingRecordingPhase.FINALIZING -> MeetingPanelStatus(MeetingPanelStatus.Phase.FINALIZING)
            MeetingRecordingPhase.CLOSING -> MeetingPanelStatus(MeetingPanelStatus.Phase.CLOSING)
            MeetingRecordingPhase.FINISHED -> MeetingPanelStatus(
                phase = MeetingPanelStatus.Phase.FINISHED,
                detail = state.recordingError,
                saveError = state.saveError,
            )
            MeetingRecordingPhase.ERROR -> MeetingPanelStatus(
                phase = MeetingPanelStatus.Phase.ERROR,
                detail = state.recordingError,
                saveError = state.saveError,
            )
            MeetingRecordingPhase.MODEL_UNAVAILABLE,
            MeetingRecordingPhase.DOCUMENT -> when (modelState) {
                is MeetingModelStoreState.Ready -> MeetingPanelStatus(MeetingPanelStatus.Phase.READY, saveError = state.saveError)
                is MeetingModelStoreState.Downloading -> MeetingPanelStatus(
                    phase = MeetingPanelStatus.Phase.DOWNLOADING,
                    progressPercent = if (modelState.totalBytes > 0L) {
                        (modelState.bytesDownloaded * 100L / modelState.totalBytes).toInt().coerceIn(0, 100)
                    } else 0,
                    modelSize = meetingModelSizeLabel(),
                    saveError = state.saveError,
                )
                MeetingModelStoreState.Checking -> MeetingPanelStatus(MeetingPanelStatus.Phase.LOADING, saveError = state.saveError)
                MeetingModelStoreState.Missing,
                is MeetingModelStoreState.Error -> MeetingPanelStatus(
                    phase = MeetingPanelStatus.Phase.MODEL_UNAVAILABLE,
                    detail = (modelState as? MeetingModelStoreState.Error)?.message,
                    modelSize = meetingModelSizeLabel(),
                    saveError = state.saveError,
                )
            }
        }
        val availabilityOverride = meetingTestOverrides?.modelAvailability?.currentAvailability()
        val derivesFromReadiness = state.phase == MeetingRecordingPhase.DOCUMENT ||
            state.phase == MeetingRecordingPhase.MODEL_UNAVAILABLE
        val effectiveStatus = if (derivesFromReadiness) {
            when (availabilityOverride) {
                MeetingModelAvailability.READY -> status.copy(
                    phase = MeetingPanelStatus.Phase.READY,
                    detail = null,
                    progressPercent = null,
                    modelSize = null,
                )
                MeetingModelAvailability.MISSING -> status.copy(
                    phase = MeetingPanelStatus.Phase.MODEL_UNAVAILABLE,
                    modelSize = meetingModelSizeLabel(),
                )
                MeetingModelAvailability.DOWNLOADING -> status.copy(phase = MeetingPanelStatus.Phase.DOWNLOADING)
                null -> status
            }.copy(saveError = state.saveError)
        } else {
            status.copy(saveError = state.saveError)
        }
        val displayedStatus = if (meetingReplacementOperation?.controller === meetingRecordingController) {
            effectiveStatus.copy(phase = MeetingPanelStatus.Phase.CLOSING)
        } else effectiveStatus
        val finalStatus = displayedStatus.copy(
            saveError = meetingDocumentActionErrorFor(state.document)?.message
                ?: meetingImageMutationErrorFor(state.document)?.message
                ?: meetingNotePublicationErrorFor(state.document)
                ?: displayedStatus.saveError,
        )
        panel.render(state.document, notes.get(state.document.sessionId)?.images.orEmpty(), finalStatus)
        if (meetingReplacementOperation?.controller === meetingRecordingController) {
            panel.view.recyclerView.visibility = View.GONE
        }
        wave?.setMeetingCaptureActive(state.captureActive)
        updateNotif()
    }

    private fun meetingModelSizeLabel(): String =
        "environ ${(MeetingModelCatalog.production.totalBytes + 999_999L) / 1_000_000L} Mo"

    private fun meetingDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showOpaqueMeetingDraft(claim: MeetingDraftOwnershipClaim) {
        val rawFallback = activeNoteId?.let(notes::get)?.text.orEmpty()
        val text = when (val read = claim.recoveredDocument) {
            is MeetingDocumentRead.Unsupported -> "Cette réunion utilise une version plus récente et reste en lecture seule.\n\n$rawFallback"
            is MeetingDocumentRead.Invalid -> "Le brouillon de réunion est illisible et reste protégé.\n\n$rawFallback"
            else -> return
        }
        val body = livePanelBody ?: return
        showOpaqueText(body, text)
    }

    private fun renderOpaqueMessage() {
        meetingOpaqueMessage?.let { message ->
            message.setTextColor(overlayPalette.ink)
            message.background = overlayCardBackground(overlayPalette.surface, overlayPalette.stroke, 16f)
        }
    }

    private fun showMeetingClaimError() {
        val body = livePanelBody ?: return
        val message = TextView(this).apply {
            text = "Impossible de lire le brouillon de réunion. Masquez puis rouvrez le panneau pour réessayer."
            setTextColor(overlayPalette.ink)
            setPadding(meetingDp(16), meetingDp(12), meetingDp(16), meetingDp(12))
        }
        meetingOpaqueMessage = message
        body.addView(message, FrameLayout.LayoutParams(-1, -1).apply { topMargin = meetingDp(48) })
    }

    private fun acquireMeetingWindow() {
        val panel = livePanel ?: return
        val layout = liveParams ?: return
        layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        if (livePanelAdded) runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, layout)
        }
    }

    private fun releaseMeetingWindow() {
        val panel = livePanel ?: return
        val layout = liveParams ?: return
        runCatching { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(panel.windowToken, 0) }
        layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (livePanelAdded) runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(panel, layout)
        }
    }

    private fun destroyMeetingResources() {
        meetingOpenGeneration += 1
        meetingReplacementGeneration += 1
        meetingImageRecoveryRequest?.cancel()
        meetingImageRecoveryRequest = null
        cancelMeetingNotePublication()
        meetingNotePublicationError = null
        meetingReplacementOperation = null
        meetingDialogs.toList().forEach { dialog ->
            dialog.setOnCancelListener(null)
            dialog.setOnDismissListener(null)
            runCatching { dialog.dismiss() }
        }
        meetingDialogs.clear()
        meetingDialogOpen = false
        meetingFinishPromptPending = false
        startMeetingAfterClaimLanguage = null
        meetingDraftClaimRequest?.cancel()
        meetingDraftClaimRequest = null
        meetingModelListener?.let { listener -> meetingModelStore?.removeListener(listener) }
        meetingModelListener = null
        meetingPanelController?.let { controller ->
            (controller.view.parent as? ViewGroup)?.removeView(controller.view)
            controller.dispose()
        }
        meetingPanelController = null
        meetingOpaqueMessage?.let { (it.parent as? ViewGroup)?.removeView(it) }
        meetingOpaqueMessage = null
        val hadController = meetingRecordingController != null
        meetingRecordingController?.destroy()
        meetingRecordingController = null
        if (!hadController) {
            meetingDraftClaim?.let { claim ->
                runCatching { claim.relinquishLatest() }
            }
        }
        meetingDraftClaim = null
        meetingControllerState = null
        meetingSurfaceOpen = false
    }

    private fun showMeetingImageActions(image: NoteImage, capturedAnchor: MeetingPanelAnchor?) {
        val controller = meetingRecordingController ?: return
        val panel = meetingPanelController ?: return
        val state = controller.state
        val mode = transcriptionModes.snapshot()
        val poisonedDocumentOnly = mode.mode == TranscriptionMode.DICTATION && mode.poisoned &&
            mode.activeRunMode == null && meetingDocumentRestored && state.phase == MeetingRecordingPhase.DOCUMENT
        if (!meetingSurfaceOpen || meetingReplacementOperation != null || meetingDocumentActionOperation != null ||
            meetingImageMutationOperation != null || hasImageCaptureInFlight() || imageDeliveryBusy ||
            !isStableMeetingDocumentActionPhase(state.phase) ||
            (mode.mode != TranscriptionMode.MEETING || mode.poisoned) && !poisonedDocumentOnly
        ) return
        val note = runCatching { notes.get(state.document.sessionId) }.getOrNull() ?: return
        if (note.meeting?.sessionId != state.document.sessionId || note.images.none { it.id == image.id }) return
        val context = MeetingImageMenuContext(
            sessionId = state.document.sessionId,
            runId = state.document.runId,
            controller = controller,
            panel = panel,
            mode = mode.mode,
            modeGeneration = mode.generation,
            poisonedDocumentOnly = poisonedDocumentOnly,
            anchor = capturedAnchor?.let { selected ->
                val row = MeetingProjection.rows(state.document, note.images.mapTo(mutableSetOf()) { it.number })
                    .firstOrNull { it.turnId == selected.turnId && it.editableSpeech }
                val turn = state.document.turns.firstOrNull { it.id == selected.turnId }
                val body = turn?.let { it.editedText ?: it.recognizedText }
                if (row != null && body != null && selected.serializedOffset in 0..body.length) {
                    MeetingImageAnchor(state.document.sessionId, turn.id, selected.serializedOffset)
                } else null
            },
        )
        val choices = buildList {
            add("Ouvrir")
            if (context.anchor != null) add("Déplacer au curseur")
            add("Déplacer après un passage…")
            add("Retirer cette image")
            add("Annuler")
        }
        var selected: String? = null
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle("Image ${image.number} · ${image.kind.label}")
            .setItems(choices.toTypedArray()) { _, index -> selected = choices.getOrNull(index) }
            .create()
        showMeetingOverlayDialog(dialog) {
            val choice = selected ?: return@showMeetingOverlayDialog
            if (!ownsMeetingImageMenuContext(context, image.id)) return@showMeetingOverlayDialog
            when (choice) {
                "Ouvrir" -> previewNoteImage(image)
                "Déplacer au curseur" -> context.anchor?.let { anchor ->
                    moveMeetingImage(context, image.id, anchor.turnId, anchor.offsetUtf16)
                }
                "Déplacer après un passage…" -> showMeetingImageDestinations(context, image)
                "Retirer cette image" -> removeMeetingImage(context, image.id)
            }
        }
    }

    private fun ownsMeetingImageMenuContext(
        context: MeetingImageMenuContext,
        imageId: String,
        allowMissingImage: Boolean = false,
    ): Boolean {
        if (!meetingSurfaceOpen || localEngineLifecycle.isDestroyed() ||
            meetingPanelController !== context.panel || meetingRecordingController !== context.controller ||
            meetingReplacementOperation != null || meetingDocumentActionOperation != null ||
            meetingImageMutationOperation != null || hasImageCaptureInFlight() || imageDeliveryBusy
        ) return false
        val state = context.controller.state
        if (state.document.sessionId != context.sessionId || state.document.runId != context.runId ||
            !isStableMeetingDocumentActionPhase(state.phase)
        ) return false
        val mode = transcriptionModes.snapshot()
        val modeStillOwned = mode.mode == context.mode && mode.generation == context.modeGeneration && when {
            context.poisonedDocumentOnly -> mode.poisoned && mode.activeRunMode == null &&
                meetingDocumentRestored && state.phase == MeetingRecordingPhase.DOCUMENT
            else -> mode.mode == TranscriptionMode.MEETING && !mode.poisoned
        }
        if (!modeStillOwned) return false
        return runCatching {
            notes.get(context.sessionId)?.let { note ->
                note.meeting?.sessionId == context.sessionId &&
                    (allowMissingImage || note.images.any { it.id == imageId })
            } == true
        }.getOrDefault(false)
    }

    private fun ownsMeetingImageMutation(operation: MeetingImageMutationOperation): Boolean {
        val context = operation.context
        if (meetingImageMutationOperation !== operation ||
            meetingImageMutationGeneration != operation.generation ||
            !meetingSurfaceOpen || localEngineLifecycle.isDestroyed() ||
            meetingPanelController !== context.panel || meetingRecordingController !== context.controller ||
            meetingReplacementOperation != null || meetingDocumentActionOperation != null ||
            hasImageCaptureInFlight() || imageDeliveryBusy
        ) return false
        val state = context.controller.state
        if (state.document.sessionId != context.sessionId || state.document.runId != context.runId ||
            !isStableMeetingDocumentActionPhase(state.phase)
        ) return false
        val mode = transcriptionModes.snapshot()
        return mode.mode == context.mode && mode.generation == context.modeGeneration && when {
            context.poisonedDocumentOnly -> mode.poisoned && mode.activeRunMode == null &&
                meetingDocumentRestored && state.phase == MeetingRecordingPhase.DOCUMENT
            else -> mode.mode == TranscriptionMode.MEETING && !mode.poisoned
        }
    }

    private fun abandonMeetingImageMutation(operation: MeetingImageMutationOperation) {
        if (meetingImageMutationOperation !== operation) return
        meetingImageMutationOperation = null
        val context = operation.context
        val mode = transcriptionModes.snapshot()
        if (meetingSurfaceOpen && meetingPanelController === context.panel &&
            meetingRecordingController === context.controller && mode.mode == context.mode &&
            mode.generation == context.modeGeneration
        ) {
            context.panel.view.recyclerView.visibility = operation.previousRecyclerVisibility
        }
    }

    private fun showMeetingImageDestinations(context: MeetingImageMenuContext, image: NoteImage) {
        if (!ownsMeetingImageMenuContext(context, image.id)) return
        val document = context.controller.state.document
        val images = runCatching { notes.get(context.sessionId)?.images.orEmpty() }.getOrDefault(emptyList())
        val destinations = MeetingProjection.rows(document, images.mapTo(mutableSetOf()) { it.number })
            .filter { row ->
                row.editableSpeech && document.turns.firstOrNull { it.id == row.turnId }?.let { turn ->
                    val body = turn.editedText ?: turn.recognizedText
                    images.fold(body) { value, known -> value.replace(known.marker, "") }.isNotBlank()
                } == true
            }
        if (destinations.isEmpty()) {
            toast("Aucun passage visible où déplacer l’image.")
            return
        }
        val labels = destinations.map { row ->
            val turn = document.turns.first { it.id == row.turnId }
            val body = images.fold(turn.editedText ?: turn.recognizedText) { value, known ->
                value.replace(known.marker, "")
            }.trim().replace('\n', ' ')
            "${row.label} · ${body.take(48)}"
        }
        var selectedIndex: Int? = null
        val dialog = AlertDialog.Builder(overlayDialogContext())
            .setTitle("Déplacer après un passage")
            .setItems(labels.toTypedArray()) { _, index -> selectedIndex = index }
            .create()
        showMeetingOverlayDialog(dialog) {
            val index = selectedIndex ?: return@showMeetingOverlayDialog
            val destination = destinations.getOrNull(index) ?: return@showMeetingOverlayDialog
            if (!ownsMeetingImageMenuContext(context, image.id)) return@showMeetingOverlayDialog
            val currentDocument = context.controller.state.document
            val currentRow = MeetingProjection.rows(
                currentDocument,
                runCatching { notes.get(context.sessionId)?.images.orEmpty() }
                    .getOrDefault(emptyList()).mapTo(mutableSetOf()) { it.number },
            ).firstOrNull { it.turnId == destination.turnId && it.editableSpeech } ?: return@showMeetingOverlayDialog
            val currentTurn = currentDocument.turns.firstOrNull { it.id == currentRow.turnId }
                ?: return@showMeetingOverlayDialog
            val body = currentTurn.editedText ?: currentTurn.recognizedText
            val insertAt = if (body.isEmpty()) 0 else body.length
            moveMeetingImage(context, image.id, currentTurn.id, insertAt)
        }
    }

    private fun moveMeetingImage(
        context: MeetingImageMenuContext,
        imageId: String,
        targetTurnId: String,
        offsetUtf16: Int,
    ) {
        applyMeetingImageMutation(context, imageId) { document, images ->
            MeetingImageBatch.move(document, images, imageId, targetTurnId, offsetUtf16)
        }
    }

    private fun removeMeetingImage(context: MeetingImageMenuContext, imageId: String) {
        applyMeetingImageMutation(context, imageId) { document, images ->
            MeetingImageBatch.remove(document, images, imageId)
        }
    }

    private fun applyMeetingImageMutation(
        context: MeetingImageMenuContext,
        imageId: String,
        mutation: (MeetingDocument, List<NoteImage>) -> MeetingImageMutation,
    ) {
        if (!ownsMeetingImageMenuContext(context, imageId)) return
        context.panel.flushFocusedEdit()
        context.panel.endEditing()
        val document = context.controller.state.document
        val note = try {
            notes.get(context.sessionId)
        } catch (_: Throwable) {
            null
        } ?: run {
            recordMeetingImageMutationFailure(context, imageId, mutation, "Échec de sauvegarde")
            return
        }
        if (note.images.none { it.id == imageId }) return
        val changed = try {
            mutation(document, note.images)
        } catch (_: Throwable) {
            recordMeetingImageMutationFailure(context, imageId, mutation, "Impossible de modifier cette image.")
            return
        }
        if (changed.document == document && changed.images == note.images) return
        persistMeetingImageMutation(
            context = context,
            imageId = imageId,
            targetImages = changed.images,
            removedImageIds = changed.removedImageIds,
        ) {
            changed.document.turns.forEach { turn ->
                val before = document.turns.firstOrNull { it.id == turn.id } ?: return@forEach
                val beforeBody = before.editedText ?: before.recognizedText
                val afterBody = turn.editedText ?: turn.recognizedText
                if (beforeBody != afterBody) context.controller.editTurn(turn.id, afterBody)
            }
        }
    }

    private fun persistMeetingImageMutation(
        context: MeetingImageMenuContext,
        imageId: String,
        targetImages: List<NoteImage>,
        removedImageIds: Set<String>,
        prepareDocument: () -> Unit = {},
    ) {
        if (!ownsMeetingImageMenuContext(context, imageId, allowMissingImage = removedImageIds.isNotEmpty())) return
        context.panel.flushFocusedEdit()
        context.panel.endEditing()
        val operation = MeetingImageMutationOperation(
            generation = ++meetingImageMutationGeneration,
            context = context,
            imageId = imageId,
            previousRecyclerVisibility = context.panel.view.recyclerView.visibility,
        )
        meetingImageMutationOperation = operation
        context.panel.view.recyclerView.visibility = View.GONE
        try {
            prepareDocument()
        } catch (_: Throwable) {
            finishMeetingImageMutationFailure(operation, null, "Impossible de modifier cette image.", removedImageIds)
            return
        }
        val flush = try {
            context.controller.flushDraft()
        } catch (_: Throwable) {
            finishMeetingImageMutationFailure(operation, null, "Échec de sauvegarde", removedImageIds)
            return
        }
        flush.whenComplete { _, failure ->
            main.post {
                if (!ownsMeetingImageMutation(operation)) {
                    abandonMeetingImageMutation(operation)
                    return@post
                }
                if (failure != null || localEngineLifecycle.isDestroyed()) {
                    finishMeetingImageMutationFailure(operation, null, "Échec de sauvegarde", removedImageIds)
                    return@post
                }
                val latest = context.controller.state.document
                if (latest.sessionId != context.sessionId || latest.runId != context.runId) {
                    abandonMeetingImageMutation(operation)
                    return@post
                }
                try {
                    notes.get(context.sessionId)
                        ?: throw IllegalStateException("Meeting note disappeared before image save")
                    if (!ownsMeetingImageMutation(operation)) {
                        abandonMeetingImageMutation(operation)
                        return@post
                    }
                    notes.saveMeeting(context.sessionId, latest, targetImages)
                    if (!ownsMeetingImageMutation(operation)) {
                        abandonMeetingImageMutation(operation)
                        return@post
                    }
                    removedImageIds.forEach { removedId -> imageStore.delete(removedId) }
                    meetingImageMutationError = null
                    finishMeetingImageMutationSuccess(operation)
                } catch (_: Throwable) {
                    finishMeetingImageMutationFailure(operation, null, "Échec de sauvegarde", removedImageIds)
                }
            }
        }
    }

    private fun recordMeetingImageMutationFailure(
        context: MeetingImageMenuContext,
        imageId: String,
        mutation: (MeetingDocument, List<NoteImage>) -> MeetingImageMutation,
        message: String,
    ) {
        meetingImageMutationError = MeetingImageMutationError(
            context.sessionId, context.runId, imageId, mutation, message,
        )
        if (meetingPanelController === context.panel && meetingSurfaceOpen) {
            renderMeetingState(context.controller.state)
        }
        toast(message)
    }

    private fun finishMeetingImageMutationFailure(
        operation: MeetingImageMutationOperation,
        mutation: ((MeetingDocument, List<NoteImage>) -> MeetingImageMutation)?,
        message: String,
        removedImageIds: Set<String> = emptySet(),
    ) {
        if (!ownsMeetingImageMutation(operation)) {
            abandonMeetingImageMutation(operation)
            return
        }
        val context = operation.context
        meetingImageMutationOperation = null
        if (meetingPanelController === context.panel && meetingSurfaceOpen) {
            context.panel.view.recyclerView.visibility = operation.previousRecyclerVisibility
            meetingImageMutationError = MeetingImageMutationError(
                sessionId = context.sessionId,
                runId = context.runId,
                imageId = operation.imageId,
                mutation = mutation,
                message = message,
                removedImageIds = removedImageIds,
            )
            renderMeetingState(context.controller.state)
        }
        toast(message)
    }

    private fun finishMeetingImageMutationSuccess(operation: MeetingImageMutationOperation) {
        if (!ownsMeetingImageMutation(operation)) {
            abandonMeetingImageMutation(operation)
            return
        }
        meetingImageMutationOperation = null
        val context = operation.context
        if (meetingPanelController === context.panel && meetingSurfaceOpen) {
            context.panel.view.recyclerView.visibility = operation.previousRecyclerVisibility
            meetingImageMutationError = null
            renderMeetingState(context.controller.state)
        }
        toast("Image mise à jour.")
    }

    private fun meetingImageMutationErrorFor(document: MeetingDocument): MeetingImageMutationError? =
        meetingImageMutationError?.takeIf {
            it.sessionId == document.sessionId &&
                meetingRecordingController?.state?.document?.runId == it.runId
        }

    private fun retryMeetingImageMutation(error: MeetingImageMutationError) {
        val controller = meetingRecordingController ?: return
        val panel = meetingPanelController ?: return
        val state = controller.state
        if (state.document.sessionId != error.sessionId || state.document.runId != error.runId) return
        val mode = transcriptionModes.snapshot()
        val context = MeetingImageMenuContext(
            sessionId = error.sessionId,
            runId = error.runId,
            controller = controller,
            panel = panel,
            mode = mode.mode,
            modeGeneration = mode.generation,
            poisonedDocumentOnly = mode.mode == TranscriptionMode.DICTATION && mode.poisoned &&
                mode.activeRunMode == null && meetingDocumentRestored && state.phase == MeetingRecordingPhase.DOCUMENT,
            anchor = null,
        )
        if (error.mutation != null) {
            if (!ownsMeetingImageMenuContext(context, error.imageId, allowMissingImage = true)) return
            applyMeetingImageMutation(context, error.imageId, error.mutation)
            return
        }
        if (!ownsMeetingImageMenuContext(context, error.imageId, allowMissingImage = true)) return
        val currentImages = try {
            notes.get(error.sessionId)?.images
                ?: throw IllegalStateException("Meeting note disappeared before image retry")
        } catch (_: Throwable) {
            meetingImageMutationError = error
            renderMeetingState(state)
            toast(error.message)
            return
        }
        val retryImages = currentImages.filterNot { it.id in error.removedImageIds }
        persistMeetingImageMutation(
            context = context,
            imageId = error.imageId,
            targetImages = retryImages,
            removedImageIds = error.removedImageIds,
        )
    }

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
        transcriptionModeSubscription?.close()
        transcriptionModeSubscription = null
        destroyMeetingResources()
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
        if (localFormatterLazy.isInitialized()) localFormatter.close()
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
                var recorderSafe = true
                try { recorderToRelease?.release() } catch (_: Throwable) { recorderSafe = false }
                var sessionSafe = true
                try {
                    while (sessionToCancel != null && !sessionToCancel.cancelAndAwait()) {
                        // Keep the resident engine alive until the cancelled native session really exits.
                    }
                } catch (_: Throwable) {
                    sessionSafe = false
                }
                var residentSafe = false
                if (sessionSafe) {
                    residentSafe = try {
                        residentAsrEngine.close()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                if (recorderSafe && sessionSafe && residentSafe) {
                    runToCancel?.let(::releaseDictationRunLease)
                } else {
                    markDictationCloseUncertain(runToCancel)
                }
            })
        }
        main.removeCallbacksAndMessages(null)
        try { hideScreenshotBatchBar() } catch (_: Exception) {}
        try { wave?.stop() } catch (_: Exception) {}
        try { loader?.stop() } catch (_: Exception) {}
        try { if (livePanelAdded) livePanel?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        try { container?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        livePanelAdded = false
        tailFollower?.reset(); tailFollower = null
        container = null; pill = null; wave = null; loader = null; pauseIndicator = null; gestureHint = null; stateIndicator = null; liveText = null; liveScroll = null; panelExpandButton = null; panelTitle = null; panelMoveHandle = null; panelResizeHandles.clear(); imageStripScroll = null; livePanelBody = null; bubblePointer = null; livePanel = null; liveParams = null; panelEdge = null; panelBodyRect = null; panelGestureStartRect = null; panelGestureStartScreen = null; panelGestureLastX = 0f; panelGestureLastY = 0f; panelGestureHandle = null; reducedPanelRect = null; reducedPanelRectScreen = null; pillPositionBeforeKeyboard = null
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
