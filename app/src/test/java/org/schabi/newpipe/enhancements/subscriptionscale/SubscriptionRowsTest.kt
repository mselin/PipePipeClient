package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.subscribers.TestSubscriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.database.subscription.SubscriptionEntity

/**
 * AC-4 and AC-5 (finding J3, plan 5.3): the channel list of the Subscriptions tab.
 *
 * `SubscriptionRows.listing(rows, gate)` emits a list only when the tab must show something
 * else: on screen for a change of the set of channels, a name or a picture; on a return also
 * for a change of a subscriber count (answer A2). Off screen it does not query.
 * The table is a [FakeTable]; every write hands out new row objects, as a query does.
 */
class SubscriptionRowsTest {

    private class Tab(initial: List<SubscriptionRow>?) {
        val table = FakeTable(initial)
        val gate = OnScreenGate()
        val shown: TestSubscriber<List<SubscriptionRow>> = SubscriptionRows.listing(table.flowable(), gate).test()

        fun emissions(): Int = shown.values().size
        fun last(): List<SubscriptionRow> = shown.values().last()
    }

    private fun onScreenTab(initial: List<SubscriptionRow>): Tab {
        val tab = Tab(initial)
        tab.gate.setOnScreen(true)
        assertEquals("the list as first shown", 1, tab.emissions())
        return tab
    }

    private fun List<SubscriptionRow>.with(uid: Long, change: (SubscriptionRow) -> SubscriptionRow) =
        map { if (it.uid == uid) change(it) else it.copy() }

    // ---- sameNamesAndPictures ----

    @Test
    fun ac4_sameNamesAndPictures_equalLists() {
        assertTrue(SubscriptionRows.sameNamesAndPictures(rows(5), rows(5)))
        assertTrue(SubscriptionRows.sameNamesAndPictures(emptyList(), emptyList()))
    }

    @Test
    fun ac4_sameNamesAndPictures_ignoresTheSubscriberCount() {
        val old = rows(5)

        assertTrue(
            "AC-4: a list that differs only in subscriber counts shows the same names and pictures",
            SubscriptionRows.sameNamesAndPictures(old, old.map { it.copy(subscriberCount = it.uid * 7) })
        )
        assertTrue(SubscriptionRows.sameNamesAndPictures(old, old.map { it.copy(subscriberCount = null) }))
    }

    @Test
    fun ac4_sameNamesAndPictures_seesEachOfTheFiveFields() {
        val old = rows(5)
        val changes = mapOf<String, (SubscriptionRow) -> SubscriptionRow>(
            "uid" to { it.copy(uid = 99) },
            "serviceId" to { it.copy(serviceId = 4) },
            "url" to { it.copy(url = "https://example.invalid/moved") },
            "name" to { it.copy(name = "Renamed") },
            "avatarUrl" to { it.copy(avatarUrl = "https://img.invalid/new.jpg") },
            "url to null" to { it.copy(url = null) },
            "name to null" to { it.copy(name = null) },
            "avatarUrl to null" to { it.copy(avatarUrl = null) }
        )
        for ((field, change) in changes) {
            for (position in listOf(0, 2, 4)) {
                val new = old.mapIndexed { i, row -> if (i == position) change(row) else row }
                assertFalse("AC-4: a change of $field at position $position is seen", SubscriptionRows.sameNamesAndPictures(old, new))
                assertFalse("AC-4: also the other way round ($field)", SubscriptionRows.sameNamesAndPictures(new, old))
            }
        }
    }

    @Test
    fun ac4_sameNamesAndPictures_nullFieldsAreEqualToNull() {
        val bare = listOf(SubscriptionRow(1, 0, null, null, null, null))

        assertTrue(SubscriptionRows.sameNamesAndPictures(bare, listOf(SubscriptionRow(1, 0, null, null, null, 12))))
    }

