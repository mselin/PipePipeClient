package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-5, AC-6, AC-9 (plan 4.1 to 4.3): the decisions of a refresh: a channel's effective
 * cooldown, whether it is in cooldown, which channels of a feed are loaded and which are
 * skipped, and which a "Refresh anyway" loads.
 *
 * The channels of plan 4.3, used throughout: A has no group, B and C are in group 7 ("Music",
 * 3600 s), C is also in group 9 ("News", "Always update"), D is in group 9 and has its own
 * value of one day, E is in group 8 ("Sport", no value). Global cooldown 300 s.
 *
 * Not shown here: that the loader calls these functions with the database's rows and the
 * setting's seconds (`CooldownRefresh`, glue: wiring test and device steps 5, 6 and 9).
 */
class CooldownRulesTest {

    private val a = Channel(1)
    private val b = Channel(2)
    private val c = Channel(3)
    private val d = Channel(4)
    private val e = Channel(5)
    private val all = listOf(a, b, c, d, e)

    private val music = GroupName(7, "Music")
    private val sport = GroupName(8, "Sport")
    private val news = GroupName(9, "News")

    private fun planData(last: Map<Long, Long> = emptyMap()) = FakeData(
        last = last,
        memberships = listOf(Membership(7, b.id), Membership(7, c.id), Membership(9, c.id), Membership(9, d.id), Membership(8, e.id)),
        groups = listOf(music, sport, news),
        own = values(keyOf(d) to 86400),
        groupValues = values(CooldownValues.groupKey(7, "Music") to 3600, CooldownValues.groupKey(9, "News") to 0)
    )

    private fun ago(seconds: Long): Long = NOW - seconds * 1000

    /** Whether [channel] is loaded by a refresh of "All" when it was last refreshed at [last] (`null`: never, or the last refresh failed). */
    private fun loaded(channel: Channel, last: Long?, data: FakeData = planData()): Boolean {
        data.last = if (last == null) emptyMap() else mapOf(channel.id to last)
        val selection = select(all, data, 300)
        val isLoaded = channel in selection.load
        assertEquals("a channel of the feed is either loaded or skipped", !isLoaded, channel.id in selection.skipped)
        return isLoaded
    }

    // ---- 4.1 effective ----

    /** AC-5 and the spec's term "effective cooldown", plan 4.1: every row of the table. */
    @Test
    fun ac5_effective_everyRowOfThePlansTable() {
        assertEquals("row 1: its own value", 3600, CooldownRules.effective(3600, emptyList(), 300))
        assertEquals("row 1: its own value, whatever its groups and the global one say", 3600, CooldownRules.effective(3600, listOf(0, 86400), 0))
        assertEquals("row 2: its own \"Always update\" beats a group's day", 0, CooldownRules.effective(0, listOf(86400), 300))
        assertEquals("row 3: its own day beats a group's \"Always update\"", 86400, CooldownRules.effective(86400, listOf(0), 300))
        assertEquals("row 4: nothing set: the global cooldown", 300, CooldownRules.effective(null, emptyList(), 300))
        assertEquals("row 5: the shortest of its groups' values", 900, CooldownRules.effective(null, listOf(3600, 900), 300))
        assertEquals("row 6: a group's value replaces the global one, also when longer", 3600, CooldownRules.effective(null, listOf(3600), 300))
        assertEquals("row 7: \"Always update\" is the shortest possible", 0, CooldownRules.effective(null, listOf(0, 3600), 86400))
    }

    /** AC-5: "the shortest cooldown among the groups it is in that have one", whatever the order and number of the groups. */
    @Test
    fun ac5_effective_theShortestOfSeveralGroupValues_inAnyOrder() {
        val several = listOf(86400, 900, 43200, 3600, 21600)
        for (rotation in several.indices) {
            val rotated = several.drop(rotation) + several.take(rotation)
            assertEquals("groups $rotated", 900, CooldownRules.effective(null, rotated, 300))
            assertEquals("groups $rotated, with a group on \"Always update\"", 0, CooldownRules.effective(null, rotated + 0, 300))
            assertEquals("groups ${rotated.reversed()}", 900, CooldownRules.effective(null, rotated.reversed(), 300))
        }
        assertEquals(300, CooldownRules.effective(null, listOf(300, 300), 86400))
        assertEquals("a set of values is a collection too", 900, CooldownRules.effective(null, setOf(3600, 900), 0))
        assertEquals("longer than the global one: still the groups' shortest", 43200, CooldownRules.effective(null, listOf(86400, 43200), 0))
    }

