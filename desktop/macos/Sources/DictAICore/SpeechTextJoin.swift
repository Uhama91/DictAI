import Foundation

/// A recognizer restart is not a sentence boundary. Only repair unambiguous
/// French connecting words; leave names, acronyms and explicit punctuation alone.
enum SpeechTextJoin {
    static func join(_ prefix: String, _ suffix: String) -> String {
        let previous = prefix.trimmingCharacters(in: .whitespaces)
        let word = String(suffix.prefix(while: { $0.isLetter }))
        let next = suffix.split(whereSeparator: \.isWhitespace).dropFirst().first?.first
        let continuations: Set<String> = ["De", "Du", "Des", "Et", "Mais", "Donc", "Car", "Parce", "Puisque", "Lorsque", "Quand", "Si", "Que", "Qui", "Pour", "Avec", "Sans", "Dans", "Sur", "Sous", "Alors", "Le", "La", "Les"]
        let elision = ["L", "D"].contains(word) && suffix.dropFirst().first.map { "'’".contains($0) } == true && suffix.dropFirst(2).first?.isLowercase == true
        guard let last = previous.last, !".!?…\n\r:»\"”".contains(last), next?.isUppercase != true,
              (continuations.contains(word) || elision), let first = suffix.first else {
            return DictationDraft.join(prefix, suffix)
        }
        return DictationDraft.join(prefix, first.lowercased() + suffix.dropFirst())
    }
}
