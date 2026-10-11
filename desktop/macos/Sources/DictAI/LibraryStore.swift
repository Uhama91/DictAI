import Foundation
import Combine
import DictAICore

@MainActor final class LibraryStore: ObservableObject {
    @Published private(set) var document = SyncDocument()
    @Published private(set) var scope = "local"
    @Published var error: String?
    @Published var selectedNote: String?
    @Published var selectedFolder: String?
    @Published private(set) var intelligentPunctuation = true
    func setIntelligentPunctuation(_ enabled: Bool) {
        perform {
            try JSONEncoder().encode(["intelligentPunctuation": enabled]).write(to: root.appendingPathComponent("mac-preferences.json"), options: .atomic)
            intelligentPunctuation = enabled
        }
    }
    let root: URL
    let actor: String
    private(set) var generation = UUID()
    var onEdit: (() -> Void)?
    init(root: URL? = nil) throws {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("DictAI")
        try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
        let localSettings = self.root.appendingPathComponent("mac-preferences.json")
        if FileManager.default.fileExists(atPath: localSettings.path) {
            intelligentPunctuation = try JSONDecoder().decode([String: Bool].self, from: Data(contentsOf: localSettings))["intelligentPunctuation"] ?? true
        }
        let identity = self.root.appendingPathComponent("device-id")
        if FileManager.default.fileExists(atPath: identity.path) {
            actor = try String(contentsOf: identity, encoding: .utf8)
            try require(isUUID(actor), "Identifiant local endommagé. Les données sont préservées.")
        } else {
            actor = UUID().uuidString.lowercased()
            try Data(actor.utf8).write(to: identity, options: .atomic)
        }
        try switchScope(nil)
    }
    var directory: URL { root.appendingPathComponent(scope, isDirectory: true) }
    var notes: [Note] { (try? document.notes()) ?? [] }
    var folders: [Folder] { (try? document.folders()) ?? [] }
    var vocabulary: [(key: String, value: String)] { document.values.filter { $0.key.hasPrefix("vocab:") }.sorted { $0.value.localizedStandardCompare($1.value) == .orderedAscending }.map { ($0.key, $0.value) } }
    func preference(_ key: String, default fallback: String) -> String { document.values["pref:" + key] ?? fallback }
    func switchScope(_ user: String?) throws {
        if let user { try require(isUUID(user), "Compte invalide.") }
        let name = user ?? "local"
        let path = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: path.appendingPathComponent("images"), withIntermediateDirectories: true)
        let file = path.appendingPathComponent("library.json")
        let incoming = FileManager.default.fileExists(atPath: file.path) ? try SyncDocument.decode(Data(contentsOf: file)) : SyncDocument()
        try incoming.validateContent()
        generation = UUID(); scope = name; document = incoming; selectedNote = nil; selectedFolder = nil
    }
    func commit(_ incoming: SyncDocument, notify: Bool = true) throws {
        try incoming.validateContent()
        let file = directory.appendingPathComponent("library.json")
        // Never publish an in-memory state unless the atomic write succeeded.
        try JSONEncoder().encode(incoming).write(to: file, options: .atomic)
        document = incoming
        if notify { onEdit?() }
    }
    func perform(_ action: () throws -> Void) { do { try action() } catch { self.error = error.localizedDescription } }
    func setPreference(_ key: String, _ value: String) { perform { try commit(document.setting("pref:" + key, value: value, actor: actor)) } }
    @discardableResult func createNote(text: String = "", folder: String? = nil) throws -> Note {
        var note = Note(text: text, folderId: folder)
        if !text.isEmpty { note.title = TextLimit.prefix(text.split(separator: "\n").first.map(String.init) ?? "Nouvelle note", utf16: 70) }
        try commit(document.saving(note, actor: actor)); selectedNote = note.id
        return notes.first { $0.id == note.id } ?? note
    }
    @discardableResult func save(_ note: Note, basedOn base: String?) throws -> Note {
        var edited = note
        if let current = document.entries["note:" + note.id], current.value != base {
            // The editor's old bytes must never silently acknowledge a remote edit.
            edited.id = UUID().uuidString.lowercased(); edited.title = TextLimit.prefix(edited.title, utf16: 58) + " (version conservée)"
            edited._seen = [:]
        }
        edited.updated = Wire.now
        try commit(document.saving(edited, actor: actor))
        selectedNote = edited.id
        return notes.first { $0.id == edited.id } ?? edited
    }
    func createFolder(_ name: String) throws {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let folder = Folder(name: clean)
        _ = try Folder.decode(folder.canonical)
        try require(!folders.contains { $0.name.caseInsensitiveCompare(clean) == .orderedSame }, "Ce dossier existe déjà.")
        try commit(document.setting("folder:" + folder.id, value: folder.canonical, actor: actor))
        selectedFolder = folder.id
    }
    func deleteNote(_ id: String) { perform { try commit(document.deletingNote(id, actor: actor)); if selectedNote == id { selectedNote = nil } } }
    func addVocabulary(_ raw: String) throws {
        let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        try require(!line.isEmpty && line.utf16.count <= 8192 && !line.contains("\n") && !line.contains("\r"), "Saisissez un terme ou une règle sur une seule ligne.")
        try commit(document.setting(Vocabulary.key(line), value: line, actor: actor))
    }
    func removeVocabulary(_ key: String) { perform { try commit(document.setting(key, value: nil, actor: actor)) } }
    func imageURL(_ id: String) -> URL? {
        guard let raw = document.values["asset:" + id], let asset = try? Asset.decode(raw) else { return nil }
        return assetURL(asset)
    }
    func assetURL(_ asset: Asset) -> URL { directory.appendingPathComponent("images/" + asset.sha256 + ".jpg") }
    func saveDraft(_ draft: DictationDraft) throws { try JSONEncoder().encode(draft).write(to: directory.appendingPathComponent("draft.json"), options: .atomic) }
    func loadDraft() -> DictationDraft? {
        let file = directory.appendingPathComponent("draft.json")
        return (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode(DictationDraft.self, from: $0) }
    }
}
