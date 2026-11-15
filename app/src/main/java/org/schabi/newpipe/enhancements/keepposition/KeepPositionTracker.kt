package org.schabi.newpipe.enhancements.keepposition

import java.lang.ref.WeakReference

// Plain Kotlin, no Android imports (plan 3.1): the decisions of the feature and the marks they
// read. The Android glue in KeepPosition.kt only unwraps the player's arguments into these calls.

/** The two switches. They are read at decision time, never when a mark is set. */
interface KeepPositionSettings {
    /** Upstream's "Always start play from beginning", default false. */
    val alwaysStartFromBeginning: Boolean

    /** "Keep position of the playing video", default true. */
    val keepPosition: Boolean
}

/** What an item carries for its next load: nothing, a reload of the playing video, a timed start. */
enum class Mark { NONE, RELOAD, TIMED }

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

/**
 * The marks of plan section 2, process-wide. A player is an opaque token, compared by identity
 * and held weakly. Every method is synchronized: the load decision runs on an io thread.
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

    /** The last synchronization of any player: that player is on that item. */
    private class Synced(player: Any, private val uuid: String) {
        private val player = WeakReference(player)

        fun isOn(player: Any, itemUuid: String): Boolean =
            uuid == itemUuid && this.player.get() === player
    }

    /** The marks one synchronization took from its item, for the seek decision of that call. */
    private class Grant(val uuid: String, val mark: Mark)

    private val timed = LinkedHashSet<String>()
    private var reload: ReloadMark? = null
    private var synced: Synced? = null
    private var grant: Grant? = null

    @Synchronized
    fun onTimedStart(itemUuid: String) {
        timed.add(itemUuid)
        while (timed.size > MAX_TIMED) {
            timed.remove(timed.first())
        }
    }

    @Synchronized
    fun onReload(player: Any, queueItemUuid: String?, positionMs: Long, isLive: Boolean) {
        markReload(player, queueItemUuid, positionMs, isLive, anyOwner = false)
    }

    @Synchronized
    fun onSwitch(player: Any, queueItemUuid: String?, positionMs: Long, isLive: Boolean) {
        markReload(player, queueItemUuid, positionMs, isLive, anyOwner = true)
    }

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
        return decide(if (isTimed) Mark.TIMED else Mark.NONE)
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
    fun onSynchronize(player: Any, itemUuid: String) {
        val mark = peekMark(player, itemUuid)
        timed.remove(itemUuid)
        grant = if (mark != Mark.NONE) Grant(itemUuid, mark) else null
        reload = null
        synced = Synced(player, itemUuid)
    }

    @Synchronized
    fun shouldSeekOnSynchronize(player: Any, itemUuid: String): Boolean {
        val mark = grant?.takeIf { it.uuid == itemUuid }?.mark ?: Mark.NONE
        grant = null
        return decide(mark)
    }

    /** `onReload` / `onSwitch`: a failed attempt changes nothing. */
    private fun markReload(
        player: Any,
        uuid: String?,
        positionMs: Long,
        isLive: Boolean,
        anyOwner: Boolean
    ) {
        if (uuid == null || positionMs <= 0 || isLive || synced?.isOn(player, uuid) != true) {
            return
        }
        val timedFlag = reload?.let { it.fits(player, uuid) && it.timed } ?: false
        reload = ReloadMark(uuid, if (anyOwner) null else WeakReference(player), timedFlag)
    }

    private fun decide(mark: Mark): Boolean =
        shouldSeek(settings.alwaysStartFromBeginning, settings.keepPosition, mark)

    companion object {
        const val MAX_TIMED = 16
    }
}
