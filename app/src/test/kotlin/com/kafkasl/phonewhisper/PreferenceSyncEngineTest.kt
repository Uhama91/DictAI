package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PreferenceSyncEngineTest {
    private val first = "10000000-0000-4000-8000-000000000001"
    private val second = "20000000-0000-4000-8000-000000000002"
    private val user = "30000000-0000-4000-8000-000000000003"
    private val session = AccountSession(user, "teacher@example.test", "access", "refresh", 5000)
    private class Replica(override val actor: String) : LocalPreferenceReplica {
        var values: Map<String,String> = emptyMap()
        var document = SyncDocument()
        override fun captureLocal() = document.record(values, actor).also { document = it }
        override fun mergeRemote(documents: List<SyncDocument>): SyncDocument {
            document = documents.fold(captureLocal()) { accumulated, remote -> accumulated.merge(remote) }
            values = document.liveValues()
            return document
        }
    }
    @Test fun independentOfflineEditsConvergeThroughHostedReplicas() {
        val left = Replica(first); val right = Replica(second)
        left.values = mapOf("pref:trailing_space" to "true")
        right.values = mapOf("pref:dictation_language" to "en")
        val hosted = linkedMapOf<String,String>()
        val transport = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String) = hosted.map { SyncReplica(it.key, it.value) }
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) { hosted[actor] = json }
        }
        assertTrue(PreferenceSyncEngine(left, transport).sync(session) { true })
        assertTrue(PreferenceSyncEngine(right, transport).sync(session) { true })
        assertTrue(PreferenceSyncEngine(left, transport).sync(session) { true })
        assertEquals(left.values, right.values)
        assertEquals(2, left.values.size)
        left.values = left.values - "pref:trailing_space"
        PreferenceSyncEngine(left, transport).sync(session) { true }
        PreferenceSyncEngine(right, transport).sync(session) { true }
        assertFalse(right.values.containsKey("pref:trailing_space"))
    }
    @Test fun logoutDuringFetchPreventsMergeAndUpload() {
        val local = Replica(first)
        local.values = mapOf("pref:dictation_language" to "fr")
        var active = true
        var uploaded = false
        val transport = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String): List<SyncReplica> {
                active = false
                return listOf(SyncReplica(second, SyncDocument().record(mapOf("pref:dictation_language" to "en"), second).toJson()))
            }
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) { uploaded = true }
        }
        assertFalse(PreferenceSyncEngine(local, transport).sync(session) { active })
        assertEquals("fr", local.values["pref:dictation_language"])
        assertFalse(uploaded)
    }
    @Test fun localEditDuringFetchIsIncludedInUpload() {
        val local = Replica(first)
        var saved: SyncDocument? = null
        val transport = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String): List<SyncReplica> {
                local.values = mapOf("pref:dictation_language" to "en")
                return emptyList()
            }
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) { saved = SyncDocument.fromJson(json) }
        }
        assertTrue(PreferenceSyncEngine(local, transport).sync(session) { true })
        assertEquals("en", saved!!.liveValues()["pref:dictation_language"])
    }
    @Test fun invalidRemotePreventsPartialMergeAndUpload() {
        val local = Replica(first)
        var uploaded = false
        val transport = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String) = listOf(SyncReplica(second, """{"version":2,"entries":{}}"""))
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) { uploaded = true }
        }
        assertThrows(IllegalArgumentException::class.java) { PreferenceSyncEngine(local, transport).sync(session) { true } }
        assertFalse(uploaded)
        assertTrue(local.values.isEmpty())
    }
    @Test fun assetsAreDurableBeforeNotesAreImportedAndBeforePublished() {
        val events = mutableListOf<String>()
        val local = object : LocalPreferenceReplica {
            override val actor = first
            override fun captureLocal() = SyncDocument()
            override fun mergeRemote(documents: List<SyncDocument>): SyncDocument {
                events += "merge"; return SyncDocument()
            }
        }
        val remote = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String) = emptyList<SyncReplica>()
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) { events += "publish" }
        }
        val assets = object : ReplicaAssetExchange {
            override fun receive(session: AccountSession, documents: List<SyncDocument>, stillCurrent: () -> Boolean) { events += "download" }
            override fun send(session: AccountSession, document: SyncDocument, stillCurrent: () -> Boolean) { events += "upload" }
        }
        PreferenceSyncEngine(local, remote, assets).sync(session) { true }
        assertEquals(listOf("download", "merge", "upload", "publish"), events)
    }
    @Test fun interruptedImageDownloadLeavesNotesUntouched() {
        val local = Replica(first)
        var merged = false
        val wrapped = object : LocalPreferenceReplica {
            override val actor = first
            override fun captureLocal() = local.captureLocal()
            override fun mergeRemote(documents: List<SyncDocument>): SyncDocument { merged = true; return local.mergeRemote(documents) }
        }
        val assets = object : ReplicaAssetExchange {
            override fun receive(session: AccountSession, documents: List<SyncDocument>, stillCurrent: () -> Boolean) { throw java.io.IOException("Interrupted") }
            override fun send(session: AccountSession, document: SyncDocument, stillCurrent: () -> Boolean) = Unit
        }
        val remote = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String) = emptyList<SyncReplica>()
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) = fail("Partial notes must not publish")
        }
        assertThrows(java.io.IOException::class.java) { PreferenceSyncEngine(wrapped, remote, assets).sync(session) { true } }
        assertFalse(merged)
    }
    @Test fun logoutDuringAssetUploadPreventsPublication() {
        var active = true
        val assets = object : ReplicaAssetExchange {
            override fun receive(session: AccountSession, documents: List<SyncDocument>, stillCurrent: () -> Boolean) = Unit
            override fun send(session: AccountSession, document: SyncDocument, stillCurrent: () -> Boolean) { active = false }
        }
        val remote = object : PreferenceReplicaTransport {
            override fun readReplicas(accessToken: String, userId: String) = emptyList<SyncReplica>()
            override fun writeReplica(accessToken: String, userId: String, actor: String, json: String) = fail("Signed-out account must not publish")
        }
        assertFalse(PreferenceSyncEngine(Replica(first), remote, assets).sync(session) { active })
    }
}
