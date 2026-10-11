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
            BrandMark(width: 23).accessibilityHidden(false)
                .accessibilityLabel("DictAI — accès rapide").help("DictAI — accès rapide")
        }
        .menuBarExtraStyle(.menu)
        .commands {
            CommandGroup(replacing: .newItem) { Button("Nouvelle note") { model.library.perform { _ = try model.library.createNote() } }.keyboardShortcut("n") }
        }
    }
}
struct MenuContent: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var history: DictationHistoryStore
    @Environment(\.openWindow) private var openWindow
    init(model: AppModel) {
        self.model = model
        self.history = model.library.history
    }
    var body: some View {
        Button(model.state == .listening ? "Terminer et insérer  ⌥" : "Dicter  ⌥") { model.toggle() }
        if model.state == .listening {
            Button("Mettre la dictée en pause") { model.pauseListening() }
        } else if model.state == .paused {
            Button("Reprendre la dictée") { model.resumeListening() }
        }
        Button("Afficher le brouillon") { model.showDraft() }
            .disabled(model.state == .idle && model.draft.text.isEmpty)
        Divider()
        Button("Copier la dernière transcription") {
            if !model.copyLatestDictation() { model.library.error = "La transcription n’a pas pu être copiée." }
        }
        .disabled(history.entries.isEmpty)
        .keyboardShortcut("c", modifiers: [.command, .shift])
        Button("Historique des dictées") { openLibrary(page: "history") }
        Button("Ouvrir la bibliothèque") { openLibrary(page: "notes") }
        Button("Réglages…") { openLibrary(page: "settings") }.keyboardShortcut(",")
        Divider()
        Button("Synchroniser") { Task { await model.sync.sync() } }
            .disabled(model.account.session == nil)
        Divider()
        Button("Quitter DictAI") { model.closeOverlay(); NSApp.terminate(nil) }.keyboardShortcut("q")
    }
    private func openLibrary(page: String) {
        model.libraryPage = page
        openWindow(id: "library"); NSApp.activate(ignoringOtherApps: true)
    }
}
