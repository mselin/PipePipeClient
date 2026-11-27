package org.schabi.newpipe.enhancements.subscriptionscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-4 and AC-5 (finding J3, plan 5.3): the list items of the Subscriptions tab are reused.
 *
 * `ReusingMapper.map(rows)` "returns the same item for a row equal to the one last mapped under
 * that id, a new one otherwise, and forgets ids that are gone". Groupie compares instances, so
 * a reused item is a row that is neither diffed as changed nor bound again.
 */
class ReusingMapperTest {

    /** Stands for `ChannelItem`: no `equals`, so only the instance counts. */
    private class Item(val row: SubscriptionRow)

    private var created = 0
    private val mapper = ReusingMapper<SubscriptionRow, Item>({ it.uid }) { created++; Item(it) }

    @Test
    fun ac4_firstMap_createsOneItemPerRow_inInputOrder() {
        val input = listOf(row(3), row(1), row(2))

        val items = mapper.map(input)

        assertEquals(3, created)
        assertEquals("the items follow the order of the rows", listOf(3L, 1L, 2L), items.map { it.row.uid })
    }

    @Test
    fun ac4_emptyList_mapsToAnEmptyList() {
        assertTrue(mapper.map(emptyList()).isEmpty())
        assertEquals(0, created)
    }

    @Test
    fun ac4_anEqualRow_givesTheSameInstance() {
        val first = mapper.map(rows(3))

        val second = mapper.map(rows(3)) // new, equal row objects, as a new query result has

        assertEquals("AC-4: nothing is created for an unchanged list", 3, created)
        for (i in 0..2) assertSame("AC-4: row ${i + 1} keeps its item", first[i], second[i])
    }

    @Test
    fun ac4_aChangedRow_givesANewInstance_theOthersStay() {
        val first = mapper.map(rows(3))

        val second = mapper.map(listOf(row(1), row(2, name = "Renamed"), row(3)))

        assertEquals("AC-4: one item is created for the one changed row", 4, created)
        assertSame(first[0], second[0])
        assertNotSame("AC-4: the changed row gets a new item", first[1], second[1])
        assertEquals("Renamed", second[1].row.name)
        assertSame(first[2], second[2])
    }

    /** Any of the six fields: the count too, which is how counts catch up on return (answer A2). */
    @Test
    fun ac4_aChangedSubscriberCountOrPicture_givesANewInstance() {
        val first = mapper.map(rows(3))

        val second = mapper.map(listOf(row(1, count = 5), row(2), row(3, avatar = "https://img.invalid/new.jpg")))

        assertNotSame(first[0], second[0])
        assertEquals(5L, second[0].row.subscriberCount)
        assertSame(first[1], second[1])
        assertNotSame(first[2], second[2])
        assertEquals(5, created)
    }

    /** "Equal to the one last mapped under that id": the item for the changed row is reused from then on. */
    @Test
    fun ac4_afterAChange_theNewInstanceIsTheOneReused() {
        mapper.map(rows(3))
        val changed = mapper.map(listOf(row(1), row(2, name = "Renamed"), row(3)))

        val again = mapper.map(listOf(row(1), row(2, name = "Renamed"), row(3)))

        assertSame(changed[1], again[1])
        assertEquals(4, created)
    }

    /** Changed and changed back: the row last mapped under the id was the changed one, so a new item. */
    @Test
    fun ac4_changedAndChangedBack_givesANewInstanceEachTime() {
        val first = mapper.map(rows(3))
        mapper.map(listOf(row(1), row(2, name = "Renamed"), row(3)))

        val back = mapper.map(rows(3))

        assertNotSame("the first item of row 2 was forgotten when the row changed", first[1], back[1])
        assertEquals("the original name is shown again", "Channel 0002", back[1].row.name)
        assertEquals(5, created)
    }

    @Test
    fun ac5_subscribing_createsOneItem_theOthersStay() {
        val first = mapper.map(listOf(row(1), row(3)))

        val second = mapper.map(rows(3))

        assertEquals("AC-5: subscribing to one channel creates one item", 3, created)
        assertSame("AC-5: and rebinds no other row", first[0], second[0])
        assertSame(first[1], second[2])
        assertEquals(2L, second[1].row.uid)
    }

    @Test
    fun ac5_unsubscribing_createsNothing_theOthersStay() {
        val first = mapper.map(rows(3))

        val second = mapper.map(listOf(row(1), row(3)))

        assertEquals("AC-5: unsubscribing creates nothing", 3, created)
        assertEquals(2, second.size)
        assertSame(first[0], second[0])
        assertSame(first[2], second[1])
    }

    @Test
    fun ac5_aRemovedId_isForgotten() {
        val first = mapper.map(rows(3))
        mapper.map(listOf(row(1), row(3)))

        val resubscribed = mapper.map(rows(3))

        assertNotSame("AC-5: an id that was gone gets a new item, also for an equal row", first[1], resubscribed[1])
        assertEquals(4, created)
        assertSame(first[0], resubscribed[0])
        assertSame(first[2], resubscribed[2])
    }

