import Foundation

/// A persistent document, independent of the recognizer's shorter-lived requests.
public struct DictationDraft: Codable {
    public private(set) var text = ""
    public private(set) var recognized = ""
    public private(set) var edited = false
    private var baseText = ""
    private var baseRecognition = ""
    private var editAnchor: String?
    private var editText = ""
    private enum CodingKeys: String, CodingKey { case text, recognized, edited }
    public init() {}

    public mutating func beginSegment() {
        baseText = text; baseRecognition = recognized
        editAnchor = nil; editText = ""
    }
    public mutating func receive(_ value: String) {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let incoming = SpeechTextJoin.join(baseRecognition, value)
        recognized = incoming
        if let anchor = editAnchor {
            // Only genuinely new words after the edited recognition snapshot may be appended.
            // A replacement hypothesis must never undo an intentional user deletion.
            let old = anchor.split(whereSeparator: \.isWhitespace)
            let new = incoming.split(whereSeparator: \.isWhitespace)
            guard new.count >= old.count,
                  zip(old, new).allSatisfy({ Self.word($0) == Self.word($1) }) else { return }
            let boundary = old.isEmpty ? incoming.startIndex : new[old.count - 1].endIndex
            let suffix = String(incoming[boundary...])
            text = Self.join(punctuatedEdit(anchor: old, incoming: new), suffix)
        } else { text = SpeechTextJoin.join(baseText, value) }
    }
    public mutating func edit(_ value: String) {
        guard value != text else { return }
        text = value; edited = true; editAnchor = recognized; editText = value
    }
    public mutating func normalizeRecognition(_ value: String) {
        guard !edited else { return }
        text = value; recognized = value
    }
    public static func join(_ prefix: String, _ suffix: String) -> String {
        guard !prefix.isEmpty else { return suffix }
        guard !suffix.isEmpty else { return prefix }
        return prefix + (prefix.last?.isWhitespace == true || suffix.first?.isWhitespace == true ? "" : " ") + suffix
    }
    private static func word(_ word: Substring) -> String {
        String(word).trimmingCharacters(in: .punctuationCharacters).lowercased()
    }
    private func punctuatedEdit(anchor: [Substring], incoming: [Substring]) -> String {
        // A word correction must not swallow punctuation finalized later by the recognizer.
        // Only accept newly added punctuation at this boundary; manual punctuation wins.
        let end = editText.lastIndex(where: { !$0.isWhitespace })
        guard let end, let previous = anchor.last?.last,
              previous.isLetter || previous.isNumber,
              editText[end].isLetter || editText[end].isNumber,
              editText.split(whereSeparator: \.isWhitespace).count == anchor.count else { return editText }
        let punctuation = incoming[anchor.count - 1].reversed().prefix { ".,;:!?…".contains($0) }.reversed()
        guard !punctuation.isEmpty else { return editText }
        return String(editText[...end]) + String(punctuation) + editText[editText.index(after: end)...]
    }
}
