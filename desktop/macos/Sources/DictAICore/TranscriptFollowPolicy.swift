import Foundation

/// A viewing preference only: following never moves the insertion point.
public struct TranscriptFollowPolicy {
    public var delay: TimeInterval
    public private(set) var following = true
    public private(set) var lastInteraction: TimeInterval?
    public init(delay: TimeInterval = 8) { self.delay = delay }
    public mutating func interact(at time: TimeInterval) { following = false; lastInteraction = time }
    public mutating func follow() { following = true; lastInteraction = nil }
    @discardableResult public mutating func resumeIfReady(at time: TimeInterval, listening: Bool, interacting: Bool) -> Bool {
        guard !following, delay > 0, listening, !interacting,
              let lastInteraction, time - lastInteraction >= delay else { return false }
        follow(); return true
    }
}
