import Foundation

/// One minimal replacement, bounded by complete Swift Characters, with AppKit UTF-16 offsets.
public struct TextMutation {
    public let range: NSRange
    public let replacement: String
    public init(from old: String, to new: String) {
        let a = Array(old), b = Array(new)
        var prefix = 0, suffix = 0
        while prefix < min(a.count, b.count), a[prefix] == b[prefix] { prefix += 1 }
        while suffix < min(a.count, b.count) - prefix, a[a.count - 1 - suffix] == b[b.count - 1 - suffix] { suffix += 1 }
        range = NSRange(location: String(a.prefix(prefix)).utf16.count,
                        length: String(a[prefix..<(a.count - suffix)]).utf16.count)
        replacement = String(b[prefix..<(b.count - suffix)])
    }
    public func applying(to text: String) -> String { (text as NSString).replacingCharacters(in: range, with: replacement) }
    public func rebasing(_ selection: NSRange) -> NSRange {
        func offset(_ value: Int) -> Int {
            if value <= range.location { return value }
            if value >= NSMaxRange(range) { return value + replacement.utf16.count - range.length }
            return range.location + min(value - range.location, replacement.utf16.count)
        }
        let start = offset(selection.location), end = offset(NSMaxRange(selection))
        return NSRange(location: start, length: max(0, end - start))
    }
    public static func mergingEdit(from rendered: String, to edited: String, into latest: String) -> String {
        let user = TextMutation(from: rendered, to: edited)
        let remote = TextMutation(from: rendered, to: latest)
        return (latest as NSString).replacingCharacters(in: remote.rebasing(user.range), with: user.replacement)
    }
}
