package org.schabi.newpipe.enhancements.keepposition

import java.lang.ref.WeakReference
import kotlin.math.abs

// Plain Kotlin, no Android imports (plan 3.1 and 10.3): the decisions of the feature and the marks
// they read. The Android glue in KeepPosition.kt only unwraps the player's arguments into these
// calls and writes a returned position into the queue.

/** The two switches. They are read at decision time, never when a mark is set. */
interface KeepPositionSettings {
    /** Upstream's "Always start play from beginning", default false. */
    val alwaysStartFromBeginning: Boolean

    /** "Keep position of the playing video", default true. */
    val keepPosition: Boolean
}

/** What an item carries for its next load: nothing, a reload of the playing video, a timed start. */
enum class Mark { NONE, RELOAD, TIMED }

/** "No position": what a method returns when the glue has nothing to store. */
const val NO_POSITION = -1L

/** The gate that replaces upstream's `Player.shouldSeek()`; first match wins (plan 3.1). */
fun shouldSeek(alwaysStartFromBeginning: Boolean, keepPosition: Boolean, mark: Mark): Boolean =
    when {
        !alwaysStartFromBeginning -> true // rule 1, AC-9: upstream's open gate
        mark == Mark.TIMED -> true // rule 2, AC-12, AC-13, AC-14
        mark == Mark.RELOAD && keepPosition -> true // rule 3, AC-2, AC-3, AC-4
        else -> false // rule 4, AC-7, AC-8, AC-10
    }

/** Whether a link's start position makes a timed start: a time, and neither round play nor live. */
fun isTimedLinkStart(startPositionSeconds: Long, isRoundPlay: Boolean, isLive: Boolean): Boolean =
    startPositionSeconds > 0 && !isRoundPlay && !isLive

/** "serviceId|url", or null when url is null or empty. The identity PlayQueue.equals() uses. */
fun itemAddress(serviceId: Int, url: String?): String? =
    if (url.isNullOrEmpty()) null else "$serviceId|$url"

/** Glue rule for H1 and H6: store the mark's position when the queue has none. */
fun shouldStore(markPositionMs: Long, storedPositionMs: Long): Boolean =
    markPositionMs > 0 && storedPositionMs <= 0

/**
 * Whether a player that failed at [failedAtMs] has played on since: it is playing right now, or
 * it knows its duration and is more than [KeepPositionTracker.MOVED_MS] away from that position.
 * Without a duration a position proves nothing (a source that does not load has none).
 */
fun hasPlayedOn(failedAtMs: Long, positionMs: Long, durationKnown: Boolean, isPlaying: Boolean): Boolean =
    isPlaying || (durationKnown && abs(positionMs - failedAtMs) > KeepPositionTracker.MOVED_MS)

/**
 * The marks of plan section 2 and 10.3, process-wide. A player is an opaque token, compared by
 * identity and held weakly. Every method is synchronized: the load decision runs on an io thread.
 *
 * Items are named by uuid (it survives queue copies) and, where a start builds a new queue for
 * the same video, by address ([itemAddress]). The tracker never applies a position: the methods
 * that return one hand it to the glue, which stores it in the queue for upstream's own seek.
 */
class KeepPositionTracker(private val settings: KeepPositionSettings) {

    /** The reload mark: one item, its owner (`null` is ANY) and whether it continues a timed start. */
    private class ReloadMark(
        val uuid: String,
        private val owner: WeakReference<Any>?,
        val timed: Boolean
    ) {
        fun fits(player: Any, itemUuid: String): Boolean =
            uuid == itemUuid && (owner == null || owner.get() === player)
    }

    /**
     * The position the last mark for an item carried; owner `null` is ANY. Unlike the reload
     * mark it survives the synchronization of its own item, for a player that reports 0 without
     * a duration because it has not got its position back yet (AM-3).
     */
    private class Carried(
        private val owner: WeakReference<Any>?,
        val uuid: String,
        val positionMs: Long
    ) {
        fun fits(player: Any, itemUuid: String): Boolean =
            uuid == itemUuid && (owner == null || owner.get() === player)
    }

    /** The last synchronization of any player: that player is on that item. */
    private class Synced(player: Any, private val uuid: String) {
        private val player = WeakReference(player)

        fun isOn(player: Any, itemUuid: String): Boolean =
            uuid == itemUuid && this.player.get() === player
    }

    /** A player that had a playback error on that item at that position (AM-1). */
    private class Failing(player: Any, val uuid: String, val address: String, val positionMs: Long) {
        private val player = WeakReference(player)

        fun isPlayer(player: Any): Boolean = this.player.get() === player
    }

    /**
     * "The player of this video was closed, continue it at the next start" (AM-1, and the page's
     * stop of AM-2). One slot, keyed by address; the next start intent uses or drops it.
     */
    private class CloseMark(val address: String, val positionMs: Long, source: Any) {
        private val source = WeakReference(source)

        fun isSource(player: Any): Boolean = source.get() === player
    }

