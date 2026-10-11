import Foundation
import Testing
import DictAICore
@testable import DictAI

@MainActor struct DictationFlowTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["DICTAI_TEST_LOCAL_MODEL"] == "1"))
    func theAvailableLocalModelPunctuatesBeforeTheRealAppFlowDelivers() async throws {
        let rig = try Rig(localModel: true)
        #expect(rig.model.punctuator.available)
        rig.model.begin(); await rig.settle()
        rig.speech.onText?("je regarde l'application dans Le navigateur la dictée est terminée je peux passer à la suite")
        // Simulate the user speaking while the empty model session warms up.
        try await Task.sleep(for: .seconds(3))
        let started = Date()
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.state == .punctuating)
        while rig.model.busy && Date().timeIntervalSince(started) < 30 { try await Task.sleep(for: .milliseconds(100)) }
        #expect(rig.delivery.texts == ["Je regarde l'application dans le navigateur. La dictée est terminée. Je peux passer à la suite. "])
        #expect(rig.model.state == .idle && rig.model.delivered)
        #expect(rig.model.library.loadDraft()?.text.isEmpty == true)
        print("Local punctuation through AppModel: \(Date().timeIntervalSince(started)) seconds")
    }
    @Test func localPunctuationRunsBeforeInsertionAndKeepsAllDictatedWords() async throws {
        let rig = try Rig(punctuation: true)
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je finis la séance les enfants rangent leur cahier")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.punctuator.inputs == ["je finis la séance les enfants rangent leur cahier"])
        #expect(rig.model.state == .punctuating && rig.model.busy)
        #expect(rig.delivery.texts.isEmpty)
        rig.model.toggle()
        rig.punctuator.resolve("Je finis la séance. Les enfants rangent leur cahier.")
        await rig.settle()
        #expect(rig.delivery.texts == ["Je finis la séance. Les enfants rangent leur cahier. "])
        #expect(rig.model.library.notes.map(\.text) == ["Je finis la séance. Les enfants rangent leur cahier."])
    }
    @Test func reviewIsPunctuatedOnceAndSavingANoteUsesTheSameResult() async throws {
        let rig = try Rig(punctuation: true)
        rig.model.begin(destination: .note); await rig.settle()
        rig.speech.onText?("la réunion est finie nous partons")
        rig.model.reviewBeforeDelivery(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.punctuator.inputs.count == 1)
        rig.punctuator.resolve("La réunion est finie. Nous partons."); await rig.settle()
        #expect(rig.model.draft.text == "La réunion est finie. Nous partons.")
        #expect(rig.model.state == .review)
        rig.model.saveNote(); await rig.settle()
        #expect(rig.punctuator.inputs.count == 1)
        #expect(rig.model.library.notes.map(\.text) == ["La réunion est finie. Nous partons."])
    }
    @Test func pauseAndRecognizerRestartNeverTriggerLocalPunctuation() async throws {
        let rig = try Rig(punctuation: true)
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je commence la phrase")
        rig.speech.onFinish?(nil); await rig.settle()
        rig.speech.onText?("et je continue")
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.punctuator.inputs.isEmpty)
        #expect(rig.model.draft.text == "je commence la phrase et je continue")
        rig.model.insert(); await rig.settle()
        #expect(rig.punctuator.inputs.count == 1)
        rig.punctuator.resolve("Je commence la phrase et je continue."); await rig.settle()
        #expect(rig.delivery.texts == ["Je commence la phrase et je continue. "])
    }
    @Test func localPunctuationNeverOverridesManualEditsOrCloudChoice() async throws {
        for choice in ["edited", "disabled", "cloud", "english"] {
            let rig = try Rig(punctuation: true)
            if choice == "disabled" { rig.model.library.setIntelligentPunctuation(false) }
            if choice == "cloud" { rig.model.library.setPreference("formatting_engine", "cloud") }
            if choice == "english" { rig.model.library.setPreference("dictation_language", "en") }
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("mon titre")
            if choice == "edited" { rig.model.edit("mon titre retouché sans point") }
            rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.punctuator.inputs.isEmpty)
            if choice == "cloud" { rig.cleaner.resolve("Mon titre."); await rig.settle() }
            if choice == "edited" { #expect(rig.delivery.texts == ["mon titre retouché sans point "]) }
        }
    }
    @Test func rejectedModelChangesOrAnUnavailableResponseUseSimpleTypography() async throws {
        for result in [String?.none, "Les élèves rangent les livres.", "Les élèves ne rangent leur cahier."] {
            let rig = try Rig(punctuation: true)
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("les élèves rangent leur cahier")
            rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
            rig.punctuator.resolve(result); await rig.settle()
            #expect(rig.delivery.texts == ["Les élèves rangent leur cahier. "])
        }
    }
    @Test func vocabularyCaseSurvivesLocalPunctuation() async throws {
        let rig = try Rig(punctuation: true)
        try rig.model.library.addVocabulary("maelys => Maëlys")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je parle avec maelys nous partons")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.punctuator.inputs == ["je parle avec Maëlys nous partons"])
        rig.punctuator.resolve("Je parle avec maëlys. Nous partons."); await rig.settle()
        #expect(rig.delivery.texts == ["Je parle avec Maëlys. Nous partons. "])
    }
    @Test func editingClosingOrChangingAccountWhilePunctuatingNeverDeliversALateResult() async throws {
        for action in ["edit", "close", "account"] {
            let rig = try Rig(punctuation: true)
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("je termine le texte")
            rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
            if action == "edit" { rig.model.edit("ma retouche") }
            if action == "close" { rig.model.closeOverlay() }
            if action == "account" { try rig.model.library.switchScope("11111111-1111-4111-8111-111111111111") }
            rig.punctuator.resolve("Je termine le texte."); await rig.settle()
            #expect(rig.delivery.texts.isEmpty)
            #expect(rig.model.draft.text == (action == "edit" ? "ma retouche" : "je termine le texte"))
        }
    }
    @Test func aSlowModelCannotBlockInsertionOrDeliverItsResultAfterTheDeadline() async throws {
        let rig = try Rig(punctuation: true, timeout: .milliseconds(30))
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je termine le texte")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        try await Task.sleep(for: .milliseconds(80)); await rig.settle()
        #expect(rig.delivery.texts == ["Je termine le texte. "])
        rig.punctuator.resolve("Je termine le texte !"); await rig.settle()
        #expect(rig.delivery.texts == ["Je termine le texte. "])
    }
    @Test func aNoneditableDestinationDoesNotFallBackToThePreviousApplication() async throws {
        let rig = try Rig()
        rig.delivery.currentField = "Chrome"
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Le texte à conserver.")
        rig.delivery.currentField = ""
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.fields.isEmpty)
        #expect(rig.model.state == .review && rig.model.panelVisible)
        #expect(rig.model.library.loadDraft()?.text == "Le texte à conserver.")
    }
    @Test func insertionResolvesTheCurrentFieldAfterAnInitiallyMissingTarget() async throws {
        let rig = try Rig()
        rig.delivery.available = false
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Ma dictée.")
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        rig.delivery.currentField = "Codex recréé"
        rig.model.insert(); await rig.settle()
        #expect(rig.delivery.fields == ["Codex recréé": "Ma dictée. "])
        #expect(rig.model.delivered && !rig.model.panelVisible)
    }
    @Test func switchingApplicationsDoesNotDeliverIntoTheFieldCapturedAtStart() async throws {
        let rig = try Rig()
        rig.delivery.currentField = "Chrome"
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Une seule insertion.")
        rig.delivery.currentField = "Codex"
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.fields == ["Codex": "Une seule insertion. "])
    }
    @Test func missingAccessibilityIsExplainedWhileTranscriptionRemainsUsable() async throws {
        let rig = try Rig()
        rig.delivery.available = false
        rig.delivery.issue = "Autorisez l’accessibilité pour insérer le texte."
        rig.model.toggle(); await rig.settle()
        #expect(rig.model.state == .listening)
        #expect(rig.model.message == "Autorisez l’accessibilité pour insérer le texte.")
        rig.speech.onText?("Le texte reste disponible.")
        #expect(rig.model.draft.text == "Le texte reste disponible.")
    }
    @Test func completingRawFrenchDictationRepairsObviousCapitalsAndFinalPeriod() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je parle sur L'application dans Le navigateur avec La fenêtre ouverte")
        #expect(rig.model.draft.text == "je parle sur l'application dans le navigateur avec la fenêtre ouverte")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Je parle sur l'application dans le navigateur avec la fenêtre ouverte. "])
        #expect(rig.model.library.notes.map(\.text) == ["Je parle sur l'application dans le navigateur avec la fenêtre ouverte."])
    }
    @Test func pauseDoesNotInventAPeriodInAnUnfinishedSentence() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je travaille sur")
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.draft.text == "je travaille sur")
        rig.model.resumeListening(); await rig.settle()
        rig.speech.onText?("L’application dans Le navigateur")
        #expect(rig.model.draft.text == "je travaille sur l’application dans le navigateur")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Je travaille sur l’application dans le navigateur. "])
    }
    @Test func humanTypographyIsNeverRewrittenAtDelivery() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("je suis sur Le navigateur")
        rig.model.edit("mon titre sans point")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["mon titre sans point "])
    }
    @Test func frenchTypographyKeepsNamesAcronymsAndExistingSentencePunctuation() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Je passe par Le Havre avec DictAI et l’API. Pourquoi ?")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Je passe par Le Havre avec DictAI et l’API. Pourquoi ? "])
    }
    @Test func successfulDeliveryEndsTheSessionInEveryPresentation() async throws {
        for mode in [DictationPresentation.compact, .editor, .window] {
            let rig = try Rig()
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("Une dictée terminée.")
            if mode == .editor { rig.model.showDraft() }
            if mode == .window { rig.model.showWorkspace() }
            rig.model.shortcut.onGesture?(.single)
            rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.delivery.texts == ["Une dictée terminée. "])
            #expect(rig.model.state == .idle)
            #expect(!rig.model.panelVisible && !rig.panel.visible)
            #expect(rig.model.draft.text.isEmpty)
            #expect(rig.model.library.loadDraft()?.text.isEmpty == true)
            #expect(rig.model.library.notes.map(\.text) == ["Une dictée terminée."])
            rig.speech.onText?("Dernier résultat en retard")
            rig.speech.onFinish?(nil); await rig.settle()
            rig.model.showDraft()
            #expect(!rig.panel.visible && rig.model.draft.text.isEmpty)
            rig.model.shortcut.onGesture?(.single); await rig.settle()
            rig.speech.onText?("La dictée suivante.")
            #expect(rig.model.draft.text == "La dictée suivante.")
            #expect(rig.model.library.notes.count == 1)
            #expect(rig.delivery.captures == 2)
        }
    }
    @Test func anArchiveFailureAfterInsertionKeepsRecoveryWithoutSendingTwice() async throws {
        let rig = try Rig()
        try FileManager.default.createDirectory(at: rig.model.library.directory.appendingPathComponent("library.json"), withIntermediateDirectories: false)
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Texte envoyé mais à conserver.")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.delivered && rig.model.state == .idle)
        #expect(!rig.panel.visible)
        #expect(rig.model.library.loadDraft()?.text == "Texte envoyé mais à conserver.")
        #expect(rig.model.library.error != nil)
        rig.model.insert(); await rig.settle()
        #expect(rig.delivery.texts == ["Texte envoyé mais à conserver. "])
    }
    @Test func doubleOptionPausesAndResumesInEveryPresentationWithoutInserting() async throws {
        for mode in [DictationPresentation.compact, .editor, .window] {
            let rig = try Rig()
            rig.model.shortcut.onGesture?(.single); await rig.settle()
            rig.speech.onText?("Une idée à conserver.")
            if mode == .editor { rig.model.showDraft() }
            if mode == .window { rig.model.showWorkspace() }
            rig.model.shortcut.onGesture?(.double)
            rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.model.state == .paused && rig.model.presentation == mode)
            #expect(rig.delivery.texts.isEmpty)
            rig.model.shortcut.onGesture?(.double); await rig.settle()
            #expect(rig.model.state == .listening)
            rig.speech.onText?("Et la suite.")
            #expect(rig.model.draft.text == "Une idée à conserver. Et la suite.")
            #expect(rig.delivery.captures == 1)
        }
    }
    @Test func tripleOptionCancelsEveryPresentationAndRetainsTheDraft() async throws {
        for mode in [DictationPresentation.compact, .editor, .window] {
            let rig = try Rig()
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("Brouillon récupérable.")
            if mode == .editor { rig.model.showDraft() }
            if mode == .window { rig.model.showWorkspace() }
            rig.model.shortcut.onGesture?(.triple)
            rig.speech.onText?("Résultat vocal en retard.")
            rig.speech.onFinish?(nil); await rig.settle()
            #expect(!rig.model.panelVisible && !rig.panel.visible)
            #expect(rig.model.draft.text == "Brouillon récupérable.")
            #expect(rig.model.library.loadDraft()?.text == "Brouillon récupérable.")
            #expect(rig.delivery.texts.isEmpty)
        }
    }
    @Test func singleOptionWhilePausedFinishesInsteadOfResuming() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Texte terminé.")
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        rig.model.shortcut.onGesture?(.single); await rig.settle()
        #expect(rig.delivery.texts == ["Texte terminé. "])
        #expect(rig.speech.starts == 1)
    }
    @Test func finishingFromPauseStillUsesTheConfiguredCorrection() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("texte à corriger")
        rig.model.shortcut.onGesture?(.double)
        rig.speech.onFinish?(nil); await rig.settle()
        rig.model.shortcut.onGesture?(.single); await rig.settle()
        #expect(rig.cleaner.inputs == ["texte à corriger"])
        #expect(rig.delivery.texts.isEmpty)
        rig.cleaner.resolve("Texte à corriger."); await rig.settle()
        #expect(rig.delivery.texts == ["Texte à corriger. "])
    }
    @Test func resumeGestureDuringPauseFinalizationIsNotLost() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Première partie")
        rig.model.shortcut.onGesture?(.double)
        #expect(rig.model.state == .finishing)
        rig.model.shortcut.onGesture?(.double)
        rig.speech.onText?("Première partie.")
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.state == .listening)
        rig.speech.onText?("La suite.")
        #expect(rig.model.draft.text == "Première partie. La suite.")
        #expect(rig.delivery.texts.isEmpty)
    }
    @Test func singleGestureCanFinishAPauseThatIsStillFinalizing() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Phrase finale")
        rig.model.shortcut.onGesture?(.double)
        rig.model.shortcut.onGesture?(.single)
        rig.speech.onText?("Phrase finale.")
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Phrase finale. "])
    }
    @Test func doubleAndTripleWhileIdleNeverStartMicrophone() async throws {
        let rig = try Rig()
        rig.model.shortcut.onGesture?(.double)
        rig.model.shortcut.onGesture?(.triple); await rig.settle()
        #expect(rig.speech.starts == 0 && rig.delivery.captures == 0)
        #expect(rig.model.state == .idle)
    }
    @Test func emptySpeechCallbackNeverErasesLiveDraft() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Une phrase déjà reconnue")
        rig.model.showDraft()
        rig.speech.onText?("")
        rig.model.showWorkspace()
        #expect(rig.model.draft.text == "Une phrase déjà reconnue")
        #expect(rig.model.library.loadDraft()?.text == "Une phrase déjà reconnue")
    }
    @Test func naturalSpeechEndContinuesSameDraftUntilOptionStopsIt() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("La première phrase.")
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.state == .listening)
        rig.speech.onText?("Et la seconde.")
        #expect(rig.model.draft.text == "La première phrase. Et la seconde.")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["La première phrase. Et la seconde. "])
        #expect(rig.delivery.captures == 1)
    }
    @Test func closingAndResumingAppendsWithoutArchivingOrErasing() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Texte conservé.")
        rig.model.closeOverlay(); rig.model.showWorkspace()
        rig.model.resumeListening(); await rig.settle()
        rig.speech.onText?("La suite.")
        #expect(rig.model.draft.text == "Texte conservé. La suite.")
        #expect(rig.model.library.notes.isEmpty)
        #expect(rig.model.presentation == .window)
        #expect(rig.delivery.captures == 1)
    }
    @Test func finishingANoteArchivesItAndClearsTheActiveDraft() async throws {
        let rig = try Rig()
        rig.model.begin(destination: .note); await rig.settle()
        rig.speech.onText?("Ma note continue")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.state == .idle && !rig.model.panelVisible)
        #expect(rig.model.draft.text.isEmpty)
        #expect(rig.model.library.loadDraft()?.text.isEmpty == true)
        #expect(rig.model.library.notes.map(\.text) == ["Ma note continue."])
    }
    @Test func optionAfterClosingStartsFreshAndPreservesPreviousDraftAsANote() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Ancienne dictée")
        rig.model.closeOverlay()
        rig.model.toggle(); await rig.settle()
        #expect(rig.model.draft.text.isEmpty)
        rig.speech.onText?("Nouvelle dictée")
        #expect(rig.model.draft.text == "Nouvelle dictée")
        #expect(rig.model.library.notes.map(\.text) == ["Ancienne dictée"])
        #expect(rig.delivery.captures == 2)
        #expect(rig.model.presentation == .compact)
    }
    @Test func explicitNewNoteDoesNotResumeAnUnfinishedDictation() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Dictée précédente")
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        rig.model.begin(destination: .note); await rig.settle()
        rig.speech.onText?("Nouvelle note")
        #expect(rig.model.draft.text == "Nouvelle note")
        #expect(rig.model.library.notes.map(\.text) == ["Dictée précédente"])
    }
    @Test func presentationChangesKeepOneCaptureAndOneEditableDraft() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Les premiers mots")
        #expect(rig.model.presentation == .compact)
        rig.model.showDraft()
        #expect(rig.model.presentation == .editor && rig.model.state == .listening)
        rig.speech.onText?("Les premiers mots et la suite")
        #expect(rig.model.draft.text == "Les premiers mots et la suite")
        rig.model.edit("Mes mots corrigés")
        rig.model.showWorkspace()
        #expect(rig.model.presentation == .window)
        rig.speech.onText?("Un résultat tardif")
        rig.model.showCompact()
        #expect(rig.model.presentation == .compact)
        #expect(rig.model.draft.text == "Mes mots corrigés")
        #expect(rig.model.library.loadDraft()?.text == "Mes mots corrigés")
        #expect(rig.speech.starts == 1 && rig.delivery.captures == 1)
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Mes mots corrigés "])
    }
    @Test func reviewAndCleanupKeepTheWorkspaceOpen() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        rig.model.toggle(); await rig.settle()
        rig.model.showWorkspace(); rig.speech.onText?("Texte pour ma note")
        rig.model.reviewBeforeDelivery(); rig.speech.onFinish?(nil); await rig.settle()
        rig.cleaner.resolve("Texte pour ma note."); await rig.settle()
        #expect(rig.model.presentation == .window && rig.panel.visible)
        #expect(rig.model.state == .review && rig.delivery.texts.isEmpty)
        rig.model.closeOverlay()
        #expect(!rig.panel.visible && rig.model.library.loadDraft()?.text == "Texte pour ma note.")
    }
    @Test func reviewActionStopsWithoutSendingAndAllowsExplicitInsertion() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Un texte à relire")
        rig.model.reviewBeforeDelivery(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts.isEmpty)
        #expect(rig.model.presentation == .editor && rig.panel.visible)
        rig.model.edit("Un texte corrigé")
        rig.model.insert(); await rig.settle()
        #expect(rig.delivery.texts == ["Un texte corrigé "])
    }
    @Test func noteModeSavesInSelectedFolderInsteadOfExternalField() async throws {
        let rig = try Rig()
        try rig.model.library.createFolder("École")
        let folder = rig.model.library.selectedFolder
        rig.model.begin(destination: .note); await rig.settle()
        rig.speech.onText?("Une idée à garder")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts.isEmpty)
        #expect(rig.model.library.notes.map(\.text) == ["Une idée à garder."])
        #expect(rig.model.library.notes.first?.folderId == folder)
        #expect(!rig.panel.visible)
    }
    @Test func editingDuringCleanupRequiresReviewInsteadOfDeliveringStaleText() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Première version")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        rig.model.edit("Ma version corrigée")
        rig.cleaner.resolve("Ancienne version nettoyée"); await rig.settle()
        #expect(rig.delivery.texts.isEmpty)
        #expect(rig.model.draft.text == "Ma version corrigée")
        #expect(rig.model.state == .review && rig.panel.visible)
    }
    @Test func additionalOptionWhileCleanupRunsDoesNotStartAnotherCapture() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Une seule dictée")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        rig.model.toggle()
        #expect(rig.model.state == .cleaning)
        rig.cleaner.resolve("Une seule dictée."); await rig.settle()
        #expect(rig.delivery.texts == ["Une seule dictée. "])
    }
    @Test func secondOptionTapInsertsFinalTranscriptExactlyOnce() async throws {
        let rig = try Rig()
        rig.model.toggle()
        await rig.settle()
        #expect(rig.model.state == .listening)
        rig.speech.onText?("Bonjour provisoire")
        rig.model.toggle()
        #expect(rig.speech.stops == 1)
        #expect(rig.delivery.texts.isEmpty)
        rig.speech.onText?("Bonjour le monde.")
        rig.speech.onFinish?(nil)
        await rig.settle()
        #expect(rig.delivery.texts == ["Bonjour le monde. "])
        #expect(!rig.panel.visible)
        #expect(rig.model.library.loadDraft()?.text.isEmpty == true)
        #expect(rig.model.library.notes.map(\.text) == ["Bonjour le monde."])
        rig.speech.onFinish?(nil)
        await rig.settle()
        #expect(rig.delivery.texts.count == 1)
    }
    @Test func manualCorrectionsAreDeliveredWithNewlyDictatedWords() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Bonjour dicta")
        rig.model.edit("Bonjour DictAI")
        rig.model.toggle()
        rig.speech.onText?("Bonjour dicta et suite")
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Bonjour DictAI et suite "])
    }
    @Test func pauseResumeAndOptionWorkInAllThreePresentations() async throws {
        for mode in [DictationPresentation.compact, .editor, .window] {
            let rig = try Rig()
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("Première partie.")
            if mode == .editor { rig.model.showDraft() }
            if mode == .window { rig.model.showWorkspace() }
            rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.model.state == .paused && rig.model.presentation == mode)
            #expect(rig.model.draft.text == "Première partie.")
            rig.model.resumeListening(); await rig.settle()
            rig.speech.onText?("Deuxième partie.")
            #expect(rig.model.draft.text == "Première partie. Deuxième partie.")
            rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.delivery.texts == ["Première partie. Deuxième partie. "])
            #expect(rig.delivery.captures == 1)
        }
    }
    @Test func automaticInsertionWaitsForCleanupAndAppliesVocabulary() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        try rig.model.library.addVocabulary("dicta => DictAI")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("bonjour dicta")
        rig.model.toggle(); rig.speech.onFinish?(nil)
        await rig.settle()
        #expect(rig.cleaner.inputs == ["bonjour DictAI"])
        #expect(rig.delivery.texts.isEmpty)
        rig.cleaner.resolve("Bonjour DictAI.")
        await rig.settle()
        #expect(rig.delivery.texts == ["Bonjour DictAI. "])
    }
    @Test func cancellationWhileCleanupRunsNeverInsertsLateResult() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Un brouillon")
        rig.model.toggle(); rig.speech.onFinish?(nil)
        await rig.settle()
        rig.model.closeOverlay()
        rig.cleaner.resolve("Résultat tardif")
        await rig.settle()
        #expect(rig.delivery.texts.isEmpty)
        #expect(rig.model.draft.text == "Un brouillon")
    }
    @Test func failedInsertionKeepsEditableRecoverableText() async throws {
        let rig = try Rig(); rig.delivery.failure = true
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Texte à récupérer")
        rig.model.toggle(); rig.speech.onFinish?(nil)
        await rig.settle()
        #expect(rig.model.state == .review)
        #expect(rig.panel.visible)
        #expect(!rig.model.message.isEmpty)
        #expect(rig.model.library.loadDraft()?.text == "Texte à récupérer.")
    }
    @Test func emptyRecognitionDoesNotInsertTrailingSpace() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("  \n")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts.isEmpty)
    }
    @Test func speechFailureDoesNotAutomaticallyDeliverPartialResult() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Résultat incomplet")
        rig.model.toggle(); rig.speech.onFinish?("Microphone débranché"); await rig.settle()
        #expect(rig.delivery.texts.isEmpty)
        #expect(rig.model.draft.text == "Résultat incomplet")
    }
}

