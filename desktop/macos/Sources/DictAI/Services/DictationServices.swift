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

struct LiveDictationCleaner: DictationCleaning {
    var credential: () throws -> Data? = { try Keychain.read("openrouter-key") }
    var session: URLSession?
    func process(_ text: String, preferences: [String: String], vocabulary: [String]) async throws -> String {
        let data = try credential()
        let key = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        let model = CleanupModel.all.first { $0.id == preferences["pref:cloud_cleanup_model"] } ?? CleanupModel.default
        return try await CloudCleanup.process(text, model: model, language: preferences["pref:dictation_language"] ?? "fr", vocabulary: vocabulary, key: key, session: session)
    }
}
