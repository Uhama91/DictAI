import Foundation
import Combine
import DictAICore

@MainActor final class SyncCoordinator: ObservableObject {
    @Published var status = "Données enregistrées sur ce Mac"
    @Published private(set) var busy = false
    @Published private(set) var lastSync: Date?
    private let library: LibraryStore
    private let account: AccountController
    private var scheduled: Task<Void, Never>?
    private var periodic: Task<Void, Never>?
    private var uploaded = Set<String>()
    private var again = false
    init(library: LibraryStore, account: AccountController) {
        self.library = library; self.account = account
        library.onEdit = { [weak self] in self?.schedule() }
    }
    func start() {
        periodic = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if Task.isCancelled { break }
                await self?.sync()
            }
        }
    }
    func schedule() {
        if account.session == nil { status = "Données enregistrées sur ce Mac"; return }
        status = "Modifications enregistrées · synchronisation en attente"
        scheduled?.cancel()
        scheduled = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }
    func sync() async {
        guard !busy else { again = true; return }
        guard let user = account.session?.userId else { return }
        guard library.scope == user else {
            status = "Synchronisation suspendue : la bibliothèque de ce compte n’a pas pu être ouverte."
            return
        }
        busy = true; status = "Synchronisation en cours…"
        let epoch = account.generation; let scope = library.generation
        func current() -> Bool { epoch == account.generation && scope == library.generation && library.scope == user && !Task.isCancelled }
        defer { busy = false; if again { again = false; schedule() } }
        do {
            guard let session = try await account.validSession(), current() else { return }
            let remote = try await account.client.replicas(session)
            guard current() else { return }
            // Recompute after every await; edits made during network I/O belong to this merge.
            let preview = try NoteMerge.merge(local: library.document, remote: remote, actor: library.actor)
            let incomingAssets = try assets(preview)
            for asset in incomingAssets {
                let file = library.assetURL(asset)
                if FileManager.default.fileExists(atPath: file.path) { try account.client.verify(Data(contentsOf: file), asset: asset) }
                else {
                    let data = try await account.client.download(asset, account: session)
                    guard current() else { return }
                    try data.write(to: file, options: .atomic)
                }
            }
            guard current() else { return }
            let merged = try NoteMerge.merge(local: library.document, remote: remote, actor: library.actor)
            try library.commit(merged, notify: false)
            for asset in try assets(merged) {
                let receipt = session.userId + ":" + asset.sha256
                if uploaded.contains(receipt) { continue }
                try await account.client.upload(asset, data: Data(contentsOf: library.assetURL(asset)), account: session)
                guard current() else { return }
                uploaded.insert(receipt)
            }
            guard current() else { return }
            try await account.client.publish(merged, actor: library.actor, account: session)
            guard current() else { return }
            lastSync = Date()
            if library.document != merged { again = true; status = "Nouvelles modifications en attente" }
            else { status = "Échange terminé · " + Date().formatted(date: .omitted, time: .shortened) }
        } catch {
            guard current() else { return }
            status = error.localizedDescription + " Les données locales sont conservées."
        }
    }
    private func assets(_ document: SyncDocument) throws -> [Asset] {
        let ids = Set(try document.notes().flatMap { $0.images.map(\.id) })
        return try ids.sorted().map {
            guard let raw = document.values["asset:" + $0] else { throw DataError.invalid("Image manquante.") }
            return try Asset.decode(raw)
        }
    }
}
