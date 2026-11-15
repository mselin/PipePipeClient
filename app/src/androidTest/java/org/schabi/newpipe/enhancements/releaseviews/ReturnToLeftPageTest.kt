package org.schabi.newpipe.enhancements.releaseviews

import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.fragments.list.channel.ChannelVideosFragment
import org.schabi.newpipe.info_list.InfoListAdapter
import org.schabi.newpipe.local.subscription.SubscriptionManager

/**
 * AC-4 and AC-5: returning to a left page shows what it showed before, and a change that
 * arrives while the page is left does no harm and is shown on return.
 *
 * Both tests use a channel videos page built from a hand-built `ChannelInfo` with 40 items, so
 * no request is made. Deterministic, no garbage collector involved.
 *
 * Not run in this repository's environment (no device); see questions.md A4.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ReturnToLeftPageTest {

    private val serviceId = ServiceList.YouTube.serviceId

    /** The AC-5 test subscribes to a fake channel in the app's real database; never leave it. */
    @Before
    @After
    fun removeTestSubscription() {
        SubscriptionManager(targetContext()).subscriptionTable()
            .deleteSubscription(serviceId, TEST_CHANNEL_HOST + AC5_KEY)
    }

    /**
     * Sets up: the channel videos page, scrolled so that item 20 is the first row; an empty page
     * is put on top and removed again.
     *
     * Asserts: the page has a new view, the list has the same number of rows and items as
     * before, and the same row is the first one shown.
     *
     * This test is expected to pass on the unfixed app as well (plan 6: "passes today; guards
     * the fix"). It fails after a fix that releases the views but loses the data or the
     * position, for example by dropping the adapter with its items, or by attaching the adapter
     * after the saved view state has been applied.
     *
     * Skipped (assumption failure) on a screen so large that 41 rows do not need scrolling.
     */
    @Test
    fun ac4_itemsAndPositionSurviveReturn() {
        launchMainActivity().use { scenario ->
            val page = scenario.onMain {
                ChannelVideosFragment.getInstance(handBuiltChannel("ac4videos", 40))
            }
            scenario.openPage(page)
            assertTrue(
                "the list must show its rows",
                scenario.waitUntil { (itemsListOf(page)?.childCount ?: 0) > 0 }
            )

            scenario.onMain {
                val list = itemsListOf(page)!!
                val layoutManager = list.layoutManager
                if (layoutManager is LinearLayoutManager) {
                    layoutManager.scrollToPositionWithOffset(SCROLL_TARGET, 0)
                } else {
                    list.scrollToPosition(SCROLL_TARGET)
                }
            }
            settle()
            val before = scenario.onMain { ListState.of(itemsListOf(page)!!) }
            val viewBefore = scenario.onMain { page.requireView() }
            assertTrue("the hand-built items must be in the list", before.items > 0)
            assumeTrue(
                "the list does not scroll on this screen, the position cannot be checked",
                before.firstVisible > 0
            )

            scenario.leaveCurrentPage()
            assertTrue("the page must be a left page", scenario.isLeft(page))
            scenario.returnToPreviousPage()
            assertTrue(
                "the list must show its rows again",
                scenario.waitUntil { (itemsListOf(page)?.childCount ?: 0) > 0 }
            )

            assertNotSame(
                "set-up: returning must build a new view",
                viewBefore, scenario.onMain { page.requireView() }
            )
            assertEquals(
                "rows, items and first shown row after returning",
                before, scenario.onMain { ListState.of(itemsListOf(page)!!) }
            )
        }
    }

    /**
     * Sets up: the channel videos page of a channel that is not subscribed; its button reads
     * "Subscribe". The page is left, and while it is left the channel's subscription is inserted
     * with `SubscriptionManager`, as a subscribe on another page of the same channel does.
     *
     * Asserts, in this order:
     * 1. while the page is left, the button of its destroyed header still reads "Subscribe"
     *    during the five seconds after the insert (and the app does not crash, which would end
     *    the test run);
     * 2. after returning, the button of the new header reads "Subscribed".
     *
     * Assumption, logged as Q7 in questions.md: the plan gives "today the monitors write into the
     * detached header" as the reason this test fails today, but its steps (no crash; on return
     * the button reads "subscribed") also hold on the unfixed app, because returning subscribes
     * new monitors. Assertion 1 is how this test observes the plan's reason.
     *
     * Expected to fail on the unfixed app at assertion 1: the three database monitors started by
     * `handleResult` stay subscribed after the view is destroyed, and `updateSubscribeButton`
     * sets "Subscribed" on the header the page still holds (plan 2, 4.3). With the fix the
     * monitors are cleared in `onDestroyView`, nothing writes to the old header, and the monitor
     * started on return shows the current state.
     */
    @Test
    fun ac5_subscriptionChangeWhileLeft_isShownOnReturn() {
        val context = targetContext()
        val subscribe = context.getString(R.string.subscribe_button_title)
        val subscribed = context.getString(R.string.subscribed_button_title)

        launchMainActivity().use { scenario ->
            val info = handBuiltChannel(AC5_KEY, 40)
            val page = scenario.onMain { ChannelVideosFragment.getInstance(info) }
            scenario.openPage(page)
            assertTrue(
                "set-up: the button of a channel that is not subscribed must read \"$subscribe\"",
                scenario.waitUntil { subscribeButtonOf(page)?.text?.toString() == subscribe }
            )
            val leftButton = scenario.onMain { subscribeButtonOf(page)!! }

            scenario.leaveCurrentPage()
            assertTrue("the page must be a left page", scenario.isLeft(page))

            val subscription = SubscriptionEntity().apply {
                serviceId = info.serviceId
                url = info.url
                setData(info.name, info.avatarUrl, info.description, info.subscriberCount)
            }
            SubscriptionManager(context).insertSubscription(subscription, info)

            val writtenWhileLeft = scenario.waitUntil(timeoutMs = 5_000) {
                leftButton.text.toString() != subscribe
            }
            assertTrue(
                "a subscription change must not be written into the header of a left page; " +
                    "its button now reads \"${scenario.onMain { leftButton.text.toString() }}\"",
                !writtenWhileLeft
            )

            scenario.returnToPreviousPage()
            assertTrue(
                "after returning the button must read \"$subscribed\", it reads " +
                    "\"${scenario.onMain { subscribeButtonOf(page)?.text?.toString() }}\"",
                scenario.waitUntil { subscribeButtonOf(page)?.text?.toString() == subscribed }
            )
            assertNotSame(
                "set-up: returning must build a new header",
                leftButton, scenario.onMain { subscribeButtonOf(page) }
            )
        }
    }

    private fun subscribeButtonOf(page: ChannelVideosFragment): TextView? =
        page.view?.findViewById(R.id.channel_subscribe_button)

    /** What AC-4 compares before leaving and after returning. */
    private data class ListState(val rows: Int, val items: Int, val firstVisible: Int) {
        companion object {
            fun of(list: androidx.recyclerview.widget.RecyclerView): ListState {
                val adapter = list.adapter
                return ListState(
                    rows = adapter?.itemCount ?: -1,
                    items = (adapter as? InfoListAdapter)?.itemsList?.size ?: -1,
                    firstVisible = firstVisiblePosition(list)
                )
            }
        }
    }

    private companion object {
        const val AC5_KEY = "ac5videos"
        const val SCROLL_TARGET = 20
    }
}
