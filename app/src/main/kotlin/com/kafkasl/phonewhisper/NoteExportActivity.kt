package com.kafkasl.phonewhisper

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Transparent bridge for the final Android save/share pickers.
 * Preparation and previews remain in OverlayService.livePanel.
 */
class NoteExportActivity : Activity() {
    companion object {
        const val EXTRA_BRIDGE_TOKEN = "com.uhama.whisperpin.EXTRA_EXPORT_BRIDGE_TOKEN"
    }

    private lateinit var directory: File
    private lateinit var files: List<File>
    private var format = OverlayExportFormat.PDF
    private var bridgeAction = ""
    private var busy = false
    private var externalStarted = false
    private var pausedOnce = false
    private var bridgeToken: String? = null
    private val restoreGate = OverlayBridgeReturnGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        externalStarted = savedInstanceState?.getBoolean("externalStarted") ?: false
        pausedOnce = savedInstanceState?.getBoolean("pausedOnce") ?: false
        busy = savedInstanceState?.getBoolean("busy") ?: false
        bridgeToken = savedInstanceState?.getString("bridgeToken") ?: intent.getStringExtra(EXTRA_BRIDGE_TOKEN)
        if (savedInstanceState?.getBoolean("pendingOverlayRestore") == true) restoreGate.request()
        bridgeAction = intent.getStringExtra("bridgeAction").orEmpty()
        directory = validatedDirectory(intent.getStringExtra("exportDirectory")) ?: run {
            toast("Export introuvable. La note est conservée.")
            requestOverlayRestore()
            return
        }
        format = runCatching {
            OverlayExportFormat.valueOf(intent.getStringExtra("exportFormat").orEmpty())
        }.getOrDefault(OverlayExportFormat.PDF)
        files = directory.listFiles()
            ?.filter { file -> runCatching { file.isFile && file.canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            toast("Export vide. La note est conservée.")
            requestOverlayRestore()
            return
        }
        if (savedInstanceState?.getBoolean("busy") == true) {
            toast("Export interrompu. La note est conservée ; réessayez depuis l’aperçu.")
            requestOverlayRestore()
            return
        }
        if (savedInstanceState == null) when (bridgeAction) {
            "share" -> share()
            "save" -> openSavePicker()
            else -> requestOverlayRestore()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("externalStarted", externalStarted)
        outState.putBoolean("pausedOnce", pausedOnce)
        outState.putBoolean("busy", busy)
        outState.putString("bridgeToken", bridgeToken)
        outState.putBoolean("pendingOverlayRestore", restoreGate.isPending())
        super.onSaveInstanceState(outState)
    }

    private fun validatedDirectory(path: String?): File? = runCatching {
        val root = File(cacheDir, "note_exports").canonicalFile
        val candidate = File(path ?: return@runCatching null).canonicalFile
        if (!candidate.isDirectory || !candidate.path.startsWith(root.path + File.separator)) return@runCatching null
        candidate
    }.getOrNull()

    private fun share() {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.note_files", it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = format.mimeType
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Note DictAI", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        if (format == OverlayExportFormat.TEXT_IMAGES) intent.getStringExtra("noteText")?.takeIf { it.isNotBlank() }?.let { text ->
            if (text.length <= 80_000) {
                send.putExtra(Intent.EXTRA_TEXT, text)
                DictationClipboard.copy(this, text)
            }
        }
        runCatching {
            externalStarted = true
            startActivity(Intent.createChooser(send, "Partager la note complète"))
        }.onFailure {
            externalStarted = false
            requestOverlayRestore()
            toast("Aucune application compatible. Enregistrez le document.")
            maybeRestoreAndFinish()
        }
    }

    private fun openSavePicker() {
        val picker = if (format == OverlayExportFormat.TEXT_IMAGES) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        else Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_TITLE, "Note DictAI.${format.extension}")
        @Suppress("DEPRECATION")
        runCatching {
            externalStarted = true
            startActivityForResult(picker, if (format == OverlayExportFormat.TEXT_IMAGES) 3 else 2)
        }.onFailure { toast("Sélecteur de fichiers indisponible."); requestOverlayRestore(); maybeRestoreAndFinish() }
    }

    override fun onPause() {
        if (externalStarted) pausedOnce = true
        restoreGate.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        restoreGate.onResume()
        if (bridgeAction == "share" && externalStarted && pausedOnce && !isFinishing) {
            externalStarted = false
            requestOverlayRestore()
        }
        maybeRestoreAndFinish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        restoreGate.onWindowFocusChanged(hasFocus)
        maybeRestoreAndFinish()
    }

    @Deprecated("Platform activity result for the system file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (uri == null || resultCode != RESULT_OK || requestCode !in 2..3) {
            externalStarted = false
            requestOverlayRestore()
            maybeRestoreAndFinish()
            return
        }
        if (busy) return
        externalStarted = false
        busy = true
        val outgoing = files.toList()
        thread(name = "dictai-note-save") {
            val ok = runCatching {
                if (requestCode == 3) {
                    val tree = DocumentFile.fromTreeUri(this, uri) ?: error("Dossier indisponible")
                    val destination = tree.createDirectory("DictAI-${UUID.randomUUID()}") ?: error("Dossier indisponible")
                    outgoing.forEach { file ->
                        val target = destination.createFile(mimeFor(file), file.name) ?: error("Fichier indisponible")
                        contentResolver.openOutputStream(target.uri, "wt")!!.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                    }
                } else {
                    val target = contentResolver.openOutputStream(uri, "wt") ?: error("Document indisponible")
                    target.use { out -> outgoing.single().inputStream().use { input -> input.copyTo(out) } }
                }
            }.isSuccess
            runOnUiThread {
                busy = false
                toast(if (ok) "Document(s) enregistré(s)." else "Enregistrement incomplet : note conservée, réessayez.")
                requestOverlayRestore()
                maybeRestoreAndFinish()
            }
        }
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "html" -> "text/html"
        "pdf" -> "application/pdf"
        else -> "text/plain"
    }

    private fun requestOverlayRestore() {
        restoreGate.request()
        maybeRestoreAndFinish()
    }

    private fun maybeRestoreAndFinish() {
        if (isFinishing || !restoreGate.consumeIfReady()) return
        bridgeToken?.let { token ->
            runCatching {
                startService(
                    Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_EXPORT_BRIDGE_FOREGROUND)
                        .putExtra(OverlayService.EXTRA_EXPORT_BRIDGE_TOKEN, token),
                )
            }
        }
        finish()
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
}
