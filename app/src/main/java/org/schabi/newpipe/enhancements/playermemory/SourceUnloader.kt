package org.schabi.newpipe.enhancements.playermemory

import android.util.Log
import org.schabi.newpipe.player.mediasource.LoadedMediaSource
import org.schabi.newpipe.player.mediasource.ManagedMediaSourcePlaylist
import org.schabi.newpipe.player.playqueue.PlayQueue

/**
 * Gives back the memory of queue items the player is no longer near.
 *
 * A loaded item keeps its media source, its stream info and, for some streams, a network
 * session. Unloading swaps it for the playlist's shared placeholder, which is exactly the state
 * of an item that was never loaded; the player's loader loads it again when it comes near.
 *
 * Must be called on the main thread, and only right after the player was synchronized to the
 * item at `currentIndex`: an item the player is still playing must never be unloaded.
 */
object SourceUnloader {
    private const val TAG = "PlayerMemory"

    /** Replaces every LoadedMediaSource outside the window by the placeholder; returns how many. */
    @JvmStatic
    fun unloadOutsideWindow(playlist: ManagedMediaSourcePlaylist, currentIndex: Int): Int {
        val size = playlist.size()
        var unloaded = 0
        for (index in 0 until size) {
            // Placeholders have nothing to free; failed sources keep their error for the player.
            if (playlist.get(index) is LoadedMediaSource &&
                !LoadWindow.keeps(index, currentIndex, playlist.size())
            ) {
                playlist.invalidate(index, null, null)
                unloaded++
            }
        }
        if (PlayQueue.DEBUG && unloaded > 0) {
            Log.d(TAG, "Unloaded $unloaded item(s) outside the window of index $currentIndex, queue of $size")
        }
        return unloaded
    }
}
