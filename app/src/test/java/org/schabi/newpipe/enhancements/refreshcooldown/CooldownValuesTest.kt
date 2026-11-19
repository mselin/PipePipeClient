package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * AC-2, AC-3, AC-4 (plan section 3 and 4.6): the stored values of the two preferences
 * `enh_refresh_cooldown_groups` and `enh_refresh_cooldown_subscriptions`: the text format, the
 * two key builders, parsing that never throws, and what the group dialog's save stores.
 *
 * AC-4's "survive a restart, an export and an import" stands on this format being one `String`
 * that `parse` reads back whatever is in it; "a value that belongs to a group or a subscription
 * that no longer exists has no effect" stands on the keys (the name part of a group's key, the
 * address of a subscription's key). That the preference itself is written and carried by a
 * backup needs a device (device step 4).
 */
class CooldownValuesTest {

    private fun parse(text: String?): CooldownValues = CooldownValues.parse(text, ALLOWED)

    private fun assertEmpty(why: String, values: CooldownValues) {
        assertEquals(why, 0, values.size)
        assertEquals(why, "", values.format())
        assertEquals(why, CooldownValues.EMPTY, values)
    }

    // ---- 3.1: the keys ----

    /** AC-2, plan 3.1: a group's key is its id and the hash of its stored name, 8 hex digits, as the plan prints them. */
    @Test
    fun ac2_groupKey_isTheIdAndEightHexDigitsOfTheNamesHash() {
        assertEquals("7:0473fe05", CooldownValues.groupKey(7, "Music"))
        assertEquals("7:0636ee25", CooldownValues.groupKey(7, "music"))
        assertEquals("9:0024fef3", CooldownValues.groupKey(9, "News"))
        assertEquals("7:00000000", CooldownValues.groupKey(7, ""))
        assertEquals("7:0026f385", CooldownValues.groupKey(7, "Rock"))
        assertEquals("a hash with the sign bit set is 8 digits, no minus sign", "7:80000000", CooldownValues.groupKey(7, "polygenelubricants"))
        assertEquals("the name is hashed as stored: a leading space is another name", "7:3b0f11e5", CooldownValues.groupKey(7, " Music"))
        assertEquals("1234567890123:0473fe05", CooldownValues.groupKey(1234567890123L, "Music"))
    }

    /** AC-2, plan 3.1: whatever the name (other scripts, emoji, `;`, `=`), the key is `<uid>:` and 8 lower-case hex digits of `name.hashCode()`. */
    @Test
    fun ac2_groupKey_anyName_givesAValidKeyOfTheSameShape() {
        val names = listOf(
            "Müsik", "音楽", "😀 fun", "a;b=c:d", "x".repeat(500), "\n", "=", ";", "7:0473fe05=300",
            "الموسيقى", "Z".repeat(7), "zzzzzzzz"
        )
        for (name in names) {
            val key = CooldownValues.groupKey(42, name)
            assertEquals("the key of `$name`", "42:" + String.format("%08x", name.hashCode()), key)
            assertTrue("`$key` matches 42:[0-9a-f]{8}", Regex("42:[0-9a-f]{8}").matches(key))
            assertTrue("plan 3.1: \"both builders produce only such keys\": `$key`", CooldownValues.isValidKey(key))
        }
    }

    /** AC-3, plan 3.1: a subscription's key is the service id and the URL-encoded address, as the plan prints them. */
    @Test
    fun ac3_subscriptionKey_isTheServiceIdAndTheEncodedAddress() {
        assertEquals(
            "0:https%3A%2F%2Fwww.youtube.com%2Fchannel%2FUC-lHJZR3Gqxm24_Vd_AJ5Yw",
            CooldownValues.subscriptionKey(0, "https://www.youtube.com/channel/UC-lHJZR3Gqxm24_Vd_AJ5Yw")
        )
        assertEquals(
            "5:https%3A%2F%2Fexample.org%2Fc%2Fa+b%3Bc%3Dd%3Ae%3Fx%3D1%26y%3D%C3%A9%7Cz",
            CooldownValues.subscriptionKey(5, "https://example.org/c/a b;c=d:e?x=1&y=é|z")
        )
        assertEquals("plan 3.1: `url ?: \"\"`", "3:", CooldownValues.subscriptionKey(3, null))
        assertEquals("3:", CooldownValues.subscriptionKey(3, ""))
        assertEquals("-1:a", CooldownValues.subscriptionKey(-1, "a"))
    }