    /** AC-5: every combination of the seven choices for own, one group and global: own, else the group's, else global. */
    @Test
    fun ac5_effective_everyCombinationOfTheChoices() {
        for (global in CHOICES) {
            assertEquals(global, CooldownRules.effective(null, emptyList(), global))
            for (group in CHOICES) {
                assertEquals("group $group, global $global", group, CooldownRules.effective(null, listOf(group), global))
                for (other in CHOICES) {
                    assertEquals("groups $group and $other, global $global", minOf(group, other), CooldownRules.effective(null, listOf(group, other), global))
                }
                for (own in CHOICES) {
                    assertEquals("own $own, group $group, global $global", own, CooldownRules.effective(own, listOf(group), global))
                }
            }
        }
    }

    // ---- 4.2 inCooldown ----

    /** AC-5, AC-6, plan 4.2: every row of the table. */
    @Test
    fun ac5_inCooldown_everyRowOfThePlansTable() {
        assertFalse("row 1: never refreshed, or the last refresh failed", CooldownRules.inCooldown(null, 300, NOW))
        assertFalse("row 1: with any cooldown", CooldownRules.inCooldown(null, 86400, NOW))
        assertFalse("row 1: also \"Always update\"", CooldownRules.inCooldown(null, 0, NOW))
        assertTrue("row 2: 299.999 s ago, 300 s", CooldownRules.inCooldown(NOW - 299_999, 300, NOW))
        assertTrue("row 3: exactly 300 s ago: `<` in the SQL is strict, so the channel is not loaded", CooldownRules.inCooldown(NOW - 300_000, 300, NOW))
        assertFalse("row 4: 300.001 s ago", CooldownRules.inCooldown(NOW - 300_001, 300, NOW))
        assertFalse("row 5: \"Always update\", 1 ms ago", CooldownRules.inCooldown(NOW - 1, 0, NOW))
        assertTrue("row 6: \"Always update\", in this very millisecond (as today)", CooldownRules.inCooldown(NOW, 0, NOW))
        assertTrue("row 7: the clock was set back (as today)", CooldownRules.inCooldown(NOW + 5000, 300, NOW))
        assertTrue("row 7: with any cooldown", CooldownRules.inCooldown(NOW + 5000, 0, NOW))
    }

    /**
     * AC-5, AC-11, plan 4.2 ("the negation of the SQL condition, on the same integers"): for
     * every choice, both sides of the boundary to the millisecond. Upstream loads a channel
     * when `last_updated < now - seconds`; in cooldown is exactly "not that".
     */
    @Test
    fun ac5_inCooldown_theBoundaryToTheMillisecond_forEveryChoice() {
        for (now in listOf(NOW, 1_795_000_000_123L, 86_400_000L)) {
            for (seconds in CHOICES) {
                val threshold = now - seconds * 1000L
                assertFalse("$seconds s: 1 ms older than the threshold is loaded", CooldownRules.inCooldown(threshold - 1, seconds, now))
                assertTrue("$seconds s: exactly at the threshold is skipped", CooldownRules.inCooldown(threshold, seconds, now))
                assertTrue("$seconds s: 1 ms newer than the threshold is skipped", CooldownRules.inCooldown(threshold + 1, seconds, now))
                assertFalse("$seconds s: long ago", CooldownRules.inCooldown(threshold - 86_400_000L, seconds, now))
                assertFalse("$seconds s: before 1970", CooldownRules.inCooldown(-1_000_000_000_000L, seconds, now))
            }
        }
    }

    /** Plan 4.2: `now - s * 1000L` is computed in `Long`: seconds whose milliseconds do not fit an `Int` (9 digits are allowed by the format). */
    @Test
    fun ac5_inCooldown_millisecondsAreComputedAsLong() {
        val now = 1_795_000_000_000L
        assertTrue(CooldownRules.inCooldown(now - 2_999_999_999L, 3_000_000, now))
        assertTrue(CooldownRules.inCooldown(now - 3_000_000_000L, 3_000_000, now))
        assertFalse(CooldownRules.inCooldown(now - 3_000_000_001L, 3_000_000, now))
        assertTrue(CooldownRules.inCooldown(now - 999_999_999_000L, 999_999_999, now))
        assertFalse(CooldownRules.inCooldown(now - 999_999_999_001L, 999_999_999, now))
    }

