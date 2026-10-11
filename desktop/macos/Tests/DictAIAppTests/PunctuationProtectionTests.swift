import Testing
import DictAICore
@testable import DictAI

struct PunctuationProtectionTests {
    @Test func aNameTagCannotProtectTheFollowingPronounOrSentenceBoundary() {
        let source = "je parle avec Maëlys nous partons"
        let terms = PunctuationProtection.terms(in: source, vocabulary: ["maelys => Maëlys"])
        #expect(PunctuationRestoration.accept("Je parle avec maëlys. Nous partons.", for: source, protectedTerms: terms) == "Je parle avec Maëlys. Nous partons.")
    }
    @Test func uncommonNamesAndPlacesAreProtectedButArticlesAndPrepositionsCanChangeCase() {
        let source = "je viens de La Rochelle avec Maëlys dans Le navigateur"
        let terms = PunctuationProtection.terms(in: source, vocabulary: [])
        #expect(PunctuationRestoration.accept("Je viens de la rochelle avec maëlys dans le navigateur.", for: source, protectedTerms: terms) == "Je viens de La Rochelle avec Maëlys dans le navigateur.")
    }
}
