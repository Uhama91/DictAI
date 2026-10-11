import Foundation

public enum PunctuationRestoration {
    private static let words = try! NSRegularExpression(pattern: "[\\p{L}\\p{M}\\p{N}]+")
    private static let literals = try! NSRegularExpression(pattern: #""[^"]*"|«[^»]*»|“[^”]*”|`[^`]*`|\S*(?:https?://|www\.|@|/|\\|\.[A-Za-z]{2,8})\S*"#)
    private static let punctuation = CharacterSet(charactersIn: ".,;:!?… \t\r\n\u{00a0}\u{202f}")
    private static let termPatterns: NSCache<NSString, NSRegularExpression> = {
        let cache = NSCache<NSString, NSRegularExpression>()
        cache.countLimit = 512
        return cache
    }()

    /// Accept punctuation/case only. Rebuild literals from the source, not the model.
    public static func accept(_ candidate: String, for source: String, protectedTerms: [String] = []) -> String? {
        let original = source as NSString, proposed = candidate as NSString
        let old = words.matches(in: source, range: NSRange(location: 0, length: original.length))
        let new = words.matches(in: candidate, range: NSRange(location: 0, length: proposed.length))
        guard !old.isEmpty, old.count == new.count,
              zip(old, new).allSatisfy({ original.substring(with: $0.range).lowercased() == proposed.substring(with: $1.range).lowercased() }) else { return nil }
        let protected = protectedRanges(source, terms: protectedTerms)
        func overlaps(_ range: NSRange) -> Bool { protected.contains { NSIntersectionRange($0, range).length > 0 } }
        var output = ""
        for index in 0...old.count {
            let start = index == 0 ? 0 : NSMaxRange(old[index - 1].range)
            let end = index == old.count ? original.length : old[index].range.location
            let nextStart = index == 0 ? 0 : NSMaxRange(new[index - 1].range)
            let nextEnd = index == new.count ? proposed.length : new[index].range.location
            let range = NSRange(location: start, length: end - start)
            let gap = original.substring(with: range)
            let replacement = proposed.substring(with: NSRange(location: nextStart, length: nextEnd - nextStart))
            guard gap.filter({ $0 == "\n" }).count == replacement.filter({ $0 == "\n" }).count else { return nil }
            if overlaps(range) || gap.unicodeScalars.contains(where: { !punctuation.contains($0) }) {
                output += gap
            } else {
                guard replacement.unicodeScalars.allSatisfy({ punctuation.contains($0) }) else { return nil }
                output += replacement
            }
            if index < old.count {
                output += overlaps(old[index].range) ? original.substring(with: old[index].range) : proposed.substring(with: new[index].range)
            }
        }
        return output
    }

    static func protectedRanges(_ text: String, terms: [String] = []) -> [NSRange] {
        let value = text as NSString, full = NSRange(location: 0, length: value.length)
        var ranges = literals.matches(in: text, range: full).map(\.range)
        for term in terms where !term.isEmpty {
            let key = term as NSString
            let expression: NSRegularExpression
            if let cached = termPatterns.object(forKey: key) { expression = cached }
            else {
                let pattern = "(?<![\\p{L}\\p{N}_])" + NSRegularExpression.escapedPattern(for: term) + "(?![\\p{L}\\p{N}_])"
                guard let compiled = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { continue }
                expression = compiled; termPatterns.setObject(compiled, forKey: key)
            }
            ranges += expression.matches(in: text, range: full).map(\.range)
        }
        for match in words.matches(in: text, range: full) {
            let word = value.substring(with: match.range), letters = word.filter(\.isLetter)
            let acronym = letters.count > 1 && letters.allSatisfy(\.isUppercase)
            let mixedCase = letters.dropFirst().contains(where: \.isUppercase) && letters.contains(where: \.isLowercase)
            if acronym || mixedCase { ranges.append(match.range) }
        }
        return ranges
    }
}
