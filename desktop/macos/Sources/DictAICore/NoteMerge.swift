import Foundation

public enum NoteMerge {
    public static func merge(local: SyncDocument, remote: [SyncDocument], actor: String) throws -> SyncDocument {
        let documents = [local] + remote
        for document in documents { try document.validateContent() }
        var merged = local
        for document in remote { merged = try merged.merging(document) }
        var values = merged.values
        var forced: [String: NoteDeletion] = [:]
        func preserve(_ entry: SyncEntry) throws {
            guard let raw = entry.value else { return }
            let copy = try Note.decode(raw).conflictCopy(stamp: entry.stamp)
            let key = "note:" + copy.id
            if merged.entries[key] == nil && values[key] == nil { values[key] = copy.canonical }
        }
        for (key, winner) in merged.entries where key.hasPrefix("note:") {
            let id = String(key.dropFirst(5))
            let candidates = documents.compactMap { $0.entries[key] }.reduce(into: [SyncEntry]()) { if !$0.contains($1) { $0.append($1) } }
            let deletion = candidates.filter { $0.value == nil }.max { $0.stamp < $1.stamp }
            if winner.value != nil, let deletion, try !dominates(winner, deletion) {
                guard let raw = merged.values["deletion:" + id] else { throw DataError.invalid("Reçu de suppression absent.") }
                let receipt = try NoteDeletion.decode(raw)
                values.removeValue(forKey: key); forced[id] = receipt
                for candidate in candidates where candidate.value != nil && (receipt._seen[candidate.stamp.actor] ?? 0) < candidate.stamp.counter { try preserve(candidate) }
            } else if let raw = winner.value {
                let note = try Note.decode(raw)
                for candidate in candidates where candidate.value != nil && candidate != winner {
                    let other = try Note.decode(candidate.value!)
                    let related = try dominates(winner, candidate) || dominates(candidate, winner)
                    if note.payload != other.payload && !related { try preserve(candidate) }
                }
            } else {
                guard let raw = merged.values["deletion:" + id] else { throw DataError.invalid("Reçu de suppression absent.") }
                let receipt = try NoteDeletion.decode(raw)
                for candidate in candidates where candidate.value != nil && (receipt._seen[candidate.stamp.actor] ?? 0) < candidate.stamp.counter { try preserve(candidate) }
            }
        }
        for (key, raw) in values where key.hasPrefix("note:") {
            for image in try Note.decode(raw).images where values["asset:" + image.id] == nil {
                let descriptor = documents.compactMap { $0.entries["asset:" + image.id] }.filter { $0.value != nil }.max { $0.stamp < $1.stamp }
                guard let descriptor else { throw DataError.invalid("Image de la version conservée absente.") }
                values["asset:" + image.id] = descriptor.value
            }
        }
        var result = try merged.recording(values, actor: actor)
        for (id, receipt) in forced { result.entries["deletion:" + id] = SyncEntry(value: receipt.canonical, stamp: result.entries["note:" + id]!.stamp) }
        try result.validateContent()
        return result
    }
    private static func dominates(_ first: SyncEntry, _ second: SyncEntry) throws -> Bool {
        if first.stamp.actor == second.stamp.actor && first.stamp.counter >= second.stamp.counter { return true }
        guard let raw = first.value else { return false }
        return (try Note.decode(raw)._seen[second.stamp.actor] ?? 0) >= second.stamp.counter
    }
}
