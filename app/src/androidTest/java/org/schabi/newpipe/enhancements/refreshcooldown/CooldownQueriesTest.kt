package org.schabi.newpipe.enhancements.refreshcooldown

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.feed.model.FeedLastUpdatedEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * AC-5, AC-6, AC-11, AC-4 (plan 5.1, 5.2 and section 9): the three queries the plan adds to
 * `FeedDAO` (`cooldownLastUpdated()`, `cooldownMemberships()`, `cooldownGroups()`) on a real,
 * in-memory [AppDatabase], next to upstream's own scope queries.
 *
 * What the JVM tests cannot show, and these do: that Room accepts the three queries and maps
 * the rows; that a failed refresh is a row with `NULL` and a never refreshed channel has no
 * row; that the times they return are the integers upstream's query compares, so that the
 * decision made in memory from them equals upstream's SQL on the same database (AC-11); that
 * upstream's queries return their whole scope for a threshold 100 years ahead (plan 5.1); and
 * that a deleted group's id is not given to a later group (plan 3.3, assumed there).
 *
 * What a pass does not prove: nothing about `RoomCooldownData`, `CooldownRefresh` or the
 * loader's chain (the tests read the DAO themselves, as the plan says `RoomCooldownData` does),
 * nothing about the thread the loader calls the queries on, and nothing about a database that
 * was imported from a backup.
 *
 * Instrumented: needs a device or emulator. Not run by CI, only compiled; run it with the
 * plan's device script. It uses only what `androidTest` already depends on. It does not compile
 * until the Code layer has added the three functions to `FeedDAO`.
 */
@RunWith(AndroidJUnit4::class)
class CooldownQueriesTest {

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun subscribe(name: String, serviceId: Int = 0, notifications: Boolean = false): Long {
        val entity = SubscriptionEntity()
        entity.serviceId = serviceId
        entity.url = "https://example.invalid/channel/$name"
        entity.name = name
        entity.notificationMode = if (notifications) NotificationMode.ENABLED else NotificationMode.DISABLED
        return database.subscriptionDAO().insert(entity)
    }

    private fun group(name: String, vararg subscriptionIds: Long): Long {
        val id = database.feedGroupDAO().insert(FeedGroupEntity(0, name, FeedGroupIcon.ALL))
        database.feedGroupDAO().updateSubscriptionsForGroup(id, subscriptionIds.toList())
        return id
    }

