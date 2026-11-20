package org.schabi.newpipe.enhancements.crashfixesmore

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.filter.Filter
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.fragments.detail.TabAdapter
import org.schabi.newpipe.fragments.list.channel.ChannelFragment
import java.lang.reflect.InvocationTargetException

/**
 * AC-5 (plan C5a and C5b), the plan's instrumented test I2: a tab that fails while the channel
 * page builds its tabs. Run by hand on a device or emulator, not in CI, **and before the pull
 * request is merged** (answer A6):
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.crashfixesmore.ChannelPageFailingTabTest`
 *
 * **Why this test matters.** The plan's claim C5b, that after one failed tab every later
 * `finishUpdate` of the pager throws `Fragment already added` and that swallowing it in
 * `TabAdapter` is enough for the page to live on, was read out of the compiled libraries and
 * never observed. The JVM tests can only check `TabAdapter` against a transaction that is
 * made to throw (`TabAdapterFinishUpdateTest`). These tests are the only ones where the real
 * `ViewPager`, `FragmentPagerAdapter`, `FragmentManager` and `TabLayout` act together.
 *
 * **How.** The real `ChannelFragment` is shown in the real `MainActivity`. Its own load is
 * cancelled at once (the url is on `crashfixesmore.invalid`, nothing is requested for the
 * page). When the pager is laid out, the page's private `currentInfo` is set to a `ChannelInfo`
 * made here, with four tabs and a description, so that the page builds at least three tabs
 * (with two, the last notification of `updateTabs` succeeds and the old code does not crash).
 * A `FragmentLifecycleCallbacks` on the page's child fragment manager throws one
 * `IllegalStateException` while the first tab fragment is created. Then the private
 * `updateTabs()` is called on the main thread. Everything that runs inside a call made by the
 * test is reported as a test failure with its stack trace; only a crash in a frame the
 * system drives ends the instrumentation run ("Process crashed").
 *
 * **What a passing run proves.**
 * - `updateTabs()` returns although the pager failed while the first of at least three tabs
 *   was created (C5a, and with C5b the tab bar is told about every tab).
 * - A measure pass of the pager directly afterwards does not throw (C5b). This is the "next
 *   layout" of AC-5, called by the test so that the failure has a stack trace.
 * - One second of real frames after that does not kill the process, and the page is still
 *   resumed.
 * - The tab bar is visible and has one tab per page of the adapter, each with its title.
 * - [ac5_failingTab_pageSurvivesStopStartAndLeaving]: the same page survives the app going to
 *   the background and coming back, and being left (the plan's row "assumed; test I2").
 *
 * **What it does not prove.**
 * - That a real failure inside a tab takes the same path. The failure here is thrown by a
 *   lifecycle callback, in [ac5_failingTab_channelPageSurvives] before the tab's own code ran
 *   (the plan's I2) and in [ac5_tabFailingInViewCreated_channelPageSurvives] after its view was
 *   created and added to the pager (where the tab failed on the owner's device). A tab that
 *   fails later, or with another exception class than `IllegalStateException`, is not covered;
 *   the plan's fix does not claim to cover it either.
 * - Anything about what the user sees in the tabs: the failed tab and the tabs after it are
 *   not looked at (AC-5: "may stay empty until the page is reopened").
 * - That the failure is in the log once: `TabAdapterFinishUpdateTest` on the JVM, and device
 *   step D6.
 * - That the page still works with the other branches merged; the plan asks for a re-run
 *   after `release-views` is merged.
 * - It was compiled, not run, by the layer that wrote it. If it fails in a way that points at
 *   the test and not at the fix, that is a question to the Tests layer.
 *
 * The tabs that do get created run their own upstream code against the made-up channel; their
 * loads fail on the `.invalid` host and show the tab's own error state. The test reads the
 * user's channel-tab settings: with fewer than three tabs enabled it fails with that reason.
 */
@RunWith(AndroidJUnit4::class)
class ChannelPageFailingTabTest {

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivity() {
        scenario?.close()
    }

    /** Where in the creation of the first tab fragment the test's failure is thrown. */
    private enum class Stage { NONE, PRE_CREATED, VIEW_CREATED }

    /** Throws one `IllegalStateException` for the first fragment that reaches [stage]. */
    private class FailFirstTab(private val stage: Stage) : FragmentManager.FragmentLifecycleCallbacks() {
        var thrown = 0
            private set

