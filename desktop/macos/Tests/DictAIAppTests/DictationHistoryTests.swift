import Foundation
import AppKit
import Testing
@testable import DictAI

@MainActor struct DictationHistoryTests {
    @Test func quickCopyUsesTheLatestCompleteTranscriptWithoutChangingTheLibrary() throws {
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-quick-copy-" + UUID().uuidString))
        _ = try model.library.createNote(text: "Note à conserver")
        try model.library.history.record(id: "old", text: "Ancienne dictée")
        try model.library.history.record(id: "latest", text: "La dernière transcription 🐈.\n\nDeuxième paragraphe intégral.")
        model.edit("Un brouillon distinct qui ne doit pas être copié")
        let history = model.library.history.entries
        let document = model.library.document
        let pasteboard = NSPasteboard.withUniqueName()
        defer { pasteboard.releaseGlobally() }
        #expect(model.copyLatestDictation(to: pasteboard))
        #expect(pasteboard.string(forType: .string) == "La dernière transcription 🐈.\n\nDeuxième paragraphe intégral.")
        #expect(model.library.history.entries == history)
        #expect(model.library.document == document)
    }
    @Test func quickCopyWithNoDictationPreservesTheClipboard() throws {
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-empty-copy-" + UUID().uuidString))
        let pasteboard = NSPasteboard.withUniqueName()
        defer { pasteboard.releaseGlobally() }
        pasteboard.setString("À conserver dans le presse-papiers", forType: .string)
        #expect(!model.copyLatestDictation(to: pasteboard))
        #expect(pasteboard.string(forType: .string) == "À conserver dans le presse-papiers")
    }
    private func library() throws -> LibraryStore {
        try LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-history-" + UUID().uuidString))
    }
    @Test func elevenDictationsEvictTheOldestAndSurviveRelaunch() throws {
        let store = try library()
        for i in 1...11 { try store.history.record(id: "session-\(i)", text: "Dictée \(i) 🐈\nTexte intégral") }
        #expect(store.history.entries.map(\.id) == (2...11).reversed().map { "session-\($0)" })
        #expect(try LibraryStore(root: store.root).history.entries == store.history.entries)
        #expect(store.document.entries.isEmpty)
    }
    @Test func retryUpdatesOneEntryAndEmptyTextDoesNotEvictAnything() throws {
        let store = try library()
        try store.history.record(id: "one", text: "Première version")
        try store.history.record(id: "one", text: "Version retouchée")
        try store.history.record(id: "empty", text: " \n ")
        #expect(store.history.entries.map(\.text) == ["Version retouchée"])
    }
    @Test func clearingHistoryLeavesExistingNotesAndVocabularyUntouched() throws {
        let store = try library()
        _ = try store.createNote(text: "Note permanente")
        try store.addVocabulary("DictAI")
        let before = store.document
        try store.history.record(id: "one", text: "Ma dictée")
        try store.history.clear()
        #expect(store.history.entries.isEmpty)
        let reopened = try LibraryStore(root: store.root)
        #expect(reopened.history.entries.isEmpty)
        #expect(reopened.document == before)
    }
    @Test func accountsHaveSeparateLocalHistoryOutsideTheSyncDocument() throws {
        let store = try library()
        try store.history.record(id: "local", text: "Sur ce Mac")
        try store.switchScope("11111111-1111-4111-8111-111111111111")
        #expect(store.history.entries.isEmpty)
        try store.history.record(id: "account", text: "Premier compte")
        #expect(store.document.entries.isEmpty)
        try store.switchScope(nil)
        #expect(store.history.entries.map(\.text) == ["Sur ce Mac"])
    }
    @Test func failedWriteAndCorruptHistoryCannotSilentlyEraseRecovery() throws {
        let store = try library()
        let file = store.directory.appendingPathComponent("dictation-history.json")
        try FileManager.default.createDirectory(at: file, withIntermediateDirectories: false)
        #expect(throws: (any Error).self) { try store.history.record(id: "one", text: "À conserver") }
        #expect(store.history.entries.isEmpty)
        let other = try library()
        let corrupt = Data("broken JSON".utf8)
        let path = other.directory.appendingPathComponent("dictation-history.json")
        try corrupt.write(to: path)
        let reopened = try LibraryStore(root: other.root)
        #expect(reopened.history.error != nil)
        #expect(throws: (any Error).self) { try reopened.history.record(id: "one", text: "Nouvelle dictée") }
        #expect(try Data(contentsOf: path) == corrupt)
    }
}
