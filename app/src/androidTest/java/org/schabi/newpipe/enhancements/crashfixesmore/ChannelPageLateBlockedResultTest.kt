package org.schabi.newpipe.enhancements.crashfixesmore

import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.fragments.list.channel.ChannelFragment

/**
 * AC-2 (plan C2b): the result for a blocked channel that arrives while the app is in the
 * background. Not one of the plan's instrumented tests (the plan checks AC-2 with the wiring
 * test and device step D3); added because the case can be built exactly, without a network and
 * without timing. Run by hand on a device or emulator, not in CI:
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.crashfixesmore.ChannelPageLateBlockedResultTest`
 *
 * The real `ChannelFragment` is shown in the real `MainActivity`, on the back stack, and its
 * own load is cancelled at once (the url is on `crashfixesmore.invalid`). The channel's name
 * is put on the block list. The activity is stopped, and the page's public `handleResult(..)`
 * is called on the main thread with a `ChannelInfo` of that name, as the page's worker does
 * when the request ends. Before the fix this throws `IllegalStateException: Can not perform
 * this action after onSaveInstanceState` from `popBackStack()`; it is reported as a test
 * failure, not as an app crash. Then the activity is resumed.
 *
 * It proves the first row of the plan's table under C2 without `release-views` (app in the
 * background, view alive). It does not cover the second row (another page on top, the page's
 * view gone), nor the variant with `release-views`, nor that the toast is shown.
 *
 * The test adds one name to the block list in the app's settings and restores the list.
 * Compiled, not run, by the layer that wrote it.
 */
@RunWith(AndroidJUnit4::class)
class ChannelPageLateBlockedResultTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var blockListKey: String
    private var blockListBefore: Set<String>? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun blockTheTestChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        blockListKey = context.getString(R.string.filter_by_channel_key) + "_set"
        blockListBefore = preferences.getStringSet(blockListKey, null)?.toSet()
        preferences.edit()
            .putStringSet(blockListKey, (blockListBefore ?: emptySet()) + CHANNEL_NAME)
            .commit()
    }

    @After
    fun restoreBlockList() {
        scenario?.close()
        val editor = preferences.edit()
        if (blockListBefore == null) editor.remove(blockListKey) else editor.putStringSet(blockListKey, blockListBefore)
        editor.commit()
    }

    /**
     * AC-2: a blocked channel's result that arrives while the page cannot navigate does not
     * crash and is kept; "the message and the step back happen when the page is returned to".
     * Expected: no exception from `handleResult` while stopped, the page still on the back
     * stack then, and gone after the return.
     */
    @Test
    fun ac2_blockedResultWhileInBackground_isKeptAndHandledOnReturn() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val page = ChannelFragment.getInstance(0, CHANNEL_URL, CHANNEL_NAME)
        launched.showPage(page) {
            cancelOwnLoad(it)
        }
        val entriesWithPage = launched.onMain { it.supportFragmentManager.backStackEntryCount }
        assertTrue("precondition: the page is on the back stack", entriesWithPage > 0)

        launched.moveToState(Lifecycle.State.CREATED)
        val blocked = ChannelInfo(
            0, CHANNEL_ID, CHANNEL_URL, CHANNEL_URL, CHANNEL_NAME,
            ListLinkHandler(CHANNEL_URL, CHANNEL_URL, CHANNEL_ID, emptyList(), null)
        )
        launched.onMain { activity ->
            assertTrue(
                "precondition: the activity is stopped, its fragment state is saved",
                activity.supportFragmentManager.isStateSaved
            )
            try {
                page.handleResult(blocked)
            } catch (e: RuntimeException) {
                throw AssertionError(
                    "AC-2 (plan C2b): a blocked channel's result that arrives while the app is in the " +
                        "background must not throw; it has to be kept until the page is resumed",
                    e
                )
            }
            assertTrue("AC-2 (plan C2b): the page must still be there while the app is in the background", page.isAdded)
            assertEquals(
                "AC-2 (plan C2b): nothing may be popped while the app is in the background",
                entriesWithPage,
                activity.supportFragmentManager.backStackEntryCount
            )
            assertSame(
                "AC-2 (plan C2b.3): the blocked result must be kept in ChannelFragment.lateBlockedResult",
                blocked,
                readField(ChannelFragment::class.java, page, "lateBlockedResult")
            )
            assertNull(
                "AC-2: a blocked channel's result must not become the page's content",
                readField(ChannelFragment::class.java, page, "currentInfo")
            )
        }

        launched.moveToState(Lifecycle.State.RESUMED)
        assertTrue(
            "AC-2 (plan C2b.2): on return the page of the blocked channel must go back " +
                "(onResume handles the kept result: toast and popBackStack)",
            waitUntil(RETURN_TIMEOUT_MS) { launched.onMain { !page.isAdded } }
        )
        assertFalse(
            "AC-2: only the blocked channel's page is popped",
            launched.onMain { it.supportFragmentManager.backStackEntryCount } < entriesWithPage - 1
        )
        assertEquals(Lifecycle.State.RESUMED, launched.state)
    }

    private companion object {
        const val CHANNEL_ID = "crashfixesmore-ac2"
        const val CHANNEL_URL = "https://crashfixesmore.invalid/channel/crashfixesmore-ac2"
        const val CHANNEL_NAME = "crash-fixes-more AC-2 test channel"
        const val RETURN_TIMEOUT_MS = 5_000L
    }
}
