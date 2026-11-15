package org.schabi.newpipe.enhancements.queueguard

// Plain Kotlin, no Android imports: the model of queue-guard (plan 3.1).

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
    /** Spec, Terms: a player is open and holds at least one item, playing or paused. */
    val somethingPlaying: Boolean get() = kind != null && queueSize > 0

    /** Whether [tap] is on the current item (AC-8): same service, exactly the same url. */
    fun isCurrent(tap: TapRequest): Boolean =
        somethingPlaying && currentUrl == tap.url && currentServiceId == tap.serviceId

    companion object {
        val IDLE = PlayerState(null, 0, -1, null)
    }
}

/** A video tap that reached a hook. */
data class TapRequest(
    val serviceId: Int,
    val url: String,
    val title: String,
    /** Local playlist with Music Player Mode on: the tap starts the list in the background. */
    val startsListInBackground: Boolean = false,
)

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
