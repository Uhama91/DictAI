package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class LayoutGroupingExpectationTest {
    private fun request(source: String, kind: LocalLayoutKind) =
        LocalFormatRequest(source, "format", "French", layoutKind = kind)

    @Test fun faithfulShoppingMonoblockFailsWhileSixItemsPass() {
        val req = request("du lait des oranges du pain du chocolat, des roses du riz", LocalLayoutKind.LIST)
        val rule = LayoutGroupingExpectation(setOf(2, 4, 6, 8, 10), allowed = setOf(2, 4, 6, 8, 10))
        assertFalse(rule.evaluate(req, "• ${req.text}")!!.passed)
        assertFalse(rule.evaluate(req, "• du lait des oranges du pain du chocolat,\n• des roses du riz")!!.passed)
        assertTrue(rule.evaluate(req, "• du lait\n• des oranges\n• du pain\n• du chocolat,\n• des roses\n• du riz")!!.passed)
        assertNull(rule.evaluate(req, "• du lait\n• des oranges"))
        assertNull(rule.evaluate(req, null))
    }

    @Test fun modifierCannotBecomeAnIndependentItem() {
        val req = request("du lait de la ferme du pain", LocalLayoutKind.LIST)
        val rule = LayoutGroupingExpectation(setOf(5), allowed = setOf(5))
        assertTrue(rule.evaluate(req, "• du lait de la ferme\n• du pain")!!.passed)
        assertFalse(rule.evaluate(req, "• du lait\n• de la ferme\n• du pain")!!.passed)
        assertFalse(rule.evaluate(req, "• du lait de la ferme du pain")!!.passed)
    }

    @Test fun actionListKeepsEachActionAndItsQuantityTogether() {
        val first = "Demain, appeler Maëlys pour confirmer les 23 élèves,"
        val second = "imprimer 2 fiches par élève"
        val third = "et apporter les cahiers bleus."
        val req = request("$first $second $third", LocalLayoutKind.LIST)
        val rule = LayoutGroupingExpectation(setOf(8, 13), allowed = setOf(8, 13))
        val correct = "• $first\n• $second\n• $third"
        assertTrue(rule.evaluate(req, correct)!!.passed)
        assertEquals(setOf(8, 13), rule.evaluate(req, "• ${req.text}")!!.missing)
        assertEquals(setOf(8), rule.evaluate(req, "• $first $second\n• $third")!!.missing)
        val splitQuantity = rule.evaluate(req, correct.replace("23 élèves,", "23\n• élèves,"))!!
        assertFalse(splitQuantity.passed)
        assertTrue(splitQuantity.missing.isEmpty())
        assertEquals(setOf(7), splitQuantity.unexpected)
    }

    @Test fun englishEmailAllowsSentenceParagraphsButKeepsNegationTogether() {
        val first = "do not send the documents today."
        val second = "Wait until Friday and contact Maëlys first."
        val req = LocalFormatRequest("Hello Karim, $first $second Thank you.", "format", "English",
            layoutKind = LocalLayoutKind.EMAIL)
        val rule = LayoutGroupingExpectation(setOf(2, 15), allowed = setOf(2, 8, 15))
        val correct = "Hello Karim,\n\n$first $second\n\nThank you."
        assertTrue(rule.evaluate(req, correct)!!.passed)
        assertTrue(rule.evaluate(req, "Hello Karim,\n\n$first\n\n$second\n\nThank you.")!!.passed)
        assertEquals(setOf(2, 15), rule.evaluate(req, req.text)!!.missing)
        val splitNegation = rule.evaluate(req, correct.replace("do not send", "do not\n\nsend"))!!
        assertFalse(splitNegation.passed)
        assertTrue(splitNegation.missing.isEmpty())
        assertEquals(setOf(4), splitNegation.unexpected)
        assertFalse(rule.evaluate(req, correct.replace("Hello Karim,", "Hello\n\nKarim,"))!!.passed)
    }

    @Test fun shortAcknowledgmentStaysInOnePart() {
        val req = request("OK, ça marche.", LocalLayoutKind.EMAIL)
        val rule = LayoutGroupingExpectation(emptySet(), allowed = emptySet())
        assertTrue(rule.evaluate(req, req.text)!!.passed)
        val split = rule.evaluate(req, "OK,\n\nça marche.")!!
        assertFalse(split.passed)
        assertTrue(split.missing.isEmpty())
        assertEquals(setOf(1), split.unexpected)
        assertNull(rule.evaluate(req, "OK, ça marche.\n\nCordialement."))
    }

    @Test fun emailChecksGreetingClosingAndIntactSignatureWithoutScoringBodyStyle() {
        val body = "voici un premier test qui vise à vérifier que le post-traitement sur le mail fonctionne comme il faut"
        val req = request("Bonjour $body cordialement M. l’utilisateur.", LocalLayoutKind.EMAIL)
        val rule = LayoutGroupingExpectation(setOf(1, 19), forbidden = setOf(21))
        assertTrue(rule.evaluate(req, "Bonjour\n\n$body\n\ncordialement M. l’utilisateur.")!!.passed)
        assertTrue(rule.evaluate(req, "Bonjour\n\n$body\n\ncordialement\n\nM. l’utilisateur.")!!.passed)
        assertTrue(rule.evaluate(req, "Bonjour\n\n${body.replace("test qui", "test\n\nqui")}\n\ncordialement\n\nM. l’utilisateur.")!!.passed)
        assertFalse(rule.evaluate(req, "Bonjour\n\n$body cordialement M. l’utilisateur.")!!.passed)
        assertFalse(rule.evaluate(req, "Bonjour\n\n$body\n\ncordialement M.\n\nl’utilisateur.")!!.passed)
        assertFalse(rule.evaluate(req, req.text)!!.passed)
    }
}
