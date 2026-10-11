package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Website distribution: only checks metadata; installation remains in the system installer. */
class AppUpdateChecker internal constructor(
    private val preferences: SharedPreferences,
    private val currentCode: Int,
    private val sdk: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val fetch: () -> String = ::fetchManifest,
) {
    enum class Status { IDLE, CHECKING, COMPLETE, UNAVAILABLE }
    private val handler = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<() -> Unit>()
    private val periodic = Runnable { check(); schedule() }
    var status = Status.IDLE
        private set
    var offer: AppUpdate? = cachedOffer()
        private set

    val statusText: String get() = when (status) {
        Status.IDLE -> "Version ${BuildConfig.VERSION_NAME} · vérifier les mises à jour"
        Status.CHECKING -> "Vérification en cours…"
        Status.COMPLETE -> if (offer != null) "Une nouvelle version est disponible" else "Aucune mise à jour compatible disponible"
        Status.UNAVAILABLE -> "Vérification indisponible · réessayer"
    }

    fun observe(observer: () -> Unit) { observers += observer; schedule() }
    fun unobserve(observer: () -> Unit) {
        observers -= observer
        if (observers.isEmpty()) handler.removeCallbacks(periodic)
    }
    private fun schedule() {
        handler.removeCallbacks(periodic)
        if (observers.isNotEmpty()) handler.postDelayed(periodic, 3_600_000)
    }
    fun check(force: Boolean = false) {
        if (status == Status.CHECKING) return
        if (!force && !AppUpdateSchedule.due(clock(), preferences.getLong("last_attempt", 0), preferences.getBoolean("succeeded", false))) return
        preferences.edit().putLong("last_attempt", clock()).putBoolean("succeeded", false).apply()
        status = Status.CHECKING
        notifyObservers()
        executor.execute {
            val result = runCatching {
                val raw = fetch()
                raw to AppUpdateManifest.android(raw, currentCode, sdk)
            }
            handler.post {
                result.onSuccess { (raw, update) ->
                    preferences.edit().putString("manifest", raw).putBoolean("succeeded", true).apply()
                    offer = update
                    status = Status.COMPLETE
                }.onFailure { status = Status.UNAVAILABLE }
                notifyObservers()
            }
        }
    }
    private fun notifyObservers() { observers.toList().forEach { it() } }
    private fun cachedOffer(): AppUpdate? = preferences.getString("manifest", null)?.let {
        runCatching { AppUpdateManifest.android(it, currentCode, sdk) }.getOrNull()
    }
    companion object {
        @Volatile private var instance: AppUpdateChecker? = null
        fun get(context: Context): AppUpdateChecker = instance ?: synchronized(this) {
            instance ?: AppUpdateChecker(context.applicationContext.getSharedPreferences("dictai_app_updates", Context.MODE_PRIVATE), BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT).also { instance = it }
        }
        private fun fetchManifest(): String {
            val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(AppUpdateManifest.FEED_URL).header("Accept", "application/json").header("Cache-Control", "no-cache").build()
            return client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Source de mise à jour indisponible" }
                readManifestBody(requireNotNull(response.body))
            }
        }
        internal fun readManifestBody(body: ResponseBody): String {
            require(body.contentLength() <= AppUpdateManifest.MAX_BYTES)
            val source = body.source()
            source.request((AppUpdateManifest.MAX_BYTES + 1).toLong())
            require(source.buffer.size <= AppUpdateManifest.MAX_BYTES)
            return source.readUtf8()
        }
    }
}
