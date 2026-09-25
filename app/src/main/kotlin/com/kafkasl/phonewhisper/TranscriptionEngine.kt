package com.kafkasl.phonewhisper

import android.content.Context
import java.io.Closeable

/** Keeps exactly one native transcription engine resident and closes it before a replacement opens. */
internal class ResidentEngine<T : Closeable>(
    private val coordinator: TranscriptionModeCoordinator? = null,
) : Closeable {
    /** Serializes native operations; brief reference reads and writes use stateLock. */
    private val operationLock = Any()
    private val stateLock = Any()
    private var modelName: String? = null
    private var engine: T? = null
    private var registration: DictationResidentRegistration? = null
    private var closeUncertain = false

    fun isLoaded(model: String): Boolean = synchronized(stateLock) {
        !closeUncertain && modelName == model && engine != null
    }

    fun replace(model: String, open: () -> T?): T? = replace(model, null, open)

    fun replace(model: String, runLease: TranscriptionRunLease?, open: () -> T?): T? {
        return if (coordinator == null) replaceWithoutCoordinator(model, open)
        else replaceWithCoordinator(model, runLease, open)
    }

    private fun replaceWithoutCoordinator(model: String, open: () -> T?): T? = synchronized(operationLock) {
        checkUsable()
        val loaded = synchronized(stateLock) { engine?.takeIf { modelName == model } }
        if (loaded != null) return@synchronized loaded

        val previous = synchronized(stateLock) {
            val old = engine
            engine = null
            modelName = null
            registration = null
            old
        }
        try {
            previous?.close()
        } catch (_: Throwable) {
            synchronized(stateLock) { closeUncertain = true }
            throw unavailable()
        }

        val next = open() ?: return@synchronized null
        synchronized(stateLock) {
            check(!closeUncertain) { TRANSCRIPTION_ENGINE_UNAVAILABLE_MESSAGE }
            engine = next
            modelName = model
        }
        next
    }

    private fun replaceWithCoordinator(
        model: String,
        runLease: TranscriptionRunLease?,
        open: () -> T?,
    ): T? {
        val modeCoordinator = checkNotNull(coordinator)
        var result: T? = null
        var failure: Throwable? = null
        var poisonAfterWorkerLock = false
        var ticketToComplete: DictationLoadTicket? = null
        var registrationToRelease: DictationResidentRegistration? = null

        synchronized(operationLock) {
            if (synchronized(stateLock) { closeUncertain }) {
                failure = unavailable()
                return@synchronized
            }
            if (!modeCoordinator.canUseDictationEngine(runLease)) {
                failure = unavailable()
                return@synchronized
            }
            val loaded = synchronized(stateLock) { engine?.takeIf { modelName == model } }
            if (loaded != null) {
                result = loaded
                return@synchronized
            }

            val ticket = modeCoordinator.beginDictationLoad(runLease)
            if (ticket == null) {
                failure = unavailable()
                return@synchronized
            }
            val previous: T?
            val oldRegistration: DictationResidentRegistration?
            synchronized(stateLock) {
                previous = engine
                oldRegistration = registration
                engine = null
                modelName = null
                registration = null
            }

            try {
                previous?.close()
            } catch (_: Throwable) {
                synchronized(stateLock) { closeUncertain = true }
                poisonAfterWorkerLock = true
                ticketToComplete = ticket
                failure = unavailable()
                return@synchronized
            }
            registrationToRelease = oldRegistration

            val opened = try {
                open()
            } catch (t: Throwable) {
                ticketToComplete = ticket
                failure = t
                return@synchronized
            }
            if (opened == null) {
                ticketToComplete = ticket
                return@synchronized
            }

            val newRegistration = try {
                ticket.publish {
                    synchronized(stateLock) {
                        if (closeUncertain) throw unavailable()
                        engine = opened
                        modelName = model
                    }
                }
            } catch (_: Throwable) {
                null
            }
            if (newRegistration != null) {
                synchronized(stateLock) { registration = newRegistration }
                result = opened
                return@synchronized
            }

            // A stale or poisoned load remains a barrier until this worker has safely closed it.
            try {
                opened.close()
            } catch (_: Throwable) {
                synchronized(stateLock) { closeUncertain = true }
                poisonAfterWorkerLock = true
                failure = unavailable()
            } finally {
                ticketToComplete = ticket
            }
        }

        // Releasing tickets and notifying process observers can complete futures inline. Keep both
        // operations outside the worker/native-operation lock so a listener may inspect the engine.
        registrationToRelease?.close()
        if (poisonAfterWorkerLock) modeCoordinator.reportUncertainClose()
        ticketToComplete?.completeWithoutPublish()
        failure?.let { throw it }
        return result
    }

    override fun close() {
        val closedRegistration: DictationResidentRegistration?
        var closeFailed = false
        synchronized(operationLock) {
            checkUsable()
            val previous = synchronized(stateLock) {
                val old = engine
                engine = null
                modelName = null
                val oldRegistration = registration
                registration = null
                old to oldRegistration
            }
            closedRegistration = previous.second
            try {
                previous.first?.close()
            } catch (_: Throwable) {
                synchronized(stateLock) { closeUncertain = true }
                closeFailed = true
            }
        }
        if (closeFailed) {
            coordinator?.reportUncertainClose()
            throw unavailable()
        }
        closedRegistration?.close()
    }

    private fun checkUsable() {
        if (synchronized(stateLock) { closeUncertain }) throw unavailable()
    }

    private fun unavailable() = IllegalStateException(TRANSCRIPTION_ENGINE_UNAVAILABLE_MESSAGE)
}

object TranscriptionEngine {

    private const val SAMPLE_RATE = 16000

    fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return out
    }

    data class Result(val text: String?, val error: String? = null)

    /** Bloquant — appeler hors thread principal. */
    fun transcribe(ctx: Context, pcm: ByteArray, local: LocalTranscriber?): Result {
        // Modèle non téléchargé : afficher un message explicite, sans repli réseau.
        if (local == null) return Result(null,
            "Modèle local absent — téléchargez Parakeet ou Nemotron dans l’application")
        return try {
            val samples = pcm16ToFloat(pcm)
            Result(local.transcribe(samples, SAMPLE_RATE))
        } catch (t: Throwable) {
            // UnsatisfiedLinkError et autres erreurs natives ne doivent jamais tuer l'overlay.
            android.util.Log.w("WhisperPin", "transcription locale indisponible: ${t.javaClass.simpleName}")
            Result(null, "Transcription locale indisponible.")
        }
    }

    fun loadLocal(ctx: Context, modelName: String = selectedModelName(ctx)): LocalTranscriber? {
        return try {
            if (LiveStreamingTranscriber.supports(modelName)) return null
            if (modelName.isBlank()) null else LocalTranscriber.create(ctx, modelName)
        } catch (t: Throwable) {
            // Une erreur native est une Error, pas nécessairement une Exception :
            // catch(Throwable) évite que l’application plante.
            android.util.Log.w("WhisperPin", "modèle local indisponible: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    fun loadStreamingLocal(ctx: Context, modelName: String = selectedModelName(ctx)): LiveStreamingTranscriber? {
        return try {
            if (modelName.isBlank()) return null
            LiveStreamingTranscriber.create(ctx, modelName)
        } catch (t: Throwable) {
            android.util.Log.w("WhisperPin", "modèle streaming indisponible: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    fun selectedModelName(ctx: Context): String =
        ModelDownloader.reconcileSelectedModel(ctx) ?: ""
}
