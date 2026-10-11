import Foundation
import NaturalLanguage

/// Repairs recognition casing inside a sentence without inventing punctuation.
enum SpeechCapitalization {
    private static let words = try! NSRegularExpression(pattern: "(?<![\\p{L}\\p{M}])\\p{Lu}[\\p{L}\\p{M}]*(?:['’][\\p{L}\\p{M}]+)?")
    private static let ordinary: Set<String> = ["je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "ce", "ça", "cela", "ceci", "voilà", "voici", "incohérent", "incohérente", "de", "du", "des", "et", "mais", "donc", "car", "parce", "puisque", "lorsque", "quand", "si", "que", "qui", "pour", "avec", "sans", "dans", "sur", "sous", "alors", "le", "la", "les", "un", "une"]
    private static let grammar: Set<NLTag> = [.pronoun, .determiner, .preposition, .conjunction, .adverb, .verb, .adjective, .particle, .interjection]
    private static let names: Set<NLTag> = [.personalName, .placeName, .organizationName]

    static func normalize(_ text: String, after prefix: String = "", protectedTerms: [String] = []) -> String {
        let context = DictationDraft.join(prefix, text)
        let source = context as NSString
        let offset = source.length - text.utf16.count
        let candidates = words.matches(in: context, range: NSRange(location: offset, length: text.utf16.count)).filter { $0.range.location > 0 }
        guard !candidates.isEmpty else { return text }
        let protected = PunctuationRestoration.protectedRanges(context, terms: protectedTerms)
        var tags: [Int: NLTag]?
        func grammatical(_ location: Int) -> Bool {
            if tags == nil {
                let tagger = NLTagger(tagSchemes: [.nameTypeOrLexicalClass])
                tagger.string = context
                tagger.setLanguage(.french, range: context.startIndex..<context.endIndex)
                var found: [Int: NLTag] = [:]
                tagger.enumerateTags(in: context.startIndex..<context.endIndex, unit: .word,
                                     scheme: .nameTypeOrLexicalClass, options: [.omitWhitespace, .omitPunctuation]) { tag, range in
                    if let tag { found[range.lowerBound.utf16Offset(in: context)] = tag }
                    return true
                }
                tags = found
            }
            guard let tag = tags?[location] else { return false }
            return !names.contains(tag) && grammar.contains(tag)
        }
        var result = text
        for match in candidates.reversed() {
            let word = source.substring(with: match.range)
            guard let first = word.first, first.isUppercase,
                  word.dropFirst().contains(where: \.isLowercase),
                  !protected.contains(where: { NSIntersectionRange($0, match.range).length > 0 }) else { continue }
            guard let contextRange = Range(match.range, in: context),
                  let previous = context[..<contextRange.lowerBound].reversed().first(where: { !$0.isWhitespace || "\n\r".contains($0) }),
                  !".!?…\n\r:»\"”«“([{`".contains(previous) else { continue }
            guard ordinary.contains(word.lowercased()) || grammatical(match.range.location) else { continue }
            // Articles and elisions can be part of names even before the tagger has enough context.
            let next = context[contextRange.upperBound...].drop(while: { $0.isWhitespace }).first
            let namePrefix = ["de", "du", "des", "le", "la", "les", "un", "une"].contains(word.lowercased())
            let elision = word.contains("'") || word.contains("’")
            if namePrefix && next?.isUppercase == true { continue }
            if elision && word.dropFirst(2).first?.isUppercase == true { continue }
            let local = NSRange(location: match.range.location - offset, length: String(first).utf16.count)
            guard let range = Range(local, in: result) else { continue }
            result.replaceSubrange(range, with: first.lowercased())
        }
        return result
    }
}
