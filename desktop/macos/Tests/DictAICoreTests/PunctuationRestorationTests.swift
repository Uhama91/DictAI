import Testing
@testable import DictAICore

struct PunctuationRestorationTests {
    @Test func theModelSeesPlaceholdersInsteadOfNamesAndTechnicalLiterals() throws {
        let source = "je viens de La Rochelle avec Maëlys ouvre rapport_v2.pdf et contacte lea@example.fr"
        let masked = PunctuationMask(source, protectedTerms: ["La Rochelle", "Maëlys"])
        for literal in ["La Rochelle", "Maëlys", "rapport_v2.pdf", "lea@example.fr"] { #expect(!masked.text.contains(literal)) }
        let candidate = masked.text.replacingOccurrences(of: "je viens", with: "Je viens") + "."
        let restored = try #require(masked.restore(candidate))
        #expect(restored == "Je viens de La Rochelle avec Maëlys ouvre rapport_v2.pdf et contacte lea@example.fr.")
        #expect(PunctuationRestoration.accept(restored, for: source, protectedTerms: ["La Rochelle", "Maëlys"]) == restored)
    }
    @Test func missingOrDuplicatedPlaceholdersCannotLoseOrRepeatProtectedText() {
        let masked = PunctuationMask("bonjour DictAI")
        #expect(masked.restore("Bonjour.") == nil)
        #expect(masked.restore(masked.text + " " + masked.text) == nil)
    }
    @Test func sentenceBoundariesAndCommonWordCaseCanChangeWithoutChangingWords() {
        let input = "je travaille dans Le navigateur la dictée est terminée je passe à la suite"
        let output = "Je travaille dans le navigateur. La dictée est terminée. Je passe à la suite."
        #expect(PunctuationRestoration.accept(output, for: input) == output)
    }
    @Test func changedWordsNamesNumbersNegationsAndMarkdownAreRejected() {
        let cases = [
            ("je ne veux pas envoyer les 23 dossiers", "Je veux envoyer les 23 dossiers."),
            ("je range les 23 dossiers", "Je range les 32 dossiers."),
            ("Maëlys arrive demain", "Maëlie arrive demain."),
            ("ouvre puis ferme", "Ferme puis ouvre."),
            ("je travaille", "Je suis désolé, je ne peux pas travailler."),
            ("je travaille", "**Je travaille.**"),
            ("ouvre rapport_v2.pdf", "Ouvre rapport_v3.pdf."),
            ("ouvre le fichier", "Ouvrez le fichier.")
        ]
        for (input, output) in cases { #expect(PunctuationRestoration.accept(output, for: input) == nil) }
    }
    @Test func protectedNamesQuotesAndIdentifiersKeepTheirExactSpelling() {
        #expect(PunctuationRestoration.accept("Je suis avec maëlys dans la rochelle et Dictai.", for: "je suis avec Maëlys dans La Rochelle et DictAI", protectedTerms: ["Maëlys", "La Rochelle"]) == "Je suis avec Maëlys dans La Rochelle et DictAI.")
        #expect(PunctuationRestoration.accept("Il dit « Pas maintenant. » puis continue.", for: "il dit « pas maintenant » puis continue") == "Il dit « pas maintenant » puis continue.")
        #expect(PunctuationRestoration.accept("Ouvre https://exemple.fr/a?x=3 avec userid et lea@example.fr.", for: "ouvre https://exemple.fr/a?x=3 avec userID et lea@example.fr") == "Ouvre https://exemple.fr/a?x=3 avec userID et lea@example.fr.")
    }
    @Test func paragraphBreaksCannotBeRemovedOrAdded() {
        #expect(PunctuationRestoration.accept("Première idée.\n\nLa suite.", for: "première idée\n\nla suite") == "Première idée.\n\nLa suite.")
        #expect(PunctuationRestoration.accept("Première idée. La suite.", for: "première idée\n\nla suite") == nil)
        #expect(PunctuationRestoration.accept("Première idée.\nLa suite.", for: "première idée la suite") == nil)
    }
}
