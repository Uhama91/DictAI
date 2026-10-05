package com.kafkasl.phonewhisper

internal interface LocalPreferenceReplica {
    val actor: String
    fun captureLocal(): SyncDocument
    fun mergeRemote(documents: List<SyncDocument>): SyncDocument
}

internal interface ReplicaAssetExchange {
    fun receive(session: AccountSession, documents: List<SyncDocument>, stillCurrent: () -> Boolean)
    fun send(session: AccountSession, document: SyncDocument, stillCurrent: () -> Boolean)
}

/** One exchange is guarded at every boundary against an account being replaced. */
internal class PreferenceSyncEngine(private val local: LocalPreferenceReplica, private val remote: PreferenceReplicaTransport,
    private val assets: ReplicaAssetExchange? = null) {
    fun sync(session: AccountSession, stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        local.captureLocal()
        val documents = remote.readReplicas(session.accessToken, session.userId).map { SyncDocument.fromJson(it.document) }
        if (!stillCurrent()) return false
        assets?.receive(session, documents, stillCurrent)
        if (!stillCurrent()) return false
        val merged = local.mergeRemote(documents)
        if (!stillCurrent()) return false
        assets?.send(session, merged, stillCurrent)
        if (!stillCurrent()) return false
        remote.writeReplica(session.accessToken, session.userId, local.actor, merged.toJson())
        return stillCurrent()
    }
}
