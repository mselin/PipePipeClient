package org.schabi.newpipe.enhancements.groupsort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.AEPFEL
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.AERGER
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.NIHON
import org.schabi.newpipe.enhancements.groupsort.GroupSortFixtures.quoted
import java.util.Locale
import kotlin.math.sign

/**
 * `GroupSorter.compareNames` (plan section 5), the name comparison of AC-5.
 *
 * Only the sign of the result is specified ("negative, 0 or positive"), so no test looks at
 * its size. Every expectation is a rule or example of spec AC-5, or follows from the algorithm
 * of plan section 5 by hand; the character codes that decide are given next to each case.
 */
class GroupSorterCompareNamesTest {

    /** [first] sorts before [second], asked in both argument orders. */
    private fun assertBefore(rule: String, first: String, second: String) {
        val forward = GroupSorter.compareNames(first, second)
        assertTrue(
            "AC-5 $rule: ${quoted(first)} must sort before ${quoted(second)}, " +
                "so compareNames(first, second) must be negative, but it was $forward",
            forward < 0
        )
        val backward = GroupSorter.compareNames(second, first)
        assertTrue(
            "AC-5 $rule: ${quoted(first)} must sort before ${quoted(second)}, " +
                "so compareNames(second, first) must be positive, but it was $backward",
            backward > 0
        )
    }

    /** [a] and [b] are equal names, asked in both argument orders. */
    private fun assertEqualNames(rule: String, a: String, b: String) {
        assertEquals(
            "AC-5 $rule: ${quoted(a)} and ${quoted(b)} are equal names, compareNames(a, b)",
            0,
            GroupSorter.compareNames(a, b)
        )
        assertEquals(
            "AC-5 $rule: ${quoted(a)} and ${quoted(b)} are equal names, compareNames(b, a)",
            0,
            GroupSorter.compareNames(b, a)
        )
    }

    /** Each name of [ascending] sorts before every name after it, not only before its neighbour. */
    private fun assertStrictlyAscending(rule: String, ascending: List<String>) {
        for (i in ascending.indices) {
            for (j in i + 1 until ascending.size) {
                assertBefore(rule, ascending[i], ascending[j])
            }
        }
    }

    /**
     * AC-5, the example chain of the spec, pairwise: "- Top" < "001 - First" < "Mix 10" <
     * "Mix 2" < "music" < "ZZZ - Last" < "ZZZZ - Last of last" < "Äpfel".
     */
    @Test
    fun ac5_exampleChain_everyNameSortsBeforeEveryLaterName() {
        assertStrictlyAscending("example chain", GroupSortFixtures.AC5_CHAIN)
    }

    /** AC-5: upper and lower case are ignored. */
    @Test
    fun ac5_caseIsIgnored() {
        assertEqualNames("case is ignored", "Music", "music")
        assertEqualNames("case is ignored", "music", "MUSIC")
        assertEqualNames("case is ignored", "MuSiC", "mUsIc")
        assertEqualNames("case is ignored", "ZZZ - Last", "zzz - last")
        // U+00C4 and U+00E4 are the same letter in two cases
        assertEqualNames("case is ignored", "\u00C4PFEL", "\u00E4pfel")

        // by raw code "B" (0x42) is before "a" (0x61) and "Z" (0x5A) before "a": case must not decide
        assertBefore("case is ignored", "apple", "Banana")
        assertBefore("case is ignored", "Apple", "banana")
        assertBefore("case is ignored", "apple", "Zebra")
        assertBefore("case is ignored", "mix", "MUSIC")
        assertBefore("case is ignored", "MIX", "music")
    }

    /** AC-5: whitespace around a name is ignored; whitespace inside a name is a character. */
    @Test
    fun ac5_surroundingWhitespaceIsIgnored() {
        assertEqualNames("surrounding whitespace is ignored", " MUSIC ", "music")
        assertEqualNames("surrounding whitespace is ignored", "   Mix 2", "Mix 2   ")
        assertEqualNames("surrounding whitespace is ignored", "\tmusic\n", "music")
        assertEqualNames("surrounding whitespace is ignored", "  ", "")

        // untrimmed, the leading space (0x20) would put these first
        assertBefore("surrounding whitespace is ignored", "a", "  b")
        assertBefore("surrounding whitespace is ignored", "Apple", " zebra")
        assertBefore("surrounding whitespace is ignored", "Mix 10", "   Mix 2")

        // the space inside "A Team" (0x20) is compared with "t" (0x74)
        assertBefore("whitespace inside a name is a character", "A Team", "ATeam")
    }

