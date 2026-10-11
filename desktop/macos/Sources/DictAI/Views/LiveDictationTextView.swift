import AppKit
import SwiftUI
import Combine
import DictAICore

struct LiveDictationTextView: NSViewRepresentable {
    @ObservedObject var model: AppModel
    let spacious: Bool
    func makeCoordinator() -> Coordinator { Coordinator(model: model, presentation: spacious ? .window : .editor) }
    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.drawsBackground = false; scroll.hasVerticalScroller = true; scroll.borderType = .noBorder
        let text = DictationNativeTextView(frame: .zero)
        text.minSize = .zero; text.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        text.isVerticallyResizable = true; text.isHorizontallyResizable = false; text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        text.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        text.isRichText = false; text.allowsUndo = true; text.drawsBackground = false
        text.isAutomaticSpellingCorrectionEnabled = false; text.isAutomaticTextReplacementEnabled = false
        text.textContainerInset = NSSize(width: 12, height: 12)
        text.setAccessibilityLabel("Texte de la dictée")
        scroll.documentView = text
        context.coordinator.attach(text, scroll: scroll)
        updateNSView(scroll, context: context)
        return scroll
    }
    func updateNSView(_ scroll: NSScrollView, context: Context) {
        guard let text = scroll.documentView as? DictationNativeTextView else { return }
        let paragraph = NSMutableParagraphStyle(); paragraph.lineSpacing = spacious ? 7 : 4
        let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: spacious ? 17 : 14), .foregroundColor: NSColor(Theme.cream), .paragraphStyle: paragraph]
        text.typingAttributes = attributes; text.textColor = NSColor(Theme.cream); text.insertionPointColor = NSColor(Theme.accent)
        text.isEditable = model.state != .inserting
        context.coordinator.apply(model.draft.text, attributes: attributes)
    }
    static func dismantleNSView(_ view: NSScrollView, coordinator: Coordinator) { coordinator.detach() }

    @MainActor final class Coordinator: NSObject, NSTextViewDelegate {
        let model: AppModel
        weak var text: DictationNativeTextView?
        weak var scroll: NSScrollView?
        var applying = false
        private var initialized = false
        private var wasCurrent = false
        private let presentation: DictationPresentation
        private var isCurrent: Bool { model.panelVisible && model.presentation == presentation }
        private var rendered = ""
        private var attributes: [NSAttributedString.Key: Any] = [:]
        private var subscription: AnyCancellable?
        private var scrollObserver: NSObjectProtocol?
        init(model: AppModel, presentation: DictationPresentation) { self.model = model; self.presentation = presentation }
        func attach(_ text: DictationNativeTextView, scroll: NSScrollView) {
            self.text = text; self.scroll = scroll; text.delegate = self
            text.onInteraction = { [weak self] active in
                guard let self, self.isCurrent else { return }
                self.model.editorSession.interact(active: active)
                if !active { self.apply(self.model.draft.text, attributes: self.attributes) }
            }
            text.onReturnToSelection = { [weak self] in
                guard let self, let text = self.text else { return }
                self.applying = true; text.scrollRangeToVisible(text.selectedRange()); self.applying = false
            }
            text.onReady = { [weak self] in self?.restorePresentation() }
            subscription = model.editorSession.$policy.map(\.following).removeDuplicates().sink { [weak self] follows in
                guard follows else { return }
                DispatchQueue.main.async { self?.scrollToLatest() }
            }
            scroll.contentView.postsBoundsChangedNotifications = true
            scrollObserver = NotificationCenter.default.addObserver(forName: NSView.boundsDidChangeNotification, object: scroll.contentView, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, self.isCurrent, !self.applying,
                          let event = NSApp.currentEvent,
                          ProcessInfo.processInfo.systemUptime - event.timestamp < 0.15,
                          [.scrollWheel, .leftMouseDragged, .leftMouseDown].contains(event.type) else { return }
                    self.model.editorSession.interact()
                }
            }
        }
        func detach() {
            subscription?.cancel()
            if let scrollObserver { NotificationCenter.default.removeObserver(scrollObserver) }
            text?.delegate = nil
        }
        func apply(_ value: String, attributes: [NSAttributedString.Key: Any]) {
            self.attributes = attributes
            guard let text, !text.hasMarkedText(), !text.selecting else { return }
            let activated = isCurrent && !wasCurrent
            wasCurrent = isCurrent
            guard value != text.string else {
                if activated { restorePresentation() }
                return
            }
            let selection = text.selectedRange()
            let oldOrigin = scroll?.contentView.bounds.origin
            let change = TextMutation(from: text.string, to: value)
            applying = true
            text.textStorage?.beginEditing()
            text.textStorage?.replaceCharacters(in: change.range, with: change.replacement)
            text.textStorage?.setAttributes(attributes, range: NSRange(location: 0, length: text.string.utf16.count))
            text.textStorage?.endEditing()
            let restored = initialized && !activated ? change.rebasing(selection) : model.editorSession.restoredSelection(in: value)
            text.setSelectedRange(restored)
            rendered = value; initialized = true
            if isCurrent { model.editorSession.remember(restored, in: value) }
            text.layoutManager?.ensureLayout(for: text.textContainer!)
            if model.editorSession.policy.following { revealEnd() }
            else if let oldOrigin { scroll?.contentView.scroll(to: oldOrigin); scroll.map { $0.reflectScrolledClipView($0.contentView) } }
            applying = false
        }
        func textDidChange(_ notification: Notification) {
            guard !applying, isCurrent, let text else { return }
            model.editorSession.interact(active: text.hasMarkedText())
            let edited = TextMutation.mergingEdit(from: rendered, to: text.string, into: model.draft.text)
            rendered = text.string
            model.edit(edited)
            model.editorSession.remember(text.selectedRange(), in: text.string)
        }
        func textViewDidChangeSelection(_ notification: Notification) {
            guard !applying, isCurrent, let text else { return }
            model.editorSession.remember(text.selectedRange(), in: text.string)
            model.editorSession.interact(active: text.selecting || text.hasMarkedText())
        }
        func restorePresentation() {
            guard isCurrent, let text else { return }
            applying = true
            text.setSelectedRange(model.editorSession.restoredSelection(in: text.string))
            if model.editorSession.policy.following { revealEnd() } else { text.scrollRangeToVisible(text.selectedRange()) }
            applying = false
        }
        func scrollToLatest() {
            guard isCurrent, let text, text.window?.isVisible == true, !text.hasMarkedText(), !text.selecting else { return }
            applying = true; revealEnd(); applying = false
        }
        private func revealEnd() {
            guard let text else { return }
            text.scrollRangeToVisible(NSRange(location: text.string.utf16.count, length: 0))
        }
    }
}

final class DictationNativeTextView: NSTextView {
    var onInteraction: ((Bool) -> Void)?
    var onReturnToSelection: (() -> Void)?
    var onReady: (() -> Void)?
    var selecting = false
    override func mouseDown(with event: NSEvent) {
        selecting = true; onInteraction?(true)
        super.mouseDown(with: event)
        selecting = false; onInteraction?(false)
    }
    override func keyDown(with event: NSEvent) {
        onInteraction?(true); onReturnToSelection?()
        super.keyDown(with: event)
        onInteraction?(hasMarkedText())
    }
    override func scrollWheel(with event: NSEvent) {
        onInteraction?(true); super.scrollWheel(with: event); onInteraction?(false)
    }
    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        guard window != nil else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window?.isVisible == true else { return }
            self.window?.makeFirstResponder(self); self.onReady?()
        }
    }
}
