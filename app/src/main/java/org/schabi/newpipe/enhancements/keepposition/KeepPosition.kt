package org.schabi.newpipe.enhancements.keepposition

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.StreamTypeUtil

// Android glue between the hooks and KeepPositionTracker (plan 3.2 and 10.5). It unwraps
// arguments, using public members of upstream classes only, and stores a position the tracker
// returns in the queue; the seek to it stays upstream's.

/** The two switches, read from the default shared preferences on every access. */
class PrefsKeepPositionSettings(private val context: Context) : KeepPositionSettings {
    override val alwaysStartFromBeginning: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.always_start_from_beginning_key), false)

    override val keepPosition: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.enh_keep_position_key), true)
}

object KeepPosition {
    private val tracker by lazy { KeepPositionTracker(PrefsKeepPositionSettings(App.getApp())) }

    /**
     * H1: `Player.reloadPlayQueueManager()`. Upstream's `setRecovery()` stores nothing while the
     * duration is unknown; the mark's position fills that gap and never replaces a stored one.
     */
    @JvmStatic
    fun onReload(player: Player) {
        val queue = player.playQueue
        val item = queue?.item
        val position = tracker.onReload(
            player, item?.uuid, player.currentPosition, isLive(player), dur(player)
        )
        storeIfUnset(queue, item, position)
    }

    /** H6: the popup's fullscreen button, before the service is stopped. */
    @JvmStatic
    fun onSwitchToMainPlayer(player: Player) {
        val queue = player.playQueue
        val item = queue?.item
        val position = tracker.onSwitch(
            player, item?.uuid, player.currentPosition, isLive(player), dur(player)
        )
        storeIfUnset(queue, item, position)
    }

    /** H2: `Player.sourceOf`, the start position given to the source. */
    @JvmStatic
    fun shouldSeekOnLoad(player: Player, item: PlayerMediaItem): Boolean =
        tracker.shouldSeekOnLoad(player, item.uuid)

    /** H3: `PlayerTransportController.onPlaybackSynchronize`, after `setCurrentItem`. */
    @JvmStatic
    fun onSynchronize(player: Player, item: PlayerMediaItem) {
        tracker.onSynchronize(player, item.uuid, itemAddress(item.serviceId, item.url))
    }

    /** H4: `PlayerTransportController.onPlaybackSynchronize`, the seek to the stored position. */
    @JvmStatic
    fun shouldSeekOnSynchronize(player: Player, item: PlayerMediaItem): Boolean =
        tracker.shouldSeekOnSynchronize(player, item.uuid)

    /** H5: `PlayerStartController.handleIntent`, the seek in place. */
    @JvmStatic
    fun shouldSeekInPlace(
        player: Player,
        linkQueue: PlayQueue,
        oldPlayerType: PlayerService.PlayerType
    ): Boolean = tracker.shouldSeekInPlace(
        player,
        linkQueue.item?.uuid,
        player.playQueue?.item?.uuid,
        oldPlayerType != player.playerType
    )

    /** H7: `InternalUrlsHandler.playOnMain`, a queue built from a timestamp link. */
    @JvmStatic
    fun onTimestampLink(queue: PlayQueue) {
        val item = queue.item ?: return
        if (isTimedLinkStart(queue.getRecoveryPosition(item) / 1000, false, live(item.streamType))) {
            tracker.onTimedStart(item.uuid)
        }
    }

    /** H8: `SinglePlayQueue(StreamInfo)`, a queue built from a start time in a video's address. */
    @JvmStatic
    fun onLinkStartPosition(queue: PlayQueue, info: StreamInfo) {
        val item = queue.item ?: return
        if (isTimedLinkStart(info.startPosition, info.isRoundPlayStream, live(info.streamType))) {
            tracker.onTimedStart(item.uuid)
        }
    }

    /**
     * E1: `PlayerErrorHandler.onPlayerError`, first statement after the log line: ExoPlayer is
     * idle and still holds the position of the error.
     */
    @JvmStatic
    fun onErrorBegin(player: Player) {
        val item = player.playQueue?.item
        tracker.onErrorBegin(
            player, item?.uuid, item?.let(::address), player.currentPosition, isLive(player),
            dur(player)
        )
    }

    /** D1: `Player.destroy()`, which every close passes, before the ExoPlayer is released. */
    @JvmStatic
    fun onPlayerClosed(player: Player) {
        tracker.onPlayerClosed(player, player.currentPosition, dur(player), player.isPlaying)
    }

    /**
     * F1: `VideoDetailFragment.hideMainPlayerOnLoadingNewStream`, before the page stops the
     * service. The page's nullable field is passed as it is, so that the hook stays one line.
     */
    @JvmStatic
    fun onPageStopsPlayer(player: Player?, pageServiceId: Int, pageUrl: String?) {
        if (player == null) {
            return
        }
        val item = player.playQueue?.item
        tracker.onPageStopsPlayer(
            player, item?.uuid, item?.let(::address), player.currentPosition, isLive(player),
            player.currentState.isCompleted, dur(player), player.isPlaying,
            itemAddress(pageServiceId, pageUrl)
        )
    }

    /**
     * G1: `PlayerStartController.handleIntent`, after the enqueue returns and before the new
     * queue's stored position is read or the old ExoPlayer is destroyed. A returned position is
     * "the position it had" and replaces whatever the new queue has stored.
     */
    @JvmStatic
    fun onStartIntent(player: Player, newQueue: PlayQueue) {
        val playing = player.playQueue?.item
        val new = newQueue.item
        val position = tracker.onStartIntent(
            player, playing?.uuid, playing?.let(::address), player.currentPosition, isLive(player),
            player.currentState.isCompleted, dur(player), new?.uuid, new?.let(::address),
            player.playQueue == newQueue
        )
        if (new != null && position > 0) {
            newQueue.setRecovery(newQueue.index, position)
        }
    }

    private fun storeIfUnset(queue: PlayQueue?, item: PlayerMediaItem?, position: Long) {
        if (queue != null && item != null && shouldStore(position, queue.getRecoveryPosition(item))) {
            queue.setRecovery(queue.index, position)
        }
    }

    private fun address(item: PlayerMediaItem): String? = itemAddress(item.serviceId, item.url)

    /** Whether the player knows its duration: 0 without an ExoPlayer, negative while unknown. */
    private fun dur(player: Player): Boolean = player.duration > 0

    /** Mirrors the private `Player.isCurrentStreamLive()` with public members only. */
    private fun isLive(player: Player): Boolean {
        val item = player.playQueue?.item
        if (item != null && live(item.streamType)) {
            return true
        }
        val info = player.currentStreamInfo.orElse(null)
        return info != null && live(info.streamType)
    }

    private fun live(streamType: StreamType?): Boolean = StreamTypeUtil.isLiveStream(streamType)
}
