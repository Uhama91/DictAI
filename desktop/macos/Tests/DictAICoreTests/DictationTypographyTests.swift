import Testing
@testable import DictAICore

struct DictationTypographyTests {
    @Test func everySentenceStartIsCapitalizedWithoutTreatingAbbreviationsAsSentences() {
        #expect(DictationTypography.finalized("bonjour. nous reprenons demain ! puis nous terminons", language: "fr") == "Bonjour. Nous reprenons demain ! Puis nous terminons.")
        #expect(DictationTypography.finalized("je parle à M. Dupont et au Dr. martin", language: "fr") == "Je parle à M. Dupont et au Dr. martin.")
        #expect(DictationTypography.finalized("  une phrase. la suite", language: "fr") == "  Une phrase. La suite.")
    }
    @Test func aClosingQuoteDoesNotSuppressTheFinalPeriodOfProse() {
        #expect(DictationTypography.finalized("il a dit « bonjour »", language: "fr") == "Il a dit « bonjour ».")
        #expect(DictationTypography.finalized("il a demandé « pourquoi ? »", language: "fr") == "Il a demandé « pourquoi ? »")
    }
    @Test func explicitFrenchQuestionsMatchTheAndroidCleanupRule() {
        #expect(DictationTypography.finalized("est-ce que tu viens demain", language: "fr") == "Est-ce que tu viens demain ?")
        #expect(DictationTypography.finalized("nous avons fini. peux-tu ranger le cahier.", language: "fr") == "Nous avons fini. Peux-tu ranger le cahier ?")
        #expect(DictationTypography.finalized("je me demande si tu viens", language: "fr") == "Je me demande si tu viens.")
        #expect(DictationTypography.finalized("est-ce que tu viens...", language: "fr") == "Est-ce que tu viens...")
    }
    @Test func literalNamesAndFilenamesKeepTheirSpelling() {
        #expect(DictationTypography.finalized("eBay fonctionne", language: "fr") == "eBay fonctionne.")
        #expect(DictationTypography.finalized("macOS fonctionne", language: "fr") == "macOS fonctionne.")
        #expect(DictationTypography.finalized("rapport.pdf", language: "fr") == "rapport.pdf")
    }
    @Test func englishAndLiteralAddressesArePreserved() {
        #expect(DictationTypography.finalized("I work with La fenêtre", language: "en") == "I work with La fenêtre")
        #expect(DictationTypography.finalized("https://exemple.fr", language: "fr") == "https://exemple.fr")
        #expect(DictationTypography.finalized("nom@exemple.fr", language: "fr") == "nom@exemple.fr")
        #expect(DictationTypography.finalized("12345", language: "fr") == "12345")
    }
    @Test func existingPunctuationAndParagraphsArePreserved() {
        #expect(DictationTypography.finalized("une phrase.\n\nLa suite ?\n", language: "fr") == "Une phrase.\n\nLa suite ?\n")
        #expect(DictationTypography.finalized("une phrase\n", language: "fr") == "Une phrase.\n")
        #expect(DictationTypography.live("Je viens de La Rochelle, puis je travaille sur Le navigateur.", language: "fr") == "Je viens de La Rochelle, puis je travaille sur le navigateur.")
    }
}
