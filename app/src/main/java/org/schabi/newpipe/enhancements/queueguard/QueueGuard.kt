package org.schabi.newpipe.enhancements.queueguard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.fragments.list.videos.RelatedItemsFragment
import org.schabi.newpipe.info_list.dialog.StreamDialogEntry
import org.schabi.newpipe.player.event.DetailPlayerEvents
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.KEY_LINK_TYPE
import org.schabi.newpipe.util.KEY_SERVICE_ID
import org.schabi.newpipe.util.KEY_URL
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.SparseItemUtil
import org.schabi.newpipe.util.ThemeHelper
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Entry point of queue-guard for the `ENH:queue-guard` hooks in upstream files (plan section 2, A2.3,
 * A2.15, A2.16). Main thread only.
 *
 * Video taps (T1-T4) call [onVideoTap]. The play buttons (B1-B5) call `playOn…` with the same arguments
 * as the [NavigationHelper] method of the same name, which runs unchanged whenever nothing is to be asked
 * and for *Replace queue*. The long-press menu (M1) calls [addStreamDetailsEntry].
 *
 * The video page calls [onPageControl] from its play controls (P1-P4) and [playersQueueFor] where it
 * sends a queue to the player (P5) and where it has loaded a video (P6). "Details" of a queue item (D1)
 * calls [openQueueItemDetails], a link with a time (L1) [onTimedLink], a video address typed in search
 * (L2) [onSearchAddress], and the screen that handles links and videos from other apps [onRouterChoice]
 * (R1) and [isAsking] (R2).
 *
 * The hooks that return a Boolean: `true` means taken over, the caller must skip its own statements.
 * Their `replay` runs those statements again; where that passes the hook once more, it returns `false`
 * that one time.
 */
object QueueGuard {
    private val policy: GuardPolicy = org.schabi.newpipe.enhancements.defaultopenaction.DefaultOpenAction.policy(QueueGuardPolicy(PrefsGuardSettings), PrefsGuardSettings) // ENH:default-open-action
    private val tapDialogExtension: TapDialogExtension? = org.schabi.newpipe.enhancements.defaultopenaction.DefaultOpenAction.tapDialogExtension // ENH:default-open-action

    private val controller = QueueGuardController(policy, HolderPlayerStateSource)