    @Test
    fun ac5_afterAnEmptyList_everythingIsForgotten() {
        val first = mapper.map(rows(3))
        mapper.map(emptyList())

        val second = mapper.map(rows(3))

        assertEquals(6, created)
        for (i in 0..2) assertNotSame(first[i], second[i])
    }

    @Test
    fun ac4_aReorder_keepsTheItemsAndFollowsTheNewOrder() {
        val first = mapper.map(rows(3))

        val second = mapper.map(rows(3).reversed())

        assertEquals("AC-4: a reorder creates nothing", 3, created)
        assertSame(first[0], second[2])
        assertSame(first[1], second[1])
        assertSame(first[2], second[0])
    }

    /** The id decides, not the position: another channel at the same position is another item. */
    @Test
    fun ac5_anotherIdAtTheSamePosition_isANewItem() {
        val first = mapper.map(rows(3))

        val second = mapper.map(listOf(row(1), row(9, name = "Channel 0002"), row(3)))

        assertNotSame(first[1], second[1])
        assertEquals(4, created)
    }

    @Test
    fun ac4_twoMappers_doNotShareItems() {
        val other = ReusingMapper<SubscriptionRow, Item>({ it.uid }) { Item(it) }

        assertNotSame(mapper.map(rows(1))[0], other.map(rows(1))[0])
    }

    // ---- at the owner's scale ----

    /** A row type that counts how often it is compared, to pin the work of one `map` call. */
    private class CountedRow(val id: Long, val name: String, private val counter: IntArray) {
        override fun equals(other: Any?): Boolean {
            counter[0]++
            return other is CountedRow && other.id == id && other.name == name
        }

        override fun hashCode(): Int = id.hashCode() * 31 + name.hashCode()
    }

    /**
     * 1600 channels remapped: the work is in proportion to the list, not to its square. Pinned
     * by counting: at most 4 calls of `idOf` and 4 row comparisons per row and call of `map`
     * (the direct implementation needs one of each); looking each row up in a list would need
     * about 1600 x 1600 / 2 = 1.28 million (question Q7).
     */
    @Test
    fun ac4_scale_remapping1600Rows_isLinearWork() {
        val comparisons = IntArray(1)
        var idCalls = 0
        var creations = 0
        val counted = ReusingMapper<CountedRow, Any>({ idCalls++; it.id }) { creations++; Any() }
        fun list() = (1L..SUBSCRIPTIONS).map { CountedRow(it, "Channel $it", comparisons) }

        counted.map(list())
        assertEquals(SUBSCRIPTIONS, creations)
        assertTrue("first map: $idCalls calls of idOf for 1600 rows", idCalls <= 4 * SUBSCRIPTIONS)

        idCalls = 0
        comparisons[0] = 0
        counted.map(list())

        assertEquals("AC-4: remapping 1600 unchanged rows creates nothing", SUBSCRIPTIONS, creations)
        assertTrue("AC-4: remap: $idCalls calls of idOf for 1600 rows", idCalls <= 4 * SUBSCRIPTIONS)
        assertTrue("AC-4: remap: ${comparisons[0]} row comparisons for 1600 rows", comparisons[0] <= 4 * SUBSCRIPTIONS)
        assertTrue("each row is compared with its predecessor", comparisons[0] >= SUBSCRIPTIONS)
    }

    @Test
    fun ac5_scale_subscribingToOneChannelAmong1600_createsOneItemAndKeeps1600() {
        val before = mapper.map(rows(SUBSCRIPTIONS))
        assertEquals(SUBSCRIPTIONS, created)
        val added = row(5000, name = "Channel 0800 b")
        val withNew = rows(SUBSCRIPTIONS).let { it.take(800) + added + it.drop(800) }

        val after = mapper.map(withNew)

        assertEquals("AC-5: one item created for the one new channel", SUBSCRIPTIONS + 1, created)
        assertEquals(SUBSCRIPTIONS + 1, after.size)
        assertEquals(5000L, after[800].row.uid)
        val kept = before.toSet()
        assertEquals("AC-5: the 1600 others keep their items: Groupie rebinds none of them", SUBSCRIPTIONS, after.count { it in kept })
        for (i in 0 until 800) assertSame(before[i], after[i])
        for (i in 800 until SUBSCRIPTIONS) assertSame(before[i], after[i + 1])
    }

    @Test
    fun ac5_scale_unsubscribingFromOneChannelAmong1600_createsNothing() {
        val before = mapper.map(rows(SUBSCRIPTIONS))

        val after = mapper.map(rows(SUBSCRIPTIONS).filter { it.uid != 1234L })

        assertEquals(SUBSCRIPTIONS, created)
        assertEquals(SUBSCRIPTIONS - 1, after.size)
        assertEquals(SUBSCRIPTIONS - 1, after.count { it === before[(it.row.uid - 1).toInt()] })
    }