    /**
     * AC-5, "surrounding whitespace" as plan section 5 defines it: Kotlin `trim()`, which strips
     * every `Char.isWhitespace()` character. That includes the no-break space U+00A0 and the
     * ideographic space U+3000, which Java's `String.trim()` would leave in place.
     */
    @Test
    fun ac5_surroundingWhitespaceIncludesNoBreakAndIdeographicSpace() {
        assertEqualNames("Kotlin trim() strips U+00A0 and U+3000", "\u00A0Music\u3000", "music")
        // untrimmed, U+00A0 would sort after "z" (0x7A)
        assertBefore("Kotlin trim() strips U+00A0", "\u00A0Apple", "Zebra")
    }

    /** AC-5: "A name that is the beginning of another comes first." */
    @Test
    fun ac5_nameThatIsTheBeginningOfAnotherComesFirst() {
        assertBefore("a prefix comes first", "Mix", "Mix 2")
        assertBefore("a prefix comes first", "Mix 2", "Mix 20")
        assertBefore("a prefix comes first", "ZZZ", "ZZZZ")
        assertBefore("a prefix comes first", "001", "001 - First")
        assertBefore("a prefix comes first", "Zebra", "zebra 2")
        // a beginning after ignoring case and surrounding whitespace
        assertBefore("a prefix comes first", "music", "Music videos")
        assertBefore("a prefix comes first", " mix ", "Mix 2")
    }

    /** AC-5: "Numbers are not compared by value": the first differing digit decides. */
    @Test
    fun ac5_digitsCompareAsCharacters() {
        // "1" (0x31) is before "2" (0x32) and "9" (0x39), whatever number follows
        assertBefore("digits are characters, not numbers", "Mix 10", "Mix 2")
        assertBefore("digits are characters, not numbers", "10", "9")
        assertBefore("digits are characters, not numbers", "100", "20")
        assertBefore("digits are characters, not numbers", "Part 10", "Part 9")
        // leading zeros are characters too: "0" (0x30) is before "1" (0x31)
        assertBefore("digits are characters, not numbers", "001 - First", "01")
        assertBefore("digits are characters, not numbers", "02", "1")
    }

    /** AC-5: digits come before letters, in either case. */
    @Test
    fun ac5_digitsComeBeforeLetters() {
        assertBefore("digits before letters", "9", "a")
        assertBefore("digits before letters", "9", "A")
        assertBefore("digits before letters", "001 - First", "Mix 10")
        assertBefore("digits before letters", "2pac", "Abba")
        assertBefore("digits before letters", "Mix 2", "Mix a")
        assertBefore("digits before letters", "Mix 2", "Mix A")
    }

    /**
     * AC-5 (answer A4): space and the punctuation with codes below "0" come before digits:
     * space, then `! " # $ % & ' ( ) * + , - . /` (0x20-0x2F), then "0" (0x30).
     */
    @Test
    fun ac5_spaceAndPunctuationComeBeforeDigits() {
        val belowDigits = listOf('!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/')
        for (c in belowDigits) {
            assertBefore("'$c' sorts before digits", c + "x", "0x")
            assertBefore("'$c' sorts before letters", c + "x", "ax")
            assertBefore("space sorts before '$c'", "a b", "a" + c + "b")
        }
        assertBefore("space sorts before digits", "a b", "a0b")
        assertBefore("punctuation before digits", "- Top", "001 - First")
        assertBefore("space before punctuation", "A Team", "A-Team")
        assertBefore("punctuation before digits", "A-Team", "A0")
    }

    /**
     * AC-5 (answer A4): `: ; < = > ? @ [ \ ] ^ _` and the backtick sort after digits and before
     * letters. Letters are folded to lower case (0x61-0x7A), so `[ \ ] ^ _` and the backtick
     * (0x5B-0x60) are before "A" as well as before "a".
     */
    @Test
    fun ac5_symbolsBetweenDigitsAndLetters() {
        val between = listOf(':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_', '`')
        for (c in between) {
            assertBefore("digits sort before '$c'", "9x", c + "x")
            assertBefore("'$c' sorts before lower-case letters", c + "x", "ax")
            assertBefore("'$c' sorts before upper-case letters", c + "x", "Ax")
        }
        assertBefore("symbols between digits and letters", "001", ":x")
        assertBefore("symbols between digits and letters", "[old]", "_misc")
        assertBefore("symbols between digits and letters", "_misc", "A Team")
    }

    /**
     * AC-5 (answer A4): `{ | } ~` (0x7B-0x7E) sort after "z" and "Z" but before letters outside
     * A-Z such as "Ä" (folded to U+00E4).
     */
    @Test
    fun ac5_symbolsAfterZAndBeforeLettersOutsideAToZ() {
        for (c in listOf('{', '|', '}', '~')) {
            assertBefore("lower-case letters sort before '$c'", "zx", c + "x")
            assertBefore("upper-case letters sort before '$c'", "Zx", c + "x")
            assertBefore("'$c' sorts before letters outside A-Z", c + "x", "\u00C4x")
            assertBefore("'$c' sorts before letters outside A-Z", c + "x", "\u00E4x")
        }
        assertBefore("symbols after z", "music", "{x}")
        assertBefore("symbols after z", "{x}", "~last")
        assertBefore("symbols before letters outside A-Z", "~last", AEPFEL)
    }

