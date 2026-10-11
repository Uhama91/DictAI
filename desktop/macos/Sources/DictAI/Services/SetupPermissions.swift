import AppKit
import AVFoundation
import Speech

@MainActor final class SetupPermissions: ObservableObject {
    @Published private(set) var readiness = SetupReadiness(microphone: false, speech: false, accessibility: false, localRecognition: false, recognitionAvailable: false)
    @Published private(set) var requesting = false
    func refresh(language: String) {
        let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language == "en" ? "en-US" : "fr-FR"))
        readiness = SetupReadiness(
            microphone: AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
            speech: SFSpeechRecognizer.authorizationStatus() == .authorized,
            accessibility: TextInsertion.trusted,
            localRecognition: recognizer?.supportsOnDeviceRecognition == true,
            recognitionAvailable: recognizer?.isAvailable == true)
    }
    func requestAudio(language: String) async {
        guard !requesting else { return }
        requesting = true
        defer { requesting = false; refresh(language: language) }
        _ = await AVCaptureDevice.requestAccess(for: .audio)
        _ = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
    }
    func requestAccessibility() {
        TextInsertion.requestPermission()
        openPrivacy("Privacy_Accessibility")
    }
    func openPrivacy(_ key: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?" + key) { NSWorkspace.shared.open(url) }
    }
    func openKeyboard() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension") { NSWorkspace.shared.open(url) }
    }
}