    // ---- 4.3 select ----

    /** AC-5, AC-6, plan 4.3: every row of the table "Which channels a refresh loads". */
    @Test
    fun ac5_select_everyRowOfThePlansTable() {
        assertFalse("A, 200 s ago, global 300: skipped", loaded(a, ago(200)))
        assertTrue("A, 301 s ago: loaded", loaded(a, ago(301)))
        assertTrue("A, never or failed: loaded", loaded(a, null))
        assertFalse("B, 301 s ago, group 7 has 3600: skipped", loaded(b, ago(301)))
        assertTrue("B, 3601 s ago: loaded", loaded(b, ago(3601)))
        assertTrue("C, 1 s ago, the shortest of 3600 and \"Always update\": loaded", loaded(c, ago(1)))
        assertFalse("D, 3601 s ago, its own day: skipped", loaded(d, ago(3601)))
        assertTrue("D, 86401 s ago: loaded", loaded(d, ago(86401)))
        assertFalse("E, 200 s ago, its group has no value, global 300: skipped", loaded(e, ago(200)))
        assertTrue("E, 301 s ago: loaded", loaded(e, ago(301)))
    }

    /** AC-4, plan 4.3 and 3.3: group 7 is called "Rock" in the database, the value is stored for "Music": it does not match, the global cooldown applies. */
    @Test
    fun ac4_select_aValueStoredForAnotherNameOfTheGroup_hasNoEffect() {
        val renamed = planData().apply { groups = listOf(GroupName(7, "Rock"), sport, news) }

        assertTrue("B, 301 s ago: loaded, as with the global 300 s", loaded(b, ago(301), renamed))
        assertFalse("B, 200 s ago: skipped, as with the global 300 s", loaded(b, ago(200), renamed))
        assertTrue("C keeps the value of its other group, which matches", loaded(c, ago(1), renamed))
        assertFalse("with the stored name the value applies", loaded(b, ago(301), planData()))
    }

    /** AC-4: values of a deleted group and of an unsubscribed channel match nothing and change nothing. */
    @Test
    fun ac4_select_valuesOfADeletedGroupAndAnUnsubscribedChannel_haveNoEffect() {
        val stale = planData().apply {
            groupValues = groupValues.with(CooldownValues.groupKey(6, "Gone"), 0).with(CooldownValues.groupKey(8, "Old name of Sport"), 0)
            own = own.with(CooldownValues.subscriptionKey(0, "https://example.invalid/channel/unsubscribed"), 0)
                .with(CooldownValues.subscriptionKey(1, a.url), 0)
        }

        assertFalse("A, 200 s ago: a value for the same address on another service is not A's", loaded(a, ago(200), stale))
        assertFalse("E, 200 s ago: the value is stored for another name of its group", loaded(e, ago(200), stale))
        assertFalse("B as before", loaded(b, ago(301), stale))
        assertTrue(loaded(b, ago(3601), stale))
    }

    /** AC-3, AC-4: a subscription's value follows service and address, not the subscription's id (a channel subscribed to again has a new id). */
    @Test
    fun ac4_select_aSubscriptionsValueFollowsItsAddress() {
        val again = Channel(77, d.serviceId, d.url)
        val sameIdOtherAddress = Channel(d.id, d.serviceId, "https://example.invalid/channel/other")
        val data = FakeData(last = mapOf(77L to ago(3601), d.id to ago(3601)), own = values(keyOf(d) to 86400))

        assertEquals("the channel with the stored address is skipped under its new id", listOf(77L), select(listOf(again), data, 300).skipped)
        assertEquals("the old id with another address has no value: global 300 s, loaded", listOf(sameIdOtherAddress), select(listOf(sameIdOtherAddress), data, 300).load)
    }

