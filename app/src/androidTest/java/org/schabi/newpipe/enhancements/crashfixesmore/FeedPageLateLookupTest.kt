package org.schabi.newpipe.enhancements.crashfixesmore

import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.local.feed.FeedFragment
import org.schabi.newpipe.local.feed.service.FeedLoadService
import java.lang.reflect.InvocationTargetException
import java.util.Collections

/**
 * AC-7 (plan C7, instrumented test I3): the feed page's "channel not available" lookup that
 * returns after the page was closed. Run by hand on a device or emulator, not in CI:
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.crashfixesmore.FeedPageLateLookupTest`
 *
 * **How.** A subscription row is inserted, the real `FeedFragment` is shown in the real
 * `MainActivity` on the back stack, and its private `handleItemsErrors(List)` is called on the
 * main thread (by reflection) with what a refresh hands it for a deleted channel: a
 * `FeedLoadService.RequestException` for that row caused by a `ContentNotAvailableException`,
 * followed by one other error. That starts the page's own database lookup on the io
 * scheduler. In the **same main-thread turn** the page is closed with
 * `popBackStackImmediate()`, so the lookup's result can only arrive after the page lost its
 * context. No network, no timing by hand.
 *
 * **How a crash is seen.** The app's RxJava error handler hands a critical error to
 * `Thread.currentThread().getUncaughtExceptionHandler()`. The test puts a recorder there for
 * the main thread and restores the old handler, so "the app closes" is an entry in a list and a
 * failed assertion with the stack trace as its cause, not a dead test process.
 *
 * **Before the fix** the recorder gets an `IllegalStateException: Fragment FeedFragment{..}
 * not attached to a context`: on the success branch from `handleFeedNotAvailable`, on the
 * error branch (the lookup itself ran after the detach) as first member of a
 * `CompositeException` whose second comes from `ErrorUtil.showSnackbar`. The second list entry
 * is there so that both branches fail, whichever the io thread takes.
 *
 * **A pass of `ac7_pageClosedBeforeLookupReturns_nothingThrown` proves**: with the page closed
 * before the lookup returned, nothing reached the main thread's uncaught-exception handler
 * within two seconds, on whichever of the two branches this run took.
 *
 * **It does not prove**: that both branches are fixed (one run takes one branch; which one is
 * not controlled and not reported); anything about the dialog's buttons tapped after the page
 * is gone (plan C7 "Not fixed", outside AC-7 by answer A9); that this was the crash of issue
 * #26 (no log exists); nor the real sequence refresh, Back (device step D7).
 *
 * The two control tests are about AC-6 and the wording of AC-7 ("closed, not merely covered by
 * another page", answer A9): a page that still exists keeps its dialog. Whether the dialog is
 * on screen is read without a UI library, from the activity losing window focus; that is the
 * weakest assertion here and is skipped when the activity had no focus to begin with. If only
 * it fails, look at the screen before the fix (D7 checks the dialog by eye).
 *
 * The test inserts one subscription row (url on `crashfixesmore.invalid`) and removes it.
 * Compiled, not run, by the layer that wrote it.
 */
@RunWith(AndroidJUnit4::class)
class FeedPageLateLookupTest {

    private lateinit var database: AppDatabase
    private var subscriptionId = -1L
    private var scenario: ActivityScenario<MainActivity>? = null

    private val recorded: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var mainThread: Thread? = null
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun insertSubscription() {
        database = NewPipeDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        database.subscriptionDAO().deleteSubscription(SERVICE_ID, CHANNEL_URL)
        val entity = SubscriptionEntity()
        entity.serviceId = SERVICE_ID
        entity.url = CHANNEL_URL
        entity.name = CHANNEL_NAME
        subscriptionId = database.subscriptionDAO().insert(entity)
        assertNotNull(
            "precondition: the subscription row can be read back by its uid",
            database.subscriptionDAO().getSubscription(subscriptionId)
        )
    }

    @After
    fun cleanUp() {
        val launched = scenario
        if (launched != null) {
            runCatching { launched.onMain { restoreHandler() } }
            launched.close()
        }
        database.subscriptionDAO().deleteSubscription(SERVICE_ID, CHANNEL_URL)
    }

    /** Main thread only: from now on what the app would die of is recorded instead. */
    private fun recordInsteadOfCrashing() {
        val thread = Thread.currentThread()
        mainThread = thread
        handlerBefore = thread.uncaughtExceptionHandler.takeIf { it !== thread.threadGroup }
        thread.setUncaughtExceptionHandler { _, throwable -> recorded.add(throwable) }
    }

    private fun restoreHandler() {
        mainThread?.uncaughtExceptionHandler = handlerBefore
        mainThread = null
    }

    /** What a refresh hands the page for one deleted channel and one other failure. */
    private fun errorsOfARefresh(): List<Throwable> = listOf(
        FeedLoadService.RequestException(subscriptionId, "$SERVICE_ID:$CHANNEL_URL", ContentNotAvailableException("gone")),
        RuntimeException("crash-fixes-more AC-7: another error of the same refresh")
    )

