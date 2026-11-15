package org.schabi.newpipe.enhancements.defaultopenaction

import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.ListEffect
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.TapEffect
import org.schabi.newpipe.enhancements.queueguard.TapRequest

// Plain Kotlin, no Android imports: the rules of default-open-action (plan 4.2).

/**
 * Wraps queue-guard's policy ([base]) with the default action and "Start playback in" ([settings]).
 * Everything else is [base]'s answer: this class never reads queue-guard's ask switch, "ask is on" is
 * [base] answering [TapEffect.ASK]. The rule tables of plan 4.2 (rows 1-23) describe the result.
 */
class OpenActionPolicy(private val base: GuardPolicy, private val settings: OpenActionSettings) : GuardPolicy {

    override fun onTap(tap: TapRequest, player: PlayerState): TapEffect {
        if (!player.somethingPlaying) {
            return idle(tap, base.onTap(tap, player)) // rows 1-4: AC-5, AC-6
        }
        val answer = base.onTap(tap, player)
        return if (answer == TapEffect.ASK || player.isCurrent(tap)) {
            answer // rows 5-6: AC-12, AC-4
        } else {
            base.onTapChoice(settings.defaultAction().choice, tap, player) // rows 7-12: AC-3
        }
    }

    override fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect {
        val answer = base.onTapChoice(choice, tap, player)
        return if (choice == GuardChoice.PLAY_NOW && !player.somethingPlaying) {
            idle(tap, answer) // row 19: AC-13
        } else {
            answer // rows 13-18: AC-4, AC-9
        }
    }

    override fun onListPlay(player: PlayerState): ListEffect = base.onListPlay(player) // AC-15

    override fun onListChoice(choice: GuardChoice, player: PlayerState): ListEffect =
        base.onListChoice(choice, player) // AC-15

    /** Rows 20-23: wherever a tap would neither open the video's page nor ask (AC-11). */
    override fun offersStreamDetails(player: PlayerState): Boolean = when {
        base.offersStreamDetails(player) -> true // row 20: QG AC-17
        !player.somethingPlaying -> settings.startPlaybackIn() != StartPlaybackIn.VIDEO_PAGE // rows 22-23
        // Rows 21, 21b. While something is playing, a play button that is not asked about is exactly
        // "ask is off" (QG plan 3.2).
        else -> base.onListPlay(player) != ListEffect.ASK && settings.defaultAction() != DefaultTapAction.PLAY_NOW
    }

    /** A tap while nothing is playing, [answer] being the wrapped policy's: "Start playback in" decides. */
    private fun idle(tap: TapRequest, answer: TapEffect): TapEffect {
        if (tap.startsListInBackground) {
            return answer // AC-6: Music Player Mode
        }
        return when (settings.startPlaybackIn()) {
            StartPlaybackIn.VIDEO_PAGE -> answer
            StartPlaybackIn.BACKGROUND_PLAYER -> TapEffect.START_BACKGROUND
            StartPlaybackIn.POPUP_PLAYER -> TapEffect.START_POPUP
        }
    }
}
