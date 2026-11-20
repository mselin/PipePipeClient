package org.schabi.newpipe.enhancements.playermemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new files of plan sections 2 and 3, read as text. `SourceUnloader` and
 * `LazySeekbarPreviews` are glue around ExoPlayer, bitmaps, Picasso and the main thread and
 * cannot run on the JVM; these tests pin only the few things in them that an acceptance
 * criterion stands on and that a refactoring could drop without any other test failing. What
 * they do on a device is D2 to D4 of the plan's device script.
 *
 * Until the Code layer writes the files, every test here fails with "does not exist" (the two
 * constant tests do not compile before that, like the logic tests).
 */
class NewFilesSourceTest {

    private val dir = "java/org/schabi/newpipe/enhancements/playermemory"

    /** Plan sections 2 and 3: the four plain Kotlin files. */
    private val plain = listOf("LoadWindow.kt", "SeekbarFrameTable.kt", "ByteBoundedCache.kt", "PreviewLoadState.kt")

    private fun source(ac: String, name: String): String = MainFiles.lines(ac, "$dir/$name").joinToString("\n")

    private fun assertMentions(ac: String, name: String, snippet: String, why: String) {
        assertTrue("$ac: app/src/main/$dir/$name must contain `$snippet`: $why", MainFiles.count(source(ac, name), snippet) > 0)
    }

