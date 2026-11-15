package org.schabi.newpipe.enhancements.releaseviews

import android.content.Context
import android.database.DataSetObservable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewbinding.ViewBinding
import androidx.viewpager.widget.PagerAdapter
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/*
 * Shared helpers of the release-views instrumented tests (plan.md section 6).
 *
 * Pages are hosted in the real MainActivity and stacked the way NavigationHelper stacks them:
 * replace(R.id.fragment_holder, ...) + addToBackStack. Unlike NavigationHelper no animation is
 * used, so that a page's view is destroyed inside the transaction and not when a fade ends.
 */

private const val APP_PACKAGE = "org.schabi.newpipe."
private const val EXTRACTOR_PACKAGE = "org.schabi.newpipe.extractor."

/** How deep [detachedViewsHeldBy] follows the app's own helper objects below a fragment. */
private const val MAX_HELPER_DEPTH = 2

/** Host of every fake channel url. `.invalid` never resolves, and it contains no SQL wildcard. */
internal const val TEST_CHANNEL_HOST = "https://releaseviews.invalid/channel/"

internal fun targetContext(): Context =
    InstrumentationRegistry.getInstrumentation().targetContext

internal fun launchMainActivity(): ActivityScenario<MainActivity> =
    ActivityScenario.launch(MainActivity::class.java)

/**
 * Runs [block] on the main thread and returns its result. Anything it throws (assertion errors
 * included) is rethrown on the test thread instead of crashing the app process.
 */
internal fun <T> ActivityScenario<MainActivity>.onMain(block: (MainActivity) -> T): T {
    var outcome: Result<T>? = null
    onActivity { activity -> outcome = runCatching { block(activity) } }
    return checkNotNull(outcome) { "the main thread action did not run" }.getOrThrow()
}

/** Lets the main thread finish what is queued (layout, posted runnables, fragment moves). */
internal fun settle() {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

/**
 * Evaluates [condition] on the main thread until it holds or [timeoutMs] have passed.
 * @return whether it held in time
 */
internal fun ActivityScenario<MainActivity>.waitUntil(
    timeoutMs: Long = 10_000,
    condition: (MainActivity) -> Boolean
): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        settle()
        if (onMain(condition)) {
            return true
        }
        if (SystemClock.uptimeMillis() >= deadline) {
            return false
        }
        Thread.sleep(50)
    }
}

/** Puts [page] on top of the current page, which becomes a left page at once. */
internal fun ActivityScenario<MainActivity>.openPage(page: Fragment) {
    onMain { activity ->
        val fragmentManager = activity.supportFragmentManager
        fragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, page)
            .addToBackStack(null)
            .commit()
        fragmentManager.executePendingTransactions()
    }
    settle()
}

/** Leaves the current page by putting an empty page on top of it. */
internal fun ActivityScenario<MainActivity>.leaveCurrentPage() = openPage(BlankPage())

/** Returns to the page below the current one, as Back does. */
internal fun ActivityScenario<MainActivity>.returnToPreviousPage() {
    onMain { activity ->
        check(activity.supportFragmentManager.popBackStackImmediate()) {
            "there was no page to return to"
        }
    }
    settle()
}

/** The page currently shown in the main fragment holder. */
internal fun ActivityScenario<MainActivity>.currentPage(): Fragment? = onMain { activity ->
    activity.supportFragmentManager.executePendingTransactions()
    activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)
}

/** True if [page] is a left page: it still exists for Back, and its view has been destroyed. */
internal fun ActivityScenario<MainActivity>.isLeft(page: Fragment): Boolean = onMain {
    page.view == null && page.host != null && !page.isDetached
}

/** An empty second page; it holds nothing and loads nothing. */
class BlankPage : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FrameLayout(inflater.context)
}

