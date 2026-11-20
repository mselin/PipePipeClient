package org.schabi.newpipe.enhancements.releaseviews

import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.fragments.list.channel.ChannelFragment
import org.schabi.newpipe.fragments.list.channel.ChannelVideosFragment
import org.schabi.newpipe.fragments.list.search.SearchFragment
import org.schabi.newpipe.info_list.InfoListAdapter

/**
 * AC-1 and AC-2: a left page holds no reference to any of its views, and keeps its data.
 *
 * Each test puts a page into `MainActivity`, puts an empty page on top of it and then asks the
 * reflective walker [detachedViewsHeldBy] what the left page still holds. Deterministic, no
 * garbage collector involved, no request made by the page under test.
 *
 * Not run in this repository's environment (no device); see questions.md A4.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LeftPageHoldsNoViewTest {

    /**
     * Sets up: a search page with an empty query (`SearchFragment.getInstance(0, "")`, nothing is
     * searched), then an empty page on top of it.
     *
     * Asserts: the left search page holds no detached view, no binding of one, and no adapter
     * that still has a list registered.
     *
     * Expected to fail on the unfixed app because `BaseListFragment` keeps `itemsList` and never
     * detaches `infoListAdapter` from it, `SearchFragment` never detaches `suggestionListAdapter`
     * from the suggestion list, and `BaseStateFragment` keeps `emptyStateView`,
     * `loadingProgressBar` and the error panel helper with the page's root view (plan 2, 4.1,
     * 4.2, 4.5). The search field and its buttons are the activity's toolbar views; they stay
     * attached and are not reported.
     */
    @Test
    fun ac1_leftSearchPage_holdsNoDetachedView() {
        launchMainActivity().use { scenario ->
            val page = scenario.onMain {
                SearchFragment.getInstance(ServiceList.YouTube.serviceId, "")
            }
            scenario.openPage(page)
            assertTrue("the search page must be shown first", scenario.onMain { page.view != null })

            scenario.leaveCurrentPage()
            assertTrue("the search page must be a left page", scenario.isLeft(page))

            assertHoldsNoDetachedView(scenario.onMain { detachedViewsHeldBy(page) })
        }
    }

    /**
     * Sets up: `ChannelVideosFragment.getInstance(info)` with a hand-built `ChannelInfo` of 40
     * `StreamInfoItem`s (the page shows them without a request), then an empty page on top.
     *
     * Asserts: the left page holds no detached view, binding or observed adapter; and it keeps
     * its data, the list adapter still has the items it showed.
     *
     * Expected to fail on the unfixed app because `ChannelVideosFragment` nulls `channelBinding`,
     * `headerBinding` and `playlistControlBinding` only in `onDestroy` and never clears
     * `addToGroupButton`, on top of what the two base classes keep (plan 2, 4.3).
     */
    @Test
    fun ac2_leftChannelVideosPage_holdsNoDetachedView() {
        launchMainActivity().use { scenario ->
            val page = scenario.onMain {
                ChannelVideosFragment.getInstance(handBuiltChannel("ac2videos", 40))
            }
            scenario.openPage(page)
            val adapter = scenario.onMain { itemsListOf(page)?.adapter as? InfoListAdapter }
                ?: throw AssertionError("the channel videos page must show its list first")
            val itemsBefore = scenario.onMain { adapter.itemsList.size }
            assertTrue("the hand-built items must be in the list", itemsBefore > 0)

            scenario.leaveCurrentPage()
            assertTrue("the channel videos page must be a left page", scenario.isLeft(page))

            assertHoldsNoDetachedView(scenario.onMain { detachedViewsHeldBy(page) })
            assertEquals(
                "a left page keeps its data",
                itemsBefore, scenario.onMain { adapter.itemsList.size }
            )
        }
    }

    /**
     * Sets up: `ChannelFragment.getInstance(...)`, left at once by an empty page on top.
     *
     * Assumption, logged as Q6 in questions.md: the plan lets the page start its load and says
     * the result does not matter. To keep the test off the network the hand-built `ChannelInfo`
     * is written into the page's private `currentInfo` field before the page is shown, which is
     * the state of a page restored from saved state: it builds its tabs (Videos, and Info because
     * the channel has a description) without a request. If that field cannot be set, the test
     * fails with a message saying so.
     *
     * Asserts: neither the left channel page nor any of its tab pages holds a detached view,
     * a binding of one, or an adapter that still has a pager or list registered.
     *
     * Expected to fail on the unfixed app because `ChannelFragment` nulls `binding` only in
     * `onDestroy` and keeps `tabAdapter`, which holds the destroyed pager as observer (plan 2,
     * 4.4); its tabs keep what `ChannelVideosFragment` and `ChannelInfoFragment` keep (4.3, 4.5).
     */
    @Test
    fun ac2_leftChannelPage_holdsNoDetachedView() {
        launchMainActivity().use { scenario ->
            val info = handBuiltChannel("ac2channel", 40, description = "Hand-built description")
            val page = scenario.onMain {
                ChannelFragment.getInstance(info.serviceId, info.url, info.name)
                    .also { presetChannelInfo(it, info) }
            }
            scenario.openPage(page)
            assertTrue("the channel page must be shown first", scenario.onMain { page.view != null })

            scenario.leaveCurrentPage()
            assertTrue("the channel page must be a left page", scenario.isLeft(page))

            assertHoldsNoDetachedView(scenario.onMain { detachedViewsHeldBy(page) })
        }
    }

    private fun assertHoldsNoDetachedView(findings: List<String>) {
        assertTrue(
            "a left page must hold nothing of its destroyed view, but:\n" +
                findings.joinToString("\n"),
            findings.isEmpty()
        )
    }

    private fun presetChannelInfo(page: Fragment, info: ChannelInfo) {
        try {
            ChannelFragment::class.java.getDeclaredField("currentInfo")
                .apply { isAccessible = true }
                .set(page, info)
        } catch (e: ReflectiveOperationException) {
            throw AssertionError(
                "test set-up: ChannelFragment.currentInfo cannot be set, " +
                    "see Q6 in enhancements/release-views/questions.md",
                e
            )
        }
    }
}
