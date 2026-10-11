import Foundation
import CryptoKit

public enum Vocabulary {
    public static func hash(_ value: String) -> String { SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined() }
    public static func key(_ line: String) -> String {
        let parts = line.components(separatedBy: "=>")
        let source = parts[0].trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
            .replacingOccurrences(of: "[\\s\u{00a0}]+", with: " ", options: .regularExpression).lowercased()
        return "vocab:" + hash((parts.count > 1 ? "rule:" : "term:") + source)
    }
    public static func apply(_ text: String, lines: [String]) -> String {
        var rules: [String: String] = [:]
        var ambiguous = Set<String>()
        for line in lines {
            let parts = line.components(separatedBy: "=>")
            guard parts.count == 2 else { continue }
            let source = parts[0].trimmingCharacters(in: .whitespaces).lowercased()
            let target = parts[1].trimmingCharacters(in: .whitespaces)
            guard !source.isEmpty, !target.isEmpty else { continue }
            if let existing = rules[source], existing != target { ambiguous.insert(source) }
            rules[source] = target
        }
        for key in ambiguous { rules.removeValue(forKey: key) }
        guard !rules.isEmpty else { return text }
        let alternatives = rules.keys.sorted { $0.count > $1.count }.map(NSRegularExpression.escapedPattern(for:)).joined(separator: "|")
        guard let regex = try? NSRegularExpression(pattern: "(?<![\\p{L}\\p{N}_])(?:" + alternatives + ")(?![\\p{L}\\p{N}_])", options: [.caseInsensitive]) else { return text }
        let original = text as NSString
        let result = NSMutableString(string: text)
        for match in regex.matches(in: text, range: NSRange(location: 0, length: original.length)).reversed() {
            if let target = rules[original.substring(with: match.range).lowercased()] { result.replaceCharacters(in: match.range, with: target) }
        }
        return result as String
    }
    public static func suggestion(before: String, after: String) -> String? {
        let a = before.split(whereSeparator: \.isWhitespace).map(String.init)
        let b = after.split(whereSeparator: \.isWhitespace).map(String.init)
        guard !a.isEmpty, !b.isEmpty, a != b else { return nil }
        var prefix = 0
        while prefix < min(a.count, b.count) && a[prefix] == b[prefix] { prefix += 1 }
        var endA = a.count; var endB = b.count
        while endA > prefix && endB > prefix && a[endA - 1] == b[endB - 1] { endA -= 1; endB -= 1 }
        guard (1...4).contains(endA - prefix), (1...4).contains(endB - prefix) else { return nil }
        let old = a[prefix..<endA].joined(separator: " ").trimmingCharacters(in: .punctuationCharacters)
        let new = b[prefix..<endB].joined(separator: " ").trimmingCharacters(in: .punctuationCharacters)
        guard !old.isEmpty, !new.isEmpty, old != new, old.count <= 80, new.count <= 80 else { return nil }
        return old + " => " + new
    }
}
