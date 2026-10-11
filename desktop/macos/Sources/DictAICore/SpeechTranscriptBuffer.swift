import Foundation

/// Apple may return only the next utterance after a silence, within the same task.
/// Keep completed utterances separate from the hypothesis that is still being revised.
public struct SpeechTranscriptBuffer {
    private var committed = ""
    private var current = ""
    private var firstTime: TimeInterval?
    private var lastEnd: TimeInterval?
    private var utteranceComplete = false
    public init() {}
    public var text: String { SpeechTextJoin.join(committed, current) }

    @discardableResult public mutating func receive(_ value: String, start: TimeInterval? = nil,
                                                    end: TimeInterval? = nil, complete: Bool = false) -> String {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return text }
        let oldWords = words(current), newWords = words(value)
        let cumulative = newWords.starts(with: oldWords) || oldWords.starts(with: newWords)
        let nextTime = start.flatMap { start in
            lastEnd.map { end in end > 0 && start >= end - 0.04 && start > (firstTime ?? 0) + 0.1 }
        } ?? false
        if !current.isEmpty && (nextTime || (utteranceComplete && !cumulative)) {
            committed = SpeechTextJoin.join(committed, current)
            firstTime = nil; lastEnd = nil; utteranceComplete = false
        } else if value != current && newWords.count > oldWords.count { utteranceComplete = false }
        current = value
        if let start, let end, end > start { firstTime = start; lastEnd = end }
        utteranceComplete = utteranceComplete || complete
        return text
    }
    private func words(_ text: String) -> [String] {
        text.split(whereSeparator: \.isWhitespace).map { String($0).trimmingCharacters(in: .punctuationCharacters).lowercased() }
    }
}
