import AppKit
import Carbon
import DictAICore

/// The clipboard is restored only after a consumer has actually requested our text.
@MainActor final class ClipboardDelivery {
    private var provider: PasteProvider?
    func paste(_ text: String, stillFocused: () -> Bool) async throws {
        let board = NSPasteboard.general
        let previous = (board.pasteboardItems ?? []).map { item -> NSPasteboardItem in
            let copy = NSPasteboardItem()
            for type in item.types { if let data = item.data(forType: type) { copy.setData(data, forType: type) } }
            return copy
        }
        let source = PasteProvider(text: text)
        provider = source
        let item = NSPasteboardItem()
        item.setDataProvider(source, forTypes: [.string])
        item.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        board.clearContents(); board.writeObjects([item])
        let change = board.changeCount
        defer { restore(previous, change: change); provider = nil }
        guard stillFocused() else { restore(previous, change: change); provider = nil; throw DataError.invalid("Le champ actif a changé. Le texte reste dans le brouillon.") }
        let code = pasteKeyCode()
        guard let down = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true), let up = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false) else {
            restore(previous, change: change); provider = nil; throw DataError.invalid("Impossible d’envoyer le raccourci de collage.")
        }
        down.flags = .maskCommand; up.flags = .maskCommand
        down.post(tap: .cghidEventTap); up.post(tap: .cghidEventTap)
        for _ in 0..<20 {
            try await Task.sleep(nanoseconds: 100_000_000)
            if source.received {
                // Let the receiver finish reading while retaining clipboard ownership checks.
                try await Task.sleep(nanoseconds: 350_000_000)
                restore(previous, change: change); provider = nil
                return
            }
        }
        // Keep plain text available for a manual paste if the application did not consume it.
        if board.changeCount == change { board.clearContents(); board.setString(text, forType: .string) }
        provider = nil
        throw DataError.invalid("Le collage n’a pas été confirmé. Le texte reste copié et dans le brouillon.")
    }
    private func restore(_ items: [NSPasteboardItem], change: Int) {
        guard NSPasteboard.general.changeCount == change else { return }
        NSPasteboard.general.clearContents(); if !items.isEmpty { NSPasteboard.general.writeObjects(items) }
    }
    private func pasteKeyCode() -> CGKeyCode {
        guard let source = TISCopyCurrentKeyboardLayoutInputSource()?.takeRetainedValue(),
              let pointer = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData) else { return 9 }
        let data = Unmanaged<CFData>.fromOpaque(pointer).takeUnretainedValue()
        let layout = UnsafeRawPointer(CFDataGetBytePtr(data)!).assumingMemoryBound(to: UCKeyboardLayout.self)
        for code in UInt16(0)..<128 {
            var dead: UInt32 = 0; var length = 0; var chars = [UniChar](repeating: 0, count: 4)
            let result = UCKeyTranslate(layout, code, UInt16(kUCKeyActionDown), 0, UInt32(LMGetKbdType()), OptionBits(kUCKeyTranslateNoDeadKeysBit), &dead, 4, &length, &chars)
            if result == noErr && length == 1 && chars[0] == 118 { return code }
        }
        return 9
    }
}
private final class PasteProvider: NSObject, NSPasteboardItemDataProvider {
    let text: String
    private(set) var received = false
    init(text: String) { self.text = text }
    func pasteboard(_ pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType type: NSPasteboard.PasteboardType) {
        if type == .string { received = true; item.setString(text, forType: .string) }
    }
}
