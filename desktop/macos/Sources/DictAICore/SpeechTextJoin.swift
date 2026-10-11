import Foundation

/// A recognizer restart is not a sentence boundary.
enum SpeechTextJoin {
    static func join(_ prefix: String, _ suffix: String, language: String = "fr", protectedTerms: [String] = []) -> String {
        guard language == "fr" else { return DictationDraft.join(prefix, suffix) }
        return DictationDraft.join(prefix, SpeechCapitalization.normalize(suffix, after: prefix, protectedTerms: protectedTerms))
    }
}
