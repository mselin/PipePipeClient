package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AC-3 and AC-4 (findings J2 and J3, plan 5.1): a screen that is not on screen does not query
 * the database, and shows current data when it is returned to.
 *
 * `OnScreenGate.gate(source)`: off screen it lets the first load through and then leaves the
 * source without a subscriber; on screen it subscribes again and passes everything. The source
 * is a [FakeTable], which behaves like a Room `Flowable`; one value handed out = one query.
 * RxJava runs for real.
 */
class OnScreenGateTest {

    @Test
    fun ac3_offScreen_theFirstItemIsDelivered_thenTheSourceHasNoSubscriber() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()

        val screen = gate.gate(table.flowable()).test()

        screen.assertValues("v1")
        assertEquals("AC-3: after the first load the table observer is removed", 0, table.observers)
        assertEquals("AC-3: the first load is one query", 1, table.queries)
        screen.assertNotComplete().assertNoErrors()
    }

    @Test
    fun ac3_offScreen_laterItemsAreNeitherQueriedNorDelivered() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()

        table.write("v2")
        table.write("v3")

        screen.assertValues("v1")
        assertEquals("AC-3: no query for a write while off screen", 1, table.queries)
        assertEquals(1, table.subscriptions)
    }

    /** The first load may take a while: the gate waits for it off screen, and stops after it. */
    @Test
    fun ac3_offScreen_theFirstItemMayArriveLater() {
        val table = FakeTable<String>()
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()
        screen.assertNoValues()
        assertEquals("AC-3: the first load is running", 1, table.observers)

        table.write("v1")
        table.write("v2")

        screen.assertValues("v1")
        assertEquals(0, table.observers)
        assertEquals(1, table.queries)
    }

    /** A screen that never reports (the settings group picker) gets its data as today. */
    @Test
    fun ac3_aScreenThatNeverReports_getsItsFirstLoad() {
        val table = FakeTable(listOf("Music", "News"))

        val picker = OnScreenGate().gate(table.flowable()).test()

        picker.assertValues(listOf("Music", "News"))
    }

    @Test
    fun ac3_onScreen_oneNewSubscription_theCurrentItemIsDelivered() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()
        table.write("v2")
        table.write("v3")

        gate.setOnScreen(true)

        screen.assertValues("v1", "v3")
        assertEquals("AC-3: returning subscribes once more", 2, table.subscriptions)
        assertEquals("AC-3: returning costs one query", 2, table.queries)
        assertEquals(1, table.observers)
    }

    @Test
    fun ac3_onScreen_everythingIsPassed() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()
        gate.setOnScreen(true)

        table.write("v2")
        table.write("v2")
        table.write("v3")

        // No distinctUntilChanged in the gate: a group edit can leave the values equal and must still reload (AC-5).
        screen.assertValues("v1", "v1", "v2", "v2", "v3")
        assertEquals("AC-3: still the one subscription", 2, table.subscriptions)
    }

    @Test
    fun ac3_offAgain_unsubscribed_laterItemsNotDelivered() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()
        gate.setOnScreen(true)
        table.write("v2")
        val queriesOnScreen = table.queries

        gate.setOnScreen(false)
        table.write("v3")
        table.write("v4")

        assertEquals("AC-3: leaving removes the table observer", 0, table.observers)
        assertEquals("AC-3: no query after leaving", queriesOnScreen, table.queries)
        assertEquals("AC-3: leaving does not start another first load", 2, table.subscriptions)
        screen.assertValues("v1", "v1", "v2")
        screen.assertNotComplete().assertNoErrors()
    }

    @Test
    fun ac3_everyReturn_subscribesAgainAndShowsCurrentData() {
        val table = FakeTable("v0")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()

        for (visit in 1..3) {
            table.write("v$visit")
            gate.setOnScreen(true)
            gate.setOnScreen(false)
        }

        screen.assertValues("v0", "v1", "v2", "v3")
        assertEquals("AC-3: one subscription for the first load and one per return", 4, table.subscriptions)
        assertEquals(4, table.queries)
        assertEquals(0, table.observers)
    }

    /** A return shows the current value even if it equals what the screen got before. */
    @Test
    fun ac3_returnWithoutAChange_deliversTheCurrentItemAgain() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()

        gate.setOnScreen(true)

        screen.assertValues("v1", "v1")
    }

    /** Plan section 10, "first show of a hidden tab": the load restarts once, one list is delivered. */
    @Test
    fun ac3_onScreenBeforeTheFirstItem_oneDelivery() {
        val table = FakeTable<String>()
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()

        gate.setOnScreen(true)
        assertEquals("AC-3: the off-screen load is replaced, not doubled", 1, table.observers)
        assertEquals(2, table.subscriptions)
        table.write("v1")

        screen.assertValues("v1")
        assertEquals(1, table.queries)
    }

    @Test
    fun ac3_onScreenBeforeTheStreamIsSubscribed_oneSubscription() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        gate.setOnScreen(true)

        val screen = gate.gate(table.flowable()).test()
        table.write("v2")

        screen.assertValues("v1", "v2")
        assertEquals(1, table.subscriptions)
    }

    @Test
    fun ac3_offReportedBeforeAnything_stillAllowsTheFirstLoad() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        gate.setOnScreen(false)

        val screen = gate.gate(table.flowable()).test()
        table.write("v2")

        screen.assertValues("v1")
        assertEquals(1, table.subscriptions)
    }

    /** onResume twice (or onPause twice) changes nothing: `distinctUntilChanged` on the reports. */
    @Test
    fun ac3_theSameReportTwice_doesNotSubscribeAgain() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()

        gate.setOnScreen(true)
        gate.setOnScreen(true)
        gate.setOnScreen(true)
        assertEquals("AC-3: a repeated 'on screen' is ignored", 2, table.subscriptions)
        gate.setOnScreen(false)
        gate.setOnScreen(false)
        gate.setOnScreen(true)

        assertEquals(3, table.subscriptions)
        screen.assertValues("v1", "v1", "v1")
    }

    /**
     * AC-4: the Subscriptions tab puts two streams through one gate (its groups and its channel
     * list, plan 5.3). "Nothing delivered yet" is per gated stream: the first stream's load must
     * not take away the second stream's (question Q5).
     */
    @Test
    fun ac4_oneGateTwoStreams_eachGetsItsFirstLoadOffScreen() {
        val groups = FakeTable("groups 1")
        val channels = FakeTable("channels 1")
        val gate = OnScreenGate()

        val groupsScreen = gate.gate(groups.flowable()).test()
        val channelsScreen = gate.gate(channels.flowable()).test()
        groups.write("groups 2")
        channels.write("channels 2")

        groupsScreen.assertValues("groups 1")
        channelsScreen.assertValues("channels 1")
        assertEquals(0, groups.observers + channels.observers)
    }

    @Test
    fun ac4_oneGateTwoStreams_bothFollowTheScreen() {
        val groups = FakeTable("groups 1")
        val channels = FakeTable("channels 1")
        val gate = OnScreenGate()
        val groupsScreen = gate.gate(groups.flowable()).test()
        val channelsScreen = gate.gate(channels.flowable()).test()

        gate.setOnScreen(true)
        groups.write("groups 2")
        channels.write("channels 2")
        gate.setOnScreen(false)
        groups.write("groups 3")
        channels.write("channels 3")

        groupsScreen.assertValues("groups 1", "groups 1", "groups 2")
        channelsScreen.assertValues("channels 1", "channels 1", "channels 2")
        assertEquals(0, groups.observers + channels.observers)
    }

    /** Two screens, two gates: one on screen does not wake the other. */
    @Test
    fun ac3_gatesAreIndependent() {
        val table = FakeTable("v1")
        val shown = OnScreenGate()
        val hidden = OnScreenGate()
        val shownScreen = shown.gate(table.flowable()).test()
        val hiddenScreen = hidden.gate(table.flowable()).test()

        shown.setOnScreen(true)
        table.write("v2")

        shownScreen.assertValues("v1", "v1", "v2")
        hiddenScreen.assertValues("v1")
    }

    /** The ViewModels have an error branch (`ErrorState`); a failing query still reaches it. */
    @Test
    fun ac3_anErrorOfTheSource_isPassedOn() {
        val table = FakeTable("v1")
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()
        gate.setOnScreen(true)
        val failure = IllegalStateException("query failed")

        table.fail(failure)

        screen.assertError(failure)
    }

    /** The gate works on any cold source, also one that completes (not only on a Room stream). */
    @Test
    fun ac3_aSourceThatCompletes_offScreenDeliversItsFirstItemOnly() {
        var subscriptions = 0
        val source = Flowable.defer { subscriptions++; Flowable.just("a", "b", "c") }
        val gate = OnScreenGate()

        val screen = gate.gate(source).test()

        screen.assertValues("a")
        screen.assertNotComplete()
        assertEquals(1, subscriptions)
    }

    // ---- at the owner's scale ----

    /**
     * Finding J2/J3: `SubscriptionsFetchService` writes the subscriptions table once per channel.
     * 1600 writes while the screen is off screen cost no query; the return costs one.
     */
    @Test
    fun ac3_scale_1600WritesOffScreen_noQuery_theReturnCostsOne() {
        val table = FakeTable(0)
        val gate = OnScreenGate()
        val screen = gate.gate(table.flowable()).test()
        assertEquals(1, table.queries)

        for (write in 1..SUBSCRIPTIONS) table.write(write)

        assertEquals("AC-3: 1600 writes off screen: still only the first load", 1, table.queries)
        assertEquals(0, table.observers)

        gate.setOnScreen(true)

        assertEquals("AC-3: the return is one query", 2, table.queries)
        screen.assertValues(0, SUBSCRIPTIONS)
    }

    /**
     * The owner's many groups: 40 group feeds alive (main tabs and back stack), none on screen,
     * each with its own gate, as each `FeedViewModel` has. 1600 writes: before the change 1600
     * queries per live feed, now none; returning to one feed costs one query in that feed only.
     */
    @Test
    fun ac3_scale_40HiddenFeeds_1600Writes_noQueryUntilOneIsReturnedTo() {
        val table = FakeTable(0)
        val gates = List(GROUPS) { OnScreenGate() }
        val screens = gates.map { it.gate(table.flowable()).test() }
        assertEquals("the 40 first loads", GROUPS, table.queries)

        for (write in 1..SUBSCRIPTIONS) table.write(write)

        assertEquals("AC-3: no hidden feed queried for any of the 1600 writes", GROUPS, table.queries)
        assertEquals("AC-3: no hidden feed observes the table", 0, table.observers)

        gates[7].setOnScreen(true)

        assertEquals("AC-3: one query, for the feed returned to", GROUPS + 1, table.queries)
        assertEquals(1, table.observers)
        screens[7].assertValues(0, SUBSCRIPTIONS)
        screens.filterIndexed { i, _ -> i != 7 }.forEach { it.assertValues(0) }
    }

    /** The feed on screen keeps updating: the gate takes nothing away from it. */
    @Test
    fun ac3_scale_theOneFeedOnScreenStillSeesEveryWrite() {
        val table = FakeTable(0)
        val gates = List(GROUPS) { OnScreenGate() }
        val screens = gates.map { it.gate(table.flowable()).test() }
        gates[0].setOnScreen(true)
        val before = table.queries

        for (write in 1..SUBSCRIPTIONS) table.write(write)

        assertEquals("AC-3: 1600 writes are 1600 queries in total, all for the feed on screen", before + SUBSCRIPTIONS, table.queries)
        assertEquals(2 + SUBSCRIPTIONS, screens[0].values().size)
        assertEquals(1, screens[1].values().size)
    }
}
