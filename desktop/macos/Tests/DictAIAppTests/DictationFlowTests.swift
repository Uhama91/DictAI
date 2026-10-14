import Foundation
import Testing
import DictAICore
@testable import DictAI

@MainActor struct DictationFlowTests {
    @Test func switchingToAnAccountRestoresAnInsertableDraftWithoutAnotherEdit() async throws {
        let rig = try Rig()
        let user = UUID().uuidString.lowercased()
        try rig.model.library.switchScope(user)
        var draft = DictationDraft()
        draft.receive("Un brouillon à insérer.")
        try rig.model.library.saveDraft(draft)
        try rig.model.library.switchScope(nil)
        let session = AccountSession(userId: user, email: "test@example.invalid", accessToken: "test-only",
                                     refreshToken: "test-only", expiresAt: .distantFuture)
        try rig.model.account.onChange?(session)
        #expect(rig.model.state == .review)
        rig.model.showDraft()
        rig.model.insert(); await rig.settle()
        #expect(rig.delivery.texts == ["Un brouillon à insérer. "])
        #expect(rig.model.delivered && !rig.panel.visible)
    }
    @Test func speechContinuesInTheQuickEditorAfterEditingAndARecognitionRevision() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Je fais une inserts")
        rig.model.showDraft()
        rig.model.edit("Je fais une insertion")
        // A silence may revise the previous words without ending the recognition request.
        rig.speech.onText?("Je fais une insertion")
        rig.speech.onText?("Je fais une insertion et je continue")
        #expect(rig.model.state == .listening && rig.model.presentation == .editor)
        #expect(rig.model.draft.text == "Je fais une insertion et je continue")
        #expect(rig.model.library.loadDraft()?.text == "Je fais une insertion et je continue")
        #expect(rig.speech.starts == 1 && rig.speech.stops == 0)
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Je fais une insertion et je continue "])
        #expect(rig.model.delivered && !rig.model.panelVisible)
    }
    @Test func insertionAfterEditingReleasesKeyboardFocusBeforeResolvingTheDestination() async throws {
        for presentation in [DictationPresentation.editor, .window] {
            let rig = try Rig()
            rig.delivery.currentField = "Antigravity"
            rig.delivery.resolveField = { [weak rig] in
                guard let rig else { return "" }
                return rig.panel.ownsKeyboardFocus ? "" : rig.delivery.currentField
            }
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("Une transcription à corriger.")
            if presentation == .editor { rig.model.showDraft() }
            else { rig.model.showWorkspace() }
            rig.model.edit("Une transcription corrigée.")
            rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.delivery.fields == ["Antigravity": "Une transcription corrigée. "])
            #expect(rig.model.delivered && rig.model.state == .idle)
            #expect(!rig.model.panelVisible && !rig.panel.visible)
            #expect(rig.model.library.history.entries.map(\.text) == ["Une transcription corrigée."])
        }
    }
    @Test func learnedNamesAreProtectedDuringLiveUpdatesAndRestarts() async throws {
        let rig = try Rig()
        try rig.model.library.addVocabulary("Ensuite")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Je travaille avec Ensuite")
        #expect(rig.model.draft.text == "Je travaille avec Ensuite")
        rig.speech.onFinish?(nil); await rig.settle()
        rig.speech.onText?("Ensuite fonctionne")
        #expect(rig.model.draft.text == "Je travaille avec Ensuite Ensuite fonctionne")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Je travaille avec Ensuite Ensuite fonctionne. "])
    }
    @Test func thinkingPauseAndCumulativeUpdatesStayInTheSameSentenceUntilDelivery() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Je prends une pause Tu vois")
        #expect(rig.model.draft.text == "Je prends une pause tu vois")
        rig.speech.onFinish?(nil); await rig.settle()
        rig.speech.onText?("Je suis toujours dans la même phrase")
        #expect(rig.model.draft.text == "Je prends une pause tu vois je suis toujours dans la même phrase")
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Voilà pourquoi c’est complètement Incohérent")
        #expect(rig.model.draft.text == "Je prends une pause tu vois je suis toujours dans la même phrase voilà pourquoi c’est complètement incohérent")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        let expected = "Je prends une pause tu vois je suis toujours dans la même phrase voilà pourquoi c’est complètement incohérent."
        #expect(rig.delivery.texts == [expected + " "])
        #expect(rig.model.library.history.entries.map(\.text) == [expected])
        #expect(rig.model.state == .idle && !rig.model.panelVisible)
    }
    @Test func failedInsertionIsRecoverableOnceAndRetryUpdatesHumanEdits() async throws {
        let rig = try Rig(); rig.delivery.failure = true
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Texte à récupérer")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.library.history.entries.map(\.text) == ["Texte à récupérer."])
        rig.model.edit("Texte corrigé à récupérer.")
        rig.delivery.failure = false
        rig.model.insert(); await rig.settle()
        #expect(rig.model.library.history.entries.map(\.text) == ["Texte corrigé à récupérer."])
        #expect(rig.model.library.notes.isEmpty)
    }
    @Test func manualTextAndCancelledDictationDoNotPopulateHistory() async throws {
        let typed = try Rig()
        typed.model.edit("Texte saisi au clavier")
        typed.model.saveNote()
        #expect(typed.model.library.history.entries.isEmpty)
        #expect(typed.model.library.notes.map(\.text) == ["Texte saisi au clavier"])
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Résultat intermédiaire")
        rig.model.shortcut.onGesture?(.triple)
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.library.history.entries.isEmpty)
    }
    @Test func retiringLocalPunctuationRestoresImmediateInsertion() async throws {
        let rig = try Rig()
        let legacy = rig.model.library.root.appendingPathComponent("mac-preferences.json")
        let data = Data("{\"intelligentPunctuation\":true}".utf8)
        try data.write(to: legacy)
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("les enfants rangent leur cahier")
        rig.model.toggle(); rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.delivery.texts == ["Les enfants rangent leur cahier. "])
        #expect(rig.model.state == .idle && rig.model.delivered)
        #expect(try Data(contentsOf: legacy) == data)
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
        #expect(rig.model.library.history.entries.map(\.text) == ["Je parle sur l'application dans le navigateur avec la fenêtre ouverte."])
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
            #expect(rig.model.library.history.entries.map(\.text) == ["Une dictée terminée."])
            rig.speech.onText?("Dernier résultat en retard")
            rig.speech.onFinish?(nil); await rig.settle()
            rig.model.showDraft()
            #expect(!rig.panel.visible && rig.model.draft.text.isEmpty)
            rig.model.shortcut.onGesture?(.single); await rig.settle()
            rig.speech.onText?("La dictée suivante.")
            #expect(rig.model.draft.text == "La dictée suivante.")
            #expect(rig.model.library.notes.isEmpty && rig.model.library.history.entries.count == 1)
            #expect(rig.delivery.captures == 2)
        }
    }
    @Test func aHistoryFailureAfterInsertionKeepsRecoveryWithoutSendingTwice() async throws {
        let rig = try Rig()
        try FileManager.default.createDirectory(at: rig.model.library.directory.appendingPathComponent("dictation-history.json"), withIntermediateDirectories: false)
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
    @Test func singleOptionResumesPausedDraftThenInsertsOnceIntoCurrentField() async throws {
        for mode in [DictationPresentation.compact, .editor, .window] {
            let rig = try Rig()
            rig.model.shortcut.onGesture?(.single); await rig.settle()
            rig.speech.onText?("Première partie.")
            if mode == .editor { rig.model.showDraft() }
            if mode == .window { rig.model.showWorkspace() }
            rig.model.shortcut.onGesture?(.double)
            rig.speech.onFinish?(nil); await rig.settle()
            rig.model.shortcut.onGesture?(.single); await rig.settle()
            #expect(rig.model.state == .listening && rig.model.presentation == mode)
            #expect(rig.model.draft.text == "Première partie.")
            #expect(rig.delivery.texts.isEmpty && rig.cleaner.inputs.isEmpty)
            #expect(rig.speech.starts == 2 && rig.delivery.captures == 1)
            rig.speech.onText?("Et la suite.")
            rig.delivery.currentField = "Champ actuel"
            rig.model.shortcut.onGesture?(.single)
            rig.speech.onFinish?(nil); await rig.settle()
            rig.speech.onFinish?(nil); await rig.settle()
            #expect(rig.delivery.texts == ["Première partie. Et la suite. "])
            #expect(rig.delivery.fields["Champ actuel"] == "Première partie. Et la suite. ")
            #expect(rig.delivery.fields["Champ d’origine"] == nil)
            #expect(rig.model.state == .idle && !rig.model.panelVisible)
            #expect(rig.model.library.history.entries.map(\.text) == ["Première partie. Et la suite."])
        }
    }
    @Test func correctionWaitsUntilOptionFinishesTheResumedDictation() async throws {
        let rig = try Rig()
        rig.model.library.setPreference("formatting_engine", "cloud")
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("texte à corriger")
        rig.model.shortcut.onGesture?(.double)
        rig.speech.onFinish?(nil); await rig.settle()
        rig.model.shortcut.onGesture?(.single); await rig.settle()
        #expect(rig.model.state == .listening)
        #expect(rig.cleaner.inputs.isEmpty && rig.delivery.texts.isEmpty)
        rig.speech.onText?("et continuer")
        rig.model.shortcut.onGesture?(.single)
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.cleaner.inputs == ["texte à corriger et continuer"])
        #expect(rig.delivery.texts.isEmpty)
        rig.cleaner.resolve("Texte à corriger et continuer."); await rig.settle()
        #expect(rig.delivery.texts == ["Texte à corriger et continuer. "])
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
    @Test func singleGestureResumesAPauseThatIsStillFinalizing() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Phrase finale")
        rig.model.shortcut.onGesture?(.double)
        rig.model.shortcut.onGesture?(.single)
        rig.model.shortcut.onGesture?(.single)
        rig.speech.onText?("Phrase finale.")
        rig.speech.onFinish?(nil); await rig.settle()
        #expect(rig.model.state == .listening)
        #expect(rig.model.draft.text == "Phrase finale.")
        #expect(rig.delivery.texts.isEmpty && rig.speech.starts == 2)
    }
    @Test func singleOptionResumesAnEmptyPausedDictation() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.model.pauseListening(); rig.speech.onFinish?(nil); await rig.settle()
        rig.model.shortcut.onGesture?(.single); await rig.settle()
        #expect(rig.model.state == .listening && rig.model.panelVisible)
        #expect(rig.speech.starts == 2 && rig.delivery.texts.isEmpty)
    }
    @Test func tripleOptionCancelsPausedAndPendingResumeWithoutInserting() async throws {
        for finalizing in [false, true] {
            let rig = try Rig()
            rig.model.toggle(); await rig.settle()
            rig.speech.onText?("Brouillon annulé.")
            rig.model.shortcut.onGesture?(.double)
            if finalizing { rig.model.shortcut.onGesture?(.single) }
            else { rig.speech.onFinish?(nil); await rig.settle() }
            rig.model.shortcut.onGesture?(.triple)
            rig.speech.onFinish?(nil); await rig.settle()
            #expect(!rig.model.panelVisible && rig.speech.starts == 1)
            #expect(rig.model.library.loadDraft()?.text == "Brouillon annulé.")
            #expect(rig.delivery.texts.isEmpty && rig.model.library.history.entries.isEmpty)
        }
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
        #expect(rig.model.library.history.entries.map(\.text) == ["Ma note continue."])
    }
    @Test func optionAfterClosingStartsFreshAndPreservesPreviousDraftInHistory() async throws {
        let rig = try Rig()
        rig.model.toggle(); await rig.settle()
        rig.speech.onText?("Ancienne dictée")
        rig.model.closeOverlay()
        rig.model.toggle(); await rig.settle()
        #expect(rig.model.draft.text.isEmpty)
        rig.speech.onText?("Nouvelle dictée")
        #expect(rig.model.draft.text == "Nouvelle dictée")
        #expect(rig.model.library.history.entries.map(\.text) == ["Ancienne dictée"])
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
        #expect(rig.model.library.history.entries.map(\.text) == ["Dictée précédente"])
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
        #expect(rig.model.library.history.entries.map(\.text) == ["Bonjour le monde."])
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
    let model: AppModel
    init() throws {
        let panel = self.panel
        model = try AppModel(root: FileManager.default.temporaryDirectory.appendingPathComponent("dictai-flow-" + UUID().uuidString), speech: speech, insertion: delivery, cleaner: cleaner, makeOverlay: { _ in panel })
    }
    func settle() async { for _ in 0..<30 { await Task.yield() } }
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
    var resolveField: (() -> String)?
    func capture() { captures += 1; selectedField = available ? currentField : "" }
    func prepareForInsertion() { selectedField = resolveField?() ?? currentField }
    func insert(_ text: String) async throws -> String {
        if failure || selectedField.isEmpty { throw DataError.invalid("Champ fermé, copiez le brouillon.") }
        texts.append(text); fields[selectedField, default: ""] += text; return "Texte inséré"
    }
    func copy(_ text: String) {}
}
@MainActor private final class RecordedPanel: OverlayPresenting {
    var visible = false
    var ownsKeyboardFocus = false
    func show(focus: Bool) { visible = true; if focus { ownsKeyboardFocus = true } }
    func hide() { visible = false; ownsKeyboardFocus = false }
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
