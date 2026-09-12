package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Export choices offered inside the live overlay. */
internal enum class OverlayExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    PDF("PDF", "pdf", "application/pdf"),
    HTML("HTML", "html", "text/html"),
    TEXT_IMAGES("Texte et images", "txt", "*/*"),
}

internal data class OverlayExportResult(
    val noteId: String,
    val format: OverlayExportFormat,
    val directory: File,
    val files: List<File>,
) {
    val primaryFile: File get() = files.first()
}

/**
 * A small generation gate prevents a slow PDF/HTML worker from publishing into a
 * newer note or a closed overlay. The class is deliberately independent of views
 * so the stale-result rule can be tested without an Android window.
 */
internal class ExportGenerationGate {
    private var generation = 0L

    @Synchronized fun next(): Long {
        generation += 1
        return generation
    }

    @Synchronized fun invalidate() { generation += 1 }

    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
}

/** Restores the overlay only after the transparent bridge is truly foreground. */
internal class OverlayBridgeReturnGate {
    private var resumed = false
    private var windowFocused = false
    private var pending = false

    fun onResume() { resumed = true }
    fun onPause() { resumed = false }
    fun onWindowFocusChanged(hasFocus: Boolean) { windowFocused = hasFocus }
    fun request() { pending = true }
    fun consumeIfReady(): Boolean {
        if (!pending || !resumed || !windowFocused) return false
        pending = false
        return true
    }
    fun isPending(): Boolean = pending
}

/** Generates local note files off the main thread for the embedded export panel. */
internal class OverlayExportController(
    private val context: Context,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictai-overlay-export").apply { isDaemon = true }
    }
    private val gate = ExportGenerationGate()
    @Volatile private var closed = false

    fun prepare(
        note: TranscriptNote,
        format: OverlayExportFormat,
        callback: (Result<OverlayExportResult>) -> Unit,
    ): Long {
        val token = gate.next()
        executor.execute {
            val result = runCatching { generate(note, format) }
            if (closed || !gate.isCurrent(token)) {
                result.getOrNull()?.directory?.deleteRecursively()
                return@execute
            }
            main.post {
                if (!closed && gate.isCurrent(token)) callback(result)
                else result.getOrNull()?.directory?.deleteRecursively()
            }
        }
        return token
    }

    fun invalidate() { gate.invalidate() }

    fun close() {
        closed = true
        gate.invalidate()
        executor.shutdownNow()
    }

    private fun generate(note: TranscriptNote, format: OverlayExportFormat): OverlayExportResult {
        val root = File(context.cacheDir, "note_exports").apply { mkdirs() }
        root.listFiles()
            ?.filter { it.isDirectory && System.currentTimeMillis() - it.lastModified() > 7L * 24 * 3600 * 1000 }
            ?.forEach { it.deleteRecursively() }
        val directory = File(root, "overlay-${UUID.randomUUID()}").apply { mkdirs() }
        val store = NoteImageStore(context)
        return try {
            val files = when (format) {
                OverlayExportFormat.PDF -> listOf(File(directory, "Note.pdf").also { file ->
                    file.outputStream().buffered().use { out -> NotePdfExport.write(note, out, store) }
                })
                OverlayExportFormat.HTML -> listOf(File(directory, "Note.html").also { file ->
                    file.outputStream().buffered().use { out ->
                        NoteHtmlExport.write(note, out) { store.file(it.id).inputStream() }
                    }
                })
                OverlayExportFormat.TEXT_IMAGES -> {
                    val text = File(directory, "Note.txt").apply { writeText(NoteShareText.create(note)) }
                    val images = NoteImageMarkers.parts(note)
                        .filterIsInstance<NoteImageMarkers.Part.Image>()
                        .map { part ->
                            File(directory, "Image-%02d.jpg".format(java.util.Locale.ROOT, part.image.number)).also {
                                NoteImageCopies.write(context, part.image, it)
                            }
                        }
                    listOf(text) + images
                }
            }
            OverlayExportResult(note.id, format, directory, files)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }
}
