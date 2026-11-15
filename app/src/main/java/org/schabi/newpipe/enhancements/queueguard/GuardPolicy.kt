package org.schabi.newpipe.enhancements.queueguard

// Plain Kotlin, no Android imports: the rules of queue-guard (plan 3.2).

/** Default of the switch "Ask before replacing the queue" (AC-15). */
const val ASK_BEFORE_REPLACING_DEFAULT: Boolean = true

/** The switch "Ask before replacing the queue", read at every decision. */
fun interface GuardSettings {
    fun askBeforeReplacing(): Boolean
}

/** Decides what a video tap, a play button and a choice in their dialogs lead to. */
interface GuardPolicy {
    fun onTap(tap: TapRequest, player: PlayerState): TapEffect

    /** Never [TapEffect.ASK]. */
    fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect

    fun onListPlay(player: PlayerState): ListEffect

    /** Never [ListEffect.ASK]. */
    fun onListChoice(choice: GuardChoice, player: PlayerState): ListEffect

    fun offersStreamDetails(player: PlayerState): Boolean
}

/** The rule table of plan 3.2; within each function the first matching row wins. */
class QueueGuardPolicy(private val settings: GuardSettings) : GuardPolicy {

    override fun onTap(tap: TapRequest, player: PlayerState): TapEffect = when {
        !player.somethingPlaying -> TapEffect.OPEN_AS_TODAY // AC-7
        player.isCurrent(tap) -> TapEffect.OPEN_AS_TODAY // AC-8
        settings.askBeforeReplacing() -> TapEffect.ASK // AC-1
        else -> playNow(tap, player) // AC-16
    }

    override fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect =
        when (choice) {
            GuardChoice.CANCEL -> TapEffect.NONE // AC-6
            GuardChoice.ADD_TO_QUEUE -> TapEffect.ENQUEUE // AC-3, AC-9
            GuardChoice.PLAY_NEXT ->
                if (player.somethingPlaying) TapEffect.ENQUEUE_NEXT else TapEffect.ENQUEUE // AC-4, AC-9
            GuardChoice.PLAY_NOW -> playNow(tap, player) // AC-5, AC-9
        }

    override fun onListPlay(player: PlayerState): ListEffect =
        if (!player.somethingPlaying || !settings.askBeforeReplacing()) {
            ListEffect.PLAY_AS_TODAY // AC-14, AC-16
        } else {
            ListEffect.ASK // AC-10
        }

    override fun onListChoice(choice: GuardChoice, player: PlayerState): ListEffect = when (choice) {
        GuardChoice.CANCEL -> ListEffect.NONE // AC-13
        GuardChoice.ADD_TO_QUEUE -> ListEffect.ENQUEUE_ALL // AC-11
        GuardChoice.PLAY_NEXT ->
            if (player.somethingPlaying) ListEffect.ENQUEUE_ALL_NEXT else ListEffect.ENQUEUE_ALL // AC-11
        GuardChoice.PLAY_NOW -> ListEffect.PLAY_AS_TODAY // AC-12
    }

    override fun offersStreamDetails(player: PlayerState): Boolean = // AC-17
        player.somethingPlaying && (player.kind == PlayerKind.BACKGROUND || player.kind == PlayerKind.POPUP)

    /** *Play now* (spec, Terms): the tapped video replaces the queue in the player that is in use. */
    private fun playNow(tap: TapRequest, player: PlayerState): TapEffect = when {
        !player.somethingPlaying -> TapEffect.OPEN_AS_TODAY // AC-9
        tap.startsListInBackground -> TapEffect.OPEN_AS_TODAY // AC-5, Music Player Mode
        else -> when (player.kind) { // AC-5
            PlayerKind.BACKGROUND -> TapEffect.START_BACKGROUND
            PlayerKind.POPUP -> TapEffect.START_POPUP
            PlayerKind.MAIN, null -> TapEffect.OPEN_AS_TODAY
        }
    }
}
