import Foundation
import Testing
import DictAICore
@testable import DictAI

@MainActor struct LibraryStoreTests {
    @Test func intelligentPunctuationIsAPersistentMacSettingOutsideAndroidSync() throws {
        let root = location()
        let store = try LibraryStore(root: root)
        #expect(store.intelligentPunctuation)
        store.setIntelligentPunctuation(false)
        #expect(!store.intelligentPunctuation)
        try store.switchScope("11111111-1111-4111-8111-111111111111")
        #expect(!store.intelligentPunctuation)
        let restored = try LibraryStore(root: root)
        #expect(!restored.intelligentPunctuation)
        #expect(store.document.values.isEmpty && restored.document.values.isEmpty)
    }
    func location() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("dictai-test-" + UUID().uuidString) }
    @Test func manualDraftIsReadyForCorrectionWithoutStartingMicrophone() throws {
        let model = try AppModel(root: location())
        model.edit("Une note tapée dans le panneau")
        #expect(model.state == .review)
        #expect(model.library.loadDraft()?.text == "Une note tapée dans le panneau")
    }
    @Test func persistenceSurvivesRelaunchIncludingTombstones() throws {
        let root = location()
        let first = try LibraryStore(root: root)
        let note = try first.createNote(text: "Une note locale")
        let second = try LibraryStore(root: root)
        #expect(second.notes.map(\.text) == ["Une note locale"])
        #expect(first.actor == second.actor)
        second.deleteNote(note.id)
        let third = try LibraryStore(root: root)
        #expect(third.notes.isEmpty)
        #expect(third.document.entries["deletion:" + note.id]?.value != nil)
    }
    @Test func switchingAccountsNeverUploadsPreviousAccountData() throws {
        let store = try LibraryStore(root: location())
        _ = try store.createNote(text: "Local")
        try store.switchScope("11111111-1111-4111-8111-111111111111")
        #expect(store.notes.isEmpty)
        _ = try store.createNote(text: "Premier compte")
        try store.switchScope("22222222-2222-4222-8222-222222222222")
        #expect(store.notes.isEmpty)
        try store.switchScope(nil)
        #expect(store.notes.map(\.text) == ["Local"])
        try store.switchScope("11111111-1111-4111-8111-111111111111")
        #expect(store.notes.map(\.text) == ["Premier compte"])
    }
    @Test func openEditorSavesSeparateCopyAfterRemoteEdit() throws {
        let store = try LibraryStore(root: location())
        var editor = try store.createNote(text: "Version initiale")
        let base = store.document.values["note:" + editor.id]
        var remote = editor; remote.text = "Retouche téléphone"
        let incoming = try store.document.saving(remote, actor: "22222222-2222-4222-8222-222222222222")
        try store.commit(incoming, notify: false)
        editor.text = "Retouche Mac"
        let preserved = try store.save(editor, basedOn: base)
        #expect(preserved.id != editor.id)
        #expect(Set(store.notes.map(\.text)) == Set(["Retouche téléphone", "Retouche Mac"]))
    }
    @Test func corruptLibraryIsNeverSilentlyReplaced() throws {
        let root = location()
        let store = try LibraryStore(root: root)
        let file = store.directory.appendingPathComponent("library.json")
        let corrupt = Data("broken JSON".utf8)
        try corrupt.write(to: file)
        #expect(throws: (any Error).self) { try LibraryStore(root: root) }
        #expect(try Data(contentsOf: file) == corrupt)
    }
    @Test func failedAccountLibraryRestoreSuspendsSyncBeforeNetwork() async throws {
        let store = try LibraryStore(root: location())
        _ = try store.createNote(text: "Brouillon sans compte")
        let user = "11111111-1111-4111-8111-111111111111"
        try store.switchScope(user)
        let file = store.directory.appendingPathComponent("library.json")
        try store.switchScope(nil)
        try Data("broken JSON".utf8).write(to: file)
        let account = AccountController()
        let session = AccountSession(userId: user, email: "test@example.invalid", accessToken: "test-only", refreshToken: "test-only", expiresAt: .distantFuture)
        try account.restore(data: JSONEncoder().encode(session))
        #expect(throws: (any Error).self) { try store.switchScope(user) }
        let sync = SyncCoordinator(library: store, account: account)
        await sync.sync()
        #expect(sync.status.hasPrefix("Synchronisation suspendue"))
        #expect(sync.lastSync == nil)
        #expect(!sync.busy)
        #expect(store.notes.map(\.text) == ["Brouillon sans compte"])
    }
    @Test func autoTitleHandlesLongEmojiDictation() throws {
        let store = try LibraryStore(root: location())
        let text = String(repeating: "🐈", count: 80)
        let note = try store.createNote(text: text)
        #expect(note.text == text)
        #expect(note.title.utf16.count <= 80)
        try store.document.validateContent()
    }
    @Test func failedAtomicWriteDoesNotAdvanceMemory() throws {
        let root = location()
        let store = try LibraryStore(root: root)
        let old = store.document
        let path = store.directory.appendingPathComponent("library.json")
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: false)
        #expect(throws: (any Error).self) { try store.createNote(text: "Ne doit pas sembler enregistrée") }
        #expect(store.document == old)
    }
}
