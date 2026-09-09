package com.kafkasl.phonewhisper

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** Exports are explicit, local, and independent of ASR or any language model. */
class NoteExportActivity : Activity() {
    private enum class Format { IMAGES, PDF, HTML }
    private var documents = emptyList<File>()
    private var format = Format.IMAGES
    private var busy = false
    private lateinit var note: TranscriptNote
    private lateinit var status: TextView
    private val actions = mutableListOf<Button>()
    private lateinit var save: Button
    private lateinit var share: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 17f }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt(); setPadding(padding, padding, padding, padding)
            addView(status)
        }
        fun action(label: String, click: () -> Unit): Button = Button(this).apply {
            text = label; setOnClickListener { click() }; root.addView(this); actions += this
        }
        action("Partager le texte et les images") { prepare(Format.IMAGES, ::shareDocument) }
        action("Lire en PDF") { prepare(Format.PDF, ::viewPdf) }
        action("Exporter en HTML autonome") { prepare(Format.HTML, ::saveDocument) }
        save = action("Enregistrer les fichiers dans un dossier", ::saveDocument).apply { isEnabled = false }
        share = action("Partager les fichiers préparés", ::shareDocument).apply { isEnabled = false }
        action("Fermer") { finish() }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            status.text = "Déverrouillez le téléphone pour exporter une note."
            actions.forEach { it.isEnabled = false }; return
        }
        val stored = AndroidTranscriptNoteStorage(this).all().firstOrNull { it.id == intent.getStringExtra("noteId") }
        if (stored == null) { status.text = "Note introuvable."; actions.forEach { it.isEnabled = false }; return }
        note = stored
        format = runCatching { Format.valueOf(savedInstanceState?.getString("format").orEmpty()) }.getOrDefault(Format.IMAGES)
        val exportRoot = File(cacheDir, "note_exports").canonicalPath + File.separator
        documents = savedInstanceState?.getStringArrayList("documents").orEmpty().map(::File)
            .filter { runCatching { it.isFile && it.canonicalPath.startsWith(exportRoot) }.getOrDefault(false) }
        save.isEnabled = documents.isNotEmpty(); share.isEnabled = documents.isNotEmpty()
        status.text = "${note.title}\n${note.images.size} image(s) · ${note.text.length} caractères"
        if (intent.getBooleanExtra("autoShare", false) && savedInstanceState == null) prepare(Format.IMAGES, ::shareDocument)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("format", format.name)
        outState.putStringArrayList("documents", ArrayList(documents.map { it.absolutePath }))
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        // Restore only the paused note which was visible when this temporary task opened.
        // Leaving the chooser or reader open must not put the editor over the destination app.
        if (intent.getBooleanExtra("restoreNote", false)) {
            intent.putExtra("restoreNote", false)
            runCatching {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_EXPORT_CLOSED)
                    .putExtra("noteId", intent.getStringExtra("noteId")))
            }
        }
        super.finish()
    }

    private fun prepare(next: Format, ready: () -> Unit) {
        if (busy) return
        if (documents.isNotEmpty() && format == next) { ready(); return }
        busy = true
        actions.forEach { it.isEnabled = false }
        status.text = "Préparation ${if (next == Format.IMAGES) "du texte et des images" else "du ${next.name}"}…\nLa note est conservée."
        thread(name = "dictai-note-export") {
            val root = File(cacheDir, "note_exports").apply { mkdirs() }
            root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L * 24 * 3600 * 1000 }?.forEach { it.deleteRecursively() }
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            val started = android.os.SystemClock.elapsedRealtime()
            val result = runCatching {
                val store = NoteImageStore(this)
                if (next == Format.IMAGES) {
                    val text = File(directory, "Note.txt").apply { writeText(NoteShareText.create(note)) }
                    val images = NoteImageMarkers.parts(note).filterIsInstance<NoteImageMarkers.Part.Image>().map { part ->
                        File(directory, "Image-%02d.jpg".format(java.util.Locale.ROOT, part.image.number)).also {
                            NoteImageCopies.write(this, part.image, it)
                        }
                    }
                    listOf(text) + images
                } else {
                    val file = File(directory, "Note.${if (next == Format.HTML) "html" else "pdf"}")
                    file.outputStream().buffered().use { out ->
                        if (next == Format.HTML) NoteHtmlExport.write(note, out) { store.file(it.id).inputStream() }
                        else NotePdfExport.write(note, out, store)
                    }
                    listOf(file)
                }
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            // Metadata only, to measure export cost on a real phone without logging the note.
            android.util.Log.i("DictAI", "event=note_export format=$next images=${note.images.size} elapsed_ms=$elapsed success=${result.isSuccess}")
            runOnUiThread {
                busy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                actions.forEach { it.isEnabled = true }
                if (result.isSuccess) {
                    documents = result.getOrThrow(); format = next
                    val bytes = documents.sumOf { it.length() }
                    status.text = "${note.title}\n${documents.size} fichier(s) · ${"%.1f".format(bytes / 1048576.0)} Mo · prêt en ${elapsed} ms"
                    save.text = if (next == Format.IMAGES) "Enregistrer le texte et les images" else "Enregistrer le ${next.name}"
                    ready()
                } else {
                    directory.deleteRecursively()
                    save.isEnabled = documents.isNotEmpty(); share.isEnabled = documents.isNotEmpty()
                    status.text = "Export impossible. Vérifiez l’espace disponible et les images de la note. Votre note est conservée."
                }
            }
        }
    }


    private fun mime() = when (format) { Format.PDF -> "application/pdf"; Format.HTML -> "text/html"; Format.IMAGES -> "*/*" }
    private fun shareDocument() {
        if (documents.isEmpty() || busy) return
        val uris = ArrayList(documents.map { FileProvider.getUriForFile(this, "$packageName.note_files", it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = mime()
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Note DictAI", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        if (format == Format.IMAGES) {
            val text = NoteShareText.create(note)
            // The full text always travels in Note.txt; extras/clipboard are conveniences with bounded IPC.
            if (text.length <= 80_000) {
                send.putExtra(Intent.EXTRA_TEXT, text)
                DictationClipboard.copy(this, text)
            }
        }
        runCatching { startActivity(Intent.createChooser(send, "Partager la note complète")) }
            .onFailure { toast("Aucune application compatible. Enregistrez les fichiers dans un dossier.") }
    }
    private fun viewPdf() {
        val file = documents.singleOrNull() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.note_files", file)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { toast("PDF prêt. Enregistrez-le pour l’ouvrir avec votre lecteur.") }
    }
    private fun saveDocument() {
        if (documents.isEmpty() || busy) return
        val intent = if (format == Format.IMAGES) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            else Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime())
                .putExtra(Intent.EXTRA_TITLE, "Note DictAI.${if (format == Format.HTML) "html" else "pdf"}")
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, if (format == Format.IMAGES) 3 else 2) }
            .onFailure { toast("Sélecteur de fichiers indisponible.") }
    }
    @Deprecated("Platform activity result for system file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode !in 2..3 || resultCode != RESULT_OK || busy || documents.isEmpty()) return
        busy = true
        val files = documents.toList()
        thread(name = "dictai-note-save") {
            val ok = runCatching {
                if (requestCode == 3) {
                    val directory = DocumentFile.fromTreeUri(this, uri)!!.createDirectory("DictAI-${System.currentTimeMillis()}")!!
                    files.forEach { file ->
                        val target = directory.createFile(if (file.extension == "jpg") "image/jpeg" else "text/plain", file.name)!!
                        contentResolver.openOutputStream(target.uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                    }
                } else contentResolver.openOutputStream(uri, "wt")!!.use { out -> files.single().inputStream().use { it.copyTo(out) } }
            }.isSuccess
            runOnUiThread { busy = false; toast(if (ok) "Document(s) enregistré(s)." else "Enregistrement incomplet : note conservée, réessayez.") }
        }
    }
    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
}
