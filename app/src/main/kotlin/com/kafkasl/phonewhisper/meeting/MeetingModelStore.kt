package com.kafkasl.phonewhisper.meeting

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Worker-published state for the complete pair of meeting models. */
sealed interface MeetingModelStoreState {
    data object Missing : MeetingModelStoreState
    data object Checking : MeetingModelStoreState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : MeetingModelStoreState
    data class Ready(val paths: MeetingModelPaths) : MeetingModelStoreState
    data class Error(val message: String) : MeetingModelStoreState

    companion object {
        const val DOWNLOAD_ERROR_MESSAGE = "Téléchargement des modèles Réunion impossible"
        const val INSUFFICIENT_SPACE_MESSAGE = "Espace insuffisant pour les modèles Réunion"
    }
}

data class MeetingModelPaths(
    val packageDirectory: File,
    val asrFile: File,
    val diarizationFile: File,
) {
    val asrPath: String get() = asrFile.absolutePath
    val diarizationPath: String get() = diarizationFile.absolutePath
}

/**
 * Shared private-files store for the pair of meeting models.
 *
 * State listeners run on this store's single worker, outside its state lock. UI owners should
 * post callbacks to the main thread and remove listeners when destroyed. Listener exceptions
 * are isolated. Repeated refreshes during an operation are ignored; download during a refresh
 * requests a download after inspection, while download during a download is ignored. A refresh
 * during a download is ignored. Cancellation invalidates a queued/active download and closes
 * its current OkHttp call; a later download should be issued after the terminal state arrives.
 */
