import Foundation
import Testing
@testable import DictAI

@MainActor struct VocabularyLearningTests {
    @Test func savedCorrectionStopsBeingOfferedWithoutAddingAStatusLine() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dictai-learning-" + UUID().uuidString)
        let model = try AppModel(root: root)
        model.draft.receive("Je lis les noncés.")
        model.edit("Je lis l’énoncé.")
        let rule = try #require(model.learningSuggestion)
        #expect(rule == "les noncés => l’énoncé")
        model.learn(rule)
        #expect(model.learningSuggestion == nil)
        #expect(model.message.isEmpty)
        #expect(model.draft.text == "Je lis l’énoncé.")
        let restored = try AppModel(root: root)
        #expect(restored.library.vocabulary.map(\.value) == ["les noncés => l’énoncé"])
        #expect(restored.learningSuggestion == nil)
        restored.edit("Je lis l’annoncé.")
        #expect(restored.learningSuggestion == "les noncés => l’annoncé")
    }

    @Test func existingCorrectionIsRecognizedDespiteSourceCaseAndPunctuationChanges() throws {
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-learning-" + UUID().uuidString))
        try model.library.addVocabulary("les noncés => l’énoncé")
        model.draft.receive("Les noncés")
        #expect(model.learningSuggestion == nil)
        model.edit("l’énoncé.")
        #expect(model.learningSuggestion == nil)
        model.library.removeVocabulary(model.library.vocabulary[0].key)
        #expect(model.learningSuggestion == "Les noncés => l’énoncé")
    }

    @Test func failedSaveKeepsTheCorrectionAvailable() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dictai-learning-" + UUID().uuidString)
        let model = try AppModel(root: root)
        model.draft.receive("Je lis les noncés.")
        model.edit("Je lis l’énoncé.")
        let rule = try #require(model.learningSuggestion)
        try FileManager.default.createDirectory(at: model.library.directory.appendingPathComponent("library.json"), withIntermediateDirectories: false)
        model.learn(rule)
        #expect(model.library.error != nil)
        #expect(model.library.vocabulary.isEmpty)
        #expect(model.learningSuggestion == rule)
        #expect(model.message.isEmpty)
    }
}
