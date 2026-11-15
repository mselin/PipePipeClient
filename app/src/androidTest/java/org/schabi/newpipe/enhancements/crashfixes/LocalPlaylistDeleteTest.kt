package org.schabi.newpipe.enhancements.crashfixes

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.LocalItem
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.BaseLocalListFragment
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import java.lang.reflect.InvocationTargetException

/**
 * AC-6 (C6), the plan's instrumented test: deleting an item from a local playlist that has no
 * thumbnail. Run by hand on a device or emulator, not in CI:
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.crashfixes.LocalPlaylistDeleteTest`
 *
 * A playlist with `thumbnail_url` NULL and two streams is inserted through the DAOs, the real
 * `LocalPlaylistFragment` is shown in the real `MainActivity`, and its private
 * `deleteItem(PlaylistStreamEntry)` (what the swipe dialog and the long-press menu call) is
 * called by reflection on the main thread. Before the fix this throws the
 * `NullPointerException` of the finding; it is reported as a test failure, not as an app crash.
 *
 * The test works on the app's own database (there is no other one the fragment could read). It
 * adds one playlist and two streams with urls on `crashfixes.invalid` and removes them again;
 * nothing else is touched. No request is needed: the items have no thumbnail, or one on a host
 * that does not resolve.
 */
@RunWith(AndroidJUnit4::class)
class LocalPlaylistDeleteTest {

    private lateinit var database: AppDatabase
    private var playlistId = -1L
    private val streams = ArrayList<StreamEntity>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun openDatabase() {
        database = NewPipeDatabase.getInstance(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
    }

    @After
    fun removeTestRows() {
        // closing first: leaving the page saves the playlist, which must not happen after the delete
        scenario?.close()
        if (playlistId >= 0) {
            waitUntil(SAVE_TIMEOUT_MS) { saveIsDone() }
            database.playlistStreamDAO().deleteBatch(playlistId)
            database.playlistDAO().deletePlaylist(playlistId)
        }
        streams.forEach { database.streamDAO().delete(it) }
    }

    /**
     * AC-6, the case of the finding: the playlist has no thumbnail, the items have one.
     * Expected: no exception, one item left in the list.
     */
    @Test
    fun ac6_deleteFromPlaylistWithoutThumbnail() {
        deleteFirstOfTwo(itemThumbnail = { "https://crashfixes.invalid/thumbnail/$it.jpg" })
    }

    /**
     * AC-6, "or the item has no thumbnail": neither the playlist nor the items have one. After
     * the fix this is the "thumbnail was removed" branch (plan C6, regression risk; answer A3),
     * which must work as well. Expected: no exception, one item left in the list.
     */
    @Test
    fun ac6_deleteItemWithoutThumbnailFromPlaylistWithoutThumbnail() {
        deleteFirstOfTwo(itemThumbnail = { null })
    }

    private fun deleteFirstOfTwo(itemThumbnail: (Int) -> String?) {
        val name = "crash-fixes AC-6 test"
        insertPlaylistWithoutThumbnail(name, itemThumbnail)

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val page = LocalPlaylistFragment.getInstance(playlistId, name)
        launched.onMain { activity ->
            val fragmentManager = activity.supportFragmentManager
            fragmentManager.beginTransaction()
                .replace(R.id.fragment_holder, page)
                .addToBackStack(null)
                .commit()
            fragmentManager.executePendingTransactions()
        }

        assertTrue(
            "the playlist page did not show its two items in time",
            waitUntil(LOAD_TIMEOUT_MS) { launched.onMain { itemsOf(page)?.size == 2 } }
        )

        launched.onMain {
            val first = itemsOf(page)!![0] as PlaylistStreamEntry
            val deleteItem = LocalPlaylistFragment::class.java
                .getDeclaredMethod("deleteItem", PlaylistStreamEntry::class.java)
            deleteItem.isAccessible = true
            try {
                deleteItem.invoke(page, first)
            } catch (e: InvocationTargetException) {
                throw AssertionError("AC-6: deleting an item from a playlist without thumbnail threw", e.targetException)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val left = launched.onMain { itemsOf(page)!!.map { (it as PlaylistStreamEntry).streamEntity.url } }
        assertEquals("AC-6: one item must be left after the delete", listOf(streams[1].url), left)
    }

    private fun insertPlaylistWithoutThumbnail(name: String, itemThumbnail: (Int) -> String?) {
        val key = System.nanoTime()
        playlistId = database.playlistDAO().insert(PlaylistEntity(name, null, -1))
        for (index in 0..1) {
            val stream = StreamEntity(
                serviceId = 0,
                url = "https://crashfixes.invalid/watch?v=$key-$index",
                title = "crash-fixes test stream $index",
                streamType = StreamType.VIDEO_STREAM,
                duration = 60,
                uploader = "crash-fixes test",
                thumbnailUrl = itemThumbnail(index)
            )
            stream.uid = database.streamDAO().upsert(stream)
            streams.add(stream)
            database.playlistStreamDAO().insert(PlaylistStreamEntity(playlistId, stream.uid, index))
        }
        assertEquals(
            "precondition: the playlist has no thumbnail",
            null,
            database.playlistDAO().getPlaylist(playlistId).blockingFirst()[0].thumbnailUrl
        )
    }

    /** The items the page shows, or null while it has no list. */
    private fun itemsOf(page: LocalPlaylistFragment): List<LocalItem>? {
        val field = BaseLocalListFragment::class.java.getDeclaredField("itemListAdapter")
        field.isAccessible = true
        return (field.get(page) as LocalItemListAdapter?)?.itemsList
    }

    /**
     * After a delete the page saves the new order when it is left. That write should be over
     * before the test rows are removed: it is when the stored playlist no longer has both items.
     * If the delete failed nothing is saved, and the wait simply runs out.
     */
    private fun saveIsDone(): Boolean =
        database.playlistStreamDAO().getOrderedStreamsOf(playlistId).blockingFirst().size != 2

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
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

    /**
     * Runs [block] on the main thread and returns its result. Anything it throws (assertion
     * errors included) is rethrown on the test thread instead of crashing the app process.
     */
    private fun <T> ActivityScenario<MainActivity>.onMain(block: (MainActivity) -> T): T {
        var outcome: Result<T>? = null
        onActivity { activity -> outcome = runCatching { block(activity) } }
        return checkNotNull(outcome) { "the main thread action did not run" }.getOrThrow()
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 10_000L
        const val SAVE_TIMEOUT_MS = 3_000L
    }
}