        override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            failOnceAt(Stage.PRE_CREATED)
        }

        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            failOnceAt(Stage.VIEW_CREATED)
        }

        private fun failOnceAt(reached: Stage) {
            if (reached == stage && thrown == 0) {
                thrown++
                throw IllegalStateException(FAILURE_MESSAGE)
            }
        }
    }

    /**
     * AC-5, the plan's I2: the first tab fails before its own code runs
     * (`onFragmentPreCreated`). Expected: `updateTabs()` returns, the next measure pass of the
     * pager and a second of frames pass, the tab bar shows every tab of the adapter.
     *
     * On the parent's code this fails with the `NullPointerException` of the device crash
     * (message "plan C5a"); with C5a alone it fails in the measure pass (message "plan C5b").
     */
    @Test
    fun ac5_failingTab_channelPageSurvives() {
        val page = showChannelPage()
        buildTabs(page, Stage.PRE_CREATED)
        expectTabBarMatchesAdapter(page)
        expectPageAliveAfterFrames(page)
        expectTabBarMatchesAdapter(page)
    }

    /**
     * AC-5, the stage of the crash on the owner's device: the first tab fails when its view
     * was already created and added to the pager (`onFragmentViewCreated`, directly after the
     * tab's `onViewCreated`), which is the case the plan describes under C5b ("the failed tab's
     * view was added to the pager before the fragment threw, so a layout pass is pending").
     * Same expectations as [ac5_failingTab_channelPageSurvives].
     *
     * Here the first tab's own code runs up to `onViewCreated` against the made-up channel. If
     * that code fails by itself, the test says so ("did not get to throw") instead of
     * blaming the fix.
     */
    @Test
    fun ac5_tabFailingInViewCreated_channelPageSurvives() {
        val page = showChannelPage()
        buildTabs(page, Stage.VIEW_CREATED)
        expectTabBarMatchesAdapter(page)
        expectPageAliveAfterFrames(page)
        expectTabBarMatchesAdapter(page)
    }

    /**
     * AC-5 "the page does not crash", for the plan's row "The page survives the further
     * lifecycle of a half-created tab fragment (C5b): assumed; test I2". After the failure of
     * [ac5_failingTab_channelPageSurvives] the app goes to the background and comes back, and
     * then the page is left with Back. Expected: no crash, the page is resumed again with its
     * tab bar unchanged after the return, and gone after Back.
     *
     * Stop and start are driven by the system, so a crash there ends the run without a test
     * failure of its own. When the app is stopped the fragment manager moves the half-created
     * tab on, so from here on the tab's own upstream code runs; a failure in this test can
     * come from that code and is then a finding for the plan, not for the fix.
     */
    @Test
    fun ac5_failingTab_pageSurvivesStopStartAndLeaving() {
        val page = showChannelPage()
        buildTabs(page, Stage.PRE_CREATED)
        expectPageAliveAfterFrames(page)
        val launched = scenario!!

        launched.moveToState(Lifecycle.State.CREATED)
        letFramesRun(500)
        launched.moveToState(Lifecycle.State.RESUMED)
        expectPageAliveAfterFrames(page)
        expectTabBarMatchesAdapter(page)

        launched.onMain { activity ->
            try {
                activity.supportFragmentManager.popBackStackImmediate()
            } catch (e: RuntimeException) {
                throw AssertionError("AC-5: leaving the channel page after a failed tab threw", e)
            }
        }
        letFramesRun(500)
        assertFalse("AC-5: the channel page must be gone after Back", launched.onMain { page.isAdded })
    }

    /**
     * AC-6 for plan C5a and C5b ("both counts are equal whenever the pager works"): the same
     * page without a failing tab. `updateTabs()` builds every tab, the tab bar has one tab per
     * page with its title, the next measure pass and a second of frames pass. Passes before
     * Code exists; it also shows that the way these tests build the page works at all.
     */
    @Test
    fun ac6_workingTabs_channelPageUnchanged() {
        val page = showChannelPage()
        buildTabs(page, Stage.NONE)
        expectTabBarMatchesAdapter(page)
        expectPageAliveAfterFrames(page)
        expectTabBarMatchesAdapter(page)
    }

    // ------------------------------------------------------------------------------------

    /** Shows a channel page whose own load is cancelled, and waits until its pager is laid out. */
    private fun showChannelPage(): ChannelFragment {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val page = ChannelFragment.getInstance(0, CHANNEL_URL, CHANNEL_NAME)
        launched.showPage(page) {
            cancelOwnLoad(it)
        }
        assertTrue(
            "the channel page's pager was not laid out in time",
            waitUntil(LAYOUT_TIMEOUT_MS) {
                launched.onMain {
                    val pager = page.view?.findViewById<ViewPager>(R.id.view_pager)
                    pager != null && pager.isLaidOut && pager.width > 0 && pager.windowToken != null
                }
            }
        )
        return page
    }

    /**
     * Gives the page a channel with at least three tabs, lets the first tab fail at [stage]
     * and calls `updateTabs()`; then runs one measure pass of the pager. Fails with the
     * criterion and plan step when either throws.
     */
    private fun buildTabs(page: ChannelFragment, stage: Stage) {
        val launched = scenario!!
        launched.onMain {
            val failFirstTab = FailFirstTab(stage)
            page.childFragmentManager.registerFragmentLifecycleCallbacks(failFirstTab, false)
            writeField(ChannelFragment::class.java, page, "currentInfo", channelWithTabs())

            val updateTabs = ChannelFragment::class.java.getDeclaredMethod("updateTabs")
            updateTabs.isAccessible = true
            try {
                updateTabs.invoke(page)
            } catch (e: InvocationTargetException) {
                val cause = e.targetException
                if (stage != Stage.NONE && failFirstTab.thrown == 0) {
                    throw AssertionError(
                        "not conclusive: updateTabs() threw before the test's failure at $stage was " +
                            "reached, so the tab's own code or the page failed with the made-up channel",
                        cause
                    )
                }
                throw AssertionError(
                    "AC-5 (plan C5a): ChannelFragment.updateTabs() must not throw when the pager failed " +
                        "while the first tab was created (stage $stage); the NullPointerException of " +
                        "getTabAt(i).setText(..) is the crash of the owner's device",
                    cause
                )
            }
            if (stage != Stage.NONE) {
                assertEquals(
                    "precondition: the first tab fragment must have been created, and made to fail, " +
                        "inside updateTabs() (the pager populates at once after its first layout)",
                    1,
                    failFirstTab.thrown
                )
            }
            val pager = pagerOf(page)
            val adapter = pager.adapter as TabAdapter
            assertTrue(
                "precondition: the page must build at least 3 tabs, it built ${adapter.count}; enable " +
                    "the channel tabs in Settings > Content (with two tabs the old code does not crash)",
                adapter.count >= 3
            )

            // AC-5 "or at the next layout": what ViewPager.onMeasure does in the next frame
            pager.requestLayout()
            try {
                pager.measure(
                    View.MeasureSpec.makeMeasureSpec(pager.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(pager.height, View.MeasureSpec.EXACTLY)
                )
            } catch (e: RuntimeException) {
                throw AssertionError(
                    "AC-5 (plan C5b): the pager's next measure pass after a failed tab (stage $stage) must " +
                        "not throw; `Fragment already added` here is the pager's stale transaction, which " +
                        "TabAdapter.finishUpdate has to swallow",
                    e
                )
            }
        }
    }

    /** AC-5 "it shows the tab bar": visible, one tab per page of the adapter, each with its title. */
    private fun expectTabBarMatchesAdapter(page: ChannelFragment) {
        scenario!!.onMain {
            val adapter = pagerOf(page).adapter as TabAdapter
            val tabLayout = page.requireView().findViewById<TabLayout>(R.id.tab_layout)
            assertEquals(
                "AC-5 (plan C5b): the tab bar must have one tab per page of the adapter " +
                    "(it is told only if the pager's failure does not leave notifyDataSetChanged)",
                adapter.count,
                tabLayout.tabCount
            )
            assertEquals("AC-5: the tab bar must be visible", View.VISIBLE, tabLayout.visibility)
            val expectedTitles = (0 until adapter.count).map { adapter.getItemTitle(it) }
            val shownTitles = (0 until tabLayout.tabCount).map { tabLayout.getTabAt(it)?.text?.toString() }
            assertEquals("AC-5 (plan C5a): every tab of the tab bar must have its title", expectedTitles, shownTitles)
        }
    }

    /** One second of real frames, with a layout requested; then the page must still be resumed. */
    private fun expectPageAliveAfterFrames(page: ChannelFragment) {
        val launched = scenario!!
        launched.onMain { page.requireView().requestLayout() }
        letFramesRun(FRAMES_MS)
        assertEquals(
            "AC-5: the activity must still be resumed after a second of frames",
            Lifecycle.State.RESUMED,
            launched.state
        )
        assertTrue(
            "AC-5: the channel page must still be shown and resumed after a second of frames",
            launched.onMain { page.isAdded && page.isResumed && page.view != null }
        )
    }

    private fun pagerOf(page: ChannelFragment): ViewPager =
        page.requireView().findViewById(R.id.view_pager)

    /** A channel with four tabs and a description: up to six tabs with "Videos" and "Info". */
    private fun channelWithTabs(): ChannelInfo {
        val info = ChannelInfo(
            0, CHANNEL_ID, CHANNEL_URL, CHANNEL_URL, CHANNEL_NAME,
            ListLinkHandler(CHANNEL_URL, CHANNEL_URL, CHANNEL_ID, emptyList(), null)
        )
        info.tabs = listOf(ChannelTabs.PLAYLISTS, ChannelTabs.LIVESTREAMS, ChannelTabs.SHORTS, ChannelTabs.CHANNELS)
            .map { tab ->
                ListLinkHandler(
                    "$CHANNEL_URL/$tab", "$CHANNEL_URL/$tab", CHANNEL_ID,
                    listOf(FilterItem(Filter.ITEM_IDENTIFIER_UNKNOWN, tab)), null
                )
            }
        info.description = "crash-fixes-more AC-5 test channel"
        return info
    }

    private companion object {
        const val CHANNEL_ID = "crashfixesmore-ac5"
        const val CHANNEL_URL = "https://crashfixesmore.invalid/channel/crashfixesmore-ac5"
        const val CHANNEL_NAME = "crash-fixes-more AC-5 test channel"
        const val FAILURE_MESSAGE = "crash-fixes-more test: a tab fails while the channel page builds its tabs"
        const val LAYOUT_TIMEOUT_MS = 10_000L
        const val FRAMES_MS = 1_000L
    }
}
