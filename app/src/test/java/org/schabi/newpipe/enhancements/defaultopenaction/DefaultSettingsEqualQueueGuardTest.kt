package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.OpenWay
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.QueueGuardPolicy
import org.schabi.newpipe.enhancements.queueguard.TapRequest

/**
 * AC-14: "With both settings on their defaults the app behaves exactly as with queue-guard alone."
 *
 * Plan section 6 names the proof, plan B2.7 its grid since amendment 2: "the grid loses the states with
 * auto-enqueued videos (13 states: four kinds with 0, 1 and 3 items, and `IDLE`) and gains, per state, the
 * taps of every `OpenWay` and the related tap ... The five comparisons stay": over the whole grid, P with
 * default settings equals `QueueGuardPolicy` alone in all five functions. One test per function. The
 * yardstick is queue-guard's own policy on the same switch, whose rules queue-guard's tests pin down;
 * nothing here repeats them.
 *
 * Before amendment 2 the grid had 21 states (8 of them with `autoQueuedCount`, which queue-guard's model no
 * longer has) and 4 taps; the asserted case counts were 168, 672, 42, 168 and 42. They are now those of 13
 * states and 14 taps.
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

    /** "four kinds": the three players and `null`. */
    private val kinds: List<PlayerKind?> = listOf<PlayerKind?>(null) + ALL_KINDS

    /** "with 0, 1 and 3 items". */
    private val queueSizes = listOf(0, 1, 3)

    /**
     * Every kind with every queue size (12), each on the item [CURRENT_URL], and `PlayerState.IDLE`. 13
     * states; something is playing in 6 of them ([SOMETHING_PLAYING]), one of which is the single video in
     * the main player.
     */
    private val states: List<PlayerState> =
        kinds.flatMap { kind ->
            queueSizes.map { size ->
                PlayerState(kind = kind, queueSize = size, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
            }
        } + PlayerState.IDLE

    /**
     * Per state: the list taps of before ("current or not" and "MPM": the url the states are on and
     * another one, each with both modes, 4), "the taps of every `OpenWay`" (each other way for another
     * video and for the current item, 8), and "the related tap" (in the related list of the current item's
     * page and of another video's page, 2). 14 requests.
     */
    private val taps: List<TapRequest> =
        tapsOnBothUrls(musicPlayerMode = false) + tapsOnBothUrls(musicPlayerMode = true) +
            REQUESTS_BY_OTHER_WAYS + relatedTap() + relatedTap(pageOf = OTHER_VIDEO)

    /** The grid is the one plan B2.7 describes: 13 different states, 14 different requests of all five ways. */
    private fun assertTheGridIsComplete() {
        assertEquals("states of the grid", 13, states.size)
        assertEquals("different states of the grid", 13, states.toSet().size)
        val missing = (SOMETHING_PLAYING + ONE_IN_MAIN + PlayerState.IDLE).filterNot { it in states }
        assertEquals("playing states of the fixtures that the grid lacks", emptyList<PlayerState>(), missing)
        assertEquals("states of the grid in which something is playing", 6, states.count { it.somethingPlaying })

        assertEquals("requests of the grid", 14, taps.size)
        assertEquals("different requests of the grid", 14, taps.toSet().size)
        assertEquals("ways of the requests", ALL_WAYS.toSet(), taps.map { it.way }.toSet())
        assertEquals("requests of another way than the list tap", 8, taps.count { it.way != OpenWay.LIST_TAP })
        assertEquals("taps in a related list", 2, taps.count { it.relatedTo != null })
    }

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
        assertEquals("cases of the grid: 2 ask x 13 states x 14 requests", 364, compared)
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
        assertEquals("cases of the grid: 2 ask x 13 states x 14 requests x 4 choices", 1456, compared)
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
        assertEquals("cases of the grid: 2 ask x 13 states", 26, compared)
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
        assertEquals("cases of the grid: 2 ask x 13 states x 4 choices", 104, compared)
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
        assertEquals("cases of the grid: 2 ask x 13 states", 26, compared)
    }
}