    /** The router screens that show the tap dialog right now ([isAsking]). */
    private val askingIn: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * The player's queue at the moment a play button was let through to replace it by a list that
     * starts with the video the player is on. [playersQueueFor] does not hand this queue back to the
     * video page, which was just given the list to play instead. Forgotten as soon as the player holds
     * another queue.
     */
    private var replacedByAList: WeakReference<PlayQueue>? = null

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
        // AM-2: a tap in a list of related videos says whose list it is; the rules compare.
        val relatedTo = (fragment as? RelatedItemsFragment)?.let { related ->
            related.getUrl()?.let { VideoRef(related.getServiceId(), it) }
        }
        return controller.onVideoTap(
            TapRequest(item.serviceId, item.url, item.name, startsListInBackground, relatedTo = relatedTo),
            FragmentTapActions(fragment, item, replay),
            DialogGuardUi(fragment.requireActivity(), fragment.viewLifecycleOwner, tapDialogExtension),
        )
    }

    @JvmStatic
    fun playOnMainPlayer(activity: AppCompatActivity, playQueue: PlayQueue) {
        onListPlay(activity, playQueue) {
            rememberQueueReplacedBy(playQueue)
            NavigationHelper.playOnMainPlayer(activity, playQueue)
        }
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
     * Hooks P1-P4 (AM-3): a play control of the video page that shows [info] was pressed: play or the
     * thumbnail, Background, Popup, the mini player's play button. On the page of another video than
     * the player's current item the dialog opens for that video; *Play now* is [replay], the pressed
     * control. On the page of the current item nothing is taken over, and [playersQueueFor] gives the
     * control the player's queue to act on (AM-9).
     */
    @JvmStatic
    fun onPageControl(fragment: Fragment, info: StreamInfo?, replay: Runnable): Boolean {
        val url = info?.url
        if (info == null || url == null || fragment.view == null) {
            return false
        }
        val activity = fragment.requireActivity()
        return controller.onVideoTap(
            TapRequest(info.serviceId, url, info.name.orEmpty(), way = OpenWay.PAGE_CONTROL),
            DirectTapActions(activity.applicationContext, { use -> use(SinglePlayQueue(info)) }, replay),
            DialogGuardUi(activity, fragment.viewLifecycleOwner, tapDialogExtension),
        )
    }

    /**
     * Hooks P5 and P6 (AM-9, AM-11): the player's own queue if the player holds one and its current
     * item is the video [info] (same service, same address), else `null`. The video page sends this
     * queue instead of its own (P5), so that a control on the page of the current item resumes the
     * queue or moves it to another player and never replaces it by that one video; and a page that
     * has just loaded the current item takes it over instead of stopping the player (P6). Reads no
     * setting.
     */
    @JvmStatic
    fun playersQueueFor(info: StreamInfo?): PlayQueue? {
        val url = info?.url ?: return null
        val queue = PlayerHolder.getInstance().player?.playQueue
        if (queue == null || queue.isEmpty) {
            return null
        }
        if (!HolderPlayerStateSource.current().isCurrent(VideoRef(info.serviceId, url))) {
            return null
        }
        val replaced = replacedByAList?.get()
        if (replaced === queue) {
            return null
        }
        replacedByAList = null
        return queue
    }

    /**
     * Hook D1 (AM-4): "Details" in the menu of [item] of the player's [queue]. `false`: beside the
     * background or popup player upstream opens the item's page, which touches neither player nor
     * queue. With the main player the page is the player: the queue screen ([context]) is closed and
     * the page shown, after moving playback to [item] inside the queue if it is not the current item.
     * No way of opening a page is used there, because each of them stops the main player first.
     */
    @JvmStatic
    fun openQueueItemDetails(context: Context, queue: PlayQueue, item: PlayerMediaItem): Boolean {
        when (queueItemDetails(HolderPlayerStateSource.current(), queue.indexOf(item), queue.index)) {
            DetailsEffect.AS_TODAY -> return false
            DetailsEffect.NONE -> return true
            // Not for the current item: selecting it would restart it from the beginning.
            DetailsEffect.SELECT_THEN_SHOW_PAGE -> PlayerHolder.getInstance().player?.selectQueueItem(item)
            DetailsEffect.SHOW_PLAYING_PAGE -> Unit
        }
        NavigationHelper.expandMainPlayer()
        activityOf<Activity>(context)?.finish()
        return true
    }

    /**
     * Hook L1 (AM-5, AM-10): a link to [seconds] into the video at [url], tapped in a description or a
     * comment. In the current item it seeks in place, whichever player is in use and however long the
     * queue, and upstream's statement (which would open the video alone) is skipped. For another video
     * the dialog opens where opening its page would replace the queue; [replay] is upstream's statement.
     */
    @JvmStatic
    fun onTimedLink(
        context: Context,
        service: StreamingService,
        url: String,
        seconds: Int,
        replay: Runnable,
    ): Boolean {
        val streamUrl = streamUrl(service, url) ?: return false
        if (HolderPlayerStateSource.current().isCurrent(VideoRef(service.serviceId, streamUrl))) {
            DetailPlayerEvents.seekTo(seconds)
            return true
        }
        val activity = activityOf<FragmentActivity>(context) ?: return false
        val app = activity.applicationContext
        return controller.onVideoTap(
            TapRequest(service.serviceId, streamUrl, streamUrl, way = OpenWay.VIDEO_PAGE),
            DirectTapActions(app, loader(app, service.serviceId, streamUrl, seconds * 1000L), replay),
            DialogGuardUi(activity, activity, tapDialogExtension),
        )
    }

    /**
     * Hook L2 (AM-5): an address typed in search was recognised and [intent] would open it. Only a video
     * is looked at. [replay] repeats the two upstream statements below the hook.
     */
    @JvmStatic
    fun onSearchAddress(fragment: Fragment, intent: Intent, replay: Runnable): Boolean {
        @Suppress("DEPRECATION")
        val linkType = intent.getSerializableExtra(KEY_LINK_TYPE)
        val url = intent.getStringExtra(KEY_URL)
        if (linkType != StreamingService.LinkType.STREAM || url == null || fragment.view == null) {
            return false
        }
        val serviceId = intent.getIntExtra(KEY_SERVICE_ID, 0)
        val service = try {
            NewPipe.getService(serviceId)
        } catch (e: Exception) {
            null
        }
        val activity = fragment.requireActivity()
        val app = activity.applicationContext
        val taken = controller.onVideoTap(
            TapRequest(serviceId, service?.let { streamUrl(it, url) } ?: url, url, way = OpenWay.VIDEO_PAGE),
            DirectTapActions(app, loader(app, serviceId, url, null), replay),
            DialogGuardUi(activity, fragment.viewLifecycleOwner, tapDialogExtension),
        )
        if (taken) {
            // The search screen shows its spinner until the page opens; with the dialog it stays.
            (fragment as? BaseStateFragment<*>)?.hideLoading()
        }
        return taken
    }

    /**
     * Hook R1 (AM-5, AM-6): the screen that handles links and videos from other apps ([activity], which
     * shows nothing but dialogs) is about to carry out [choiceKey] for [url]. Only a video and only the
     * actions that play it are looked at. [replay] runs the screen's handler again.
     *
     * The screen is finished on every way out of the dialog: after *Add to queue* and *Play next* at
     * once (the video is loaded with the application context), after *Play now* by the handler itself,
     * as today, and after Cancel, Back, a tap outside or leaving the app by the dialog's dismissal.
     */
    @JvmStatic
    fun onRouterChoice(
        activity: AppCompatActivity,
        service: StreamingService?,
        linkType: StreamingService.LinkType?,
        url: String?,
        choiceKey: String,
        replay: Runnable,
    ): Boolean {
        if (linkType != StreamingService.LinkType.STREAM || service == null || url == null) {
            return false
        }
        val way = openWayOfRouterChoice(
            choiceKey,
            activity.getString(R.string.show_info_key),
            activity.getString(R.string.video_player_key),
            activity.getString(R.string.background_player_key),
            activity.getString(R.string.popup_player_key),
        ) ?: return false
        val app = activity.applicationContext
        // The screen's own theme is transparent; its chooser is built with this one too.
        val themed = ContextThemeWrapper(
            activity,
            if (ThemeHelper.isLightThemeSelected(activity)) R.style.LightTheme else R.style.DarkTheme,
        )
        val added = askingIn.add(activity)
        var taken = false
        try {
            taken = controller.onVideoTap(
                TapRequest(service.serviceId, streamUrl(service, url) ?: url, url, way = way),
                DirectTapActions(
                    app,
                    loader(app, service.serviceId, url, null),
                    replay,
                    afterEnqueue = { activity.finish() },
                ),
                DialogGuardUi(
                    themed,
                    activity,
                    tapDialogExtension,
                    dismissOnStop = true,
                    onDismiss = { chosen ->
                        askingIn.remove(activity)
                        // After a choice the screen is finished by the choice itself. *Play now* with
                        // "show info" finishes only after its intent has started; finishing here
                        // would cancel it.
                        if (!chosen) {
                            activity.finish()
                        }
                    },
                ),
            )
        } finally {
            if (!taken && added) {
                askingIn.remove(activity)
            }
        }
        return taken
    }

    /**
     * Hook R2: whether the tap dialog is open on the router screen [activity]. The screen's chooser
     * finishes the screen when it is dismissed, which is right after one of its buttons was pressed;
     * while the dialog that the button opened is up, that must not happen.
     */
    @JvmStatic
    fun isAsking(activity: Activity): Boolean = activity in askingIn

    /**
     * Called when a play button is let through to send [list] to the main player, which goes through
     * the video page of the list's first video. If that video is the one the player is on, the page
     * must play [list] and not go back to the player's queue ([playersQueueFor]).
     */
    private fun rememberQueueReplacedBy(list: PlayQueue) {
        val playing = PlayerHolder.getInstance().player?.playQueue
        val first = list.item
        val startsWithCurrent = first != null &&
            HolderPlayerStateSource.current().isCurrent(VideoRef(first.serviceId, first.url))
        // An equal queue is not remembered: the player keeps its own object for it.
        replacedByAList = if (playing != null && startsWithCurrent && playing != list) {
            WeakReference(playing)
        } else {
            null
        }
    }

    /**
     * [url] in the form list items and queue items carry, so that the current item is recognised;
     * `null` if the service cannot tell.
     */
    private fun streamUrl(service: StreamingService, url: String): String? = try {
        service.streamLHFactory.let { it.getUrl(it.getId(url)) }
    } catch (e: Exception) {
        null
    }

    /**
     * Loads the video and hands it over as a queue of its own, with the toast and the error
     * notification of the long-press "Enqueue" on an item that has to be loaded first.
     */
    private fun loader(app: Context, serviceId: Int, url: String, startMs: Long?): ((PlayQueue) -> Unit) -> Unit =
        { use ->
            SparseItemUtil.fetchStreamInfoAndSaveToDatabase(app, serviceId, url) { info ->
                use(if (startMs == null) SinglePlayQueue(info) else SinglePlayQueue(info, startMs))
            }
        }

    /** The activity [context] is, or wraps. */
    private inline fun <reified T : Activity> activityOf(context: Context): T? {
        var current: Context? = context
        while (current != null) {
            if (current is T) {
                return current
            }
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
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
