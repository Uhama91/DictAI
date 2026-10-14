import ApplicationServices

enum InsertionTargetPolicy: Equatable {
    case field, nativePaste, unavailable

    static func resolve(bundleIdentifier: String?, role: String, subrole: String,
                        hasSelection: Bool, isWebField: Bool, hasPasteAction: Bool = false) -> Self {
        guard subrole != kAXSecureTextFieldSubrole, role != "AXSecureTextField" else { return .unavailable }
        let terminal = ["com.apple.Terminal", "com.googlecode.iterm2", "com.mitchellh.ghostty", "com.cmuxterm.app"]
            .contains(bundleIdentifier ?? "")
        if terminal && !isWebField && (role == kAXTextAreaRole ||
            (role == kAXWindowRole && subrole == kAXStandardWindowSubrole)) {
            return .nativePaste
        }
        if [kAXTextFieldRole, kAXTextAreaRole, kAXComboBoxRole].contains(role) || hasSelection { return .field }
        return hasPasteAction ? .nativePaste : .unavailable
    }
}
