import Foundation

/// Exclusive commands: one/two releases wait for another press; the third wins.
/// Timestamps come from the same monotonic clock as AppKit events.
public struct OptionGesture {
    public enum Action { case single, double, triple }
    public let interval: TimeInterval
    public private(set) var deadline: TimeInterval?
    private var downAt: TimeInterval?
    private var eligible = false
    private var count = 0
    private var quietUntil: TimeInterval?
    public init(interval: TimeInterval = 0.5) {
        self.interval = interval.isFinite && interval > 0 ? interval : 0.5
    }
    public mutating func flags(option: Bool, otherModifier: Bool, at time: TimeInterval) -> Action? {
        // A chord invalidates the whole sequence, including taps awaiting resolution.
        if otherModifier { interrupt() }
        let expired = otherModifier ? nil : advance(at: time)
        if option {
            guard downAt == nil else { return expired }
            downAt = time
            eligible = !otherModifier && time >= (quietUntil ?? 0)
            deadline = nil
            return expired
        }
        guard let pressed = downAt else { return expired }
        downAt = nil
        if let quietUntil, time < quietUntil {
            self.quietUntil = time + interval
            interrupt(); return expired
        }
        guard eligible, (0.025...max(1.2, interval * 2)).contains(time - pressed) else {
            interrupt(); return expired
        }
        eligible = false; count += 1
        if count == 3 {
            count = 0; deadline = nil; quietUntil = time + interval
            return .triple
        }
        deadline = time + interval
        return expired
    }
    public mutating func advance(at time: TimeInterval) -> Action? {
        guard downAt == nil, let deadline, time >= deadline else { return nil }
        let result: Action? = count == 1 ? .single : count == 2 ? .double : nil
        count = 0; self.deadline = nil
        return result
    }
    public mutating func interrupt() { eligible = false; count = 0; deadline = nil }
    public mutating func reset() { downAt = nil; quietUntil = nil; interrupt() }
}
