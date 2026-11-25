package org.schabi.newpipe.enhancements.playermemory

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.SeekBar
import com.squareup.picasso.MemoryPolicy
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper
import org.schabi.newpipe.player.seekbarpreview.SeekbarPreviewThumbnailHelper.SeekbarPreviewThumbnailType
import org.schabi.newpipe.util.PicassoHelper
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The seekbar preview images of the item the player is on, loaded only when they can be seen.
 *
 * Nothing is downloaded for an item until its seekbar is touched in a player that shows one
 * (never in the background player). From then on the sheet under the thumb is downloaded, one
 * sheet at a time, and kept in a cache of at most [MAX_SHEET_BYTES] of decoded images; until a
 * sheet has arrived the nearest frame of the sheets already there is shown. A new item drops
 * everything of the old one: its sheets are recycled, its running download is interrupted and
 * a sheet that still arrives is refused.
 *
 * All methods must be called on the main thread; only the download itself runs elsewhere. The
 * decoded sheets are referenced by the cache alone, and a bitmap handed out by [getBitmapAt] is
 * always the caller's own (the caller recycles it), never a sheet.
 */
class LazySeekbarPreviews(
    private val player: Player,
    private val refresh: (SeekBar, Int, Boolean) -> Unit
) {
    private val state = PreviewLoadState()

    private val cache = ByteBoundedCache<Int, Bitmap>(
        MAX_SHEET_BYTES,
        { it.allocationByteCount.toLong() },
        { it.recycle() }
    )

    // Its thread is created with the first download, so a player that never shows a preview
    // has none.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SeekbarPreviews").apply { isDaemon = true }
    }
    private val mainThread = Handler(Looper.getMainLooper())

    private var download: Future<*>? = null
    private var frameset: Frameset? = null
    private var table: SeekbarFrameTable? = null

    // The sheet whose download failed last, and when: it is not asked for again at once.
    private var failedPage = NO_PAGE
    private var failedAtMs = 0L

    /**
     * The player is on a new item (or reloaded the same one): everything of the old one is
     * dropped and nothing is chosen yet. Upstream calls this at the item change and [resetFrom]
     * at the first touch of the seekbar. Loads nothing.
     */
    fun clear() {
        state.onNewItem()
        download?.cancel(true)
        download = null
        cache.clear()
        frameset = null
        table = null
        failedPage = NO_PAGE
    }

    /** The item's seekbar is touched for the first time: the frameset is chosen. Loads nothing. */
    fun resetFrom(context: Context, framesets: List<Frameset>) {
        clear()

        val type = SeekbarPreviewThumbnailHelper.getSeekbarPreviewThumbnailType(context)
        if (type == SeekbarPreviewThumbnailType.NONE) {
            return
        }
        val area = Comparator.comparingInt<Frameset> { it.frameHeight * it.frameWidth }
        val picked = if (type == SeekbarPreviewThumbnailType.HIGH_QUALITY) {
            framesets.maxWithOrNull(area)
        } else {
            framesets.minWithOrNull(area)
        }
        // A frameset without usable numbers gave no previews upstream either (its generation
        // failed off the main thread); here it must not get as far as dividing by them.
        if (picked == null || picked.urls == null || picked.durationPerFrame <= 0 ||
            picked.framesPerPageX <= 0 || picked.framesPerPageY <= 0 ||
            picked.frameWidth <= 0 || picked.frameHeight <= 0
        ) {
            return
        }
        frameset = picked
        table = SeekbarFrameTable(
            FramesetShape(
                picked.urls.size,
                picked.totalCount,
                picked.durationPerFrame,
                picked.framesPerPageX,
                picked.framesPerPageY
            )
        )
    }

    /** The user put a finger on the player's seekbar at [positionMs]. */
    fun onSeekbarTouched(positionMs: Int) {
        if (state.arm(!player.audioPlayerSelected())) {
            want(positionMs)
        }
    }

    /**
     * The preview for [positionInMs], cut out of the nearest sheet that is loaded, or empty.
     * Only called for a seekbar change made by the user, so it arms like a touch and asks for
     * the sheet of the position.
     */
    fun getBitmapAt(positionInMs: Int): Optional<Bitmap> {
        if (!state.arm(!player.audioPlayerSelected())) {
            return Optional.empty()
        }
        want(positionInMs)

        val frames = frameset ?: return Optional.empty()
        val ref = table?.nearestAmong(positionInMs, cache.keys) ?: return Optional.empty()
        val sheet = cache[ref.pageIndex]
        if (sheet == null || sheet.isRecycled) {
            return Optional.empty()
        }
        return try {
            Optional.ofNullable(cutOut(sheet, frames, ref.positionMs))
        } catch (e: Exception) {
            // For example a sheet smaller than one frame. No preview is better than a crash.
            Log.w(TAG, "Could not cut the preview at $positionInMs ms out of sheet ${ref.pageIndex}", e)
            Optional.empty()
        }
    }

    /** As upstream's holder cut a frame out of its sheet. Never returns [sheet] itself. */
    private fun cutOut(sheet: Bitmap, frames: Frameset, framePositionMs: Int): Bitmap? {
        val bounds = frames.getFrameBoundsAt(framePositionMs.toLong())

        // Under some rare circumstances YouTube returns slightly too small storyboards
        // (or not the matching frame width/height), so the bounds are moved into the sheet.
        if (sheet.width < bounds[1] + frames.frameWidth) {
            bounds[1] = sheet.width - frames.frameWidth
        }
        if (sheet.height < bounds[2] + frames.frameHeight) {
            bounds[2] = sheet.height - frames.frameHeight
        }

        val cut = Bitmap.createBitmap(sheet, bounds[1], bounds[2], frames.frameWidth, frames.frameHeight)

        // createBitmap may return its source when the cut-out is the whole sheet. The caller
        // recycles what it gets and the cache recycles the sheet, so that needs a copy.
        return if (cut === sheet) sheet.copy(sheet.config ?: Bitmap.Config.ARGB_8888, true) else cut
    }

    private fun want(positionMs: Int) {
        val page = table?.nearest(positionMs)?.pageIndex ?: return
        if (page == failedPage && SystemClock.elapsedRealtime() - failedAtMs < RETRY_FAILED_AFTER_MS) {
            return
        }
        start(state.request(page, page in cache.keys))
    }

    /** Downloads the sheet [page], which [state] has just put in flight. */
    private fun start(page: Int?) {
        if (page == null) {
            return
        }
        val generation = state.generation
        val url = frameset?.urls?.getOrNull(page)
        download = executor.submit {
            var sheet: Bitmap? = null
            try {
                if (Player.DEBUG) {
                    Log.d(TAG, "Downloading preview sheet $page")
                }
                // Blocks for at most the call timeout of the image downloader. Picasso's memory
                // cache is neither read nor written, so the bitmap is this object's alone and
                // can be recycled.
                val address = url ?: throw IllegalStateException("the frameset has no address for it")
                sheet = PicassoHelper.loadSeekbarThumbnailPreview(address)
                    .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                    .get()
                if (sheet == null) {
                    Log.w(TAG, "Preview sheet $page was not loaded")
                }
            } catch (e: Exception) {
                // Also the end of a download that was interrupted because the item changed.
                Log.w(TAG, "Preview sheet $page was not loaded: $e")
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Preview sheet $page was not loaded: $e")
            } finally {
                // Every download reports back, with or without a sheet, or the page would stay
                // in flight and no other sheet of the item would ever load.
                val loaded = sheet
                if (!mainThread.post { onDownloaded(generation, page, loaded) }) {
                    loaded?.recycle()
                }
            }
        }
    }

    private fun onDownloaded(generation: Int, page: Int, sheet: Bitmap?) {
        val result = state.onLoaded(generation, page)
        if (!result.accept) {
            // A sheet of an item the player has left.
            sheet?.recycle()
            return
        }
        if (sheet == null) {
            failedPage = page
            failedAtMs = SystemClock.elapsedRealtime()
        } else {
            cache.put(page, sheet)
        }
        start(result.next)

        // Show the new sheet if the user is still seeking. Not after a failure: nothing new
        // could be drawn, and the redraw would ask for the failed sheet again.
        if (sheet != null && player.currentState.isPausedSeek) {
            val seekBar = player.binding?.playbackSeekBar ?: return
            refresh(seekBar, seekBar.progress, true)
        }
    }

    companion object {
        /** The most memory the decoded sheets of one item may take. */
        const val MAX_SHEET_BYTES = 8L * 1024 * 1024

        private const val TAG = "PlayerMemory"
        private const val NO_PAGE = -1
        private const val RETRY_FAILED_AFTER_MS = 3000L
    }
}
