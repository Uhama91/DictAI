import Testing
@testable import DictAICore

struct DictationTypographyTests {
    @Test func commonFrenchContinuationsWorkWithoutGrammarTags() {
        for (input, expected) in [
            ("C’est Complètement incohérent", "C’est complètement incohérent"),
            ("Je fais une pause, Ensuite je continue", "Je fais une pause, ensuite je continue"),
            ("Je pense J’ai encore quelque chose à dire", "Je pense j’ai encore quelque chose à dire"),
            ("Je regarde L’application", "Je regarde l’application"),
            ("Je pense C’est cohérent", "Je pense c’est cohérent"),
            ("Je pense Qu’il faut continuer", "Je pense qu’il faut continuer")
        ] {
            #expect(SpeechCapitalization.normalize(input, wordTags: { _ in [:] }) == expected)
        }
        #expect(SpeechCapitalization.normalize("Je parle à D’Artagnan et L’Oréal", wordTags: { _ in [:] }) == "Je parle à D’Artagnan et L’Oréal")
        #expect(SpeechCapitalization.normalize("Je cite Ensuite et Complètement", protectedTerms: ["Ensuite", "Complètement"], wordTags: { _ in [:] }) == "Je cite Ensuite et Complètement")
        #expect(SpeechCapitalization.normalize("Je visite Paris. Ensuite je continue", wordTags: { _ in [:] }) == "Je visite Paris. Ensuite je continue")
    }

    @Test func vocabularyChangesNeverReuseAnOldProtectionDecision() {
        let text = "Je dis Tu vois"
        #expect(DictationTypography.live(text, language: "fr") == "Je dis tu vois")
        #expect(DictationTypography.live(text, language: "fr", protectedTerms: ["Tu"]) == text)
        #expect(DictationTypography.live(text, language: "fr") == "Je dis tu vois")
    }
    @Test func cumulativeHypothesesRepairPauseCapitalsWithoutAddingPunctuation() {
        #expect(DictationTypography.live("Je prends une pause Tu vois c’est complètement Incohérent Voilà pourquoi", language: "fr") == "Je prends une pause tu vois c’est complètement incohérent voilà pourquoi")
        #expect(DictationTypography.live("Je réfléchis Tu vois. Tu comprends ? Je continue", language: "fr") == "Je réfléchis tu vois. Tu comprends ? Je continue")
    }
    @Test func liveRepairsPreserveNamesAcronymsQuotesAndTechnicalText() {
        let text = "Je parle à Paul et Paris avec DictAI API et Le Havre, je lis « Tu vois » et Tu.pdf"
        #expect(DictationTypography.live(text, language: "fr") == text)
    }
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
