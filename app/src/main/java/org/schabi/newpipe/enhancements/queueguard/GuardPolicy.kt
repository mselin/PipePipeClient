package org.schabi.newpipe.enhancements.queueguard

// Plain Kotlin, no Android imports: the rules of queue-guard (plan 3.2 as changed by A2.2).

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

/** The rule table of plan A2.2 (row numbers in the comments); within each function the first matching row wins. */
class QueueGuardPolicy(private val settings: GuardSettings) : GuardPolicy {

    override fun onTap(tap: TapRequest, player: PlayerState): TapEffect {
        val list = tap.way == OpenWay.LIST_TAP
        val ask = settings.askBeforeReplacing()
        val related = tap.relatedTo
        return when {
            !player.somethingPlaying -> TapEffect.OPEN_AS_TODAY // 1, AM-1
            player.isCurrent(tap) && (
                tap.way == OpenWay.LIST_TAP || tap.way == OpenWay.PAGE_CONTROL ||
                    tap.way == OpenWay.VIDEO_PAGE || player.queueSize == 1
                ) -> TapEffect.OPEN_AS_TODAY // 2, AC-8, AM-9, AM-10
            !ask && list -> playNow(tap, player) // 3, AC-16
            !ask -> TapEffect.OPEN_AS_TODAY // 4, AM-7
            list && related != null && player.isCurrent(related) -> TapEffect.OPEN_AS_TODAY // 5, AM-2
            !list && !replacesQueue(tap.way, player) -> TapEffect.OPEN_AS_TODAY // 6, AM-5, AM-6
            else -> TapEffect.ASK // 7, AC-1, AM-3, AM-5, AM-6
        }
    }

    override fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect =
        when (choice) {
            GuardChoice.CANCEL -> TapEffect.NONE // 8, AC-6
            GuardChoice.ADD_TO_QUEUE -> TapEffect.ENQUEUE // 9, AC-3, AC-9
            GuardChoice.PLAY_NEXT ->
                if (player.somethingPlaying) TapEffect.ENQUEUE_NEXT else TapEffect.ENQUEUE // 10, AC-4, AC-9
            // 11 (AM-3: what the pressed control does today) and 12 (AC-5, AC-9)
            GuardChoice.PLAY_NOW ->
                if (tap.way != OpenWay.LIST_TAP) TapEffect.OPEN_AS_TODAY else playNow(tap, player)
        }

    override fun onListPlay(player: PlayerState): ListEffect =
        if (!player.somethingPlaying || !settings.askBeforeReplacing()) {
            ListEffect.PLAY_AS_TODAY // 13, AM-1, AC-16
        } else {
            ListEffect.ASK // 14, AC-10
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