    /** The marks one synchronization took from its item, for the seek decision of that call. */
    private class Grant(val uuid: String, val mark: Mark)

    private val timed = LinkedHashSet<String>()
    private var reload: ReloadMark? = null
    private var synced: Synced? = null
    private var grant: Grant? = null
    private var carried: Carried? = null
    private var failing: Failing? = null
    private var closeMark: CloseMark? = null

    private val bothOn: Boolean
        get() = settings.alwaysStartFromBeginning && settings.keepPosition

    @Synchronized
    fun onTimedStart(itemUuid: String) {
        timed.add(itemUuid)
        while (timed.size > MAX_TIMED) {
            timed.remove(timed.first())
        }
    }

    /** @return the position of the mark if one was set and both switches are on, else [NO_POSITION]. */
    @Synchronized
    fun onReload(
        player: Any,
        queueItemUuid: String?,
        positionMs: Long,
        isLive: Boolean,
        durationKnown: Boolean = true
    ): Long = markReload(player, queueItemUuid, positionMs, isLive, durationKnown, anyOwner = false)

    /** As [onReload], with a mark that any player may take (the reload continues in a new service). */
    @Synchronized
    fun onSwitch(
        player: Any,
        queueItemUuid: String?,
        positionMs: Long,
        isLive: Boolean,
        durationKnown: Boolean = true
    ): Long = markReload(player, queueItemUuid, positionMs, isLive, durationKnown, anyOwner = true)

    @Synchronized
    fun shouldSeekInPlace(
        player: Any,
        linkItemUuid: String?,
        playingItemUuid: String?,
        reloadFollows: Boolean
    ): Boolean {
        val isTimed = linkItemUuid != null && timed.remove(linkItemUuid)
        if (isTimed && reloadFollows && playingItemUuid != null) {
            reload = ReloadMark(playingItemUuid, WeakReference(player), timed = true)
        }
        val seeks = decide(if (isTimed) Mark.TIMED else Mark.NONE)
        if (seeks) {
            carried = null // the seek in place moves the position
        }
        return seeks
    }

    @Synchronized
    fun peekMark(player: Any, itemUuid: String): Mark {
        val fitting = reload?.takeIf { it.fits(player, itemUuid) }
        return when {
            itemUuid in timed || fitting?.timed == true -> Mark.TIMED
            fitting != null -> Mark.RELOAD
            else -> Mark.NONE
        }
    }

    @Synchronized
    fun shouldSeekOnLoad(player: Any, itemUuid: String): Boolean = decide(peekMark(player, itemUuid))

    @Synchronized
    fun onSynchronize(player: Any, itemUuid: String, itemAddress: String? = null) {
        val mark = peekMark(player, itemUuid)
        timed.remove(itemUuid)
        grant = if (mark != Mark.NONE) Grant(itemUuid, mark) else null
        reload = null
        synced = Synced(player, itemUuid)

        // carried follows its own item; failing survives only the retry of the same item in the
        // same player; a close mark only the synchronization of its own video in its dying player.
        carried = carried?.takeIf { it.fits(player, itemUuid) }
            ?.let { Carried(WeakReference(player), itemUuid, it.positionMs) }
        failing = failing?.takeIf { it.isPlayer(player) && it.uuid == itemUuid }
        closeMark = closeMark?.takeIf {
            itemAddress != null && it.address == itemAddress && it.isSource(player)
        }
    }

    @Synchronized
    fun shouldSeekOnSynchronize(player: Any, itemUuid: String): Boolean {
        val mark = grant?.takeIf { it.uuid == itemUuid }?.mark ?: Mark.NONE
        grant = null
        return decide(mark)
    }

    /** A playback error arrived. A repeated error of a player that has not played on keeps the first position. */
    @Synchronized
    fun onErrorBegin(
        player: Any,
        itemUuid: String?,
        itemAddress: String?,
        positionMs: Long,
        isLive: Boolean,
        durationKnown: Boolean
    ) {
        if (itemUuid == null || itemAddress == null || isLive || synced?.isOn(player, itemUuid) != true) {
            return
        }
        val p = position(player, itemUuid, positionMs, durationKnown)
        if (p == NO_POSITION) {
            return
        }
        val f = failing
        if (f != null && f.isPlayer(player) && f.uuid == itemUuid &&
            !hasPlayedOn(f.positionMs, p, durationKnown, false)
        ) {
            return
        }
        failing = Failing(player, itemUuid, itemAddress, p)
    }

    /** The one place every close passes: a failing player that has not played on leaves a close mark. */
    @Synchronized
    fun onPlayerClosed(player: Any, positionMs: Long, durationKnown: Boolean, isPlaying: Boolean) {
        val f = failing?.takeIf { it.isPlayer(player) } ?: return
        if (!hasPlayedOn(f.positionMs, positionMs, durationKnown, isPlaying)) {
            closeMark = CloseMark(f.address, f.positionMs, player)
        }
        failing = null
    }

