package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.QueueGuardPolicy

/**
 * AC-14: "With both settings on their defaults the app behaves exactly as with queue-guard alone."
 *
 * Plan section 6 names the proof: over the whole grid "(kind incl. `null`, queue size 0, 1 and 3, the
 * queues with auto-enqueued videos of both fixtures, current or not, ask, MPM, every `GuardChoice`), P with
 * default settings equals `QueueGuardPolicy` alone in all five functions". One test per function. The
 * yardstick is queue-guard's own policy on the same switch, whose rules queue-guard's tests pin down;
 * nothing here repeats them.
 *
 * "Default settings" are the enums' `DEFAULT` (what the glue falls back to while nothing is stored, plan
 * 4.4); that these are *Play now* and *Video page* is checked in [OpenActionModelTest].
 */
class DefaultSettingsEqualQueueGuardTest {

    private val askSwitch = FakeAskSwitch()
    private val queueGuardAlone: GuardPolicy = QueueGuardPolicy(askSwitch)
    private val withDefaultSettings: GuardPolicy = OpenActionPolicy(
        QueueGuardPolicy(askSwitch),
        askSwitch,
        FakeOpenActionSettings(action = DefaultTapAction.DEFAULT, startIn = StartPlaybackIn.DEFAULT),
    )

    /** "kind incl. `null`". */
    private val kinds: List<PlayerKind?> = listOf<PlayerKind?>(null) + ALL_KINDS

    /** "queue size 0, 1 and 3". */
    private val queueSizes = listOf(0, 1, 3)

    /**
     * "the queues with auto-enqueued videos of both fixtures" ([WORTH_GUARDING], [SINGLE_IN_MAIN]): the
     * eight states of the two fixtures that the kinds and queue sizes above do not give, i.e. those with
     * auto-enqueued videos or with two videos (plan section 6.1), written `queueSize`/`autoQueuedCount`.
     */
    private val statesOfTheFixtures: List<PlayerState> = listOf(
        playing(PlayerKind.BACKGROUND, queueSize = 3, autoQueued = 2),
        playing(PlayerKind.MAIN, queueSize = 2),
        playing(PlayerKind.MAIN, queueSize = 3, autoQueued = 1),
        playing(PlayerKind.MAIN, queueSize = 5, autoQueued = 3),
        playing(PlayerKind.MAIN, queueSize = 2, autoQueued = 1),
        playing(PlayerKind.MAIN, queueSize = 3, autoQueued = 2),
        playing(PlayerKind.MAIN, queueSize = 2, autoQueued = 2),
        playing(PlayerKind.MAIN, queueSize = 1, autoQueued = 1),
    )

    /**
     * Every kind with every queue size (12), each on the item [CURRENT_URL], `PlayerState.IDLE`, and
     * [statesOfTheFixtures] (8). 21 states; something is playing in 14 of them, and in 5 of those the main
     * player holds a single video (plan section 6.1).
     */
    private val states: List<PlayerState> =
        kinds.flatMap { kind ->
            queueSizes.map { size ->
                PlayerState(kind = kind, queueSize = size, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
            }
        } + PlayerState.IDLE + statesOfTheFixtures

    /** The grid is the one plan section 6.1 counts: 21 different states that hold both fixtures. */
    private fun assertTheGridIsComplete() {
        assertEquals("states of the grid", 21, states.size)
        assertEquals("different states of the grid", 21, states.toSet().size)
        val missing = (WORTH_GUARDING + SINGLE_IN_MAIN).filterNot { it in states }
        assertEquals("states of the two fixtures that the grid lacks", emptyList<PlayerState>(), missing)
    }

    /** "current or not" and "MPM": a tap on the url the states are on and on another one, each with both modes. */
    private val taps = tapsOnBothUrls(musicPlayerMode = false) + tapsOnBothUrls(musicPlayerMode = true)

    /** Runs [compare] with the ask switch on, then off, and returns how often [compare] counted a comparison. */
    private fun withAskOnAndOff(compare: (ask: String, count: () -> Unit) -> Unit): Int {
        var compared = 0
        for (ask in ASK_ON_AND_OFF) {
            askSwitch.ask = ask
            compare(if (ask) "ask on" else "ask off") { compared++ }
        }
        return compared
    }

    @Test
    fun ac14_defaultSettings_aTapDoesWhatItDoesWithQueueGuardAlone() {
        assertTheGridIsComplete()
        val compared = withAskOnAndOff { ask, count ->
            for (state in states) {
                for (tap in taps) {
                    assertEquals(
                        "onTap with $ask, $state, $tap",
                        queueGuardAlone.onTap(tap, state),
                        withDefaultSettings.onTap(tap, state),
                    )
                    count()
                }
            }
        }
        assertEquals("cases of the grid: 2 ask x 21 states x 4 taps", 168, compared)
    }

    @Test
    fun ac14_defaultSettings_aDialogChoiceDoesWhatItDoesWithQueueGuardAlone() {
        assertTheGridIsComplete()
        val compared = withAskOnAndOff { ask, count ->
            for (state in states) {
                for (tap in taps) {
                    for (choice in ALL_CHOICES) {
                        assertEquals(
                            "onTapChoice($choice) with $ask, $state, $tap",
                            queueGuardAlone.onTapChoice(choice, tap, state),
                            withDefaultSettings.onTapChoice(choice, tap, state),
                        )
                        count()
                    }
                }
            }
        }
        assertEquals("cases of the grid: 2 ask x 21 states x 4 taps x 4 choices", 672, compared)
    }

    @Test
    fun ac14_defaultSettings_aPlayButtonDoesWhatItDoesWithQueueGuardAlone() {
        assertTheGridIsComplete()
        val compared = withAskOnAndOff { ask, count ->
            for (state in states) {
                assertEquals(
                    "onListPlay with $ask, $state",
                    queueGuardAlone.onListPlay(state),
                    withDefaultSettings.onListPlay(state),
                )
                count()
            }
        }
        assertEquals("cases of the grid: 2 ask x 21 states", 42, compared)
    }

    @Test
    fun ac14_defaultSettings_aListDialogChoiceDoesWhatItDoesWithQueueGuardAlone() {
        assertTheGridIsComplete()
        val compared = withAskOnAndOff { ask, count ->
            for (state in states) {
                for (choice in ALL_CHOICES) {
                    assertEquals(
                        "onListChoice($choice) with $ask, $state",
                        queueGuardAlone.onListChoice(choice, state),
                        withDefaultSettings.onListChoice(choice, state),
                    )
                    count()
                }
            }
        }
        assertEquals("cases of the grid: 2 ask x 21 states x 4 choices", 168, compared)
    }

    @Test
    fun ac14_defaultSettings_streamDetailsIsOfferedExactlyWhereQueueGuardAloneOffersIt() {
        assertTheGridIsComplete()
        val compared = withAskOnAndOff { ask, count ->
            for (state in states) {
                assertEquals(
                    "offersStreamDetails with $ask, $state",
                    queueGuardAlone.offersStreamDetails(state),
                    withDefaultSettings.offersStreamDetails(state),
                )
                count()
            }
        }
        assertEquals("cases of the grid: 2 ask x 21 states", 42, compared)
    }
}
