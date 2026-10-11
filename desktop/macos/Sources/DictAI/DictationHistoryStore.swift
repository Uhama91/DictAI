import Foundation
import Combine
import DictAICore

struct RecentDictation: Codable, Identifiable, Equatable {
    let id: String
    let text: String
    let date: Date
}

/// A bounded local recovery buffer, deliberately absent from SyncDocument.
@MainActor final class DictationHistoryStore: ObservableObject {
    static let limit = 10
    @Published private(set) var entries: [RecentDictation] = []
    @Published private(set) var error: String?
    private var file: URL?
    private var unreadable = false

    func load(directory: URL) {
        file = directory.appendingPathComponent("dictation-history.json")
        entries = []; error = nil; unreadable = false
        do {
            guard let file, FileManager.default.fileExists(atPath: file.path) else { return }
            let decoded = try JSONDecoder().decode([RecentDictation].self, from: Data(contentsOf: file))
            entries = Array(decoded.prefix(Self.limit))
        } catch {
            unreadable = true
            self.error = "L’historique ne peut pas être lu. Son fichier est conservé : " + error.localizedDescription
        }
    }

    func record(id: String, text: String, date: Date = Date()) throws {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        guard !unreadable else { throw DataError.invalid(error ?? "Historique illisible.") }
        if entries.first(where: { $0.id == id })?.text == clean { return }
        let originalDate = entries.first(where: { $0.id == id })?.date ?? date
        let next = [RecentDictation(id: id, text: clean, date: originalDate)] + entries.filter { $0.id != id }
        try write(Array(next.prefix(Self.limit)))
    }

    func clear() throws {
        try write([])
        unreadable = false
    }

    private func write(_ next: [RecentDictation]) throws {
        guard let file else { throw DataError.invalid("Historique indisponible.") }
        try JSONEncoder().encode(next).write(to: file, options: .atomic)
        entries = next; error = nil
    }
}
