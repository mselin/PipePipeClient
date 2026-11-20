package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule table of [QueueGuardPolicy], row by row: plan 3.2 as replaced by plan A2.2 for `onTap` (rows
 * 1 to 7), `onTapChoice` (rows 8 to 12) and `onListPlay` (rows 13 and 14). Expected values are the
 * table's and the spec's (AC or AM in each test name), for every player kind and every "nothing is
 * playing" state a row covers. Where a row does not mention the switch, the test runs with the switch on
 * and off.
 *
 * Amendment 2 (AM-1) turned the first row of `onTap` and of `onListPlay` back to "something is playing":
 * a single video in the main player asks, and so does a queue that grew by "auto-enqueue next stream".
 * The tests that pinned the opposite (answers A11 and A13) are inverted in the section "A queue worth
 * guarding, as amended"; each says in its KDoc what it used to expect. Tests that expect a dialog run
 * over [SOMETHING_PLAYING]. The sections "Amendment 2" cover the new rows: requests that are not list
 * taps ([OpenWay]), the related list (AM-2), and the order of the rows (first match wins).
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

    /** Row 1, "not playing -> OPEN_AS_TODAY". AC-7 as replaced by AM-1: "while the player holds nothing". */
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

    /** Plan section 5, AC-8: "same url on another service still asks", in every state with something playing. */
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

    /** Plan section 5, AC-1: "and for startsListInBackground", in every state worth guarding. */
    @Test
    fun ac1_tapInAListWithMusicPlayerModeAsksInEveryPlayer() {
        for (state in SOMETHING_PLAYING) {
            assertEquals("$state", TapEffect.ASK, policy.onTap(musicModeTap, state))
        }
    }

    /**
     * Answer A1: anything loaded in the player is protected, a single item included, playing or paused.
     * Answer A11 had taken the main player out of this test; AM-1 puts it back ("one video counts"), so
     * the test runs over every player again (plan A2.10: "holds for the main player again").
     */
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

    /**
     * Row 14, "otherwise -> ASK": every state with a queue worth guarding, which is every state with something
     * playing.
     */
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

    /** AC-15: the switch "covers video taps and play buttons together", wherever there is something to ask. */
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

    // =============================================================================================
    // A queue worth guarding, as amended (AM-1)
    // =============================================================================================
    //
    // Before Amendment 2 this section pinned answers A11 and A13: the main player with at most one video
    // the user put there was not guarded, and videos the player added by itself never counted. AM-1
    // withdraws both. The model cannot tell any more how a video got into the queue, so "one chosen
    // video and two auto-enqueued ones" is simply the main player with three items.

    private fun main(queueSize: Int) = playing(PlayerKind.MAIN, queueSize = queueSize)

    /**
     * The main player from a single video on. 1: one video. 2 and 3: what used to be "it and the
     * auto-enqueued next ones" (the removed fixture `SINGLE_IN_MAIN`), or a queue the user built.
     */
    private val mainFromOneVideoOn = listOf(main(1), main(2), main(3), main(5))

    /**
     * INVERTED by AM-1. Was `ac7_tapWithASingleVideoInTheMainPlayerOpensAsToday` and expected
     * `OPEN_AS_TODAY` with the switch on or off (answer A11, the old row "not worth guarding"). Now row 7
     * holds for it: with the switch on the tap asks, Music Player Mode or not. With the switch off it is
     * row 3, *playNow*, which is `OPEN_AS_TODAY` in the main player (AC-16).
     */
    @Test
    fun am1_tapWithASingleVideoInTheMainPlayerAsks() {
        for (state in mainFromOneVideoOn) {
            for (tap in bothTaps) {
                settings.ask = true
                assertEquals("switch on, $state, $tap", TapEffect.ASK, policy.onTap(tap, state))
                settings.ask = false
                assertEquals("switch off, $state, $tap", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
            }
        }
    }

    /**
     * AC-8 holds in every state. Was `ac8_tapOnTheCurrentItemOpensAsTodayWhetherTheQueueIsWorthGuardingOrNot`;
     * the result is the same, the fixture is [SOMETHING_PLAYING] and the main player from one video on.
     */
    @Test
    fun ac8_tapOnTheCurrentItemOpensAsTodayWhateverTheQueue() {
        withSwitchOnAndOff { switch ->
            for (state in mainFromOneVideoOn + SOMETHING_PLAYING) {
                for (music in listOf(false, true)) {
                    val tap = tapOnCurrent(startsListInBackground = music)
                    assertEquals("$switch, $state, $tap", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
                }
            }
        }
    }

    /**
     * INVERTED by AM-1. Was `ac7_ac8_sameUrlOnAnotherServiceOpensAsTodayWithASingleVideoInTheMainPlayer`
     * and expected `OPEN_AS_TODAY`. Now the same url on another service is another video (AC-8) and a
     * single video in the main player is a queue worth guarding (AM-1): it asks.
     */
    @Test
    fun am1_ac8_sameUrlOnAnotherServiceAsksWithASingleVideoInTheMainPlayer() {
        val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
        for (state in mainFromOneVideoOn) {
            settings.ask = true
            assertEquals("switch on, $state", TapEffect.ASK, policy.onTap(tap, state))
            settings.ask = false
            assertEquals("switch off, $state", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
        }
    }

    /**
     * Row 7 for the main player with several videos (AC-1). Was
     * `ac1_mainPlayerWithTwoVideosOfTheUsersAsksWithAndWithoutAutoQueuedOnes`; same result, the states
     * no longer say who added the videos.
     */
    @Test
    fun ac1_mainPlayerWithSeveralVideosAsks() {
        for (state in listOf(main(2), main(3), main(5))) {
            for (tap in bothTaps) {
                assertEquals("$state, $tap", TapEffect.ASK, policy.onTap(tap, state))
            }
        }
    }

    /** Row 7 over the whole fixture, both kinds of tap (AC-1, AM-1). */
    @Test
    fun ac1_tapAsksForEveryQueueWorthGuarding() {
        for (state in SOMETHING_PLAYING) {
            for (tap in bothTaps) {
                assertEquals("$state, $tap", TapEffect.ASK, policy.onTap(tap, state))
            }
        }
    }

    /**
     * INVERTED by AM-1. Was `ac1_ac7_theSecondVideoOfTheUsersMakesTheTapAsk`: one video opened as today
     * and the second one of the user's brought the dialog. Now the first video does: the step is from an
     * empty player (row 1) to one item (row 7), and every longer queue asks as well.
     */
    @Test
    fun am1_theFirstVideoMakesTheTapAsk() {
        assertEquals("no player", TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, PlayerState.IDLE))
        assertEquals("empty", TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, openButEmpty(PlayerKind.MAIN)))
        assertEquals("1", TapEffect.ASK, policy.onTap(plainTap, main(1)))
        assertEquals("2", TapEffect.ASK, policy.onTap(plainTap, main(2)))
        assertEquals("3", TapEffect.ASK, policy.onTap(plainTap, main(3)))
        assertEquals("4", TapEffect.ASK, policy.onTap(plainTap, main(4)))
    }

    /**
     * AM-1, the opposite of the removed `ac7_videosThePlayerAddedByItselfNeverMakeTheTapAsk` (answer
     * A13): "videos added by auto-enqueue next stream" count. However long the main player's queue grew
     * by itself behind one chosen video, the tap and the play button ask.
     */
    @Test
    fun am1_aQueueThatGrewByItselfBehindOneVideoAsksLikeAnyOther() {
        for (autoQueued in listOf(1, 2, 10, 200)) {
            val watchedOneAndLetItRun = main(autoQueued + 1)
            settings.ask = true
            assertEquals("$watchedOneAndLetItRun", TapEffect.ASK, policy.onTap(plainTap, watchedOneAndLetItRun))
            assertEquals(
                "play button, $watchedOneAndLetItRun",
                ListEffect.ASK,
                policy.onListPlay(watchedOneAndLetItRun),
            )
            settings.ask = false
            assertEquals(
                "switch off, $watchedOneAndLetItRun",
                TapEffect.OPEN_AS_TODAY,
                policy.onTap(plainTap, watchedOneAndLetItRun),
            )
        }
    }

    /**
     * The background and the popup player ask for every queue length (AC-1, AC-10). Was
     * `ac1_ac10_backgroundAndPopupPlayerAskWhateverWasAutoQueued`; same result on plain states.
     */
    @Test
    fun ac1_ac10_backgroundAndPopupPlayerAskWhateverTheQueueLength() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 2, 3, 40)) {
                val state = playing(kind, queueSize = queueSize)
                assertEquals("tap, $state", TapEffect.ASK, policy.onTap(plainTap, state))
                assertEquals("play button, $state", ListEffect.ASK, policy.onListPlay(state))
            }
        }
    }

    /**
     * INVERTED by AM-1. Was `ac14_playButtonWithASingleVideoInTheMainPlayerPlaysAsToday` and expected
     * `PLAY_AS_TODAY` with the switch on or off. Now row 14 holds: with the switch on a play button asks
     * (AC-10) from a single video in the main player on. With the switch off it is row 13 (AC-16).
     */
    @Test
    fun am1_ac10_playButtonWithASingleVideoInTheMainPlayerAsks() {
        for (state in mainFromOneVideoOn) {
            settings.ask = true
            assertEquals("switch on, $state", ListEffect.ASK, policy.onListPlay(state))
            settings.ask = false
            assertEquals("switch off, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
        }
    }

    /**
     * INVERTED by AM-1. Was `ac10_playButtonAsksInTheMainPlayerFromTheSecondVideoOfTheUsersOn`, with
     * `PLAY_AS_TODAY` for one video. Now the step is from an empty player (row 13) to one item (row 14).
     */
    @Test
    fun am1_ac10_playButtonAsksInTheMainPlayerFromTheFirstVideoOn() {
        assertEquals("no player", ListEffect.PLAY_AS_TODAY, policy.onListPlay(PlayerState.IDLE))
        assertEquals("empty", ListEffect.PLAY_AS_TODAY, policy.onListPlay(openButEmpty(PlayerKind.MAIN)))
        for (state in listOf(main(1), main(2), main(3), main(5))) {
            assertEquals("$state", ListEffect.ASK, policy.onListPlay(state))
        }
    }

    /**
     * AC-16 in the main player, from a single video on: with the switch off a tap is neither asked about
     * nor played in another player; it is *playNow* for `MAIN`, today's tap. Result unchanged by
     * Amendment 2, the row is now row 3 (row 2 for the current item).
     */
    @Test
    fun ac16_switchOff_singleVideoInTheMainPlayerNeitherAsksNorStartsAnotherPlayer() {
        settings.ask = false
        val taps = bothTaps + tapOnCurrent() + tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
        for (state in mainFromOneVideoOn) {
            for (tap in taps) {
                val effect = policy.onTap(tap, state)
                assertNotEquals("$state, $tap", TapEffect.ASK, effect)
                assertNotEquals("$state, $tap", TapEffect.START_BACKGROUND, effect)
                assertNotEquals("$state, $tap", TapEffect.START_POPUP, effect)
                assertEquals("$state, $tap", TapEffect.OPEN_AS_TODAY, effect)
            }
            assertEquals("play button, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
        }
    }

    /** AC-16 with a queue worth guarding: Play now directly, by the player in use; play buttons as today. */
    @Test
    fun ac16_switchOff_tapPlaysNowDirectlyForEveryQueueWorthGuarding() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            val expected = when (state.kind) {
                PlayerKind.BACKGROUND -> TapEffect.START_BACKGROUND
                PlayerKind.POPUP -> TapEffect.START_POPUP
                else -> TapEffect.OPEN_AS_TODAY
            }
            assertEquals("$state", expected, policy.onTap(plainTap, state))
            assertEquals("Music Player Mode, $state", TapEffect.OPEN_AS_TODAY, policy.onTap(musicModeTap, state))
            assertEquals("play button, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
        }
    }

    /**
     * Rows 8 to 12: a choice is judged on "playing" at the moment it is made. If the main player is down
     * to one video when the user chooses, the choice is carried out: `ENQUEUE`, `ENQUEUE_NEXT`, and
     * *playNow* by kind, which is `OPEN_AS_TODAY` for the main player.
     */
    @Test
    fun ac3_ac4_ac5_choiceIsCarriedOutWithASingleVideoInTheMainPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in mainFromOneVideoOn) {
                for (tap in bothTaps + tapOnCurrent()) {
                    val case = "$switch, $state, $tap"
                    assertEquals(case, TapEffect.ENQUEUE, policy.onTapChoice(GuardChoice.ADD_TO_QUEUE, tap, state))
                    assertEquals(case, TapEffect.ENQUEUE_NEXT, policy.onTapChoice(GuardChoice.PLAY_NEXT, tap, state))
                    assertEquals(case, TapEffect.OPEN_AS_TODAY, policy.onTapChoice(GuardChoice.PLAY_NOW, tap, state))
                    assertEquals(case, TapEffect.NONE, policy.onTapChoice(GuardChoice.CANCEL, tap, state))
                }
            }
        }
    }

    /** The choice rows for a list tap in every state with something playing (AC-3, AC-4, AC-5). */
    @Test
    fun ac3_ac4_ac5_choiceIsCarriedOutForEveryQueueWorthGuarding() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                val case = "$switch, $state"
                val playNow = when (state.kind) {
                    PlayerKind.BACKGROUND -> TapEffect.START_BACKGROUND
                    PlayerKind.POPUP -> TapEffect.START_POPUP
                    else -> TapEffect.OPEN_AS_TODAY
                }
                assertEquals(case, TapEffect.ENQUEUE, policy.onTapChoice(GuardChoice.ADD_TO_QUEUE, plainTap, state))
                assertEquals(case, TapEffect.ENQUEUE_NEXT, policy.onTapChoice(GuardChoice.PLAY_NEXT, plainTap, state))
                assertEquals(case, playNow, policy.onTapChoice(GuardChoice.PLAY_NOW, plainTap, state))
            }
        }
    }

    /** As above for the list dialog: `ENQUEUE_ALL`, `ENQUEUE_ALL_NEXT`, `PLAY_AS_TODAY`. */
    @Test
    fun ac11_ac12_listChoiceIsCarriedOutWithASingleVideoInTheMainPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in mainFromOneVideoOn) {
                val case = "$switch, $state"
                assertEquals(case, ListEffect.ENQUEUE_ALL, policy.onListChoice(GuardChoice.ADD_TO_QUEUE, state))
                assertEquals(case, ListEffect.ENQUEUE_ALL_NEXT, policy.onListChoice(GuardChoice.PLAY_NEXT, state))
                assertEquals(case, ListEffect.PLAY_AS_TODAY, policy.onListChoice(GuardChoice.PLAY_NOW, state))
                assertEquals(case, ListEffect.NONE, policy.onListChoice(GuardChoice.CANCEL, state))
            }
        }
    }

    /** `offersStreamDetails` is unchanged by Amendment 2: `false` for the main player, whatever its queue. */
    @Test
    fun ac17_streamDetailsAreNotOfferedWithASingleVideoInTheMainPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in mainFromOneVideoOn) {
                assertFalse("$switch, $state", policy.offersStreamDetails(state))
            }
        }
    }

    /**
     * Background and popup player always offer "Show stream details". Was
     * `ac17_streamDetailsAreOfferedInTheBackgroundAndPopupPlayerWhateverWasAutoQueued`; same result.
     */
    @Test
    fun ac17_streamDetailsAreOfferedInTheBackgroundAndPopupPlayerWhateverTheQueueLength() {
        withSwitchOnAndOff { switch ->
            for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
                for (queueSize in listOf(1, 2, 3)) {
                    assertTrue("$switch, $kind, $queueSize", policy.offersStreamDetails(playing(kind, queueSize)))
                }
            }
        }
    }

    // =============================================================================================
    // Amendment 2: onTap, rows 1 to 7, for every way a request can come by
    // =============================================================================================

    /** Every request the tests below put to the policy for a video that is not the current item. */
    private val requestsForAnotherVideo: List<TapRequest> =
        ALL_WAYS.map { requestBy(it) } + musicModeTap + relatedTap() + relatedTap(pageOf = OTHER_VIDEO)

    private fun playNowBy(kind: PlayerKind?): TapEffect = when (kind) {
        PlayerKind.BACKGROUND -> TapEffect.START_BACKGROUND
        PlayerKind.POPUP -> TapEffect.START_POPUP
        else -> TapEffect.OPEN_AS_TODAY
    }

    /**
     * Row 1 (AM-1): "while the player holds nothing, video taps and play buttons behave as today and no
     * dialog appears", by whichever way the request comes, switch on or off.
     */
    @Test
    fun am1_row1_noRequestOfAnyWayIsChangedWhileNothingIsPlaying() {
        withSwitchOnAndOff { switch ->
            for (state in NOTHING_PLAYING) {
                for (request in requestsForAnotherVideo + ALL_WAYS.map { requestForCurrentBy(it) }) {
                    assertEquals("$switch, $state, $request", TapEffect.OPEN_AS_TODAY, policy.onTap(request, state))
                }
            }
        }
    }

    /**
     * Row 2, first half (AC-8, AM-9, AM-10, AM-11): a list tap, a page control and an opened page for the
     * current item are `OPEN_AS_TODAY` "whatever the queue size" (plan A2.15), switch on or off. The
     * hooks then keep the queue (P5, P6, the seek of L1).
     */
    @Test
    fun ac8_am9_row2_listTapPageControlAndVideoPageForTheCurrentItemOpenAsTodayWhateverTheQueueLength() {
        withSwitchOnAndOff { switch ->
            for (kind in ALL_KINDS) {
                for (queueSize in listOf(1, 2, 3, 40)) {
                    val state = playing(kind, queueSize = queueSize)
                    for (way in listOf(OpenWay.LIST_TAP, OpenWay.PAGE_CONTROL, OpenWay.VIDEO_PAGE)) {
                        assertEquals(
                            "$switch, $state, $way",
                            TapEffect.OPEN_AS_TODAY,
                            policy.onTap(requestForCurrentBy(way), state),
                        )
                    }
                }
            }
        }
    }

    /**
     * AM-10: "A link to a time in the current item seeks in place and keeps the queue, also when the
     * queue is longer than that one video." The glue seeks before it asks the policy (plan A2.15); should
     * the request reach the policy all the same, it never asks and never plays now: in the main player
     * with three items, where another video's link is row 7, the current item's is row 2.
     */
    @Test
    fun am10_aTimeLinkToTheCurrentItemOfALongerQueueNeverAsks() {
        val link = requestForCurrentBy(OpenWay.VIDEO_PAGE)
        val linkToAnotherVideo = requestBy(OpenWay.VIDEO_PAGE)
        settings.ask = true
        for (queueSize in listOf(1, 3, 40)) {
            val state = main(queueSize)
            assertEquals("the current item, $state", TapEffect.OPEN_AS_TODAY, policy.onTap(link, state))
            assertEquals("another video, $state", TapEffect.ASK, policy.onTap(linkToAnotherVideo, state))
        }
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            assertEquals("$kind", TapEffect.OPEN_AS_TODAY, policy.onTap(link, playing(kind, queueSize = 3)))
        }
    }

    /**
     * Row 2, second half (plan A2.15): "background player" or "popup player" chosen in another app for
     * the current item is `OPEN_AS_TODAY` "only while the queue is that one video", switch on or off.
     */
    @Test
    fun am6_row2_playerActionForTheCurrentItemOpensAsTodayWhileItIsTheWholeQueue() {
        withSwitchOnAndOff { switch ->
            for (kind in ALL_KINDS) {
                for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
                    assertEquals(
                        "$switch, $kind, $way",
                        TapEffect.OPEN_AS_TODAY,
                        policy.onTap(requestForCurrentBy(way), playing(kind, queueSize = 1)),
                    )
                }
            }
        }
    }

    /**
     * Row 2 does not match, row 7 does (AM-6, answer A17, plan A2.15): "a longer queue would be replaced
     * by the video alone, so it asks like any other video". Fails if row 2 lets every request for the
     * current item through.
     */
    @Test
    fun am6_row7_playerActionForTheCurrentItemOfALongerQueueAsks() {
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(2, 3, 40)) {
                for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
                    assertEquals(
                        "$kind, queue of $queueSize, $way",
                        TapEffect.ASK,
                        policy.onTap(requestForCurrentBy(way), playing(kind, queueSize = queueSize)),
                    )
                }
            }
        }
    }

    /** The same request with the switch off is row 4 (AM-7): as today, no dialog. */
    @Test
    fun am7_row4_switchOff_playerActionForTheCurrentItemOfALongerQueueOpensAsToday() {
        settings.ask = false
        for (kind in ALL_KINDS) {
            for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
                assertEquals(
                    "$kind, $way",
                    TapEffect.OPEN_AS_TODAY,
                    policy.onTap(requestForCurrentBy(way), playing(kind, queueSize = 3)),
                )
            }
        }
    }

    /**
     * Row 2 compares service and address (AC-8): the same address on another service is another video, by every way.
     */
    @Test
    fun ac8_am3_row2_sameAddressOnAnotherServiceIsNotTheCurrentItemByAnyWay() {
        for (way in ALL_WAYS) {
            val request = requestBy(way, url = CURRENT_URL, serviceId = OTHER_SERVICE)
            assertEquals("$way", TapEffect.ASK, policy.onTap(request, main(3)))
            assertEquals("$way, queue of one", TapEffect.ASK, policy.onTap(request, main(1)))
        }
    }

    /**
     * Row 4 (AM-7): "With Ask before replacing the queue off, AM-3, AM-5 and AM-6 do not apply and those
     * paths behave as today." A page control, a link, a typed address and a router action are
     * `OPEN_AS_TODAY` in every player; never the direct start of row 3, which is for list taps only.
     */
    @Test
    fun am7_row4_switchOff_pageControlsLinksAndRouterActionsBehaveAsTodayInEveryPlayer() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            for (way in NOT_LIST_WAYS) {
                for (request in listOf(requestBy(way), requestForCurrentBy(way))) {
                    assertEquals("$state, $request", TapEffect.OPEN_AS_TODAY, policy.onTap(request, state))
                }
            }
        }
    }

    /** AM-7 and AC-16 together: with the switch off no request of any way asks, in any state. */
    @Test
    fun am7_ac16_switchOff_noRequestOfAnyWayAsks() {
        settings.ask = false
        for (state in everyState) {
            for (request in requestsForAnotherVideo + ALL_WAYS.map { requestForCurrentBy(it) }) {
                assertNotEquals("$state, $request", TapEffect.ASK, policy.onTap(request, state))
            }
        }
    }

    /**
     * Rows 3 and 4 side by side (AM-7): "with the switch off only list taps are changed by this
     * feature". In the background and the popup player a list tap starts there directly, every other way
     * is left alone.
     */
    @Test
    fun am7_ac16_switchOff_onlyListTapsArePlayedNowDirectly() {
        settings.ask = false
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            val state = playing(kind)
            assertEquals("$kind, list tap", playNowBy(kind), policy.onTap(requestBy(OpenWay.LIST_TAP), state))
            for (way in NOT_LIST_WAYS) {
                assertEquals("$kind, $way", TapEffect.OPEN_AS_TODAY, policy.onTap(requestBy(way), state))
            }
        }
    }

    /**
     * Row 5 (AM-2): "a video tap in the list of related videos on the video page of the current item
     * opens the video as today, without a dialog", in every player and for every queue length.
     */
    @Test
    fun am2_row5_relatedTapOnThePageOfTheCurrentItemOpensAsTodayWithoutADialog() {
        settings.ask = true
        for (state in SOMETHING_PLAYING + main(40)) {
            assertEquals("$state", TapEffect.OPEN_AS_TODAY, policy.onTap(relatedTap(pageOf = CURRENT_VIDEO), state))
        }
    }

    /**
     * Row 5 does not match (AM-2 is "one exception"): the related list on the page of a video that is
     * not the current item asks like any list (device script step 2, second half). The page's video is
     * compared by service and address.
     */
    @Test
    fun am2_row7_relatedTapOnThePageOfAnotherVideoAsks() {
        settings.ask = true
        val pages = listOf(
            OTHER_VIDEO,
            VideoRef(OTHER_SERVICE, CURRENT_URL),
            VideoRef(SERVICE, TAPPED_URL),
            VideoRef(SERVICE, "$CURRENT_URL&t=90"),
        )
        for (state in SOMETHING_PLAYING) {
            for (page in pages) {
                assertEquals("$state, page of $page", TapEffect.ASK, policy.onTap(relatedTap(pageOf = page), state))
            }
        }
    }

    /**
     * Row 5 is for list taps only: "`relatedTo` is ignored for every other `way`". A request by another
     * way that carries the current item as `relatedTo` is decided exactly like the same request without
     * it; a page control and a player action of another app still ask.
     */
    @Test
    fun am2_row5_relatedToIsIgnoredForEveryWayButTheListTap() {
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (way in NOT_LIST_WAYS) {
                    val plain = requestBy(way)
                    val withRelated = plain.copy(relatedTo = CURRENT_VIDEO)
                    assertEquals(
                        "$switch, $state, $way",
                        policy.onTap(plain, state),
                        policy.onTap(withRelated, state),
                    )
                }
            }
        }
        settings.ask = true
        for (way in listOf(OpenWay.PAGE_CONTROL, OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
            for (state in SOMETHING_PLAYING) {
                assertEquals(
                    "$way, $state",
                    TapEffect.ASK,
                    policy.onTap(requestBy(way).copy(relatedTo = CURRENT_VIDEO), state),
                )
            }
        }
    }

    /**
     * Row 6 (AM-5, AM-6, "one rule"): "A link, a typed address or a show info action that only opens the
     * page beside the background or popup player opens it as today."
     */
    @Test
    fun am5_am6_row6_openingAVideoPageBesideTheBackgroundOrPopupPlayerOpensAsToday() {
        settings.ask = true
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 3, 40)) {
                assertEquals(
                    "$kind, queue of $queueSize",
                    TapEffect.OPEN_AS_TODAY,
                    policy.onTap(requestBy(OpenWay.VIDEO_PAGE), playing(kind, queueSize = queueSize)),
                )
            }
        }
    }

    /**
     * Row 6 is "not list and not `replacesQueue`": it must not swallow a list tap beside the background
     * or popup player (AC-1), nor any way that does replace the queue there.
     */
    @Test
    fun ac1_am3_row6_onlyTheVideoPageIsLetThroughBesideTheBackgroundOrPopupPlayer() {
        settings.ask = true
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (way in ALL_WAYS - OpenWay.VIDEO_PAGE) {
                assertEquals("$kind, $way", TapEffect.ASK, policy.onTap(requestBy(way), playing(kind)))
            }
        }
    }

    /**
     * Row 7 (AM-3): on the page of a video that is not the current item, "the page's play button, its
     * Background and Popup buttons, a tap on its thumbnail and the mini player's play button open the
     * video-tap dialog", in every player, from a queue of one on.
     */
    @Test
    fun am3_row7_pageControlOfAnotherVideoAsksInEveryPlayer() {
        settings.ask = true
        for (state in SOMETHING_PLAYING) {
            assertEquals("$state", TapEffect.ASK, policy.onTap(requestBy(OpenWay.PAGE_CONTROL), state))
        }
    }

    /**
     * Row 7 (AM-5, AM-6): with the main player, a link with a time, a typed address and "show info" or
     * "video player" for a video of another app ask, from a queue of one on.
     */
    @Test
    fun am5_am6_row7_openingAVideoPageAsksInTheMainPlayer() {
        settings.ask = true
        for (state in mainFromOneVideoOn) {
            assertEquals("$state", TapEffect.ASK, policy.onTap(requestBy(OpenWay.VIDEO_PAGE), state))
        }
    }

    /** Row 7 (AM-6): "background player" and "popup player" chosen for a video of another app ask in every player. */
    @Test
    fun am6_row7_playerActionOfAnotherAppAsksInEveryPlayer() {
        settings.ask = true
        for (state in SOMETHING_PLAYING) {
            for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
                assertEquals("$way, $state", TapEffect.ASK, policy.onTap(requestBy(way), state))
            }
        }
    }

    /**
     * Rows 6 and 7 as one table, switch on, another video (spec, "AM-5 and AM-6, one rule": "A dialog
     * appears exactly where the queue would be replaced"): every way and player asks, except a video
     * page beside the background or popup player.
     */
    @Test
    fun am3_am5_am6_switchOn_aDialogAppearsExactlyWhereTheQueueWouldBeReplaced() {
        settings.ask = true
        for (state in SOMETHING_PLAYING) {
            for (way in ALL_WAYS) {
                val expected =
                    if (way == OpenWay.VIDEO_PAGE && state.kind != PlayerKind.MAIN) {
                        TapEffect.OPEN_AS_TODAY
                    } else {
                        TapEffect.ASK
                    }
                assertEquals("$way, $state", expected, policy.onTap(requestBy(way), state))
                if (way != OpenWay.LIST_TAP) {
                    assertEquals(
                        "$way, $state: asks exactly where replacesQueue says so",
                        replacesQueue(way, state),
                        policy.onTap(requestBy(way), state) == TapEffect.ASK,
                    )
                }
            }
        }
    }

    /**
     * `onTap` never returns an effect of a choice for a request that is not a list tap: no direct start, no enqueue.
     */
    @Test
    fun am3_am7_onTapOfANonListRequestIsOnlyEverAskOrOpenAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (way in NOT_LIST_WAYS) {
                    for (request in listOf(requestBy(way), requestForCurrentBy(way))) {
                        val effect = policy.onTap(request, state)
                        assertTrue(
                            "$switch, $state, $request -> $effect",
                            effect == TapEffect.ASK || effect == TapEffect.OPEN_AS_TODAY,
                        )
                    }
                }
            }
        }
    }

    // =============================================================================================
    // Amendment 2: the order of the rows of onTap (first match wins)
    // =============================================================================================
    //
    // Each test picks a request and a state that two rows match with different results, so it fails if
    // those two rows are swapped. Pairs that give the same result or cannot both match have no test:
    // 1/2, 1/3 (playNow of an empty player is OPEN_AS_TODAY), 1/4 to 1/6, 2/4 to 2/6, 3/4, 4/5, 4/6, 5/6.

    /**
     * Row 2 before row 3 (AC-8 before AC-16): with the switch off a list tap on the current item in the
     * background or popup player is today's tap, not a direct restart of the video in that player.
     */
    @Test
    fun ac8_ac16_rowOrder_theCurrentItemComesBeforeTheSwitch() {
        settings.ask = false
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 3)) {
                val state = playing(kind, queueSize = queueSize)
                assertEquals("$state", TapEffect.OPEN_AS_TODAY, policy.onTap(tapOnCurrent(), state))
                assertEquals("$state: another video is row 3", playNowBy(kind), policy.onTap(plainTap, state))
            }
        }
    }

    /**
     * Row 3 before row 5 (answer A18): "AM-2 only removes the dialog. With the switch off a tap in the
     * related list of the current item's page does what AC-16 says": Play now, in the background or popup
     * player the video starts there.
     */
    @Test
    fun am2_ac16_rowOrder_switchOff_relatedTapOnThePageOfTheCurrentItemIsPlayedNow() {
        settings.ask = false
        assertEquals(
            TapEffect.START_BACKGROUND,
            policy.onTap(relatedTap(pageOf = CURRENT_VIDEO), playing(PlayerKind.BACKGROUND)),
        )
        assertEquals(TapEffect.START_POPUP, policy.onTap(relatedTap(pageOf = CURRENT_VIDEO), playing(PlayerKind.POPUP)))
        assertEquals(
            TapEffect.OPEN_AS_TODAY,
            policy.onTap(relatedTap(pageOf = CURRENT_VIDEO), playing(PlayerKind.MAIN)),
        )
        settings.ask = true
        for (kind in ALL_KINDS) {
            assertEquals("switch on, $kind", TapEffect.OPEN_AS_TODAY, policy.onTap(relatedTap(), playing(kind)))
        }
    }

    /**
     * Row 2 before row 7, and row 2's own limit: with the switch on, the current item never asks by a
     * list tap, a page control or an opened page, while the very same request for another video asks
     * (main player, three items). Fails if row 7 is reached first or row 2 is dropped.
     */
    @Test
    fun ac8_am9_rowOrder_theCurrentItemComesBeforeAsking() {
        settings.ask = true
        val state = main(3)
        for (way in listOf(OpenWay.LIST_TAP, OpenWay.PAGE_CONTROL, OpenWay.VIDEO_PAGE)) {
            assertEquals("$way, current item", TapEffect.OPEN_AS_TODAY, policy.onTap(requestForCurrentBy(way), state))
            assertEquals("$way, another video", TapEffect.ASK, policy.onTap(requestBy(way), state))
        }
    }

    /**
     * Row 4 before row 7 and row 3 before row 7: the switch is looked at before anything asks (AC-16,
     * AM-7), also where `replacesQueue` is true.
     */
    @Test
    fun ac16_am7_rowOrder_theSwitchComesBeforeAsking() {
        for (state in SOMETHING_PLAYING) {
            for (way in ALL_WAYS) {
                settings.ask = false
                assertNotEquals("switch off, $way, $state", TapEffect.ASK, policy.onTap(requestBy(way), state))
            }
        }
        settings.ask = true
        assertEquals(TapEffect.ASK, policy.onTap(requestBy(OpenWay.PAGE_CONTROL), main(3)))
    }

    /**
     * Row 1 before row 7: a request that looks like one for the current item of a closed player (stale
     * item data, empty queue) is as today by every way, and nothing asks.
     */
    @Test
    fun am1_rowOrder_nothingPlayingComesFirst() {
        settings.ask = true
        val closed = listOf(
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL),
            PlayerState(kind = PlayerKind.MAIN, queueSize = 0, currentServiceId = SERVICE, currentUrl = CURRENT_URL),
            PlayerState(PlayerKind.BACKGROUND, queueSize = 0, currentServiceId = SERVICE, currentUrl = CURRENT_URL),
        )
        for (state in closed) {
            for (way in ALL_WAYS) {
                assertEquals("$state, $way", TapEffect.OPEN_AS_TODAY, policy.onTap(requestBy(way), state))
                assertEquals("$state, $way", TapEffect.OPEN_AS_TODAY, policy.onTap(requestForCurrentBy(way), state))
            }
        }
    }

    // =============================================================================================
    // Amendment 2: onTapChoice, rows 8 to 12, for requests that are not list taps
    // =============================================================================================

    /**
     * Row 11 (AM-3): "*Play now* does what the pressed control does today": `OPEN_AS_TODAY`, the replay
     * of the control, link or router action, in every player. Not the direct start of row 12: a page's
     * Popup button pressed beside the background player must open the popup, not the background player.
     */
    @Test
    fun am3_row11_playNowOfARequestThatIsNoListTapOpensAsTodayInEveryPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (way in NOT_LIST_WAYS) {
                    for (request in listOf(requestBy(way), requestForCurrentBy(way))) {
                        assertEquals(
                            "$switch, $state, $request",
                            TapEffect.OPEN_AS_TODAY,
                            policy.onTapChoice(GuardChoice.PLAY_NOW, request, state),
                        )
                    }
                }
            }
        }
    }

    /**
     * Row 12 is unchanged for list taps (AC-5, AC-9): *playNow* by the player in use, also for a tap in a
     * related list. Together with the test above this fails if rows 11 and 12 are mixed up.
     */
    @Test
    fun ac5_row12_playNowOfAListTapStaysPlayNowByThePlayerInUse() {
        withSwitchOnAndOff { switch ->
            for (state in SOMETHING_PLAYING) {
                for (request in listOf(requestBy(OpenWay.LIST_TAP), relatedTap(), relatedTap(pageOf = OTHER_VIDEO))) {
                    assertEquals(
                        "$switch, $state, $request",
                        playNowBy(state.kind),
                        policy.onTapChoice(GuardChoice.PLAY_NOW, request, state),
                    )
                }
            }
            for (state in NOTHING_PLAYING) {
                assertEquals(
                    "$switch, $state",
                    TapEffect.OPEN_AS_TODAY,
                    policy.onTapChoice(GuardChoice.PLAY_NOW, relatedTap(), state),
                )
            }
        }
    }

    /** Row 9 (AC-3, AC-9): *Add to queue* enqueues, by whichever way the request came, playing or not. */
    @Test
    fun ac3_ac9_row9_addToQueueEnqueuesForEveryWay() {
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (request in requestsForAnotherVideo) {
                    assertEquals(
                        "$switch, $state, $request",
                        TapEffect.ENQUEUE,
                        policy.onTapChoice(GuardChoice.ADD_TO_QUEUE, request, state),
                    )
                }
            }
        }
    }

    /** Row 10 (AC-4, AC-9): *Play next* is `ENQUEUE_NEXT` while playing, else `ENQUEUE`, for every way. */
    @Test
    fun ac4_ac9_row10_playNextEnqueuesNextWhilePlayingElseEnqueuesForEveryWay() {
        withSwitchOnAndOff { switch ->
            for (request in requestsForAnotherVideo) {
                for (state in SOMETHING_PLAYING) {
                    assertEquals(
                        "$switch, $state, $request",
                        TapEffect.ENQUEUE_NEXT,
                        policy.onTapChoice(GuardChoice.PLAY_NEXT, request, state),
                    )
                }
                for (state in NOTHING_PLAYING) {
                    assertEquals(
                        "$switch, $state, $request",
                        TapEffect.ENQUEUE,
                        policy.onTapChoice(GuardChoice.PLAY_NEXT, request, state),
                    )
                }
            }
        }
    }

    /** Row 8 (AC-6): *Cancel* is `NONE` for every way; and no choice of any way asks again. */
    @Test
    fun ac6_row8_cancelDoesNothingAndNoChoiceAsksAgainForEveryWay() {
        val choices = listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW, GuardChoice.CANCEL)
        withSwitchOnAndOff { switch ->
            for (state in everyState) {
                for (request in requestsForAnotherVideo + ALL_WAYS.map { requestForCurrentBy(it) }) {
                    assertEquals(
                        "$switch, $state, $request",
                        TapEffect.NONE,
                        policy.onTapChoice(GuardChoice.CANCEL, request, state),
                    )
                    for (choice in choices) {
                        assertNotEquals(
                            "$switch, $choice, $state, $request",
                            TapEffect.ASK,
                            policy.onTapChoice(choice, request, state),
                        )
                    }
                }
            }
        }
    }

    // =============================================================================================
    // Amendment 2: onListPlay, rows 13 and 14
    // =============================================================================================

    /**
     * Rows 13 and 14 as one table (AM-1, AC-10, AC-16): a play button asks exactly when something is
     * playing and the switch is on; the kind of player and the length of the queue do not matter.
     */
    @Test
    fun am1_ac10_ac16_rows13And14_playButtonAsksExactlyWhenSomethingIsPlayingAndTheSwitchIsOn() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (state in everyState + mainFromOneVideoOn + playing(PlayerKind.POPUP, queueSize = 40)) {
                val expected = if (ask && state.somethingPlaying) ListEffect.ASK else ListEffect.PLAY_AS_TODAY
                assertEquals("switch $ask, $state", expected, policy.onListPlay(state))
            }
        }
    }
}
