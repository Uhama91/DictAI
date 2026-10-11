import AppKit
import SwiftUI
import Testing
@testable import DictAI

@MainActor struct LiveEditorTests {
    @Test func swiftUIEditorObservesIncomingSpeechAfterItsCreation() async throws {
        _ = NSApplication.shared
        let model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-host-" + UUID().uuidString), makeOverlay: { _ in EditorOverlay() })
        model.showDraft(); model.state = .listening; model.draft.receive("Le début")
        let host = NSHostingView(rootView: LiveDictationTextView(model: model, spacious: false))
        host.frame = NSRect(x: 0, y: 0, width: 380, height: 160)
        host.layoutSubtreeIfNeeded()
        func editor(in view: NSView) -> DictationNativeTextView? {
            if let text = view as? DictationNativeTextView { return text }
            return view.subviews.lazy.compactMap { editor(in: $0) }.first
        }
        let text = try #require(editor(in: host))
        #expect(text.string == "Le début")
        model.draft.receive("Le début et la suite")
        try await Task.sleep(nanoseconds: 100_000_000)
        host.layoutSubtreeIfNeeded()
        #expect(text.string == "Le début et la suite")
    }
    @Test func realTextViewKeepsSelectionAndViewportWhileSpeechAppends() throws {
        let rig = try EditorRig()
        rig.model.editorSession.interact()
        rig.text.setSelectedRange(NSRange(location: 18, length: 6))
        rig.scroll.contentView.scroll(to: .zero)
        let selection = rig.text.selectedRange()
        let before = rig.scroll.contentView.bounds.origin.y
        rig.coordinator.apply(rig.content + "\nUne nouvelle phrase tout en bas.", attributes: rig.attributes)
        #expect(rig.text.selectedRange() == selection)
        #expect(abs(rig.scroll.contentView.bounds.origin.y - before) < 1)
        #expect(rig.text.string.hasSuffix("tout en bas."))
        rig.model.editorSession.follow()
        rig.coordinator.restorePresentation()
        #expect(rig.scroll.contentView.bounds.origin.y > before + 100)
        #expect(rig.text.selectedRange() == selection)
        rig.coordinator.detach()
    }
    @Test func openingAnotherPresentationRestoresTheSameSelection() throws {
        let rig = try EditorRig()
        let selection = NSRange(location: 30, length: 9)
        rig.text.setSelectedRange(selection)
        rig.model.editorSession.remember(selection, in: rig.content)
        rig.model.editorSession.interact()
        rig.model.showWorkspace()
        let second = LiveDictationTextView.Coordinator(model: rig.model, presentation: .window)
        let text = DictationNativeTextView(frame: NSRect(x: 0, y: 0, width: 400, height: 200))
        let scroll = NSScrollView(frame: text.frame); scroll.documentView = text
        second.attach(text, scroll: scroll)
        second.apply(rig.content, attributes: rig.attributes)
        #expect(text.selectedRange() == selection)
        // A hidden editor receiving more text cannot overwrite the active selection.
        rig.coordinator.apply(rig.content + " encore", attributes: rig.attributes)
        #expect(rig.model.editorSession.selection == selection)
        let nextSelection = NSRange(location: 60, length: 3)
        text.setSelectedRange(nextSelection)
        rig.model.showDraft()
        rig.coordinator.apply(rig.content, attributes: rig.attributes)
        #expect(rig.text.selectedRange() == nextSelection)
        second.detach(); rig.coordinator.detach()
    }
    @Test func realTypingInAnEarlierParagraphDoesNotDeleteConcurrentSpeech() throws {
        let rig = try EditorRig()
        rig.text.setSelectedRange(NSRange(location: 0, length: 7))
        rig.model.draft.receive(rig.content + "\nNouveaux mots.")
        rig.text.insertText("Retouche", replacementRange: NSRange(location: 0, length: 7))
        #expect(rig.model.draft.text.hasPrefix("Retouche"))
        #expect(rig.model.draft.text.hasSuffix("Nouveaux mots."))
        rig.coordinator.apply(rig.model.draft.text, attributes: rig.attributes)
        #expect(rig.text.string == rig.model.draft.text)
        rig.coordinator.detach()
    }
}

@MainActor private final class EditorRig {
    let model: AppModel
    let text = DictationNativeTextView(frame: NSRect(x: 0, y: 0, width: 380, height: 160))
    let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 380, height: 160))
    let coordinator: LiveDictationTextView.Coordinator
    let content = (1...30).map { "Passage \($0) : une phrase conservée pendant la dictée." }.joined(separator: "\n")
    let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 17)]
    init() throws {
        _ = NSApplication.shared
        model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-editor-" + UUID().uuidString), makeOverlay: { _ in EditorOverlay() })
        model.showDraft(); model.state = .listening; model.draft.receive(content)
        text.isVerticallyResizable = true; text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        scroll.documentView = text
        coordinator = LiveDictationTextView.Coordinator(model: model, presentation: .editor)
        coordinator.attach(text, scroll: scroll)
        coordinator.apply(content, attributes: attributes)
    }
}
@MainActor private final class EditorOverlay: OverlayPresenting {
    func show(focus: Bool) {}
    func hide() {}
}
