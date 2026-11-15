package org.schabi.newpipe.enhancements.groupcreateinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [GroupNameRules] (plan section 4): which names the name prompt accepts.
 *
 * - `clean(raw)` is the name as stored and shown: Kotlin `raw.trim()`.
 * - `sameName(a, b)` is the spec's "same name": equal after removing surrounding whitespace and
 *   ignoring upper/lower case.
 * - `check(raw, takenNames)` is `EMPTY` if `clean(raw)` is empty, else `DUPLICATE` if a taken
 *   name is the same name, else `OK`.
 *
 * Expected values come from spec.md (Terms, AC-3, AC-4, AC-5) and from that contract.
 */
class GroupNameRulesTest {

    // ---- AC-3: empty or only whitespace ----

    @Test
    fun ac3_checkIsEmptyForEmptyAndWhitespaceOnlyNames() {
        for (raw in listOf("", " ", "   ", "\t", "\n", "\r\n", " \t \n ")) {
            assertEquals(
                "AC-3: check(${show(raw)}, no name taken)",
                NameCheck.EMPTY,
                GroupNameRules.check(raw, emptyList())
            )
        }
    }

    /**
     * The plan defines the cleaned name as Kotlin `raw.trim()`, which removes every character
     * that is whitespace for Kotlin, not only those up to U+0020 as `java.lang.String.trim()`
     * does. A no-break space (U+00A0) and the ideographic space of CJK keyboards (U+3000) are
     * such characters, so a name made only of them is "only whitespace".
     */
    @Test
    fun ac3_checkIsEmptyForNamesMadeOfUnicodeSpaces() {
        for (raw in listOf(" ", "　", "  　 ")) {
            assertEquals(
                "AC-3: check(${show(raw)}, no name taken); the plan cleans with Kotlin trim()",
                NameCheck.EMPTY,
                GroupNameRules.check(raw, emptyList())
            )
        }
    }

    /**
     * `check` decides `EMPTY` before it looks at the taken names. The second case can happen:
     * a group whose name is a single space may exist, and AC-3 still asks for "Empty group name".
     */
    @Test
    fun ac3_checkDecidesEmptyBeforeDuplicate() {
        assertEquals(
            "AC-3: a whitespace-only name is EMPTY whatever names are taken",
            NameCheck.EMPTY,
            GroupNameRules.check("  ", listOf("Music", "News"))
        )
        assertEquals(
            "AC-3: EMPTY wins over DUPLICATE when a taken name is itself blank",
            NameCheck.EMPTY,
            GroupNameRules.check(" ", listOf("", " ", "Music"))
        )
    }

    // ---- AC-4: the same name is refused ----

    @Test
    fun ac4_sameNameIgnoresSurroundingWhitespaceAndCase() {
        val sameNames = listOf(
            "Music" to " music ",
            "Music" to "MUSIC",
            " music " to "MUSIC",
            "Music" to "Music",
            "\tLate Night\n" to "late night"
        )
        for ((a, b) in sameNames) {
            assertTrue("AC-4: sameName(${show(a)}, ${show(b)})", GroupNameRules.sameName(a, b))
            assertTrue("AC-4: sameName(${show(b)}, ${show(a)})", GroupNameRules.sameName(b, a))
        }
    }

    @Test
    fun ac4_sameNameIsFalseWhenTheTextItselfDiffers() {
        val differentNames = listOf(
            "Music" to "Musik",
            "Music" to "Music 2",
            "Music" to "Mus",
            "Music" to "Mu sic",
            "Late Night" to "Late  Night",
            "Music" to ""
        )
        for ((a, b) in differentNames) {
            assertFalse("AC-4: sameName(${show(a)}, ${show(b)})", GroupNameRules.sameName(a, b))
            assertFalse("AC-4: sameName(${show(b)}, ${show(a)})", GroupNameRules.sameName(b, a))
        }
    }

    @Test
    fun ac4_sameNameIgnoresCaseOfLettersOutsideAscii() {
        val sameNames = listOf(
            "Ärzte" to "äRZTE",
            "Écoute" to "écoute",
            "Музыка" to "МУЗЫКА"
        )
        for ((a, b) in sameNames) {
            assertTrue("AC-4: sameName(${show(a)}, ${show(b)})", GroupNameRules.sameName(a, b))
        }
    }

