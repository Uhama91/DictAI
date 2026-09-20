package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaListConnectorRestorationTest {
    private fun request(
        source: String,
        kind: LocalLayoutKind,
        validation: LocalFormatValidation = LocalFormatValidation.GEMMA_PROJECTION,
        protectedTerms: List<String> = emptyList(),
    ) = LocalFormatRequest(
        text = source,
        instructions = kind.name,
        language = "fr",
        protectedTerms = protectedTerms,
        layoutKind = kind,
        validation = validation,
    )

    private val deliverySource =
        "La livraison comprend deux cartons de livres quatre pochettes avec les étiquettes et un rouleau de papier les cartons restent dans le hall"

    private val deliveryCandidate = """
        La livraison comprend :
        • deux cartons de livres ;
        • quatre pochettes avec les étiquettes ;
        • un rouleau de papier.

        Les cartons restent dans le hall.
    """.trimIndent()

    @Test
    fun editingRouteRestoresTheOmittedEtInTheRealEp35List() {
        val source =
            "La livraison comprend deux cartons de livres quatre pochettes avec les étiquettes et un rouleau de papier les cartons doivent rester dans le hall jusqu’au contrôle du matin le transporteur appellera avant de repartir et la réception signera le bordereau La personne de permanence vérifiera aussi que les quatre pochettes restent fermées jusqu’à l’inventaire de jeudi matin prévu par le service"
        val candidate = """
            La livraison comprend :
            • deux cartons de livres ;
            • quatre pochettes avec les étiquettes ;
            • un rouleau de papier.
            Les cartons doivent rester dans le hall jusqu’au contrôle du matin. Le transporteur appellera avant de repartir et la réception signera le bordereau. La personne de permanence vérifiera aussi que les quatre pochettes restent fermées jusqu’à l’inventaire de jeudi matin prévu par le service.
        """.trimIndent()
        val expected = candidate.replace("• un rouleau", "• et un rouleau")

        assertEquals(
            expected,
            request(source, LocalLayoutKind.LIST, LocalFormatValidation.GEMMA_EDITING).acceptOutput(candidate),
        )
    }

    @Test
    fun restoresTheSameLocalListInCorrectedListAndEmailModes() {
        val expected = deliveryCandidate.replace("• un rouleau", "• et un rouleau")
            .replace("\nLes cartons", "\nles cartons")
        assertEquals(expected, request(deliverySource, LocalLayoutKind.TEXT).acceptOutput(deliveryCandidate))
        assertEquals(expected, request(deliverySource, LocalLayoutKind.LIST).acceptOutput(deliveryCandidate))

        val emailSource =
            "Bonjour Claire voici la livraison deux cartons de livres quatre pochettes avec les étiquettes et un rouleau de papier les cartons restent dans le hall merci Marie"
        val emailCandidate = """
            Bonjour Claire,

            Voici la livraison :
            • deux cartons de livres ;
            • quatre pochettes avec les étiquettes ;
            • un rouleau de papier.

            Les cartons restent dans le hall.

            Merci,
            Marie.
        """.trimIndent()
        assertEquals(
            emailCandidate.replace("• un rouleau", "• et un rouleau")
                .replace("Voici", "voici")
                .replace("\nLes cartons", "\nles cartons")
                .replace("\nMerci,", "\nmerci,"),
            request(emailSource, LocalLayoutKind.EMAIL).acceptOutput(emailCandidate),
        )
    }

    @Test
    fun keepsAnEtThatIsAlreadyAtTheNextBullet() {
        val source = "Ranger le sac et fermer la boîte"
        val output = "• Ranger le sac\n• et fermer la boîte"
        assertEquals(output, request(source, LocalLayoutKind.LIST).acceptOutput(output))
    }

    @Test
    fun rejectsTwoOmissionsAndDoesNotRestoreOrdinaryProse() {
        val source = "Ranger le sac et fermer la boîte"
        assertNull(request(source, LocalLayoutKind.LIST).acceptOutput("• Ranger le\n• boîte"))
        assertNull(request(source, LocalLayoutKind.TEXT).acceptOutput("Ranger le sac\nfermer la boîte"))
    }

    @Test
    fun rejectsAmbiguousEtAlignment() {
        val source = "Ranger le sac et et fermer la boîte"
        val candidate = "• Ranger le sac et\n• fermer la boîte"
        assertNull(request(source, LocalLayoutKind.LIST).acceptOutput(candidate))
    }

    @Test
    fun rejectsOuNearTheOmittedConnector() {
        val source = "Choisir le sac et ou la boîte"
        val candidate = "Choisir :\n• le sac\n• ou la boîte"
        assertNull(request(source, LocalLayoutKind.TEXT).acceptOutput(candidate))
    }

    @Test
    fun rejectsQuotedOrProtectedEt() {
        val quotedSource = "La livraison comprend le sac et « et » la boîte"
        val quotedCandidate = "La livraison comprend :\n• le sac et\n• « » la boîte"
        assertNull(request(quotedSource, LocalLayoutKind.LIST).acceptOutput(quotedCandidate))

        val protectedSource = "La livraison comprend le sac et la boîte"
        val protectedCandidate = "La livraison comprend :\n• le sac\n• la boîte"
        assertNull(
            request(protectedSource, LocalLayoutKind.TEXT, protectedTerms = listOf("et"))
                .acceptOutput(protectedCandidate),
        )
    }

    @Test
    fun rejectsNumericOperatorLinesAndLostNegationOrName() {
        val numericSource = "Prévoir le matériel Codes 3 et 1"
        assertNull(request(numericSource, LocalLayoutKind.LIST).acceptOutput("• Prévoir le matériel\nCodes 3\n- 1"))

        val source = "La livraison comprend Zoé et ne pas déplacer la boîte"
        assertNull(request(source, LocalLayoutKind.LIST).acceptOutput("• La livraison comprend Zoé\n• déplacer la boîte"))
    }
}