    /** AC-5, AC-11, plan 4.2 through `select`: the boundary to the millisecond for the global, a group's and the channel's own cooldown. */
    @Test
    fun ac5_select_theBoundaryToTheMillisecond_atEveryLevel() {
        // A: global 300 s. B: group 7, 3600 s. D: own, 86400 s. C: "Always update" through group 9.
        for ((channel, seconds) in listOf(a to 300L, b to 3600L, d to 86400L, c to 0L)) {
            val threshold = NOW - seconds * 1000
            assertTrue("${channel.id}: 1 ms older than its $seconds s is loaded", loaded(channel, threshold - 1))
            assertFalse("${channel.id}: exactly $seconds s ago is skipped (upstream's `<` is strict)", loaded(channel, threshold))
            assertFalse("${channel.id}: 1 ms newer is skipped", loaded(channel, threshold + 1))
        }
    }

    /** AC-6: never refreshed, and last refresh failed (no stored time), are not in cooldown, whatever value applies. */
    @Test
    fun ac6_select_neverRefreshedOrFailed_isLoaded_whateverItsCooldown() {
        val data = planData()
        val selection = select(all, data, 86400)

        assertEquals("no channel has a stored time: all are loaded", all, selection.load)
        assertEquals(emptyList<Long>(), selection.skipped)

        // The others were refreshed a second ago; one channel at a time has no time.
        for (channel in all) {
            data.last = all.filter { it != channel }.associate { it.id to ago(1) }
            val one = select(all, data, 86400)
            assertTrue("channel ${channel.id} without a stored time is loaded", channel in one.load)
            assertFalse(channel.id in one.skipped)
        }
    }

    /** AC-6 ("afterwards in cooldown like any other"): a channel refreshed in this very millisecond is skipped under every cooldown, as today. */
    @Test
    fun ac6_select_refreshedJustNow_isSkipped() {
        val data = planData(all.associate { it.id to NOW })
        val selection = select(all, data, 300)

        assertEquals(emptyList<Channel>(), selection.load)
        assertEquals(all.map { it.id }, selection.skipped)
    }

    /** Plan 4.3 step 3: `load` and `skipped` are in the order of the feed's query; every channel of the feed is in exactly one. */
    @Test
    fun ac5_select_keepsTheOrderOfTheScope() {
        val last = mapOf(a.id to ago(200), b.id to ago(3601), c.id to ago(1), d.id to ago(3601), e.id to ago(200))
        for (scope in listOf(all, all.reversed(), listOf(c, a, e, b, d), listOf(d, e, a, b, c))) {
            val selection = select(scope, planData(last), 300)

            assertEquals("loaded, in scope order", scope.filter { it == b || it == c }, selection.load)
            assertEquals("skipped, in scope order", scope.filter { it == a || it == d || it == e }.map { it.id }, selection.skipped)
        }
    }

    /** Plan 4.3, last row: a channel that is not in the feed is neither loaded nor skipped, whatever is stored about it. */
    @Test
    fun ac5_select_aChannelOutsideTheScope_isNeitherLoadedNorSkipped() {
        val last = all.associate { it.id to ago(200) } + (99L to ago(200)) + (98L to ago(999_999))
        val data = planData(last).apply { memberships = memberships + Membership(7, 99) + Membership(9, 98) }

        val selection = select(listOf(a, b), data, 300)

        assertEquals(emptyList<Channel>(), selection.load)
        assertEquals(listOf(a.id, b.id), selection.skipped)

        val nothing = select(emptyList(), data, 300)
        assertEquals(emptyList<Channel>(), nothing.load)
        assertEquals(emptyList<Long>(), nothing.skipped)
    }

    /**
     * AC-5 ("The result for a channel is the same whichever feed the refresh is started from"):
     * the same data, refreshed as "All", as each group's feed and as single channels: every
     * channel gets the same result in every feed it appears in. C is in the feeds of group 7
     * (3600 s) and group 9 ("Always update") and is loaded in both; B is skipped in group 7's
     * feed although C, of the same group, is loaded.
     */
    @Test
    fun ac5_select_theResultDoesNotDependOnTheFeed() {
        val last = mapOf(a.id to ago(200), b.id to ago(301), c.id to ago(301), d.id to ago(301), e.id to ago(301))
        val expectedLoaded = setOf(c.id, e.id)
        val feeds = linkedMapOf(
            "All" to all,
            "group 7" to listOf(b, c),
            "group 9" to listOf(c, d),
            "group 8" to listOf(e),
            "All, reversed" to all.reversed()
        ) + all.associate { "only ${it.id}" to listOf(it) }

        for ((name, scope) in feeds) {
            val selection = select(scope, planData(last), 300)
            assertEquals("feed `$name`: loaded", scope.filter { it.id in expectedLoaded }, selection.load)
            assertEquals("feed `$name`: skipped", scope.filter { it.id !in expectedLoaded }.map { it.id }, selection.skipped)
        }
    }

