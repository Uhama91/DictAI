import Foundation
import Testing
@testable import DictAICore

struct TranscriptFollowTests {
    @Test func lastInteractionRestartsTheEightSecondGracePeriod() {
        var policy = TranscriptFollowPolicy()
        policy.interact(at: 10)
        let resumed1 = policy.resumeIfReady(at: 17.99, listening: true, interacting: false)
        #expect(!resumed1)
        policy.interact(at: 17)
        let resumed2 = policy.resumeIfReady(at: 24.99, listening: true, interacting: false)
        #expect(!resumed2)
        let resumed3 = policy.resumeIfReady(at: 25, listening: true, interacting: false)
        #expect(resumed3)
        #expect(policy.following)
    }
    @Test func draggingCompositionAndPauseNeverTriggerAnAutomaticReturn() {
        var policy = TranscriptFollowPolicy()
        policy.interact(at: 0)
        let resumed4 = policy.resumeIfReady(at: 100, listening: true, interacting: true)
        #expect(!resumed4)
        let resumed5 = policy.resumeIfReady(at: 100, listening: false, interacting: false)
        #expect(!resumed5)
        #expect(!policy.following)
    }
    @Test func manualModeWaitsUntilTheUserChoosesFollow() {
        var policy = TranscriptFollowPolicy(delay: 0)
        policy.interact(at: 0)
        let resumed6 = policy.resumeIfReady(at: 100, listening: true, interacting: false)
        #expect(!resumed6)
        policy.follow()
        #expect(policy.following)
    }
    @Test func appendingSpeechDoesNotMoveTheCaretOrSelection() {
        let patch = TextMutation(from: "Bonjour 🌿 ici", to: "Bonjour 🌿 ici et la suite.")
        let selection = NSRange(location: 8, length: 2)
        #expect(patch.rebasing(selection) == selection)
        let caret = NSRange(location: "Bonjour 🌿 ici".utf16.count, length: 0)
        #expect(patch.rebasing(caret) == caret)
    }
    @Test func unicodeReplacementAndSelectionOffsetsStayValid() {
        let old = "A 👩🏽‍💻 écrit e\u{301}."
        let new = "Avant A 👩🏽‍💻 écrit e\u{301}."
        let patch = TextMutation(from: old, to: new)
        #expect(patch.applying(to: old) == new)
        #expect(patch.rebasing(NSRange(location: 2, length: 7)) == NSRange(location: 8, length: 7))
        let removed = TextMutation(from: old, to: "A.")
        #expect(NSMaxRange(removed.rebasing(NSRange(location: 2, length: 8))) <= 2)
    }
    @Test func editingAnOlderSnapshotRetainsNewlyArrivedWords() {
        #expect(TextMutation.mergingEdit(from: "Bonjour Paul", to: "Bonjour Pauline", into: "Bonjour Paul et bienvenue.") == "Bonjour Pauline et bienvenue.")
        #expect(TextMutation.mergingEdit(from: "Bonjour Paul", to: "Salut Paul", into: "Bonjour Paul et bienvenue.") == "Salut Paul et bienvenue.")
    }
    @Test func namesQuotesAndParagraphsAreNotFlattened() {
        #expect(SpeechTextJoin.join("Nous suivons", "De Gaulle.") == "Nous suivons De Gaulle.")
        #expect(SpeechTextJoin.join("Une phrase.\n", "Et la suivante.") == "Une phrase.\nEt la suivante.")
        #expect(SpeechTextJoin.join("Il explique :", "Parce que cela fonctionne.") == "Il explique : Parce que cela fonctionne.")
    }
}
