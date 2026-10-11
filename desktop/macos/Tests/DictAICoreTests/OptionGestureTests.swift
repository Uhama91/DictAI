import Testing
@testable import DictAICore

struct OptionGestureTests {
    @Test func singleWaitsForThePossibilityOfADoubleTap() {
        var key = OptionGesture(interval: 0.4)
        let down = key.flags(option: true, otherModifier: false, at: 1)
        let up = key.flags(option: false, otherModifier: false, at: 1.08)
        let early = key.advance(at: 1.47)
        let result = key.advance(at: 1.49)
        let duplicate = key.advance(at: 2)
        #expect(down == nil && up == nil && early == nil)
        #expect(result == .single)
        #expect(duplicate == nil)
    }
    @Test func doubleTapNeverAlsoEmitsASingle() {
        var key = OptionGesture(interval: 0.4)
        #expect(tap(&key, down: 1, up: 1.08) == nil)
        #expect(tap(&key, down: 1.24, up: 1.32) == nil)
        let early = key.advance(at: 1.6)
        let result = key.advance(at: 1.73)
        #expect(early == nil)
        #expect(result == .double)
    }
    @Test func tripleCancelsImmediatelyAndAnExtraTapCannotRestart() {
        var key = OptionGesture(interval: 0.4)
        _ = tap(&key, down: 1, up: 1.08)
        _ = tap(&key, down: 1.2, up: 1.28)
        #expect(tap(&key, down: 1.4, up: 1.48) == .triple)
        #expect(tap(&key, down: 1.6, up: 1.68) == nil)
        let quiet = key.advance(at: 2.2)
        #expect(quiet == nil)
        _ = tap(&key, down: 3, up: 3.08)
        let next = key.advance(at: 3.49)
        #expect(next == .single)
    }
    @Test func secondPressSuspendsTheFirstDeadlineUntilRelease() {
        var key = OptionGesture(interval: 0.4)
        _ = tap(&key, down: 1, up: 1.08)
        _ = key.flags(option: true, otherModifier: false, at: 1.45)
        let held = key.advance(at: 1.6)
        let up = key.flags(option: false, otherModifier: false, at: 1.7)
        let result = key.advance(at: 2.11)
        #expect(held == nil && up == nil)
        #expect(result == .double)
    }
    @Test func systemIntervalDeterminesWhetherTapsBelongTogether() {
        var fast = OptionGesture(interval: 0.25), slow = OptionGesture(interval: 0.75)
        _ = tap(&fast, down: 1, up: 1.08)
        _ = tap(&slow, down: 1, up: 1.08)
        // Even if a timer ran late, the event timestamps separate the fast sequence.
        let fastDown = fast.flags(option: true, otherModifier: false, at: 1.5)
        let slowDown = slow.flags(option: true, otherModifier: false, at: 1.5)
        _ = fast.flags(option: false, otherModifier: false, at: 1.58)
        _ = slow.flags(option: false, otherModifier: false, at: 1.58)
        let fastResult = fast.advance(at: 2.4), slowResult = slow.advance(at: 2.4)
        #expect(fastDown == .single && slowDown == nil)
        #expect(fastResult == .single && slowResult == .double)
    }
    @Test func keyboardMouseOrModifierChordsCancelThePendingSequence() {
        var key = OptionGesture(interval: 0.4)
        _ = tap(&key, down: 1, up: 1.08)
        _ = key.flags(option: true, otherModifier: false, at: 1.2)
        key.interrupt()
        _ = key.flags(option: false, otherModifier: false, at: 1.3)
        let cancelled = key.advance(at: 2)
        #expect(cancelled == nil)
        _ = key.flags(option: true, otherModifier: true, at: 3)
        _ = key.flags(option: false, otherModifier: false, at: 3.1)
        let chord = key.advance(at: 4)
        #expect(chord == nil)
    }
    @Test func longHoldsBouncesAndRepeatedFlagsNeverBecomeCommands() {
        var key = OptionGesture(interval: 0.4)
        #expect(tap(&key, down: 1, up: 3) == nil)
        let held = key.advance(at: 4)
        #expect(held == nil)
        _ = tap(&key, down: 5, up: 5.01)
        let bounce = key.advance(at: 6)
        #expect(bounce == nil)
        _ = key.flags(option: true, otherModifier: false, at: 7)
        _ = key.flags(option: true, otherModifier: false, at: 7.01)
        _ = key.flags(option: false, otherModifier: false, at: 7.08)
        _ = key.flags(option: false, otherModifier: false, at: 7.09)
        let once = key.advance(at: 7.5)
        #expect(once == .single)
    }
    @Test func resetDiscardsEveryPendingAction() {
        var key = OptionGesture(interval: 0.4)
        _ = tap(&key, down: 1, up: 1.08)
        key.reset()
        let result = key.advance(at: 2)
        #expect(result == nil && key.deadline == nil)
    }
    private func tap(_ key: inout OptionGesture, down: Double, up: Double) -> OptionGesture.Action? {
        let first = key.flags(option: true, otherModifier: false, at: down)
        return key.flags(option: false, otherModifier: false, at: up) ?? first
    }
}