    /** The page's private `handleItemsErrors(List)`; main thread. What it throws itself is rethrown. */
    private fun handleItemsErrors(page: FeedFragment, errors: List<Throwable>) {
        val method = FeedFragment::class.java.getDeclaredMethod("handleItemsErrors", List::class.java)
        method.isAccessible = true
        try {
            method.invoke(page, errors)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun assertNothingRecorded(what: String) {
        val copy = synchronized(recorded) { ArrayList(recorded) }
        if (copy.isNotEmpty()) {
            throw AssertionError(
                "$what: the app's error handler handed ${copy.size} error(s) to the main thread's " +
                    "uncaught-exception handler, which closes the app: $copy",
                copy[0]
            )
        }
    }

    /**
     * AC-7: "a feed page that is closed before its 'channel not available' lookup has returned
     * never crashes; the lookup's result is dropped". Fails before the fix on both branches of
     * the lookup (see the class comment).
     */
    @Test
    fun ac7_pageClosedBeforeLookupReturns_nothingThrown() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val page = FeedFragment.newInstance()
        launched.showPage(page)
        letFramesRun(500)

        launched.onMain { activity ->
            recordInsteadOfCrashing()
            assertNotNull("precondition: the feed page is attached", page.context)
            // starts the lookup on the io scheduler ..
            handleItemsErrors(page, errorsOfARefresh())
            // .. and the page is closed before the main thread can deliver its result
            assertTrue(
                "precondition: the feed page is on the back stack and can be closed",
                activity.supportFragmentManager.popBackStackImmediate()
            )
            assertNull("precondition: a closed page has no context", page.context)
        }

        letFramesRun(LOOKUP_WAIT_MS)
        assertNothingRecorded("AC-7 (plan C7, E7a / E7b): the lookup returned after its feed page was closed")
        assertEquals(
            "AC-7: the activity is still there and resumed",
            androidx.lifecycle.Lifecycle.State.RESUMED,
            launched.state
        )
    }

    /**
     * AC-6 for AC-7, control: the same lookup on a page that is still there. Nothing is
     * thrown, and the "could not load feed" dialog is shown as before (read from the activity
     * losing window focus; see the class comment). Passes before the fix.
     */
    @Test
    fun ac7_pageStillThere_dialogIsShown() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val page = FeedFragment.newInstance()
        launched.showPage(page)
        letFramesRun(500)
        val hadFocus = waitUntil(FOCUS_WAIT_MS) { launched.onMain { it.hasWindowFocus() } }

        launched.onMain {
            recordInsteadOfCrashing()
            handleItemsErrors(page, errorsOfARefresh())
            assertNotNull("the page is still attached", page.context)
        }

        letFramesRun(LOOKUP_WAIT_MS)
        assertNothingRecorded("AC-6 / AC-7: the lookup returned to a feed page that is still there")
        assumeTrue("the activity never had window focus; the dialog cannot be read from it", hadFocus)
        assertTrue(
            "AC-6 / AC-7: for a page that is still there the \"could not load feed\" dialog must be shown as before " +
                "(expected the activity to lose window focus to the dialog; if only this fails, look at the screen)",
            waitUntil(FOCUS_WAIT_MS) { launched.onMain { !it.hasWindowFocus() } }
        )
    }

    /**
     * AC-7 "closed (not merely covered by another page)", answer A9: another page is opened on
     * top of the feed page before the lookup returns. The feed page still exists (it has a
     * context), so the result is **not** dropped: nothing is thrown and the dialog is shown
     * over the page on top, as today. Passes before the fix; it fails for a guard that also
     * drops the result of a covered page (`!isAdded` instead of `context == null`).
     */
    @Test
    fun ac7_pageMerelyCovered_resultIsNotDropped() {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val page = FeedFragment.newInstance()
        launched.showPage(page)
        letFramesRun(500)
        val hadFocus = waitUntil(FOCUS_WAIT_MS) { launched.onMain { it.hasWindowFocus() } }

        launched.onMain { activity ->
            recordInsteadOfCrashing()
            handleItemsErrors(page, errorsOfARefresh())
            // another page on top, as the app opens one: replace, on the back stack
            activity.supportFragmentManager.beginTransaction()
                .replace(org.schabi.newpipe.R.id.fragment_holder, Fragment())
                .addToBackStack(null)
                .commit()
            activity.supportFragmentManager.executePendingTransactions()
            assertNotNull("precondition: a covered page still has its context", page.context)
            assertTrue("precondition: a covered page is no longer 'added'", !page.isAdded)
        }

        letFramesRun(LOOKUP_WAIT_MS)
        assertNothingRecorded("AC-7 (answer A9): the lookup returned to a feed page that is covered by another page")
        assumeTrue("the activity never had window focus; the dialog cannot be read from it", hadFocus)
        assertTrue(
            "AC-7 (answer A9): the result of a page that is merely covered must not be dropped; its dialog must be " +
                "shown as today (expected the activity to lose window focus to the dialog; if only this fails, look at the screen)",
            waitUntil(FOCUS_WAIT_MS) { launched.onMain { !it.hasWindowFocus() } }
        )
    }

    private companion object {
        const val SERVICE_ID = 0
        const val CHANNEL_URL = "https://crashfixesmore.invalid/channel/crashfixesmore-ac7"
        const val CHANNEL_NAME = "crash-fixes-more AC-7 test channel"
        const val LOOKUP_WAIT_MS = 2_000L
        const val FOCUS_WAIT_MS = 3_000L
    }
}
