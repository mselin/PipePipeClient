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
    /** Of the [queueSize] items, those the player added by itself ("Auto-enqueue next stream"). */
    val autoQueuedCount: Int = 0,
) {
    /** Spec, Terms: a player is open and holds at least one item, playing or paused. */
    val somethingPlaying: Boolean get() = kind != null && queueSize > 0

    /**
     * Spec, Terms, "a queue worth guarding": something is playing, except in the main player while its
     * queue holds at most one video the user put there.
     */
    val worthGuarding: Boolean
        get() = somethingPlaying && !(kind == PlayerKind.MAIN && queueSize - autoQueuedCount <= 1)

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

/** How many auto-enqueued videos an [AutoQueuedRecord] remembers unless told otherwise. */
const val AUTO_QUEUED_RECORD_LIMIT: Int = 1000

/**
 * The uuids of the queue items the player added by itself (plan 3.1, rules 1 to 6). Keyed by uuid, so it
 * follows an item wherever it goes; nothing is removed on a move or on playback. Thread-safe: every
 * member is synchronized.
 */
class AutoQueuedRecord(private val limit: Int = AUTO_QUEUED_RECORD_LIMIT) {
    /** In the order they were first remembered, oldest first. */
    private val uuids = LinkedHashSet<String>()

    /** Remembers [uuids]. Beyond [limit] entries the oldest are forgotten, one per entry added. */
    @Synchronized
    fun add(uuids: Collection<String>) {
        for (uuid in uuids) {
            if (this.uuids.add(uuid)) {
                while (this.uuids.size > limit) {
                    val oldest = this.uuids.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    /** How many entries of [queueUuids] are remembered; a uuid that occurs twice counts twice. */
    @Synchronized
    fun countIn(queueUuids: List<String>): Int = queueUuids.count { it in uuids }
}