@MainActor private final class Rig {
    let speech = ControlledSpeech()
    let delivery = RecordedDelivery()
    let panel = RecordedPanel()
    let cleaner = ControlledCleaner()
    let punctuator = ControlledPunctuator()
    let model: AppModel
    init(punctuation: Bool = false, timeout: Duration = .seconds(25), localModel: Bool = false) throws {
        let panel = self.panel
        punctuator.available = punctuation
        model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-flow-" + UUID().uuidString), speech: speech, insertion: delivery, cleaner: cleaner, punctuator: localModel ? LiveDictationPunctuator() : punctuator, punctuationTimeout: timeout, makeOverlay: { _ in panel })
    }
    func settle() async { for _ in 0..<30 { await Task.yield() } }
}
@MainActor private final class ControlledPunctuator: DictationPunctuating {
    var available = false
    var inputs: [String] = []
    var completion: CheckedContinuation<String?, Error>?
    func process(_ text: String, protectedTerms: [String]) async throws -> String? {
        inputs.append(text)
        return try await withCheckedThrowingContinuation { completion = $0 }
    }
    func resolve(_ text: String?) { completion?.resume(returning: text); completion = nil }
}
@MainActor private final class ControlledSpeech: SpeechCapturing {
    var onText: ((String) -> Void)?
    var onLevel: ((Float) -> Void)?
    var onFinish: ((String?) -> Void)?
    var stops = 0
    var starts = 0
    func start(language: String, vocabulary: [String]) async throws { starts += 1 }
    func stop() { stops += 1 }
    func cancel() {}
}
@MainActor private final class RecordedDelivery: TextDelivering {
    var available = true
    var issue: String?
    var targetName = "Éditeur de test"
    var texts: [String] = []
    var failure = false
    var captures = 0
    var currentField = "Champ d’origine"
    var selectedField = ""
    var fields: [String: String] = [:]
    func capture() { captures += 1; selectedField = available ? currentField : "" }
    func prepareForInsertion() { selectedField = currentField }
    func insert(_ text: String) async throws -> String {
        if failure || selectedField.isEmpty { throw DataError.invalid("Champ fermé, copiez le brouillon.") }
        texts.append(text); fields[selectedField, default: ""] += text; return "Texte inséré"
    }
    func copy(_ text: String) {}
}
@MainActor private final class RecordedPanel: OverlayPresenting {
    var visible = false
    func show(focus: Bool) { visible = true }
    func hide() { visible = false }
}
@MainActor private final class ControlledCleaner: DictationCleaning {
    var inputs: [String] = []
    var completion: CheckedContinuation<String, Error>?
    func process(_ text: String, preferences: [String: String], vocabulary: [String]) async throws -> String {
        inputs.append(text)
        return try await withCheckedThrowingContinuation { completion = $0 }
    }
    func resolve(_ text: String) { completion?.resume(returning: text); completion = nil }
}
