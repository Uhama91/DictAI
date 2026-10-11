import SwiftUI

@MainActor final class OverlayController: NSObject, NSWindowDelegate, OverlayPresenting {
    private let model: AppModel
    private var panel: NSPanel?
    private var workspace: NSWindow?
    private var panelMode: DictationPresentation?
    private var exitingFullScreen = false
    private var afterFullScreenExit: (() -> Void)?
    init(model: AppModel) { self.model = model }

    func show(focus: Bool = false) {
        if exitingFullScreen {
            afterFullScreenExit = { [weak self] in self?.show(focus: focus) }; return
        }
        if model.presentation == .window { showWorkspace(focus: focus); return }
        if leaveFullScreen(then: { [weak self] in self?.show(focus: focus) }) { return }
        workspace?.orderOut(nil)
        if panel == nil {
            let window = EditablePanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            window.title = "DictAI · Votre dictée"
            window.isFloatingPanel = true; window.level = .floating; window.hidesOnDeactivate = false
            window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            window.isMovableByWindowBackground = true; window.backgroundColor = .clear; window.isOpaque = false; window.hasShadow = true
            window.contentView = NSHostingView(rootView: OverlayRootView(model: model, library: model.library))
            window.delegate = self; panel = window
        }
        guard let panel else { return }
        let size = model.presentation == .compact ? NSSize(width: 440, height: 124) : NSSize(width: 560, height: 330)
        let wasVisible = panel.isVisible
        let screen = panel.screen ?? NSScreen.screens.first { $0.frame.contains(NSEvent.mouseLocation) } ?? NSScreen.main
        if let area = screen?.visibleFrame {
            let origin = wasVisible ? NSPoint(x: panel.frame.midX - size.width / 2, y: panel.frame.minY) : NSPoint(x: area.midX - size.width / 2, y: area.minY + 70)
            let frame = NSRect(x: min(max(origin.x, area.minX), area.maxX - size.width), y: min(max(origin.y, area.minY), area.maxY - size.height), width: size.width, height: size.height)
            let animate = wasVisible && panelMode != model.presentation && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            panel.setFrame(frame, display: true, animate: animate)
        }
        panelMode = model.presentation
        panel.orderFrontRegardless()
        if focus { panel.makeKeyAndOrderFront(nil) }
    }

    private func showWorkspace(focus: Bool) {
        let sourceScreen = panel?.screen ?? NSScreen.main
        panel?.orderOut(nil)
        if workspace == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 660), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window.title = "DictAI · Votre dictée"
            window.isReleasedWhenClosed = false
            window.minSize = NSSize(width: 640, height: 480)
            window.collectionBehavior = [.fullScreenPrimary]
            window.contentView = NSHostingView(rootView: DictationWorkspaceView(model: model, library: model.library))
            window.delegate = self
            if let area = sourceScreen?.visibleFrame {
                let width = min(window.frame.width, area.width)
                let height = min(window.frame.height, area.height)
                window.setFrame(NSRect(x: area.midX - width / 2, y: area.midY - height / 2, width: width, height: height), display: false)
            }
            workspace = window
        }
        if workspace?.isMiniaturized == true { workspace?.deminiaturize(nil) }
        if focus { NSApp.activate(ignoringOtherApps: true); workspace?.makeKeyAndOrderFront(nil) }
        else { workspace?.orderFront(nil) }
    }

    private func leaveFullScreen(then action: @escaping () -> Void) -> Bool {
        guard let workspace, exitingFullScreen || workspace.styleMask.contains(.fullScreen) else { return false }
        afterFullScreenExit = action
        if !exitingFullScreen { exitingFullScreen = true; workspace.toggleFullScreen(nil) }
        return true
    }
    func windowDidExitFullScreen(_ notification: Notification) {
        guard notification.object as? NSWindow === workspace else { return }
        exitingFullScreen = false
        let action = afterFullScreenExit; afterFullScreenExit = nil
        action?()
    }
    func hide() {
        panel?.orderOut(nil)
        if leaveFullScreen(then: { [weak self] in self?.workspace?.orderOut(nil) }) { return }
        workspace?.orderOut(nil)
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool { model.closeOverlay(); return false }
}

private class EditablePanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
    override func animationResizeTime(_ newFrame: NSRect) -> TimeInterval { 0.18 }
}
