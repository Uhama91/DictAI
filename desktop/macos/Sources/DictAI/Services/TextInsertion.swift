import AppKit
import ApplicationServices
import DictAICore

@MainActor final class TextInsertion: TextDelivering {
    private var target: AXUIElement?
    private var application: NSRunningApplication?
    private var original: String?
    private var range: AXValue?
    private var nativePaste = false
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
                guard let self, let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                      app.processIdentifier != ProcessInfo.processInfo.processIdentifier,
                      app.processIdentifier != self.application?.processIdentifier else { return }
                self.application = app
                self.target = nil; self.original = nil; self.range = nil; self.nativePaste = false
            }
        }
    }
    deinit { if let activation { activationCenter.removeObserver(activation) } }
    static func requestPermission() {
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
    }
    func capture() {
        target = nil; original = nil; range = nil; nativePaste = false
        application = NSWorkspace.shared.frontmostApplication
        if application?.processIdentifier == ProcessInfo.processInfo.processIdentifier { application = nil }
        captureField()
    }
    func prepareForInsertion() {
        // Resolve the current field, including editors that replaced their AX element.
        // A DictAI editor may own focus; keep the last external app in that case.
        if let current = NSWorkspace.shared.frontmostApplication, current.processIdentifier != ProcessInfo.processInfo.processIdentifier { application = current }
        target = nil; original = nil; range = nil; nativePaste = false
        captureField()
    }
    private func captureField() {
        guard let application, !application.isTerminated, Self.trusted else { return }
        let app = AXUIElementCreateApplication(application.processIdentifier)
        let field = element(app, kAXFocusedUIElementAttribute)
        let role = field.flatMap { attribute($0, kAXRoleAttribute) as? String } ?? ""
        let subrole = field.flatMap { attribute($0, kAXSubroleAttribute) as? String } ?? ""
        let webField = field.map(isWebField) ?? false
        let pasteAvailable = hasEnabledPasteCommand(app)
        let policy = InsertionTargetPolicy.resolve(bundleIdentifier: application.bundleIdentifier, role: role,
            subrole: subrole, hasSelection: field.flatMap { attribute($0, kAXSelectedTextRangeAttribute) } != nil,
            isWebField: webField, hasPasteAction: pasteAvailable)
        writeTargetDiagnostic(["stage": "capture", "application": application.bundleIdentifier ?? "",
            "role": role, "subrole": subrole, "pasteAvailable": pasteAvailable,
            "windowAvailable": focusedWindow(app) != nil, "policy": String(describing: policy)])
        guard policy != .unavailable else { return }
        if policy == .nativePaste {
            // Custom editors can expose a scroll area or no focused AX element at all.
            // Preserve the native responder and anchor the paste to its current window.
            target = focusedWindow(app)
            nativePaste = target != nil
            original = nil; range = nil
            return
        }
        guard let field else { return }
        target = field
        original = attribute(field, kAXValueAttribute) as? String
        // Web editors often expose a marker range rather than a writable CFRange.
        // Preserve their native selection and use Command-V instead of AX text mutation.
        if !webField, let selected = attribute(field, kAXSelectedTextRangeAttribute), CFGetTypeID(selected) == AXValueGetTypeID() { range = (selected as! AXValue) }
    }
    func insert(_ text: String) async throws -> String {
        try require(Self.trusted, issue ?? "Autorisez l’accessibilité pour insérer le texte. Le bouton Copier reste disponible.")
        guard let target, let application, !application.isTerminated else { throw DataError.invalid("Sélectionnez un champ dans l’application souhaitée, puis choisissez Insérer le texte. Le brouillon est conservé.") }
        let usesNativePaste = nativePaste
        try require(available, "Sélectionnez d’abord un champ dans une autre application, puis appuyez sur Option.")
        if let original { try require(attribute(target, kAXValueAttribute) as? String == original, "Le champ d’origine a été modifié. Utilisez Copier pour conserver ses changements.") }
        application.activate(options: [.activateAllWindows])
        for _ in 0..<20 {
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        try require(NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier, "Impossible de réactiver l’application d’origine.")
        let app = AXUIElementCreateApplication(application.processIdentifier)
        if usesNativePaste {
            try require(nativePasteTargetIsFocused(app, window: target, pid: application.processIdentifier),
                        "La zone de saisie active ne permet plus le collage. Votre texte reste dans le brouillon.")
        } else {
            for _ in 0..<20 {
                _ = AXUIElementSetAttributeValue(target, kAXFocusedAttribute as CFString, kCFBooleanTrue)
                if attribute(app, kAXFocusedUIElementAttribute).map({ CFEqual($0, target) }) == true { break }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            guard let focused = attribute(app, kAXFocusedUIElementAttribute), CFEqual(focused, target) else {
                throw DataError.invalid("Le champ d’origine a perdu le focus. Utilisez Copier.")
            }
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
            var pasteAction: (() throws -> Void)?
            if usesNativePaste { pasteAction = { try self.performNativePaste(app) } }
            try await self.clipboard.paste(text, performPaste: pasteAction) {
                if usesNativePaste { return self.nativePasteTargetIsFocused(app, window: target, pid: application.processIdentifier) }
                return NSWorkspace.shared.frontmostApplication?.processIdentifier == application.processIdentifier &&
                self.attribute(app, kAXFocusedUIElementAttribute).map { CFEqual($0, target) } == true
            }
        })
    }
    private func nativePasteTargetIsFocused(_ app: AXUIElement, window: AXUIElement, pid: pid_t) -> Bool {
        let appFocused = NSWorkspace.shared.frontmostApplication?.processIdentifier == pid
        let windowFocused = focusedWindow(app).map({ CFEqual($0, window) }) == true
        var secure = false
        if let focused = element(app, kAXFocusedUIElementAttribute) {
            let role = attribute(focused, kAXRoleAttribute) as? String ?? ""
            let subrole = attribute(focused, kAXSubroleAttribute) as? String ?? ""
            secure = role == "AXSecureTextField" || subrole == kAXSecureTextFieldSubrole
        }
        let pasteAvailable = hasEnabledPasteCommand(app)
        writeTargetDiagnostic(["stage": "paste-check", "application": NSRunningApplication(processIdentifier: pid)?.bundleIdentifier ?? "",
            "delivery": "native-menu", "applicationFocused": appFocused,
            "windowFocused": windowFocused, "secure": secure, "pasteAvailable": pasteAvailable])
        return appFocused && windowFocused && !secure && pasteAvailable
    }
    private func focusedWindow(_ app: AXUIElement) -> AXUIElement? {
        element(app, kAXFocusedWindowAttribute) ?? element(app, kAXMainWindowAttribute)
    }
    private func hasEnabledPasteCommand(_ app: AXUIElement) -> Bool {
        enabledPasteCommand(app) != nil
    }
    private func performNativePaste(_ app: AXUIElement) throws {
        guard let command = enabledPasteCommand(app) else {
            throw DataError.invalid("La commande Coller n’est plus disponible. Votre texte reste dans le brouillon.")
        }
        try require(AXUIElementPerformAction(command, kAXPressAction as CFString) == .success,
                    "La commande Coller n’a pas pu être activée. Votre texte reste dans le brouillon.")
    }
    private func enabledPasteCommand(_ app: AXUIElement) -> AXUIElement? {
        guard let menu = element(app, kAXMenuBarAttribute) else { return nil }
        func findPaste(_ item: AXUIElement, depth: Int) -> AXUIElement? {
            let key = (attribute(item, kAXMenuItemCmdCharAttribute) as? String)?.lowercased()
            let modifiers = (attribute(item, kAXMenuItemCmdModifiersAttribute) as? NSNumber)?.uint32Value
            let identifier = attribute(item, kAXIdentifierAttribute) as? String
            if (identifier == "paste:" || (key == "v" && modifiers == 0)),
               attribute(item, kAXEnabledAttribute) as? Bool == true { return item }
            guard depth > 0 else { return nil }
            let children = attribute(item, kAXChildrenAttribute) as? [AXUIElement] ?? []
            for child in children {
                if let command = findPaste(child, depth: depth - 1) { return command }
            }
            return nil
        }
        return findPaste(menu, depth: 5)
    }
    private func element(_ parent: AXUIElement, _ name: String) -> AXUIElement? {
        guard let value = attribute(parent, name), CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
        return (value as! AXUIElement)
    }
    private func writeTargetDiagnostic(_ fields: [String: Any]) {
        // Structural metadata only: never persist the dictation, field value, selection or clipboard.
        guard let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first,
              let data = try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys]) else { return }
        try? data.write(to: root.appendingPathComponent("DictAI/last-insertion-target.json"), options: .atomic)
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