    /**
     * The video page stops the main player: (1) in reaction to an error, or (2) for the very video
     * the player is on (the page shows it, without Autoplay). `failing` stays for (1): the close
     * follows, and the dying player may still recover without undoing this mark.
     */
    @Synchronized
    fun onPageStopsPlayer(
        player: Any,
        playingUuid: String?,
        playingAddress: String?,
        positionMs: Long,
        isLive: Boolean,
        isEnded: Boolean,
        durationKnown: Boolean,
        isPlaying: Boolean,
        pageAddress: String?
    ) {
        val f = failing?.takeIf { it.isPlayer(player) }
        if (f != null && !hasPlayedOn(f.positionMs, positionMs, durationKnown, isPlaying)) {
            closeMark = CloseMark(f.address, f.positionMs, player)
            return
        }
        if (playingUuid != null && playingAddress != null && playingAddress == pageAddress &&
            !isLive && !isEnded && synced?.isOn(player, playingUuid) == true
        ) {
            val p = position(player, playingUuid, positionMs, durationKnown)
            if (p != NO_POSITION) {
                closeMark = CloseMark(playingAddress, p, player)
            }
        }
    }

    /**
     * A start intent arrived at [player], before upstream reads the new queue (plan 10.3, rows 1-6).
     * Rows 4 and 5 put an ordinary reload mark on the new queue's item, so the load decisions do
     * the rest. Row 6 leaves the reload mark alone (the popup's fullscreen button set it for the
     * intent that follows).
     *
     * @return the position to store for the new queue's item if both switches are on, else [NO_POSITION].
     */
    @Synchronized
    fun onStartIntent(
        player: Any,
        playingUuid: String?,
        playingAddress: String?,
        positionMs: Long,
        isLive: Boolean,
        isEnded: Boolean,
        durationKnown: Boolean,
        newUuid: String?,
        newAddress: String?,
        sameQueue: Boolean
    ): Long {
        if (newUuid == null || newAddress == null) { // row 1
            return NO_POSITION
        }
        if (sameQueue) { // row 2
            return NO_POSITION
        }
        if (newUuid in timed) { // row 3
            carried = carried?.takeIf { it.uuid != newUuid } // a start without a claim inherits nothing (Q15)
            closeMark = null
            failing = null
            return NO_POSITION
        }
        if (playingUuid != null && playingAddress == newAddress && !isLive && !isEnded &&
            synced?.isOn(player, playingUuid) == true
        ) { // row 4
            val p = position(player, playingUuid, positionMs, durationKnown)
            if (p != NO_POSITION) {
                closeMark = null
                failing = null
                reload = ReloadMark(newUuid, WeakReference(player), false)
                carried = Carried(WeakReference(player), newUuid, p)
                return if (bothOn) p else NO_POSITION
            }
        }
        val c = closeMark
        if (c != null && c.address == newAddress) { // row 5
            closeMark = null
            failing = null
            reload = ReloadMark(newUuid, null, false)
            carried = Carried(null, newUuid, c.positionMs)
            return if (bothOn) c.positionMs else NO_POSITION
        }
        carried = carried?.takeIf { it.uuid != newUuid } // row 6; a start without a claim inherits nothing (Q15)
        closeMark = null
        failing = null
        return NO_POSITION
    }

    /** Probe: the close mark's position for that address, else NO_POSITION. */
    @Synchronized
    fun peekClosePosition(itemAddress: String): Long =
        closeMark?.takeIf { it.address == itemAddress }?.positionMs ?: NO_POSITION

    /** Probe. */
    @Synchronized
    fun isFailing(player: Any): Boolean = failing?.isPlayer(player) == true

    /** "Position of a mark" (plan 10.3); NO_POSITION is "none". */
    private fun position(player: Any, uuid: String, positionMs: Long, durationKnown: Boolean): Long {
        if (positionMs > 0) {
            return positionMs
        }
        val c = carried
        if (!durationKnown && c != null && c.fits(player, uuid)) {
            return c.positionMs
        }
        return NO_POSITION
    }

    /** `onReload` / `onSwitch`: a failed attempt changes nothing. */
    private fun markReload(
        player: Any,
        uuid: String?,
        positionMs: Long,
        isLive: Boolean,
        durationKnown: Boolean,
        anyOwner: Boolean
    ): Long {
        if (uuid == null || isLive || synced?.isOn(player, uuid) != true) {
            return NO_POSITION
        }
        val p = position(player, uuid, positionMs, durationKnown)
        if (p == NO_POSITION) {
            return NO_POSITION
        }
        val timedFlag = reload?.let { it.fits(player, uuid) && it.timed } ?: false
        val owner = if (anyOwner) null else WeakReference(player)
        reload = ReloadMark(uuid, owner, timedFlag)
        carried = Carried(owner, uuid, p)
        return if (bothOn) p else NO_POSITION
    }

    private fun decide(mark: Mark): Boolean =
        shouldSeek(settings.alwaysStartFromBeginning, settings.keepPosition, mark)

    companion object {
        const val MAX_TIMED = 16
        const val MOVED_MS = 2000L
    }
}