    @Test
    fun ac5_sameNamesAndPictures_seesInsertDeleteAndReorder() {
        val old = rows(5)

        assertFalse("AC-5: one channel more", SubscriptionRows.sameNamesAndPictures(old, rows(6)))
        assertFalse("AC-5: one channel fewer", SubscriptionRows.sameNamesAndPictures(old, rows(4)))
        assertFalse("AC-5: against an empty list", SubscriptionRows.sameNamesAndPictures(old, emptyList()))
        assertFalse("AC-5: from an empty list", SubscriptionRows.sameNamesAndPictures(emptyList(), old))
        assertFalse("AC-4: same channels, other order", SubscriptionRows.sameNamesAndPictures(old, old.reversed()))
        assertFalse(
            "AC-5: same size, one channel replaced by another",
            SubscriptionRows.sameNamesAndPictures(old, old.dropLast(1) + row(77))
        )
    }

    // ---- listing: on screen ----

    @Test
    fun ac4_onScreen_aCountOnlyChange_emitsNothing() {
        val tab = onScreenTab(rows(5))

        tab.table.write(rows(5).map { it.copy(subscriberCount = 1) })
        tab.table.write(rows(5).map { it.copy(subscriberCount = null) })

        assertEquals("AC-4: no rebuild for a write that changes only subscriber counts", 1, tab.emissions())
    }

    @Test
    fun ac4_onScreen_anUnchangedReload_emitsNothing() {
        val tab = onScreenTab(rows(5))

        repeat(3) { tab.table.write(rows(5)) }

        assertEquals("AC-4: no rebuild for a write that changes nothing", 1, tab.emissions())
    }

    @Test
    fun ac4_onScreen_aNameChange_isEmitted() {
        val tab = onScreenTab(rows(5))
        val renamed = rows(5).with(3) { it.copy(name = "Renamed") }

        tab.table.write(renamed)

        assertEquals("AC-4: a changed name rebuilds the list", 2, tab.emissions())
        assertEquals(renamed, tab.last())
    }

    @Test
    fun ac4_onScreen_aPictureChange_isEmitted() {
        val tab = onScreenTab(rows(5))
        val repainted = rows(5).with(3) { it.copy(avatarUrl = "https://img.invalid/new.jpg") }

        tab.table.write(repainted)

        assertEquals("AC-4: a changed picture rebuilds the list", 2, tab.emissions())
        assertEquals(repainted, tab.last())
    }

    @Test
    fun ac5_onScreen_subscribing_isEmitted() {
        val tab = onScreenTab(rows(5))

        tab.table.write(rows(6))

        assertEquals("AC-5: a new subscription shows at once", 2, tab.emissions())
        assertEquals(6, tab.last().size)
    }

    @Test
    fun ac5_onScreen_unsubscribing_isEmitted() {
        val tab = onScreenTab(rows(5))
        val without3 = rows(5).filter { it.uid != 3L }

        tab.table.write(without3)

        assertEquals("AC-5: an unsubscribed channel leaves at once", 2, tab.emissions())
        assertEquals(without3, tab.last())
    }

    @Test
    fun ac5_onScreen_unsubscribingTheLastChannel_isEmitted() {
        val tab = onScreenTab(rows(1))

        tab.table.write(emptyList())

        assertEquals(2, tab.emissions())
        assertEquals(emptyList<SubscriptionRow>(), tab.last())
    }

    @Test
    fun ac4_onScreen_aReorder_isEmitted() {
        val tab = onScreenTab(rows(5))
        // channel 1 renamed so that it sorts last
        val reordered = rows(5).drop(1) + row(1, name = "Zebra")

        tab.table.write(reordered)

        assertEquals("AC-4: another order rebuilds the list", 2, tab.emissions())
        assertEquals(reordered, tab.last())
    }

    /** The next visible change brings the counts along (question Q2: "or with the next such change"). */
    @Test
    fun ac4_onScreen_aNameChangeAfterCountChanges_carriesTheNewCounts() {
        val tab = onScreenTab(rows(5))
        val newCounts = rows(5).map { it.copy(subscriberCount = 5) }
        tab.table.write(newCounts)
        assertEquals(1, tab.emissions())

        val renamed = newCounts.with(2) { it.copy(name = "Renamed") }
        tab.table.write(renamed)

        assertEquals(2, tab.emissions())
        assertEquals(renamed, tab.last())
    }

