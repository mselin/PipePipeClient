package org.schabi.newpipe.enhancements.crashfixesmore

import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.disposables.Disposable
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.list.channel.ChannelFragment
import java.util.concurrent.atomic.AtomicBoolean

/*
 * What the instrumented tests of this package share: running code on the main thread of the
 * real MainActivity, waiting, and showing a page in it. No test of its own.
 */

/**
 * Runs [block] on the main thread and returns its result. Anything it throws (assertion errors
 * included) is rethrown on the test thread instead of crashing the app process.
 */
internal fun <T> ActivityScenario<MainActivity>.onMain(block: (MainActivity) -> T): T {
    var outcome: Result<T>? = null
    onActivity { activity -> outcome = runCatching { block(activity) } }
    return checkNotNull(outcome) { "the main thread action did not run" }.getOrThrow()
}

/** Waits until [condition] holds, checking after each idle main thread; false on timeout. */
internal fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (condition()) {
            return true
        }
        if (SystemClock.uptimeMillis() >= deadline) {
            return false
        }
        Thread.sleep(50)
    }
}

/** Lets the main thread run its frames and posted work for [durationMs]. */
internal fun letFramesRun(durationMs: Long) {
    val deadline = SystemClock.uptimeMillis() + durationMs
    while (SystemClock.uptimeMillis() < deadline) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(50)
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

/**
 * Shows [page] in the activity's main container, on the back stack, as the app does. [then]
 * runs in the same main-thread turn, after the page's view was created and before anything the
 * page started can deliver a result.
 */
internal fun <F : Fragment> ActivityScenario<MainActivity>.showPage(page: F, then: (F) -> Unit = {}) {
    onMain { activity ->
        val fragmentManager = activity.supportFragmentManager
        fragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, page)
            .addToBackStack(null)
            .commit()
        fragmentManager.executePendingTransactions()
        then(page)
    }
}

/** Reads the private field [name] declared by [owner]. */
internal fun readField(owner: Class<*>, instance: Any, name: String): Any? {
    val field = owner.getDeclaredField(name)
    field.isAccessible = true
    return field.get(instance)
}

/** Writes the private field [name] declared by [owner]. */
internal fun writeField(owner: Class<*>, instance: Any, name: String, value: Any?) {
    val field = owner.getDeclaredField(name)
    field.isAccessible = true
    field.set(instance, value)
}

/**
 * Cancels the load a channel page started in `onViewCreated`, in the same main-thread turn, so
 * that nothing arrives from it; the page is left as its worker leaves it before it hands over
 * a result (not loading). The test's channel url is on a host that does not exist.
 */
internal fun cancelOwnLoad(page: ChannelFragment) {
    (readField(ChannelFragment::class.java, page, "currentWorker") as Disposable?)?.dispose()
    (readField(BaseStateFragment::class.java, page, "isLoading") as AtomicBoolean).set(false)
}
