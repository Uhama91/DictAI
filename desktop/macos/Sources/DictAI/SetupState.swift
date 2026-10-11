import Foundation
import Combine

/// Device settings are deliberately outside the account's synchronized library.
@MainActor final class SetupState: ObservableObject {
    private struct Saved: Codable {
        var completed = false
    }
    private let file: URL
    private var saved: Saved
    @Published var presented: Bool
    @Published var step = 0

    init(root: URL) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        file = root.appendingPathComponent("setup.json")
        saved = FileManager.default.fileExists(atPath: file.path)
            ? try JSONDecoder().decode(Saved.self, from: Data(contentsOf: file)) : Saved()
        presented = !saved.completed
    }
    func restart() { step = 0; presented = true }
    func complete() throws {
        var next = saved; next.completed = true
        try save(next)
        presented = false
    }
    private func save(_ next: Saved) throws {
        try JSONEncoder().encode(next).write(to: file, options: .atomic)
        saved = next
    }
}

struct SetupReadiness {
    var microphone: Bool
    var speech: Bool
    var accessibility: Bool
    var localRecognition: Bool
    var recognitionAvailable: Bool
    var canDictate: Bool { microphone && speech && localRecognition && recognitionAvailable }
    var canInsert: Bool { canDictate && accessibility }
}
