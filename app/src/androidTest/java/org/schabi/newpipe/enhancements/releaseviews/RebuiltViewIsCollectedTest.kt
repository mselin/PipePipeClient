package org.schabi.newpipe.enhancements.releaseviews

import android.view.View
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.fragments.MainFragment
import org.schabi.newpipe.fragments.list.channel.ChannelVideosFragment
import org.schabi.newpipe.fragments.list.search.SearchFragment
import java.lang.ref.WeakReference

/**
 * AC-3: building a page's view again never leaves an earlier view of that page in memory.
 *
 * The plan names one test, `ac3_viewRebuiltFiveTimes_earlierViewsAreCollected`, for three pages;
 * it is one method per page here so that a failure names the page.
 *
 * All three tests are GC-dependent. They keep only weak references to the earlier root views
 * and use the retry-and-settle pattern of [stillReachableAfterGc]. A collection cannot be
 * forced, so a failure can in rare cases be spurious (plan 9); the LeakCanary walk of the device
 * script is the backing check. A pass cannot be spurious.
 *
 * Not run in this repository's environment (no device); see questions.md A4.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RebuiltViewIsCollectedTest {

    /**
     * GC-dependent.
     *
     * Sets up: a search page with an empty query; left and returned to five times.
     *
     * Asserts: after the fifth return the five earlier root views of the page are all collected.
     *
     * Expected to fail on the unfixed app because every `itemsList.setAdapter(infoListAdapter)`
     * and `suggestionsList.setAdapter(suggestionListAdapter)` registers the new list as observer
     * of an adapter that lives as long as the page, and nothing unregisters the old one: the
     * adapters keep every earlier list, and each list its whole view tree (plan 1 M2, 4.2, 4.5).
     */
    @Test
    fun ac3_viewRebuiltFiveTimes_earlierViewsAreCollected_searchPage() {
        launchMainActivity().use { scenario ->
            val page = scenario.onMain {
                SearchFragment.getInstance(ServiceList.YouTube.serviceId, "")
            }
            scenario.openPage(page)

            assertEarlierViewsAreCollected(scenario, page)
        }
    }

    /**
     * GC-dependent.
     *
     * Sets up: a channel videos page built from a hand-built `ChannelInfo` with 40 items (no
     * request); left and returned to five times.
     *
     * Asserts: after the fifth return the five earlier root views of the page are all collected.
     *
     * Expected to fail on the unfixed app because the reused `infoListAdapter` keeps every
     * earlier list as observer, and through its header supplier the last header (plan 1 M2, 4.2).
     */
    @Test
    fun ac3_viewRebuiltFiveTimes_earlierViewsAreCollected_channelVideosPage() {
        launchMainActivity().use { scenario ->
            val page = scenario.onMain {
                ChannelVideosFragment.getInstance(handBuiltChannel("ac3videos", 40))
            }
            scenario.openPage(page)

            assertEarlierViewsAreCollected(scenario, page)
        }
    }

    /**
     * GC-dependent. In scope by questions.md Q3/A3 (AC-2a, plan 4.6).
     *
     * Sets up: the main page that `MainActivity` opens itself, with whatever tabs are configured
     * (their content is not looked at; whether their loads succeed does not matter); left and
     * returned to five times.
     *
     * Asserts: after the fifth return the five earlier root views of the main page are all
     * collected. Every tab's view is a child of that root, so a tab page that keeps a view of
     * its own (the Subscriptions tab does today) fails this test as well.
     *
     * Expected to fail on the unfixed app because `MainFragment` reuses `pagerAdapter`, which
     * keeps every earlier tab bar as observer (one per rebuild, never unregistered), and because
     * the Subscriptions tab's `groupAdapter` keeps every earlier list (plan 2, 4.6).
     */
    @Test
    fun ac3_viewRebuiltFiveTimes_earlierViewsAreCollected_mainPage() {
        launchMainActivity().use { scenario ->
            val page = scenario.currentPage()
            assertTrue(
                "MainActivity must start with the main page, found $page",
                page is MainFragment
            )

            assertEarlierViewsAreCollected(scenario, page!!)
        }
    }

    private fun assertEarlierViewsAreCollected(
        scenario: ActivityScenario<MainActivity>,
        page: Fragment
    ) {
        val earlierViews = ArrayList<WeakReference<View>>()
        repeat(REBUILDS) {
            // only the weak reference leaves the main thread; nothing here keeps the view alive
            earlierViews += scenario.onMain { WeakReference(page.requireView()) }
            scenario.leaveCurrentPage()
            assertTrue("the page must be a left page", scenario.isLeft(page))
            scenario.returnToPreviousPage()
        }
        assertTrue("the page must be shown again", scenario.onMain { page.view != null })
        assertTrue(
            "set-up: the view shown now must not be one of the earlier views",
            scenario.onMain { earlierViews.none { it.get() === page.view } }
        )

        assertEquals(
            "earlier views of ${page.javaClass.simpleName} still in memory after " +
                "$REBUILDS rebuilds (GC-dependent: retried, see class comment)",
            0, stillReachableAfterGc(earlierViews)
        )
    }

    private companion object {
        const val REBUILDS = 5
    }
}
