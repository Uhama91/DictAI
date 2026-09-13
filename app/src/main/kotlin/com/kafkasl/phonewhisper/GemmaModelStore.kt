package com.kafkasl.phonewhisper

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class GemmaModelArtifact(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal sealed class GemmaInstallState {
    data class Downloading(val bytes: Long, val total: Long) : GemmaInstallState()
    data class Verifying(val bytes: Long, val total: Long) : GemmaInstallState()
    data class Installed(val file: File) : GemmaInstallState()
    data object Paused : GemmaInstallState()
    data class Error(val message: String) : GemmaInstallState()
}

/** Cancel immediately without joining a download or waiting for a socket timeout. */
internal class GemmaDownloadCancellation {
    private val cancelled = AtomicBoolean()
    private val call = AtomicReference<Call?>()
    fun cancel() { cancelled.set(true); call.get()?.cancel() }
    internal fun isCancelled() = cancelled.get()
    internal fun check() { if (isCancelled()) throw DownloadPaused() }
    internal fun attach(value: Call) {
        call.set(value)
        if (isCancelled()) value.cancel()
    }
    internal fun detach(value: Call) { call.compareAndSet(value, null) }
}

private class DownloadPaused : IOException()
private class InstallationFailure(val userMessage: String) : IOException(userMessage)

/**
 * The final directory is visible only after a size/SHA-256 check and atomic publication.
 * installedModel() checks a tiny integrity receipt, size and mtime; it never hashes model bytes.
 * download() is blocking and must run on a worker. Partial files survive app/process restarts.
 */
internal class GemmaModelStore internal constructor(
    private val root: File,
    private val artifact: GemmaModelArtifact = ARTIFACT,
    private val client: OkHttpClient = HTTP_CLIENT,
) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "gemma-format"))

    private val finalDir get() = File(root, "installed-${artifact.sha256.take(16)}")
    private val workspace get() = File(root, "download-${artifact.sha256.take(16)}")
    private val partFile get() = File(workspace, "model.part")
    private val resumeFile get() = File(workspace, "resume")
    private val staging get() = File(workspace, "staging")

    fun installedModel(): File? {
        val file = File(finalDir, artifact.fileName)
        if (!file.isFile || file.length() != artifact.sizeBytes) return null
        return try {
            val receipt = File(finalDir, "integrity")
            if (!receipt.isFile || receipt.length() > 512L) return null
            DataInputStream(BufferedInputStream(FileInputStream(receipt))).use { input ->
                file.takeIf {
                    input.readUTF() == INTEGRITY_MAGIC && input.readUTF() == artifact.sha256 &&
                        input.readLong() == artifact.sizeBytes && input.readLong() == file.lastModified() &&
                        input.read() == -1
                }
            }
        } catch (_: IOException) { null }
    }

    /** A read-only status hint. A resumable transfer additionally needs a valid server ETag. */
    fun partialSizeBytes(): Long = partFile.length().coerceIn(0L, artifact.sizeBytes)

    fun download(cancellation: GemmaDownloadCancellation, onState: (GemmaInstallState) -> Unit) {
        val key = root.absolutePath
        if (!ACTIVE.add(key)) {
            onState(GemmaInstallState.Error("L’installation de Gemma est déjà en cours."))
            return
        }
        try {
            cancellation.check()
            installedModel()?.let { onState(GemmaInstallState.Installed(it)); return }
            if (!workspace.isDirectory && !workspace.mkdirs()) fail("Impossible de préparer le stockage de Gemma.")
            // Recover a verified-but-unpublished file after a process interruption by verifying it anew.
            val stagedFile = File(staging, artifact.fileName)
            if (!partFile.exists() && stagedFile.isFile && stagedFile.length() == artifact.sizeBytes) {
                if (!stagedFile.renameTo(partFile)) fail("Impossible de reprendre l’installation de Gemma.")
            }
            if (partFile.length() > artifact.sizeBytes) clearPartial()
            if (partFile.length() != artifact.sizeBytes) {
                val remaining = artifact.sizeBytes - partFile.length()
                if (root.usableSpace < remaining + STORAGE_RESERVE_BYTES) {
                    fail("Espace insuffisant : libérez au moins ${formatBytes(remaining + STORAGE_RESERVE_BYTES)} pour terminer l’installation.")
                }
                transfer(cancellation, onState)
            }
            verify(cancellation, onState)
            cancellation.check()
            val installed = publish()
            onState(GemmaInstallState.Installed(installed))
        } catch (_: DownloadPaused) {
            onState(GemmaInstallState.Paused)
        } catch (error: IOException) {
            onState(if (cancellation.isCancelled()) GemmaInstallState.Paused else GemmaInstallState.Error(
                (error as? InstallationFailure)?.userMessage
                    ?: "Téléchargement interrompu. Vérifiez la connexion et l’espace disponible, puis touchez Reprendre."
            ))
        } catch (_: SecurityException) {
            onState(GemmaInstallState.Error("L’application ne peut pas accéder au stockage du modèle."))
        } finally {
            ACTIVE.remove(key)
        }
    }

    private fun transfer(cancellation: GemmaDownloadCancellation, onState: (GemmaInstallState) -> Unit) {
        var previousTag = readResumeTag()
        var offset = partFile.length()
        if (offset > 0L && previousTag == null) { clearPartial(); offset = 0L }
        if (offset == 0L) previousTag = null
        val request = Request.Builder().url(artifact.url).header("Accept-Encoding", "identity").apply {
            if (offset > 0L) {
                header("Range", "bytes=$offset-")
                header("If-Range", checkNotNull(previousTag))
            }
        }.build()
        val call = client.newCall(request)
        cancellation.attach(call)
        try {
            call.execute().use { response ->
                cancellation.check()
                if (response.code != 200 && response.code != 206) {
                    fail(when (response.code) {
                        401, 403 -> "Le serveur ne permet pas ce téléchargement pour le moment. Réessayez plus tard."
                        404 -> "Le modèle Gemma est indisponible sur le serveur."
                        416 -> {
                            clearPartial()
                            "La reprise n’est plus disponible. Touchez Reprendre pour télécharger une nouvelle copie."
                        }
                        else -> "Le serveur de téléchargement est indisponible (HTTP ${response.code}). Réessayez."
                    })
                }
                if (response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } == true) {
                    fail("Réponse de téléchargement inattendue. Réessayez.")
                }
                val body = response.body ?: fail("Le serveur a envoyé une réponse vide.")
                val tag = response.header("ETag")?.takeIf(::isStrongTag)
                val append = response.code == 206
                var responseBytes = artifact.sizeBytes
                if (append) {
                    val range = parseContentRange(response.header("Content-Range"))
                    if (offset <= 0L || range == null || range.first != offset ||
                        range.total != artifact.sizeBytes || range.last != artifact.sizeBytes - 1L ||
                        tag == null || tag != previousTag
                    ) {
                        clearPartial()
                        fail("Le serveur n’a pas confirmé la reprise. Touchez Reprendre pour télécharger une nouvelle copie.")
                    }
                    responseBytes = range.last - range.first + 1L
                } else {
                    // The server may legitimately ignore Range / reject If-Range and send a full 200.
                    offset = 0L
                }
                val available = root.usableSpace + if (append) 0L else partFile.length()
                if (available < responseBytes + STORAGE_RESERVE_BYTES) {
                    fail("Espace insuffisant pour terminer l’installation de Gemma. Libérez de l’espace puis touchez Reprendre.")
                }
                val length = body.contentLength()
                if (length >= 0L && length != responseBytes) {
                    fail("La taille annoncée par le serveur ne correspond pas au modèle Gemma.")
                }
                // Truncate before changing the resume receipt so an interruption cannot pair old bytes
                // with a new ETag. A missing/weak ETag permits this transfer but disables later resume.
                FileOutputStream(partFile, append).use { output ->
                    writeResumeTag(tag)
                    var received = 0L
                    var lastPercent = -1
                    fun progress() {
                        val bytes = offset + received
                        val percent = (100L * bytes / artifact.sizeBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onState(GemmaInstallState.Downloading(bytes, artifact.sizeBytes))
                        }
                    }
                    progress()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            cancellation.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (received + count > responseBytes) {
                                clearResumeReceipt()
                                fail("Le serveur a envoyé un fichier trop volumineux. Réessayez.")
                            }
                            output.write(buffer, 0, count)
                            received += count
                            progress()
                        }
                    }
                    output.fd.sync()
                    cancellation.check()
                    if (received != responseBytes || partFile.length() != artifact.sizeBytes) {
                        fail("Téléchargement incomplet. Touchez Reprendre pour continuer.")
                    }
                }
            }
        } finally {
            cancellation.detach(call)
        }
    }

    private fun verify(cancellation: GemmaDownloadCancellation, onState: (GemmaInstallState) -> Unit) {
        if (partFile.length() != artifact.sizeBytes) fail("Le fichier Gemma est incomplet. Touchez Reprendre.")
        val digest = MessageDigest.getInstance("SHA-256")
        var checked = 0L
        var lastPercent = -1
        FileInputStream(partFile).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                cancellation.check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                checked += count
                val percent = (100L * checked / artifact.sizeBytes).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onState(GemmaInstallState.Verifying(checked, artifact.sizeBytes))
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != artifact.sha256) {
            clearPartial()
            fail("La vérification du fichier a échoué. Touchez Reprendre pour télécharger une nouvelle copie.")
        }
    }

    private fun publish(): File {
        if (!staging.isDirectory && !staging.mkdirs()) fail("Impossible de finaliser l’installation de Gemma.")
        val model = File(staging, artifact.fileName)
        if (model.exists() && !model.delete()) fail("Impossible de finaliser l’installation de Gemma.")
        if (!partFile.renameTo(model)) fail("Impossible de finaliser l’installation de Gemma.")
        FileOutputStream(File(staging, "integrity")).use { output ->
            DataOutputStream(output).apply {
                writeUTF(INTEGRITY_MAGIC)
                writeUTF(artifact.sha256)
                writeLong(artifact.sizeBytes)
                writeLong(model.lastModified())
                flush()
            }
            output.fd.sync()
        }
        if (finalDir.exists() && !finalDir.deleteRecursively()) fail("Impossible de remplacer l’installation incomplète de Gemma.")
        if (!staging.renameTo(finalDir)) fail("Impossible de publier le modèle Gemma.")
        val installed = installedModel() ?: fail("La validation de l’installation a échoué.")
        workspace.deleteRecursively()
        return installed
    }

    private fun readResumeTag(): String? = try {
        if (!resumeFile.isFile || resumeFile.length() > 4096L) null else
            DataInputStream(BufferedInputStream(FileInputStream(resumeFile))).use { input ->
                if (input.readUTF() != RESUME_MAGIC || input.readUTF() != artifact.sha256 ||
                    input.readLong() != artifact.sizeBytes) null else input.readUTF().takeIf(::isStrongTag)
            }
    } catch (_: IOException) { null }

    private fun writeResumeTag(tag: String?) {
        clearResumeReceipt()
        if (tag == null) return
        val pending = File(workspace, "resume.part")
        FileOutputStream(pending).use { output ->
            DataOutputStream(output).apply {
                writeUTF(RESUME_MAGIC); writeUTF(artifact.sha256); writeLong(artifact.sizeBytes); writeUTF(tag); flush()
            }
            output.fd.sync()
        }
        if (!pending.renameTo(resumeFile)) fail("Impossible d’enregistrer la reprise du téléchargement.")
    }

    private fun clearResumeReceipt() {
        if (resumeFile.exists() && !resumeFile.delete()) fail("Impossible de réinitialiser la reprise du téléchargement.")
    }

    private fun clearPartial() {
        clearResumeReceipt()
        if (partFile.exists() && !partFile.delete()) fail("Impossible de réinitialiser le téléchargement incomplet.")
    }

    companion object {
        const val MODEL_TITLE = "Gemma 4 E2B (FR/EN)"
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val EXPECTED_SIZE_BYTES = 2_588_147_712L
        const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val MODEL_REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
        val ARTIFACT = GemmaModelArtifact(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$MODEL_REVISION/$MODEL_FILE",
            MODEL_FILE, EXPECTED_SIZE_BYTES, MODEL_SHA256,
        )
        private const val INTEGRITY_MAGIC = "dictai-gemma-integrity-v1"
        private const val RESUME_MAGIC = "dictai-gemma-resume-v1"
        private const val BUFFER_BYTES = 64 * 1024
        private const val STORAGE_RESERVE_BYTES = 64L * 1024 * 1024
        private val ACTIVE = ConcurrentHashMap.newKeySet<String>()
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.HOURS)
            .build()
        private fun fail(message: String): Nothing = throw InstallationFailure(message)
        private fun isStrongTag(tag: String) = tag.length in 2..1024 && tag.startsWith('"') && tag.endsWith('"') &&
            tag.none { it == '\n' || it == '\r' }
        fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000L)
            String.format(java.util.Locale.FRANCE, "%.2f Go", bytes / 1_000_000_000.0)
        else "${bytes / 1_000_000L} Mo"
    }
}

private data class GemmaContentRange(val first: Long, val last: Long, val total: Long)
private fun parseContentRange(value: String?): GemmaContentRange? {
    val parts = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty())?.groupValues ?: return null
    val start = parts[1].toLongOrNull() ?: return null
    val end = parts[2].toLongOrNull() ?: return null
    val total = parts[3].toLongOrNull() ?: return null
    return GemmaContentRange(start, end, total).takeIf { start <= end && end < total }
}
