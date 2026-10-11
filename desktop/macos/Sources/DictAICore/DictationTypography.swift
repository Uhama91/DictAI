import Foundation

/// Small local repairs, without inferring sentence boundaries from silence.
public enum DictationTypography {
    private static let article = try! NSRegularExpression(pattern: "\\b(?i:sur|dans|avec|sans|pour|par|sous|entre|vers|chez|de|à)[ \\t]+((?:Le|La|Les|Un|Une|Des|Du|Ce|Cet|Cette|Ces)[ \\t]+(?=\\p{Ll})|L['’](?=\\p{Ll}))")
    private static let filename = try! NSRegularExpression(pattern: "^[^\\s]+\\.[A-Za-z0-9]{1,8}$")
    private static let starts = try! NSRegularExpression(pattern: "(?:^[ \\t]*|[.!?][»”)]*[ \\t]+|\\n[ \\t]*)([\\p{L}])")
    private static let question = try! NSRegularExpression(pattern: #"(?i)^(?:est-ce que\b|qu['’]est-ce que\b|(?:peux|pouvez|pourrais|pourriez|veux|voulez|dois|doit|suis|est|êtes|avez|as|a)-(?:tu|vous|je|il|elle|t-il|t-elle)\b)"#)
    private static let abbreviations: Set<String> = ["m.", "mme.", "mmes.", "mlle.", "dr.", "pr.", "ex.", "cf.", "etc.", "p.", "pp."]
    public static func live(_ text: String, language: String) -> String {
        guard language == "fr" else { return text }
        let source = text as NSString
        var result = text
        for match in article.matches(in: text, range: NSRange(location: 0, length: source.length)).reversed() {
            guard let range = Range(match.range(at: 1), in: result) else { continue }
            result.replaceSubrange(range, with: source.substring(with: match.range(at: 1)).lowercased())
        }
        return result
    }
    public static func finalized(_ text: String, language: String) -> String {
        guard language == "fr", text.contains(where: \.isLetter) else { return text }
        var result = live(text, language: language)
        let token = result.split(whereSeparator: \.isWhitespace).first.map(String.init) ?? ""
        // Technical identifiers, addresses and URLs are literal text.
        if token.contains("://") || token.contains("@") || token.contains("/") || token.contains("_") || filename.firstMatch(in: token, range: NSRange(location: 0, length: token.utf16.count)) != nil { return result }
        let source = result as NSString
        let protected = PunctuationRestoration.protectedRanges(result)
        for match in starts.matches(in: result, range: NSRange(location: 0, length: source.length)).reversed() {
            let wordRange = match.range(at: 1)
            let before = source.substring(to: wordRange.location).trimmingCharacters(in: .whitespacesAndNewlines)
            let previous = before.split(whereSeparator: \.isWhitespace).last.map(String.init) ?? ""
            guard !abbreviations.contains(previous.lowercased()),
                  !protected.contains(where: { NSIntersectionRange($0, wordRange).length > 0 }),
                  let range = Range(wordRange, in: result), let first = result[range].first else { continue }
            result.replaceSubrange(range.lowerBound...range.lowerBound, with: first.uppercased())
        }
        guard let last = result.lastIndex(where: { !$0.isWhitespace }) else { return result }
        let end = result.index(after: last)
        let prose = String(result[..<end]).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let lastSentence = prose.split(whereSeparator: { ".!?\n".contains($0) }).last.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        if (result[last].isLetter || result[last].isNumber || result[last] == "."),
           !result[..<end].hasSuffix(".."),
           !prose.contains(where: { "\"«»“”`".contains($0) }),
           question.firstMatch(in: lastSentence, range: NSRange(location: 0, length: lastSentence.utf16.count)) != nil {
            if result[last] == "." { result.remove(at: last) }
            let position = result.lastIndex(where: { !$0.isWhitespace }).map { result.index(after: $0) } ?? result.endIndex
            result.insert(contentsOf: " ?", at: position)
        } else {
            let tail = result[..<end].reversed().drop(while: { "»”\")] \t".contains($0) }).first
            if tail?.isLetter == true || tail?.isNumber == true { result.insert(".", at: end) }
        }
        return result
    }
}