    /**
     * The spec's own example, "Music" and "MUSIC", must hold on every device. With Turkish as
     * the default locale, lower-casing "I" gives a dotless "ı", so a comparison that goes
     * through the default locale tells "MUSIC" and "music" apart. The plan's
     * `equals(ignoreCase = true)` does not depend on the locale.
     */
    @Test
    fun ac4_sameNameDoesNotDependOnTheDeviceLanguage() {
        val deviceLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(
                "AC-4: \"MUSIC\" and \"music\" are the same name with a Turkish default locale too",
                GroupNameRules.sameName("MUSIC", "music")
            )
            assertEquals(
                "AC-4: check(\"MUSIC\", [Music]) with a Turkish default locale",
                NameCheck.DUPLICATE,
                GroupNameRules.check("MUSIC", listOf("Music"))
            )
        } finally {
            Locale.setDefault(deviceLocale)
        }
    }

    @Test
    fun ac4_checkIsDuplicateForATakenNameInAnyCaseOrSpacing() {
        val taken = listOf("News", "Music")
        for (raw in listOf("Music", " music ", "MUSIC", "\tnews\n")) {
            assertEquals(
                "AC-4: check(${show(raw)}, $taken)",
                NameCheck.DUPLICATE,
                GroupNameRules.check(raw, taken)
            )
        }
    }

    /** `sameName` cleans both sides, so a taken name with spaces or in another case still counts. */
    @Test
    fun ac4_checkComparesTakenNamesAsSameNamesNotLiterally() {
        assertEquals(
            "AC-4: check(\"music\", [\"  MUSIC  \"])",
            NameCheck.DUPLICATE,
            GroupNameRules.check("music", listOf("  MUSIC  "))
        )
        assertEquals(
            "AC-4: check(\"Late Night\", {\"News\", \"late night \"}) with the taken names as a Set",
            NameCheck.DUPLICATE,
            GroupNameRules.check("Late Night", setOf("News", "late night "))
        )
    }

    // ---- AC-5: any other name is accepted, surrounding whitespace removed ----

    @Test
    fun ac5_cleanRemovesSurroundingWhitespaceAndNothingElse() {
        assertEquals("AC-5: clean(\" Music \")", "Music", GroupNameRules.clean(" Music "))
        assertEquals("AC-5: clean(\"Music\")", "Music", GroupNameRules.clean("Music"))
        assertEquals(
            "AC-5: whitespace inside the name stays",
            "Late  Night",
            GroupNameRules.clean("\t Late  Night \n")
        )
        assertEquals("AC-5: upper and lower case stay", "mUSIC", GroupNameRules.clean("  mUSIC"))
        assertEquals("AC-5: clean(\"   \")", "", GroupNameRules.clean("   "))
    }

    /** See [ac3_checkIsEmptyForNamesMadeOfUnicodeSpaces]: the plan's `clean` is Kotlin `trim()`. */
    @Test
    fun ac5_cleanRemovesSurroundingUnicodeSpaces() {
        assertEquals(
            "AC-5: clean(\"\\u00A0Music\\u3000\"); the plan cleans with Kotlin trim()",
            "Music",
            GroupNameRules.clean(" Music　")
        )
    }

    @Test
    fun ac5_checkIsOkForANameThatIsNotTaken() {
        assertEquals(
            "AC-5: check(\" Music \", no name taken)",
            NameCheck.OK,
            GroupNameRules.check(" Music ", emptyList())
        )
        assertEquals(
            "AC-5: names that only resemble the new one do not make it a duplicate",
            NameCheck.OK,
            GroupNameRules.check("Music", listOf("News", "Musik", "Music 2", "Mus", "Mu sic"))
        )
    }
}

/** Makes whitespace visible in a failure message. */
private fun show(raw: String): String {
    val visible = StringBuilder("\"")
    for (c in raw) {
        when {
            c == '\t' -> visible.append("\\t")
            c == '\n' -> visible.append("\\n")
            c == '\r' -> visible.append("\\r")
            c == ' ' || !c.isWhitespace() -> visible.append(c)
            else -> visible.append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
        }
    }
    return visible.append('"').toString()
}
