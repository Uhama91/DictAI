package com.kafkasl.phonewhisper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

/**
 * Demonstration-only native renders for the real overlay editor and progressive transcript state.
 *
 * The strings are synthetic fixtures. The PNGs document layout only; they are not a
 * phone-device rendering or a model-quality evaluation.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressiveOverlayVisualRenderTest {
    private lateinit var controller: ServiceController<OverlayService>
    private lateinit var service: OverlayService

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        controller = Robolectric.buildService(OverlayService::class.java)
        service = controller.create().get()
        invoke(service, "showButton")
        invoke(service, "setLivePreviewVisible", true)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        ShadowSettings.reset()
    }

    @Test
    fun `renders corrected list email and retained progressive correction scenes`() {
        val output = renderScenes()
        assertEquals(
            listOf(
                "overlay-corrected-local-list.png",
                "overlay-email-paragraphs.png",
                "overlay-progressive-prefix-tail.png",
            ),
            output.map(File::getName),
        )
        output.forEach { file ->
            assertTrue("empty render: $file", file.length() > 1_000L)
        }
    }

    private fun renderScenes(): List<File> {
        val body = field<FrameLayout>(service, "livePanelBody")
        val pill = field<FrameLayout>(service, "pill")
        val editor = field<OverlayTranscriptEditor>(service, "liveText")
        val listText = "Pour le dossier il faut :\n" +
            "• le formulaire signé ;\n" +
            "• deux photos d'identité ;\n" +
            "• la copie du justificatif.\n" +
            "Je déposerai le dossier demain."
        val emailText = "Bonjour,\n\n" +
            "Je vous confirme la réunion de mardi.\n\n" +
            "Cordialement,\nClaire."

        val listOutput = renderScene(body, pill, editor, listText, "overlay-corrected-local-list.png")
        assertTrue(editor.text.toString().contains("• le formulaire signé"))
        assertTrue(editor.text.toString().contains("Je déposerai le dossier demain."))

        val emailOutput = renderScene(body, pill, editor, emailText, "overlay-email-paragraphs.png")
        assertTrue(editor.text.toString().contains("Bonjour,\n\nJe vous confirme"))
        assertTrue(editor.text.toString().contains("\n\nCordialement,\nClaire."))

        // Use the service-owned editable transcript to model a real inline change. The editor
        // is still the real OverlayTranscriptEditor, so this render checks the visible contract
        // without pretending that a synthetic formatter acceptance is a human edit.
        val editableTranscript = field<EditableTranscript>(service, "editableTranscript")
        editableTranscript.clear()
        val initial = "Je prépare le dossier pour la réunion de mercredi."
        assertEquals(initial, editableTranscript.update(initial))
        editor.setText(editableTranscript.visibleText())
        val correction = initial.replace("mercredi", "jeudi")
        editableTranscript.edit(correction)
        assertEquals(correction, editableTranscript.visibleText())
        editor.setText(editableTranscript.visibleText())
        // The next ASR update still says "mercredi"; the user-owned "jeudi" must survive.
        val continued = "$initial Ensuite je transmettrai la copie au service puis " +
            "j'attendrai la confirmation avant de prévenir Claire et Nora."
        val expectedRendered = "$correction Ensuite je transmettrai la copie au service puis " +
            "j'attendrai la confirmation avant de prévenir Claire et Nora."
        val rendered = editableTranscript.update(continued)
        assertEquals(expectedRendered, rendered)
        assertTrue(rendered.startsWith("Je prépare le dossier pour la réunion de jeudi."))
        assertTrue(rendered.contains("Ensuite je transmettrai la copie au service"))
        val progressiveOutput = renderScene(
            body,
            pill,
            editor,
            rendered,
            "overlay-progressive-prefix-tail.png",
        )
        assertTrue(editor.text.toString().startsWith(correction))
        assertTrue(editor.text.toString().contains("puis j'attendrai la confirmation"))

        return listOf(listOutput, emailOutput, progressiveOutput)
    }

    private fun renderScene(
        body: FrameLayout,
        pill: FrameLayout,
        editor: OverlayTranscriptEditor,
        text: String,
        name: String,
    ): File {
        val panelWidth = 336
        val panelHeight = 320
        editor.setText(text)
        editor.setSelection(editor.length())
        invoke(service, "layoutTranscriptRows", panelHeight, 1f)
        body.visibility = View.VISIBLE
        body.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panelHeight, View.MeasureSpec.EXACTLY),
        )
        body.layout(0, 0, panelWidth, panelHeight)
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(74, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(44, View.MeasureSpec.EXACTLY),
        )
        pill.layout(0, 0, 74, 44)

        val bitmap = Bitmap.createBitmap(480, 420, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(20, 24, 22))
            canvas.save()
            canvas.translate(20f, 48f)
            body.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.translate(366f, 76f)
            pill.draw(canvas)
            canvas.restore()
            val output = File("build/robolectric-renders/gemma4-progressive-visual-v1/$name")
                .apply { parentFile?.mkdirs() }
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            return output
        } finally {
            bitmap.recycle()
        }
    }

    private inline fun <reified T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(target) as T
        }

    private fun invoke(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        method.invoke(target, *args)
    }
}
