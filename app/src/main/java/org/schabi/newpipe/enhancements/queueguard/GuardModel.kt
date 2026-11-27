package org.schabi.newpipe.enhancements.queueguard

// Plain Kotlin, no Android imports: the model of queue-guard (plan 3.1 as changed by A2.1).

/** The player that is in use. */
enum class PlayerKind { MAIN, BACKGROUND, POPUP }

/** What the player holds at one moment. */
data class PlayerState(
    /** `null`: no player open. */
    val kind: PlayerKind?,
    /** `0` while the player holds no queue. */
    val queueSize: Int,
    val currentServiceId: Int,
    /** Url of the current item, `null` if none. */
    val currentUrl: String?,
) {
    /** Spec, "a queue worth guarding" as amended: a player is open and holds at least one item. */
    val somethingPlaying: Boolean get() = kind != null && queueSize > 0

    /** Whether [tap] is on the current item (AC-8): same service, exactly the same url. */
    fun isCurrent(tap: TapRequest): Boolean = isCurrent(VideoRef(tap.serviceId, tap.url))

    fun isCurrent(video: VideoRef): Boolean =
        somethingPlaying && currentUrl == video.url && currentServiceId == video.serviceId

    companion object {
        val IDLE = PlayerState(null, 0, -1, null)
    }
}

/** A video by service and exact address, as [PlayerState.isCurrent] compares it. */
data class VideoRef(val serviceId: Int, val url: String)

/** How carrying a request out "as today" would play the video. */
enum class OpenWay {
    /** A video tap in a list (hooks T1-T4). */
    LIST_TAP,

    /** A play control of the video page: play or thumbnail, Background, Popup, the mini player's play. */
    PAGE_CONTROL,

    /** The video's page is opened: a timed link, an address typed in search, "show info" or "video player". */
    VIDEO_PAGE,

    /** "Background player" chosen for a video from a link or another app. */
    BACKGROUND_PLAYER,

    /** "Popup player" chosen for a video from a link or another app. */
    POPUP_PLAYER,
}

/** A video tap that reached a hook. */
data class TapRequest(
    val serviceId: Int,
    val url: String,
    val title: String,
    /** Local playlist with Music Player Mode on: the tap starts the list in the background. */
    val startsListInBackground: Boolean = false,
    val way: OpenWay = OpenWay.LIST_TAP,
    /** LIST_TAP only: the video whose list of related videos was tapped. */
    val relatedTo: VideoRef? = null,
)

/**
 * Whether carrying out [way] as today replaces the player's queue (AM-3, AM-5, AM-6). Opening a page
 * starts or stops the main player; beside the background or popup player it only shows the page.
 */
fun replacesQueue(way: OpenWay, player: PlayerState): Boolean = when {
    !player.somethingPlaying -> false
    way == OpenWay.VIDEO_PAGE -> player.kind == PlayerKind.MAIN
    else -> true
}

/** [choice]: the key the router handles. `null`: an action that never plays. */
fun openWayOfRouterChoice(
    choice: String,
    showInfo: String,
    videoPlayer: String,
    backgroundPlayer: String,
    popupPlayer: String,
): OpenWay? = when (choice) {
    showInfo, videoPlayer -> OpenWay.VIDEO_PAGE
    backgroundPlayer -> OpenWay.BACKGROUND_PLAYER
    popupPlayer -> OpenWay.POPUP_PLAYER
    else -> null
}

/** What "Details" in the menu of a queue item does (AM-4). */
enum class DetailsEffect {
    /** Background or popup player, or nothing playing: upstream opens the item's page beside the player. */
    AS_TODAY,

    /** Main player, the current item: show its page, which is the player. */
    SHOW_PLAYING_PAGE,

    /** Main player, another item: move playback to it inside the queue, then show the page. */
    SELECT_THEN_SHOW_PAGE,

    /** Main player, the item is no longer in the queue. */
    NONE,
}

/** "Details" of the queue item at [itemIndex] (-1: no longer in the queue) while the player is on [currentIndex]. */
fun queueItemDetails(player: PlayerState, itemIndex: Int, currentIndex: Int): DetailsEffect = when {
    !player.somethingPlaying || player.kind != PlayerKind.MAIN -> DetailsEffect.AS_TODAY
    itemIndex < 0 -> DetailsEffect.NONE
    itemIndex == currentIndex -> DetailsEffect.SHOW_PLAYING_PAGE
    else -> DetailsEffect.SELECT_THEN_SHOW_PAGE
}

/** A row of the dialog. In the list dialog: add all, all next, replace. */
enum class GuardChoice { ADD_TO_QUEUE, PLAY_NEXT, PLAY_NOW, CANCEL }

/** What a video tap, or a choice in its dialog, leads to. */
enum class TapEffect { NONE, OPEN_AS_TODAY, ASK, ENQUEUE, ENQUEUE_NEXT, START_BACKGROUND, START_POPUP }

/** What a play button, or a choice in its dialog, leads to. */
enum class ListEffect { NONE, PLAY_AS_TODAY, ASK, ENQUEUE_ALL, ENQUEUE_ALL_NEXT }

/** One `PlayQueue.move(from, to)`. */
data class QueueMove(val from: Int, val to: Int)

/**
 * Moves, applied in order, that bring the last [added] of [sizeAfterAppend] queue items to just behind
 * [currentIndex], in their order: i in 0 until added -> (sizeAfterAppend - added + i, currentIndex + 1 + i),
 * minus from == to.
 */
fun movesForPlayNext(sizeAfterAppend: Int, added: Int, currentIndex: Int): List<QueueMove> =
    (0 until added)
        .map { i -> QueueMove(sizeAfterAppend - added + i, currentIndex + 1 + i) }
        .filter { it.from != it.to }

/**
 * Index for "Show stream details" in a menu given as string resource ids: -1 if [detailsRes] is already
 * there, else the index of [anchorRes], else the size (append).
 */
fun streamDetailsInsertIndex(entryResources: List<Int>, detailsRes: Int, anchorRes: Int): Int {
    if (detailsRes in entryResources) {
        return -1
    }
    val anchor = entryResources.indexOf(anchorRes)
    return if (anchor >= 0) anchor else entryResources.size
}
