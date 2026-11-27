package org.schabi.newpipe.enhancements.refreshcooldown

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.database.Converters
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Random

/**
 * AC-11 as an equivalence test (plan 5.2): "With every group and subscription on *Use default*,
 * refreshing skips and refreshes the same channels as today."
 *
 * **Today's rule, restated here from upstream** (`FeedLoadManager.kt:88-98` and the three
 * queries of `FeedDAO.kt`, e.g. `getAllOutdated`): the loader computes
 * `outdatedThreshold = OffsetDateTime.now(UTC).minusSeconds(seconds)`, Room stores and compares
 * times as epoch milliseconds (`Converters.offsetDateTimeToTimestamp`), and a subscription is
 * loaded when
 *
 * ```sql
 * lu.last_updated IS NULL OR lu.last_updated < :outdatedThreshold
 * ```
 *
 * where a subscription without a row in `feed_last_updated` has `NULL` through the `LEFT JOIN`.
 * [loadedToday] is that rule, with the threshold converted by upstream's own [Converters].
 * The order of the result is the query's, here the order of the scope.
 *
 * The plan's side: `CooldownRefresh.queryThreshold` keeps `now.toInstant().toEpochMilli()` and
 * the setting's seconds and hands them to `CooldownRules.select` (plan 5.1). So the test gives
 * `select` exactly that and compares with the restated rule, for generated channels and times
 * and for every choice of the global setting.
 *
 * What a pass does not prove: that the glue passes these two numbers, that the query with
 * `now.plusYears(100)` returns the whole scope, and that Room's second query reads the times
 * the first would have compared (wiring test, instrumented `CooldownQueriesTest`, device step 11).
 */
class CooldownEqualsTodayTest {

    private val converters = Converters()

    /** A subscription and its row of `feed_last_updated`: [lastUpdated] is `null` for no row, and for a row with `NULL` (a failed refresh). */
    private class Row(val channel: Channel, val lastUpdated: OffsetDateTime?)

    /** Upstream's rule: the subscriptions today's query returns, in its order. */
    private fun loadedToday(rows: List<Row>, now: OffsetDateTime, seconds: Int): List<Channel> {
        val outdatedThreshold: Long = converters.offsetDateTimeToTimestamp(now.minusSeconds(seconds.toLong()))!!
        return rows.filter { row ->
            val lastUpdated: Long? = converters.offsetDateTimeToTimestamp(row.lastUpdated)
            lastUpdated == null || lastUpdated < outdatedThreshold
        }.map { it.channel }
    }

    /** What `RoomCooldownData.lastUpdatedMillis()` gives for these rows: "rows with a non-null time, as `toInstant().toEpochMilli()`". */
    private fun lastUpdatedMillis(rows: List<Row>): Map<Long, Long> =
        rows.filter { it.lastUpdated != null }.associate { it.channel.id to it.lastUpdated!!.toInstant().toEpochMilli() }

    private fun assertSameAsToday(what: String, rows: List<Row>, now: OffsetDateTime, seconds: Int, data: FakeData = FakeData()) {
        data.last = lastUpdatedMillis(rows)
        val scope = rows.map { it.channel }
        val today = loadedToday(rows, now, seconds)

        val selection = select(scope, data, seconds, now.toInstant().toEpochMilli())

        assertEquals("AC-11, $what, global $seconds s: the loaded channels, in the query's order", today, selection.load)
        assertEquals("AC-11, $what, global $seconds s: the skipped channels are the rest", scope.filter { it !in today }.map { it.id }, selection.skipped)
    }

    private fun atMillis(millis: Long): OffsetDateTime = converters.offsetDateTimeFromTimestamp(millis)!!

    /**
     * AC-11: for each of the seven global choices, channels refreshed 1 ms before, exactly at
     * and 1 ms after `now - seconds`, never, with a failed refresh, just now and in the future.
     */
    @Test
    fun ac11_noValues_everyGlobalChoice_aroundTheBoundary_equalsToday() {
        val now = OffsetDateTime.of(2026, 11, 19, 10, 23, 45, 678_901_234, ZoneOffset.UTC)
        val nowMillis = now.toInstant().toEpochMilli()
        for (seconds in CHOICES) {
            val boundary = nowMillis - seconds * 1000L
            val times = listOf(
                boundary - 1, boundary, boundary + 1, boundary - 1000, boundary + 1000, nowMillis - 1, nowMillis, nowMillis + 1,
                nowMillis + 5000, nowMillis - 86_400_001, nowMillis - 86_400_000, nowMillis - 86_399_999, 0L, 1L, null, null
            )
            val rows = times.mapIndexed { index, time -> Row(Channel(index + 1L), time?.let { atMillis(it) }) }
            val data = FakeData()

            assertSameAsToday("around the boundary", rows, now, seconds, data)

            assertEquals("AC-11, plan 4.3: with no group value the group tables are not read", listOf(0, 0), listOf(data.membershipCalls, data.groupCalls))
            assertEquals("one query for the times", 1, data.lastUpdatedCalls)
            // the three rows at the boundary, spelled out: only the first is loaded
            val loaded = select(rows.map { it.channel }, data, seconds, nowMillis).load.map { it.id }
            assertEquals("global $seconds s: 1 ms older than the threshold is loaded, at and after it not", listOf(true, false, false), listOf(1L in loaded, 2L in loaded, 3L in loaded))
        }
    }