    /** AC-5: letters outside A-Z (such as "Ä") come after all of A-Z, in either case. */
    @Test
    fun ac5_lettersOutsideAToZComeAfterZ() {
        assertBefore("\"\u00C4\" after \"Z\"", "Zebra", AEPFEL)
        assertBefore("\"\u00C4\" after \"z\"", "zebra 2", AEPFEL)
        assertBefore("\"\u00C4\" after \"Z\"", "ZZZZ - Last of last", AEPFEL)
        assertBefore("\"\u00E4\" after \"Z\"", "ZZZ", AERGER)
        assertBefore("\"\u00C4\" after \"A\"", "Apfel", AEPFEL)
        assertBefore("\"\u00C4\" after \"A\"", "Azzz", "\u00C4a")
        // U+00C4 and U+00E4 are one letter; then "p" (0x70) is before "r" (0x72)
        assertEqualNames("\"\u00C4\" equals \"\u00E4\"", AEPFEL, "\u00E4pfel")
        assertBefore("letters outside A-Z by code", AEPFEL, AERGER)
        // "Ö" folds to U+00F6, "É" to U+00E9, Cyrillic "М" to U+043C, "日" is U+65E5
        assertBefore("\"\u00D6\" after \"Z\"", "Zoo", "\u00D6l")
        assertBefore("\"\u00C9\" after \"Z\"", "Zebra", "\u00C9clair")
        assertBefore("letters outside A-Z by code", AEPFEL, "\u00D6l")
        assertBefore("letters outside A-Z by code", AERGER, "\u041C\u0443\u0437\u044B\u043A\u0430")
        assertBefore("letters outside A-Z by code", AERGER, NIHON)
        assertBefore("letters outside A-Z by code", "\u041C\u0443\u0437\u044B\u043A\u0430", NIHON)
    }

    /**
     * AC-5: "no language-specific alphabet rules apply". A German dictionary puts "Äpfel" with
     * "A" and a Spanish one puts "ñ" between "n" and "o"; here both come after "z".
     */
    @Test
    fun ac5_noLanguageSpecificAlphabetRules() {
        assertBefore("no language-specific alphabet", "Banane", AEPFEL)
        assertBefore("no language-specific alphabet", "Azur", AEPFEL)
        assertBefore("no language-specific alphabet", "oso", "\u00F1u")
        assertBefore("no language-specific alphabet", "zorro", "\u00F1u")
    }

    /**
     * AC-5: "no language-specific alphabet rules apply", so the result must not depend on the
     * language of the device. Plan section 5 folds case per character with no `Locale`. With
     * Turkish or Azerbaijani as default locale, a locale-sensitive case conversion turns "I"
     * into a dotless "ı" (U+0131) and "i" into "İ" (U+0130) and breaks these cases.
     */
    @Test
    fun ac5_resultDoesNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("tr-TR", "az-AZ", "lt-LT", "de-DE", "ja-JP")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val rule = "default locale $tag must not matter"
                assertEqualNames(rule, "MIX", "mix")
                assertEqualNames(rule, "INDIE", "indie")
                assertEqualNames(rule, "Titles", "TITLES")
                // "i" (0x69) is before "j" (0x6A) and "u" (0x75), also when written "I"
                assertBefore(rule, "Indie", "jazz")
                assertBefore(rule, "MIX 2", "music")
                assertStrictlyAscending(rule, GroupSortFixtures.AC5_CHAIN)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    /**
     * AC-5 "ignoring upper/lower case", as plan section 5 defines it:
     * `fold(c) = Character.toLowerCase(Character.toUpperCase(c))`, character by character.
     * Upper case first makes the Greek final sigma "ς" (U+03C2) equal to "Σ" (U+03A3) and to
     * "σ" (U+03C3); lower-casing alone would keep "ς" and "σ" apart.
     */
    @Test
    fun ac5_caseIsFoldedToUpperThenLowerPerCharacter() {
        val upper = "\u039F\u0394\u039F\u03A3" // ΟΔΟΣ
        val lowerWithFinalSigma = "\u03BF\u03B4\u03BF\u03C2" // οδος
        val lowerWithPlainSigma = "\u03BF\u03B4\u03BF\u03C3" // οδοσ
        assertEqualNames("fold = toLowerCase(toUpperCase(c))", upper, lowerWithFinalSigma)
        assertEqualNames("fold = toLowerCase(toUpperCase(c))", upper, lowerWithPlainSigma)
        assertEqualNames("fold = toLowerCase(toUpperCase(c))", lowerWithFinalSigma, lowerWithPlainSigma)
    }