    private fun at(millis: Long): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    /** A successful refresh: `FeedDatabaseManager.upsertAll` ends with this (`FeedDatabaseManager.kt:116-118`). */
    private fun refreshedAt(subscriptionId: Long, millis: Long) =
        database.feedDAO().setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, at(millis)))

    /** A failed refresh: `FeedDatabaseManager.markAsOutdated` (`:78-79`). */
    private fun failed(subscriptionId: Long) =
        database.feedDAO().setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, null))

    /** The data of a decision, read as plan 5.1 says `RoomCooldownData` reads it. */
    private fun data(own: CooldownValues = CooldownValues.EMPTY, groupValues: CooldownValues = CooldownValues.EMPTY) = object : CooldownData {
        override fun lastUpdatedMillis(): Map<Long, Long> = database.feedDAO().cooldownLastUpdated()
            .filter { it.lastUpdated != null }.associate { it.subscriptionId to it.lastUpdated!!.toInstant().toEpochMilli() }
        override fun memberships(): List<Membership> = database.feedDAO().cooldownMemberships().map { Membership(it.feedGroupId, it.subscriptionId) }
        override fun groups(): List<GroupName> = database.feedDAO().cooldownGroups().map { GroupName(it.uid, it.name) }
        override fun ownValues(): CooldownValues = own
        override fun groupValues(): CooldownValues = groupValues
    }

    private fun select(scope: List<SubscriptionEntity>, data: CooldownData, globalSeconds: Int, nowMillis: Long): Selection<SubscriptionEntity> =
        CooldownRules.select(scope, { it.uid }, { CooldownValues.subscriptionKey(it.serviceId, it.url) }, data, globalSeconds, nowMillis)

    // ---- the three queries ----

    /**
     * AC-6: `cooldownLastUpdated()` returns every row of `feed_last_updated`: a refreshed
     * channel with its time to the millisecond, a failed one with `null`; a channel that was
     * never refreshed has no row.
     */
    @Test
    fun ac6_cooldownLastUpdated_timeForRefreshed_nullForFailed_noRowForNever() {
        val refreshed = subscribe("refreshed")
        val failedOne = subscribe("failed")
        val never = subscribe("never")
        refreshedAt(refreshed, 1_795_000_000_123L)
        refreshedAt(failedOne, 1_795_000_000_456L)
        failed(failedOne)

        val rows = database.feedDAO().cooldownLastUpdated().associateBy { it.subscriptionId }

        assertEquals(setOf(refreshed, failedOne), rows.keys)
        assertEquals("the stored time, to the millisecond", 1_795_000_000_123L, rows.getValue(refreshed).lastUpdated!!.toInstant().toEpochMilli())
        assertNull("AC-6: a failed refresh leaves NULL", rows.getValue(failedOne).lastUpdated)
        assertTrue("AC-6: never refreshed: no row", never !in rows.keys)
        assertEquals("AC-6: only the refreshed channel has a time for the decision", mapOf(refreshed to 1_795_000_000_123L), data().lastUpdatedMillis())
    }

    /** An empty database: the three queries return empty lists. */
    @Test
    fun ac5_theThreeQueries_emptyDatabase() {
        assertEquals(emptyList<FeedLastUpdatedEntity>(), database.feedDAO().cooldownLastUpdated())
        assertEquals(emptyList<FeedGroupSubscriptionEntity>(), database.feedDAO().cooldownMemberships())
        assertEquals(emptyList<FeedGroupEntity>(), database.feedDAO().cooldownGroups())
    }

    /** AC-5: `cooldownMemberships()` returns the memberships of all groups, whatever feed is refreshed. */
    @Test
    fun ac5_cooldownMemberships_allGroups() {
        val a = subscribe("a")
        val b = subscribe("b")
        val c = subscribe("c")
        val music = group("Music", a, b)
        val news = group("News", b, c)
        group("Empty")

        assertEquals(
            setOf(music to a, music to b, news to b, news to c),
            database.feedDAO().cooldownMemberships().map { it.feedGroupId to it.subscriptionId }.toSet()
        )
        assertEquals(4, database.feedDAO().cooldownMemberships().size)
    }

    /** AC-2, AC-4: `cooldownGroups()` returns every group with its id and its name as stored, also after a rename. */
    @Test
    fun ac4_cooldownGroups_idsAndStoredNames() {
        val music = group("Music")
        val news = group("News")

        assertEquals(setOf(music to "Music", news to "News"), database.feedDAO().cooldownGroups().map { it.uid to it.name }.toSet())

        val renamed = database.feedGroupDAO().getGroup(music).blockingGet()!!
        renamed.name = "Rock"
        database.feedGroupDAO().update(renamed)

        assertEquals(setOf(music to "Rock", news to "News"), database.feedDAO().cooldownGroups().map { it.uid to it.name }.toSet())
        assertEquals(
            "AC-4: the key of the renamed group is another one: a value stored for \"Music\" no longer matches",
            listOf(CooldownValues.groupKey(music, "Rock")),
            database.feedDAO().cooldownGroups().filter { it.uid == music }.map { CooldownValues.groupKey(it.uid, it.name) }
        )
    }

    /**
     * AC-4, plan 3.3 ("no group will ever have that id again (AUTOINCREMENT)", assumed in
     * section 12): a deleted group's id is not given to the next group, also when it was the
     * newest group and when all groups were deleted; the same for a subscription.
     */
    @Test
    fun ac4_aDeletedGroupsIdIsNotReused() {
        val first = group("Music")
        val second = group("News")
        database.feedGroupDAO().delete(second)
        val third = group("Sport")
        assertTrue("the newest group was deleted: the next id ($third) is above it ($second)", third > second)

        database.feedGroupDAO().deleteAll()
        val fourth = group("Music")
        assertTrue("all groups were deleted: the next id ($fourth) is above every earlier one", fourth > third && fourth > first)

        val channel = subscribe("gone")
        database.subscriptionDAO().deleteAll()
        val next = subscribe("gone")
        assertTrue("a subscription's id is not reused either ($next after $channel)", next > channel)
    }

    // ---- with upstream's queries ----

    /**
     * Plan 5.1: "With that threshold upstream's query returns every subscription of its scope":
     * for `now.plusYears(100)` each of the three scope queries returns all its subscriptions,
     * refreshed a moment ago, failed and never refreshed alike.
     */
    @Test
    fun ac5_upstreamsScopeQueries_returnTheWholeScope_forAThreshold100YearsAhead() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val a = subscribe("a", notifications = true)
        val b = subscribe("b")
        val c = subscribe("c", notifications = true)
        val d = subscribe("d")
        val music = group("Music", a, b)
        refreshedAt(a, now.toInstant().toEpochMilli())
        refreshedAt(b, now.toInstant().toEpochMilli() + 5_000)
        failed(c)
        val threshold = now.plusYears(100)

        assertEquals(setOf(a, b, c, d), database.feedDAO().getAllOutdated(threshold).blockingFirst().map { it.uid }.toSet())
        assertEquals(setOf(a, b), database.feedDAO().getAllOutdatedForGroup(music, threshold).blockingFirst().map { it.uid }.toSet())
        assertEquals(setOf(a, c), database.feedDAO().getOutdatedWithNotificationMode(threshold, NotificationMode.ENABLED).blockingFirst().map { it.uid }.toSet())
    }

    /**
     * AC-11 on a real database: with no values, the channels `CooldownRules.select` loads from
     * the whole scope and `cooldownLastUpdated()` are the channels upstream's own query returns
     * for `now.minusSeconds(seconds)`, for every choice of the global setting and for times
     * 1 ms before, at and 1 ms after each boundary, never, failed and in the future.
     */
    @Test
    fun ac11_noValues_theDecisionInMemory_equalsUpstreamsQuery() {
        val now = OffsetDateTime.of(2026, 11, 19, 10, 23, 45, 678_901_234, ZoneOffset.UTC)
        val nowMillis = now.toInstant().toEpochMilli()
        val choices = listOf(0, 300, 900, 3600, 21600, 43200, 86400)
        var index = 0
        database.runInTransaction {
            for (seconds in choices) for (offset in listOf(-1L, 0L, 1L)) refreshedAt(subscribe("s${index++}"), nowMillis - seconds * 1000L + offset)
            subscribe("never")
            refreshedAt(subscribe("future"), nowMillis + 5_000)
            refreshedAt(subscribe("old"), nowMillis - 200_000_000L)
            failed(subscribe("failed"))
        }
        val scope = database.feedDAO().getAllOutdated(now.plusYears(100)).blockingFirst()
        assertEquals(choices.size * 3 + 4, scope.size)

        for (seconds in choices) {
            val today = database.feedDAO().getAllOutdated(now.minusSeconds(seconds.toLong())).blockingFirst().map { it.uid }.toSet()

            val selection = select(scope, data(), seconds, nowMillis)

            assertEquals("AC-11, global $seconds s: loaded", today, selection.load.map { it.uid }.toSet())
            assertEquals("AC-11, global $seconds s: the rest is skipped", scope.map { it.uid }.toSet() - today, selection.skipped.toSet())
            assertTrue("the data makes the rule matter", today.isNotEmpty() && selection.skipped.isNotEmpty())
        }
    }

    /**
     * AC-5 on a real database: the channels of plan 4.3 (B and C in "Music" with one hour, C
     * also in "News" with "Always update", D in "News" with its own day, E in a group without a
     * value), read through the three queries: the same result from "All" and from each group's
     * own scope query.
     */
    @Test
    fun ac5_valuesOnGroupsAndChannels_theSameResultFromEveryFeed() {
        val now = OffsetDateTime.of(2026, 11, 19, 12, 0, 0, 0, ZoneOffset.UTC)
        val nowMillis = now.toInstant().toEpochMilli()
        val a = subscribe("a")
        val b = subscribe("b")
        val c = subscribe("c")
        val d = subscribe("d")
        val e = subscribe("e")
        val music = group("Music", b, c)
        val news = group("News", c, d)
        val sport = group("Sport", e)
        refreshedAt(a, nowMillis - 200_000)
        for (id in listOf(b, c, d, e)) refreshedAt(id, nowMillis - 301_000)
        val data = data(
            own = CooldownValues.EMPTY.with(CooldownValues.subscriptionKey(0, "https://example.invalid/channel/d"), 86400),
            groupValues = CooldownValues.EMPTY.with(CooldownValues.groupKey(music, "Music"), 3600).with(CooldownValues.groupKey(news, "News"), 0)
        )
        val threshold = now.plusYears(100)
        val expectedLoaded = setOf(c, e)

        val feeds = mapOf(
            "All" to database.feedDAO().getAllOutdated(threshold).blockingFirst(),
            "Music" to database.feedDAO().getAllOutdatedForGroup(music, threshold).blockingFirst(),
            "News" to database.feedDAO().getAllOutdatedForGroup(news, threshold).blockingFirst(),
            "Sport" to database.feedDAO().getAllOutdatedForGroup(sport, threshold).blockingFirst()
        )
        assertEquals(listOf(5, 2, 2, 1), feeds.values.map { it.size })
        for ((name, scope) in feeds) {
            val selection = select(scope, data, 300, nowMillis)
            assertEquals("feed $name: loaded", scope.map { it.uid }.filter { it in expectedLoaded }.toSet(), selection.load.map { it.uid }.toSet())
            assertEquals("feed $name: skipped", scope.map { it.uid }.filter { it !in expectedLoaded }.toSet(), selection.skipped.toSet())
        }
    }

    /** AC-9: a channel refreshed again by "Refresh anyway" has a new time and is in cooldown like any other; a failed one is not. */
    @Test
    fun ac9_afterARefresh_theChannelIsInCooldown_afterAFailureItIsNot() {
        val nowMillis = 1_795_000_000_000L
        val ok = subscribe("ok")
        val bad = subscribe("bad")
        refreshedAt(ok, nowMillis - 100_000)
        refreshedAt(bad, nowMillis - 100_000)
        val scope = database.feedDAO().getAllOutdated(at(nowMillis).plusYears(100)).blockingFirst()
        assertEquals("both are skipped by the refresh", setOf(ok, bad), select(scope, data(), 300, nowMillis).skipped.toSet())

        // "Refresh anyway": one succeeds, one fails.
        refreshedAt(ok, nowMillis + 1_000)
        failed(bad)

        val next = select(scope, data(), 300, nowMillis + 2_000)
        assertEquals("AC-9: afterwards in cooldown like any other refreshed channel", listOf(ok), next.skipped)
        assertEquals("AC-6: the failed one is not in cooldown", listOf(bad), next.load.map { it.uid })
    }

    /** The owner's database: 1600 subscriptions, 40 groups, 4000 memberships: each query returns all its rows in one call. */
    @Test
    fun scale_1600Subscriptions40Groups_theThreeQueriesReturnAllRows() {
        val ids = ArrayList<Long>()
        database.runInTransaction {
            for (i in 1..1600) ids.add(subscribe("Channel %04d".format(i)))
            for (g in 0 until 40) {
                val members = ids.filterIndexed { i, _ -> i % 40 == g || (i + 13) % 40 == g || (i % 2 == 0 && (i + 27) % 40 == g) }
                group("Group $g", *members.toLongArray())
            }
            for ((i, id) in ids.withIndex()) if (i % 11 != 0) refreshedAt(id, 1_795_000_000_000L - i * 60_000L)
        }

        assertEquals(1600 - 146, database.feedDAO().cooldownLastUpdated().size)
        assertEquals(4000, database.feedDAO().cooldownMemberships().size)
        assertEquals(40, database.feedDAO().cooldownGroups().size)

        val scope = database.feedDAO().getAllOutdated(at(1_795_000_000_000L).plusYears(100)).blockingFirst()
        val selection = select(scope, data(), 3600, 1_795_000_000_000L)
        assertEquals(1600, scope.size)
        assertEquals(1600, selection.load.size + selection.skipped.size)
    }
}
