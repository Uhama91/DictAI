package com.kafkasl.phonewhisper.meeting

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.Rect
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.kafkasl.phonewhisper.NoteImage
import com.kafkasl.phonewhisper.NoteImageKind
import com.kafkasl.phonewhisper.OverlayTranscriptEditor
import com.kafkasl.phonewhisper.ThemeMode
import com.kafkasl.phonewhisper.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetingPanelControllerRobolectricTest {
    @Test
    fun revisedHypothesisKeepsFocusedEditorSelectionAndComposingSpan() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val reducer = MeetingTranscriptReducer("session-a", "run-a")
        reducer.apply(hypothesis(1, 1, listOf(word("Bonjour", 0, 100)), "Bonjour"))
        val initial = reducer.snapshot()
        val turnId = initial.turns.single().id
        val controller = MeetingPanelController(
            activity,
            TestDialogHost(),
            MeetingPanelActions(edit = reducer::edit),
        )
        activity.setContentView(controller.view)
        controller.render(initial)
        layout(activity, controller.view)

        val editorView = controller.view.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:$turnId")
        assertNotNull("render adds an editor for the stable turn", editorView)
        val editor = requireNotNull(editorView)
        editor.requestFocus()
        editor.setSelection(3)
        val composing = Any()
        editor.text.setSpan(composing, 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        editor.text.insert(3, "X")
        val manuallyEdited = editor.text.toString()
        val caretBeforeRevision = editor.selectionStart
        val composingStartBeforeRevision = editor.text.getSpanStart(composing)
        val composingEndBeforeRevision = editor.text.getSpanEnd(composing)
        val composingFlagsBeforeRevision = editor.text.getSpanFlags(composing)
        assertEquals(manuallyEdited, reducer.snapshot().turns.single().editedText)

        reducer.apply(hypothesis(
            1,
            2,
            listOf(word("Bonjour", 0, 100), word("ensuite", 110, 180)),
            "Bonjour ensuite",
        ))
        controller.render(reducer.snapshot())
        ShadowLooper.idleMainLooper()

        assertSame("the stable turn keeps its editor instance", editor, editor(controller, turnId))
        assertTrue("focus remains on the field being edited", editor.isFocused)
        assertEquals(manuallyEdited + " ensuite", editor.text.toString())
        assertEquals(caretBeforeRevision, editor.selectionStart)
        assertEquals(composingStartBeforeRevision, editor.text.getSpanStart(composing))
        assertEquals(composingEndBeforeRevision, editor.text.getSpanEnd(composing))
        assertEquals(composingFlagsBeforeRevision, editor.text.getSpanFlags(composing))

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun participantChoicesDisambiguateHomonymsAndRenameCapturedIdentity() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val renamed = mutableListOf<Pair<String, String>>()
        val controller = MeetingPanelController(
            activity,
            host,
            MeetingPanelActions(rename = { id, name -> renamed += id to name }),
        )
        activity.setContentView(controller.view)
        controller.render(documentWithHomonyms())
        layout(activity, controller.view)

        val participantsButton = findText(controller.view, "Intervenants (2)")
        assertNotNull("the participants action is available", participantsButton)
        participantsButton!!.performClick()
        val participants = host.choiceRequests.last()
        assertEquals(listOf("profile-1", "profile-2"), participants.request.choices.map { it.id })
        assertTrue(participants.request.choices[0].label.contains("Sophie"))
        assertTrue(participants.request.choices[0].label.contains("Personne 1"))
        assertTrue(participants.request.choices[1].label.contains("Personne 2"))

        participants.respond("profile-2")
        val profileActions = host.choiceRequests.last()
        profileActions.respond("profile:rename")
        val renameInput = host.textRequests.last()
        assertEquals("Sophie", renameInput.request.initialText)

        controller.render(documentWithHomonyms(reversed = true))
        renameInput.respond("Karim")

        assertEquals(listOf("profile-2" to "Karim"), renamed)
        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun captureAnchorCountsSerializedImageMarkerRatherThanEditorObject() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val image = NoteImage(
            id = "00000000-0000-0000-0000-000000000007",
            number = 7,
            kind = NoteImageKind.CAMERA,
            capturedAt = 1L,
            width = 640,
            height = 480,
        )
        val turnId = "turn-with-image"
        val doc = MeetingDocument(
            sessionId = "session-image",
            runId = "run-image",
            participants = listOf(MeetingParticipant("profile-1", 1, 1, name = "Sophie")),
            turns = listOf(MeetingTurn(
                id = turnId,
                utteranceId = 1,
                startMs = 0,
                endMs = 100,
                recognizedText = "avant [[Image 7]]après",
                automaticParticipantId = "profile-1",
                attributionStable = true,
            )),
        )
        val controller = MeetingPanelController(activity, TestDialogHost())
        activity.setContentView(controller.view)
        controller.render(doc, listOf(image))
        layout(activity, controller.view)

        val editorView = controller.view.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:$turnId")
        assertNotNull("render adds an editable field for the image turn", editorView)
        val editor = requireNotNull(editorView)
        assertTrue("known image markers are rendered as editor objects", editor.text.contains('\uFFFC'))
        editor.requestFocus()
        val markerEditorOffset = editor.text.indexOf('\uFFFC') + 1
        editor.setSelection(markerEditorOffset)

        assertEquals(
            MeetingPanelAnchor(turnId, "avant ".length + "[[Image 7]]".length),
            controller.captureAnchor(),
        )

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun dismissingAssignmentDoesNothingAndExplicitUnknownUsesNullParticipant() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val assignments = mutableListOf<Pair<String, String?>>()
        val controller = MeetingPanelController(
            activity,
            host,
            MeetingPanelActions(assign = { turnId, participantId -> assignments += turnId to participantId }),
        )
        val doc = documentWithHomonyms()
        activity.setContentView(controller.view)
        controller.render(doc)
        layout(activity, controller.view)

        val firstLabel = findText(controller.view, "Sophie")
        assertNotNull("speaker labels open assignment choices", firstLabel)
        firstLabel!!.performClick()
        host.choiceRequests.last().respond(null)
        assertTrue("closing the dialog without a choice does not mutate attribution", assignments.isEmpty())

        firstLabel.performClick()
        val request = host.choiceRequests.last()
        request.respond("outside-choice-list")
        assertTrue("responses not present in the request are ignored", assignments.isEmpty())

        firstLabel.performClick()
        val unknown = host.choiceRequests.last().request.choices.firstOrNull { it.label == "Inconnu" }
        assertNotNull("unknown is an explicit stable choice", unknown)
        assertEquals("participant:unknown", unknown!!.id)
        host.choiceRequests.last().respond(unknown.id)

        assertEquals(listOf("turn-1" to null), assignments)
        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun dialogResultsAreIgnoredAfterRunChangesOrTargetTurnDisappears() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val renamed = mutableListOf<Pair<String, String>>()
        val assignments = mutableListOf<Pair<String, String?>>()
        val controller = MeetingPanelController(
            activity,
            host,
            MeetingPanelActions(
                assign = { turnId, participantId -> assignments += turnId to participantId },
                rename = { id, name -> renamed += id to name },
            ),
        )
        val initial = documentWithHomonyms()
        activity.setContentView(controller.view)
        controller.render(initial)
        layout(activity, controller.view)

        findText(controller.view, "Intervenants (2)")!!.performClick()
        host.choiceRequests.last().respond("profile-1")
        val renameChoices = host.choiceRequests.last()
        controller.render(initial.copy(runId = "new-run"))
        renameChoices.respond("profile:rename")
        assertTrue("a stale choice does not open a text input", host.textRequests.isEmpty())
        assertTrue("a response from the prior run is ignored", renamed.isEmpty())

        controller.render(initial)
        findText(controller.view, "Intervenants (2)")!!.performClick()
        host.choiceRequests.last().respond("profile-1")
        host.choiceRequests.last().respond("profile:rename")
        val lateTextInput = host.textRequests.last()
        controller.render(initial.copy(runId = "newer-run"))
        lateTextInput.respond("Karim")
        assertTrue("a text response from the prior run is ignored", renamed.isEmpty())

        controller.render(initial)
        findText(controller.view, "Sophie")!!.performClick()
        val assignmentChoices = host.choiceRequests.last()
        controller.render(initial.copy(turns = initial.turns.drop(1)))
        assignmentChoices.respond("profile-1")
        assertTrue("a replaced turn is not assigned by a late dialog result", assignments.isEmpty())

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun longTurnIsNotSilentlyLimitedToSixLines() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val longText = (1..80).joinToString(" ") { "mot$it" }
        val doc = documentWithLongTurn(longText)
        val controller = MeetingPanelController(activity, TestDialogHost())
        activity.setContentView(controller.view)
        controller.render(doc)
        layout(activity, controller.view)

        val editor = requireNotNull(editor(controller, "long-turn"))
        assertEquals(longText, editor.text.toString())
        assertTrue("a long turn may grow beyond six visible lines", editor.maxLines > 6)

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun tenImageActionsRemainReachableThroughHorizontalScrolling() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val images = (1..10).map(::noteImage)
        val markers = images.joinToString(" ") { it.marker }
        val doc = documentWithLongTurn("Paroles $markers")
        val controller = MeetingPanelController(activity, TestDialogHost())
        activity.setContentView(controller.view)
        controller.render(doc, images)
        layout(activity, controller.view)

        val allViews = descendants(controller.view)
        val imageStrip = allViews.filterIsInstance<HorizontalScrollView>().firstOrNull()
        assertNotNull("image actions use a horizontal scrolling container", imageStrip)
        val imageButtons = allViews.filterIsInstance<Button>().filter {
            it.contentDescription?.startsWith("Ouvrir les actions pour") == true
        }
        assertEquals("all ten attachment actions remain in the scrollable strip", 10, imageButtons.size)
        assertTrue("image actions can receive keyboard focus", imageButtons.all { it.isFocusable })
        assertTrue("touching image actions does not take focus", imageButtons.none { it.isFocusableInTouchMode })

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun ignoredVoiceHidesSpeechButKeepsItsImageAction() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val image = noteImage(4)
        val participant = MeetingParticipant("ignored-profile", 1, 1, name = "Sophie", ignored = true)
        val turn = MeetingTurn(
            id = "ignored-turn",
            utteranceId = 1,
            startMs = 0,
            endMs = 100,
            recognizedText = "paroles privées ${image.marker}",
            automaticParticipantId = participant.id,
            attributionStable = true,
        )
        val doc = MeetingDocument(
            sessionId = "session-ignored",
            runId = "run-ignored",
            participants = listOf(participant),
            turns = listOf(turn),
        )
        val opened = mutableListOf<Pair<MeetingPanelAnchor?, Int>>()
        val controller = MeetingPanelController(
            activity,
            TestDialogHost(),
            MeetingPanelActions(imageAction = { anchor, selected -> opened += anchor to selected.number }),
        )
        activity.setContentView(controller.view)
        controller.render(doc, listOf(image))
        layout(activity, controller.view)

        assertEquals(null, controller.view.findViewWithTag<OverlayTranscriptEditor>("meeting-editor:ignored-turn"))
        assertTrue(
            "ignored words are absent from visible text",
            descendants(controller.view).filterIsInstance<TextView>().none { "paroles privées" in it.text },
        )
        val imageAction = descendants(controller.view).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == "Photo 4" }
        assertNotNull("the attached image still has its dedicated action", imageAction)
        imageAction!!.performClick()
        assertEquals(listOf(null to 4), opened)

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun compactPanelKeepsStatusCommandsAndRetryAccessibleAt240By112Dp() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val scaledConfiguration = Configuration(activity.resources.configuration).apply { fontScale = 1.25f }
        activity.resources.updateConfiguration(scaledConfiguration, activity.resources.displayMetrics)
        val host = TestDialogHost()
        val baseDoc = documentWithLongTurn("Première ligne visible\nSeconde ligne visible")
        val doc = baseDoc.copy(participants = baseDoc.participants.map { it.copy(name = "🙂ophie") })
        val controller = MeetingPanelController(activity, host)
        controller.render(doc, status = MeetingPanelStatus(
            phase = MeetingPanelStatus.Phase.LISTENING,
            saveError = "disque indisponible",
        ))
        val density = activity.resources.displayMetrics.density
        val root = FrameLayout(activity)
        val panelParams = FrameLayout.LayoutParams((360 * density).toInt(), (640 * density).toInt())
        root.addView(controller.view, panelParams)
        activity.setContentView(root)
        layoutContainer(root, 360, 640)
        val editorBeforeResize = requireNotNull(editor(controller, "long-turn"))
        panelParams.width = (240 * density).toInt()
        panelParams.height = (112 * density).toInt()
        controller.view.layoutParams = panelParams
        layoutContainer(root, 240, 112)
        assertEquals("the panel uses the actual requested compact surface", 240, controller.view.width)
        assertEquals("the panel uses the actual requested compact surface", 112, controller.view.height)
        assertSame("resizing does not recreate the actively displayed editor", editorBeforeResize, editor(controller, "long-turn"))
        val compactSpeakerLabel = descendants(controller.view).filterIsInstance<TextView>()
            .first { it.contentDescription == "Attribuer la prise de parole : 🙂ophie" }
        assertEquals("compact labels keep the first Unicode code point intact", "🙂", compactSpeakerLabel.text.toString())

        val toolbar = controller.view.getChildAt(0) as LinearLayout
        val title = toolbar.getChildAt(0) as TextView
        val buttons = (1 until toolbar.childCount).mapNotNull { toolbar.getChildAt(it) as? Button }
        assertTrue("compact status title retains some width", title.width >= 48)
        assertTrue("compact controls remain at least 48 dp wide", buttons.all { it.width >= 48 })
        assertTrue(title.contentDescription.toString().contains("Écoute en cours"))
        assertTrue(title.contentDescription.toString().contains("disque indisponible"))
        val visibleText = Rect()
        assertTrue("the speech editor is visibly laid out", editorBeforeResize.getGlobalVisibleRect(visibleText))
        assertTrue(
            "two lines of editable speech remain visible under the compact toolbar",
            visibleText.height() >= editorBeforeResize.lineHeight * 2,
        )

        val actionsButton = buttons.last()
        actionsButton.performClick()
        val actionLabels = host.choiceRequests.last().request.choices.map { it.label }
        assertTrue(
            "retry is available from the compact actions menu",
            "Réessayer la sauvegarde" in actionLabels,
        )
        assertTrue("the live audio phase remains visible in its own actions", "Mettre en pause" in actionLabels)
        assertTrue("a save error does not offer a second start", actionLabels.none { it == "Démarrer la réunion" })

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun documentActionsAppearOnlyInStablePhases() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val controller = MeetingPanelController(activity, host)
        val document = documentWithLongTurn("Paroles")
        activity.setContentView(controller.view)
        layout(activity, controller.view)

        val documentLabels = setOf(
            "Prendre une photo",
            "Ajouter une capture",
            "Copier la transcription",
            "Exporter la note",
        )
        val stablePhases = listOf(
            MeetingPanelStatus.Phase.READY,
            MeetingPanelStatus.Phase.LISTENING,
            MeetingPanelStatus.Phase.DELAYED,
            MeetingPanelStatus.Phase.PAUSED,
            MeetingPanelStatus.Phase.FINISHED,
            MeetingPanelStatus.Phase.MODEL_UNAVAILABLE,
            MeetingPanelStatus.Phase.ERROR,
        )
        stablePhases.forEach { phase ->
            host.choiceRequests.clear()
            controller.render(document, status = MeetingPanelStatus(phase = phase))
            actionsButton(controller).performClick()
            val menu = host.choiceRequests.single().request
            assertEquals("Actions de la réunion", menu.title)
            assertTrue("$phase exposes all four document actions", menu.choices.map { it.label }.containsAll(documentLabels))
        }

        val transitionalPhases = listOf(
            MeetingPanelStatus.Phase.LOADING,
            MeetingPanelStatus.Phase.DOWNLOADING,
            MeetingPanelStatus.Phase.PAUSING,
            MeetingPanelStatus.Phase.FINALIZING,
            MeetingPanelStatus.Phase.CLOSING,
        )
        transitionalPhases.forEach { phase ->
            host.choiceRequests.clear()
            controller.render(document, status = MeetingPanelStatus(phase = phase))
            actionsButton(controller).performClick()
            val menu = host.choiceRequests.single().request
            assertTrue("$phase keeps existing session actions available", menu.choices.isNotEmpty())
            assertTrue(
                "$phase hides only the four document actions",
                menu.choices.none { it.label in documentLabels },
            )
        }

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun documentActionReceivesFocusedEditAnchorAfterFlush() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val edits = mutableListOf<Pair<String, String>>()
        val documentActions = mutableListOf<Pair<MeetingPanelDocumentAction, MeetingPanelAnchor?>>()
        val controller = MeetingPanelController(
            activity,
            host,
            MeetingPanelActions(
                edit = { turnId, text -> edits += turnId to text },
                documentAction = { action, anchor -> documentActions += action to anchor },
            ),
        )
        activity.setContentView(controller.view)
        controller.render(documentWithLongTurn("Paroles"))
        layout(activity, controller.view)

        val editor = editor(controller, "long-turn")
        editor.requestFocus()
        editor.setSelection(3)
        editor.text.insert(3, " X")
        val currentText = editor.text.toString()
        val expectedAnchor = requireNotNull(controller.captureAnchor())
        assertEquals("the focused correction is published", currentText, edits.last().second)

        actionsButton(controller).performClick()
        val menu = host.choiceRequests.last()
        val copy = menu.request.choices.single { it.label == "Copier la transcription" }
        menu.respond(copy.id)
        listOf(
            MeetingPanelDocumentAction.PHOTO,
            MeetingPanelDocumentAction.SCREENSHOT,
            MeetingPanelDocumentAction.COPY,
            MeetingPanelDocumentAction.EXPORT,
        ).forEach { action ->
            val selected = menu.request.choices.single { it.label == documentActionLabel(action) }
            menu.respond(selected.id)
        }

        assertEquals(
            listOf(
                MeetingPanelDocumentAction.COPY,
                MeetingPanelDocumentAction.PHOTO,
                MeetingPanelDocumentAction.SCREENSHOT,
                MeetingPanelDocumentAction.COPY,
                MeetingPanelDocumentAction.EXPORT,
            ).map { it to expectedAnchor },
            documentActions,
        )
        assertTrue("opening the menu leaves the speech field focused", editor.isFocused)
        assertEquals(currentText, editor.text.toString())

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun documentActionChoiceIsIgnoredAfterRunChanges() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val documentActions = mutableListOf<MeetingPanelDocumentAction>()
        val document = documentWithLongTurn("Paroles")
        val controller = MeetingPanelController(
            activity,
            host,
            MeetingPanelActions(documentAction = { action, _ -> documentActions += action }),
        )
        activity.setContentView(controller.view)
        controller.render(document)
        layout(activity, controller.view)
        actionsButton(controller).performClick()
        val menu = host.choiceRequests.last()
        val export = menu.request.choices.single { it.label == "Exporter la note" }

        controller.render(document.copy(runId = "replacement-run"))
        menu.respond(export.id)

        assertTrue("a dialog from the prior run cannot request a document action", documentActions.isEmpty())

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun manualThemeRefreshRecolorsWithoutReplacingFocusedEditor() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        activity.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", ThemeMode.LIGHT.preferenceValue).commit()
        val doc = documentWithLongTurn("Bonjour")
        val controller = MeetingPanelController(activity, TestDialogHost())
        activity.setContentView(controller.view)
        controller.render(doc)
        layout(activity, controller.view)

        val editor = requireNotNull(editor(controller, "long-turn"))
        editor.requestFocus()
        editor.setSelection(editor.length())
        val composing = Any()
        editor.text.setSpan(composing, 0, editor.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        val composingStart = editor.text.getSpanStart(composing)
        val composingEnd = editor.text.getSpanEnd(composing)
        val composingFlags = editor.text.getSpanFlags(composing)
        controller.refreshTheme()
        assertEquals(ThemeTokens.LIGHT.surface, (controller.view.background as ColorDrawable).color)
        assertEquals(ThemeTokens.LIGHT.ink, editor.currentTextColor)

        activity.getSharedPreferences("whisperpin", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", ThemeMode.DARK.preferenceValue).commit()
        controller.refreshTheme()
        assertSame("theme refresh preserves the existing editor", editor, editor(controller, "long-turn"))
        assertTrue("theme refresh preserves focus", editor.isFocused)
        assertEquals(composingStart, editor.text.getSpanStart(composing))
        assertEquals(composingEnd, editor.text.getSpanEnd(composing))
        assertEquals(composingFlags, editor.text.getSpanFlags(composing))
        assertEquals(ThemeTokens.DARK.surface, (controller.view.background as ColorDrawable).color)
        assertEquals(ThemeTokens.DARK.ink, editor.currentTextColor)

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun successiveRendersCoalesceStaleScrollRestorations() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val participant = MeetingParticipant("scroll-profile", 1, 1, name = "Sophie")
        val turns = (0..24).map { index ->
            MeetingTurn("scroll-$index", index.toLong(), index * 100L, index * 100L + 50, "Parole $index", participant.id, attributionStable = true)
        }
        val doc = MeetingDocument(
            sessionId = "session-scroll",
            runId = "run-scroll",
            participants = listOf(participant),
            turns = turns,
        )
        val controller = MeetingPanelController(activity, TestDialogHost())
        val manager = RecordingLayoutManager(activity)
        controller.view.recyclerView.layoutManager = manager
        activity.setContentView(controller.view)
        controller.render(doc)
        layout(activity, controller.view)

        manager.scrollToPositionWithOffset(4, -12)
        layout(activity, controller.view)
        manager.offsetScrolls.clear()

        controller.render(doc.copy(turns = turns.map { it.copy(recognizedText = "Première ${it.id}") }))
        controller.render(doc.copy(turns = turns.map { it.copy(recognizedText = "Dernière ${it.id}") }))
        ShadowLooper.idleMainLooper()

        assertEquals("only the latest pending anchor restoration is applied", 1, manager.offsetScrolls.size)

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun editingAtTheBottomDoesNotFollowNewRowsAutomatically() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val participant = MeetingParticipant("tail-profile", 1, 1, name = "Sophie")
        val turns = listOf(MeetingTurn(
            "tail-0", 0L, 0L, 50L, "Parole 0", participant.id, attributionStable = true,
        ))
        val doc = MeetingDocument(
            sessionId = "session-tail",
            runId = "run-tail",
            participants = listOf(participant),
            turns = turns,
        )
        val controller = MeetingPanelController(activity, TestDialogHost())
        val manager = RecordingLayoutManager(activity)
        controller.view.recyclerView.layoutManager = manager
        activity.setContentView(controller.view)
        controller.render(doc)
        layout(activity, controller.view)

        assertFalse("the single visible turn is at the bottom of the list", controller.view.recyclerView.canScrollVertically(1))
        val lastEditor = requireNotNull(editor(controller, turns.last().id))
        lastEditor.requestFocus()
        assertTrue("the visible tail turn is actively edited", lastEditor.isFocused)
        manager.tailScrolls.clear()

        val added = MeetingTurn("tail-1", 1L, 100L, 150L, "Nouvelle parole", participant.id, attributionStable = true)
        controller.render(doc.copy(turns = turns + added))
        ShadowLooper.idleMainLooper()

        assertTrue("an active editor prevents automatic tail following", manager.tailScrolls.isEmpty())

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    @Test
    fun actionsMatchSessionPhaseAndKeepDownloadCancellationSeparate() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val host = TestDialogHost()
        val commands = mutableListOf<MeetingPanelSessionCommand>()
        val controller = MeetingPanelController(
            activity,
            host,
            MeetingPanelActions(sessionCommand = { commands += it }),
        )
        val doc = documentWithHomonyms()
        activity.setContentView(controller.view)
        controller.render(doc)
        layout(activity, controller.view)

        fun choices(phase: MeetingPanelStatus.Phase): List<MeetingPanelChoice> {
            controller.render(doc, status = MeetingPanelStatus(phase = phase))
            findText(controller.view, "Actions")!!.performClick()
            return host.choiceRequests.last().request.choices
        }

        assertTrue(choices(MeetingPanelStatus.Phase.READY).any { it.label == "Démarrer la réunion" })
        val loading = choices(MeetingPanelStatus.Phase.LOADING)
        assertTrue(loading.any { it.label == "Annuler le chargement" })
        assertTrue(loading.none { it.label == "Annuler le téléchargement" })
        host.choiceRequests.last().respond("action:cancel")
        val downloading = choices(MeetingPanelStatus.Phase.DOWNLOADING)
        assertTrue(downloading.any { it.label == "Annuler le téléchargement" })
        host.choiceRequests.last().respond("action:cancel-download")
        assertEquals(
            listOf(MeetingPanelSessionCommand.CANCEL, MeetingPanelSessionCommand.CANCEL_DOWNLOAD),
            commands,
        )
        listOf(
            MeetingPanelStatus.Phase.PAUSING,
            MeetingPanelStatus.Phase.FINALIZING,
            MeetingPanelStatus.Phase.CLOSING,
        ).forEach { phase ->
            val labels = choices(phase).map { it.label }
            assertFalse("$phase cannot resume the microphone", "Reprendre la réunion" in labels)
            assertFalse("$phase cannot restart the current session", "Démarrer la réunion" in labels)
        }
        val finished = choices(MeetingPanelStatus.Phase.FINISHED).map { it.label }
        assertTrue("a finished note offers a new session", "Nouvelle réunion" in finished)
        assertFalse("a finished note cannot resume a lost session", "Reprendre la réunion" in finished)

        controller.dispose()
        activityController.pause().stop().destroy()
    }

    private fun editor(controller: MeetingPanelController, turnId: String): OverlayTranscriptEditor =
        requireNotNull(controller.view.findViewWithTag("meeting-editor:$turnId"))

    private fun actionsButton(controller: MeetingPanelController): Button = descendants(controller.view)
        .filterIsInstance<Button>()
        .first { it.contentDescription == "Actions de la réunion" }

    private fun documentActionLabel(action: MeetingPanelDocumentAction): String = when (action) {
        MeetingPanelDocumentAction.PHOTO -> "Prendre une photo"
        MeetingPanelDocumentAction.SCREENSHOT -> "Ajouter une capture"
        MeetingPanelDocumentAction.COPY -> "Copier la transcription"
        MeetingPanelDocumentAction.EXPORT -> "Exporter la note"
    }

    private fun layout(activity: Activity, view: View, widthDp: Int = 360, heightDp: Int = 640) {
        val density = activity.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(exact(width), exact(height))
        view.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
    }

    private fun layoutContainer(root: FrameLayout, widthDp: Int, heightDp: Int) {
        val density = root.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
    }

    private fun findText(root: View, value: String): TextView? {
        if (root is TextView && root.text.toString() == value) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) findText(root.getChildAt(index), value)?.let { return it }
        return null
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private fun noteImage(number: Int) = NoteImage(
        id = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}",
        number = number,
        kind = NoteImageKind.CAMERA,
        capturedAt = number.toLong(),
        width = 640,
        height = 480,
    )

    private fun documentWithLongTurn(text: String): MeetingDocument {
        val participant = MeetingParticipant("long-profile", 1, 1, name = "Sophie")
        return MeetingDocument(
            sessionId = "session-long",
            runId = "run-long",
            participants = listOf(participant),
            turns = listOf(MeetingTurn(
                id = "long-turn",
                utteranceId = 1,
                startMs = 0,
                endMs = 100,
                recognizedText = text,
                automaticParticipantId = participant.id,
                attributionStable = true,
            )),
        )
    }

    private class RecordingLayoutManager(context: Context) : LinearLayoutManager(context) {
        val offsetScrolls = mutableListOf<Pair<Int, Int>>()
        val tailScrolls = mutableListOf<Int>()

        override fun scrollToPositionWithOffset(position: Int, offset: Int) {
            offsetScrolls += position to offset
            super.scrollToPositionWithOffset(position, offset)
        }

        override fun scrollToPosition(position: Int) {
            tailScrolls += position
            super.scrollToPosition(position)
        }
    }

    private fun documentWithHomonyms(reversed: Boolean = false): MeetingDocument {
        val first = MeetingParticipant("profile-1", 1, 1, name = "Sophie")
        val second = MeetingParticipant("profile-2", 2, 2, name = "Sophie")
        return MeetingDocument(
            sessionId = "session-homonyms",
            runId = "run-homonyms",
            participants = if (reversed) listOf(second, first) else listOf(first, second),
            turns = listOf(
                MeetingTurn("turn-1", 1, 0, 100, "Bonjour", first.id, attributionStable = true),
                MeetingTurn("turn-2", 2, 120, 200, "Salut", second.id, attributionStable = true),
            ),
        )
    }

    private fun hypothesis(utteranceId: Long, revision: Long, words: List<MeetingWord>, transcript: String) =
        MeetingHypothesis(
            runId = "run-a",
            utteranceId = utteranceId,
            revision = revision,
            words = words,
            transcript = transcript,
            isFinal = false,
            stableSpeakerThroughMs = 1_000,
            audioProcessedMs = 1_000,
        )

    private fun word(text: String, startMs: Long, endMs: Long) = MeetingWord(text, startMs, endMs, channel = 1)

    private class TestDialogHost : MeetingPanelDialogHost {
        data class ChoiceCall(
            val request: MeetingPanelChoicesRequest,
            val callback: (String?) -> Unit,
        ) {
            fun respond(id: String?) = callback(id)
        }

        data class TextCall(
            val request: MeetingPanelTextInputRequest,
            val callback: (String?) -> Unit,
        ) {
            fun respond(value: String?) = callback(value)
        }

        val choiceRequests = mutableListOf<ChoiceCall>()
        val textRequests = mutableListOf<TextCall>()

        override fun showChoices(request: MeetingPanelChoicesRequest, onChoice: (String?) -> Unit) {
            choiceRequests += ChoiceCall(request, onChoice)
        }

        override fun showTextInput(request: MeetingPanelTextInputRequest, onSubmit: (String?) -> Unit) {
            textRequests += TextCall(request, onSubmit)
        }
    }
}