/**
 * A channel built by hand, so that a channel page can show itself without any request:
 * `ChannelVideosFragment.getInstance(info)` takes its items from it and, with no next page,
 * never asks for more.
 *
 * The items have no upload date and are plain videos, so subscribing to this channel writes no
 * stream into the feed tables (`FeedDatabaseManager.upsertAll` skips such items).
 *
 * @param key distinguishes the channels of different tests; letters and digits only, because
 * the subscription table matches urls with `LIKE`
 */
internal fun handBuiltChannel(
    key: String,
    itemCount: Int,
    bannerUrl: String? = null,
    description: String? = null
): ChannelInfo {
    require(key.all { it.isLetterOrDigit() }) { "key must not contain LIKE wildcards: $key" }
    val serviceId = ServiceList.YouTube.serviceId
    val url = TEST_CHANNEL_HOST + key
    val handler = ListLinkHandler(url, url, key, emptyList(), emptyList())
    val info = ChannelInfo(serviceId, key, url, url, "Release views test channel $key", handler)
    info.relatedItems = (1..itemCount).map { index ->
        StreamInfoItem(serviceId, "$url/video/$index", "Test video $index", StreamType.VIDEO_STREAM)
            .apply {
                uploaderName = info.name
                duration = 60L + index
                viewCount = index.toLong()
            }
    }
    info.nextPage = null
    info.subscriberCount = -1
    info.bannerUrl = bannerUrl
    info.description = description
    return info
}

/** The list of a shown list page, or null while the page has no view. */
internal fun itemsListOf(page: Fragment): RecyclerView? = page.view?.findViewById(R.id.items_list)

/** Adapter position of the first row [list] shows, or -1 if it shows none. */
internal fun firstVisiblePosition(list: RecyclerView): Int {
    val layoutManager = list.layoutManager
    if (layoutManager is LinearLayoutManager) {
        return layoutManager.findFirstVisibleItemPosition()
    }
    val first = list.getChildAt(0) ?: return RecyclerView.NO_POSITION
    return list.getChildAdapterPosition(first)
}

/**
 * The reflective walker of plan.md section 6: what a fragment still holds of a view that is no
 * longer on screen.
 *
 * It reads the instance fields declared by the fragment's class and its superclasses down to
 * `BaseFragment` (the classes of the app; `androidx.fragment.app.Fragment` itself is not read)
 * and reports
 * - a [View] that is not attached to a window,
 * - a [ViewBinding] whose root is not attached to a window,
 * - a [RecyclerView.Adapter] that still has observers (every list it was ever set on and that
 *   was not detached from it is one),
 * - a [PagerAdapter] that still has a pager or another observer registered.
 *
 * Three things go beyond the plan's wording and are logged as Q5 in `questions.md`:
 * - Fields that hold one of the app's own helper objects (for example the error panel helper of
 *   `BaseStateFragment`, which is neither a view nor a binding but holds the page's root view)
 *   are followed [MAX_HELPER_DEPTH] levels deep. This also reaches the header binding captured
 *   by the header supplier of the list adapter, where the build compiles that method reference
 *   to a class with a field.
 * - The pager adapter check, because the tab adapter of a channel page is a [PagerAdapter] and
 *   not a [RecyclerView.Adapter]. It reads two private fields of the androidx class; if they
 *   cannot be read the adapter is not reported.
 * - The child fragments of the page (the tabs of a channel page) are walked too.
 *
 * Must be called on the main thread.
 *
 * @return one line per finding, empty if the fragment holds nothing of a detached view
 */
internal fun detachedViewsHeldBy(fragment: Fragment): List<String> {
    val findings = ArrayList<String>()
    val seen = IdentityHashMap<Any, Boolean>()
    walkFragment(fragment, fragment.javaClass.simpleName, findings, seen)
    return findings
}

private fun walkFragment(
    fragment: Fragment,
    path: String,
    findings: MutableList<String>,
    seen: IdentityHashMap<Any, Boolean>
) {
    walkFields(fragment, path, 0, findings, seen)
    val children = try {
        if (fragment.host == null) emptyList() else fragment.childFragmentManager.fragments
    } catch (e: IllegalStateException) {
        emptyList()
    }
    for (child in children) {
        walkFragment(child, "$path > ${child.javaClass.simpleName}", findings, seen)
    }
}

