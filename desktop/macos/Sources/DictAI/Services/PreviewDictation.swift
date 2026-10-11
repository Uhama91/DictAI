import Foundation
import AppKit
import DictAICore

enum PreviewMode {
    static let enabled = ProcessInfo.processInfo.arguments.contains("--preview") || Bundle.main.object(forInfoDictionaryKey: "DictAIPreview") as? Bool == true
    static let dictation = enabled && (ProcessInfo.processInfo.arguments.contains("--preview-dictation") || Bundle.main.object(forInfoDictionaryKey: "DictAIPreview") as? Bool == true)
}

/// Explicit --preview --preview-dictation fixture: ephemeral library, no microphone or external insertion.
@MainActor final class PreviewSpeech: SpeechCapturing {
    var onText: ((String) -> Void)?
    var onLevel: ((Float) -> Void)?
    var onFinish: ((String?) -> Void)?
    private var stream: Task<Void, Never>?
    func start(language: String, vocabulary: [String]) async throws {
        cancel()
        stream = Task {
            let introduction = "Préparer la réunion de jeudi à neuf heures.\n\nRassembler les documents, vérifier l’ordre du jour et noter les prochaines étapes."
            let paragraphs = (1...24).map { "\n\nPoint \($0). La dictée poursuit son chemin pendant que je relis une phrase précédente. Je peux corriger un mot, prendre le temps de réfléchir et retrouver ensuite les nouvelles idées en bas de la note." }
            let words = (introduction + paragraphs.joined()).split(separator: " ")
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 140_000_000)
                guard !Task.isCancelled else { return }
                if tick < words.count { onText?(words.prefix(tick + 1).joined(separator: " ")) }
                onLevel?(Float(0.5 + 0.4 * sin(Double(tick) * 0.7)))
                tick += 1
            }
        }
    }
    func stop() { cancel(); onFinish?(nil) }
    func cancel() { stream?.cancel(); stream = nil; onLevel?(0) }
}

@MainActor final class PreviewTextDelivery: TextDelivering {
    var available: Bool { false }
    var targetName: String { "Aperçu isolé" }
    func capture() {}
    func insert(_ text: String) async throws -> String { throw DataError.invalid("L’aperçu isolé n’insère aucun texte dans les autres applications.") }
    func copy(_ text: String) { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string) }
}