    /** AC-5: a channel in two groups gets the shorter value whichever membership and whichever group the database lists first. */
    @Test
    fun ac5_select_theShorterOfTwoGroups_whicheverIsListedFirst() {
        val memberships = listOf(Membership(7, c.id), Membership(9, c.id), Membership(7, b.id), Membership(9, d.id))
        val groups = listOf(music, news, sport)
        for (m in listOf(memberships, memberships.reversed())) {
            for (g in listOf(groups, groups.reversed())) {
                val data = planData().apply { this.memberships = m; this.groups = g }
                assertTrue("C, 1 s ago: \"Always update\" is the shorter", loaded(c, ago(1), data))
                assertFalse("B, 301 s ago: only in the 3600 s group", loaded(b, ago(301), data))
            }
        }

        // Both groups with a real duration: the shorter one decides, not the first and not the longer.
        val durations = planData().apply {
            groupValues = values(CooldownValues.groupKey(7, "Music") to 3600, CooldownValues.groupKey(9, "News") to 900)
            own = CooldownValues.EMPTY
        }
        for (m in listOf(memberships, memberships.reversed())) {
            durations.memberships = m
            assertTrue("C, 901 s ago: loaded by the 900 s of group 9", loaded(c, ago(901), durations))
            assertFalse("C, 899 s ago: skipped", loaded(c, ago(899), durations))
            assertFalse("B, 901 s ago: group 7 only, 3600 s", loaded(b, ago(901), durations))
            assertFalse("D, 899 s ago: group 9 only, 900 s", loaded(d, ago(899), durations))
            assertTrue("D, 901 s ago", loaded(d, ago(901), durations))
        }
    }

    /** AC-5: the channel's own value wins over its groups in both directions. */
    @Test
    fun ac5_select_ownValueBeatsTheGroups_inBothDirections() {
        val data = planData().apply { own = values(keyOf(d) to 86400, keyOf(b) to 0) }

        assertFalse("D: own day, group 9 on \"Always update\": skipped an hour later", loaded(d, ago(3601), data))
        assertTrue("B: own \"Always update\", group 7 on 3600 s: loaded a second later", loaded(b, ago(1), data))
        assertFalse("C has no own value: its groups decide; refreshed in this millisecond it is skipped", loaded(c, NOW, data))
    }

    /** AC-5: a group's value replaces the global cooldown also when the global one is shorter or "Always update". */
    @Test
    fun ac5_select_aGroupsValueReplacesTheGlobalOne() {
        val data = planData(mapOf(a.id to ago(200), b.id to ago(200), e.id to ago(200)))

        val always = select(all, data, 0)
        assertTrue("global \"Always update\": A is loaded", a in always.load)
        assertTrue("E, whose group has no value, is loaded", e in always.load)
        assertEquals("B is skipped by its group's 3600 s", listOf(b.id), always.skipped)

        val day = select(all, data, 86400)
        assertEquals("global one day: A, B and E were refreshed 200 s ago", listOf(a.id, b.id, e.id), day.skipped)
    }

    /** Plan 4.3 step 2 and section 10: without a group value the two group queries are not made; every function is called at most once. */
    @Test
    fun ac5_select_readsEachSourceOnce_andTheGroupsOnlyWhenAGroupHasAValue() {
        val withGroupValues = planData(mapOf(a.id to ago(200)))
        select(all, withGroupValues, 300)
        assertEquals("lastUpdated, memberships, groups, ownValues, groupValues", listOf(1, 1, 1, 1, 1), withGroupValues.calls)

        val without = planData(mapOf(a.id to ago(200))).apply { groupValues = CooldownValues.EMPTY }
        select(all, without, 300)
        assertEquals("no group value: memberships() and groups() are not called", listOf(1, 0, 0, 1, 1), without.calls)
    }

    /** A channel without an address (`url` is nullable in the database) is decided on like any other. */
    @Test
    fun ac5_select_aChannelWithoutAnAddress() {
        val noAddress = Channel(50, 2, null)
        val data = FakeData(last = mapOf(50L to ago(200)))

        assertEquals(listOf(50L), select(listOf(noAddress), data, 300).skipped)
        data.own = values(CooldownValues.subscriptionKey(2, null) to 0)
        assertEquals(listOf(noAddress), select(listOf(noAddress), data, 300).load)
    }

