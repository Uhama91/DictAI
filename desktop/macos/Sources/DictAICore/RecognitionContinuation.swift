import Foundation

/// Locates the already dictated prefix when recognition revises words after a manual edit.
enum RecognitionContinuation {
    static func prefixLength(anchor: [Substring], incoming: [Substring], allowSingleWordRevision: Bool) -> Int? {
        let old = anchor.map(word), new = incoming.map(word)
        var prefix = 0
        while prefix < min(old.count, new.count), old[prefix] == new[prefix] { prefix += 1 }
        if prefix == old.count { return prefix }

        let a = Array(old.dropFirst(prefix)), b = Array(new.dropFirst(prefix))
        guard a.count * b.count <= 1_000_000 else { return nil }
        // Include the continuation in the alignment so revised prefix words are not
        // mistaken for new speech. Android's editable transcript uses the same rule.
        var costs = Array(repeating: Array(repeating: 0, count: b.count + 1), count: a.count + 1)
        var matched = costs
        for i in stride(from: a.count, through: 0, by: -1) {
            for j in stride(from: b.count, through: 0, by: -1) {
                if i == a.count { costs[i][j] = b.count - j }
                else if j == b.count { costs[i][j] = a.count - i }
                else {
                    let same = a[i] == b[j]
                    var best = (costs[i + 1][j + 1] + (same ? 0 : 1), matched[i + 1][j + 1] + (same ? 1 : 0))
                    for candidate in [(costs[i + 1][j] + 1, matched[i + 1][j]), (costs[i][j + 1] + 1, matched[i][j + 1])] {
                        if candidate.0 < best.0 || (candidate.0 == best.0 && candidate.1 > best.1) { best = candidate }
                    }
                    costs[i][j] = best.0; matched[i][j] = best.1
                }
            }
        }
        var i = 0, j = 0, edits = 0, matches = prefix
        while i < a.count {
            if j == b.count { i += 1; edits += 1 }
            else if costs[i][j] == costs[i + 1][j + 1] + (a[i] == b[j] ? 0 : 1) &&
                        matched[i][j] == matched[i + 1][j + 1] + (a[i] == b[j] ? 1 : 0) {
                if a[i] == b[j] { matches += 1 } else { edits += 1 }
                i += 1; j += 1
            } else if costs[i][j] == costs[i + 1][j] + 1 && matched[i][j] == matched[i + 1][j] {
                i += 1; edits += 1
            }
            else { j += 1; edits += 1 }
        }
        // An unrelated replacement cannot resurrect an erased draft.
        guard edits <= max(1, old.count / 2),
              matches > 0 || (old.count == 1 && allowSingleWordRevision) else { return nil }
        return prefix + j
    }

    private static func word(_ value: Substring) -> String {
        String(value).trimmingCharacters(in: .punctuationCharacters)
            .replacingOccurrences(of: "’", with: "'").lowercased()
    }
}