    /** A change and its undo are two visible changes. */
    @Test
    fun ac4_onScreen_renameAndBack_emitsTwice() {
        val tab = onScreenTab(rows(5))

        tab.table.write(rows(5).with(2) { it.copy(name = "Renamed") })
        tab.table.write(rows(5))

        assertEquals(3, tab.emissions())
        assertEquals(rows(5), tab.last())
    }

    // ---- listing: off screen and return ----

    /** A tab never shown has its list ready when first opened, as today (answer A3). */
    @Test
    fun ac4_neverShown_theFirstLoadIsDelivered() {
        val tab = Tab(rows(5))

        assertEquals(1, tab.emissions())
        assertEquals(rows(5), tab.last())
        assertEquals("AC-4: after the first load the hidden tab does not observe the table", 0, tab.table.observers)
    }

    @Test
    fun ac4_offScreen_theSourceIsNotSubscribed() {
        val tab = onScreenTab(rows(5))
        tab.gate.setOnScreen(false)
        val queries = tab.table.queries

        tab.table.write(rows(6))
        tab.table.write(rows(6).with(1) { it.copy(name = "Renamed") })

        assertEquals("AC-4: no work while the tab is not on screen", 0, tab.table.observers)
        assertEquals(queries, tab.table.queries)
        assertEquals(1, tab.emissions())
    }

    @Test
    fun ac4_returnAfterACountOnlyChange_oneEmissionWithTheNewCounts() {
        val tab = onScreenTab(rows(5))
        tab.gate.setOnScreen(false)
        val newCounts = rows(5).map { it.copy(subscriberCount = it.uid * 3) }
        tab.table.write(newCounts)

        tab.gate.setOnScreen(true)

        assertEquals("AC-4: subscriber counts catch up when the tab is returned to", 2, tab.emissions())
        assertEquals(newCounts, tab.last())
    }

    /** Counts that changed while the tab was on screen (and were held back) also arrive with the return. */
    @Test
    fun ac4_returnAfterACountChangeSeenOnScreen_oneEmissionWithTheNewCounts() {
        val tab = onScreenTab(rows(5))
        val newCounts = rows(5).map { it.copy(subscriberCount = it.uid * 3) }
        tab.table.write(newCounts)
        assertEquals("held back while on screen", 1, tab.emissions())

        tab.gate.setOnScreen(false)
        tab.gate.setOnScreen(true)

        assertEquals("AC-4: subscriber counts catch up when the tab is returned to", 2, tab.emissions())
        assertEquals(newCounts, tab.last())
    }

    @Test
    fun ac4_returnUnchanged_emitsNothing() {
        val tab = onScreenTab(rows(5))
        val queries = tab.table.queries

        repeat(3) {
            tab.gate.setOnScreen(false)
            tab.gate.setOnScreen(true)
        }

        assertEquals("AC-4: a return without any change does not rebuild the list", 1, tab.emissions())
        assertEquals("AC-4: each return costs one query and a comparison", queries + 3, tab.table.queries)
    }

    @Test
    fun ac4_returnAfterAnUnchangedReload_emitsNothing() {
        val tab = onScreenTab(rows(5))
        tab.gate.setOnScreen(false)
        tab.table.write(rows(5))

        tab.gate.setOnScreen(true)

        assertEquals(1, tab.emissions())
    }

    /** Subscribed on a channel page, then Back (plan device step 6). */
    @Test
    fun ac5_returnAfterSubscribingElsewhere_showsTheChannel() {
        val tab = onScreenTab(rows(5))
        tab.gate.setOnScreen(false)
        tab.table.write(rows(6))

        tab.gate.setOnScreen(true)

        assertEquals("AC-5: the channel subscribed elsewhere is there on return", 2, tab.emissions())
        assertEquals(rows(6), tab.last())
    }

    @Test
    fun ac5_returnAfterUnsubscribingElsewhere_theChannelIsGone() {
        val tab = onScreenTab(rows(5))
        tab.gate.setOnScreen(false)
        tab.table.write(rows(4))

        tab.gate.setOnScreen(true)

        assertEquals(2, tab.emissions())
        assertEquals(rows(4), tab.last())
    }

