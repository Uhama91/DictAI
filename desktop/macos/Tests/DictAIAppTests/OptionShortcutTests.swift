import AppKit
import Testing
import DictAICore
@testable import DictAI

@MainActor struct OptionShortcutTests {
    @Test func aBusyMainThreadDoesNotRestartTheWholeGestureDelay() async throws {
        let shortcut = OptionShortcut()
        defer { shortcut.stop() }
        var actions: [OptionGesture.Action] = []
        shortcut.onGesture = { actions.append($0) }
        let now = ProcessInfo.processInfo.systemUptime
        shortcut.handleFlags(.option, keyCode: 58, at: now - 0.06)
        shortcut.handleFlags([], keyCode: 58, at: now)
        blockMainThread(for: NSEvent.doubleClickInterval + 0.1)
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(actions == [.single])
    }
    @Test func nativeAdapterDeliversOnlyDoubleAfterItsRealTimer() async throws {
        let shortcut = OptionShortcut()
        defer { shortcut.stop() }
        var actions: [OptionGesture.Action] = []
        shortcut.onGesture = { actions.append($0) }
        let now = ProcessInfo.processInfo.systemUptime
        let gap = min(0.1, NSEvent.doubleClickInterval / 2)
        shortcut.handleFlags(.option, keyCode: 61, at: now - gap - 0.12)
        shortcut.handleFlags([], keyCode: 61, at: now - gap - 0.06)
        shortcut.handleFlags(.option, keyCode: 61, at: now - 0.06)
        shortcut.handleFlags([], keyCode: 61, at: now)
        #expect(actions.isEmpty)
        try await Task.sleep(nanoseconds: UInt64((NSEvent.doubleClickInterval + 0.15) * 1_000_000_000))
        #expect(actions == [.double])
    }
    @Test func stoppingTheMonitorCancelsAnOutstandingCommand() async throws {
        let shortcut = OptionShortcut()
        var actions: [OptionGesture.Action] = []
        shortcut.onGesture = { actions.append($0) }
        let now = ProcessInfo.processInfo.systemUptime
        shortcut.handleFlags(.option, keyCode: 58, at: now - 0.06)
        shortcut.handleFlags([], keyCode: 58, at: now)
        shortcut.stop()
        try await Task.sleep(nanoseconds: UInt64((NSEvent.doubleClickInterval + 0.15) * 1_000_000_000))
        #expect(actions.isEmpty)
    }
    @Test func overlappingLeftAndRightOptionKeysAreNotATap() async throws {
        let shortcut = OptionShortcut()
        defer { shortcut.stop() }
        var actions: [OptionGesture.Action] = []
        shortcut.onGesture = { actions.append($0) }
        let now = ProcessInfo.processInfo.systemUptime
        shortcut.handleFlags(.option, keyCode: 58, at: now - 0.2)
        shortcut.handleFlags(.option, keyCode: 61, at: now - 0.15)
        shortcut.handleFlags(.option, keyCode: 58, at: now - 0.1)
        shortcut.handleFlags([], keyCode: 61, at: now)
        try await Task.sleep(nanoseconds: UInt64((NSEvent.doubleClickInterval + 0.15) * 1_000_000_000))
        #expect(actions.isEmpty)
    }
    private func blockMainThread(for duration: TimeInterval) { Thread.sleep(forTimeInterval: duration) }
}