class MeetingModelStore internal constructor(
    private val filesDirectory: File,
    internal val catalog: MeetingModelCatalog = MeetingModelCatalog.production,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val spaceMarginBytes: Long = DEFAULT_SPACE_MARGIN_BYTES,
    private val publishDirectory: (File, File) -> Unit = { source, destination ->
        val parent = destination.parentFile ?: throw IOException("package path unavailable")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("package path unavailable")
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "meeting-model-store").apply { isDaemon = true }
    },
) {
    private val stateLock = Any()
    private val listeners = mutableListOf<ListenerRegistration>()
    private var operation: Operation? = null

    @Volatile
    var currentState: MeetingModelStoreState = MeetingModelStoreState.Missing
        private set

    init {
        require(spaceMarginBytes >= 0L)
    }

    /** Registers a callback whose initial snapshot is also delivered on the store worker. */
    fun addListener(listener: (MeetingModelStoreState) -> Unit) {
        val registration = synchronized(stateLock) {
            listeners.firstOrNull { it.listener === listener } ?: ListenerRegistration(listener).also(listeners::add)
        }
        try {
            worker.execute {
                deliverCallbacks(listOf(registration), currentState, operationForSnapshot(), completed = false)
            }
        } catch (_: RuntimeException) {
            registration.active.set(false)
        }
    }

    /** Removes a listener and suppresses callbacks that have not already entered it. */
    fun removeListener(listener: (MeetingModelStoreState) -> Unit) {
        synchronized(stateLock) {
            listeners.filter { it.listener === listener }.forEach {
                it.active.set(false)
                listeners.remove(it)
            }
        }
    }

    /** Inspects the immutable package on the worker. A repeated request never starts a second operation. */
    fun refresh() = requestOperation(downloadRequested = false)

    /** Inspects first, then downloads both files if no complete verified package exists. */
    fun download() = requestOperation(downloadRequested = true)

    /** Cancels only a queued or active download; the worker owns rollback and temporary cleanup. */
    fun cancelDownload() {
        val callToCancel = synchronized(stateLock) {
            val active = operation ?: return
            if ((!active.downloadRequested && !active.downloadStarted) || active.cancelled) return
            active.cancelled = true
            active.activeCall
        }
        callToCancel?.cancel()
    }

    private fun requestOperation(downloadRequested: Boolean) {
        val toStart = synchronized(stateLock) {
            val active = operation
            if (active != null) {
                if (downloadRequested && !active.cancelled) active.downloadRequested = true
                return
            }
            Operation(UUID.randomUUID().toString()).also {
                it.downloadRequested = downloadRequested
                operation = it
            }
        }
        try {
            worker.execute { runOperation(toStart) }
        } catch (_: RuntimeException) {
            completeOperation(toStart, MeetingModelStoreState.Error(MeetingModelStoreState.DOWNLOAD_ERROR_MESSAGE))
        }
    }

    private fun runOperation(active: Operation) {
        var stagingDirectory: File? = null
        var terminalAfterCleanup: MeetingModelStoreState? = null
        try {
            checkNotCancelled(active)
            updateState(active, MeetingModelStoreState.Checking)
            checkNotCancelled(active)

            val installed = findValidPackage()
            if (!resolveInspection(active, installed)) return

            checkNotCancelled(active)
            if (!filesDirectory.exists() && !filesDirectory.mkdirs()) throw IOException("directory unavailable")
            if (!filesDirectory.isDirectory) throw IOException("directory unavailable")
            val requiredSpace = Math.addExact(catalog.totalBytes, spaceMarginBytes)
            if (availableBytes(filesDirectory) < requiredSpace) throw InsufficientSpaceException()

            stagingDirectory = File(filesDirectory, ".meeting-models-op-${active.id}")
            if (!stagingDirectory.mkdir()) throw IOException("staging unavailable")
            downloadAndPublish(active, stagingDirectory)
        } catch (_: CancellationException) {
            terminalAfterCleanup = MeetingModelStoreState.Missing
        } catch (_: InsufficientSpaceException) {
            terminalAfterCleanup = if (isCancelled(active)) {
                MeetingModelStoreState.Missing
            } else {
                MeetingModelStoreState.Error(MeetingModelStoreState.INSUFFICIENT_SPACE_MESSAGE)
            }
        } catch (_: Throwable) {
            terminalAfterCleanup = if (isCancelled(active)) {
                MeetingModelStoreState.Missing
            } else {
                MeetingModelStoreState.Error(MeetingModelStoreState.DOWNLOAD_ERROR_MESSAGE)
            }
        } finally {
            synchronized(stateLock) { active.activeCall = null }
            try {
                stagingDirectory?.takeIf(File::exists)?.deleteRecursively()
            } catch (_: Throwable) {
                // Cleanup is limited to this operation's private staging directory.
            }
            terminalAfterCleanup?.let { completeOperation(active, it) }
        }
    }

    /** Decides atomically whether inspection finishes or an explicit download request follows. */
    private fun resolveInspection(active: Operation, installed: MeetingModelPaths?): Boolean {
        var shouldDownload = false
        var terminal: MeetingModelStoreState? = null
        var callbackSnapshot: List<ListenerRegistration> = emptyList()
        synchronized(stateLock) {
            if (operation !== active) return false
            when {
                active.cancelled -> terminal = MeetingModelStoreState.Missing
                installed != null -> terminal = MeetingModelStoreState.Ready(installed)
                active.downloadRequested -> {
                    active.downloadStarted = true
                    shouldDownload = true
                }
                else -> terminal = MeetingModelStoreState.Missing
            }
            if (terminal != null) {
                currentState = terminal!!
                operation = null
                callbackSnapshot = listeners.toList()
            }
        }
        terminal?.let { deliverCallbacks(callbackSnapshot, it, active, completed = true) }
        return shouldDownload
    }

    private fun downloadAndPublish(active: Operation, stagingDirectory: File) {
        checkNotCancelled(active)
        publishState(active, MeetingModelStoreState.Downloading(0L, catalog.totalBytes), forceProgress = true)
        var downloadedBytes = 0L

        for (artifact in catalog.artifacts) {
            checkNotCancelled(active)
            downloadedBytes = downloadArtifact(active, stagingDirectory, artifact, downloadedBytes)
        }

        checkNotCancelled(active)
        publishState(active, MeetingModelStoreState.Downloading(catalog.totalBytes, catalog.totalBytes), forceProgress = true)
        checkNotCancelled(active)
        publishDirectory(stagingDirectory, File(filesDirectory, catalog.packageDirectoryName(active.id)))
        publishAndComplete(active, File(filesDirectory, catalog.packageDirectoryName(active.id)))
    }

    /** A complete renamed package survives cancellation; a later refresh may verify and adopt it. */
    private fun publishAndComplete(active: Operation, finalDirectory: File) {
        val (ready, callbackSnapshot) = synchronized(stateLock) {
            if (operation !== active || active.cancelled) throw CancellationException()
            val asrFile = resolveArtifactPath(finalDirectory, catalog.asr.relativePath)
            val diarizationFile = resolveArtifactPath(finalDirectory, catalog.diarization.relativePath)
            if (!asrFile.isFile || asrFile.length() != catalog.asr.sizeBytes ||
                !diarizationFile.isFile || diarizationFile.length() != catalog.diarization.sizeBytes
            ) {
                throw IOException("published package is incomplete")
            }
            val state = MeetingModelStoreState.Ready(MeetingModelPaths(finalDirectory, asrFile, diarizationFile))
            currentState = state
            operation = null
            state to listeners.toList()
        }
        deliverCallbacks(callbackSnapshot, ready, active, completed = true)
    }

    private fun downloadArtifact(
        active: Operation,
        stagingDirectory: File,
        artifact: MeetingModelArtifact,
        totalDownloadedBefore: Long,
    ): Long {
        val target = resolveArtifactPath(stagingDirectory, artifact.relativePath)
        val parent = target.parentFile ?: throw IOException("invalid model path")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("staging unavailable")
        val partFile = File(parent, target.name + ".part")
        val request = Request.Builder().url(artifact.url).get().build()
        val call = httpClient.newCall(request)
        synchronized(stateLock) {
            if (operation !== active || active.cancelled) {
                call.cancel()
                throw CancellationException()
            }
            active.activeCall = call
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("model request failed")
                val body = response.body ?: throw IOException("empty response")
                if (body.contentLength() > artifact.sizeBytes) throw IOException("model response too large")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var totalDownloaded = totalDownloadedBefore

                FileOutputStream(partFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkNotCancelled(active)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (count.toLong() > artifact.sizeBytes - received) {
                                throw IOException("model response too large")
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            received += count
                            totalDownloaded += count

                            publishState(
                                active,
                                MeetingModelStoreState.Downloading(totalDownloaded, catalog.totalBytes),
                            )
                        }
                    }
                    output.fd.sync()
                }

                if (received != artifact.sizeBytes) throw IOException("model response truncated")
                if (!digest.digest().toHex().equals(artifact.sha256, ignoreCase = true)) {
                    throw IOException("model SHA-256 mismatch")
                }
                Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return totalDownloaded
            }
        } finally {
            synchronized(stateLock) {
                if (active.activeCall === call) active.activeCall = null
            }
        }
    }

    private fun findValidPackage(): MeetingModelPaths? {
        if (!filesDirectory.exists()) return null
        if (!filesDirectory.isDirectory) throw IOException("model directory unavailable")
        val directories = filesDirectory.listFiles() ?: throw IOException("model directory unavailable")
        return directories.asSequence()
            .filter { it.isDirectory && catalog.isPackageDirectoryName(it.name) }
            .sortedByDescending(File::lastModified)
            .mapNotNull(::inspectPackage)
            .firstOrNull()
    }

    private fun inspectPackage(directory: File): MeetingModelPaths? {
        if (!directory.isDirectory || !catalog.isPackageDirectoryName(directory.name)) return null
        val asrFile = resolveArtifactPath(directory, catalog.asr.relativePath)
        val diarizationFile = resolveArtifactPath(directory, catalog.diarization.relativePath)
        if (!isValidFile(asrFile, catalog.asr) || !isValidFile(diarizationFile, catalog.diarization)) return null
        return MeetingModelPaths(directory, asrFile, diarizationFile)
    }

    private fun isValidFile(file: File, artifact: MeetingModelArtifact): Boolean {
        if (!file.isFile || file.length() != artifact.sizeBytes) return false
        return hashFile(file).equals(artifact.sha256, ignoreCase = true)
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun resolveArtifactPath(directory: File, relativePath: String): File {
        val basePath = directory.canonicalFile.toPath()
        val file = File(directory, relativePath)
        if (!file.canonicalFile.toPath().startsWith(basePath)) throw IOException("unsafe model path")
        return file
    }

    private fun updateState(active: Operation, next: MeetingModelStoreState) {
        val callbackSnapshot = synchronized(stateLock) {
            if (operation !== active || active.cancelled) return
            currentState = next
            listeners.toList()
        }
        deliverCallbacks(callbackSnapshot, next, active, completed = false)
    }

    private fun publishState(
        active: Operation,
        next: MeetingModelStoreState,
        forceProgress: Boolean = false,
    ) {
        if (next is MeetingModelStoreState.Downloading) {
            val now = System.nanoTime()
            if (!forceProgress && now - active.lastProgressAtNanos < PROGRESS_INTERVAL_NANOS) return
            active.lastProgressAtNanos = now
        }
        updateState(active, next)
    }

    private fun completeOperation(active: Operation, terminal: MeetingModelStoreState) {
        val (finalState, callbackSnapshot) = synchronized(stateLock) {
            if (operation !== active) return
            val resolved = if (active.cancelled) MeetingModelStoreState.Missing else terminal
            currentState = resolved
            operation = null
            resolved to listeners.toList()
        }
        deliverCallbacks(callbackSnapshot, finalState, active, completed = true)
    }

    private fun deliverCallbacks(
        callbackSnapshot: List<ListenerRegistration>,
        state: MeetingModelStoreState,
        originatingOperation: Operation?,
        completed: Boolean,
    ) {
        callbackSnapshot.forEach { registration ->
            val admitted = synchronized(stateLock) {
                registration.active.get() && currentState === state && when {
                    originatingOperation == null -> operation?.cancelled != true
                    operation === originatingOperation -> !originatingOperation.cancelled
                    completed -> operation !== originatingOperation
                    else -> false
                }
            }
            if (admitted) {
                try {
                    registration.listener(state)
                } catch (_: Throwable) {
                    // A consumer callback must not fail or block the store's state transition.
                }
            }
        }
    }

    private fun operationForSnapshot(): Operation? = synchronized(stateLock) { operation }

    private fun checkNotCancelled(active: Operation) {
        if (isCancelled(active)) throw CancellationException()
    }

    private fun isCancelled(active: Operation): Boolean = synchronized(stateLock) {
        operation !== active || active.cancelled
    }

    internal fun shutdownForTests() {
        cancelDownload()
        worker.shutdownNow()
    }

    private class Operation(val id: String) {
        var downloadRequested = false
        var downloadStarted = false
        var cancelled = false
        var activeCall: Call? = null
        var lastProgressAtNanos: Long = 0L
    }

    private class ListenerRegistration(val listener: (MeetingModelStoreState) -> Unit) {
        val active = AtomicBoolean(true)
    }

    private class InsufficientSpaceException : IOException()

    companion object {
        private const val DEFAULT_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        private val sharedStores = ConcurrentHashMap<String, MeetingModelStore>()

        /** Uses only the application files directory and never retains an Activity context. */
        @JvmStatic
        fun shared(context: Context): MeetingModelStore {
            val root = File(context.applicationContext.filesDir, "meeting-models")
            return sharedStores.computeIfAbsent(root.absolutePath) { MeetingModelStore(filesDirectory = root) }
        }

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
