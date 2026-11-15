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

// Android glue between the hooks H1-H8 and KeepPositionTracker (plan 3.2). It only unwraps
// arguments, using public members of upstream classes.

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

    /** H1: `Player.reloadPlayQueueManager()`. */
    @JvmStatic
    fun onReload(player: Player) {
        tracker.onReload(
            player, player.playQueue?.item?.uuid, player.currentPosition, isLive(player)
        )
    }

    /** H6: the popup's fullscreen button, before the service is stopped. */
    @JvmStatic
    fun onSwitchToMainPlayer(player: Player) {
        tracker.onSwitch(
            player, player.playQueue?.item?.uuid, player.currentPosition, isLive(player)
        )
    }

    /** H2: `Player.sourceOf`, the start position given to the source. */
    @JvmStatic
    fun shouldSeekOnLoad(player: Player, item: PlayerMediaItem): Boolean =
        tracker.shouldSeekOnLoad(player, item.uuid)

    /** H3: `PlayerTransportController.onPlaybackSynchronize`, after `setCurrentItem`. */
    @JvmStatic
    fun onSynchronize(player: Player, item: PlayerMediaItem) {
        tracker.onSynchronize(player, item.uuid)
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
