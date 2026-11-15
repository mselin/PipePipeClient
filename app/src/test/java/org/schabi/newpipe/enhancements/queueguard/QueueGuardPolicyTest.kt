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
 *
 * Since answers A11 and A13 the first row of `onTap` and of `onListPlay` asks for "a queue worth
 * guarding" instead of "something is playing". Tests that expect a dialog run over [WORTH_GUARDING];
 * the last section covers the states in between, [SINGLE_IN_MAIN]. The choice, switch-off and
 * "Show stream details" tests keep running over [SOMETHING_PLAYING], the main player with one video
 * included: those rows still look at "playing" only.
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

    /** Plan section 5, AC-8: "same url on another service still asks", with a queue worth guarding. */
    @Test
    fun ac8_sameUrlOnAnotherServiceIsNotTheCurrentItemAndAsks() {
        for (state in WORTH_GUARDING) {
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
        for (state in WORTH_GUARDING) {
            assertEquals("$state", TapEffect.ASK, policy.onTap(musicModeTap, state))
        }
    }

    /**
     * Answer A1: anything loaded in the player is protected, a single item included, playing or paused.
     * Answer A11 overrides it for the main player, so this holds for the background and the popup player
     * only (plan 3.1, table of `worthGuarding`, second line). The main player with one video is
     * [ac7_tapWithASingleVideoInTheMainPlayerOpensAsToday].
     */
    @Test
    fun ac1_queueOfOneItemIsProtectedToo() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
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

    /** Row "otherwise -> ASK": every state with a queue worth guarding. */
    @Test
    fun ac10_playButtonWhileSomethingIsPlayingAsksInEveryPlayer() {
        for (state in WORTH_GUARDING) {
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
        for (state in WORTH_GUARDING) {
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
    // A queue worth guarding (answers A11 and A13)
    // =============================================================================================
    //
    // SINGLE_IN_MAIN: the main player with at most one video the user put there, alone or with videos
    // the player added by itself. Something is playing, but there is no queue to guard (plan 3.2, the
    // four paragraphs below the table).

    private fun main(queueSize: Int, autoQueued: Int = 0) =
        playing(PlayerKind.MAIN, queueSize = queueSize, autoQueued = autoQueued)

    /**
     * Row "not worth guarding -> OPEN_AS_TODAY": "switch on or off, Music Player Mode or not: no dialog,
     * upstream's own statements run".
     */
    @Test
    fun ac7_tapWithASingleVideoInTheMainPlayerOpensAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in SINGLE_IN_MAIN) {
                for (tap in bothTaps) {
                    assertEquals("$switch, $state, $tap", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
                }
            }
        }
    }

    /** The first row comes before the current-item row, and both give the same: AC-8 holds in every state. */
    @Test
    fun ac8_tapOnTheCurrentItemOpensAsTodayWhetherTheQueueIsWorthGuardingOrNot() {
        withSwitchOnAndOff { switch ->
            for (state in SINGLE_IN_MAIN + WORTH_GUARDING) {
                for (music in listOf(false, true)) {
                    val tap = tapOnCurrent(startsListInBackground = music)
                    assertEquals("$switch, $state, $tap", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
                }
            }
        }
    }

    /** With a single video in the main player not even the same url on another service asks. */
    @Test
    fun ac7_ac8_sameUrlOnAnotherServiceOpensAsTodayWithASingleVideoInTheMainPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SINGLE_IN_MAIN) {
                val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
                assertEquals("$switch, $state", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, state))
            }
        }
    }

    /**
     * Row "askBeforeReplacing() -> ASK" for the main player: two videos the user put there make a queue,
     * with and without auto-enqueued ones (2/0, 3/1, 5/3).
     */
    @Test
    fun ac1_mainPlayerWithTwoVideosOfTheUsersAsksWithAndWithoutAutoQueuedOnes() {
        for (state in listOf(main(2), main(3, autoQueued = 1), main(5, autoQueued = 3))) {
            for (tap in bothTaps) {
                assertEquals("$state, $tap", TapEffect.ASK, policy.onTap(tap, state))
            }
        }
    }

    /** Row "askBeforeReplacing() -> ASK" over the whole fixture, both kinds of tap. */
    @Test
    fun ac1_tapAsksForEveryQueueWorthGuarding() {
        for (state in WORTH_GUARDING) {
            for (tap in bothTaps) {
                assertEquals("$state, $tap", TapEffect.ASK, policy.onTap(tap, state))
            }
        }
    }

    /** Plan 3.2: "The moment the user has enqueued a second video the rows below the first apply". */
    @Test
    fun ac1_ac7_theSecondVideoOfTheUsersMakesTheTapAsk() {
        assertEquals("1/0", TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, main(1)))
        assertEquals("2/0", TapEffect.ASK, policy.onTap(plainTap, main(2)))

        assertEquals("2/1", TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, main(2, autoQueued = 1)))
        assertEquals("3/1", TapEffect.ASK, policy.onTap(plainTap, main(3, autoQueued = 1)))

        assertEquals("3/2", TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, main(3, autoQueued = 2)))
        assertEquals("4/2", TapEffect.ASK, policy.onTap(plainTap, main(4, autoQueued = 2)))

        assertEquals("2/2, no video of the user's", TapEffect.OPEN_AS_TODAY, policy.onTap(plainTap, main(2, 2)))
    }

    /** Answer A13: however long playback has moved on by itself, the tap does not ask (plan section 8). */
    @Test
    fun ac7_videosThePlayerAddedByItselfNeverMakeTheTapAsk() {
        withSwitchOnAndOff { switch ->
            for (autoQueued in listOf(1, 2, 10, 200)) {
                val watchedOneAndLetItRun = main(autoQueued + 1, autoQueued)
                assertEquals(
                    "$switch, $watchedOneAndLetItRun",
                    TapEffect.OPEN_AS_TODAY,
                    policy.onTap(plainTap, watchedOneAndLetItRun),
                )
                assertEquals(
                    "$switch, play button, $watchedOneAndLetItRun",
                    ListEffect.PLAY_AS_TODAY,
                    policy.onListPlay(watchedOneAndLetItRun),
                )
            }
        }
    }

    /** In the background and the popup player the auto-enqueued videos change nothing: the tap asks. */
    @Test
    fun ac1_ac10_backgroundAndPopupPlayerAskWhateverWasAutoQueued() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for ((queueSize, autoQueued) in listOf(1 to 1, 2 to 1, 3 to 2, 3 to 3)) {
                val state = playing(kind, queueSize = queueSize, autoQueued = autoQueued)
                assertEquals("tap, $state", TapEffect.ASK, policy.onTap(plainTap, state))
                assertEquals("play button, $state", ListEffect.ASK, policy.onListPlay(state))
            }
        }
    }

    /** Row "not worth guarding, or switch off -> PLAY_AS_TODAY", first half, for the main player. */
    @Test
    fun ac14_playButtonWithASingleVideoInTheMainPlayerPlaysAsToday() {
        withSwitchOnAndOff { switch ->
            for (state in SINGLE_IN_MAIN) {
                assertEquals("$switch, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
            }
        }
    }

    /** Row "otherwise -> ASK" for the main player with two videos of the user's, and the step up to it. */
    @Test
    fun ac10_playButtonAsksInTheMainPlayerFromTheSecondVideoOfTheUsersOn() {
        for (state in listOf(main(2), main(3, autoQueued = 1), main(5, autoQueued = 3), main(3))) {
            assertEquals("$state", ListEffect.ASK, policy.onListPlay(state))
        }
        assertEquals("1/0", ListEffect.PLAY_AS_TODAY, policy.onListPlay(main(1)))
        assertEquals("2/1", ListEffect.PLAY_AS_TODAY, policy.onListPlay(main(2, autoQueued = 1)))
        assertEquals("3/2", ListEffect.PLAY_AS_TODAY, policy.onListPlay(main(3, autoQueued = 2)))
    }

    /**
     * AC-16 where nothing is worth guarding: with the switch off a single video in the main player is
     * neither asked about nor played in another player. Plan 3.2: "With the switch off this equals
     * *playNow* for `MAIN`".
     */
    @Test
    fun ac16_switchOff_singleVideoInTheMainPlayerNeitherAsksNorStartsAnotherPlayer() {
        settings.ask = false
        val taps = bothTaps + tapOnCurrent() + tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
        for (state in SINGLE_IN_MAIN) {
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
        for (state in WORTH_GUARDING) {
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
     * Plan 3.2: "What a choice does is decided by `playing`, as before." If the main player is down to
     * one video when the user chooses, the choice is still carried out: `ENQUEUE`, `ENQUEUE_NEXT`, and
     * *playNow* by kind, which is `OPEN_AS_TODAY` for the main player.
     */
    @Test
    fun ac3_ac4_ac5_choiceIsCarriedOutWithASingleVideoInTheMainPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SINGLE_IN_MAIN) {
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

    /** The choice rows do not look at the auto-enqueued videos in any player. */
    @Test
    fun ac3_ac4_ac5_choiceIsCarriedOutForEveryQueueWorthGuarding() {
        withSwitchOnAndOff { switch ->
            for (state in WORTH_GUARDING) {
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
            for (state in SINGLE_IN_MAIN) {
                val case = "$switch, $state"
                assertEquals(case, ListEffect.ENQUEUE_ALL, policy.onListChoice(GuardChoice.ADD_TO_QUEUE, state))
                assertEquals(case, ListEffect.ENQUEUE_ALL_NEXT, policy.onListChoice(GuardChoice.PLAY_NEXT, state))
                assertEquals(case, ListEffect.PLAY_AS_TODAY, policy.onListChoice(GuardChoice.PLAY_NOW, state))
                assertEquals(case, ListEffect.NONE, policy.onListChoice(GuardChoice.CANCEL, state))
            }
        }
    }

    /** `offersStreamDetails` stays on "playing": `false` for the main player, whatever its queue. */
    @Test
    fun ac17_streamDetailsAreNotOfferedWithASingleVideoInTheMainPlayer() {
        withSwitchOnAndOff { switch ->
            for (state in SINGLE_IN_MAIN) {
                assertFalse("$switch, $state", policy.offersStreamDetails(state))
            }
            assertFalse("$switch, 3/1", policy.offersStreamDetails(main(3, autoQueued = 1)))
        }
    }

    /** `offersStreamDetails` does not look at `autoQueuedCount`: background and popup player always offer it. */
    @Test
    fun ac17_streamDetailsAreOfferedInTheBackgroundAndPopupPlayerWhateverWasAutoQueued() {
        withSwitchOnAndOff { switch ->
            for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
                for ((queueSize, autoQueued) in listOf(2 to 1, 3 to 2, 1 to 1)) {
                    val state = playing(kind, queueSize = queueSize, autoQueued = autoQueued)
                    assertTrue("$switch, $state", policy.offersStreamDetails(state))
                }
            }
        }
    }
}
