import AppKit
import SwiftUI
import DictAICore

enum DictationState: String { case idle, preparing, listening, finishing, paused, review, cleaning, inserting }
enum DictationDestination { case field, note }
enum DictationPresentation { case compact, editor, window }

@MainActor final class AppModel: ObservableObject {
    let library: LibraryStore
    let setup: SetupState
    let account = AccountController()
    let sync: SyncCoordinator
    let shortcut = OptionShortcut()
    let appUpdates = AppUpdateChecker()
    let speech: any SpeechCapturing
    let insertion: any TextDelivering
    let cleaner: any DictationCleaning
    private let makeOverlay: @MainActor (AppModel) -> any OverlayPresenting
    lazy var overlay = makeOverlay(self)
    let editorSession = DictationEditorSession()
    @Published var state: DictationState = .idle {
        didSet { editorSession.listening = state == .listening }
    }
    @Published var draft = DictationDraft()
    @Published var level: Float = 0
    @Published var message = ""
    @Published var panelVisible = false
    @Published var libraryPage = "notes"
    @Published private(set) var presentation: DictationPresentation = .editor
    @Published private(set) var delivered = false
    @Published private(set) var destination: DictationDestination = .field
    @Published var startedAt = Date()
    private var operation: Task<Void, Never>?
    private var revision = UUID()
    private var generation = UUID()
    private var deliverWhenFinished = false
    private var pauseWhenFinished = false
    private var resumeAfterPause = false
    private var capturedTarget = false
    private var segmentHasSpeech = false
    private var historyCancelled = false
    private var savedNote: Note?
    private var savedNoteBase: String?
    private var didStart = false
    init(root: URL? = nil, speech: (any SpeechCapturing)? = nil, insertion: (any TextDelivering)? = nil,
         cleaner: (any DictationCleaning)? = nil, makeOverlay: @escaping @MainActor (AppModel) -> any OverlayPresenting = { OverlayController(model: $0) }) throws {
        library = try LibraryStore(root: root)
        let setup = try SetupState(root: library.root)
        self.setup = setup
        self.speech = speech ?? SpeechEngine(); self.insertion = insertion ?? TextInsertion()
        self.cleaner = cleaner ?? LiveDictationCleaner(); self.makeOverlay = makeOverlay
        sync = SyncCoordinator(library: library, account: account)
        if let restored = library.loadDraft() { draft = restored; if !draft.text.isEmpty { state = .review } }
        account.onChange = { [weak self] session in
            guard let self else { return }
            self.closeOverlay()
            try self.library.switchScope(session?.userId)
            self.draft = self.library.loadDraft() ?? DictationDraft()
            self.editorSession.reset()
            self.delivered = false
            self.capturedTarget = false; self.savedNote = nil; self.savedNoteBase = nil
        }
        self.speech.onText = { [weak self] text in
            guard let self, [.preparing, .listening, .finishing].contains(self.state) else { return }
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { self.segmentHasSpeech = true }
            let language = self.library.preference("dictation_language", default: "fr")
            let terms = self.dictationTerms
            self.draft.receive(DictationTypography.live(text, language: language, protectedTerms: terms), language: language, protectedTerms: terms)
            self.persistDraft()
        }
        self.speech.onLevel = { [weak self] in self?.level = $0 }
        self.speech.onFinish = { [weak self] error in self?.finished(error) }
        shortcut.onGesture = { [weak self] in self?.handleOption($0) }
        shortcut.onCancel = { [weak self] in if self?.panelVisible == true { self?.closeOverlay() } }
    }
    func start() {
        guard !didStart else { return }; didStart = true
        if PreviewMode.enabled {
            if speech is PreviewSpeech { begin(destination: .note) }
            return
        }
        do {
            try account.restore()
            if let session = account.session { try library.switchScope(session.userId); draft = library.loadDraft() ?? DictationDraft() }
        } catch { library.error = error.localizedDescription }
        shortcut.start(); sync.start()
        appUpdates.start()
        Task { await sync.sync() }
    }
    private func handleOption(_ action: OptionGesture.Action) {
        switch action {
        case .single:
            if state == .finishing && pauseWhenFinished {
                resumeAfterPause = true
            } else { toggle() }
        case .double:
            guard panelVisible, !delivered else { return }
            if state == .listening { pauseListening() }
            else if state == .paused { resumeListening() }
            else if state == .finishing && pauseWhenFinished { resumeAfterPause.toggle() }
        case .triple:
            if panelVisible || busy { historyCancelled = true; closeOverlay() }
        }
    }
    func toggle() {
        switch state {
        case .listening: finishListening(deliver: true)
        case .preparing: closeOverlay()
        case .finishing, .cleaning, .inserting: break
        case .paused:
            if panelVisible { resumeListening() }
            else { begin() }
        case .review where panelVisible && !delivered && !draft.text.isEmpty: completeDictation()
        default: begin()
        }
    }
    func begin(destination: DictationDestination = .field) {
        guard ![.preparing, .listening, .finishing, .cleaning, .inserting].contains(state) else { return }
        do {
            if !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if var note = savedNote {
                    if note.text != draft.text {
                        note.text = draft.text
                        _ = try library.save(note, basedOn: savedNoteBase)
                    }
                } else if self.destination == .field && !draft.recognized.isEmpty {
                    try recordRecentDictation()
                } else { _ = try library.createNote(text: draft.text, folder: library.selectedFolder) }
            }
        } catch { reveal(error.localizedDescription); return }
        self.destination = destination
        insertion.capture(); capturedTarget = true
        draft = DictationDraft(); savedNote = nil; savedNoteBase = nil
        historyCancelled = false
        editorSession.reset()
        presentation = .compact
        startSpeechSegment()
    }
    func resumeListening() {
        guard !busy, state != .listening else { return }
        if !capturedTarget { insertion.capture(); capturedTarget = true }
        delivered = false
        historyCancelled = false
        startSpeechSegment()
    }
    private func startSpeechSegment() {
        revision = UUID(); generation = UUID(); let epoch = generation
        deliverWhenFinished = false; pauseWhenFinished = false; resumeAfterPause = false; delivered = false; segmentHasSpeech = false
        draft.beginSegment(); persistDraft()
        message = destination == .field ? insertion.issue ?? "" : ""; level = 0; state = .preparing
        overlay.show(); panelVisible = true
        operation = Task {
            do {
                try await speech.start(language: library.preference("dictation_language", default: "fr"), vocabulary: library.vocabulary.map(\.value))
                guard generation == epoch, state == .preparing, !Task.isCancelled else { return }
                state = .listening; startedAt = Date()
            } catch {
                guard generation == epoch else { return }
                reveal(error.localizedDescription)
            }
        }
    }
    private func finishListening(deliver: Bool) {
        deliverWhenFinished = deliver
        state = .finishing
        speech.stop()
    }
    func pauseListening() {
        guard state == .listening else { return }
        pauseWhenFinished = true
        finishListening(deliver: false)
    }
    func reviewBeforeDelivery() {
        deliverWhenFinished = false
        if state == .listening { finishListening(deliver: false) }
        present(presentation == .window ? .window : .editor, focus: true)
    }
    func edit(_ text: String) {
        guard text != draft.text else { return }
        draft.edit(text); revision = UUID(); delivered = false
        if state == .idle { state = .review }
        persistDraft()
    }
    func showDraft() { present(.editor, focus: true) }
    func showCompact() { present(.compact, focus: false) }
    func showWorkspace() { present(.window, focus: true) }
    func toggleExpanded() { presentation == .window ? showCompact() : showWorkspace() }
    private func present(_ presentation: DictationPresentation, focus: Bool) {
        guard !delivered || !draft.text.isEmpty else { return }
        self.presentation = presentation; panelVisible = true; overlay.show(focus: focus)
    }
    private func revealEditor() {
        present(presentation == .window ? .window : .editor, focus: false)
    }
    func closeOverlay() {
        revision = UUID(); generation = UUID(); deliverWhenFinished = false; resumeAfterPause = false
        operation?.cancel(); speech.cancel(); level = 0
        state = draft.text.isEmpty ? .idle : .paused
        persistDraft(); overlay.hide(); panelVisible = false
    }
    private func persistDraft() {
        do { try library.saveDraft(draft) } catch { message = "Le brouillon n’a pas pu être enregistré : " + error.localizedDescription }
    }
    private func reveal(_ reason: String) {
        state = .review; message = reason; revealEditor()
    }
    private func finished(_ error: String?) {
        guard [.listening, .finishing, .preparing].contains(state) else { return }
        // A recognizer may finish its request after a silence. It does not end the document.
        if state == .listening && error == nil {
            guard segmentHasSpeech else { state = .paused; level = 0; return }
            startSpeechSegment(); return
        }
        let shouldDeliver = deliverWhenFinished
        let shouldPause = pauseWhenFinished; pauseWhenFinished = false
        let shouldResume = resumeAfterPause; resumeAfterPause = false
        deliverWhenFinished = false; state = .review; level = 0
        draft.normalizeRecognition(Vocabulary.apply(draft.text, lines: library.vocabulary.map(\.value)))
        persistDraft()
        if let error { reveal("Reconnaissance interrompue : " + error); return }
        if shouldPause {
            state = .paused
            if shouldResume { resumeListening() }
            return
        }
        guard !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            reveal("Aucune parole reconnue. Appuyez sur Option pour réessayer."); return
        }
        if !draft.edited && library.preference("formatting_engine", default: "off") == "cloud" {
            clean(deliverAfter: shouldDeliver)
        } else if shouldDeliver { completeDictation() }
        else { finalizeTypography(); revealEditor() }
    }
    var learningSuggestion: String? { Vocabulary.suggestion(before: draft.recognized, after: draft.text) }
    var busy: Bool { [.preparing, .finishing, .cleaning, .inserting].contains(state) }
    var deliveryLabel: String { destination == .note ? "Enregistrer la note" : "Insérer le texte" }
    func learn(_ rule: String) { library.perform { try library.addVocabulary(rule); message = "Préférence ajoutée au vocabulaire" } }
    func saveNote() {
        guard !busy, !delivered, state != .listening, !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        finalizeTypography()
        library.perform {
            try archiveDraft()
            endDeliveredSession("Note enregistrée")
        }
    }
    private func archiveDraft() throws {
        if var note = savedNote {
            if note.text != draft.text || note.folderId != library.selectedFolder {
                note.text = draft.text; note.folderId = library.selectedFolder
                savedNote = try library.save(note, basedOn: savedNoteBase)
            }
        } else { savedNote = try library.createNote(text: draft.text, folder: library.selectedFolder) }
        savedNoteBase = savedNote.flatMap { library.document.entries["note:" + $0.id]?.value }
    }
    private func endDeliveredSession(_ result: String) {
        // The recovery buffer must be durable before the active draft is cleared.
        message = result; delivered = true
        do {
            try recordRecentDictation()
            try library.saveDraft(DictationDraft())
            draft = DictationDraft(); savedNote = nil; savedNoteBase = nil
        } catch {
            library.error = "La dictée est terminée, mais son historique n’a pas pu être enregistré. Le brouillon est conservé : " + error.localizedDescription
        }
        revision = UUID(); generation = UUID()
        deliverWhenFinished = false; pauseWhenFinished = false; resumeAfterPause = false
        state = .idle; speech.cancel(); level = 0; capturedTarget = false
        editorSession.reset(); overlay.hide(); panelVisible = false; presentation = .compact
    }
    func completeDictation() { if destination == .note { saveNote() } else { insert() } }
    @discardableResult func copyLatestDictation(to pasteboard: NSPasteboard = .general) -> Bool {
        guard let latest = library.history.entries.first else { return false }
        pasteboard.clearContents()
        return pasteboard.setString(latest.text, forType: .string)
    }
    private func recordRecentDictation() throws {
        guard !historyCancelled, !draft.recognized.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        try library.history.record(id: draft.historyID, text: draft.text)
    }
    func insert() {
        guard [.review, .paused].contains(state), !delivered, !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        finalizeTypography()
        do { try recordRecentDictation() }
        catch { library.error = "L’historique n’a pas pu être enregistré. Le brouillon est conservé : " + error.localizedDescription }
        let text = draft.text + (library.preference("trailing_space", default: "true") == "true" ? " " : "")
        let edit = revision; let epoch = generation; let scope = library.generation
        state = .inserting
        insertion.prepareForInsertion()
        // A nonactivating NSPanel can still own keyboard focus. Release it before delivery.
        overlay.hide(); panelVisible = false
        operation = Task {
            do {
                let result = try await insertion.insert(text)
                guard epoch == generation, scope == library.generation else { return }
                guard edit == revision else { reveal("Vos retouches sont conservées. Le texte envoyé précédait cette modification."); return }
                endDeliveredSession(result)
            } catch {
                guard epoch == generation, scope == library.generation else { return }
                reveal(error.localizedDescription)
            }
        }
    }
    private func finalizeTypography() {
        guard !draft.edited, library.preference("formatting_engine", default: "off") != "cloud" else { return }
        draft.normalizeRecognition(DictationTypography.finalized(draft.text, language: library.preference("dictation_language", default: "fr"), protectedTerms: dictationTerms))
        persistDraft()
    }
    private var dictationTerms: [String] {
        library.vocabulary.map { $0.value.components(separatedBy: "=>").last!.trimmingCharacters(in: .whitespaces) }
    }
    func clean(deliverAfter: Bool = false) {
        guard !draft.text.isEmpty, [.review, .paused].contains(state) else { return }
        let text = draft.text; let edit = revision; let epoch = generation; let scope = library.generation
        let preferences = library.document.values; let vocabulary = library.vocabulary.map(\.value)
        state = .cleaning; message = ""
        operation = Task {
            var failure: String?
            do {
                let result = try await cleaner.process(text, preferences: preferences, vocabulary: vocabulary)
                guard generation == epoch, scope == library.generation, !Task.isCancelled else { return }
                if revision == edit { draft.edit(result); persistDraft() }
            } catch { failure = error.localizedDescription }
            guard generation == epoch, scope == library.generation, !Task.isCancelled else { return }
            state = .review
            guard revision == edit else {
                let action = destination == .note ? "l’enregistrer" : "l’insérer"
                reveal("Vos retouches ont été conservées. Vérifiez le texte avant de \(action)."); return
            }
            if let failure { reveal(failure); return }
            if deliverAfter { completeDictation() }
            else { revealEditor() }
        }
    }
}