    /**
     * AC-11: generated feeds. 200 feeds of up to 60 channels, times spread over the two days
     * before `now` and a little after it, with a share of channels never refreshed, a share
     * exactly on a choice's boundary, and `now` with a random nanosecond part and offset; every
     * global choice.
     */
    @Test
    fun ac11_noValues_generatedChannelsAndTimes_everyGlobalChoice_equalsToday() {
        val random = Random(11)
        var loadedSomewhere = 0
        var skippedSomewhere = 0
        repeat(200) { round ->
            val now = OffsetDateTime.of(2026, 1 + random.nextInt(12), 1 + random.nextInt(28), random.nextInt(24), random.nextInt(60), random.nextInt(60), random.nextInt(1_000_000_000), ZoneOffset.ofHours(random.nextInt(25) - 12))
            val nowMillis = now.toInstant().toEpochMilli()
            val rows = (1..random.nextInt(61)).map { index ->
                val time: Long? = when (random.nextInt(10)) {
                    0, 1 -> null
                    2 -> nowMillis - CHOICES[random.nextInt(CHOICES.size)] * 1000L + (random.nextInt(3) - 1)
                    3 -> nowMillis + random.nextInt(10_000)
                    4 -> nowMillis - random.nextInt(400_000)
                    else -> nowMillis - (random.nextDouble() * 2 * 86_400_000).toLong()
                }
                Row(Channel(round * 100L + index, random.nextInt(3)), time?.let { atMillis(it) })
            }.shuffled(random)
            for (seconds in CHOICES) {
                assertSameAsToday("generated feed $round", rows, now, seconds)
                val today = loadedToday(rows, now, seconds)
                loadedSomewhere += today.size
                skippedSomewhere += rows.size - today.size
            }
        }
        assertEquals("the generated feeds have both loaded and skipped channels ($loadedSomewhere, $skippedSomewhere)", true, loadedSomewhere > 1000 && skippedSomewhere > 1000)
    }

    /** AC-11 at the owner's size: 1600 subscriptions, every global choice, one query for the times and none for the groups. */
    @Test
    fun ac11_scale_1600Subscriptions_noValues_equalsToday() {
        val random = Random(1600)
        val now = OffsetDateTime.of(2026, 11, 19, 21, 0, 0, 999_999_999, ZoneOffset.UTC)
        val nowMillis = now.toInstant().toEpochMilli()
        val rows = (1..SUBSCRIPTIONS).map { index ->
            val time: Long? = if (index % 9 == 0) null else nowMillis - (random.nextDouble() * 100_000_000).toLong()
            Row(Channel(index.toLong()), time?.let { atMillis(it) })
        }
        for (seconds in CHOICES) {
            val data = FakeData()
            assertSameAsToday("1600 subscriptions", rows, now, seconds, data)
            assertEquals("plan section 10: lastUpdated, memberships, groups, ownValues, groupValues", listOf(1, 0, 0, 1, 1), data.calls)
        }
    }

    /**
     * AC-11 and AC-4: stored values that belong to nothing (a deleted group, a group with
     * another name, an unsubscribed channel) do not change which channels are loaded. Here the
     * group tables may be read (a group value exists); the result is still today's.
     */
    @Test
    fun ac11_staleValuesOnly_equalsToday() {
        val random = Random(4)
        val now = OffsetDateTime.of(2026, 11, 19, 8, 0, 0, 5_000_000, ZoneOffset.UTC)
        val nowMillis = now.toInstant().toEpochMilli()
        val rows = (1..300).map { index ->
            Row(Channel(index.toLong()), if (index % 7 == 0) null else atMillis(nowMillis - (random.nextDouble() * 90_000_000).toLong()))
        }
        val memberships = rows.map { Membership(1 + it.channel.id % 5, it.channel.id) }
        val groups = (1..5L).map { GroupName(it, "Group $it") }
        for (seconds in CHOICES) {
            val data = FakeData(
                memberships = memberships,
                groups = groups,
                own = values(CooldownValues.subscriptionKey(0, "https://example.invalid/channel/unsubscribed") to 0, CooldownValues.subscriptionKey(9, rows[0].channel.url) to 86400),
                groupValues = values(CooldownValues.groupKey(6, "Group 6") to 0, CooldownValues.groupKey(1, "Another name") to 86400, CooldownValues.groupKey(2, "group 2") to 0)
            )
            assertSameAsToday("only stale values", rows, now, seconds, data)
        }
    }

    /**
     * Plan section 12: "Stored times are epoch milliseconds; `minusSeconds(s)` then conversion
     * equals conversion minus `s * 1000`", for a `now` with nanoseconds. It is why the plan may
     * compute the threshold from `now` in milliseconds.
     */
    @Test
    fun ac11_thresholdInMilliseconds_isWhatUpstreamsConversionGives() {
        val random = Random(12)
        repeat(2000) {
            val now = OffsetDateTime.of(2026, 1 + random.nextInt(12), 1 + random.nextInt(28), random.nextInt(24), random.nextInt(60), random.nextInt(60), random.nextInt(1_000_000_000), ZoneOffset.ofHours(random.nextInt(25) - 12))
            for (seconds in CHOICES) {
                assertEquals(
                    "now = $now, $seconds s",
                    now.toInstant().toEpochMilli() - seconds * 1000L,
                    converters.offsetDateTimeToTimestamp(now.minusSeconds(seconds.toLong()))
                )
            }
        }
    }
}