    /**
     * AC-5 "character by character by character code", as plan section 5 defines it: per UTF-16
     * unit, not per code point, so that the test JVM and the device agree. An emoji is two
     * units starting with 0xD83C or 0xD83D, which is below the fullwidth letters (U+FF21 and
     * up), although its code point (U+1F3B5) is above them. `compareTo(ignoreCase = true)`
     * compares by code point on a JDK and gets the first case wrong.
     *
     * Assumption (question Q5, tests -> plan, open): spec AC-5 says "by character code", which
     * for an emoji can also be read as its code point; then the first two assertions would be
     * the other way round. This test follows plan section 5 as written. The last three
     * assertions hold under both readings, and no other test depends on the answer.
     */
    @Test
    fun ac5_charactersOutsideTheBmpCompareByUtf16Unit() {
        val notes = "\uD83C\uDFB5 Music" // U+1F3B5 MUSICAL NOTE, then " Music"
        val grin = "\uD83D\uDE00" // U+1F600 GRINNING FACE
        val fullwidthMv = "\uFF2D\uFF36" // fullwidth "MV", folded to U+FF4D U+FF56
        assertBefore("UTF-16 unit 0xD83C is before 0xFF4D", notes, fullwidthMv)
        assertBefore("UTF-16 unit 0xD83D is before 0xFF4D", grin, fullwidthMv)
        // these agree with code point order as well
        assertBefore("UTF-16 unit 0x65E5 is before 0xD83C", NIHON, notes)
        assertBefore("UTF-16 unit 0xD83C is before 0xD83D", notes, grin)
        assertBefore("letters sort before an emoji", "music", notes)
    }

    /**
     * AC-5 through fixture F4 of plan section 5 (answer A4), pairwise: "- Top" < "001" < ":x" <
     * "[old]" < "_misc" < "A Team" < "A-Team" < "music" < "{x}" < "~last" < "Äpfel".
     */
    @Test
    fun ac5_fixtureF4_everyNameSortsBeforeEveryLaterName() {
        assertStrictlyAscending("fixture F4", GroupSortFixtures.F4_A_TO_Z)
    }

    /** AC-5 through fixtures F1 and F3 of plan section 5, pairwise on their A-Z columns. */
    @Test
    fun ac5_fixturesF1AndF3_everyNameSortsBeforeEveryLaterName() {
        assertStrictlyAscending("fixture F1", GroupSortFixtures.F1_A_TO_Z)
        assertStrictlyAscending("fixture F3", GroupSortFixtures.F3_A_TO_Z)
    }

    /**
     * AC-5 (and the ties of AC-6): the three names of fixture F2 that differ only in case and
     * surrounding whitespace are equal.
     */
    @Test
    fun ac5_fixtureF2_namesThatDifferOnlyInCaseAndSurroundingWhitespaceAreEqual() {
        assertEqualNames("fixture F2 ties", "Music", "music")
        assertEqualNames("fixture F2 ties", "Music", " MUSIC ")
        assertEqualNames("fixture F2 ties", "music", " MUSIC ")
        assertBefore("fixture F2", "a", "b")
        assertBefore("fixture F2", "b", "Music")
        assertBefore("fixture F2", "b", " MUSIC ")
    }

    /** AC-5: a name is equal to itself. */
    @Test
    fun ac5_nameComparedWithItselfIsZero() {
        for (name in namePool()) {
            assertEquals("AC-5: compareNames(${quoted(name)}, the same name)", 0, GroupSorter.compareNames(name, name))
        }
    }

    /**
     * AC-5: `compareNames(a, b)` and `compareNames(b, a)` have opposite signs (both 0 for equal
     * names), for every pair out of all fixtures. A sort needs this to be well defined.
     */
    @Test
    fun ac5_swappedArgumentsGiveTheOppositeSign() {
        val pool = namePool()
        for (a in pool) {
            for (b in pool) {
                val forward = GroupSorter.compareNames(a, b)
                val backward = GroupSorter.compareNames(b, a)
                assertEquals(
                    "AC-5: compareNames(${quoted(a)}, ${quoted(b)}) was $forward and with swapped " +
                        "arguments $backward; the signs must be opposite",
                    -forward.sign,
                    backward.sign
                )
            }
        }
    }

    private fun namePool(): List<String> = (
        GroupSortFixtures.AC5_CHAIN +
            GroupSortFixtures.F1_MANUAL +
            GroupSortFixtures.F2_MANUAL +
            GroupSortFixtures.F3_A_TO_Z +
            GroupSortFixtures.F4_A_TO_Z +
            listOf("", " ", "Mix", "MIX ", "\tmix", "10", "9", "A", "a", "\u00E4pfel", "\uD83C\uDFB5 Music", "\uFF2D\uFF36")
        ).distinct()
}
