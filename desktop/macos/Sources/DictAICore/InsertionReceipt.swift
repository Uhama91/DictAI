import Foundation

public enum InsertionReceipt {
    public enum Result: Equatable { case confirmed, unchanged, changed, unreadable }
    public static func expected(original: String?, selection: NSRange?, inserted: String) -> String? {
        guard let original, let selection, selection.location != NSNotFound,
              selection.location >= 0, selection.length >= 0,
              selection.location <= original.utf16.count,
              selection.length <= original.utf16.count - selection.location else { return nil }
        return (original as NSString).replacingCharacters(in: selection, with: inserted)
    }
    public static func check(original: String?, expected: String?, actual: String?) -> Result {
        guard let actual else { return .unreadable }
        if let expected, normalized(actual) == normalized(expected) { return .confirmed }
        if let original, actual == original { return .unchanged }
        return .changed
    }
    private static func normalized(_ value: String) -> String {
        value.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\u{00a0}", with: " ")
            .replacingOccurrences(of: "\u{202f}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
