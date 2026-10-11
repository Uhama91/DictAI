import Foundation
import Testing
@testable import DictAICore

struct SpeechContinuityTests {
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
        #expect(draft.text == "Bonjour DictAI et la suite Un autre paragraphe.")
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
