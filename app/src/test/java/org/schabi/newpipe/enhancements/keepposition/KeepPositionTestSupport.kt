package org.schabi.newpipe.enhancements.keepposition

/**
 * Hand-written settings for the tracker tests. Both switches are on unless a test states
 * otherwise (plan section 6): that is the case the feature exists for. The properties are
 * `var`s because the tracker reads them at decision time, never when a mark is set
 * (plan section 3.1), and tests flip them between the two.
 */
internal class FakeKeepPositionSettings(
    override var alwaysStartFromBeginning: Boolean = true,
    override var keepPosition: Boolean = true
) : KeepPositionSettings

/**
 * The two seek decisions of one load: the start position given to the source
 * (`shouldSeekOnLoad`, hook H2) and the seek at the synchronization
 * (`shouldSeekOnSynchronize`, hook H4). A failed assertion prints both values, so it shows
 * which of the two decisions is wrong.
 */
internal data class LoadDecisions(val onLoad: Boolean, val onSynchronize: Boolean)

/** Both decisions say: apply the stored position (continue, or go to the link's time). */
internal val SEEKS = LoadDecisions(onLoad = true, onSynchronize = true)

/** Both decisions say: do not apply it, the item begins at upstream's default position, 0:00. */
internal val NO_SEEK = LoadDecisions(onLoad = false, onSynchronize = false)

/**
 * "load U in p" of plan section 6: `shouldSeekOnLoad`, `onSynchronize`,
 * `shouldSeekOnSynchronize`, in the order the player calls them.
 */
internal fun KeepPositionTracker.load(player: Any, itemUuid: String): LoadDecisions {
    val onLoad = shouldSeekOnLoad(player, itemUuid)
    onSynchronize(player, itemUuid)
    return LoadDecisions(onLoad, shouldSeekOnSynchronize(player, itemUuid))
}

// ---- Amendment 2 (spec AM-1 to AM-3, plan 10.3 and 10.7) ----

/**
 * "load U in p" with the item's address, as hook H3 passes it since amendment 2
 * (`onSynchronize(p, u, a)`, plan 10.3). The address decides what happens to a close mark.
 */
internal fun KeepPositionTracker.load(player: Any, itemUuid: String, itemAddress: String?): LoadDecisions {
    val onLoad = shouldSeekOnLoad(player, itemUuid)
    onSynchronize(player, itemUuid, itemAddress)
    return LoadDecisions(onLoad, shouldSeekOnSynchronize(player, itemUuid))
}

/** `err(p,U,A,pos,dur)` of plan 10.7: a playback error of a stream that is not live (hook E1). */
internal fun KeepPositionTracker.err(
    player: Any,
    itemUuid: String?,
    itemAddress: String?,
    positionMs: Long,
    durationKnown: Boolean
) = onErrorBegin(player, itemUuid, itemAddress, positionMs, false, durationKnown)

/** `closed(p,pos,dur,playing)` of plan 10.7: the player is destroyed, by anyone (hook D1). */
internal fun KeepPositionTracker.closed(
    player: Any,
    positionMs: Long,
    durationKnown: Boolean,
    isPlaying: Boolean
) = onPlayerClosed(player, positionMs, durationKnown, isPlaying)

/**
 * `start(p,N,A)` of plan 10.7: a start intent arrives at a new player, which plays nothing
 * yet (hook G1): `onStartIntent(p, null, null, 0, false, false, false, N, A, false)`.
 */
internal fun KeepPositionTracker.start(player: Any, newUuid: String?, newAddress: String?): Long =
    onStartIntent(player, null, null, 0, false, false, false, newUuid, newAddress, false)
