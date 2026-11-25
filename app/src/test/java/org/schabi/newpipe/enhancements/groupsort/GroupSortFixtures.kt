package org.schabi.newpipe.enhancements.groupsort

import java.util.Random

/**
 * Names and orders used by the group-sort tests.
 *
 * Every expected order is copied from `enhancements/group-sort/spec.md` (the example chain of
 * AC-5) or from `enhancements/group-sort/plan.md` section 5 (fixtures F1-F4). None of them is
 * computed by the code under test.
 *
 * Characters outside ASCII are written as escapes so the code points are unambiguous:
 * U+00C4 is "Ä", U+00E4 is "ä", U+65E5 U+672C is "日本".
 */
internal object GroupSortFixtures {

    /** "Äpfel", with a precomposed capital A-umlaut. */
    const val AEPFEL = "\u00C4pfel"

    /** "ärger", with a precomposed small a-umlaut. */
    const val AERGER = "\u00E4rger"

    /** "日本". */
    const val NIHON = "\u65E5\u672C"

    /** Spec AC-5: each name sorts before every name to its right. */
    val AC5_CHAIN: List<String> = listOf(
        "- Top",
        "001 - First",
        "Mix 10",
        "Mix 2",
        "music",
        "ZZZ - Last",
        "ZZZZ - Last of last",
        AEPFEL
    )

    /** Plan fixture F1, column "Input in manual order". */
    val F1_MANUAL: List<String> = listOf(
        "ZZZ - Last",
        "music",
        "Mix 2",
        "001 - First",
        "ZZZZ - Last of last",
        "Mix 10"
    )

    /** Plan fixture F1, column "A-Z". */
    val F1_A_TO_Z: List<String> = listOf(
        "001 - First",
        "Mix 10",
        "Mix 2",
        "music",
        "ZZZ - Last",
        "ZZZZ - Last of last"
    )

    /** Plan fixture F1, column "Z-A": "exact reverse of A-Z". */
    val F1_Z_TO_A: List<String> = listOf(
        "ZZZZ - Last of last",
        "ZZZ - Last",
        "music",
        "Mix 2",
        "Mix 10",
        "001 - First"
    )

    /** Plan fixture F2, manual order. "Music", "music" and " MUSIC " are equal names (ties). */
    val F2_MANUAL: List<String> = listOf("b", "Music", "a", "music", " MUSIC ")

    /** Plan fixture F2, column "A-Z": the ties stay in manual order. */
    val F2_A_TO_Z: List<String> = listOf("a", "b", "Music", "music", " MUSIC ")

    /** Plan fixture F2, column "Z-A": the ties stay in manual order, so not the reverse of A-Z. */
    val F2_Z_TO_A: List<String> = listOf("Music", "music", " MUSIC ", "b", "a")

    /** Plan fixture F3, column "A-Z". The input is "any shuffle" of it. */
    val F3_A_TO_Z: List<String> = listOf("Mix", "music", "Zebra", "zebra 2", AEPFEL, AERGER, NIHON)

    /** Plan fixture F3, column "Z-A": "exact reverse". */
    val F3_Z_TO_A: List<String> = listOf(NIHON, AERGER, AEPFEL, "zebra 2", "Zebra", "music", "Mix")

    /** Plan fixture F4, column "A-Z". The input is "any shuffle" of it. */
    val F4_A_TO_Z: List<String> = listOf(
        "- Top",
        "001",
        ":x",
        "[old]",
        "_misc",
        "A Team",
        "A-Team",
        "music",
        "{x}",
        "~last",
        AEPFEL
    )

    /** Plan fixture F4, column "Z-A": "exact reverse". */
    val F4_Z_TO_A: List<String> = listOf(
        AEPFEL,
        "~last",
        "{x}",
        "music",
        "A-Team",
        "A Team",
        "_misc",
        "[old]",
        ":x",
        "001",
        "- Top"
    )

    /**
     * Deterministic rearrangements of [sorted], standing in for "any shuffle": the list as it
     * is, reversed, every rotation, and 20 shuffles with fixed seeds. Every one of them must
     * sort to the same result because no two names of F1, F3, F4 or the AC-5 chain are equal.
     */
    fun <T> shufflesOf(sorted: List<T>): List<List<T>> {
        val result = mutableListOf(sorted.toList(), sorted.reversed())
        for (shift in 1 until sorted.size) {
            result += sorted.drop(shift) + sorted.take(shift)
        }
        for (seed in 1L..20L) {
            result += sorted.shuffled(Random(seed))
        }
        return result
    }

    /** [names] as groups in manual order; ids count up from 1, as positions in that order. */
    fun groupsOf(names: List<String>): List<TestGroup> =
        names.mapIndexed { index, name -> TestGroup(index + 1L, name) }

    /** Makes surrounding whitespace and invisible characters visible in failure messages. */
    fun quoted(name: String): String = buildString {
        append('"')
        for (c in name) {
            when {
                c == '\t' -> append("\\t")
                c == '\n' -> append("\\n")
                c.code < 0x20 || c == '\u00A0' || c == '\u3000' ->
                    append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}

/**
 * Stand-in for a channel group (the tests must not use the Android-bound entity). [id] tells
 * groups with equal names apart and is the position in the manual order where a test says so.
 */
internal data class TestGroup(val id: Long, val name: String)
