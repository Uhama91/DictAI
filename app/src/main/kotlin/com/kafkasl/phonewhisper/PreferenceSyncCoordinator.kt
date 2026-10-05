package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.work.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal data class AccountSyncConfiguration(val url: HttpUrl, val key: String) {
    companion object {
        fun installed(): AccountSyncConfiguration? {
            val url = BuildConfig.SYNC_SUPABASE_URL.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null || url.encodedPath != "/") return null
            val key = BuildConfig.SYNC_SUPABASE_PUBLISHABLE_KEY
            if (!key.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) return null
            return AccountSyncConfiguration(url, key)
        }
    }
}

/** Device preferences stay usable without any account or network access. */
internal class PreferenceSyncCoordinator private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val account = AccountSessionStore(app)
    private val state = app.getSharedPreferences("dictai_sync_status", Context.MODE_PRIVATE)
    private val userPrefs = app.getSharedPreferences("whisperpin", Context.MODE_PRIVATE)
    private val formats = app.getSharedPreferences("dictai_formats", Context.MODE_PRIVATE)
    private val notes = app.getSharedPreferences("transcript_notes", Context.MODE_PRIVATE)
    private val folders = app.getSharedPreferences("transcript_note_folders", Context.MODE_PRIVATE)
    private val networkLock = Any()
    private val loginExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "DictAI account").apply { isDaemon = true } }
    private var local: Pair<String, PreferenceSyncStore>? = null
    private var applyingRemote = false
    private var initialized = false
    private val observers = linkedSetOf<() -> Unit>()
    private val dataObservers = linkedSetOf<() -> Unit>()
    private val debounce = Runnable { enqueue() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
        if (!applyingRemote && ((source === userPrefs && key in sharedKeys) ||
                (source === formats && key in setOf("custom", "selected")) || source === notes || source === folders)) {
            currentSession()?.let {
                try {
                    setStatus("pending")
                    main.removeCallbacks(debounce)
                    main.postDelayed(debounce, 1500)
                } catch (_: Exception) { setStatus("data_error") }
            }
        }
    }

    val available: Boolean get() = AccountSyncConfiguration.installed() != null
    fun currentSession(): AccountSession? = try { account.session() } catch (_: Exception) { null }
    fun status(): String = state.getString("status", "local") ?: "local"
    fun lastSyncedAt(): Long = state.getLong("last_sync", 0)
    fun deviceCount(): Int = state.getInt("devices", 0)

    fun initialize() {
        if (initialized) return
        initialized = true
        userPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        formats.registerOnSharedPreferenceChangeListener(preferenceListener)
        notes.registerOnSharedPreferenceChangeListener(preferenceListener)
        folders.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (available) {
            try { if (account.session() != null) { schedulePeriodic(); requestSync() } }
            catch (_: Exception) { setStatus("reconnect") }
        }
    }
    fun observe(observer: () -> Unit) { observers += observer }
    fun unobserve(observer: () -> Unit) { observers -= observer }
    fun observeData(observer: () -> Unit) { dataObservers += observer }
    fun unobserveData(observer: () -> Unit) { dataObservers -= observer }
    fun requestSync() {
        if (!available || currentSession() == null) return
        setStatus("pending")
        main.removeCallbacks(debounce)
        enqueue()
    }
    fun beginGoogleLogin(): String {
        val config = AccountSyncConfiguration.installed() ?: error("Account connection unavailable")
        // Reconnecting replaces only this device's session, preserving its preferences.
        disconnect()
        val auth = SupabaseAccountAuth(config.url, config.key)
        val attempt = auth.newAttempt(System.currentTimeMillis())
        account.beginAttempt(attempt)
        setStatus("connecting")
        return auth.authorizeUrl(attempt).toString()
    }
    fun finishGoogleLogin(callback: String) {
        val config = AccountSyncConfiguration.installed() ?: return
        val auth = SupabaseAccountAuth(config.url, config.key)
        val generation = account.generation()
        val attempt = try { account.attempt() } catch (_: Exception) { null } ?: return
        val code = try { auth.callbackCode(callback, attempt, System.currentTimeMillis()) }
        catch (_: Exception) { setStatus("login_error"); return }
        if (!account.clearAttempt(generation)) return
        setStatus("connecting")
        loginExecutor.execute {
            try {
                val session = synchronized(networkLock) { auth.exchange(code, attempt, System.currentTimeMillis()) }
                onMain {
                    if (account.saveSession(session, generation)) {
                        state.edit().remove("last_sync").remove("devices").commit()
                        schedulePeriodic()
                        requestSync()
                    }
                }
            } catch (_: Exception) { onMain { if (account.generation() == generation) setStatus("login_error") } }
        }
    }
    fun disconnect() {
        val old = currentSession()
        account.disconnect()
        local = null
        main.removeCallbacks(debounce)
        WorkManager.getInstance(app).cancelAllWorkByTag(WORK_TAG)
        state.edit().remove("last_sync").remove("devices").commit()
        setStatus("local")
        if (old != null) AccountSyncConfiguration.installed()?.let { config ->
            loginExecutor.execute {
                synchronized(networkLock) { runCatching { SupabaseAccountAuth(config.url, config.key).revoke(old) } }
            }
        }
    }

    /** Invoked only by a background Worker. Source writes are serialized on the main looper. */
    fun runSync(): Boolean = synchronized(networkLock) {
        val config = AccountSyncConfiguration.installed() ?: return true
        val generation = account.generation()
        var session = try { account.session() } catch (_: Exception) {
            onMain { if (account.generation() == generation) setStatus("reconnect") }; return true
        } ?: return true
        val auth = SupabaseAccountAuth(config.url, config.key)
        fun current() = account.generation() == generation && currentSession()?.userId == session.userId
        fun refresh(): Boolean {
            val refreshed = auth.refresh(session, System.currentTimeMillis())
            if (!account.saveSession(refreshed, generation)) return false
            session = refreshed
            return true
        }
        try {
            if (!current()) return true
            if (session.expiresAt <= System.currentTimeMillis() + 60_000 && !refresh()) return true
            if (!current()) return true
            onMain { if (current()) setStatus("syncing") }
            val transport = SupabasePreferenceTransport(config.url, config.key)
            val assets = AccountNoteAssetExchange(app, SupabaseAccountAttachmentTransport(config.url, config.key),
                "${config.url}|${session.userId}") { documents -> onMain {
                    if (!current()) throw AccountChanged()
                    store(session.userId).previewRemote(documents)
                } }
            val pending = onMain { if (!current()) throw AccountChanged(); store(session.userId).pendingDocument() }
            if (pending != null) {
                assets.restorePending(session, pending, ::current)
                onMain {
                    if (!current()) throw AccountChanged()
                    importRemote { store(session.userId).current() }
                }
            }
            AccountNotesSyncStore(app).prepareAssets()
            var receivedActors = emptySet<String>()
            var sentDocument: SyncDocument? = null
            val counted = object : PreferenceReplicaTransport {
                override fun readReplicas(accessToken: String, userId: String): List<SyncReplica> =
                    transport.readReplicas(accessToken, userId).also { receivedActors = it.map(SyncReplica::actor).toSet() }
                override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) {
                    transport.writeReplica(accessToken, userId, actor, json)
                    sentDocument = SyncDocument.fromJson(json)
                }
            }
            val replica = object : LocalPreferenceReplica {
                override val actor: String get() = onMain { if (!current()) throw AccountChanged(); store(session.userId).actor }
                override fun captureLocal(): SyncDocument = onMain {
                    if (!current()) throw AccountChanged()
                    store(session.userId).captureLocal()
                }
                override fun mergeRemote(documents: List<SyncDocument>): SyncDocument = onMain {
                    if (!current()) throw AccountChanged()
                    importRemote { store(session.userId).mergeRemote(documents) }
                }
            }
            var result: Boolean
            try { result = PreferenceSyncEngine(replica, counted, assets).sync(session, ::current) }
            catch (e: SyncHttpException) {
                if (e.code != 401 || !refresh()) throw e
                result = PreferenceSyncEngine(replica, counted, assets).sync(session, ::current)
            }
            if (result) onMain {
                if (current()) {
                    state.edit().putLong("last_sync", System.currentTimeMillis())
                        .putInt("devices", (receivedActors + replica.actor).size).commit()
                    // If a user edited while the upload ran, its journal remains queued.
                    setStatus(if (store(session.userId).captureLocal() == sentDocument) "synced" else "pending")
                    if (status() == "pending") enqueue()
                }
            }
            true
        } catch (_: AccountChanged) { true }
        catch (e: SyncHttpException) {
            onMain { if (current()) setStatus(if (e.code == 401 || e.code == 403) "reconnect" else "network_error") }
            e.code < 500 && e.code != 429
        } catch (_: IOException) { onMain { if (current()) setStatus("network_error") }; false }
        catch (_: Exception) { onMain { if (current()) setStatus("data_error") }; true }
    }
    private fun store(userId: String): PreferenceSyncStore {
        local?.takeIf { it.first == userId }?.let { return it.second }
        val previous = applyingRemote
        applyingRemote = true
        return try { PreferenceSyncStore(app, userId, recoveryDeferred = true).also { local = userId to it } } finally { applyingRemote = previous }
    }
    private fun importRemote(block: () -> SyncDocument): SyncDocument {
        val beforeTheme = userPrefs.getString("theme_mode", null)
        applyingRemote = true
        val document = try { block() } finally { applyingRemote = false }
        if (beforeTheme != userPrefs.getString("theme_mode", null)) ThemeModeController.apply(app)
        dataObservers.toList().forEach { observer -> runCatching { observer() } }
        return document
    }
    private fun enqueue() {
        if (!available || currentSession() == null) return
        val request = OneTimeWorkRequestBuilder<PreferenceSyncWorker>().setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<PreferenceSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints()).addTag(WORK_TAG).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
    private fun setStatus(value: String) {
        if (status() == value) return
        state.edit().putString("status", value).commit()
        notifyObservers()
    }
    private fun notifyObservers() { observers.toList().forEach { runCatching { it() } } }
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return try { task.get(30, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(false); throw e }
    }
    private class AccountChanged : IOException("Account changed")
    companion object {
        private var active: PreferenceSyncCoordinator? = null
        @Synchronized fun get(context: Context): PreferenceSyncCoordinator {
            val app = context.applicationContext
            return active?.takeIf { it.app === app } ?: PreferenceSyncCoordinator(app).also { active = it }
        }
        private val sharedKeys = setOf("vocab_raw", "dictation_language", "number_style", "light_text_cleanup",
            "trailing_space", "show_transcript", "theme_mode", "formatting_engine", "cloud_cleanup_enabled", "cloud_cleanup_model")
        private const val WORK_TAG = "dictai-preferences"
        private const val WORK_ONCE = "dictai-preferences-once"
        private const val WORK_PERIODIC = "dictai-preferences-periodic"
        private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

class PreferenceSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (PreferenceSyncCoordinator.get(applicationContext).runSync()) Result.success() else Result.retry()
}
