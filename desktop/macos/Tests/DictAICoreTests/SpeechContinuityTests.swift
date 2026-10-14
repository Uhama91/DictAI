import Foundation
import Testing
@testable import DictAICore

struct SpeechContinuityTests {
    @Test func revisionsBeforeTheProtectedBoundaryDoNotDuplicateRecognizedWords() {
        var draft = DictationDraft()
        draft.receive("Je fais une insertion")
        draft.edit("Je fais une insertion corrigée")
        draft.receive("Je fais une très bonne insertion et je continue")
        #expect(draft.text == "Je fais une insertion corrigée et je continue")
    }
    @Test func aSingleCorrectedWordStillAllowsTheNextWords() {
        var draft = DictationDraft()
        draft.receive("dicta")
        draft.edit("DictAI")
        draft.receive("DictAI et la suite")
        #expect(draft.text == "DictAI et la suite")
    }
    @Test func revisingARecognizedWordDoesNotStopSpeechAfterAnEdit() {
        var draft = DictationDraft()
        draft.receive("je fais une inserts")
        draft.edit("Je fais une insertion")
        draft.receive("je fais une insertion et je continue")
        #expect(draft.text == "Je fais une insertion et je continue")
        draft.receive("je fais une insertion et je continue à parler")
        #expect(draft.text == "Je fais une insertion et je continue à parler")
    }
    @Test func revisedRecognitionDoesNotRestoreWordsDeletedByTheUser() {
        var draft = DictationDraft()
        draft.receive("je corrige cette faute")
        draft.edit("Je corrige")
        draft.receive("je corrige cette erreur et je continue")
        #expect(draft.text == "Je corrige et je continue")
        draft.receive("je corrige cette erreur et je continue")
        #expect(draft.text == "Je corrige et je continue")
    }
    @Test func recognitionCanResegmentWordsBeforeAppendingNewSpeech() {
        var draft = DictationDraft()
        draft.receive("J’ai quatre vingt euros")
        draft.edit("J’ai quatre-vingts euros")
        draft.receive("J’ai quatre-vingts euros et je poursuis")
        #expect(draft.text == "J’ai quatre-vingts euros et je poursuis")
    }
    @Test func anUnrelatedReplacementHypothesisCannotRestoreAnErasedDraft() {
        var draft = DictationDraft()
        draft.receive("Bonjour le monde.")
        draft.edit("")
        draft.receive("Je préfère recommencer autrement.")
        #expect(draft.text.isEmpty)
    }
    @Test func aRevisedHypothesisWithNoNewSpeechKeepsManualCorrections() {
        var draft = DictationDraft()
        draft.receive("je fais une inserts")
        draft.edit("Une insertion corrigée par moi")
        draft.receive("je fais une insertion")
        #expect(draft.text == "Une insertion corrigée par moi")
        draft.receive("je fais une insertion puis je continue")
        #expect(draft.text == "Une insertion corrigée par moi puis je continue")
    }
    @Test func vocabularyProtectionAlsoAppliesWithinTheSpeechBuffer() {
        var buffer = SpeechTranscriptBuffer(protectedTerms: ["Ensuite"])
        buffer.receive("Je travaille avec", start: 0, end: 2, complete: true)
        #expect(buffer.receive("Ensuite", start: 4, end: 5) == "Je travaille avec Ensuite")
    }
    @Test func frenchRepairsDoNotChangeEnglishCapitalization() {
        var buffer = SpeechTranscriptBuffer(language: "en")
        buffer.receive("I work with", start: 0, end: 2, complete: true)
        #expect(buffer.receive("Car Next", start: 4, end: 5) == "I work with Car Next")
        var draft = DictationDraft()
        draft.receive("I work with", language: "en"); draft.beginSegment()
        draft.receive("Car Next", language: "en")
        #expect(draft.text == "I work with Car Next")
    }
    @Test func newSpeechAfterAManualCorrectionUsesTheEditedSentenceBoundary() {
        var draft = DictationDraft()
        draft.receive("je réfléchi")
        draft.edit("Je réfléchis")
        draft.receive("je réfléchi Tu vois")
        #expect(draft.text == "Je réfléchis tu vois")
        draft.edit("Je réfléchis. Tu vois")
        draft.receive("je réfléchi Tu vois Je continue")
        #expect(draft.text == "Je réfléchis. Tu vois je continue")
    }
    @Test func reflectiveSilenceKeepsPronounsAndOrdinaryWordsLowercase() {
        let cases = [
            ("Je prends une pause", "Tu vois ce que je veux dire", "Je prends une pause tu vois ce que je veux dire"),
            ("Je réfléchis", "Je suis toujours dans la même phrase", "Je réfléchis je suis toujours dans la même phrase"),
            ("C’est", "Complètement incohérent", "C’est complètement incohérent"),
            ("C’est complètement", "Incohérent", "C’est complètement incohérent"),
            ("Je fais une pause", "Voilà pourquoi", "Je fais une pause voilà pourquoi"),
            ("Je fais une pause,", "Ensuite je continue", "Je fais une pause, ensuite je continue"),
            ("Je fais une pause ;", "Nous continuons", "Je fais une pause ; nous continuons"),
            ("Je pense", "J’ai encore quelque chose à dire", "Je pense j’ai encore quelque chose à dire")
        ]
        for (prefix, suffix, expected) in cases {
            var buffer = SpeechTranscriptBuffer()
            buffer.receive(prefix, start: 0, end: 2, complete: true)
            #expect(buffer.receive(suffix, start: 8, end: 10) == expected)
            #expect(buffer.receive(suffix + " maintenant", start: 8, end: 11) == expected + " maintenant")
            var draft = DictationDraft()
            draft.receive(prefix); draft.beginSegment(); draft.receive(suffix)
            #expect(draft.text == expected)
        }
    }
    @Test func aProperNameAfterAContinuationDoesNotCapitalizeThePronoun() {
        #expect(SpeechTextJoin.join("Je réfléchis", "Tu connais Paris") == "Je réfléchis tu connais Paris")
        #expect(SpeechTextJoin.join("Je réfléchis", "Avec DictAI") == "Je réfléchis avec DictAI")
    }
    @Test func frenchArticlesContinueAcrossRecognitionRestarts() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("Je regarde", start: 0, end: 2, complete: true)
        #expect(buffer.receive("L’application", start: 3, end: 4) == "Je regarde l’application")
        var draft = DictationDraft()
        draft.receive("Je regarde")
        draft.beginSegment()
        draft.receive("Le navigateur.")
        #expect(draft.text == "Je regarde le navigateur.")
    }
    @Test func manualCorrectionsPreserveParagraphsInNewSpeech() {
        var draft = DictationDraft()
        draft.receive("Préparer jeudi.")
        draft.edit("Préparer vendredi.")
        draft.receive("Préparer jeudi.\n\nRassembler les documents.")
        #expect(draft.text == "Préparer vendredi.\n\nRassembler les documents.")
    }
    @Test func silenceDoesNotCapitalizeAnUnfinishedFrenchSentence() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("je souhaite continuer", start: 0.2, end: 2, complete: true)
        #expect(buffer.receive("Parce que la phrase continue", start: 4, end: 6) == "je souhaite continuer parce que la phrase continue")
    }
    @Test func restartedRequestKeepsAContinuationLowercase() {
        var draft = DictationDraft()
        draft.receive("Il est possible")
        draft.beginSegment()
        draft.receive("De continuer à parler.")
        #expect(draft.text == "Il est possible de continuer à parler.")
    }
    @Test func namesAndRealSentenceStartsKeepTheirCapitals() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("Nous allons à", start: 0, end: 2, complete: true)
        #expect(buffer.receive("Paris.", start: 4, end: 5, complete: true) == "Nous allons à Paris.")
        #expect(buffer.receive("Nous repartons.", start: 6, end: 7) == "Nous allons à Paris. Nous repartons.")
    }
    @Test func silenceBoundaryDoesNotReplaceThePreviousSentence() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("Une première phrase.", start: 0.2, end: 2, complete: true)
        buffer.receive("")
        #expect(buffer.receive("Une seconde", start: 3, end: 4) == "Une première phrase. Une seconde")
        #expect(buffer.receive("Une seconde phrase.", start: 3, end: 5, complete: true) == "Une première phrase. Une seconde phrase.")
    }
    @Test func cumulativeResultsAndCorrectionsDoNotDuplicateSentences() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("bonjour", start: 0.2, end: 0.7)
        buffer.receive("Bonjour.", start: 0.2, end: 0.7, complete: true)
        #expect(buffer.receive("Bonjour.", start: 0.2, end: 0.7, complete: true) == "Bonjour.")
        #expect(buffer.receive("Bonjour. Voici la suite.", start: 0.2, end: 4) == "Bonjour. Voici la suite.")
    }
    @Test func repeatingTheSameWordLaterIsRealSpeech() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("Oui.", start: 1, end: 2, complete: true)
        #expect(buffer.receive("Oui.", start: 4, end: 5, complete: true) == "Oui. Oui.")
    }
    @Test func engineTimestampResetAfterCompletedUtteranceKeepsHistory() {
        var buffer = SpeechTranscriptBuffer()
        buffer.receive("Première idée.", complete: true)
        #expect(buffer.receive("Autre idée.") == "Première idée. Autre idée.")
    }
    @Test func newWordsContinueAfterManualCorrection() {
        var draft = DictationDraft()
        draft.receive("bonjour dicta")
        draft.edit("Bonjour DictAI")
        draft.receive("bonjour dicta et la suite")
        #expect(draft.text == "Bonjour DictAI et la suite")
        draft.receive("")
        #expect(draft.text == "Bonjour DictAI et la suite")
        draft.beginSegment(); draft.receive("Un autre paragraphe.")
        #expect(draft.text == "Bonjour DictAI et la suite un autre paragraphe.")
    }
    @Test func intentionalDeletionAndOldDraftDecodingRemainSupported() throws {
        var draft = try JSONDecoder().decode(DictationDraft.self, from: Data(#"{"text":"Ancien texte.","recognized":"Ancien texte.","edited":false}"#.utf8))
        draft.beginSegment(); draft.receive("Suite.")
        #expect(draft.text == "Ancien texte. Suite.")
        draft.edit(""); draft.receive("Suite.")
        #expect(draft.text.isEmpty)
    }
    @Test func delayedPunctuationSurvivesAWordCorrection() {
        var draft = DictationDraft()
        draft.receive("bonjour dicta")
        draft.edit("Bonjour DictAI")
        draft.receive("Bonjour dicta, voici la suite.")
        #expect(draft.text == "Bonjour DictAI, voici la suite.")
        draft.receive("Bonjour dicta, voici la suite.")
        #expect(draft.text == "Bonjour DictAI, voici la suite.")
    }
    @Test func finalPunctuationArrivesAfterAnEditWithoutNewWords() {
        var draft = DictationDraft()
        draft.receive("bonjour dicta")
        draft.edit("Bonjour DictAI\n")
        draft.receive("Bonjour dicta.")
        #expect(draft.text == "Bonjour DictAI.\n")
    }
    @Test func manualPunctuationAndDeletionsTakePriority() {
        var draft = DictationDraft()
        draft.receive("bonjour dicta.")
        draft.edit("Bonjour DictAI") // The user deliberately removed the period.
        draft.receive("Bonjour dicta!")
        #expect(draft.text == "Bonjour DictAI")
        draft.edit("Bonjour DictAI ?")
        draft.receive("Bonjour dicta.")
        #expect(draft.text == "Bonjour DictAI ?")
        draft.edit("")
        draft.receive("Bonjour dicta.")
        #expect(draft.text.isEmpty)
    }
}
