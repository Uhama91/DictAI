import Testing
@testable import DictAICore

struct InteractionTests {
    @Test func testHumanEditSurvivesLateRecognition() {
        var draft = DictationDraft()
        draft.receive("bonjour le monde")
        draft.edit("Bonjour, Madame.")
        draft.receive("bonjour le monde comment allez vous")
        #expect((draft.text) == ("Bonjour, Madame. comment allez vous"))
        #expect((draft.recognized) == ("bonjour le monde comment allez vous"))
    }
    @Test func testVocabularyUsesWholeWordsAndDoesNotCascadeRules() throws {
        let result = Vocabulary.apply("un chat et un château, DictAI", lines: ["chat => chien", "chien => loup", "DictAI"])
        #expect((result) == ("un chien et un château, DictAI"))
    }
    @Test func testVocabularyIdentityMatchesAndroidNormalization() {
        #expect((Vocabulary.key("  ÉCOLE\u{00a0}Verte => X")) == (Vocabulary.key("école verte => Y")))
        #expect((Vocabulary.key("école verte")) != (Vocabulary.key("école verte => Y")))
    }
    @Test func testLearningOffersOnlySmallReplacementAndNeverLearnsAutomatically() {
        #expect((Vocabulary.suggestion(before: "Je parle de dicta ici.", after: "Je parle de DictAI ici.")) == ("dicta => DictAI"))
        #expect((Vocabulary.suggestion(before: "", after: "un long texte")) == nil)
        #expect((Vocabulary.suggestion(before: "Bonjour.", after: "Bonjour.")) == nil)
    }
}