private fun walkFields(
    owner: Any,
    path: String,
    depth: Int,
    findings: MutableList<String>,
    seen: IdentityHashMap<Any, Boolean>
) {
    if (seen.put(owner, true) != null) {
        return
    }
    var type: Class<*>? = owner.javaClass
    while (type != null && type.name.startsWith(APP_PACKAGE)) {
        for (field in type.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) {
                continue
            }
            val value = try {
                field.isAccessible = true
                field.get(owner)
            } catch (e: Exception) {
                null
            } ?: continue
            val name = "$path.${field.name}"
            when {
                value is View -> if (!value.isAttachedToWindow) {
                    findings += "$name holds a detached ${value.javaClass.simpleName}"
                }
                value is ViewBinding -> if (!value.root.isAttachedToWindow) {
                    findings += "$name holds the binding of a detached view " +
                        "(${value.javaClass.simpleName})"
                }
                value is RecyclerView.Adapter<*> -> {
                    if (value.hasObservers()) {
                        findings += "$name (${value.javaClass.simpleName}) still has a list " +
                            "registered as observer"
                    }
                    if (depth < MAX_HELPER_DEPTH && isAppHelper(value)) {
                        walkFields(value, name, depth + 1, findings, seen)
                    }
                }
                value is PagerAdapter -> if (pagerAdapterHasObservers(value) == true) {
                    findings += "$name (${value.javaClass.simpleName}) still has a pager or " +
                        "tab bar registered as observer"
                }
                depth < MAX_HELPER_DEPTH && isAppHelper(value) ->
                    walkFields(value, name, depth + 1, findings, seen)
            }
        }
        type = type.superclass
    }
}

/** One of the app's own objects that may keep views for a fragment; not a page, not data. */
private fun isAppHelper(value: Any): Boolean {
    val className = value.javaClass.name
    return className.startsWith(APP_PACKAGE) &&
        !className.startsWith(EXTRACTOR_PACKAGE) &&
        value !is Fragment &&
        value !is Context &&
        value !is Enum<*>
}

/**
 * Whether [adapter] still has its pager (`mViewPagerObserver`) or any other data set observer
 * (a tab bar registers one) attached.
 * @return null if the private fields of the androidx class cannot be read on this build
 */
private fun pagerAdapterHasObservers(adapter: PagerAdapter): Boolean? = try {
    val pagerObserver = PagerAdapter::class.java.getDeclaredField("mViewPagerObserver")
        .apply { isAccessible = true }
        .get(adapter)
    val observable = PagerAdapter::class.java.getDeclaredField("mObservable")
        .apply { isAccessible = true }
        .get(adapter) as DataSetObservable
    val observers = android.database.Observable::class.java.getDeclaredField("mObservers")
        .apply { isAccessible = true }
        .get(observable) as Collection<*>
    pagerObserver != null || observers.isNotEmpty()
} catch (e: Exception) {
    null
}

/**
 * The retry-and-settle pattern for the garbage collector based checks: let the main thread go
 * idle (so no queued message or layout pass still holds a view), ask for a collection, run
 * finalizers, look, and try again. A collection can only be requested, never forced, so a
 * single attempt proves nothing; only "still reachable after every attempt" counts as retained.
 *
 * The caller must not hold a strong reference to what [references] point to.
 *
 * @return how many of [references] are still reachable after the last attempt
 */
internal fun stillReachableAfterGc(
    references: List<WeakReference<View>>,
    attempts: Int = 15
): Int {
    repeat(attempts) {
        settle()
        Runtime.getRuntime().gc()
        Thread.sleep(150)
        System.runFinalization()
        Runtime.getRuntime().gc()
        if (references.none { it.get() != null }) {
            return 0
        }
        Thread.sleep(250)
    }
    return references.count { it.get() != null }
}
