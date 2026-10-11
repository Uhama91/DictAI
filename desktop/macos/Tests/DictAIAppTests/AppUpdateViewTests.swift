import AppKit
import SwiftUI
import Testing
@testable import DictAI

@MainActor struct AppUpdateViewTests {
    @Test func updateBannerAndSettingsRenderInBothAppearances() async throws {
        _ = NSApplication.shared
        let preferences = try #require(UserDefaults(suiteName: "dictai-update-render-" + UUID().uuidString))
        let checker = AppUpdateChecker(preferences: preferences, currentBuild: 16, systemVersion: "14.0", fetch: {
            Data(#"{"schemaVersion":1,"macos":{"bundleIdentifier":"com.uhama.dictai.desktop","buildNumber":100,"versionName":"0.4.8","minSystemVersion":"14.0","downloadUrl":"https://github.com/Uhama91/DictAI/releases/download/v1/DictAI-macOS.pkg"}}"#.utf8)
        })
        await checker.check(force: true)
        #expect(checker.offer?.version == "0.4.8")
        for theme in ["light", "dark"] {
            let view = NSHostingView(rootView: VStack(alignment: .leading, spacing: 28) {
                AppUpdateBanner(updates: checker)
                VStack(alignment: .leading, spacing: 20) {
                    Text("Réglages").font(.largeTitle)
                    Text("Mises à jour").font(.title2)
                    AppUpdateSettings(updates: checker)
                }.padding(32)
                Spacer()
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(Theme.background).foregroundStyle(Theme.cream).tint(Theme.accent)
                .environment(\.colorScheme, theme == "light" ? .light : .dark))
            view.appearance = NSAppearance(named: theme == "light" ? .aqua : .darkAqua)
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 420), styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.contentView = view; window.orderFront(nil)
            try await Task.sleep(for: .milliseconds(200))
            view.layoutSubtreeIfNeeded()
            if let path = ProcessInfo.processInfo.environment["DICTAI_TEST_RENDER_DIR"] {
                let directory = URL(fileURLWithPath: path)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
                view.cacheDisplay(in: view.bounds, to: bitmap)
                try #require(bitmap.representation(using: .png, properties: [:])).write(to: directory.appendingPathComponent("macos-update-\(theme).png"))
            }
            window.close()
        }
    }
}
