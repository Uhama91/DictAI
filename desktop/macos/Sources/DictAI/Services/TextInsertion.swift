import AppKit
import ApplicationServices
import DictAICore

@MainActor final class TextInsertion: TextDelivering {
    private var target: AXUIElement?
    private var application: NSRunningApplication?
    private var original: String?
    private var range: AXValue?
    private var tracking = false
    private var activation: NSObjectProtocol?
    private let activationCenter = NSWorkspace.shared.notificationCenter
    private let clipboard = ClipboardDelivery()
    var targetName: String { application?.localizedName ?? "l’application précédente" }
    var available: Bool { target != nil && application?.processIdentifier != ProcessInfo.processInfo.processIdentifier }
    static var trusted: Bool { AXIsProcessTrusted() }
    var issue: String? {
        if !Self.trusted { return "Accessibilité requise pour Option et l’insertion. Autorisez DictAI dans Réglages Système → Confidentialité et sécurité → Accessibilité. Votre dictée reste dans le brouillon." }
        if !available { return "Sélectionnez un champ dans l’application souhaitée, puis choisissez Insérer le texte. Votre dictée reste dans le brouillon." }
        return nil
    }
    init() {
        activation = activationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] notification in
            MainActor.assumeIsolated {
                guard let self, self.tracking, let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                      app.processIdentifier != ProcessInfo.processInfo.processIdentifier,
                      app.processIdentifier != self.application?.processIdentifier else { return }
                self.application = app
                self.target = nil; self.original = nil; self.range = nil
            }
        }
    }
    deinit { if let activation { activationCenter.removeObserver(activation) } }
    static func requestPermission() {
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
    }
    func capture() {
        tracking = true
        target = nil; original = nil; range = nil
        application = NSWorkspace.shared.frontmostApplication
        if application?.processIdentifier == ProcessInfo.processInfo.processIdentifier { application = nil }
        captureField()
    }
    func prepareForInsertion() {
        // Resolve the current field, including editors that replaced their AX element.
        // A DictAI editor may own focus; keep the last external app in that case.
        if let current = NSWorkspace.shared.frontmostApplication, current.processIdentifier != ProcessInfo.processInfo.processIdentifier { application = current }
        target = nil; original = nil; range = nil
        captureField()
    }
    private func captureField() {
        guard let application, !application.isTerminated, Self.trusted else { return }
        let app = AXUIElementCreateApplication(application.processIdentifier)
        guard let focused = attribute(app, kAXFocusedUIElementAttribute), CFGetTypeID(focused) == AXUIElementGetTypeID() else { return }
        let field = focused as! AXUIElement
        let role = attribute(field, kAXRoleAttribute) as? String ?? ""
        let subrole = attribute(field, kAXSubroleAttribute) as? String ?? ""
        guard subrole != kAXSecureTextFieldSubrole, role != "AXSecureTextField" else { return }
        guard [kAXTextFieldRole, kAXTextAreaRole, kAXComboBoxRole].contains(role) || attribute(field, kAXSelectedTextRangeAttribute) != nil else { return }
        target = field; original = attribute(field, kAXValueAttribute) as? String
        // Web editors often expose a marker range rather than a writable CFRange.
        // Preserve their native selection and use Command-V instead of AX text mutation.
        if !isWebField(field), let selected = attribute(field, kAXSelectedTextRangeAttribute), CFGetTypeID(selected) == AXValueGetTypeID() { range = (selected as! AXValue) }
    }
    func insert(_ text: String) async throws -> String {
        try require(Self.trusted, issue ?? "Autorisez l’accessibilité pour insérer le texte. Le bouton Copier reste disponible.")
        guard let target, let application, !application.isTerminated else { throw DataError.invalid("Sélectionnez un champ dans l’application souhaitée, puis choisissez Insérer le texte. Le brouillon est conservé.") }
        try require(available, "Sélectionnez d’abord un champ dans une autre application, puis appuyez sur Option.")
        if let original { try require(attribute(target, kAXValueAttribute) as? String == original, "Le champ d’origine a été modifié. Utilisez Copier pour conserver ses changements.") }
        application.activate(options: [.activateAllWindows])
        for _ in 0..<20 {
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        try require(NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier, "Impossible de réactiver l’application d’origine.")
        let app = AXUIElementCreateApplication(application.processIdentifier)
        for _ in 0..<20 {
            _ = AXUIElementSetAttributeValue(target, kAXFocusedAttribute as CFString, kCFBooleanTrue)
            if attribute(app, kAXFocusedUIElementAttribute).map({ CFEqual($0, target) }) == true { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        guard let focused = attribute(app, kAXFocusedUIElementAttribute), CFEqual(focused, target) else {
            throw DataError.invalid("Le champ d’origine a perdu le focus. Utilisez Copier.")
        }
        if let range {
            _ = AXUIElementSetAttributeValue(target, kAXSelectedTextRangeAttribute as CFString, range)
            try require(attribute(target, kAXSelectedTextRangeAttribute).map { CFEqual($0, range) } == true,
                        "Impossible de retrouver la position du curseur. Le texte reste dans le brouillon.")
        }
        var selection: NSRange?
        if let range {
            var value = CFRange()
            if AXValueGetValue(range, .cfRange, &value) { selection = NSRange(location: value.location, length: value.length) }
        }
        let expected = InsertionReceipt.expected(original: original, selection: selection, inserted: text)
        // Some web editors acknowledge AXSelectedText without actually changing the field.
        // Only try this path when its effect can be read back; otherwise use real paste events.
        return try await InsertionDelivery.send(canVerify: expected != nil, select: {
            _ = AXUIElementSetAttributeValue(target, kAXSelectedTextAttribute as CFString, text as CFString)
        }, read: {
            try await self.receipt(target, expected: expected)
        }, paste: {
            try await self.clipboard.paste(text) {
                NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier &&
                self.attribute(app, kAXFocusedUIElementAttribute).map { CFEqual($0, target) } == true
            }
        })
    }
    private func isWebField(_ field: AXUIElement) -> Bool {
        var element: AXUIElement? = field
        for _ in 0..<20 {
            guard let current = element else { break }
            if attribute(current, kAXRoleAttribute) as? String == "AXWebArea" { return true }
            guard let parent = attribute(current, kAXParentAttribute), CFGetTypeID(parent) == AXUIElementGetTypeID() else { break }
            element = (parent as! AXUIElement)
        }
        return false
    }
    private func receipt(_ target: AXUIElement, expected: String?) async throws -> InsertionReceipt.Result {
        var result = InsertionReceipt.Result.unreadable
        for _ in 0..<10 {
            result = InsertionReceipt.check(original: original, expected: expected, actual: attribute(target, kAXValueAttribute) as? String)
            if result == .confirmed || result == .changed { return result }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return result
    }
    func copy(_ text: String) { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string) }
    private func attribute(_ element: AXUIElement, _ name: String) -> CFTypeRef? {
        var result: CFTypeRef?
        return AXUIElementCopyAttributeValue(element, name as CFString, &result) == .success ? result : nil
    }
}
