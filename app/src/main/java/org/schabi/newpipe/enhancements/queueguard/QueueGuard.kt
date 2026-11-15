package org.schabi.newpipe.enhancements.queueguard

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.info_list.dialog.StreamDialogEntry
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.NavigationHelper

/**
 * Entry point of queue-guard for the `ENH:queue-guard` hooks in upstream files (plan section 2).
 *
 * Video taps (T1-T4) call [onVideoTap]. The play buttons (B1-B5) call `playOn…` with the same arguments
 * as the [NavigationHelper] method of the same name, which runs unchanged whenever nothing is to be asked
 * and for *Replace queue*. The long-press menu (M1) calls [addStreamDetailsEntry]. The play queue (Q1)
 * calls [noteAutoQueued].
 */
object QueueGuard {
    private val policy: GuardPolicy = QueueGuardPolicy(PrefsGuardSettings)
    private val tapDialogExtension: TapDialogExtension? = null

    private val controller = QueueGuardController(policy, HolderPlayerStateSource)

    /**
     * A video tap on [item] in [fragment]. `true`: taken over, the caller must skip its own statements.
     * [replay] runs the caller's tap handler again; that one call returns `false`.
     */
    @JvmStatic
    fun onVideoTap(
        fragment: Fragment,
        item: StreamInfoItem,
        startsListInBackground: Boolean,
        replay: Runnable,
    ): Boolean {
        if (fragment.view == null) {
            return false
        }
        return controller.onVideoTap(
            TapRequest(item.serviceId, item.url, item.name, startsListInBackground),
            FragmentTapActions(fragment, item, replay),
            DialogGuardUi(fragment.requireActivity(), fragment.viewLifecycleOwner, tapDialogExtension),
        )
    }

    @JvmStatic
    fun playOnMainPlayer(activity: AppCompatActivity, playQueue: PlayQueue) {
        onListPlay(activity, playQueue) { NavigationHelper.playOnMainPlayer(activity, playQueue) }
    }

    @JvmStatic
    fun playOnPopupPlayer(context: Context, queue: PlayQueue, resumePlayback: Boolean) {
        onListPlay(context, queue) { NavigationHelper.playOnPopupPlayer(context, queue, resumePlayback) }
    }

    @JvmStatic
    fun playOnBackgroundPlayer(context: Context, queue: PlayQueue, resumePlayback: Boolean) {
        onListPlay(context, queue) { NavigationHelper.playOnBackgroundPlayer(context, queue, resumePlayback) }
    }

    @JvmStatic
    fun playOnBackgroundPlayerShuffled(context: Context, queue: PlayQueue, resumePlayback: Boolean) {
        onListPlay(context, queue) {
            NavigationHelper.playOnBackgroundPlayerShuffled(context, queue, resumePlayback)
        }
    }

    /**
     * Adds "Show stream details" to a long-press menu while the background or popup player is in use
     * (AC-17), in front of "Download", unless the menu has it already. [fragment] is the menu's own
     * fragment; the entry's action gets it again when clicked.
     */
    @JvmStatic
    fun addStreamDetailsEntry(
        @Suppress("UNUSED_PARAMETER") fragment: Fragment,
        entries: MutableList<StreamDialogEntry>,
    ) {
        if (!controller.offersStreamDetails()) {
            return
        }
        val index = streamDetailsInsertIndex(
            entries.map { it.resource },
            R.string.show_stream_details,
            R.string.download,
        )
        if (index == -1) {
            return
        }
        entries.add(
            index,
            StreamDialogEntry(R.string.show_stream_details) { f, item ->
                NavigationHelper.openVideoDetailFragment(
                    f.requireContext(), f.requireActivity().supportFragmentManager,
                    item.serviceId, item.url, item.name, null, false,
                )
            },
        )
    }

    /**
     * Hook Q1: [items] were added to a queue by the player itself ("Auto-enqueue next stream"). Called on
     * any thread, inside the queue's lock: a plain insertion into the record, nothing else.
     */
    @JvmStatic
    fun noteAutoQueued(items: List<PlayerMediaItem>) {
        autoQueuedRecord.add(items.map { it.uuid })
    }

    /** A play button that would run [asToday] with [queue]. */
    private fun onListPlay(context: Context, queue: PlayQueue, asToday: Runnable) {
        val activity = context as? AppCompatActivity
        if (activity == null) {
            asToday.run()
            return
        }
        controller.onListPlay(
            queue.streams.size,
            QueueListActions(context, queue, asToday),
            DialogGuardUi(activity, activity, null),
        )
    }
}