    /**
     * PROCESS.md: decision logic is plain Kotlin with no Android imports. The four logic files
     * import nothing from Android, ExoPlayer or Picasso, and do not log through Android.
     */
    @Test
    fun logicFilesHaveNoAndroidImports() {
        for (name in plain) {
            val text = source("plan sections 2 and 3", name)
            val imports = text.lines().filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$dir/$name must not import Android, ExoPlayer or Picasso classes",
                emptyList<String>(),
                imports.filter {
                    it.contains("import android.") || it.contains("import androidx.") ||
                        it.contains("com.google.android.") || it.contains("com.squareup.picasso")
                }
            )
            assertFalse("app/src/main/$dir/$name must not use android.util.Log", text.contains("android.util.Log"))
            assertFalse("app/src/main/$dir/$name must not use Bitmap", text.contains("Bitmap"))
        }
    }

    /** Plan sections 0, 2 and 3: the package holds exactly the six planned files. */
    @Test
    fun thePackageHoldsTheSixPlannedFiles() {
        source("plan section 0", "LoadWindow.kt") // "does not exist" until Code wrote the files
        val names = MainFiles.file(dir).listFiles()!!.map { it.name }.sorted()

        assertEquals(
            "plan section 0: the new files of the package",
            listOf("ByteBoundedCache.kt", "LazySeekbarPreviews.kt", "LoadWindow.kt", "PreviewLoadState.kt", "SeekbarFrameTable.kt", "SourceUnloader.kt"),
            names
        )
    }

    // ---- SourceUnloader (AC-4) ----

    /** AC-4: the unloader asks `LoadWindow.keeps` and replaces through the existing `invalidate`, for loaded sources only. */
    @Test
    fun ac4_sourceUnloader_asksLoadWindow_andInvalidatesLoadedSourcesOnly() {
        val name = "SourceUnloader.kt"
        assertMentions("AC-4", name, "fun unloadOutsideWindow(playlist: ManagedMediaSourcePlaylist, currentIndex: Int): Int", "plan section 2: the signature")
        assertMentions("AC-4", name, "@JvmStatic", "MediaSourceManager.java calls SourceUnloader.unloadOutsideWindow statically")
        assertMentions("AC-4", name, "object SourceUnloader", "plan section 2")
        assertMentions("AC-4", name, "LoadWindow.keeps(", "the decision is LoadWindow's, which the unit tests cover")
        assertMentions("AC-4", name, "is LoadedMediaSource", "failed sources and placeholders are left alone")
        assertMentions("AC-4", name, ".invalidate(", "unloading is the existing replace-by-placeholder operation")
        assertMentions("AC-4", name, "null, null)", "plan section 2: playlist.invalidate(i, null, null)")
        assertMentions("AC-4", name, "playlist.size()", "the queue size given to LoadWindow is the playlist's")
        assertMentions("AC-4", name, "\"PlayerMemory\"", "D2 reads the unloads from `adb logcat -s PlayerMemory`")
        for (other in listOf(".remove(", ".move(", ".append(", ".expand(", "reloadPlayQueueManager", "MediaSourceManager(")) {
            assertEquals(
                "AC-4/AC-5: app/src/main/$dir/$name only invalidates; it must not contain `$other` (plan section 6: no reload path)",
                0,
                MainFiles.count(source("AC-4", name), other)
            )
        }
    }

    // ---- LazySeekbarPreviews (AC-1, AC-2, AC-3, AC-7) ----

    /** AC-3: the limit is 8 MiB. Compiled against the constant, so it is the number the cache is built with. */
    @Test
    fun ac3_theLimitIsEightMebibytes() {
        assertEquals("AC-3, A2: 8 MiB of decoded sheets per item", 8L * 1024 * 1024, LazySeekbarPreviews.MAX_SHEET_BYTES)
        assertEquals(LIMIT_BYTES, LazySeekbarPreviews.MAX_SHEET_BYTES)
        assertEquals(
            "plan section 3: eight sheets of 960x540 RGB_565 fit, a ninth does not",
            SHEETS_WITHIN_LIMIT.toLong(),
            LazySeekbarPreviews.MAX_SHEET_BYTES / SHEET_BYTES
        )
    }

    /** AC-3: the sheets are held in a `ByteBoundedCache` of that limit, measured by allocation size, recycled on eviction. */
    @Test
    fun ac3_lazyPreviews_holdSheetsInTheByteBoundedCache() {
        val name = "LazySeekbarPreviews.kt"
        assertMentions("AC-3", name, "ByteBoundedCache", "plan section 3: the cache")
        assertMentions("AC-3", name, "(MAX_SHEET_BYTES,", "the cache is built with the limit as its first argument")
        assertMentions("AC-3", name, "allocationByteCount", "the size of a sheet is its allocation, not an estimate")
        assertMentions("AC-3", name, ".recycle()", "an evicted or refused sheet is recycled")
        // Nothing else may keep decoded sheets: no second collection of bitmaps next to the cache.
        for (other in listOf("SparseArray", "List<Bitmap", "Map<Int, Bitmap", "Set<Bitmap", "Array<Bitmap", "LruCache")) {
            assertEquals("AC-3: app/src/main/$dir/$name must not keep sheets outside the cache (`$other`)", 0, MainFiles.count(source("AC-3", name), other))
        }
    }

    /** AC-1: a download can start only through `PreviewLoadState`, armed by a touch in a player that is not the background player. */
    @Test
    fun ac1_lazyPreviews_loadOnlyThroughTheLoadState_armedByATouchOutsideTheBackgroundPlayer() {
        val name = "LazySeekbarPreviews.kt"
        assertMentions("AC-1", name, "PreviewLoadState()", "plan section 3")
        assertMentions("AC-1", name, ".arm(!player.audioPlayerSelected())", "the background player never arms")
        assertMentions("AC-1", name, ".request(", "what to download is PreviewLoadState's decision")
        assertMentions("AC-1", name, ".onLoaded(", "every result goes back through PreviewLoadState with its generation")
        assertEquals(
            "AC-1: one place downloads a sheet",
            1,
            MainFiles.count(source("AC-1", name), "PicassoHelper.loadSeekbarThumbnailPreview(")
        )
        assertMentions("AC-1", name, "\"PlayerMemory\"", "D4 reads the downloads from `adb logcat -s PlayerMemory`")
    }

    /** AC-2: `resetFrom` moves to a new generation, stops the running download and clears the cache. */
    @Test
    fun ac2_lazyPreviews_resetReleasesAndCancels() {
        val name = "LazySeekbarPreviews.kt"
        assertMentions("AC-2", name, ".onNewItem()", "a result of the old item is refused by its generation")
        assertMentions("AC-2", name, ".cancel(true)", "plan section 3: the running download is interrupted")
        assertMentions("AC-2", name, ".clear()", "the old item's sheets are released")
        assertMentions("AC-2", name, "newSingleThreadExecutor", "plan section 3: one single-thread executor owned by the object")
        assertEquals(
            "AC-2: one executor for the life of the object, not one per item as upstream",
            1,
            MainFiles.count(source("AC-2", name), "newSingleThreadExecutor")
        )
    }

    /** AC-7: the frame shown comes from the table over the loaded sheets, cut out with upstream's bounds. */
    @Test
    fun ac7_lazyPreviews_cutOutAsUpstream() {
        val name = "LazySeekbarPreviews.kt"
        assertMentions("AC-7", name, "SeekbarFrameTable(", "plan section 3")
        assertMentions("AC-7", name, ".nearestAmong(", "the frame shown is the nearest one of the loaded sheets")
        assertMentions("AC-7", name, ".getFrameBoundsAt(", "bounds as upstream")
        assertMentions("AC-7", name, "Bitmap.createBitmap(", "the cut-out as upstream :198")
        assertMentions("AC-7", name, ".isRecycled", "a recycled sheet gives no preview instead of a crash (upstream :182)")
        assertMentions("AC-7", name, ".copy(", "a cut-out identical to its sheet is copied (upstream :203), or recycling the sheet would blank the preview")
        assertMentions("AC-7", name, "getSeekbarPreviewThumbnailType(", "the frameset is picked by the existing setting")
    }

    /** Plan section 3: the signatures the hooks in `PlayerProgressController.kt` compile against. */
    @Test
    fun lazyPreviews_haveThePlannedSignatures() {
        val name = "LazySeekbarPreviews.kt"
        assertMentions("plan section 3", name, "class LazySeekbarPreviews(", "the class")
        assertMentions("plan section 3", name, "private val player: Player", "first constructor parameter")
        assertMentions("plan section 3", name, "private val refresh: (SeekBar, Int, Boolean) -> Unit", "second constructor parameter, ::onProgressChanged")
        assertMentions("plan section 3", name, "fun resetFrom(context: Context, framesets: List<Frameset>)", "called at PlayerProgressController.kt:44")
        assertMentions("plan section 3", name, "fun onSeekbarTouched(positionMs: Int)", "the new hook")
        assertMentions("plan section 3", name, "fun getBitmapAt(positionInMs: Int): Optional<Bitmap>", "called at PlayerProgressController.kt:199")
    }
}
