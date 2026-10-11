import AppKit
import SwiftUI
import Foundation
import CoreText
import Testing
@testable import DictAI

@MainActor struct HistoryViewTests {
    @Test func historyRendersEmptyListAndFullTranscriptInBothAppearances() async throws {
        _ = NSApplication.shared
        let package = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        CTFontManagerRegisterFontsForURL(package.appendingPathComponent("Resources/Fonts/Caveat.ttf") as CFURL, .process, nil)
        let store = try LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-history-render-" + UUID().uuidString))
        for theme in ["light", "dark"] {
            try store.history.clear()
            try await render(store.history, theme: theme, name: "empty")
            for i in 1...11 {
                try store.history.record(id: "dictation-\(i)", text: "Dictée \(i). Préparer les activités de jeudi, puis envoyer le document à l’équipe.\n\nLa dictée reste disponible même si l’insertion dans une autre application a échoué.", date: Date(timeIntervalSince1970: 1_791_717_600 + Double(i * 60)))
            }
            #expect(store.history.entries.count == 10)
            try await render(store.history, theme: theme, name: "populated")
        }
    }
    private func render(_ history: DictationHistoryStore, theme: String, name: String) async throws {
        let view = NSHostingView(rootView: HistoryView(history: history).environment(\.colorScheme, theme == "light" ? .light : .dark))
        view.appearance = NSAppearance(named: theme == "light" ? .aqua : .darkAqua)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 936, height: 760), styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = view; window.orderFront(nil)
        defer { window.close() }
        try await Task.sleep(for: .milliseconds(200))
        view.layoutSubtreeIfNeeded()
        guard let path = ProcessInfo.processInfo.environment["DICTAI_TEST_RENDER_DIR"] else { return }
        let directory = URL(fileURLWithPath: path)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        try #require(bitmap.representation(using: .png, properties: [:])).write(to: directory.appendingPathComponent("history-\(name)-\(theme).png"))
    }
}
