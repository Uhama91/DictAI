package com.kafkasl.phonewhisper

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayNotesNavigationTest {
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService
    private lateinit var notes: TranscriptNotes
    private lateinit var folder: NoteFolder
    private lateinit var note: TranscriptNote

    @Before fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        invoke("showButton")
        notes = field<Lazy<TranscriptNotes>>("notes\$delegate").value
        folder = notes.createFolder("Préparation de classe")!!
        note = notes.save(null, "Préparer les activités de jeudi.", folderId = folder.id)
        notes.rename(note.id, "Jeudi 8 octobre")
        note = notes.get(note.id)!!
        showRoot()
    }

    @After fun tearDown() {
        runCatching { controller.destroy() }
        ShadowSettings.reset()
    }

    @Test fun doneReturnsToTheFolderAndPreservesEdits() {
        openFolderNote()
        field<OverlayTranscriptEditor>("liveText").text.append(" Apporter les affiches.")
        field<TextView>("noteDoneButton").performClick()
        assertFolderVisible()
        assertEquals("Préparer les activités de jeudi. Apporter les affiches.", notes.get(note.id)?.text)
        assertEquals(folder.id, notes.get(note.id)?.folderId)
        assertNull(field<Any?>("activeNoteId"))
    }

    @Test fun aNewNoteCreatedInsideAFolderReturnsThereWhenDone() {
        click(menu(), folder.name)
        click(menu(), "＋ Nouvelle note")
        field<OverlayTranscriptEditor>("liveText").setText("Une nouvelle idée")
        field<TextView>("noteDoneButton").performClick()
        assertFolderVisible()
        assertEquals(2, notes.all(folder.id).size)
    }

    @Test fun backButtonSavesTheNoteAndReturnsOneLevel() {
        openFolderNote()
        field<OverlayTranscriptEditor>("liveText").text.append(" Relire la consigne.")
        click(field("livePanelBody"), "‹ Retour")
        assertFolderVisible()
        assertTrue(notes.get(note.id)!!.text.endsWith("Relire la consigne."))
        click(menu(), "← Mes dossiers")
        assertRootVisible()
    }

    @Test fun rightSwipeReturnsFromTheNoteThenFromItsFolder() {
        openFolderNote()
        val body = field<View>("livePanelBody")
        layout(body)
        render(body, "note")
        swipe(body, 45f, 115f, 185f, 118f)
        assertFolderVisible()
        render(menu(), "folder")
        swipe(menu(), 45f, 210f, 185f, 213f)
        assertRootVisible()
        render(menu(), "root")
    }

    @Test fun cancelledOrVerticalOrLeftSwipesKeepTheNoteOpen() {
        openFolderNote()
        val body = field<View>("livePanelBody")
        layout(body)
        swipe(body, 45f, 115f, 185f, 118f, cancel = true)
        assertNull(field<Any?>("floatingMenu"))
        invoke("releaseTranscriptFocus")
        swipe(body, 45f, 180f, 48f, 95f)
        assertNull(field<Any?>("floatingMenu"))
        invoke("releaseTranscriptFocus")
        swipe(body, 185f, 115f, 45f, 118f)
        assertNull(field<Any?>("floatingMenu"))
        assertEquals(note.id, field<String>("activeNoteId"))
    }

    @Test fun incompleteSwipeOnANoteRowNeitherOpensTheNoteNorLeavesTheFolder() {
        click(menu(), folder.name)
        swipe(menu(), 45f, 210f, 75f, 213f)
        assertFolderVisible()
        assertNull(field<Any?>("activeNoteId"))
    }

    @Test fun verticalScrollingInAFolderDoesNotBecomeABackSwipe() {
        click(menu(), folder.name)
        swipe(menu(), 45f, 270f, 50f, 100f)
        assertFolderVisible()
        assertNull(field<Any?>("activeNoteId"))
    }

    @Test fun aSwipeWhileEditingDoesNotStealTextSelection() {
        openFolderNote()
        val body = field<View>("livePanelBody")
        layout(body)
        field<OverlayTranscriptEditor>("liveText").beginEditing()
        field<OverlayTranscriptEditor>("liveText").setSelection(0, 8)
        swipe(body, 45f, 115f, 185f, 118f)
        assertEquals(note.id, field<String>("activeNoteId"))
        assertNull(field<Any?>("floatingMenu"))
    }

    @Test fun scrollingTheNoteDoesNotDisableTheNextBackSwipe() {
        openFolderNote()
        val body = field<View>("livePanelBody")
        layout(body)
        val editor = field<OverlayTranscriptEditor>("liveText")
        val textBounds = android.graphics.Rect()
        editor.getDrawingRect(textBounds)
        (body as ViewGroup).offsetDescendantRectToMyCoords(editor, textBounds)
        val y = textBounds.centerY().toFloat()
        swipe(body, 55f, y, 58f, y - 40)
        assertEquals(note.id, field<String?>("activeNoteId"))
        swipe(body, 55f, 115f, 185f, 115f)
        assertFolderVisible()
    }

    @Test fun resizingANoteFromItsLeftCornerDoesNotNavigateBack() {
        openFolderNote()
        val body = field<View>("livePanelBody")
        layout(body)
        val handle = field<Map<PanelResizeHandle, View>>("panelResizeHandles").getValue(PanelResizeHandle.TOP_LEFT)
        val y = handle.top + handle.height / 2f
        swipe(body, 10f, y, 110f, y)
        assertEquals(note.id, field<String?>("activeNoteId"))
        assertNull(field<Any?>("floatingMenu"))
    }

    @Test fun openingANoteDirectlyStillReturnsToItsFolder() {
        invoke("openNote", note)
        field<TextView>("noteDoneButton").performClick()
        assertFolderVisible()
    }

    @Test fun deletedParentFallsBackToRootWithoutLosingTheNote() {
        openFolderNote()
        notes.deleteFolder(folder.id)
        field<TextView>("noteDoneButton").performClick()
        assertRootVisible()
        assertEquals(note.text, notes.get(note.id)?.text)
        assertNull(notes.get(note.id)?.folderId)
    }

    @Test fun unfiledNoteReturnsToUnfiledAndThenRoot() {
        val unfiled = notes.chooseFolder(notes.save(null, "Note libre").id, null)!!
        click(menu(), "Sans dossier")
        click(menu(), unfiled.title)
        field<TextView>("noteDoneButton").performClick()
        assertTrue(allViews(menu()).filterIsInstance<TextView>().any { it.text.toString().startsWith("Sans dossier") })
        click(menu(), "← Mes dossiers")
        assertRootVisible()
    }

    private fun showRoot() {
        service.onStartCommand(Intent(service, OverlayService::class.java).setAction(OverlayService.ACTION_OPEN_NOTES), 0, 1)
    }

    private fun openFolderNote() { click(menu(), folder.name); click(menu(), note.title) }
    private fun menu(): View = field("floatingMenu")
    private fun assertFolderVisible() {
        assertTrue("The note's folder must remain open", allViews(menu()).filterIsInstance<TextView>()
            .any { it.text.toString().startsWith(folder.name) })
        assertTrue(allViews(menu()).filterIsInstance<TextView>().any { it.text.toString() == note.title })
    }
    private fun assertRootVisible() {
        assertTrue(allViews(menu()).filterIsInstance<TextView>().any { it.text.toString().startsWith("Mes notes") })
        assertTrue(allViews(menu()).filterIsInstance<TextView>().any { it.text.toString() == "＋ Nouveau dossier" })
    }
    private fun click(root: View, label: String) {
        val text = allViews(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == label }
        assertNotNull("Missing action: $label", text)
        var target: View = text!!
        while (!target.isClickable) target = target.parent as View
        assertTrue(target.performClick())
    }
    private fun allViews(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) } else emptyList()

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(430, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 430)
    }
    private fun swipe(view: View, x: Float, y: Float, endX: Float, endY: Float, cancel: Boolean = false) {
        layout(view)
        val start = SystemClock.uptimeMillis()
        listOf(Triple(MotionEvent.ACTION_DOWN, x, y), Triple(MotionEvent.ACTION_MOVE, endX, endY),
            Triple(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX, endY))
            .forEachIndexed { i, (action, px, py) ->
                val event = MotionEvent.obtain(start, start + i * 30, action, px, py, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
    }
    private fun render(view: View, name: String) {
        val width = view.layoutParams.width.coerceAtLeast(240)
        val height = view.layoutParams.height.coerceAtLeast(160)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap).apply { scale(2f, 2f) }
            view.draw(canvas)
            File("build/robolectric-renders/notes-navigation-$name.png").apply { parentFile?.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private inline fun <reified T> field(name: String): T = service.javaClass.getDeclaredField(name).run {
        isAccessible = true
        @Suppress("UNCHECKED_CAST") get(service) as T
    }
    private fun invoke(name: String, vararg args: Any?) {
        service.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(service, *args)
    }
}
