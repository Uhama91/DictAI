import Foundation
import Speech
import AVFoundation
import DictAICore

@MainActor final class SpeechEngine: SpeechCapturing {
    var onText: ((String) -> Void)?
    var onLevel: ((Float) -> Void)?
    var onFinish: ((String?) -> Void)?
    private var engine: AVAudioEngine?
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var timeout: Task<Void, Never>?
    private var epoch = UUID()
    private var tapping = false
    private var completed = false
    private var transcript = SpeechTranscriptBuffer()
    func start(language: String, vocabulary: [String]) async throws {
        cancel()
        let session = UUID(); epoch = session; completed = false
        let terms = vocabulary.map { $0.components(separatedBy: "=>").last!.trimmingCharacters(in: .whitespaces) }
        transcript = SpeechTranscriptBuffer(language: language, protectedTerms: terms)
        let microphone = await AVCaptureDevice.requestAccess(for: .audio)
        try require(epoch == session, "Dictée annulée.")
        try require(microphone, "Autorisez le microphone dans Réglages Système → Confidentialité et sécurité → Microphone.")
        let permission = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        try require(epoch == session, "Dictée annulée.")
        try require(permission == .authorized, "Autorisez DictAI dans Réglages Système → Confidentialité et sécurité → Reconnaissance vocale.")
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language == "en" ? "en-US" : "fr-FR")), recognizer.isAvailable else {
            throw DataError.invalid("La reconnaissance vocale est indisponible pour cette langue.")
        }
        try require(recognizer.supportsOnDeviceRecognition, "Le modèle vocal local n’est pas disponible. Activez la dictée de cette langue dans Réglages Système → Clavier, puis réessayez.")
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        request.contextualStrings = Array(terms.prefix(100))
        request.taskHint = .dictation
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        try require(format.channelCount > 0 && format.sampleRate > 0, "Aucun microphone n’est disponible.")
        self.engine = engine; self.recognizer = recognizer; self.request = request
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor in
                guard let self, self.epoch == session, !self.completed else { return }
                if let result {
                    let value = result.bestTranscription
                    let last = value.segments.last
                    let text = self.transcript.receive(value.formattedString,
                        start: value.segments.first?.timestamp,
                        end: last.map { $0.timestamp + $0.duration },
                        complete: result.speechRecognitionMetadata != nil || result.isFinal)
                    self.onText?(text)
                }
                if result?.isFinal == true { self.finish(message: nil) }
                else if let error { self.finish(message: error.localizedDescription) }
            }
        }
        input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            request.append(buffer)
            if let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 {
                var sum: Float = 0
                for index in 0..<Int(buffer.frameLength) { sum += samples[index] * samples[index] }
                let level = min(1, sqrt(sum / Float(buffer.frameLength)) * 8)
                Task { @MainActor in guard self?.epoch == session else { return }; self?.onLevel?(level) }
            }
        }
        tapping = true
        do { engine.prepare(); try engine.start() }
        catch { cancel(); throw error }
    }
    func stop() {
        stopMicrophone()
        request?.endAudio()
        let session = epoch
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled, let self, self.epoch == session else { return }
            self.finish(message: nil)
        }
    }
    func cancel() {
        epoch = UUID(); completed = true
        timeout?.cancel(); timeout = nil
        stopMicrophone(); task?.cancel(); task = nil; request = nil; recognizer = nil
    }
    private func stopMicrophone() {
        engine?.stop()
        if tapping { engine?.inputNode.removeTap(onBus: 0); tapping = false }
        engine = nil
    }
    private func finish(message: String?) {
        guard !completed else { return }; completed = true
        timeout?.cancel(); timeout = nil
        stopMicrophone(); task?.cancel(); task = nil; request = nil
        onLevel?(0); onFinish?(message)
    }
}
