package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * AC-5 at the owner's size (plan 4.3 and section 10): 1600 subscriptions in 40 groups with
 * 4000 memberships, values on 10 groups and 100 channels.
 *
 * Two things are counted, nothing is timed:
 *
 * 1. **Queries.** Each function of [CooldownData] is one query or one preference read. A
 *    refresh makes upstream's scope query plus the calls counted here: 1 more without a group
 *    value (2 in all), 3 more with one (4 in all), whatever the number of channels.
 * 2. **Work per channel.** The data is handed over in collections that count how often they
 *    are read; the decision reads each row and looks each channel up a fixed number of times,
 *    so twice the channels cost twice the reads, not four times.
 */
class CooldownScaleTest {

    private class Feed(channelCount: Int, withGroupValues: Boolean, seed: Long = 1600) {
        val channels: List<Channel> = (1..channelCount).map { Channel(it.toLong(), it % 3) }
        val groups: List<GroupName> = (1..GROUPS).map { GroupName(it.toLong(), "Group $it") }

        /** Every channel is in two groups, every second one in a third: 2.5 memberships per channel (4000 for 1600). */
        val memberships: List<Membership> = channels.flatMap { channel ->
            val i = channel.id.toInt()
            listOfNotNull(
                Membership(1L + i % GROUPS, channel.id),
                Membership(1L + (i + 3) % GROUPS, channel.id),
                if (i % 2 == 0) Membership(1L + (i + 7) % GROUPS, channel.id) else null
            )
        }.shuffled(Random(seed))

        /** Groups 1 to 10 have a value, among them "Always update" and a day. */
        val secondsOfGroup: Map<Long, Int> = if (withGroupValues) (1..10L).associateWith { CHOICES[(it % CHOICES.size).toInt()] } else emptyMap()

        /** Every 16th channel has its own value: 100 of 1600. */
        val ownSeconds: Map<Long, Int> = channels.filter { it.id % 16 == 0L }.associate { it.id to CHOICES[((it.id / 16) % CHOICES.size).toInt()] }

        private val random = Random(seed)
        val last: Map<Long, Long> = channels.filter { it.id % 11 != 0L }.associate { it.id to NOW - (random.nextDouble() * 20_000_000).toLong() }

        val countingLast = CountingMap(last)
        val countingMemberships = CountingList(memberships)
        val countingGroups = CountingList(groups)
        val countingScope = CountingList(channels)
        var idCalls = 0L
        var keyCalls = 0L

        val data = FakeData(
            last = countingLast,
            memberships = countingMemberships,
            groups = countingGroups,
            own = ownSeconds.entries.fold(CooldownValues.EMPTY) { v, (id, s) -> v.with(keyOf(channels[id.toInt() - 1]), s) },
            groupValues = secondsOfGroup.entries.fold(CooldownValues.EMPTY) { v, (id, s) -> v.with(CooldownValues.groupKey(id, "Group $id"), s) }
        )

        fun select(global: Int = 300): Selection<Channel> =
            CooldownRules.select(countingScope, { idCalls++; it.id }, { keyCalls++; keyOf(it) }, data, global, NOW)

        /** The spec's terms, written out naively, channel by channel. */
        fun expectedLoaded(global: Int = 300): List<Channel> = channels.filter { channel ->
            val groupSeconds = memberships.filter { it.subscriptionId == channel.id }.mapNotNull { secondsOfGroup[it.groupId] }
            val effective = ownSeconds[channel.id] ?: groupSeconds.minOrNull() ?: global
            val lastUpdated = last[channel.id]
            lastUpdated == null || lastUpdated < NOW - effective * 1000L
        }

        /** Every counted read of the data. */
        val reads: Long get() = countingLast.lookups + countingLast.walks * last.size + countingMemberships.reads + countingGroups.reads + countingScope.reads + idCalls + keyCalls
    }

    /** The test's data is the size the plan names. */
    @Test
    fun scale_theDataIsTheOwnersSize() {
        val feed = Feed(SUBSCRIPTIONS, withGroupValues = true)

        assertEquals(1600, feed.channels.size)
        assertEquals(40, feed.groups.size)
        assertEquals(4000, feed.memberships.size)
        assertEquals(4000, feed.memberships.toSet().size)
        assertEquals(10, feed.data.groupValues.size)
        assertEquals(100, feed.data.own.size)
        assertTrue("some channels were never refreshed", feed.last.size in 1400 until 1600)
        val inTwoGroupsWithDifferentValues = feed.channels.count { channel ->
            feed.memberships.filter { it.subscriptionId == channel.id }.mapNotNull { feed.secondsOfGroup[it.groupId] }.toSet().size > 1
        }
        assertTrue("many channels are in two or three groups with different values ($inTwoGroupsWithDifferentValues)", inTwoGroupsWithDifferentValues > 200)
    }

