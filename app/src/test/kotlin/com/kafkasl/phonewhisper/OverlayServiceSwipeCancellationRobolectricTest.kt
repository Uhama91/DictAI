package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayServiceSwipeCancellationRobolectricTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private lateinit var modes: TranscriptionModeCoordinator
    private var previousMode = TranscriptionMode.DICTATION
    private var previousModePresent = false
    private var previousModeValue: String? = null
    private var previousNotes: Map<String, Any?> = emptyMap()
    private var previousFolders: Map<String, Any?> = emptyMap()
    private var currentTouchDownTime = 0L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        val modePrefs = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        previousModePresent = modePrefs.contains("transcription_mode")
        previousModeValue = modePrefs.getString("transcription_mode", null)
        previousMode = PersistencePrefs(context).transcriptionMode
        TranscriptionModeCoordinator.clearProcessForTest()
        modePrefs.edit().putString("transcription_mode", TranscriptionMode.DICTATION.preferenceValue).commit()
        modes = TranscriptionModeCoordinator.process(context)

        val notesPrefs = context.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
        previousNotes = notesPrefs.all.toMap()
        notesPrefs.edit().clear().commit()
        val foldersPrefs = context.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
        previousFolders = foldersPrefs.all.toMap()
        foldersPrefs.edit().clear().commit()

        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        invoke(service, "showButton")
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        restorePreferences("transcript_notes", previousNotes)
        restorePreferences("transcript_note_folders", previousFolders)
        val modePrefs = context.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
        if (previousModePresent) modePrefs.edit().putString("transcription_mode", previousModeValue).commit()
        else modePrefs.edit().remove("transcription_mode").commit()
        PersistencePrefs(context).transcriptionMode = previousMode
        TranscriptionModeCoordinator.clearProcessForTest()
        ShadowSettings.reset()
    }

    @Test
    fun `quick right swipe during transcription cancels only the active run`() {
        val notes = serviceNotes()
        val oldNote = notes.save(null, "Note enregistrée avant la dictée")
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")

        val pill = field<FrameLayout>(service, "pill")
        val layout = field<WindowManager.LayoutParams>(service, "params")
        val width = (74 * context.resources.displayMetrics.density).toInt()
        val screen = serviceScreenRect()
        layout.width = width
        layout.x = screen.right - width
        val centerX = width / 2f
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val minimumDistance = maxOf(24 * context.resources.displayMetrics.density, 2f * touchSlop)
        val runway = screen.right - (layout.x + centerX)
        val threshold = minOf(56 * context.resources.displayMetrics.density, maxOf(minimumDistance, runway * 0.6f))
        val rightwardDistance = minOf(threshold + 3f, runway - 2f)
        assertTrue("edge fixture must fit swipe threshold: slop=$touchSlop minimum=$minimumDistance runway=$runway", rightwardDistance >= threshold)
        assertTrue("active transcription must enable cancellation", invokeBoolean(service, "classicSessionCancellationAvailable"))
        val touchTrace = wrapPillTouchListener(pill)
        send(pill, MotionEvent.ACTION_DOWN, centerX, 20f)
        send(pill, MotionEvent.ACTION_UP, centerX + rightwardDistance, 20f)

        assertTrue(
            "a rightward release cancels the current native session " +
                "(screen=$screen, lpX=${layout.x}, width=$width, runway=$runway, swipe=$rightwardDistance, " +
                "slop=$touchSlop threshold=$threshold, " +
                "touch=${touchTrace.joinToString("; ")})",
            cancellationRequested.get(),
        )
        assertEquals("the pill reports the in-flight cancellation", "CANCELLING", serviceStateName())
        assertEquals("a previous saved note remains intact", "Note enregistrée avant la dictée", notes.get(oldNote.id)?.text)
    }

    @Test
    fun `outside event over pill preserves folder back before the pill DOWN and UP`() {
        val notes = serviceNotes()
        val folder = requireNotNull(notes.createFolder("Dossier simulé"))
        val note = notes.save(id = null, text = "Texte conservé", folderId = folder.id)
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")
        val notesViewType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView")
        val folderPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Folder")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(folder.id)
        showNotesPage(notesViewType, folderPage)

        val menu = field<View>(service, "floatingMenu")
        val pill = field<FrameLayout>(service, "pill")
        val layout = field<WindowManager.LayoutParams>(service, "params")
        val width = (74 * context.resources.displayMetrics.density).toInt()
        val screen = serviceScreenRect()
        layout.width = width
        layout.x = screen.right - width
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(pill, layout)
        val pillLocation = IntArray(2).also(pill::getLocationOnScreen)
        val outsideX = pillLocation[0] + pill.width / 2f
        val outsideY = pillLocation[1] + pill.height / 2f

        sendOutside(menu, outsideX, outsideY)
        assertSame("ACTION_OUTSIDE on the visible pill keeps the notes back callback available",
            menu, field<View?>(service, "floatingMenu"))

        val centerX = pill.width / 2f
        val centerY = pill.height / 2f
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val minimumDistance = maxOf(24 * context.resources.displayMetrics.density, 2f * slop)
        val runway = screen.right - (layout.x + centerX)
        val threshold = minOf(56 * context.resources.displayMetrics.density, maxOf(minimumDistance, runway * 0.6f))
        val distance = minOf(threshold + 3f, runway - 2f)
        assertTrue("the physical edge fixture supports the folder back threshold", distance >= threshold)
        send(pill, MotionEvent.ACTION_DOWN, centerX, centerY)
        send(pill, MotionEvent.ACTION_UP, centerX + distance, centerY)

        assertFalse("folder navigation takes priority over an active transcription", cancellationRequested.get())
        assertEquals("the active transcription remains untouched", "TRANSCRIBING", serviceStateName())
        assertTrue("the menu now shows the root folder list",
            containsText(field(service, "floatingMenu"), "Mes notes   ×"))
        assertEquals("the folder note remains unchanged", "Texte conservé", notes.get(note.id)?.text)
    }

    @Test
    fun `outside event away from the pill dismisses a folder menu`() {
        val notes = serviceNotes()
        val folder = requireNotNull(notes.createFolder("Dossier simulé"))
        notes.save(id = null, text = "Texte conservé", folderId = folder.id)
        val notesViewType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView")
        val folderPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Folder")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(folder.id)
        showNotesPage(notesViewType, folderPage)
        val menu = field<View>(service, "floatingMenu")
        val pill = field<View>(service, "pill")
        val location = IntArray(2).also(pill::getLocationOnScreen)
        val outsideX = if (location[0] > 1) location[0] - 1f else location[0] + pill.width + 1f
        val outsideY = maxOf(1, location[1] + pill.height / 2).toFloat()

        sendOutside(menu, outsideX, outsideY)

        assertEquals("an outside touch away from the pill closes the menu", null,
            field<View?>(service, "floatingMenu"))
    }

    @Test
    fun `zero raw outside coordinates close a folder menu normally`() {
        val notes = serviceNotes()
        val folder = requireNotNull(notes.createFolder("Dossier simulé"))
        notes.save(id = null, text = "Texte conservé", folderId = folder.id)
        val notesViewType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView")
        val folderPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Folder")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(folder.id)
        showNotesPage(notesViewType, folderPage)
        val menu = field<View>(service, "floatingMenu")

        sendOutside(menu, 0f, 0f)

        assertEquals("masked zero coordinates cannot retain the folder menu", null,
            field<View?>(service, "floatingMenu"))
    }

    @Test
    fun `late outside event from an old menu cannot dismiss a replacement menu`() {
        val notes = serviceNotes()
        val folder = requireNotNull(notes.createFolder("Dossier simulé"))
        notes.save(id = null, text = "Texte conservé", folderId = folder.id)
        val notesViewType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView")
        val folderPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Folder")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(folder.id)
        showNotesPage(notesViewType, folderPage)
        val oldMenu = field<View>(service, "floatingMenu")
        val pill = field<View>(service, "pill")
        val location = IntArray(2).also(pill::getLocationOnScreen)
        val outsideX = if (location[0] > 1) location[0] - 1f else location[0] + pill.width + 1f
        val outsideY = maxOf(1, location[1] + pill.height / 2).toFloat()
        sendOutside(oldMenu, outsideX, outsideY)
        assertEquals("the first outside event closes its own menu", null,
            field<View?>(service, "floatingMenu"))

        showNotesPage(notesViewType, folderPage)
        val replacementMenu = field<View>(service, "floatingMenu")
        val pillLocation = IntArray(2).also(pill::getLocationOnScreen)
        sendOutside(
            oldMenu,
            pillLocation[0] + pill.width / 2f,
            pillLocation[1] + pill.height / 2f,
        )

        assertSame("a delayed event from the old menu leaves its replacement attached",
            replacementMenu, field<View?>(service, "floatingMenu"))
    }

    @Test
    fun `dedicated accessibility action cancels active transcription but standard focus actions do not`() {
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")
        val pill = field<FrameLayout>(service, "pill")
        val actionId = field<Int>(service, "cancelPillAccessibilityActionId")
        assertEquals("pill cancellation uses a stable resource action ID", R.id.accessibility_action_cancel_pill, actionId)

        listOf(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS,
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT,
        ).forEach { standardAction ->
            pill.performAccessibilityAction(standardAction, null)
        }
        assertFalse("standard accessibility actions must not cancel the transcription", cancellationRequested.get())
        assertEquals("standard accessibility actions preserve transcription", "TRANSCRIBING", serviceStateName())

        assertTrue("the dedicated action cancels the active transcription", pill.performAccessibilityAction(actionId, null))
        assertTrue(cancellationRequested.get())
        assertEquals("the dedicated action reports cancellation", "CANCELLING", serviceStateName())
    }

    @Test
    fun `short left vertical and interrupted gestures never cancel transcription`() {
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")
        val pill = field<FrameLayout>(service, "pill")

        val width = (74 * context.resources.displayMetrics.density).toInt()
        val layout = field<WindowManager.LayoutParams>(service, "params")
        layout.width = width
        layout.x = serviceScreenRect().right - width
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val minimumDistance = maxOf(24 * context.resources.displayMetrics.density, 2f * slop)
        val runway = width / 2f
        val threshold = minOf(56 * context.resources.displayMetrics.density, maxOf(minimumDistance, runway * 0.6f))
        send(pill, MotionEvent.ACTION_DOWN, width / 2f, 10f)
        send(pill, MotionEvent.ACTION_UP, width / 2f + threshold - 1f, 10f)
        assertFalse("release one pixel below threshold must preserve active transcription", cancellationRequested.get())

        send(pill, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(pill, MotionEvent.ACTION_UP, 25f, 10f)
        send(pill, MotionEvent.ACTION_DOWN, 100f, 10f)
        send(pill, MotionEvent.ACTION_UP, 20f, 10f)
        send(pill, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(pill, MotionEvent.ACTION_UP, 10f, 90f)
        send(pill, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(pill, MotionEvent.ACTION_CANCEL, 10f, 10f)

        assertFalse("failed or interrupted releases preserve the active operation", cancellationRequested.get())
        assertEquals("failed gestures leave transcription active", "TRANSCRIBING", serviceStateName())
    }

    @Test
    fun `hold then movement remains a pill drag instead of cancelling`() {
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")
        val pill = field<FrameLayout>(service, "pill")

        send(pill, MotionEvent.ACTION_DOWN, 10f, 10f)
        disableLoopingPillAnimationsForVirtualTime()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(401L, TimeUnit.MILLISECONDS)
        send(pill, MotionEvent.ACTION_MOVE, 90f, 10f)
        send(pill, MotionEvent.ACTION_UP, 90f, 10f)

        assertFalse("hold-and-drag must not cancel the run", cancellationRequested.get())
        assertEquals("the dictation remains active", "TRANSCRIBING", serviceStateName())
    }

    @Test
    fun `notes folder and unfiled content swipe returns to root without opening a note`() {
        val notes = serviceNotes()
        val folder = requireNotNull(notes.createFolder("Projet"))
        val note = notes.save(null, "Texte de la note conservée", folderId = folder.id)
        val notesViewType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView")
        val folderPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Folder")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(folder.id)
        showNotesPage(notesViewType, folderPage)
        assertNotesBackAccessibilityActionAndReturnToRoot()
        showNotesPage(notesViewType, folderPage)
        swipeMenuRightAndAssertRoot()

        val unfiledPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Unfiled")
            .getField("INSTANCE").get(null)
        showNotesPage(notesViewType, unfiledPage)
        swipeMenuRightAndAssertRoot()

        assertEquals("navigation leaves the previous note saved", "Texte de la note conservée", notes.get(note.id)?.text)
        assertEquals("a folder-list swipe does not open its note", null, field<String?>(service, "activeNoteId"))
    }

    @Test
    fun `right swipe in idle note draft preserves saved note and edits`() {
        val notes = serviceNotes()
        val saved = notes.save(null, "Texte enregistré")
        setField(service, "activeNoteId", saved.id)
        setField(service, "purpose", DictationPurpose.NOTE)
        val draftStore = serviceDraftStore()
        draftStore.save("Retouches non terminées")
        setServiceState("IDLE")

        val pill = field<FrameLayout>(service, "pill")
        send(pill, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(pill, MotionEvent.ACTION_UP, 90f, 10f)

        assertEquals("the finished note remains saved", "Texte enregistré", notes.get(saved.id)?.text)
        assertEquals("the current note stays open", saved.id, field<String?>(service, "activeNoteId"))
        assertEquals("unsaved edits stay in the draft", "Retouches non terminées", draftStore.load())
    }

    @Test
    fun `active pill exposes an accessible Annuler action`() {
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")
        val pill = field<FrameLayout>(service, "pill")
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
        try {
            pill.onInitializeAccessibilityNodeInfo(node)
            val actionId = field<Int>(service, "cancelPillAccessibilityActionId")
            val action = node.actionList.firstOrNull { it.id == actionId }
            assertEquals("Annuler", action?.label?.toString())
            assertTrue(pill.performAccessibilityAction(actionId, null))
            assertTrue("the native accessible action cancels the active operation", cancellationRequested.get())
        } finally {
            node.recycle()
        }
    }

    @Test
    fun `open folder describes navigation and restores active cancellation help when closed`() {
        val cancellationRequested = installTranscribingRun()
        setServiceState("TRANSCRIBING")
        val notes = serviceNotes()
        val folder = requireNotNull(notes.createFolder("Projet"))
        val notesViewType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView")
        val folderPage = Class.forName("com.kafkasl.phonewhisper.OverlayService\$NotesView\$Folder")
            .declaredConstructors.single().apply { isAccessible = true }.newInstance(folder.id)

        showNotesPage(notesViewType, folderPage)

        val pill = field<FrameLayout>(service, "pill")
        assertEquals(
            "Dossier ouvert. Glisser vers la droite ou utiliser l’action d’accessibilité « Retour à Mes dossiers » pour revenir à Mes dossiers.",
            pill.contentDescription,
        )
        val actionId = field<Int>(service, "cancelPillAccessibilityActionId")
        val folderNode = android.view.accessibility.AccessibilityNodeInfo.obtain()
        try {
            pill.onInitializeAccessibilityNodeInfo(folderNode)
            assertEquals("the pill action navigates while a folder is open", "Retour à Mes dossiers",
                folderNode.actionList.firstOrNull { it.id == actionId }?.label?.toString())
            assertTrue("the accessible action returns to the notes root", pill.performAccessibilityAction(actionId, null))
        } finally {
            folderNode.recycle()
        }

        assertFalse("folder navigation does not cancel the active transcription", cancellationRequested.get())
        assertEquals("Transcription en cours. Glisser vers la droite ou utiliser l’action d’accessibilité « Annuler ».",
            pill.contentDescription)
        val rootNode = android.view.accessibility.AccessibilityNodeInfo.obtain()
        try {
            pill.onInitializeAccessibilityNodeInfo(rootNode)
            assertEquals("the active cancellation action is restored after folder navigation", "Annuler",
                rootNode.actionList.firstOrNull { it.id == actionId }?.label?.toString())
        } finally {
            rootNode.recycle()
        }
    }

    @Test
    fun `meeting wave fills the pill viewport and restores the dictation viewport`() {
        val wave = field<CursiveWaveView>(service, "wave")
        val pill = field<FrameLayout>(service, "pill")
        val density = context.resources.displayMetrics.density
        val width = (74 * density).toInt()
        val height = (44 * density).toInt()
        val dictationHeight = (32 * density).toInt()
        val applyMode = service.javaClass.getDeclaredMethod("applyTranscriptionMode", TranscriptionMode::class.java)
            .apply { isAccessible = true }

        assertEquals(dictationHeight, wave.layoutParams.height)
        applyMode.invoke(service, TranscriptionMode.MEETING)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, wave.layoutParams.height)
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        pill.layout(0, 0, width, height)
        assertEquals("meeting wave uses the full 44 dp pill viewport", height, wave.height)
        assertTrue("meeting wave remains within the pill bounds",
            wave.left >= 0 && wave.top >= 0 && wave.right <= pill.width && wave.bottom <= pill.height)

        applyMode.invoke(service, TranscriptionMode.DICTATION)
        assertEquals("dictation restores the compact viewport", dictationHeight, wave.layoutParams.height)
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        pill.layout(0, 0, width, height)
        assertEquals(dictationHeight, wave.height)

        applyMode.invoke(service, TranscriptionMode.MEETING)
        assertEquals("switching back restores the meeting viewport", ViewGroup.LayoutParams.MATCH_PARENT,
            wave.layoutParams.height)
    }

    @Test
    fun `same accessibility projection is applied to a recreated pill`() {
        val description = "Transcription en cours. Glisser vers la droite ou utiliser l’action d’accessibilité « Annuler »."
        invoke(service, "updatePillAccessibilityProjection", description)

        val replacement = FrameLayout(context)
        setField(service, "pill", replacement)
        invoke(service, "updatePillAccessibilityProjection", description)

        assertEquals("the replacement pill receives the cached description", description, replacement.contentDescription)
    }

    private fun installTranscribingRun(): AtomicBoolean {
        val lease = requireNotNull(modes.reserveRun(TranscriptionMode.DICTATION))
        val prefs = PersistencePrefs(context)
        val optionsType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$RecordingOptions")
        val options = optionsType.declaredConstructors.single { it.parameterTypes.size == 9 }.apply {
            isAccessible = true
        }.newInstance(
            prefs.dictationLanguage,
            DictationAsrMode.STREAMING,
            false,
            false,
            prefs.cloudModel(),
            PostProcessingFormats(context).selected(),
            false,
            prefs.numberStyle,
            prefs.lightTextCleanup,
        )
        val session = object : DictationAsrSession {
            override fun acceptPcm16(buffer: ByteArray, length: Int) = Unit
            override fun finish(fullPcm: ByteArray): TranscriptionEngine.Result = error("finish must not run")
            override fun cancel() = Unit
            override fun cancelAndAwait(): Boolean = true
        }
        val cancellation = DictationCancellationCoordinator()
        val runType = Class.forName("com.kafkasl.phonewhisper.OverlayService\$ActiveDictationRun")
        val run = runType.declaredConstructors.single { it.parameterTypes.size == 5 }.apply {
            isAccessible = true
        }.newInstance(session, options, DictationPurpose.MESSAGE, lease, cancellation)
        val requested = AtomicBoolean(false)
        cancellation.onCancel { requested.set(true) }
        setField(service, "activeRun", run)
        return requested
    }

    private fun serviceNotes(): TranscriptNotes =
        (field<Lazy<TranscriptNotes>>(service, "notes\$delegate")).value

    private fun serviceDraftStore(): DictationDraftStore =
        field<Lazy<DictationDraftStore>>(service, "draftStore\$delegate").value

    private fun setServiceState(name: String) {
        val stateType = service.javaClass.declaredClasses.single { it.simpleName == "State" }
        val value = stateType.enumConstants.single { (it as Enum<*>).name == name }
        service.javaClass.getDeclaredMethod("setState", stateType).apply { isAccessible = true }
            .invoke(service, value)
    }

    private fun serviceStateName(): String =
        (field<Any>(service, "state") as Enum<*>).name

    private fun send(view: View, action: Int, x: Float, y: Float) {
        val eventTime = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) currentTouchDownTime = eventTime
        val event = MotionEvent.obtain(currentTouchDownTime, eventTime, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun sendOutside(view: View, rawX: Float, rawY: Float) {
        val eventTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_OUTSIDE, rawX, rawY, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun serviceScreenRect(): Rect = service.javaClass
        .getDeclaredMethod("screenRect").apply { isAccessible = true }
        .invoke(service) as Rect

    private fun wrapPillTouchListener(pill: View): MutableList<String> {
        val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
        val listenerInfo = requireNotNull(listenerInfoField.get(pill))
        val listenerField = listenerInfo.javaClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }
        val original = requireNotNull(listenerField.get(listenerInfo) as? View.OnTouchListener)
        val trace = mutableListOf<String>()
        var downRawX = 0f
        var downRawY = 0f
        var downLocalX = 0f
        listenerField.set(listenerInfo, View.OnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downRawX = event.rawX
                downRawY = event.rawY
                downLocalX = event.x
            }
            val layout = field<WindowManager.LayoutParams>(service, "params")
            val screen = serviceScreenRect()
            val runway = screen.right - (layout.x + downLocalX)
            val deltaX = event.rawX - downRawX
            val deltaY = event.rawY - downRawY
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            val minimumDistance = maxOf(24 * context.resources.displayMetrics.density, 2f * touchSlop)
            val threshold = minOf(56 * context.resources.displayMetrics.density, maxOf(minimumDistance, runway * 0.6f))
            val releaseEligible = event.actionMasked == MotionEvent.ACTION_UP && deltaX >= threshold && deltaX >= 2f * kotlin.math.abs(deltaY)
            val enabled = invokeBoolean(service, "classicSessionCancellationAvailable")
            val stateBefore = serviceStateName()
            val action = event.actionMasked
            val localX = event.x
            val rawX = event.rawX
            val localY = event.y
            val rawY = event.rawY
            val handled = original.onTouch(view, event)
            trace += "action=$action local=($localX,$localY) raw=($rawX,$rawY) " +
                "delta=($deltaX,$deltaY) runway=$runway enabled=$enabled " +
                "slop=$touchSlop threshold=$threshold releaseEligible=$releaseEligible " +
                "before=$stateBefore handled=$handled after=${serviceStateName()}"
            handled
        })
        return trace
    }

    private fun invokeBoolean(target: Any, name: String): Boolean = target.javaClass
        .getDeclaredMethod(name).apply { isAccessible = true }.invoke(target) as Boolean

    private fun disableLoopingPillAnimationsForVirtualTime() {
        field<CursiveWaveView?>(service, "wave")?.stop()
        field<LoadingBorderView?>(service, "loader")?.stop()
    }

    private fun showNotesPage(notesViewType: Class<*>, page: Any) {
        service.javaClass.getDeclaredMethod("showNotesOverlay", notesViewType).apply { isAccessible = true }
            .invoke(service, page)
    }

    private fun swipeMenuRightAndAssertRoot() {
        val menu = field<View>(service, "floatingMenu")
        val scroll = requireNotNull(findView<NotesBackScrollView>(menu))
        val width = (320 * context.resources.displayMetrics.density).toInt()
        val height = (240 * context.resources.displayMetrics.density).toInt()
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        scroll.layout(0, 0, width, height)
        send(scroll, MotionEvent.ACTION_DOWN, 100f, 150f)
        send(scroll, MotionEvent.ACTION_UP, 180f, 150f)
        val root = field<View>(service, "floatingMenu")
        assertTrue("the notes root is shown after returning", containsText(root, "Mes notes   ×"))
    }

    private fun assertNotesBackAccessibilityActionAndReturnToRoot() {
        val pill = field<FrameLayout>(service, "pill")
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
        try {
            pill.onInitializeAccessibilityNodeInfo(node)
            val actionId = field<Int>(service, "cancelPillAccessibilityActionId")
            val action = node.actionList.firstOrNull { it.id == actionId }
            assertEquals("Retour à Mes dossiers", action?.label?.toString())
            assertTrue("the accessible back action returns to the folder root",
                pill.performAccessibilityAction(actionId, null))
            assertTrue("the root folder list is visible after accessibility navigation",
                containsText(field(service, "floatingMenu"), "Mes notes   ×"))
        } finally {
            node.recycle()
        }
    }

    private inline fun <reified T : View> findView(root: View): T? {
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current is T) return current
            if (current is ViewGroup) {
                for (index in 0 until current.childCount) pending.addLast(current.getChildAt(index))
            }
        }
        return null
    }

    private fun containsText(root: View?, expected: String): Boolean {
        if (root == null) return false
        if (root is android.widget.TextView && root.text.toString() == expected) return true
        return root is ViewGroup && (0 until root.childCount).any { containsText(root.getChildAt(it), expected) }
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }.apply {
            isAccessible = true
        }.invoke(target, *args)
    }

    private fun restorePreferences(name: String, values: Map<String, Any?>) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val edit = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        edit.commit()
    }
}
