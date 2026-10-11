import AppKit
import SwiftUI
import Testing
import DictAICore
@testable import DictAI

@MainActor struct DictationWindowTests {
    @Test func cloudCorrectionSettingsAndReviewRenderInNativeViews() async throws {
        _ = NSApplication.shared
        Theme.registerFonts()
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-cloud-render-" + UUID().uuidString))
        defer { model.closeOverlay() }
        for theme in ["light", "dark"] {
            model.library.setPreference("theme_mode", theme)
            let settings = NSHostingView(rootView: SettingsView(model: model, library: model.library, account: model.account, sync: model.sync)
                .foregroundStyle(Theme.cream).tint(Theme.accent).background(Theme.surface).environment(\.colorScheme, theme == "light" ? .light : .dark))
            settings.appearance = NSAppearance(named: theme == "light" ? .aqua : .darkAqua)
            settings.frame = NSRect(x: 0, y: 0, width: 920, height: 1500)
            settings.layoutSubtreeIfNeeded()
            try render(settings, name: "cloud-settings-\(theme)")
        }
        model.edit("Je regarde l’application dans le navigateur. La dictée est terminée. Je peux passer à la suite.")
        model.showDraft()
        try await Task.sleep(for: .milliseconds(200))
        let editor = try #require(NSApp.windows.first { $0.isVisible && $0.title == "DictAI · Votre dictée" })
        editor.contentView?.layoutSubtreeIfNeeded()
        try render(editor.contentView, name: "cloud-review")
        model.state = .cleaning
        model.showCompact()
        try await Task.sleep(for: .milliseconds(200))
        let compact = try #require(NSApp.windows.first { $0.isVisible && $0.title == "DictAI · Votre dictée" })
        compact.contentView?.layoutSubtreeIfNeeded()
        try render(compact.contentView, name: "cloud-progress")
    }
    @Test func missingAccessibilityRemainsVisibleWithARecoverableDraft() async throws {
        _ = NSApplication.shared
        let speech = WindowTestSpeech()
        let delivery = WindowTestDelivery()
        delivery.available = false
        delivery.issue = "Accessibilité requise pour Option et l’insertion. Autorisez DictAI dans Réglages Système → Confidentialité et sécurité → Accessibilité. Votre dictée reste dans le brouillon."
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-permission-" + UUID().uuidString), speech: speech, insertion: delivery)
        defer { model.closeOverlay() }
        model.begin()
        for _ in 0..<30 { await Task.yield() }
        speech.onText?("Je regarde l’application dans le navigateur.")
        try await Task.sleep(for: .milliseconds(200))
        let compact = try #require(NSApp.windows.first { $0.isVisible && $0.title == "DictAI · Votre dictée" })
        compact.contentView?.layoutSubtreeIfNeeded()
        try render(compact.contentView, name: "fix-0.4.1-permission-compact")
        model.showDraft()
        try await Task.sleep(for: .milliseconds(200))
        let editor = try #require(NSApp.windows.first { $0.isVisible && $0.title == "DictAI · Votre dictée" })
        editor.contentView?.layoutSubtreeIfNeeded()
        try render(editor.contentView, name: "fix-0.4.1-permission-editor")
        model.toggle()
        try await Task.sleep(for: .milliseconds(200))
        #expect(model.state == .review && model.panelVisible && !model.delivered)
        #expect(model.library.loadDraft()?.text == "Je regarde l’application dans le navigateur.")
        #expect(!model.message.isEmpty)
        #expect(delivery.texts.isEmpty)
    }
    @Test func optionAfterEditingClosesRealWindowsWithoutReopening() async throws {
        _ = NSApplication.shared
        for mode in [DictationPresentation.compact, .editor, .window] {
            let speech = WindowTestSpeech()
            let delivery = WindowTestDelivery()
            let before = Set(NSApp.windows.map(ObjectIdentifier.init))
            let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-dismiss-" + UUID().uuidString), speech: speech, insertion: delivery)
            model.begin()
            for _ in 0..<30 { await Task.yield() }
            speech.onText?("Une dictée avant retouche.")
            if mode == .editor { model.showDraft() }
            if mode == .window { model.showWorkspace() }
            try await Task.sleep(for: .milliseconds(250))
            let windows = NSApp.windows.filter { !before.contains(ObjectIdentifier($0)) && $0.title == "DictAI · Votre dictée" }
            let window = try #require(windows.first { $0.isVisible })
            func editor(_ view: NSView) -> DictationNativeTextView? {
                if let text = view as? DictationNativeTextView { return text }
                return view.subviews.lazy.compactMap(editor).first
            }
            if mode == .compact { speech.onText?("Une dictée corrigée.") }
            else {
                let text = try #require(window.contentView.flatMap(editor))
                text.insertText("Une dictée corrigée.", replacementRange: NSRange(location: 0, length: text.string.utf16.count))
            }
            #expect(model.draft.text == "Une dictée corrigée.")
            window.contentView?.layoutSubtreeIfNeeded()
            try render(window.contentView, name: "insertion-0.3.6-\(mode)")
            model.shortcut.onGesture?(.single)
            try await Task.sleep(for: .milliseconds(800))
            #expect(model.delivered && model.state == .idle)
            #expect(delivery.texts == ["Une dictée corrigée. "])
            #expect(!model.panelVisible)
            #expect(windows.allSatisfy { !$0.isVisible })
            #expect(model.library.history.entries.map(\.text) == ["Une dictée corrigée."])
            #expect(model.library.loadDraft()?.text.isEmpty == true)
            if mode == .window {
                let library = NSHostingView(rootView: LibraryView(model: model, library: model.library, account: model.account, sync: model.sync))
                library.frame = NSRect(x: 0, y: 0, width: 1160, height: 760)
                library.layoutSubtreeIfNeeded()
                try render(library, name: "insertion-0.3.6-archived")
            }
            model.closeOverlay()
        }
    }
    private func render(_ view: NSView?, name: String) throws {
        guard let directory = ProcessInfo.processInfo.environment["DICTAI_TEST_RENDER_DIR"], let view else { return }
        let root = URL(fileURLWithPath: directory, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        try #require(bitmap.representation(using: .png, properties: [:])).write(to: root.appendingPathComponent(name + ".png"))
    }
    @Test func expandControlOpensAStandardResizableWindow() throws {
        _ = NSApplication.shared
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-window-" + UUID().uuidString))
        model.edit("Une note à développer sans perdre son texte.")
        model.showDraft()
        model.toggleExpanded()
        defer { model.closeOverlay() }
        let window = try #require(NSApp.windows.first { $0.isVisible && $0.title == "DictAI · Votre dictée" })
        #expect(window.styleMask.contains(.titled))
        #expect(window.styleMask.contains(.resizable))
        #expect(window.styleMask.contains(.miniaturizable))
        #expect(window.level == .normal)
        #expect(model.draft.text == "Une note à développer sans perdre son texte.")
        let originalFrame = window.frame
        model.showCompact()
        #expect(!window.isVisible)
        model.showDraft()
        let editor = try #require(NSApp.windows.first { $0.isVisible && $0 is NSPanel && $0.title == window.title })
        #expect(editor.frame.height == 330)
        model.edit("Une note retouchée dans le petit éditeur.")
        model.showWorkspace()
        #expect(window.isVisible && !editor.isVisible)
        #expect(window.frame == originalFrame)
        #expect(model.draft.text == "Une note retouchée dans le petit éditeur.")
    }
}

@MainActor private final class WindowTestSpeech: SpeechCapturing {
    var onText: ((String) -> Void)?
    var onLevel: ((Float) -> Void)?
    var onFinish: ((String?) -> Void)?
    func start(language: String, vocabulary: [String]) async throws {}
    func stop() { onFinish?(nil) }
    func cancel() {}
}
@MainActor private final class WindowTestDelivery: TextDelivering {
    var available = true
    var issue: String?
    var targetName = "Champ de test"
    var texts: [String] = []
    func capture() {}
    func insert(_ text: String) async throws -> String {
        if let issue { throw DataError.invalid(issue) }
        return try await InsertionDelivery.send(canVerify: true, select: {}, read: { .unchanged }, paste: {
            try await Task.sleep(for: .milliseconds(100))
            self.texts.append(text)
        })
    }
    func copy(_ text: String) {}
}
