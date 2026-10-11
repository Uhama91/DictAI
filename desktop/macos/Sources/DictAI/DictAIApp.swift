import SwiftUI
import AppKit

@main struct DictAIApp: App {
    @StateObject private var model: AppModel
    init() {
        Theme.registerFonts()
        do {
            let preview = PreviewMode.enabled
            let demo = PreviewMode.dictation
            let root = preview ? URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("dictai-preview-" + UUID().uuidString) : nil
            let instance = try AppModel(root: root, speech: demo ? PreviewSpeech() : nil, insertion: demo ? PreviewTextDelivery() : nil)
            _model = StateObject(wrappedValue: instance)
        } catch {
            let alert = NSAlert(); alert.messageText = "Impossible d’ouvrir la bibliothèque DictAI"; alert.informativeText = error.localizedDescription
            alert.runModal(); exit(1)
        }
    }
    var body: some Scene {
        WindowGroup("DictAI", id: "library") {
            WorkspaceView(model: model, setup: model.setup)
                .frame(minWidth: 960, minHeight: 640)
        }.defaultSize(width: 1160, height: 760).windowStyle(.hiddenTitleBar)
        MenuBarExtra {
            MenuContent(model: model)
        } label: {
            BrandMark(width: 23)
        }
        .commands {
            CommandGroup(replacing: .newItem) { Button("Nouvelle note") { model.library.perform { _ = try model.library.createNote() } }.keyboardShortcut("n") }
        }
    }
}
struct MenuContent: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow
    var body: some View {
        Button(model.state == .listening ? "Terminer et insérer  ⌥" : "Dicter  ⌥") { model.toggle() }
        Button("Afficher le brouillon") { model.showDraft() }
            .disabled(model.state == .idle && model.draft.text.isEmpty)
        Button("Ouvrir la bibliothèque") { openWindow(id: "library"); NSApp.activate(ignoringOtherApps: true) }
        Button("Historique des dictées") {
            model.libraryPage = "history"
            openWindow(id: "library"); NSApp.activate(ignoringOtherApps: true)
        }
        Divider()
        Button("Synchroniser") { Task { await model.sync.sync() } }
        Divider()
        Button("Quitter DictAI") { model.closeOverlay(); NSApp.terminate(nil) }.keyboardShortcut("q")
    }
}
