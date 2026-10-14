import Testing
@testable import DictAI

struct InsertionTargetPolicyTests {
    @Test func cmuxTerminalWindowCanReceivePasteWithoutAnAccessibleTextField() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.cmuxterm.app", role: "AXWindow",
            subrole: "AXStandardWindow", hasSelection: false, isWebField: false) == .nativePaste)
    }

    @Test func terminalOutputAndSelectionAreNeverReplacedAsAnEditableDocument() {
        for bundle in ["com.cmuxterm.app", "com.mitchellh.ghostty", "com.apple.Terminal", "com.googlecode.iterm2"] {
            #expect(InsertionTargetPolicy.resolve(bundleIdentifier: bundle, role: "AXTextArea",
                subrole: "", hasSelection: true, isWebField: false) == .nativePaste)
        }
    }

    @Test func unknownApplicationsStillRequireATextField() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.example.app", role: "AXWindow",
            subrole: "AXStandardWindow", hasSelection: false, isWebField: false) == .unavailable)
    }

    @Test func cmuxFocusedScrollAreaUsesItsNativePasteCommand() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.cmuxterm.app", role: "AXScrollArea",
            subrole: "", hasSelection: false, isWebField: false, hasPasteAction: true) == .nativePaste)
    }

    @Test func customEditorsDoNotNeedAnApplicationAllowlistOrAStandardTextRole() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.example.custom-editor", role: "AXGroup",
            subrole: "", hasSelection: false, isWebField: false, hasPasteAction: true) == .nativePaste)
    }

    @Test func aNativePasteCommandCanTargetTheWindowWhenNoFocusedElementIsExposed() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.example.custom-editor", role: "",
            subrole: "", hasSelection: false, isWebField: false, hasPasteAction: true) == .nativePaste)
    }

    @Test func terminalDialogsAndButtonsAreNotPasteTargets() {
        for (role, subrole) in [("AXButton", ""), ("AXStaticText", ""), ("AXWindow", "AXDialog")] {
            #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.cmuxterm.app", role: role,
                subrole: subrole, hasSelection: false, isWebField: false) == .unavailable)
        }
    }

    @Test func secureFieldsRemainBlockedInsideTerminalApplications() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.cmuxterm.app", role: "AXTextField",
            subrole: "AXSecureTextField", hasSelection: true, isWebField: false, hasPasteAction: true) == .unavailable)
    }

    @Test func browserAndOrdinaryTextFieldsKeepTheExistingFieldPath() {
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.cmuxterm.app", role: "AXTextArea",
            subrole: "", hasSelection: true, isWebField: true) == .field)
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.apple.TextEdit", role: "AXTextArea",
            subrole: "", hasSelection: true, isWebField: false) == .field)
        #expect(InsertionTargetPolicy.resolve(bundleIdentifier: "com.cmuxterm.app", role: "AXTextField",
            subrole: "", hasSelection: true, isWebField: false) == .field)
    }
}
