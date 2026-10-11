import Foundation
import AppKit
import SwiftUI
import CoreText
import Testing
@testable import DictAI

@Suite @MainActor struct SetupTests {
    private func location() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("dictai-setup-tests-" + UUID().uuidString)
    }

    @Test func completionSurvivesRelaunchAndReplayPreservesLibrary() async throws {
        let root = location()
        let library = try LibraryStore(root: root)
        _ = try library.createNote(text: "Une note à conserver")
        try library.addVocabulary("dicta => DictAI")
        let file = library.directory.appendingPathComponent("library.json")
        let original = try Data(contentsOf: file)
        let setup = try SetupState(root: root)
        #expect(setup.presented)
        try setup.complete()
        let reopened = try SetupState(root: root)
        #expect(!reopened.presented)
        reopened.restart()
        #expect(reopened.presented)
        #expect(reopened.step == 0)
        #expect(try Data(contentsOf: file) == original)
        try await renderSetup(root: root, setup: reopened)
    }

    private func renderSetup(root: URL, setup: SetupState) async throws {
        guard let path = ProcessInfo.processInfo.environment["DICTAI_TEST_RENDER_DIR"] else { return }
        _ = NSApplication.shared
        let package = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        CTFontManagerRegisterFontsForURL(package.appendingPathComponent("Resources/Fonts/Caveat.ttf") as CFURL, .process, nil)
        let model = try AppModel(root: root)
        let directory = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for step in 0...3 {
            setup.step = step
            let view = NSHostingView(rootView: SetupView(model: model, setup: setup, library: model.library, account: model.account, sync: model.sync))
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 960, height: 640), styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.contentView = view
            window.orderFront(nil)
            try await Task.sleep(for: .milliseconds(250))
            view.layoutSubtreeIfNeeded()
            let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
            view.cacheDisplay(in: view.bounds, to: bitmap)
            try #require(bitmap.representation(using: .png, properties: [:])).write(to: directory.appendingPathComponent("setup-0.4.0-\(step + 1).png"))
            window.close()
        }
        model.shortcut.stop()
    }

    @Test func failedSaveDoesNotMarkSetupComplete() throws {
        let root = location()
        let setup = try SetupState(root: root)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("setup.json"), withIntermediateDirectories: true)
        #expect(throws: (any Error).self) { try setup.complete() }
        #expect(setup.presented)
    }

    @Test func permissionsAloneNeverPromiseWorkingDictation() {
        var readiness = SetupReadiness(microphone: true, speech: true, accessibility: true, localRecognition: false, recognitionAvailable: true)
        #expect(!readiness.canDictate && !readiness.canInsert)
        readiness.localRecognition = true
        #expect(readiness.canDictate && readiness.canInsert)
        readiness.accessibility = false
        #expect(readiness.canDictate && !readiness.canInsert)
        readiness.recognitionAvailable = false
        #expect(!readiness.canDictate)
        readiness.recognitionAvailable = true; readiness.microphone = false
        #expect(!readiness.canDictate)
        readiness.microphone = true; readiness.speech = false
        #expect(!readiness.canDictate)
    }
}
