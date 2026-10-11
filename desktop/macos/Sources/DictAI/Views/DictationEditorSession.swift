import Foundation
import Combine
import DictAICore

@MainActor final class DictationEditorSession: ObservableObject {
    @Published private(set) var policy: TranscriptFollowPolicy
    var selection: NSRange?
    var selectionText = ""
    var listening = false { didSet { scheduleReturn() } }
    var interacting = false
    private var timer: Task<Void, Never>?
    init() {
        let saved = UserDefaults.standard.object(forKey: "transcriptFollowDelay") as? Double
        policy = TranscriptFollowPolicy(delay: [0, 5, 8, 15].contains(saved ?? 8) ? saved ?? 8 : 8)
    }
    func setDelay(_ delay: TimeInterval) {
        policy.delay = delay; UserDefaults.standard.set(delay, forKey: "transcriptFollowDelay")
        scheduleReturn()
    }
    func interact(active: Bool = false) {
        interacting = active
        policy.interact(at: ProcessInfo.processInfo.systemUptime)
        scheduleReturn()
    }
    func follow() { timer?.cancel(); policy.follow() }
    func reset() { selection = nil; selectionText = ""; interacting = false; follow() }
    func remember(_ range: NSRange, in text: String) { selection = range; selectionText = text }
    func restoredSelection(in text: String) -> NSRange {
        guard let selection else { return NSRange(location: text.utf16.count, length: 0) }
        return TextMutation(from: selectionText, to: text).rebasing(selection)
    }
    private func scheduleReturn() {
        timer?.cancel()
        guard listening, !interacting, !policy.following, policy.delay > 0, let last = policy.lastInteraction else { return }
        let remaining = max(0, policy.delay - (ProcessInfo.processInfo.systemUptime - last))
        timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            if !self.policy.resumeIfReady(at: ProcessInfo.processInfo.systemUptime, listening: self.listening, interacting: self.interacting) {
                self.scheduleReturn()
            }
        }
    }
}
