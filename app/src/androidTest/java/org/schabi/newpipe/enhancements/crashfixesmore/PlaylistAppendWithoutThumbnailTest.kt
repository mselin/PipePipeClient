package org.schabi.newpipe.enhancements.crashfixesmore

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
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.LocalItemListAdapter
import org.schabi.newpipe.local.dialog.PlaylistAppendDialog
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import java.lang.reflect.InvocationTargetException

/**
 * AC-3 (plan C3a), the plan's instrumented test I1: adding a video to a local playlist that
 * has no thumbnail. Run by hand on a device or emulator, not in CI:
 *
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.enhancements.crashfixesmore.PlaylistAppendWithoutThumbnailTest`
 *
 * A playlist with one stream is inserted through the DAOs, the real "Add to playlist" dialog
 * is shown on the real `MainActivity` for a second stream, and when the dialog lists the
 * playlist its private `onPlaylistSelected(..)` (what a tap on the row calls) is called by
 * reflection on the main thread, with the entry the dialog's own list holds. Before the fix
 * this throws the `NullPointerException` of the finding; it is reported as a test failure, not
 * as an app crash.
 *
 * The test works on the app's own database (there is no other one the dialog could read). It
 * adds one playlist and two streams with urls on `crashfixesmore.invalid` and removes them
 * again; nothing else is touched.
 *
 * Not verified without a device: the dialog dismisses itself right after it started the
 * write, and its `onDestroyView` disposes the write's subscription. Upstream relies on the
 * write having started by then; so does the "one more stream" assertion here. If only that
 * assertion fails, with no exception, look at this race before the fix.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistAppendWithoutThumbnailTest {

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
        scenario?.close()
        if (playlistId >= 0) {
            database.playlistStreamDAO().deleteBatch(playlistId)
            database.playlistDAO().deletePlaylist(playlistId)
        }
        streams.forEach { database.streamDAO().delete(it) }
    }

    /**
     * AC-3, both sentences: the add works for a playlist without thumbnail (no exception, the
     * video is in the playlist), and the playlist stays without a thumbnail (answer A6a).
     * Fails before the fix with the `NullPointerException`.
     */
    @Test
    fun ac3_addToPlaylistWithoutThumbnail() {
        addSecondStream(playlistThumbnail = null)
        assertEquals(
            "AC-3: a playlist without thumbnail stays without one after the add",
            null,
            storedThumbnail()
        )
    }

    /**
     * AC-6 for plan C3a ("regression risk: none for a playlist with a thumbnail"): the same
     * add into a playlist that has a thumbnail works as before and keeps its thumbnail.
     * Passes before the fix.
     */
    @Test
    fun ac6_addToPlaylistWithThumbnail_unchanged() {
        val thumbnail = "https://crashfixesmore.invalid/thumbnail/playlist.jpg"
        addSecondStream(playlistThumbnail = thumbnail)
        assertEquals("AC-6: the playlist keeps its thumbnail", thumbnail, storedThumbnail())
    }

    private fun addSecondStream(playlistThumbnail: String?) {
        insertPlaylistWithOneStream(playlistThumbnail)
        val toAdd = streams[1]

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val dialog = PlaylistAppendDialog.newInstance(listOf(toAdd))
        launched.onMain { activity ->
            dialog.show(activity.supportFragmentManager, "crashfixesmore-append")
            activity.supportFragmentManager.executePendingTransactions()
        }

        assertTrue(
            "the Add to playlist dialog did not list the test playlist in time",
            waitUntil(LOAD_TIMEOUT_MS) { launched.onMain { entryOf(dialog) != null } }
        )

        launched.onMain {
            val entry = entryOf(dialog)!!
            assertEquals("precondition: the listed playlist has the thumbnail under test", playlistThumbnail, entry.thumbnailUrl)
            val onPlaylistSelected = PlaylistAppendDialog::class.java.getDeclaredMethod(
                "onPlaylistSelected",
                LocalPlaylistManager::class.java,
                PlaylistDuplicatesEntry::class.java,
                List::class.java
            )
            onPlaylistSelected.isAccessible = true
            try {
                onPlaylistSelected.invoke(dialog, LocalPlaylistManager(database), entry, listOf(toAdd))
            } catch (e: InvocationTargetException) {
                throw AssertionError(
                    "AC-3 (plan C3a): choosing a playlist with thumbnail `$playlistThumbnail` in the " +
                        "Add to playlist dialog threw",
                    e.targetException
                )
            }
        }

        assertTrue(
            "AC-3 (plan C3a): the video must be in the playlist after the add; streams of the " +
                "playlist now: ${storedStreamUrls()}",
            waitUntil(WRITE_TIMEOUT_MS) { storedStreamUrls() == streams.map { it.url } }
        )
    }

    private fun insertPlaylistWithOneStream(playlistThumbnail: String?) {
        val key = System.nanoTime()
        playlistId = database.playlistDAO()
            .insert(PlaylistEntity("crash-fixes-more AC-3 test $key", playlistThumbnail, -1))
        for (index in 0..1) {
            val stream = StreamEntity(
                serviceId = 0,
                url = "https://crashfixesmore.invalid/watch?v=$key-$index",
                title = "crash-fixes-more test stream $index",
                streamType = StreamType.VIDEO_STREAM,
                duration = 60,
                uploader = "crash-fixes-more test",
                thumbnailUrl = "https://crashfixesmore.invalid/thumbnail/$index.jpg"
            )
            stream.uid = database.streamDAO().upsert(stream)
            streams.add(stream)
        }
        // the playlist needs a stream: the dialog's query groups playlists without any into one row
        database.playlistStreamDAO().insert(PlaylistStreamEntity(playlistId, streams[0].uid, 0))
        assertEquals("precondition: the stored thumbnail of the playlist", playlistThumbnail, storedThumbnail())
    }

    private fun storedThumbnail(): String? =
        database.playlistDAO().getPlaylist(playlistId).blockingFirst()[0].thumbnailUrl

    private fun storedStreamUrls(): List<String> =
        database.playlistStreamDAO().getOrderedStreamsOf(playlistId).blockingFirst()
            .map { it.streamEntity.url }

    /** The row of the test playlist in the dialog's list, or null while it is not there. */
    private fun entryOf(dialog: PlaylistAppendDialog): PlaylistDuplicatesEntry? {
        val adapter = readField(PlaylistAppendDialog::class.java, dialog, "playlistAdapter")
            as LocalItemListAdapter? ?: return null
        return adapter.itemsList
            .filterIsInstance<PlaylistDuplicatesEntry>()
            .firstOrNull { it.uid == playlistId }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 10_000L
        const val WRITE_TIMEOUT_MS = 5_000L
    }
}
