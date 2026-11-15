package org.schabi.newpipe.enhancements.queueguard

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.NavigationHelper

// Android glue between the plain-Kotlin controller and upstream (plan 3.3). Every effect reuses
// upstream code: the long-press entries' own actions and NavigationHelper.

/** The switch "Ask before replacing the queue", read from the default shared preferences on every call. */
internal object PrefsGuardSettings : GuardSettings {
    override fun askBeforeReplacing(): Boolean {
        val app = App.getApp()
        return PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean(app.getString(R.string.enh_queue_guard_ask_key), ASK_BEFORE_REPLACING_DEFAULT)
    }
}

/** The player as [PlayerHolder] sees it. */
internal object HolderPlayerStateSource : PlayerStateSource {
    override fun current(): PlayerState {
        val holder = PlayerHolder.getInstance()
        val kind = when (holder.type) {
            PlayerService.PlayerType.VIDEO -> PlayerKind.MAIN
            PlayerService.PlayerType.AUDIO -> PlayerKind.BACKGROUND
            PlayerService.PlayerType.POPUP -> PlayerKind.POPUP
            null -> null
        }
        val current = holder.player?.playQueue?.item
        return PlayerState(
            kind = kind,
            queueSize = holder.queueSize,
            currentServiceId = current?.serviceId ?: -1,
            currentUrl = current?.url,
        )
    }
}

/** The tap actions of [item] in [fragment]: today's tap is [replay], the others are long-press entries. */
internal class FragmentTapActions(
    private val fragment: Fragment,
    private val item: StreamInfoItem,
    private val replay: Runnable,
) : TapActions {
    override fun openAsToday() = replay.run()

    override fun enqueue() = StreamDialogDefaultEntry.ENQUEUE.action.onClick(fragment, item)

    override fun enqueueNext() = StreamDialogDefaultEntry.ENQUEUE_NEXT.action.onClick(fragment, item)

    override fun startInBackground() =
        StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND.action.onClick(fragment, item)

    override fun startInPopup() = StreamDialogDefaultEntry.START_HERE_ON_POPUP.action.onClick(fragment, item)
}

/** The list actions of a play button that would play [queue]: today's press is [asToday]. */
internal class QueueListActions(
    private val context: Context,
    private val queue: PlayQueue,
    private val asToday: Runnable,
) : ListActions {
    override fun playAsToday() = asToday.run()

    override fun enqueueAll() = NavigationHelper.enqueueOnPlayer(context, queue)

    /**
     * Upstream's "enqueue next" moves only the last video of a list behind the current one, so the list is
     * appended to the player's own queue and then moved behind its current item, one move per video.
     */
    override fun enqueueAllNext() {
        val target = PlayerHolder.getInstance().player?.playQueue
        if (target == null) {
            NavigationHelper.enqueueNextOnPlayer(context, queue)
            return
        }
        Toast.makeText(context, R.string.enqueued_next, Toast.LENGTH_SHORT).show()
        target.append(queue.streams)
        for (move in movesForPlayNext(target.size(), queue.streams.size, target.index)) {
            target.move(move.from, move.to)
        }
    }
}