    /**
     * AC-3, AC-4, plan 3.1: an address can never break the format. For addresses with `=`, `;`,
     * `:`, spaces, line breaks, `%`, `+` and letters outside ASCII, the key holds none of the
     * format's separators after its one `:`, is a valid key, decodes back to the address, and
     * two different addresses never share a key.
     */
    @Test
    fun ac4_subscriptionKey_anAddressCanNeverBreakTheFormat() {
        val addresses = listOf(
            "https://example.org/c/a b;c=d:e?x=1&y=é|z",
            "https://www.bilibili.com/space/123?a=1;b=2",
            "=", ";", ":", "==;;::", "a=300;7:0473fe05=0", ";7:0473fe05=0;", " ", "  a  ", "\n", "a\tb\r\nc",
            "100%", "a+b", "a%3Bb", "a%3Db", "a b", "a+b ", "~*._-", "é", "é", "音楽/😀",
            "https://example.org/@موسيقى", " ", "\u0000", "https://example.org/" + "p/".repeat(400)
        )
        var all = CooldownValues.EMPTY
        for ((index, address) in addresses.withIndex()) {
            val key = CooldownValues.subscriptionKey(2, address)
            val rest = key.removePrefix("2:")
            assertTrue("the key of `$address` starts with the service id: `$key`", key.startsWith("2:"))
            for (separator in listOf(';', '=', ':', ' ', '\n', '\r', '\t')) {
                assertFalse("the encoded address of `$address` must not contain `$separator`: `$rest`", rest.contains(separator))
            }
            assertTrue("only A-Z a-z 0-9 . * _ + % - after the colon: `$rest`", Regex("[A-Za-z0-9.*_+%-]*").matches(rest))
            assertTrue("plan 3.1: \"both builders produce only such keys\": `$key`", CooldownValues.isValidKey(key))
            assertEquals("the address can be read back from the key", address, URLDecoder.decode(rest, "UTF-8"))
            all = all.with(key, CHOICES[index % CHOICES.size])
        }
        assertEquals("AC-3: different addresses have different keys", addresses.size, all.size)

        val text = all.format()
        assertEquals("one `;` between two entries and no other", addresses.size - 1, text.count { it == ';' })
        assertEquals("one `=` per entry and no other", addresses.size, text.count { it == '=' })
        val back = parse(text)
        assertEquals("AC-4: every value survives the round trip through the preference", all, back)
        for ((index, address) in addresses.withIndex()) {
            assertEquals("the value of `$address`", CHOICES[index % CHOICES.size], back[CooldownValues.subscriptionKey(2, address)])
        }
    }

    /** AC-3: the same address on two services, and two addresses that differ only in case, are different subscriptions. */
    @Test
    fun ac3_subscriptionKey_serviceAndCaseMatter() {
        val keys = setOf(
            CooldownValues.subscriptionKey(0, "https://example.org/c/abc"),
            CooldownValues.subscriptionKey(1, "https://example.org/c/abc"),
            CooldownValues.subscriptionKey(0, "https://example.org/c/ABC"),
            CooldownValues.subscriptionKey(0, "https://example.org/c/abc/"),
            CooldownValues.subscriptionKey(10, "https://example.org/c/abc")
        )
        assertEquals(5, keys.size)
    }