    /** First show of a tab loaded while hidden: the load is not shown twice. */
    @Test
    fun ac4_firstShowOfATabLoadedWhileHidden_emitsNothingMore() {
        val tab = Tab(rows(5))
        assertEquals(1, tab.emissions())

        tab.gate.setOnScreen(true)

        assertEquals("AC-4: the list loaded while hidden is not rebuilt on first show", 1, tab.emissions())
    }

    // ---- the row ----

    @Test
    fun ac4_toChannelInfoItem_carriesTheFieldsTheRowShows() {
        val item = SubscriptionRow(42, 3, "https://example.invalid/c", "Some Channel", "https://img.invalid/c.jpg", 12345).toChannelInfoItem()

        assertEquals(3, item.serviceId)
        assertEquals("https://example.invalid/c", item.url)
        assertEquals("Some Channel", item.name)
        assertEquals("https://img.invalid/c.jpg", item.thumbnailUrl)
        assertEquals(12345L, item.subscriberCount)
        assertNull("plan 5.3: channel rows lose `description`", item.description)
    }

    @Test
    fun ac4_toChannelInfoItem_aNullCountIsMinusOne() {
        val item = SubscriptionRow(42, 3, "https://example.invalid/c", "Some Channel", null, null).toChannelInfoItem()

        assertEquals("unknown subscriber count, as `SubscriptionEntity.toChannelInfoItem` has it", -1L, item.subscriberCount)
        assertNull(item.thumbnailUrl)
    }

    /** The row gives what the entity gives today, minus the description. */
    @Test
    fun ac4_toChannelInfoItem_matchesTheEntity() {
        val entity = SubscriptionEntity()
        entity.uid = 42
        entity.serviceId = 3
        entity.url = "https://example.invalid/c"
        entity.name = "Some Channel"
        entity.avatarUrl = "https://img.invalid/c.jpg"
        entity.subscriberCount = 777L
        entity.description = "not carried"
        val today = entity.toChannelInfoItem()

        val item = SubscriptionRow(42, 3, entity.url, entity.name, entity.avatarUrl, entity.subscriberCount).toChannelInfoItem()

        assertEquals(today.serviceId, item.serviceId)
        assertEquals(today.url, item.url)
        assertEquals(today.name, item.name)
        assertEquals(today.thumbnailUrl, item.thumbnailUrl)
        assertEquals(today.subscriberCount, item.subscriberCount)
    }

    /** A row is a value: Groupie item reuse (ReusingMapper) and the outer filter compare rows with `==`. */
    @Test
    fun ac4_rowsAreComparedByValue_allSixFields() {
        assertEquals(row(1), row(1))
        assertEquals(row(1).hashCode(), row(1).hashCode())
        assertFalse("the subscriber count is part of the row", row(1) == row(1).copy(subscriberCount = 0))
        assertFalse(row(1) == row(1).copy(name = "x"))
    }

    // ---- the query ----

    /** Plan section 2: the query text. Six columns under the row's property names, no `description`, the order of today's list. */
    @Test
    fun ac4_query_isTheSlimOneOfThePlan() {
        assertEquals(
            "SELECT uid, service_id AS serviceId, url, name, avatar_url AS avatarUrl, subscriber_count AS subscriberCount " +
                "FROM subscriptions ORDER BY name COLLATE NOCASE ASC",
            SUBSCRIPTION_ROWS_QUERY
        )
    }

    @Test
    fun ac4_query_usesTheColumnNamesOfTheEntity() {
        val q = SUBSCRIPTION_ROWS_QUERY
        assertTrue(q.startsWith("SELECT ${SubscriptionEntity.SUBSCRIPTION_UID}, "))
        assertTrue(q.contains(" ${SubscriptionEntity.SUBSCRIPTION_SERVICE_ID} AS serviceId,"))
        assertTrue(q.contains(" ${SubscriptionEntity.SUBSCRIPTION_URL},"))
        assertTrue(q.contains(" ${SubscriptionEntity.SUBSCRIPTION_NAME},"))
        assertTrue(q.contains(" ${SubscriptionEntity.SUBSCRIPTION_AVATAR_URL} AS avatarUrl,"))
        assertTrue(q.contains(" ${SubscriptionEntity.SUBSCRIPTION_SUBSCRIBER_COUNT} AS subscriberCount "))
        assertTrue(q.contains(" FROM ${SubscriptionEntity.SUBSCRIPTION_TABLE} "))
        assertFalse("AC-4: the description is not loaded", q.contains(SubscriptionEntity.SUBSCRIPTION_DESCRIPTION))
        assertFalse(q.contains("*"))
    }