    /** "Forgets ids that are gone": the mapper holds items for the current list only. */
    @Test
    fun ac4_scale_theMapperForgetsAListThatIsGone() {
        mapper.map(rows(SUBSCRIPTIONS))
        mapper.map(rows(3))

        mapper.map(rows(SUBSCRIPTIONS))

        assertEquals("ids 4..1600 were forgotten and are created again; 1..3 are reused", SUBSCRIPTIONS + (SUBSCRIPTIONS - 3), created)
    }

    // ---- the tab's pipeline: table -> listing -> map (plan 5.3), with RxJava for real ----

    private class Pipeline(initial: List<SubscriptionRow>) {
        val table = FakeTable(initial)
        val gate = OnScreenGate()
        var created = 0
        var rebuilds = 0
        var shown: List<Item> = emptyList()
        private val mapper = ReusingMapper<SubscriptionRow, Item>({ it.uid }) { created++; Item(it) }

        init {
            SubscriptionRows.listing(table.flowable(), gate)
                .map { rows -> mapper.map(rows) }
                .subscribe { rebuilds++; shown = it }
        }
    }

    /**
     * Device step 5b on the JVM: the tab on screen during a refresh of a 100-channel group (five
     * batches of 20 that rewrite subscriber counts). Before: 5 x 1600 `ChannelItem`s created.
     * Now: none, and the list is not handed to the adapter at all.
     */
    @Test
    fun ac4_scale_pipeline_groupRefreshOnScreen_createsNoItem() {
        val tab = Pipeline(rows(SUBSCRIPTIONS))
        tab.gate.setOnScreen(true)
        assertEquals(SUBSCRIPTIONS, tab.created)
        assertEquals("first load while hidden; the first show adds no rebuild", 1, tab.rebuilds)
        val shownBefore = tab.shown

        var current = rows(SUBSCRIPTIONS)
        for (batch in 0 until 5) {
            val refreshed = (batch * BATCH + 1L)..((batch + 1L) * BATCH)
            current = current.map { if (it.uid in refreshed) it.copy(subscriberCount = 9) else it.copy() }
            tab.table.write(current)
        }

        assertEquals("AC-4: no item created during the refresh", SUBSCRIPTIONS, tab.created)
        assertEquals("AC-4: no list delivered during the refresh", 1, tab.rebuilds)
        assertSame(shownBefore, tab.shown)
    }

    /** The same refresh seen from elsewhere, then the return: the 100 refreshed rows catch up, 1500 keep their items. */
    @Test
    fun ac4_scale_pipeline_returnAfterAGroupRefresh_recreatesOnlyTheRefreshedRows() {
        val tab = Pipeline(rows(SUBSCRIPTIONS))
        tab.gate.setOnScreen(true)
        tab.gate.setOnScreen(false)
        val before = tab.shown
        val queries = tab.table.queries

        var current = rows(SUBSCRIPTIONS)
        for (batch in 0 until 5) {
            val refreshed = (batch * BATCH + 1L)..((batch + 1L) * BATCH)
            current = current.map { if (it.uid in refreshed) it.copy(subscriberCount = 9) else it.copy() }
            tab.table.write(current)
        }
        assertEquals("AC-4: no list query while the tab is not on screen", queries, tab.table.queries)
        assertEquals(SUBSCRIPTIONS, tab.created)

        tab.gate.setOnScreen(true)

        assertEquals("AC-4: the return is one query", queries + 1, tab.table.queries)
        assertEquals("AC-4: and one list", 2, tab.rebuilds)
        assertEquals("AC-4: items are created only for the 100 rows whose count changed", SUBSCRIPTIONS + 100, tab.created)
        assertEquals(1500, tab.shown.indices.count { tab.shown[it] === before[it] })
        assertEquals(9L, tab.shown[0].row.subscriberCount)
    }

    /** AC-5 on the tab itself: unsubscribe by long-press, then subscribe again, among 1600. */
    @Test
    fun ac5_scale_pipeline_unsubscribeAndSubscribe_showAtOnceAndTouchOneRow() {
        val tab = Pipeline(rows(SUBSCRIPTIONS))
        tab.gate.setOnScreen(true)

        tab.table.write(rows(SUBSCRIPTIONS).filter { it.uid != 77L })
        assertEquals("AC-5: the row leaves at once", 2, tab.rebuilds)
        assertEquals(SUBSCRIPTIONS - 1, tab.shown.size)
        assertEquals("AC-5: nothing is created for an unsubscribe", SUBSCRIPTIONS, tab.created)

        tab.table.write(rows(SUBSCRIPTIONS))
        assertEquals("AC-5: the row is back at once", 3, tab.rebuilds)
        assertEquals(SUBSCRIPTIONS, tab.shown.size)
        assertEquals("AC-5: one item is created for the one channel", SUBSCRIPTIONS + 1, tab.created)
    }
}
