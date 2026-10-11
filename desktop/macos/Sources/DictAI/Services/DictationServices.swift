import Foundation
import DictAICore

@MainActor protocol SpeechCapturing: AnyObject {
    var onText: ((String) -> Void)? { get set }
    var onLevel: ((Float) -> Void)? { get set }
    var onFinish: ((String?) -> Void)? { get set }
    func start(language: String, vocabulary: [String]) async throws
    func stop()
    func cancel()
}

@MainActor protocol TextDelivering: AnyObject {
    var available: Bool { get }
    var targetName: String { get }
    var issue: String? { get }
    func capture()
    func prepareForInsertion()
    func insert(_ text: String) async throws -> String
    func copy(_ text: String)
}
extension TextDelivering {
    var issue: String? { nil }
    func prepareForInsertion() {}
}

@MainActor protocol OverlayPresenting: AnyObject {
    func show(focus: Bool)
    func hide()
}
extension OverlayPresenting {
    func show() { show(focus: false) }
}

@MainActor protocol DictationCleaning {
    func process(_ text: String, preferences: [String: String], vocabulary: [String]) async throws -> String
}

@MainActor protocol DictationPunctuating {
    var available: Bool { get }
    func prepare()
    func process(_ text: String, protectedTerms: [String]) async throws -> String?
}
extension DictationPunctuating { func prepare() {} }
struct UnavailablePunctuator: DictationPunctuating {
    let available = false
    func process(_ text: String, protectedTerms: [String]) async throws -> String? { nil }
}
struct LiveDictationPunctuator: DictationPunctuating {
    private let model = PunctuationModel()
    var available: Bool { model.available }
    func prepare() { model.prepare() }
    func process(_ text: String, protectedTerms: [String]) async throws -> String? {
        try await model.process(text, protectedTerms: protectedTerms)
    }
}

struct LiveDictationCleaner: DictationCleaning {
    func process(_ text: String, preferences: [String: String], vocabulary: [String]) async throws -> String {
        let data = try Keychain.read("openrouter-key")
        let key = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        let model = CleanupModel.all.first { $0.id == preferences["pref:cloud_cleanup_model"] } ?? CleanupModel.all[0]
        let format = preferences["pref:selected_format"] ?? "cleanup"
        let builtins = ["cleanup": "Supprime seulement les hésitations et les répétitions inutiles.", "corrected": "Corrige la grammaire et la ponctuation.", "list": "Présente les éléments dictés sous forme de liste.", "email": "Mets en forme un courriel, sans inventer de destinataire ni de signature."]
        let custom = preferences["format:" + format].flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: String] }
        return try await CloudCleanup.process(text, model: model, instructions: custom?["instructions"] ?? builtins[format] ?? builtins["cleanup"]!, language: preferences["pref:dictation_language"] ?? "fr", vocabulary: vocabulary, key: key)
    }
}