    // ---- at the owner's scale ----

    /**
     * Finding J3: an "All" refresh of 1600 channels writes 80 batches of 20, each rewriting the
     * subscriber counts of its channels. Before: 80 rebuilds of 1600 rows. Now, with the tab on
     * screen: none.
     */
    @Test
    fun ac4_scale_aRefreshOf1600Channels_onScreen_noRebuild() {
        val tab = onScreenTab(rows(SUBSCRIPTIONS))
        var current = rows(SUBSCRIPTIONS)

        for (batch in 0 until SUBSCRIPTIONS / BATCH) {
            val refreshed = (batch * BATCH + 1L)..((batch + 1L) * BATCH)
            current = current.map { if (it.uid in refreshed) it.copy(subscriberCount = it.uid * 11) else it.copy() }
            tab.table.write(current)
        }

        assertEquals("AC-4: 80 batches that change only counts: the list is not rebuilt at all", 1, tab.emissions())

        // unless a name changed: the 81st write renames one channel
        tab.table.write(current.with(800) { it.copy(name = "Channel 0800 (renamed)") })
        assertEquals("AC-4: one name change, one rebuild", 2, tab.emissions())
        assertEquals("the rebuilt list has the counts of the refresh", 1600L * 11, tab.last().last().subscriberCount)
    }

    /** `SubscriptionsFetchService` writes once per channel: 1600 writes, each a count change. */
    @Test
    fun ac4_scale_1600PerChannelWrites_onScreen_noRebuild() {
        val tab = onScreenTab(rows(SUBSCRIPTIONS))
        var current = rows(SUBSCRIPTIONS)

        for (uid in 1L..SUBSCRIPTIONS) {
            current = current.with(uid) { it.copy(subscriberCount = -5) }
            tab.table.write(current)
        }

        assertEquals(1, tab.emissions())
    }

    @Test
    fun ac4_scale_aRefreshOf1600Channels_offScreen_noQuery_oneRebuildOnReturn() {
        val tab = onScreenTab(rows(SUBSCRIPTIONS))
        tab.gate.setOnScreen(false)
        val queries = tab.table.queries
        var current = rows(SUBSCRIPTIONS)

        for (batch in 0 until SUBSCRIPTIONS / BATCH) {
            val refreshed = (batch * BATCH + 1L)..((batch + 1L) * BATCH)
            current = current.map { if (it.uid in refreshed) it.copy(subscriberCount = it.uid * 11) else it.copy() }
            tab.table.write(current)
        }
        assertEquals("AC-4: 80 batches off screen: no query", queries, tab.table.queries)

        tab.gate.setOnScreen(true)

        assertEquals("AC-4: the return costs one query", queries + 1, tab.table.queries)
        assertEquals("AC-4: and one rebuild, with the new counts", 2, tab.emissions())
        assertEquals(current, tab.last())
    }

    @Test
    fun ac4_scale_sameNamesAndPictures_on1600Rows() {
        val old = rows(SUBSCRIPTIONS)

        assertTrue(SubscriptionRows.sameNamesAndPictures(old, rows(SUBSCRIPTIONS).map { it.copy(subscriberCount = 0) }))
        assertFalse(
            "AC-4: a change in the very last row is seen",
            SubscriptionRows.sameNamesAndPictures(old, rows(SUBSCRIPTIONS).with(SUBSCRIPTIONS.toLong()) { it.copy(name = "Renamed") })
        )
        assertFalse(SubscriptionRows.sameNamesAndPictures(old, rows(SUBSCRIPTIONS).with(1) { it.copy(avatarUrl = null) }))
    }
}