    /** AC-5: 1600 channels with values on groups and channels: the result is the spec's rule applied to each channel, every channel loaded or skipped. */
    @Test
    fun ac5_scale_1600Channels_theResultIsTheRuleForEachChannel() {
        for (global in listOf(300, 0, 86400)) {
            val feed = Feed(SUBSCRIPTIONS, withGroupValues = true)

            val selection = feed.select(global)

            val expected = feed.expectedLoaded(global)
            assertEquals("global $global s: loaded", expected, selection.load)
            assertEquals("global $global s: skipped", feed.channels.filter { it !in expected.toSet() }.map { it.id }, selection.skipped)
            assertEquals("plan section 13: `load.size + skipped.size == 1600`", SUBSCRIPTIONS, selection.load.size + selection.skipped.size)
            assertTrue("the data makes the rule matter: ${selection.load.size} loaded, ${selection.skipped.size} skipped", selection.load.size > 100 && selection.skipped.size > 30)
        }
    }

    /** AC-5: the same at 1600 channels without any group value (only the channels' own values and the global one). */
    @Test
    fun ac5_scale_1600Channels_noGroupValues_theResultIsTheRuleForEachChannel() {
        val feed = Feed(SUBSCRIPTIONS, withGroupValues = false)

        val selection = feed.select()

        assertEquals(feed.expectedLoaded(), selection.load)
        assertEquals(SUBSCRIPTIONS, selection.load.size + selection.skipped.size)
    }

    /**
     * Plan section 10 ("Per manual refresh … 4 queries … Nothing per channel"): with group
     * values each of the five sources is read exactly once: 3 queries, 4 with upstream's scope
     * query. The same for 16 channels as for 1600.
     */
    @Test
    fun scale_withGroupValues_fourQueriesPerRefresh_whateverTheNumberOfChannels() {
        for (channelCount in listOf(16, 160, SUBSCRIPTIONS, 2 * SUBSCRIPTIONS)) {
            val feed = Feed(channelCount, withGroupValues = true)
            feed.select()

            assertEquals("$channelCount channels: lastUpdated, memberships, groups, ownValues, groupValues", listOf(1, 1, 1, 1, 1), feed.data.calls)
            assertEquals("$channelCount channels: 3 queries of this feature + upstream's scope query = 4", 4, feed.data.queries + 1)
        }
    }

    /** Plan section 10: without a group value the two group tables are not read: 1 query, 2 with upstream's scope query. */
    @Test
    fun scale_withoutGroupValues_twoQueriesPerRefresh_whateverTheNumberOfChannels() {
        for (channelCount in listOf(16, 160, SUBSCRIPTIONS, 2 * SUBSCRIPTIONS)) {
            val feed = Feed(channelCount, withGroupValues = false)
            feed.select()

            assertEquals("$channelCount channels: lastUpdated, memberships, groups, ownValues, groupValues", listOf(1, 0, 0, 1, 1), feed.data.calls)
            assertEquals("$channelCount channels: 1 query of this feature + upstream's scope query = 2", 2, feed.data.queries + 1)
            assertEquals("the group tables' rows are not read", 0L, feed.countingMemberships.reads + feed.countingGroups.reads)
        }
    }

    /**
     * Plan section 10 ("Then one pass over the scope with map lookups"): at 1600 channels each
     * row of each table is read at most twice, each channel is looked up at most twice, and its
     * id and key are asked for a fixed number of times.
     */
    @Test
    fun scale_1600Channels_everyRowAndChannelIsReadAFixedNumberOfTimes() {
        val feed = Feed(SUBSCRIPTIONS, withGroupValues = true)
        feed.select()

        assertTrue("the feed's rows: ${feed.countingScope.reads} reads of 1600", feed.countingScope.reads <= 2L * SUBSCRIPTIONS)
        assertTrue("the join table's rows: ${feed.countingMemberships.reads} reads of 4000", feed.countingMemberships.reads <= 2L * 4000)
        assertTrue("the groups: ${feed.countingGroups.reads} reads of 40", feed.countingGroups.reads <= 2L * GROUPS)
        assertTrue("the stored times: ${feed.countingLast.lookups} lookups for 1600 channels", feed.countingLast.lookups <= 2L * SUBSCRIPTIONS)
        assertTrue("the stored times are walked through at most twice (${feed.countingLast.walks})", feed.countingLast.walks <= 2)
        assertTrue("idOf: ${feed.idCalls} calls for 1600 channels", feed.idCalls <= 3L * SUBSCRIPTIONS)
        assertTrue("keyOf: ${feed.keyCalls} calls for 1600 channels", feed.keyCalls <= 2L * SUBSCRIPTIONS)
    }

    /** Linear, not quadratic: twice the channels (and memberships) cost at most twice the reads, from 200 up to 6400 channels. */
    @Test
    fun scale_twiceTheChannels_atMostTwiceTheReads() {
        for (withGroupValues in listOf(true, false)) {
            var previous = -1L
            for (channelCount in listOf(200, 400, 800, 1600, 3200, 6400)) {
                val feed = Feed(channelCount, withGroupValues)
                feed.select()
                val reads = feed.reads
                assertTrue("$channelCount channels are read at all ($reads)", reads >= channelCount)
                if (previous >= 0) {
                    assertTrue(
                        "group values: $withGroupValues; $channelCount channels cost $reads reads, half as many cost $previous: more than twice (+ the 40 groups)",
                        reads <= 2 * previous + 4 * GROUPS
                    )
                }
                previous = reads
            }
        }
    }
}