    // ---- 4.3 forced ----

    /** AC-9, plan 4.3: "Refresh anyway" loads exactly the listed channels, in the feed's order, and skips nothing. */
    @Test
    fun ac9_forced_loadsExactlyTheListedChannels_inScopeOrder() {
        val selection = CooldownRules.forced(all, { it.id }, longArrayOf(d.id, a.id))

        assertEquals(listOf(a, d), selection.load)
        assertEquals("AC-10: a forced refresh reports nothing as skipped", emptyList<Long>(), selection.skipped)
        assertEquals(listOf(d, a), CooldownRules.forced(all.reversed(), { it.id }, longArrayOf(d.id, a.id)).load)
        assertEquals(all, CooldownRules.forced(all, { it.id }, all.map { it.id }.reversed().toLongArray()).load)
    }

    /** AC-9 ("and nothing else"): a channel that is not listed is not loaded, also when its cooldown has long expired or it was never refreshed. */
    @Test
    fun ac9_forced_loadsNothingElse() {
        for (channel in all) {
            val selection = CooldownRules.forced(all, { it.id }, longArrayOf(channel.id))
            assertEquals("forced: only ${channel.id}", listOf(channel), selection.load)
            assertEquals(emptyList<Long>(), selection.skipped)
        }
        val none = CooldownRules.forced(all, { it.id }, longArrayOf())
        assertEquals("an empty set loads nothing", emptyList<Channel>(), none.load)
        assertEquals(emptyList<Long>(), none.skipped)
    }

    /** AC-9, plan 6.3 and Q5: a listed channel that was unsubscribed or left the group since the report is not in the scope and is not loaded. */
    @Test
    fun ac9_forced_idsThatLeftTheScopeAreIgnored() {
        val selection = CooldownRules.forced(listOf(a, c, e), { it.id }, longArrayOf(b.id, c.id, 999, d.id, -1, 0))

        assertEquals(listOf(c), selection.load)
        assertEquals(emptyList<Long>(), selection.skipped)
        assertEquals(emptyList<Channel>(), CooldownRules.forced(emptyList<Channel>(), { it.id }, longArrayOf(1, 2, 3)).load)
        assertEquals("an id listed twice is loaded once", listOf(a, c), CooldownRules.forced(all, { it.id }, longArrayOf(c.id, a.id, c.id, a.id)).load)
    }

    /**
     * AC-9: the round trip of AC-8 and AC-9: what a refresh skipped is exactly what "Refresh
     * anyway" loads, although every one of those channels is still in cooldown; together the
     * two refreshes load every channel of the feed once.
     */
    @Test
    fun ac9_forced_loadsExactlyWhatTheRefreshSkipped() {
        val last = mapOf(a.id to ago(200), b.id to ago(301), c.id to ago(1), d.id to ago(3601), e.id to ago(301))
        val first = select(all, planData(last), 300)
        assertEquals("the refresh skipped A, B and D", listOf(a.id, b.id, d.id), first.skipped)

        val anyway = CooldownRules.forced(all, { it.id }, first.skipped.toLongArray())

        assertEquals("the cooldown is ignored for exactly those", listOf(a, b, d), anyway.load)
        assertEquals(emptyList<Long>(), anyway.skipped)
        assertEquals(all.toSet(), (first.load + anyway.load).toSet())
        assertEquals(all.size, first.load.size + anyway.load.size)
    }

    /** AC-9 at the owner's size: 1600 channels, every second one forced. */
    @Test
    fun ac9_scale_forced_1600Channels() {
        val scope = (1..SUBSCRIPTIONS).map { Channel(it.toLong()) }
        var idCalls = 0
        val forcedIds = scope.filter { it.id % 2 == 0L }.map { it.id }.shuffled(java.util.Random(7)).toLongArray()

        val selection = CooldownRules.forced(scope, { idCalls++; it.id }, forcedIds)

        assertEquals(scope.filter { it.id % 2 == 0L }, selection.load)
        assertEquals(emptyList<Long>(), selection.skipped)
        assertTrue("the id of each channel is asked for a fixed number of times ($idCalls for 1600)", idCalls <= 2 * SUBSCRIPTIONS)
    }
}
