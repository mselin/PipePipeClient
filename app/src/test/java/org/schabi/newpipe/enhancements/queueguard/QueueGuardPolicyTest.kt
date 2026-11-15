package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule table of plan 3.2 for [QueueGuardPolicy], row by row. Expected values are the table's and the
 * spec's (AC in each test name), for every player kind and every "nothing is playing" state a row
 * covers. Where a row does not mention the switch, the test runs with the switch on and off.
 */
class QueueGuardPolicyTest {

    private val settings = SettableGuardSettings(ask = true)
    private val policy: GuardPolicy = QueueGuardPolicy(settings)

    private val plainTap = tapOn()
    private val musicModeTap = tapOn(startsListInBackground = true)
    private val bothTaps = listOf(plainTap, musicModeTap)

    private val everyState = NOTHING_PLAYING + SOMETHING_PLAYING

    /** Runs [check] with the switch on, then off. [check] gets a label for failure messages. */
    private fun withSwitchOnAndOff(check: (switch: String) -> Unit) {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            check(if (ask) "switch on" else "switch off")
        }
    }

    // =============================================================================================
    // onTap
    // =============================================================================================

    /** Row "not playing -> OPEN_AS_TODAY". */
    @Test
    fun ac7_tapWhileNothingIsPlayingOpensAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                for (tap in bothTaps) {
                    assertEquals("$switch, $state, $tap", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
                }
            }
        }
    }

    /** Row "isCurrent -> OPEN_AS_TODAY". It comes before the switch: no dialog, and no direct Play now either. */
    @Test
    fun ac8_tapOnTheCurrentItemOpensAsTodayInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                for (music in listOf(false, true)) {
                    val tap = tapOnCurrent(startsListInBackground = music)
                    assertEquals("$switch, $state, $tap", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
                }
            }
        }
    }

    /** Plan section 5, AC-8: "same url on another service still asks". */
    @Test
    fun ac8_sameUrlOnAnotherServiceIsNotTheCurrentItemAndAsks() {
        for (state in SOMETHING_PLAYING) {
            val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
            assertEquals("$state", TapEffect.ASK, policy.onTap(tap, state))
        }
    }

    @Test
    fun ac8_ac16_switchOff_sameUrlOnAnotherServiceIsPlayedNowLikeAnyOtherVideo() {
        settings.ask = false
        val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
        assertEquals(TapEffect.START_BACKGROUND, policy.onTap(tap, playing(PlayerKind.BACKGROUND)))
        assertEquals(TapEffect.START_POPUP, policy.onTap(tap, playing(PlayerKind.POPUP)))
    }

    /** Row "askBeforeReplacing() -> ASK", plan section 5 AC-1: "for each PlayerKind". */
    @Test
    fun ac1_tapOnAnotherVideoAsksInMainPlayer() {
        assertEquals(TapEffect.ASK, policy.onTap(plainTap, playing(PlayerKind.MAIN)))
    }

    @Test
    fun ac1_tapOnAnotherVideoAsksInBackgroundPlayer() {
        assertEquals(TapEffect.ASK, policy.onTap(plainTap, playing(PlayerKind.BACKGROUND)))
    }

    @Test
    fun ac1_tapOnAnotherVideoAsksInPopupPlayer() {
        assertEquals(TapEffect.ASK, policy.onTap(plainTap, playing(PlayerKind.POPUP)))
    }

    /** Plan section 5, AC-1: "and for startsListInBackground". */
    @Test
    fun ac1_tapInAListWithMusicPlayerModeAsksInEveryPlayer() {
        for (state in SOMETHING_PLAYING) {
            assertEquals("$state", TapEffect.ASK, policy.onTap(musicModeTap, state))
        }
    }

    /** Answer A1: anything loaded in the player is protected, a single item included, playing or paused. */
    @Test
    fun ac1_queueOfOneItemIsProtectedToo() {
        for (kind in ALL_KINDS) {
            assertEquals("$kind", TapEffect.ASK, policy.onTap(plainTap, playing(kind, queueSize = 1)))
        }
    }

    @Test
    fun ac1_longQueueIsProtected() {
        for (kind in ALL_KINDS) {
            assertEquals("$kind", TapEffect.ASK, policy.onTap(plainTap, playing(kind, queueSize = 500)))
        }
    }

    /** Row "otherwise -> playNow", kind MAIN: today's tap. The page opens and autoplay decides (answer A9). */
    @Test
    fun ac16_switchOff_tapInMainPlayerOpensAsToday() {
        settings.ask = false
        assertEquals(TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, playing(PlayerKind.MAIN)))
        assertEquals(TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, playing(PlayerKind.MAIN, queueSize = 1)))
    }

    /** Row "otherwise -> playNow", kind BACKGROUND. */
    @Test
    fun ac16_switchOff_tapInBackgroundPlayerStartsInBackground() {
        settings.ask = false
        assertEquals(TapEffect.START_BACKGROUND, policy.onTap(plainTap, playing(PlayerKind.BACKGROUND)))
        assertEquals(TapEffect.START_BACKGROUND, policy.onTap(plainTap, playing(PlayerKind.BACKGROUND, queueSize = 1)))
    }

    /** Row "otherwise -> playNow", kind POPUP. */
    @Test
    fun ac16_switchOff_tapInPopupPlayerStartsInPopup() {
        settings.ask = false
        assertEquals(TapEffect.START_POPUP, policy.onTap(plainTap, playing(PlayerKind.POPUP)))
        assertEquals(TapEffect.START_POPUP, policy.onTap(plainTap, playing(PlayerKind.POPUP, queueSize = 1)))
    }

    /** playNow row "startsListInBackground -> OPEN_AS_TODAY" comes before the kind rows. */
    @Test
    fun ac16_switchOff_tapWithMusicPlayerModeOpensAsTodayInEveryPlayer() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            assertEquals("$state", TapEffect.OPEN_AS_TODAY, policy.onTap(musicModeTap, state))
        }
    }

    /** AC-16: "With the switch off no dialog appears." */
    @Test
    fun ac16_switchOff_noTapAsks() {
        settings.ask = false
        val taps = bothTaps + tapOnCurrent() + tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
        for (state in everyState) {
            for (tap in taps) {
                assertNotEquals("$state, $tap", TapEffect.ASK, policy.onTap(tap, state))
            }
        }
    }

    // =============================================================================================
    // onTapChoice
    // =============================================================================================

    /** Row "CANCEL -> NONE". */
    @Test
    fun ac6_cancelDoesNothingWhateverThePlayerDoes() {
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (tap in bothTaps) {
                    assertEquals(
                        "$switch, $state, $tap",
                        TapEffect.NONE,
                        policy.onTapChoice(GuardChoice.CANCEL, tap, state),
                    )
                }
            }
        }
    }

    /** Row "ADD_TO_QUEUE -> ENQUEUE", something playing. */
    @Test
    fun ac3_addToQueueEnqueuesInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                for (tap in bothTaps) {
                    assertEquals(
                        "$switch, $state, $tap",
                        TapEffect.ENQUEUE,
                        policy.onTapChoice(GuardChoice.ADD_TO_QUEUE, tap, state),
                    )
                }
            }
        }
    }

    /** Row "ADD_TO_QUEUE -> ENQUEUE", nothing playing: Enqueue then starts the background player. */
    @Test
    fun ac9_addToQueueAfterThePlayerWasClosedEnqueues() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                for (tap in bothTaps) {
                    assertEquals(
                        "$switch, $state, $tap",
                        TapEffect.ENQUEUE,
                        policy.onTapChoice(GuardChoice.ADD_TO_QUEUE, tap, state),
                    )
                }
            }
        }
    }

    /** Row "PLAY_NEXT -> playing: ENQUEUE_NEXT". */
    @Test
    fun ac4_playNextEnqueuesNextInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                for (tap in bothTaps) {
                    assertEquals(
                        "$switch, $state, $tap",
                        TapEffect.ENQUEUE_NEXT,
                        policy.onTapChoice(GuardChoice.PLAY_NEXT, tap, state),
                    )
                }
            }
        }
    }

    /** Row "PLAY_NEXT -> else ENQUEUE": with nothing playing there is no current item to follow. */
    @Test
    fun ac9_playNextAfterThePlayerWasClosedEnqueues() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                for (tap in bothTaps) {
                    assertEquals(
                        "$switch, $state, $tap",
                        TapEffect.ENQUEUE,
                        policy.onTapChoice(GuardChoice.PLAY_NEXT, tap, state),
                    )
                }
            }
        }
    }

    /** playNow row "kind MAIN -> OPEN_AS_TODAY": what a video tap does today (answer A9). */
    @Test
    fun ac5_playNowInMainPlayerOpensAsToday() {
        withSwitchOnAndOff { switch ->
            for (queueSize in listOf(1, 3)) {
                val state = playing(PlayerKind.MAIN, queueSize = queueSize)
                assertEquals(
                    "$switch, $state",
                    TapEffect.OPEN_AS_TODAY,
                    policy.onTapChoice(GuardChoice.PLAY_NOW, plainTap, state),
                )
            }
        }
    }

    /** playNow row "kind BACKGROUND -> START_BACKGROUND": the tapped video alone (answer A4). */
    @Test
    fun ac5_playNowInBackgroundPlayerStartsInBackground() {
        withSwitchOnAndOff { switch ->
            for (queueSize in listOf(1, 3)) {
                val state = playing(PlayerKind.BACKGROUND, queueSize = queueSize)
                assertEquals(
                    "$switch, $state",
                    TapEffect.START_BACKGROUND,
                    policy.onTapChoice(GuardChoice.PLAY_NOW, plainTap, state),
                )
            }
        }
    }

    /** playNow row "kind POPUP -> START_POPUP": the tapped video alone (answer A4). */
    @Test
    fun ac5_playNowInPopupPlayerStartsInPopup() {
        withSwitchOnAndOff { switch ->
            for (queueSize in listOf(1, 3)) {
                val state = playing(PlayerKind.POPUP, queueSize = queueSize)
                assertEquals(
                    "$switch, $state",
                    TapEffect.START_POPUP,
                    policy.onTapChoice(GuardChoice.PLAY_NOW, plainTap, state),
                )
            }
        }
    }

    /**
     * playNow row "startsListInBackground -> OPEN_AS_TODAY". Spec, Terms: in a local playlist with Music
     * Player Mode on, Play now is what a tap does there today, "whichever player is in use".
     */
    @Test
    fun ac5_playNowWithMusicPlayerModeOpensAsTodayInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                assertEquals(
                    "$switch, $state",
                    TapEffect.OPEN_AS_TODAY,
                    policy.onTapChoice(GuardChoice.PLAY_NOW, musicModeTap, state),
                )
            }
        }
    }

    /**
     * playNow row "not playing -> OPEN_AS_TODAY". It comes before the kind rows: a background or popup
     * player that lost its queue does not get a direct start.
     */
    @Test
    fun ac9_playNowAfterThePlayerWasClosedOpensAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                for (tap in bothTaps) {
                    assertEquals(
                        "$switch, $state, $tap",
                        TapEffect.OPEN_AS_TODAY,
                        policy.onTapChoice(GuardChoice.PLAY_NOW, tap, state),
                    )
                }
            }
        }
    }

    /**
     * The `onTapChoice` rows have no "current item" condition: the choice the user made in the dialog is
     * carried out even if the queue moved on to the tapped video while the dialog was open.
     */
    @Test
    fun ac3_ac4_ac5_choiceIsCarriedOutEvenIfTheTappedVideoBecameTheCurrentItem() {
        val tap = tapOnCurrent()
        for (kind in ALL_KINDS) {
            val state = playing(kind)
            assertEquals("$kind", TapEffect.ENQUEUE, policy.onTapChoice(GuardChoice.ADD_TO_QUEUE, tap, state))
            assertEquals("$kind", TapEffect.ENQUEUE_NEXT, policy.onTapChoice(GuardChoice.PLAY_NEXT, tap, state))
        }
        assertEquals(
            TapEffect.OPEN_AS_TODAY,
            policy.onTapChoice(GuardChoice.PLAY_NOW, tap, playing(PlayerKind.MAIN)),
        )
        assertEquals(
            TapEffect.START_BACKGROUND,
            policy.onTapChoice(GuardChoice.PLAY_NOW, tap, playing(PlayerKind.BACKGROUND)),
        )
        assertEquals(
            TapEffect.START_POPUP,
            policy.onTapChoice(GuardChoice.PLAY_NOW, tap, playing(PlayerKind.POPUP)),
        )
    }

    /** Plan 3.2: `onTapChoice` is "never ASK": a choice does not open a second dialog. */
    @Test
    fun ac3_ac4_ac5_ac6_aChoiceNeverAsksAgain() {
        val choices = listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW, GuardChoice.CANCEL)
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (tap in bothTaps + tapOnCurrent()) {
                    for (choice in choices) {
                        assertNotEquals(
                            "$switch, $choice, $state, $tap",
                            TapEffect.ASK,
                            policy.onTapChoice(choice, tap, state),
                        )
                    }
                }
            }
        }
    }

    // =============================================================================================
    // onListPlay
    // =============================================================================================

    /** Row "not playing, or switch off -> PLAY_AS_TODAY", first half. */
    @Test
    fun ac14_playButtonWhileNothingIsPlayingPlaysAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                assertEquals("$switch, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
            }
        }
    }

    /** Row "otherwise -> ASK". */
    @Test
    fun ac10_playButtonWhileSomethingIsPlayingAsksInEveryPlayer() {
        for (state in SOMETHING_PLAYING) {
            assertEquals("$state", ListEffect.ASK, policy.onListPlay(state))
        }
    }

    /** Row "not playing, or switch off -> PLAY_AS_TODAY", second half. */
    @Test
    fun ac16_switchOff_playButtonPlaysAsTodayInEveryPlayer() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            assertEquals("$state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
        }
    }

    // =============================================================================================
    // onListChoice
    // =============================================================================================

    /** Row "CANCEL -> NONE". */
    @Test
    fun ac13_cancelInTheListDialogDoesNothingWhateverThePlayerDoes() {
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                assertEquals("$switch, $state", ListEffect.NONE, policy.onListChoice(GuardChoice.CANCEL, state))
            }
        }
    }

    /** Row "ADD_TO_QUEUE -> ENQUEUE_ALL", something playing. */
    @Test
    fun ac11_addAllToQueueEnqueuesAllInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                assertEquals(
                    "$switch, $state",
                    ListEffect.ENQUEUE_ALL,
                    policy.onListChoice(GuardChoice.ADD_TO_QUEUE, state),
                )
            }
        }
    }

    /** Row "PLAY_NEXT -> playing: ENQUEUE_ALL_NEXT". */
    @Test
    fun ac11_playAllNextEnqueuesAllNextInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                assertEquals(
                    "$switch, $state",
                    ListEffect.ENQUEUE_ALL_NEXT,
                    policy.onListChoice(GuardChoice.PLAY_NEXT, state),
                )
            }
        }
    }

    /**
     * Rows "ADD_TO_QUEUE -> ENQUEUE_ALL" and "PLAY_NEXT -> else ENQUEUE_ALL" with nothing playing. AC-11
     * and answer A5: both do what "Enqueue" does while nothing is playing, the list starts in the
     * background player.
     */
    @Test
    fun ac11_addAllAndPlayAllNextAfterThePlayerWasClosedBothEnqueueAll() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                assertEquals(
                    "$switch, add all, $state",
                    ListEffect.ENQUEUE_ALL,
                    policy.onListChoice(GuardChoice.ADD_TO_QUEUE, state),
                )
                assertEquals(
                    "$switch, play all next, $state",
                    ListEffect.ENQUEUE_ALL,
                    policy.onListChoice(GuardChoice.PLAY_NEXT, state),
                )
            }
        }
    }

    /** Row "PLAY_NOW -> PLAY_AS_TODAY", something playing. */
    @Test
    fun ac12_replaceQueuePlaysAsTodayInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                assertEquals(
                    "$switch, $state",
                    ListEffect.PLAY_AS_TODAY,
                    policy.onListChoice(GuardChoice.PLAY_NOW, state),
                )
            }
        }
    }

    /** Row "PLAY_NOW -> PLAY_AS_TODAY", nothing playing (answer A5: the button as today). */
    @Test
    fun ac12_replaceQueueAfterThePlayerWasClosedPlaysAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                assertEquals(
                    "$switch, $state",
                    ListEffect.PLAY_AS_TODAY,
                    policy.onListChoice(GuardChoice.PLAY_NOW, state),
                )
            }
        }
    }

    /** Plan 3.2: `onListChoice` is "never ASK". */
    @Test
    fun ac11_ac12_ac13_aListChoiceNeverAsksAgain() {
        val choices = listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW, GuardChoice.CANCEL)
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (choice in choices) {
                    assertNotEquals("$switch, $choice, $state", ListEffect.ASK, policy.onListChoice(choice, state))
                }
            }
        }
    }

    // =============================================================================================
    // offersStreamDetails
    // =============================================================================================
    //
    // Plan section 8 reads AC-17's "in use" as: something is playing in the background or popup player.
    // The spec says the entry is needed "whatever the switch is set to", so both positions are checked.

    @Test
    fun ac17_streamDetailsAreOfferedWhileTheBackgroundPlayerIsPlaying() {
        withSwitchOnAndOff { switch ->
            assertTrue(switch, policy.offersStreamDetails(playing(PlayerKind.BACKGROUND)))
            assertTrue(
                "$switch, queue of one",
                policy.offersStreamDetails(playing(PlayerKind.BACKGROUND, queueSize = 1)),
            )
        }
    }

    @Test
    fun ac17_streamDetailsAreOfferedWhileThePopupPlayerIsPlaying() {
        withSwitchOnAndOff { switch ->
            assertTrue(switch, policy.offersStreamDetails(playing(PlayerKind.POPUP)))
            assertTrue("$switch, queue of one", policy.offersStreamDetails(playing(PlayerKind.POPUP, queueSize = 1)))
        }
    }

    /** In the main player a tap can still reach the page (AC-5, AC-16), so the menu stays as it is. */
    @Test
    fun ac17_streamDetailsAreNotOfferedWhileTheMainPlayerIsPlaying() {
        withSwitchOnAndOff { switch ->
            assertFalse(switch, policy.offersStreamDetails(playing(PlayerKind.MAIN)))
            assertFalse("$switch, queue of one", policy.offersStreamDetails(playing(PlayerKind.MAIN, queueSize = 1)))
        }
    }

    /** With nothing playing a tap opens the page as today (AC-7), also with an emptied background or popup one. */
    @Test
    fun ac17_streamDetailsAreNotOfferedWhileNothingIsPlaying() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                assertFalse("$switch, $state", policy.offersStreamDetails(state))
            }
        }
    }

    // =============================================================================================
    // The switch
    // =============================================================================================

    /** AC-15: "It is on by default". The settings XML carries the same default (QueueGuardResourcesTest). */
    @Test
    fun ac15_askBeforeReplacingIsOnByDefault() {
        assertTrue(ASK_BEFORE_REPLACING_DEFAULT)
    }

    /** AC-15: the switch "covers video taps and play buttons together". */
    @Test
    fun ac15_oneSwitchCoversVideoTapsAndPlayButtons() {
        for (state in SOMETHING_PLAYING) {
            settings.ask = true
            assertEquals("switch on, tap, $state", TapEffect.ASK, policy.onTap(plainTap, state))
            assertEquals("switch on, play button, $state", ListEffect.ASK, policy.onListPlay(state))

            settings.ask = false
            assertNotEquals("switch off, tap, $state", TapEffect.ASK, policy.onTap(plainTap, state))
            assertNotEquals("switch off, play button, $state", ListEffect.ASK, policy.onListPlay(state))
        }
    }

    /**
     * The glue keeps one policy for the life of the app (plan 3.3, `QueueGuard` fields) and
     * `PrefsGuardSettings` is "read on every call": flipping the switch in Settings must take effect at
     * the next tap, without a restart. So the policy asks its [GuardSettings] at each decision.
     */
    @Test
    fun ac15_ac16_switchIsReadAtEveryDecision() {
        val state = playing(PlayerKind.BACKGROUND)

        settings.ask = true
        assertEquals(TapEffect.ASK, policy.onTap(plainTap, state))
        assertEquals(ListEffect.ASK, policy.onListPlay(state))

        settings.ask = false
        assertEquals(TapEffect.START_BACKGROUND, policy.onTap(plainTap, state))
        assertEquals(ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))

        settings.ask = true
        assertEquals(TapEffect.ASK, policy.onTap(plainTap, state))
        assertEquals(ListEffect.ASK, policy.onListPlay(state))
    }

    /** `GuardSettings` is a `fun interface` (plan 3.2): default-open-action and the glue pass a lambda. */
    @Test
    fun ac15_ac16_settingsCanBeGivenAsALambda() {
        val state = playing(PlayerKind.POPUP)
        assertEquals(TapEffect.ASK, QueueGuardPolicy { true }.onTap(plainTap, state))
        assertEquals(TapEffect.START_POPUP, QueueGuardPolicy { false }.onTap(plainTap, state))
    }
}
