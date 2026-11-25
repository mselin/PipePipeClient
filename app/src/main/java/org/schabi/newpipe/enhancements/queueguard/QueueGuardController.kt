package org.schabi.newpipe.enhancements.queueguard

// Plain Kotlin, no Android imports: the controller of queue-guard and the interfaces it calls back
// through (plan 3.2).

/** The player as it is right now. */
fun interface PlayerStateSource {
    fun current(): PlayerState
}

/** What a video tap can lead to. [openAsToday] runs upstream's own tap handler again (the replay). */
interface TapActions {
    fun openAsToday()
    fun enqueue()
    fun enqueueNext()
    fun startInBackground()
    fun startInPopup()
}

/** What a play button can lead to. [playAsToday] is what the pressed button does today. */
interface ListActions {
    fun playAsToday()
    fun enqueueAll()
    fun enqueueAllNext()
}

/**
 * The queue-guard dialog. `onChoice`: at most once, only for a choice the user made; a dismissal calls
 * nothing or [GuardChoice.CANCEL].
 */
interface GuardUi {
    fun askForTap(title: String, queueSize: Int, onChoice: (GuardChoice) -> Unit)
    fun askForList(videoCount: Int, queueSize: Int, onChoice: (GuardChoice) -> Unit)
}

/** Asks [policy] about each video tap and play button and carries out its answer. Main thread only. */
class QueueGuardController(private val policy: GuardPolicy, private val player: PlayerStateSource) {

    /** Set while [TapActions.openAsToday] runs: the tap it replays is let through. */
    private var replaying = false

    /** `true`: the tap was taken over and upstream's own statements must be skipped. */
    fun onVideoTap(tap: TapRequest, actions: TapActions, ui: GuardUi): Boolean {
        if (replaying) {
            return false
        }
        val state = player.current()
        return when (val effect = policy.onTap(tap, state)) {
            TapEffect.OPEN_AS_TODAY -> false
            TapEffect.ASK -> {
                ui.askForTap(
                    tap.title,
                    state.queueSize,
                    once { choice -> perform(policy.onTapChoice(choice, tap, player.current()), actions) },
                )
                true
            }
            else -> {
                perform(effect, actions)
                true
            }
        }
    }

    fun onListPlay(videoCount: Int, actions: ListActions, ui: GuardUi) {
        if (videoCount <= 0) {
            actions.playAsToday()
            return
        }
        val state = player.current()
        when (val effect = policy.onListPlay(state)) {
            ListEffect.ASK -> ui.askForList(
                videoCount,
                state.queueSize,
                once { choice -> perform(policy.onListChoice(choice, player.current()), actions) },
            )
            else -> perform(effect, actions)
        }
    }

    fun offersStreamDetails(): Boolean = policy.offersStreamDetails(player.current())

    private fun perform(effect: TapEffect, actions: TapActions) {
        when (effect) {
            TapEffect.OPEN_AS_TODAY -> {
                replaying = true
                try {
                    actions.openAsToday()
                } finally {
                    replaying = false
                }
            }
            TapEffect.ENQUEUE -> actions.enqueue()
            TapEffect.ENQUEUE_NEXT -> actions.enqueueNext()
            TapEffect.START_BACKGROUND -> actions.startInBackground()
            TapEffect.START_POPUP -> actions.startInPopup()
            TapEffect.NONE, TapEffect.ASK -> Unit
        }
    }

    private fun perform(effect: ListEffect, actions: ListActions) {
        when (effect) {
            ListEffect.PLAY_AS_TODAY -> actions.playAsToday()
            ListEffect.ENQUEUE_ALL -> actions.enqueueAll()
            ListEffect.ENQUEUE_ALL_NEXT -> actions.enqueueAllNext()
            ListEffect.NONE, ListEffect.ASK -> Unit
        }
    }

    /** A dialog callback of which only the first call counts. */
    private fun once(block: (GuardChoice) -> Unit): (GuardChoice) -> Unit {
        var called = false
        return { choice ->
            if (!called) {
                called = true
                block(choice)
            }
        }
    }
}