    /** Plan 3.1: "A valid key matches `-?[0-9]+:[A-Za-z0-9.*_+%-]*`", the whole key. */
    @Test
    fun ac4_isValidKey_isThePlansPattern() {
        for (key in listOf("7:0473fe05", "7:", "-1:abc", "0:https%3A%2F%2Fa.b%2Fc+d", "007:A.z*_+%-9", "12345678901234567890:x")) {
            assertTrue("`$key` is a valid key", CooldownValues.isValidKey(key))
        }
        for (key in listOf(
            "", "7", ":", ":abc", "abc", "x:1", "7:a b", "7:a;b", "7:a=b", "7:a:b", "7::", "+7:a", "--7:a", "-:a", " 7:a", "7:a ",
            "7 :a", "7:a\n", "\n7:a", "7:a/b", "7:a?b", "7:a&b", "7:a~b", "7:é", "٧:a", "7.5:a", "7:a\u0000", "7=300"
        )) {
            assertFalse("`$key` is not a valid key", CooldownValues.isValidKey(key))
        }
    }

    // ---- 3.2: parsing ----

    /** AC-4, plan 3.2 row 1: nothing stored, or nothing that is an entry, is "no values". */
    @Test
    fun ac4_parse_nothingOrNoEntry_isEmpty() {
        for (text in listOf(null, "", ";;", "abc", "=300", "7:0473fe05=", ";", " ", " ; ; ", "=", "==", ";=;", "\n")) {
            assertEmpty("parse(${text?.let { "\"$it\"" }})", parse(text))
        }
    }

    /** AC-4, plan 3.2: the example of 3.1. */
    @Test
    fun ac4_parse_readsTheEntries() {
        val values = parse("7:0473fe05=3600;9:0024fef3=0")

        assertEquals(2, values.size)
        assertEquals(3600, values["7:0473fe05"])
        assertEquals("\"Always update\" is a value, not \"no value\"", 0, values["9:0024fef3"])
        assertNull(values["8:0473fe05"])
        assertNull(values["7:0473fe0"])
        assertNull(values[""])
    }

    /** AC-4, plan 3.2 rows 2-5: an entry that is not `<valid key>=<1-9 digits, a choice>` is dropped. */
    @Test
    fun ac4_parse_dropsEveryKindOfGarbageEntry() {
        val garbage = listOf(
            // no `=`, or `=` at position 0
            "7:0473fe05", "7:0473fe05 300", "=300", "=", "300",
            // the key does not match the pattern
            "7=300", "x:1=300", "7:a b=300", ":0473fe05=300", "7:04:73=300", "7:a/b=300", "7 :a=300", "7:é=300", "٧:a=300",
            // the seconds are not 1 to 9 digits
            "7:0473fe05=", "7:0473fe05=3e2", "7:0473fe05=-300", "7:0473fe05=+300", "7:0473fe05= 300", "7:0473fe05=3000000000",
            "7:0473fe05=300.0", "7:0473fe05=0x12c", "7:0473fe05=300s", "7:0473fe05=3 00", "7:0473fe05=1234567890",
            "7:0473fe05=99999999999999999999", "7:0473fe05=٣٠٠", "7:0473fe05=null", "7:0473fe05=true",
            // the split is at the last `=`: what is before it is not a key
            "7:0473fe05=300=300", "7:0473fe05==300", "7:0473fe05=300=",
            // the seconds are not a choice
            "7:0473fe05=301", "7:0473fe05=1", "7:0473fe05=299", "7:0473fe05=60", "7:0473fe05=86401", "7:0473fe05=999999999"
        )
        for (entry in garbage) {
            assertEmpty("parse(\"$entry\")", parse(entry))
            val beside = parse("9:0024fef3=0;$entry;8:0026f385=900")
            assertEquals("plan 3.2: \"a valid entry next to garbage: the valid entry is kept\" (garbage: `$entry`)", values("9:0024fef3" to 0, "8:0026f385" to 900), beside)
        }
    }

    /** AC-4, plan 3.2: stray separators around and between valid entries change nothing. */
    @Test
    fun ac4_parse_straySeparators() {
        val expected = values("7:0473fe05" to 300, "9:0024fef3" to 0)
        for (text in listOf(
            "7:0473fe05=300;9:0024fef3=0", ";7:0473fe05=300;9:0024fef3=0", "7:0473fe05=300;9:0024fef3=0;", ";;7:0473fe05=300;;;9:0024fef3=0;;",
            "7:0473fe05=300; ;9:0024fef3=0", "7:0473fe05=300;=;9:0024fef3=0", "9:0024fef3=0;7:0473fe05=300"
        )) {
            assertEquals("parse(\"$text\")", expected, parse(text))
        }
    }

    /** AC-4, plan 3.2: "spaces around an entry: trimmed, kept"; a space inside an entry is not. */
    @Test
    fun ac4_parse_trimsSpacesAroundAnEntry() {
        assertEquals(values("7:0473fe05" to 300), parse(" 7:0473fe05=300 ;"))
        assertEquals(values("7:0473fe05" to 300, "9:0024fef3" to 0), parse("  7:0473fe05=300  ;   9:0024fef3=0   "))
        assertEmpty("a space before `=` belongs to the key", parse("7:0473fe05 =300"))
        assertEmpty("a space after `=` belongs to the seconds", parse("7:0473fe05= 300"))
    }

    /** AC-4, plan 3.2: "the same key twice: the last one wins". */
    @Test
    fun ac4_parse_duplicateKey_theLastOneWins() {
        assertEquals(values("7:0473fe05" to 3600), parse("7:0473fe05=300;7:0473fe05=3600"))
        assertEquals(values("7:0473fe05" to 0, "9:0024fef3" to 900), parse("7:0473fe05=300;9:0024fef3=900;7:0473fe05=86400;7:0473fe05=0"))
        assertEquals("a later entry that is garbage does not replace an earlier valid one", values("7:0473fe05" to 300), parse("7:0473fe05=300;7:0473fe05=301"))
    }

    /** AC-4, plan 3.2 and risk 7: which seconds count is given by the caller: a value that is no longer a choice is "Use default". */
    @Test
    fun ac4_parse_onlyTheGivenChoicesCount() {
        val text = "7:0473fe05=300;9:0024fef3=0;8:0026f385=3600"

        assertEquals(values("8:0026f385" to 3600), CooldownValues.parse(text, setOf(3600)))
        assertEmpty("no choices: no values", CooldownValues.parse(text, emptySet()))
        assertEquals(3, CooldownValues.parse(text, ALLOWED).size)
        assertEquals("a choice the app does not have today", 7200, CooldownValues.parse("7:0473fe05=7200", setOf(7200))["7:0473fe05"])
    }

    /**
     * AC-4, plan 3.2 "(plain, never throws)": whatever a restored or hand-edited backup holds.
     * 20 000 generated strings from the format's own alphabet plus junk: `parse` returns, and
     * what it returns holds only valid keys with seconds that are a choice, and writes back as
     * text that parses to the same values.
     */
    @Test
    fun ac4_parse_neverThrows_andKeepsOnlyValidEntries() {
        val random = java.util.Random(20261119)
        val pieces = listOf(
            "7", "9", "-1", ":", "=", ";", " ", "0473fe05", "300", "0", "3600", "301", "-", "+", "%3A", "abc", "\n", "é", "٣",
            "99999999999", "x", ".", "*", "_", "/", "\u0000", "7:0473fe05=300", "9:0024fef3=0", "0:https%3A%2F%2Fa.b=86400"
        )
        var kept = 0
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(12)) { append(pieces[random.nextInt(pieces.size)]) } }
            val values = try {
                parse(text)
            } catch (e: Throwable) {
                throw AssertionError("plan 3.2: parse never throws, but did for \"$text\": $e", e)
            }
            val written = values.format()
            if (values.size > 0) {
                kept++
                for (entry in written.split(';')) {
                    val key = entry.substringBeforeLast('=')
                    assertTrue("parse(\"$text\") kept the invalid key `$key`", CooldownValues.isValidKey(key))
                    assertTrue("parse(\"$text\") kept seconds that are not a choice: `$entry`", entry.substringAfterLast('=').toInt() in ALLOWED)
                    assertEquals(entry.substringAfterLast('=').toInt(), values[key])
                }
                assertEquals(values.size, written.split(';').size)
            } else {
                assertEquals("", written)
            }
            assertEquals("plan 3.2: `parse(format(v), allowed)` equals `v` (from \"$text\")", values, parse(written))
        }
        assertTrue("the generator produces valid entries too ($kept of 20000)", kept > 200)
    }

    // ---- with / format / equals ----

    /** AC-2, AC-3, plan section 4: `with` adds, replaces and removes, and never changes the object it is called on. */
    @Test
    fun ac3_with_addsReplacesRemoves_andLeavesTheReceiverAlone() {
        val empty = CooldownValues.EMPTY
        val one = empty.with("7:0473fe05", 300)
        val replaced = one.with("7:0473fe05", 3600)
        val two = replaced.with("9:0024fef3", 0)
        val removed = two.with("7:0473fe05", null)

        assertEquals(0, empty.size)
        assertNull(empty["7:0473fe05"])
        assertEquals(1, one.size)
        assertEquals(300, one["7:0473fe05"])
        assertEquals(1, replaced.size)
        assertEquals(3600, replaced["7:0473fe05"])
        assertEquals(2, two.size)
        assertEquals(0, two["9:0024fef3"])
        assertEquals(1, removed.size)
        assertNull(removed["7:0473fe05"])
        assertEquals(0, removed["9:0024fef3"])
        assertEquals("the earlier objects are unchanged", listOf("", "7:0473fe05=300", "7:0473fe05=3600", "7:0473fe05=3600;9:0024fef3=0", "9:0024fef3=0"), listOf(empty, one, replaced, two, removed).map { it.format() })
        assertEquals("removing what is not there changes nothing", one, one.with("8:0026f385", null))
        assertEmpty("removing the last entry gives the empty values", one.with("7:0473fe05", null))
        assertEquals(0, CooldownValues.EMPTY.size)
    }

    /** Plan section 4: "`with` … an invalid key: returns this". Nothing unparsable can be written. */
    @Test
    fun ac4_with_anInvalidKey_returnsThis() {
        val one = values("7:0473fe05" to 300)
        for (key in listOf("", "7", "7:a b", "7:a;b", "7:a=b", "x:1", "7:0473fe05;9:0024fef3", "7:0473fe05=300", "https://example.org/c/a")) {
            assertSame("with(\"$key\", 300)", one, one.with(key, 300))
            assertSame("with(\"$key\", null)", one, one.with(key, null))
        }
        assertEquals("7:0473fe05=300", one.format())
    }

    /**
     * Plan 3.1: "entries `<key>=<seconds>` joined by `;`, sorted by key (`String.compareTo`)",
     * whatever the order they were added in. As text, not as numbers: `100:` comes before
     * `10:0…` (`0` sorts before `:`) and `10:` before `7:`.
     */
    @Test
    fun ac4_format_isSortedByKeyAsText() {
        val keys = listOf("7:0473fe05", "10:0473fe05", "-1:abc", "9:0024fef3", "7:0026f385", "0:https%3A%2F%2Fb", "0:https%3A%2F%2FB", "100:", "7:")
        val expected = "-1:abc=300;0:https%3A%2F%2FB=300;0:https%3A%2F%2Fb=300;100:=300;10:0473fe05=300;7:=300;7:0026f385=300;7:0473fe05=300;9:0024fef3=300"
        assertEquals("the test's expectation is sorted by String.compareTo", keys.sorted().joinToString(";") { "$it=300" }, expected)

        var forward = CooldownValues.EMPTY
        for (key in keys) forward = forward.with(key, 300)
        var backward = CooldownValues.EMPTY
        for (key in keys.reversed()) backward = backward.with(key, 300)

        assertEquals(expected, forward.format())
        assertEquals("the order of the writes does not show in the text", expected, backward.format())
        assertEquals("nor does the order in the stored text", expected, parse(keys.shuffled(java.util.Random(1)).joinToString(";") { "$it=300" }).format())
        assertEquals("7:0473fe05=3600;9:0024fef3=0", values("9:0024fef3" to 0, "7:0473fe05" to 3600).format())
    }

    /** Plan 3.1: "With no entry at all the preference is removed": `format()` of no values is `""`, which the store turns into a removal. */
    @Test
    fun ac4_format_noValues_isTheEmptyString() {
        assertEquals("", CooldownValues.EMPTY.format())
        assertEquals("", parse("garbage").format())
        assertEquals("", values("7:0473fe05" to 300).with("7:0473fe05", null).format())
    }

    /** Plan section 4: "`equals` by entries; `hashCode` with it". */
    @Test
    fun ac4_equals_isByEntries() {
        val a = values("7:0473fe05" to 300, "9:0024fef3" to 0)
        val b = parse("9:0024fef3=0;7:0473fe05=300")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, values("7:0473fe05" to 300))
        assertNotEquals(a, values("7:0473fe05" to 300, "9:0024fef3" to 300))
        assertNotEquals(a, values("7:0473fe05" to 300, "9:0026f385" to 0))
        assertEquals(CooldownValues.EMPTY, parse(null))
        assertEquals(CooldownValues.EMPTY.hashCode(), parse(";;").hashCode())
        assertFalse(a.equals("7:0473fe05=300;9:0024fef3=0"))
        assertFalse(a.equals(null))
    }

    /** AC-4, plan section 13: "`parse(format(v))` equals `v` for 1600 subscription values" - the owner's list, every channel with a value. */
    @Test
    fun ac4_scale_1600SubscriptionValues_surviveTheRoundTrip() {
        var all = CooldownValues.EMPTY
        val channels = (1..SUBSCRIPTIONS).map { Channel(it.toLong(), it % 3, "https://www.youtube.com/channel/UC%022d?a=1;b=%d".format(it, it)) }
        for (channel in channels) all = all.with(keyOf(channel), CHOICES[channel.id.toInt() % CHOICES.size])

        val text = all.format()
        val back = parse(text)

        assertEquals(SUBSCRIPTIONS, all.size)
        assertEquals(all, back)
        assertEquals(text, back.format())
        assertEquals("one entry per channel", SUBSCRIPTIONS, text.split(';').size)
        assertEquals("sorted", text.split(';').map { it.substringBeforeLast('=') }.sorted(), text.split(';').map { it.substringBeforeLast('=') })
        for (channel in channels) assertEquals(CHOICES[channel.id.toInt() % CHOICES.size], back[keyOf(channel)])
    }

    // ---- 3.3 and 4.6: the group's name ----

    /**
     * AC-4, plan 3.3: "A group's value applies only to a group with the same id **and** the
     * same name; any other group is on *Use default*": a database imported without its
     * settings can hold another group under the same id.
     */
    @Test
    fun ac4_aGroupWithTheSameIdButAnotherName_hasNoValue() {
        val stored = parse("7:0473fe05=3600")

        assertEquals(3600, stored[CooldownValues.groupKey(7, "Music")])
        for (other in listOf("News", "music", "Music ", " Music", "MUSIC", "", "Musik")) {
            assertNull("group 7 named `$other`", stored[CooldownValues.groupKey(7, other)])
        }
        assertNull("another group of the same name", stored[CooldownValues.groupKey(8, "Music")])
        assertNull(stored[CooldownValues.groupKey(70, "Music")])
    }

    /** Plan 3.3, last row: the one false match the plan accepts: equal id and equal `String.hashCode` ("Aa" and "BB"). */
    @Test
    fun ac4_groupKey_namesWithTheSameHashShareAKey_asThePlanAccepts() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals("7:00000840", CooldownValues.groupKey(7, "Aa"))
        assertEquals(CooldownValues.groupKey(7, "Aa"), CooldownValues.groupKey(7, "BB"))
    }

    /** AC-2, plan 4.6: every row of the table: what the group dialog's save stores. */
    @Test
    fun ac2_groupSaved_everyRowOfThePlansTable() {
        fun saved(before: String, oldName: String, newName: String, seconds: Int?): String =
            CooldownValues.groupSaved(parse(before), 7, oldName, newName, seconds).format()

        assertEquals("row 1: Use default stays Use default", "", saved("", "Music", "Music", null))
        assertEquals("row 2: a value is chosen", "7:0473fe05=3600", saved("", "Music", "Music", 3600))
        assertEquals("row 3: back to Use default removes the entry", "", saved("7:0473fe05=3600", "Music", "Music", null))
        assertEquals("row 4: renamed, value kept: the entry moves to the new name", "7:0024fef3=3600", saved("7:0473fe05=3600", "Music", "News", 3600))
        assertEquals("row 5: another group's entry is untouched", "7:0473fe05=900;9:0024fef3=0", saved("7:0473fe05=3600;9:0024fef3=0", "Music", "Music", 900))
    }

    /**
     * AC-2, AC-4, plan 3.3 "Group renamed in the dialog: the save removes the entry of the old
     * name and writes the one of the new name": after a rename the group still has its value
     * under its new name, and nothing is left under the old one.
     */
    @Test
    fun ac2_groupSaved_renameKeepsTheValue_andLeavesNothingUnderTheOldName() {
        val before = values(CooldownValues.groupKey(7, "Music") to 3600, CooldownValues.groupKey(9, "News") to 0)

        val after = CooldownValues.groupSaved(before, 7, "Music", "Rock", 3600)

        assertEquals("the renamed group has its value", 3600, after[CooldownValues.groupKey(7, "Rock")])
        assertNull("nothing is left under the old name", after[CooldownValues.groupKey(7, "Music")])
        assertEquals(0, after[CooldownValues.groupKey(9, "News")])
        assertEquals(2, after.size)
        assertEquals("the stored values are not changed by the call", 3600, before[CooldownValues.groupKey(7, "Music")])

        val changed = CooldownValues.groupSaved(before, 7, "Music", "Rock", 900)
        assertEquals("renamed and changed in one save", "7:0026f385=900;9:0024fef3=0", changed.format())

        val cleared = CooldownValues.groupSaved(before, 7, "Music", "Rock", null)
        assertEquals("renamed and set to Use default: neither name has an entry", "9:0024fef3=0", cleared.format())

        val toTheNameOfAnotherGroup = CooldownValues.groupSaved(before, 7, "Music", "News", 300)
        assertEquals("the id is part of the key: group 9's entry stays its own", "7:0024fef3=300;9:0024fef3=0", toTheNameOfAnotherGroup.format())
    }

    /** AC-2, plan 4.6: a save for a group whose stored value was under no name (never set) writes only the new entry. */
    @Test
    fun ac2_groupSaved_noEntryBefore_writesOnlyTheNewOne() {
        val before = values(CooldownValues.groupKey(9, "News") to 0)

        assertEquals("7:0026f385=86400;9:0024fef3=0", CooldownValues.groupSaved(before, 7, "Music", "Rock", 86400).format())
        assertEquals(before, CooldownValues.groupSaved(before, 7, "Music", "Rock", null))
    }
}
