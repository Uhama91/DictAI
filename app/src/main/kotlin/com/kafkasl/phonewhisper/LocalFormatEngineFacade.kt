package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Looper

internal enum class LocalFormatEngineRouteKind { CPU_PILOT, CPU_GEMMA3_REPAIR, GPU_LITERT }

internal fun localFormatEngineRouteKind(pilot: Boolean): LocalFormatEngineRouteKind =
    localFormatEngineRouteKind(gemma4Pilot = pilot, gemma3RepairPilot = false)

internal fun localFormatEngineRouteKind(
    gemma4Pilot: Boolean,
    gemma3RepairPilot: Boolean,
): LocalFormatEngineRouteKind {
    require(!(gemma4Pilot && gemma3RepairPilot)) { "Gemma pilot engines are mutually exclusive." }
    return when {
        gemma3RepairPilot -> LocalFormatEngineRouteKind.CPU_GEMMA3_REPAIR
        gemma4Pilot -> LocalFormatEngineRouteKind.CPU_PILOT
        else -> LocalFormatEngineRouteKind.GPU_LITERT
    }
}

/** Stable formatter API. Only one runtime implementation is constructed per process owner. */
internal class LocalFormatEngine(context: Context) : AutoCloseable {
    private val route: LocalFormatEngineRoute = when (localFormatEngineRouteKind(
        gemma4Pilot = BuildConfig.GEMMA4_FINE_TUNED_PILOT,
        gemma3RepairPilot = BuildConfig.GEMMA3_REPAIR_PILOT,
    )) {
        LocalFormatEngineRouteKind.CPU_PILOT -> CpuLocalFormatRoute(
            context.applicationContext,
            GemmaModelStore.Q6_ARTIFACT,
            LocalFormatCpuProfile.Gemma4,
        )
        LocalFormatEngineRouteKind.CPU_GEMMA3_REPAIR -> CpuLocalFormatRoute(
            context.applicationContext,
            GemmaModelStore.GEMMA3_REPAIR_ARTIFACT,
            LocalFormatCpuProfile.Gemma3Final,
        )
        LocalFormatEngineRouteKind.GPU_LITERT -> GpuLocalFormatRoute(context.applicationContext)
    }

    fun runtimeName(): String = route.runtimeName()
    fun failureCode(): String? = route.failureCode()
    fun isLoaded(): Boolean = route.isLoaded()
    fun lastLoadMs(): Long? = route.lastLoadMs()

    fun prepareForBenchmarkInfo(): LocalFormatPreparation = route.prepareForBenchmarkInfo()
    fun prepareForBenchmark(): Long = route.prepareForBenchmark()
    fun warm() = route.warm()
    fun backend(): LocalFormatBackend = route.backend()
    override fun close() = route.close()

    companion object {
        val MODEL_FILE: String get() = GemmaModelStore.MODEL_FILE
        const val GENERATION_DEADLINE_MS = 20_000L
    }
}

private interface LocalFormatEngineRoute : AutoCloseable {
    fun runtimeName(): String
    fun failureCode(): String?
    fun isLoaded(): Boolean
    fun lastLoadMs(): Long?
    fun prepareForBenchmarkInfo(): LocalFormatPreparation
    fun prepareForBenchmark(): Long = prepareForBenchmarkInfo().waitMs
    fun warm()
    fun backend(): LocalFormatBackend
}

private class GpuLocalFormatRoute(context: Context) : LocalFormatEngineRoute {
    private val engine = GpuLocalFormatEngine(context)

    override fun runtimeName(): String = engine.runtimeName()
    override fun failureCode(): String? = engine.failureCode()
    override fun isLoaded(): Boolean = engine.isLoaded()
    override fun lastLoadMs(): Long? = engine.lastLoadMs()
    override fun prepareForBenchmarkInfo(): LocalFormatPreparation = engine.prepareForBenchmarkInfo()
    override fun warm() = engine.warm()
    override fun backend(): LocalFormatBackend = engine.backend()
    override fun close() = engine.close()
}

private class CpuLocalFormatRoute(
    context: Context,
    artifact: GemmaModelArtifact,
    profile: LocalFormatCpuProfile,
) : LocalFormatEngineRoute {
    private val engine = LocalFormatCpuEngine(
        modelProvider = LocalFormatCpuModelProvider {
            GemmaModelStore(context.applicationContext, artifact).installedModel()
                ?.takeIf { it.extension.equals("gguf", ignoreCase = true) }
        },
        profile = profile,
    )

    override fun runtimeName(): String = engine.runtimeName()
    override fun failureCode(): String? = engine.failureCode()
    override fun isLoaded(): Boolean = engine.isLoaded()
    override fun lastLoadMs(): Long? = engine.lastLoadMs()

    override fun prepareForBenchmarkInfo(): LocalFormatPreparation {
        requireLocalFormatWorkerThread()
        return engine.prepareForBenchmarkInfo()
    }

    override fun warm() = engine.warm()

    override fun backend(): LocalFormatBackend = WorkerGuardedBackend(engine.backend())

    override fun close() = engine.close()
}

/** Keeps the worker-thread contract identical to the existing GPU API. */
private class WorkerGuardedBackend(private val delegate: LocalFormatBackend) : LocalFormatBackend {
    override fun generate(request: LocalFormatRequest, onChunk: (String) -> Unit): String? {
        requireLocalFormatWorkerThread()
        return delegate.generate(request, onChunk)
    }

    override fun generate(
        request: LocalFormatRequest,
        onChunk: (String) -> Unit,
        onNativeStart: () -> Unit,
    ): String? {
        requireLocalFormatWorkerThread()
        return delegate.generate(request, onChunk, onNativeStart)
    }

    override fun cancel() = delegate.cancel()
}

private fun requireLocalFormatWorkerThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Local formatter requires a worker thread" }
}
