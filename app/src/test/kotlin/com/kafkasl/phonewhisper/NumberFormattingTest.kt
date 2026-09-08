package com.kafkasl.phonewhisper

import com.ibm.icu.text.MessageFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormattingTest {
    private fun engine(language: DictationLanguage): NumberFormattingEngine {
        val format = MessageFormat("{0,spellout}", if (language == DictationLanguage.FRENCH) Locale.FRANCE else Locale.US)
        return NumberFormattingEngine(language) { format.format(arrayOf(it)) }
    }
    private val fr = engine(DictationLanguage.FRENCH)
    private val en = engine(DictationLanguage.ENGLISH)

    @Test fun unchangedKeepsEveryCharacterWithoutInitializingAndroidIcu() {
        val text = "  vingt trois élèves, 2,50 €\ncode 0123  "
        assertEquals(text, NumberFormatting.apply(text, DictationLanguage.FRENCH, NumberStyle.UNCHANGED))
    }
    @Test fun frenchCommonCardinalsAndDecimalsBecomeDigits() {
        assertEquals("Il y a 23 élèves et 2000 livres.", fr.apply("Il y a vingt trois élèves et deux mille livres.", NumberStyle.DIGITS))
        assertEquals("2,5 litres et -3,05 degrés", fr.apply("deux virgule cinq litres et moins trois virgule zéro cinq degrés", NumberStyle.DIGITS))
        assertEquals("81 euros, 91 centimes et 200 points", fr.apply("quatre-vingt-un euros, quatre vingt onze centimes et deux cents points", NumberStyle.DIGITS))
        assertEquals("21 élèves", fr.apply("vingt et une élèves", NumberStyle.DIGITS))
    }
    @Test fun englishCommonCardinalsAndDecimalsBecomeDigits() {
        assertEquals("We have 23 pupils and 2305 books.", en.apply("We have twenty three pupils and two thousand three hundred and five books.", NumberStyle.DIGITS))
        assertEquals("2.5 litres and -3.05 degrees", en.apply("two point five litres and minus three point zero five degrees", NumberStyle.DIGITS))
        assertEquals("1000001 items", en.apply("one million and one items", NumberStyle.DIGITS))
    }
    @Test fun digitsBecomeLocalWordsWithoutChangingDecimalPrecision() {
        assertEquals("vingt-trois élèves et deux mille livres", fr.apply("23 élèves et 2 000 livres", NumberStyle.WORDS))
        assertEquals("deux virgule zéro cinq zéro litres", fr.apply("2,050 litres", NumberStyle.WORDS))
        assertEquals("twenty-three pupils and two thousand books", en.apply("23 pupils and 2000 books", NumberStyle.WORDS))
        assertEquals("minus three point zero five degrees", en.apply("-3.05 degrees", NumberStyle.WORDS))
    }
    @Test fun articlesOrdinalsNamesAndWordFragmentsArePreserved() {
        val french = "Un élève, une classe, premier, deuxième, quelqu’un, quatre-quarts, un livre neuf, les Deux Alpes."
        assertEquals(french, fr.apply(french, NumberStyle.DIGITS))
        val english = "A pupil, first, second, someone, no one, one another, Seven Sisters."
        assertEquals(english, en.apply(english, NumberStyle.DIGITS))
        assertEquals("9 élèves", fr.apply("neuf élèves", NumberStyle.DIGITS))
    }
    @Test fun numericIdentifiersPhonesUrlsAndDatesArePreserved() {
        val text = "code 1234 ; AB-23 ; version 2.3.4 ; 06 12 34 56 78 ; +33612345678 ; 0123 ; 08/09/2026 ; 14:30 ; https://exemple.fr/23 ; user23@example.com ; 23e"
        assertEquals(text, fr.apply(text, NumberStyle.WORDS))
        assertEquals(text, en.apply(text, NumberStyle.WORDS))
        val words = "code vingt trois ; téléphone zéro six douze trente quatre cinquante six soixante dix huit ; https://exemple.fr/deux"
        assertEquals(words, fr.apply(words, NumberStyle.DIGITS))
    }
    @Test fun invalidOrUnsupportedNumberPhrasesAreNotPartiallyConverted() {
        assertEquals("one two three four", en.apply("one two three four", NumberStyle.DIGITS))
        assertEquals("deux mille mille", fr.apply("deux mille mille", NumberStyle.DIGITS))
        assertEquals("trois virgule", fr.apply("trois virgule", NumberStyle.DIGITS))
        assertEquals("mille milliards", fr.apply("mille milliards", NumberStyle.DIGITS))
        assertEquals("2.5 litres", fr.apply("2.5 litres", NumberStyle.WORDS))
        assertEquals("2,500 items", en.apply("2,500 items", NumberStyle.WORDS))
    }
    @Test fun punctuationAndNonNumberSpacingAreUnchanged() {
        assertEquals("  23 élèves !\nEt 2 chats.  ", fr.apply("  Vingt-trois élèves !\nEt deux chats.  ", NumberStyle.DIGITS))
        assertEquals("2 et Paul", fr.apply("deux et Paul", NumberStyle.DIGITS))
    }
    @Test fun commonCardinalsRoundTripInBothLanguages() {
        val values = listOf(0L, 2, 9, 17, 21, 71, 80, 81, 99, 100, 101, 200, 201, 999, 1000, 1001, 2026, 999999, 1000000, 2345678)
        for ((language, formatter) in listOf(DictationLanguage.FRENCH to fr, DictationLanguage.ENGLISH to en)) {
            val format = MessageFormat("{0,spellout}", if (language == DictationLanguage.FRENCH) Locale.FRANCE else Locale.US)
            for (value in values) {
                val spoken = format.format(arrayOf(value))
                val noun = if (language == DictationLanguage.FRENCH) " élèves" else " pupils"
                assertEquals("$language: $spoken", "$value$noun", formatter.apply(spoken + noun, NumberStyle.DIGITS))
            }
        }
    }
    @Test fun simpleMessageDoesNotInitializeIcuAndVocabularyIsProtected() {
        val cold = NumberFormattingEngine(DictationLanguage.FRENCH) { error("ICU called") }
        assertEquals("OK, ça marche", cold.apply("OK, ça marche", NumberStyle.DIGITS))
        assertEquals("septante trois élèves", fr.apply("septante trois élèves", NumberStyle.DIGITS))
        assertEquals("Studio 23 et deux élèves", fr.apply("Studio 23 et 2 élèves", NumberStyle.WORDS, listOf("Studio 23")))
    }
}
