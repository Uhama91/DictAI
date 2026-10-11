import AppKit
import DictAICore

@MainActor final class OptionShortcut {
    private var local: Any?
    private var global: Any?
    private var wake: NSObjectProtocol?
    private var gesture = OptionGesture(interval: NSEvent.doubleClickInterval)
    private var resolution: Task<Void, Never>?
    private var optionKey: UInt16?
    var onGesture: ((OptionGesture.Action) -> Void)?
    var onCancel: (() -> Void)?
    func start() {
        stop()
        gesture = OptionGesture(interval: NSEvent.doubleClickInterval)
        let mask: NSEvent.EventTypeMask = [.flagsChanged, .keyDown, .leftMouseDown, .rightMouseDown, .otherMouseDown, .scrollWheel]
        global = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
        local = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }; return event
        }
        wake = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.reset() }
        }
    }
    func stop() {
        if let local { NSEvent.removeMonitor(local) }; if let global { NSEvent.removeMonitor(global) }
        if let wake { NSWorkspace.shared.notificationCenter.removeObserver(wake) }
        local = nil; global = nil; wake = nil; reset()
    }
    private func reset() { resolution?.cancel(); resolution = nil; optionKey = nil; gesture.reset() }
    private func handle(_ event: NSEvent) {
        guard event.type == .flagsChanged else {
            gesture.interrupt(); resolution?.cancel()
            if event.type == .keyDown && event.keyCode == 53 && event.modifierFlags.intersection([.command, .option, .control, .shift]).isEmpty { onCancel?() }
            return
        }
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        handleFlags(flags, keyCode: event.keyCode, at: event.timestamp)
    }
    func handleFlags(_ flags: NSEvent.ModifierFlags, keyCode: UInt16, at time: TimeInterval) {
        let option = flags.contains(.option)
        var other = !flags.intersection([.command, .control, .shift, .function]).isEmpty
        // Pressing both Option keys, or changing another modifier, is not a tap.
        if ![58, 61].contains(keyCode) { other = true }
        if option, let optionKey, optionKey != keyCode { other = true }
        if option && optionKey == nil { optionKey = keyCode }
        if !option { optionKey = nil }
        let action = gesture.flags(option: option, otherModifier: other, at: time)
        scheduleResolution()
        if let action { onGesture?(action) }
    }
    private func scheduleResolution() {
        resolution?.cancel(); resolution = nil
        guard let deadline = gesture.deadline else { return }
        resolution = Task { [weak self] in
            let delay = max(0, deadline - ProcessInfo.processInfo.systemUptime)
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            let action = self.gesture.advance(at: ProcessInfo.processInfo.systemUptime)
            self.scheduleResolution()
            if let action { self.onGesture?(action) }
        }
    }
}
