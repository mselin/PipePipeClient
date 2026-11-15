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
 * Plan section 6 names the proof: over the whole grid "(kind incl. `null`, queue size 0 and 3, current or
 * not, ask, MPM, every `GuardChoice`), P with default settings equals `QueueGuardPolicy` alone in all five
 * functions". One test per function. The yardstick is queue-guard's own policy on the same switch, whose
 * rules queue-guard's tests pin down; nothing here repeats them.
 *
 * "Default settings" are the enums' `DEFAULT` (what the glue falls back to while nothing is stored, plan
 * 4.4); that these are *Play now* and *Video page* is checked in [OpenActionModelTest].
 */
class DefaultSettingsEqualQueueGuardTest {

    private val askSwitch = FakeAskSwitch()
    private val queueGuardAlone: GuardPolicy = QueueGuardPolicy(askSwitch)
    private val withDefaultSettings: GuardPolicy = OpenActionPolicy(
        QueueGuardPolicy(askSwitch),
        FakeOpenActionSettings(action = DefaultTapAction.DEFAULT, startIn = StartPlaybackIn.DEFAULT),
    )

    /** "kind incl. `null`". */
    private val kinds: List<PlayerKind?> = listOf<PlayerKind?>(null) + ALL_KINDS

    /** "queue size 0 and 3". */
    private val queueSizes = listOf(0, 3)

    /**
     * Every kind with every queue size, each on the item [CURRENT_URL], and `PlayerState.IDLE` on top.
     * 9 states; something is playing in 3 of them.
     */
    private val states: List<PlayerState> =
        kinds.flatMap { kind ->
            queueSizes.map { size ->
                PlayerState(kind = kind, queueSize = size, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
            }
        } + PlayerState.IDLE

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
        assertEquals("cases of the grid: 2 ask x 9 states x 4 taps", 72, compared)
    }

    @Test
    fun ac14_defaultSettings_aDialogChoiceDoesWhatItDoesWithQueueGuardAlone() {
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
        assertEquals("cases of the grid: 2 ask x 9 states x 4 taps x 4 choices", 288, compared)
    }

    @Test
    fun ac14_defaultSettings_aPlayButtonDoesWhatItDoesWithQueueGuardAlone() {
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
        assertEquals("cases of the grid: 2 ask x 9 states", 18, compared)
    }

    @Test
    fun ac14_defaultSettings_aListDialogChoiceDoesWhatItDoesWithQueueGuardAlone() {
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
        assertEquals("cases of the grid: 2 ask x 9 states x 4 choices", 72, compared)
    }

    @Test
    fun ac14_defaultSettings_streamDetailsIsOfferedExactlyWhereQueueGuardAloneOffersIt() {
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
        assertEquals("cases of the grid: 2 ask x 9 states", 18, compared)
    }
}
