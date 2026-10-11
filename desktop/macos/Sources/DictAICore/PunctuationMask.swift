import Foundation

public struct PunctuationMask {
    public let text: String
    private let literals: [(String, String)]
    public init(_ source: String, protectedTerms: [String] = []) {
        var merged: [NSRange] = []
        for range in PunctuationRestoration.protectedRanges(source, terms: protectedTerms).sorted(by: { $0.location < $1.location }) {
            if let last = merged.last, range.location < NSMaxRange(last) { merged[merged.count - 1] = NSUnionRange(last, range) }
            else { merged.append(range) }
        }
        var prefix = "DICTAILITERAL"
        while source.localizedCaseInsensitiveContains(prefix) { prefix += "X" }
        let original = source as NSString
        literals = merged.enumerated().map { (prefix + String($0.offset), original.substring(with: $0.element)) }
        let masked = NSMutableString(string: source)
        for (index, range) in merged.enumerated().reversed() { masked.replaceCharacters(in: range, with: literals[index].0) }
        text = masked as String
    }
    public func restore(_ candidate: String) -> String? {
        let result = NSMutableString(string: candidate)
        for (placeholder, literal) in literals {
            let expression = try! NSRegularExpression(pattern: "\\b" + placeholder + "\\b", options: .caseInsensitive)
            let matches = expression.matches(in: result as String, range: NSRange(location: 0, length: result.length))
            guard matches.count == 1 else { return nil }
            result.replaceCharacters(in: matches[0].range, with: literal)
        }
        return result as String
    }
}
