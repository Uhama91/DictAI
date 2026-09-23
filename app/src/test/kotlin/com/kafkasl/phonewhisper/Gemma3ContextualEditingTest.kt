package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Gemma3ContextualEditingTest {
    private fun accept(
        source: String,
        candidate: String,
        protectedTerms: List<String> = emptyList(),
    ): String? = gemma3FinalLocalFormatRequest(
        source = source,
        language = DictationLanguage.FRENCH,
        protectedTerms = protectedTerms,
    ).acceptOutput(candidate)

    @Test
    fun acceptsOnlyContextualRepairsProposedByTheGemma3Candidate() {
        val cases = listOf(
            "Il faut qu’il est accès au dossier." to "Il faut qu’il ait accès au dossier.",
            "Je me permet de rappeler la consigne." to "Je me permets de rappeler la consigne.",
            "Tu dois peut-être au courant." to "Tu dois peut-être être au courant.",
            "des éléments de cet akaby" to "des éléments de cet acabit",
            "je range la carte et euh mais je garde la boussole" to
                "je range la carte, mais je garde la boussole",
            "nous cherchons la sortie, nous cherchons la sortie près du pont" to
                "nous cherchons la sortie près du pont",
            "On on répartit les dossiers" to "On répartit les dossiers",
            "Le rendez-vous est mardi, non, mercredi à 14h." to
                "Le rendez-vous est mercredi à 14h.",
            "Le rendez-vous est le 12, non, le 13 mai." to
                "Le rendez-vous est le 13 mai.",
            "Le rendez-vous est le 22 mars pardon le 23 mars." to
                "Le rendez-vous est le 23 mars.",
        )

        cases.forEach { (source, candidate) ->
            assertEquals(source, candidate, accept(source, candidate))
        }
    }

    @Test
    fun keepsAnUnchangedCandidateEvenWhenItsGrammarCouldBeRepaired() {
        val source = "Il faut qu’il est accès au dossier."

        assertEquals(source, accept(source, source))
    }

    @Test
    fun refusesReflexiveAndEmphaticRepetitionAndHalfAnAbandonedConjunction() {
        assertNull(accept("Nous nous lavons les mains.", "Nous lavons les mains."))
        assertNull(accept("C’est très très utile.", "C’est très utile."))
        assertNull(accept("On, on gardera les clés.", "On gardera les clés."))
        assertNull(accept("Il faut qu’il est prudent.", "Il faut qu’il ait prudent."))
        assertNull(accept(
            "Nous calculons 2+3, nous calculons 2-3.",
            "Nous calculons 2+3.",
        ))
        assertNull(accept(
            "Nous aimons vraiment la sortie, nous aimons vraiment la sortie près du pont.",
            "Nous aimons vraiment la sortie près du pont.",
        ))
        assertNull(accept(
            "Nous pensons 😊 à ce sujet, nous pensons 😢 à ce sujet.",
            "Nous pensons 😊 à ce sujet.",
        ))
        assertNull(accept(
            "je range la carte et euh mais je garde la boussole",
            "je range la carte et mais je garde la boussole",
        ))
    }

    @Test
    fun refusesDeletionOfAnEmbeddedClauseThatOnlyContainsARepeatedSubject() {
        assertNull(accept(
            "Il croit que nous pensons que nous pensons cela.",
            "Il croit que nous pensons cela.",
        ))
    }

    @Test
    fun refusesDeletionWhenARepeatedGroupEndsWithASuspendedConnector() {
        assertNull(accept(
            "Nous savons que nous savons que tout est prêt.",
            "Nous savons que tout est prêt.",
        ))
    }

    @Test
    fun keepsOrdinaryHesitationCleanupBeforeAStillValidPreposition() {
        val cases = listOf(
            "Je préfère attendre avant euh de partir." to "Je préfère attendre avant de partir.",
            "Je réserve pour euh après Noël." to "Je réserve pour après Noël.",
        )
        cases.forEach { (source, candidate) -> assertEquals(candidate, accept(source, candidate)) }
    }

    @Test
    fun refusesUnpunctuatedNonAndDateGapsThatCouldHideOtherInformation() {
        assertNull(accept("La séance est lundi, non mardi.", "La séance est mardi."))
        assertNull(accept("La séance est lundi 😊 non 😊 mardi.", "La séance est mardi."))
        assertNull(accept("La séance est 😊 lundi, non, mardi.", "La séance est mardi."))
        assertNull(accept("La séance est lundi, non, mardi.", "La séance est 😊 mardi."))
    }

    @Test
    fun refusesUncuedDatesExtraChangesProtectedTextAndMultipleSpecialSpans() {
        assertNull(accept("La réunion est mardi enfin mercredi.", "La réunion est mercredi."))
        assertNull(accept("Non, je confirme mercredi.", "Je confirme mercredi."))
        assertNull(accept(
            "Il faut qu’il est accès au dossier.",
            "Il faut qu’il ait accès au dossier demain.",
        ))
        assertNull(accept(
            "Il a dit « je me permet de rappeler la consigne ». ",
            "Il a dit « je me permets de rappeler la consigne ». ",
        ))
        assertNull(accept(
            "Je me permet de rappeler la consigne à Maëlys.",
            "Je me permets de rappeler la consigne à Maëlle.",
            protectedTerms = listOf("Maëlys"),
        ))
        assertNull(accept(
            "Il faut qu’il est accès au dossier et je me permet de rappeler la consigne.",
            "Il faut qu’il ait accès au dossier et je me permets de rappeler la consigne.",
        ))
        assertNull(accept(
            "Le rendez-vous est le 12 non le 13 mai pour 23 personnes.",
            "Le rendez-vous est le 13 mai pour 32 personnes.",
        ))
    }
}
