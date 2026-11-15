package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.ListEffect
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.TapEffect
import org.schabi.newpipe.enhancements.queueguard.TapRequest

/**
 * The rule tables of plan 4.2 for the combined policy
 * `OpenActionPolicy(QueueGuardPolicy(g), g, settings)` with `g = GuardSettings { ask }` ("P" in plan section
 * 6), one test per row, named after the row and the acceptance criterion in its last column. (Rows 14 to 16 name only
 * criteria of queue-guard there; their tests carry AC-4, see row 14 and questions.md, Q7.)
 *
 * Every expected value is the table's. Where a row says "any" in a column, the test runs through every
 * value of that column: both positions of the ask switch, the three default actions, the three "Start
 * playback in" values, Music Player Mode on and off, and every player state of the row's "playing" value
 * ([SOMETHING_PLAYING]: each player with one item and with several; [NOTHING_PLAYING]: no player, and each
 * player without a queue). Where a row depends on "worth guarding", or a test is about a single video in
 * the main player (spec AC-3, AC-4), the states are [WORTH_GUARDING] and [SINGLE_IN_MAIN], which also hold
 * the queues with auto-enqueued videos. A failure message starts with the row and names the combination.
 *
 * One policy object serves a whole test while the settings change under it, so an implementation that
 * caches a setting fails here too (plan 4.1: "Both are read on every call, never cached").
 */
class OpenActionPolicyTest {

    private val combined = CombinedPolicy()
    private val policy: GuardPolicy = combined.policy

    private val on = listOf(true)
    private val off = listOf(false)

    /** A video other than the current item, tapped in a list with Music Player Mode off, then on. */
    private val anotherVideoInAnyList =
        listOf(tapOn(startsListInBackground = false), tapOn(startsListInBackground = true))

    /** The current item, tapped in a list with Music Player Mode off, then on. */
    private val currentItemInAnyList =
        listOf(tapOnCurrent(startsListInBackground = false), tapOnCurrent(startsListInBackground = true))

    /** Both urls of the fixtures, each with Music Player Mode off and on. */
    private val anyTap = tapsOnBothUrls(musicPlayerMode = false) + tapsOnBothUrls(musicPlayerMode = true)

    private val anyState = NOTHING_PLAYING + SOMETHING_PLAYING

    private fun expectOnTap(
        row: String,
        expected: TapEffect,
        settings: List<Combination>,
        states: List<PlayerState>,
        taps: List<TapRequest>,
    ) {
        combined.forEach(settings) { setting ->
            for (state in states) {
                for (tap in taps) {
                    assertEquals("$row: onTap under $setting, $state, $tap", expected, policy.onTap(tap, state))
                }
            }
        }
    }

    private fun expectOnTapChoice(
        row: String,
        choice: GuardChoice,
        expected: TapEffect,
        settings: List<Combination>,
        states: List<PlayerState>,
        taps: List<TapRequest>,
    ) {
        combined.forEach(settings) { setting ->
            for (state in states) {
                for (tap in taps) {
                    assertEquals(
                        "$row: onTapChoice($choice) under $setting, $state, $tap",
                        expected,
                        policy.onTapChoice(choice, tap, state),
                    )
                }
            }
        }
    }

    private fun expectStreamDetails(
        row: String,
        expected: Boolean,
        settings: List<Combination>,
        states: List<PlayerState>,
    ) {
        combined.forEach(settings) { setting ->
            for (state in states) {
                assertEquals(
                    "$row: offersStreamDetails under $setting, $state",
                    expected,
                    policy.offersStreamDetails(state),
                )
            }
        }
    }

    // =============================================================================================
    // onTap while nothing is playing: rows 1-4
    // =============================================================================================

    /**
     * Row 1. AC-6: in a local playlist with Music Player Mode on, a tap while nothing is playing "does
     * what it does there today; Start playback in does not apply".
     */
    @Test
    fun ac6_row1_nothingPlaying_musicPlayerModeTapOpensAsTodayWhateverStartPlaybackInSays() {
        expectOnTap(
            row = "row 1",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = true),
        )
    }

    /** Row 2. AC-5, *Video page*: "what a video tap does today", "whatever the default action is". */
    @Test
    fun ac5_ac14_row2_nothingPlaying_videoPageOpensAsTodayWhateverTheDefaultActionIs() {
        expectOnTap(
            row = "row 2",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(startIns = listOf(StartPlaybackIn.VIDEO_PAGE)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /** Row 3. AC-5, *Background player*: "the tapped video alone starts in the background player". */
    @Test
    fun ac5_row3_nothingPlaying_backgroundPlayerStartsInTheBackgroundPlayerWhateverTheDefaultActionIs() {
        expectOnTap(
            row = "row 3",
            expected = TapEffect.START_BACKGROUND,
            settings = combinations(startIns = listOf(StartPlaybackIn.BACKGROUND_PLAYER)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /** Row 4. AC-5, *Popup player*: "the tapped video alone starts in the popup player". */
    @Test
    fun ac5_row4_nothingPlaying_popupPlayerStartsInThePopupPlayerWhateverTheDefaultActionIs() {
        expectOnTap(
            row = "row 4",
            expected = TapEffect.START_POPUP,
            settings = combinations(startIns = listOf(StartPlaybackIn.POPUP_PLAYER)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    // =============================================================================================
    // onTap while something is playing: rows 5-12
    // =============================================================================================

    /** Row 5. AC-12: "A video tap on the current item behaves as today, whatever the settings." */
    @Test
    fun ac12_row5_tapOnTheCurrentItemOpensAsTodayWhateverTheSettings() {
        expectOnTap(
            row = "row 5",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(),
            states = SOMETHING_PLAYING,
            taps = currentItemInAnyList,
        )
    }

    /**
     * Row 5 on a single video in the main player. AC-12 holds there under all 18 combinations: with ask
     * off and *Add to queue* or *Play next*, a tap on the playing video is not enqueued behind itself.
     */
    @Test
    fun ac12_row5_tapOnTheCurrentItemOpensAsTodayOnASingleVideoInTheMainPlayerWhateverTheSettings() {
        val all = combinations()
        assertEquals("combinations of the three settings", 18, all.size)
        expectOnTap(
            row = "row 5 (single video in the main player)",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = all,
            states = SINGLE_IN_MAIN,
            taps = currentItemInAnyList,
        )
    }

    /**
     * Row 6. AC-4: with ask on "the default action is not used: with a queue worth guarding the dialog
     * opens".
     */
    @Test
    fun ac4_row6_askOn_tapAsksForAllNineCombinationsOfTheTwoSettings() {
        val nine = combinations(asks = on)
        assertEquals("combinations of the two settings", 9, nine.size)
        expectOnTap(
            row = "row 6",
            expected = TapEffect.ASK,
            settings = nine,
            states = WORTH_GUARDING,
            taps = anotherVideoInAnyList,
        )
    }

    /**
     * Row 6b. AC-4: with ask on "the default action is not used: ... with a single video in the main
     * player the tap opens the video as today" (answer A8), also with *Add to queue* or *Play next*
     * selected, in a list with Music Player Mode on or off.
     */
    @Test
    fun ac4_row6b_askOn_singleVideoInTheMainPlayerOpensAsTodayForAllNineCombinationsOfTheTwoSettings() {
        val nine = combinations(asks = on)
        assertEquals("combinations of the two settings", 9, nine.size)
        expectOnTap(
            row = "row 6b",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = nine,
            states = SINGLE_IN_MAIN,
            taps = anotherVideoInAnyList,
        )
    }

    /**
     * Rows 5, 6 and 6b together. AC-4: with ask on "a video tap behaves exactly as queue-guard specifies":
     * in every state in which something is playing, for every tap, the answer is the one of queue-guard's
     * policy alone on the same switch (plan section 6, AC-4: "equal to `QueueGuardPolicy` alone in both").
     */
    @Test
    fun ac4_rows6and6b_askOn_tapGetsQueueGuardsAnswerInEveryPlayingState() {
        val nine = combinations(asks = on)
        assertEquals("combinations of the two settings", 9, nine.size)
        combined.forEach(nine) { setting ->
            for (state in SOMETHING_PLAYING + WORTH_GUARDING + SINGLE_IN_MAIN) {
                for (tap in anyTap) {
                    assertEquals(
                        "rows 5, 6, 6b: onTap under $setting, $state, $tap",
                        combined.queueGuardAlone.onTap(tap, state),
                        policy.onTap(tap, state),
                    )
                }
            }
        }
    }

    /** Row 7. AC-3 with *Add to queue*: the result of the dialog choice of that name (QG AC-3). */
    @Test
    fun ac3_row7_askOff_addToQueueEnqueuesInEveryPlayer() {
        expectOnTap(
            row = "row 7",
            expected = TapEffect.ENQUEUE,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = SOMETHING_PLAYING,
            taps = anotherVideoInAnyList,
        )
    }

    /** Row 8. AC-3 with *Play next*: the result of the dialog choice of that name (QG AC-4). */
    @Test
    fun ac3_row8_askOff_playNextEnqueuesNextInEveryPlayer() {
        expectOnTap(
            row = "row 8",
            expected = TapEffect.ENQUEUE_NEXT,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            states = SOMETHING_PLAYING,
            taps = anotherVideoInAnyList,
        )
    }

    /**
     * Row 9. AC-3 with *Play now* in a local playlist with Music Player Mode on: queue-guard's Play now
     * there is "what a video tap does there today, whichever player is in use" (QG spec, Terms).
     */
    @Test
    fun ac3_row9_askOff_playNowWithMusicPlayerModeOpensAsTodayInEveryPlayer() {
        expectOnTap(
            row = "row 9",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = SOMETHING_PLAYING,
            taps = listOf(tapOn(startsListInBackground = true)),
        )
    }

    /** Row 10. AC-3 with *Play now*; AC-2: playback "stays in the player that is in use", here the main one. */
    @Test
    fun ac2_ac3_row10_askOff_playNowInTheMainPlayerOpensAsTodayWhateverStartPlaybackInSays() {
        expectOnTap(
            row = "row 10",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.MAIN),
            taps = listOf(tapOn(startsListInBackground = false)),
        )
    }

    /** Row 11. As row 10 for the background player. */
    @Test
    fun ac2_ac3_row11_askOff_playNowInTheBackgroundPlayerStartsThereWhateverStartPlaybackInSays() {
        expectOnTap(
            row = "row 11",
            expected = TapEffect.START_BACKGROUND,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.BACKGROUND),
            taps = listOf(tapOn(startsListInBackground = false)),
        )
    }

    /** Row 12. As row 10 for the popup player. */
    @Test
    fun ac2_ac3_row12_askOff_playNowInThePopupPlayerStartsThereWhateverStartPlaybackInSays() {
        expectOnTap(
            row = "row 12",
            expected = TapEffect.START_POPUP,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.POPUP),
            taps = listOf(tapOn(startsListInBackground = false)),
        )
    }

    /**
     * Row 7 on a single video in the main player. AC-3: with ask off the default action is carried out
     * "also on a single video in the main player (this is how a queue is started by tapping)". The single
     * video may have auto-enqueued ones around it; "Start playback in" and Music Player Mode do not matter.
     */
    @Test
    fun ac3_row7_askOff_addToQueueEnqueuesOnASingleVideoInTheMainPlayer() {
        expectOnTap(
            row = "row 7 (single video in the main player)",
            expected = TapEffect.ENQUEUE,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = SINGLE_IN_MAIN,
            taps = anotherVideoInAnyList,
        )
    }

    /** Row 8 on a single video in the main player, as row 7 there. */
    @Test
    fun ac3_row8_askOff_playNextEnqueuesNextOnASingleVideoInTheMainPlayer() {
        expectOnTap(
            row = "row 8 (single video in the main player)",
            expected = TapEffect.ENQUEUE_NEXT,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            states = SINGLE_IN_MAIN,
            taps = anotherVideoInAnyList,
        )
    }

    /**
     * Rows 9 and 10 on a single video in the main player. AC-3 with *Play now*: queue-guard's Play now in
     * the main player is "what a video tap does today", with Music Player Mode (row 9) and without (row 10).
     */
    @Test
    fun ac3_rows9and10_askOff_playNowOnASingleVideoInTheMainPlayerOpensAsToday() {
        expectOnTap(
            row = "rows 9, 10 (single video in the main player)",
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = SINGLE_IN_MAIN,
            taps = anotherVideoInAnyList,
        )
    }

    /**
     * Rows 7 to 12 over the queues worth guarding, those with auto-enqueued videos included (the background
     * player with 3/2, the main player with 3/1 and 5/3): with ask off they get the default action like
     * any other queue. `autoQueuedCount` has no influence on the result (plan 4.2).
     */
    @Test
    fun ac3_rows7to12_askOff_queuesWithAutoEnqueuedVideosGetTheDefaultAction() {
        expectOnTap(
            "row 7",
            TapEffect.ENQUEUE,
            combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            WORTH_GUARDING,
            anotherVideoInAnyList,
        )
        expectOnTap(
            "row 8",
            TapEffect.ENQUEUE_NEXT,
            combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            WORTH_GUARDING,
            anotherVideoInAnyList,
        )
        val playNow = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW))
        val withMusicPlayerMode = listOf(tapOn(startsListInBackground = true))
        val withoutMusicPlayerMode = listOf(tapOn(startsListInBackground = false))
        expectOnTap("row 9", TapEffect.OPEN_AS_TODAY, playNow, WORTH_GUARDING, withMusicPlayerMode)
        val playNowIn = mapOf(
            PlayerKind.MAIN to TapEffect.OPEN_AS_TODAY,
            PlayerKind.BACKGROUND to TapEffect.START_BACKGROUND,
            PlayerKind.POPUP to TapEffect.START_POPUP,
        )
        for ((kind, expected) in playNowIn) {
            val states = WORTH_GUARDING.filter { it.kind == kind }
            assertEquals("states of WORTH_GUARDING in the $kind player: $states", true, states.isNotEmpty())
            expectOnTap("rows 10 to 12 ($kind)", expected, playNow, states, withoutMusicPlayerMode)
        }
    }

    /**
     * Rows 6 to 8 and 11 at the edge of row 5's "current" (QG plan 3.1: same url and same service id): the
     * url of the current item on another service is another video. It is asked about (with a queue worth
     * guarding, row 6), or gets the default action.
     */
    @Test
    fun ac12_ac3_rows6to8and11_sameUrlOnAnotherServiceIsNotTheCurrentItem() {
        val tap = listOf(tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE))
        expectOnTap("row 6", TapEffect.ASK, combinations(asks = on), WORTH_GUARDING, tap)
        expectOnTap(
            "row 7",
            TapEffect.ENQUEUE,
            combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            SOMETHING_PLAYING,
            tap,
        )
        expectOnTap(
            "row 8",
            TapEffect.ENQUEUE_NEXT,
            combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            SOMETHING_PLAYING,
            tap,
        )
        expectOnTap(
            "row 11",
            TapEffect.START_BACKGROUND,
            combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            playingIn(PlayerKind.BACKGROUND),
            tap,
        )
    }

    // =============================================================================================
    // onTapChoice: rows 13-19 ("ask, default action and current have no influence")
    // =============================================================================================

    /** Row 13. AC-9: *Cancel* changes nothing (QG AC-6), whatever the settings and the player. */
    @Test
    fun ac9_row13_cancelDoesNothingWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 13",
            choice = GuardChoice.CANCEL,
            expected = TapEffect.NONE,
            settings = combinations(),
            states = anyState,
            taps = anyTap,
        )
    }

    /**
     * Row 14: *Add to queue* enqueues, also after the player was closed, where "Start playback in" does
     * not redirect it.
     *
     * Assumption (questions.md, Q7): the table's last column names only QG AC-3 and QG AC-9 for this row,
     * and plan section 6 lists rows 14 to 16 under no criterion of this feature. Their tests are named
     * after AC-4: the dialog is queue-guard's "as before" and "the default action is not used". The
     * expected values are the table's whatever the answer is.
     */
    @Test
    fun ac4_row14_addToQueueInTheDialogEnqueuesWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 14",
            choice = GuardChoice.ADD_TO_QUEUE,
            expected = TapEffect.ENQUEUE,
            settings = combinations(),
            states = anyState,
            taps = anyTap,
        )
    }

    /** Row 15 (QG AC-4; named after AC-4 by the assumption at row 14, Q7). */
    @Test
    fun ac4_row15_playNextInTheDialogEnqueuesNextWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 15",
            choice = GuardChoice.PLAY_NEXT,
            expected = TapEffect.ENQUEUE_NEXT,
            settings = combinations(),
            states = SOMETHING_PLAYING,
            taps = anyTap,
        )
    }

    /**
     * Row 16 (QG AC-9; named after AC-4 by the assumption at row 14, Q7). After the player was closed
     * *Play next* does what "Enqueue" does, and "Start playback in" does not redirect it: AC-13 is about
     * *Play now* only.
     */
    @Test
    fun ac4_row16_playNextInTheDialogAfterThePlayerWasClosedEnqueuesWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 16",
            choice = GuardChoice.PLAY_NEXT,
            expected = TapEffect.ENQUEUE,
            settings = combinations(),
            states = NOTHING_PLAYING,
            taps = anyTap,
        )
    }

    /** Row 17. AC-2 (QG AC-5): *Play now* in a list with Music Player Mode on is today's tap there. */
    @Test
    fun ac2_row17_playNowInTheDialogWithMusicPlayerModeOpensAsTodayWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 17",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(),
            states = SOMETHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = true),
        )
    }

    /** Row 18, kind `MAIN`. AC-2: "playback stays in the player that is in use (queue-guard's Play now)". */
    @Test
    fun ac2_row18_playNowInTheDialogInTheMainPlayerOpensAsTodayWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 18 (MAIN)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(),
            states = playingIn(PlayerKind.MAIN),
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /** Row 18, kind `BACKGROUND`. */
    @Test
    fun ac2_row18_playNowInTheDialogInTheBackgroundPlayerStartsThereWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 18 (BACKGROUND)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.START_BACKGROUND,
            settings = combinations(),
            states = playingIn(PlayerKind.BACKGROUND),
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /** Row 18, kind `POPUP`. */
    @Test
    fun ac2_row18_playNowInTheDialogInThePopupPlayerStartsThereWhateverTheSettings() {
        expectOnTapChoice(
            row = "row 18 (POPUP)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.START_POPUP,
            settings = combinations(),
            states = playingIn(PlayerKind.POPUP),
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /**
     * Row 19 through row 1. AC-13: if the player was closed while the dialog was open, *Play now* does
     * what a tap does while nothing is playing, which is "AC-6 in a local playlist with Music Player Mode
     * on": today's tap there, whatever "Start playback in" says.
     */
    @Test
    fun ac13_ac6_row19_playNowAfterThePlayerWasClosed_musicPlayerModeOpensAsTodayWhateverStartPlaybackInSays() {
        expectOnTapChoice(
            row = "row 19 (as row 1)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = true),
        )
    }

    /** Row 19 through row 2. AC-13: "AC-5 everywhere else", here with *Video page*. */
    @Test
    fun ac13_row19_playNowAfterThePlayerWasClosed_videoPageOpensAsToday() {
        expectOnTapChoice(
            row = "row 19 (as row 2)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.OPEN_AS_TODAY,
            settings = combinations(startIns = listOf(StartPlaybackIn.VIDEO_PAGE)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /** Row 19 through row 3. AC-13 with *Background player*. */
    @Test
    fun ac13_row19_playNowAfterThePlayerWasClosed_backgroundPlayerStartsInTheBackgroundPlayer() {
        expectOnTapChoice(
            row = "row 19 (as row 3)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.START_BACKGROUND,
            settings = combinations(startIns = listOf(StartPlaybackIn.BACKGROUND_PLAYER)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /** Row 19 through row 4. AC-13 with *Popup player*. */
    @Test
    fun ac13_row19_playNowAfterThePlayerWasClosed_popupPlayerStartsInThePopupPlayer() {
        expectOnTapChoice(
            row = "row 19 (as row 4)",
            choice = GuardChoice.PLAY_NOW,
            expected = TapEffect.START_POPUP,
            settings = combinations(startIns = listOf(StartPlaybackIn.POPUP_PLAYER)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
        )
    }

    /**
     * Rows 13 to 15, 17 and 18 over a single video in the main player. A dialog only opens for a queue
     * worth guarding (row 6), but the main player can be down to a single video when the user chooses.
     * Plan 4.2: the rows "still apply, on "playing" ...: an explicit choice is never dropped or turned into
     * another one". AC-4: the dialog is queue-guard's, under all 18 combinations. (Row 16 is about nothing
     * playing, which a single video is not.)
     */
    @Test
    fun ac4_rows13to18_aDialogChoiceOnASingleVideoInTheMainPlayerIsQueueGuards() {
        val all = combinations()
        assertEquals("combinations of the three settings", 18, all.size)
        val expected = mapOf(
            GuardChoice.CANCEL to ("row 13" to TapEffect.NONE),
            GuardChoice.ADD_TO_QUEUE to ("row 14" to TapEffect.ENQUEUE),
            GuardChoice.PLAY_NEXT to ("row 15" to TapEffect.ENQUEUE_NEXT),
            GuardChoice.PLAY_NOW to ("rows 17, 18 (MAIN)" to TapEffect.OPEN_AS_TODAY),
        )
        for ((choice, rowAndEffect) in expected) {
            val (row, effect) = rowAndEffect
            expectOnTapChoice(
                row = "$row (single video in the main player)",
                choice = choice,
                expected = effect,
                settings = all,
                states = SINGLE_IN_MAIN,
                taps = anyTap,
            )
        }
    }

    // =============================================================================================
    // offersStreamDetails: rows 20-23
    // =============================================================================================

    /** Row 20. Queue-guard's AC-17, which AC-11 keeps ("in addition to queue-guard's AC-17"). */
    @Test
    fun ac11_row20_backgroundOrPopupPlayerInUse_streamDetailsIsOfferedWhateverTheSettings() {
        expectStreamDetails(
            row = "row 20",
            expected = true,
            settings = combinations(),
            states = playingIn(PlayerKind.BACKGROUND) + playingIn(PlayerKind.POPUP),
        )
    }

    /**
     * Row 21 with *Add to queue*. AC-11: "while something is playing in any player, Ask before replacing
     * the queue is off and the default action is Add to queue or Play next" (answer A3: the main player
     * included).
     */
    @Test
    fun ac11_row21_mainPlayerInUse_askOff_addToQueueOffersStreamDetails() {
        expectStreamDetails(
            row = "row 21 (ADD_TO_QUEUE)",
            expected = true,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = playingIn(PlayerKind.MAIN),
        )
    }

    /** Row 21 with *Play next*. */
    @Test
    fun ac11_row21_mainPlayerInUse_askOff_playNextOffersStreamDetails() {
        expectStreamDetails(
            row = "row 21 (PLAY_NEXT)",
            expected = true,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            states = playingIn(PlayerKind.MAIN),
        )
    }

    /** Row 21b, ask on: the tap asks, so the menu needs no extra way to the page (QG AC-17: main player, no entry). */
    @Test
    fun ac11_row21b_mainPlayerInUse_askOn_streamDetailsIsNotOfferedWhateverTheDefaultAction() {
        expectStreamDetails(
            row = "row 21b (ask on)",
            expected = false,
            settings = combinations(asks = on),
            states = playingIn(PlayerKind.MAIN),
        )
    }

    /** Row 21b, ask off with *Play now*: the tap itself opens the page. */
    @Test
    fun ac11_ac14_row21b_mainPlayerInUse_askOff_playNowDoesNotOfferStreamDetails() {
        expectStreamDetails(
            row = "row 21b (ask off, PLAY_NOW)",
            expected = false,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.MAIN),
        )
    }

    /**
     * Row 21 on a single video in the main player ("`MAIN`, a single video or a queue"). AC-11: "while
     * something is playing in any player, Ask before replacing the queue is off and the default action is
     * Add to queue or Play next": the tap enqueues there (rows 7 and 8), so the menu is the way to the page.
     */
    @Test
    fun ac11_row21_singleVideoInTheMainPlayer_askOff_addToQueueOrPlayNextOffersStreamDetails() {
        expectStreamDetails(
            row = "row 21 (single video in the main player)",
            expected = true,
            settings = combinations(
                asks = off,
                actions = listOf(DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.PLAY_NEXT),
            ),
            states = SINGLE_IN_MAIN,
        )
    }

    /**
     * Row 21b on a single video in the main player. AC-11 offers the entry "whenever a video tap would
     * neither open the video's page nor ask". With ask on the tap opens the page there whatever the
     * default action is (row 6b, AC-4), and with ask off and *Play now* it does too (rows 9 and 10): no
     * entry, as with queue-guard alone (QG AC-17: only the background and the popup player).
     */
    @Test
    fun ac11_row21b_singleVideoInTheMainPlayer_streamDetailsIsNotOfferedWithAskOnOrPlayNow() {
        val askOn = combinations(asks = on)
        assertEquals("combinations of the two settings", 9, askOn.size)
        expectStreamDetails(
            row = "row 21b (single video in the main player, ask on)",
            expected = false,
            settings = askOn,
            states = SINGLE_IN_MAIN,
        )
        expectStreamDetails(
            row = "row 21b (single video in the main player, ask off, PLAY_NOW)",
            expected = false,
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = SINGLE_IN_MAIN,
        )
    }

    /**
     * Row 22 with *Background player*. AC-11: "while nothing is playing and Start playback in is Background
     * player or Popup player".
     */
    @Test
    fun ac11_row22_nothingPlaying_backgroundPlayerOffersStreamDetails() {
        expectStreamDetails(
            row = "row 22 (BACKGROUND_PLAYER)",
            expected = true,
            settings = combinations(startIns = listOf(StartPlaybackIn.BACKGROUND_PLAYER)),
            states = NOTHING_PLAYING,
        )
    }

    /** Row 22 with *Popup player*. */
    @Test
    fun ac11_row22_nothingPlaying_popupPlayerOffersStreamDetails() {
        expectStreamDetails(
            row = "row 22 (POPUP_PLAYER)",
            expected = true,
            settings = combinations(startIns = listOf(StartPlaybackIn.POPUP_PLAYER)),
            states = NOTHING_PLAYING,
        )
    }

    /** Row 23. With *Video page* a tap opens the page while nothing is playing: no entry, as today. */
    @Test
    fun ac11_ac14_row23_nothingPlaying_videoPageDoesNotOfferStreamDetails() {
        expectStreamDetails(
            row = "row 23",
            expected = false,
            settings = combinations(startIns = listOf(StartPlaybackIn.VIDEO_PAGE)),
            states = NOTHING_PLAYING,
        )
    }

    // =============================================================================================
    // AC-2: "Start playback in" applies only while nothing is playing
    // =============================================================================================

    /** The three values of "Start playback in", each with the answer [ask] gets under it. */
    private fun <T> underEveryStartIn(
        ask: Boolean,
        action: DefaultTapAction,
        answer: () -> T,
    ): Map<StartPlaybackIn, T> =
        ALL_START_INS.associateWith { startIn ->
            combined.set(Combination(ask, action, startIn))
            answer()
        }

    /** Plan section 6, AC-2: "while playing, `onTap` ... give[s] the same result for all three values". */
    @Test
    fun ac2_whileSomethingIsPlaying_startPlaybackInDoesNotChangeWhatATapDoes() {
        for (ask in ASK_ON_AND_OFF) {
            for (action in ALL_ACTIONS) {
                for (state in SOMETHING_PLAYING) {
                    for (tap in anyTap) {
                        val answers = underEveryStartIn(ask, action) { policy.onTap(tap, state) }
                        assertEquals(
                            "onTap must not depend on \"Start playback in\" " +
                                "(ask=$ask, $action, $state, $tap): $answers",
                            1,
                            answers.values.toSet().size,
                        )
                    }
                }
            }
        }
    }

    /** Plan section 6, AC-2: "... and `onTapChoice` give the same result for all three values". */
    @Test
    fun ac2_whileSomethingIsPlaying_startPlaybackInDoesNotChangeWhatADialogChoiceDoes() {
        for (ask in ASK_ON_AND_OFF) {
            for (action in ALL_ACTIONS) {
                for (state in SOMETHING_PLAYING) {
                    for (tap in anyTap) {
                        for (choice in ALL_CHOICES) {
                            val answers = underEveryStartIn(ask, action) { policy.onTapChoice(choice, tap, state) }
                            assertEquals(
                                "onTapChoice($choice) must not depend on \"Start playback in\" " +
                                    "(ask=$ask, $action, $state, $tap): $answers",
                                1,
                                answers.values.toSet().size,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Rows 20, 21 and 21b say "any" for "Start playback in": no influence on the menu entry while playing. */
    @Test
    fun ac2_ac11_whileSomethingIsPlaying_startPlaybackInDoesNotChangeTheStreamDetailsOffer() {
        for (ask in ASK_ON_AND_OFF) {
            for (action in ALL_ACTIONS) {
                for (state in SOMETHING_PLAYING) {
                    val answers = underEveryStartIn(ask, action) { policy.offersStreamDetails(state) }
                    assertEquals(
                        "offersStreamDetails must not depend on \"Start playback in\" " +
                            "(ask=$ask, $action, $state): $answers",
                        1,
                        answers.values.toSet().size,
                    )
                }
            }
        }
    }

    // =============================================================================================
    // AC-1, AC-2: a setting changed in Settings applies to the next tap
    // =============================================================================================

    /**
     * Plan 4.1: "Both are read on every call, never cached." The same policy object lives as long as the
     * app (plan 2, G1), so a value chosen in Settings, or remembered in the dialog (AC-8), has to be seen
     * by the very next call.
     */
    @Test
    fun ac1_ac2_aChangedSettingAppliesToTheNextCallOfTheSamePolicy() {
        val inBackground = playing(PlayerKind.BACKGROUND)
        val inMain = playing(PlayerKind.MAIN)
        val tap = tapOn()
        combined.askSwitch.ask = false

        combined.settings.action = DefaultTapAction.PLAY_NOW
        assertEquals("PLAY_NOW", TapEffect.START_BACKGROUND, policy.onTap(tap, inBackground))
        assertEquals("PLAY_NOW", false, policy.offersStreamDetails(inMain))
        combined.settings.action = DefaultTapAction.ADD_TO_QUEUE
        assertEquals("then ADD_TO_QUEUE", TapEffect.ENQUEUE, policy.onTap(tap, inBackground))
        assertEquals("then ADD_TO_QUEUE", true, policy.offersStreamDetails(inMain))
        combined.settings.action = DefaultTapAction.PLAY_NEXT
        assertEquals("then PLAY_NEXT", TapEffect.ENQUEUE_NEXT, policy.onTap(tap, inBackground))
        combined.settings.action = DefaultTapAction.PLAY_NOW
        assertEquals("then PLAY_NOW again", TapEffect.START_BACKGROUND, policy.onTap(tap, inBackground))

        combined.settings.startIn = StartPlaybackIn.VIDEO_PAGE
        assertEquals("VIDEO_PAGE", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, PlayerState.IDLE))
        assertEquals("VIDEO_PAGE", false, policy.offersStreamDetails(PlayerState.IDLE))
        combined.settings.startIn = StartPlaybackIn.BACKGROUND_PLAYER
        assertEquals("then BACKGROUND_PLAYER", TapEffect.START_BACKGROUND, policy.onTap(tap, PlayerState.IDLE))
        assertEquals("then BACKGROUND_PLAYER", true, policy.offersStreamDetails(PlayerState.IDLE))
        assertEquals(
            "then BACKGROUND_PLAYER",
            TapEffect.START_BACKGROUND,
            policy.onTapChoice(GuardChoice.PLAY_NOW, tap, PlayerState.IDLE),
        )
        combined.settings.startIn = StartPlaybackIn.POPUP_PLAYER
        assertEquals("then POPUP_PLAYER", TapEffect.START_POPUP, policy.onTap(tap, PlayerState.IDLE))
        assertEquals(
            "then POPUP_PLAYER",
            TapEffect.START_POPUP,
            policy.onTapChoice(GuardChoice.PLAY_NOW, tap, PlayerState.IDLE),
        )
        combined.settings.startIn = StartPlaybackIn.VIDEO_PAGE
        assertEquals("then VIDEO_PAGE again", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, PlayerState.IDLE))
    }

    /**
     * Plan 4.2: "Asking is on" is `guardSettings.askBeforeReplacing()`, "read inside every call that needs
     * it and never cached (the user changes it in Settings, "Remember my choice" switches it off, AC-8)".
     * One policy object, one video in the main player, *Add to queue*: the tap opens the video while asking
     * is on (row 6b, AC-4) and enqueues while it is off (row 7, AC-3); the menu entry follows (rows 21b, 21).
     */
    @Test
    fun ac3_ac4_theAskSwitchIsReadAtEveryTap() {
        val oneVideoInMain = playing(PlayerKind.MAIN, queueSize = 1)
        val tap = tapOn()
        combined.settings.action = DefaultTapAction.ADD_TO_QUEUE

        combined.askSwitch.ask = true
        assertEquals("ask on", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, oneVideoInMain))
        combined.askSwitch.ask = false
        assertEquals("then ask off", TapEffect.ENQUEUE, policy.onTap(tap, oneVideoInMain))
        combined.askSwitch.ask = true
        assertEquals("then ask on again", TapEffect.OPEN_AS_TODAY, policy.onTap(tap, oneVideoInMain))

        assertEquals("ask on", false, policy.offersStreamDetails(oneVideoInMain))
        combined.askSwitch.ask = false
        assertEquals("then ask off", true, policy.offersStreamDetails(oneVideoInMain))
        combined.askSwitch.ask = true
        assertEquals("then ask on again", false, policy.offersStreamDetails(oneVideoInMain))
    }

    // =============================================================================================
    // AC-15: the play buttons
    // =============================================================================================

    /** Plan section 6, AC-15: `onListPlay` is "equal to `QueueGuardPolicy` alone for all 9 setting combinations". */
    @Test
    fun ac15_playButtonsGetQueueGuardsAnswerForAllNineCombinationsOfTheTwoSettings() {
        for (ask in ASK_ON_AND_OFF) {
            val nine = combinations(asks = listOf(ask))
            assertEquals("combinations of the two settings", 9, nine.size)
            combined.forEach(nine) { setting ->
                for (state in anyState) {
                    assertEquals(
                        "onListPlay under $setting, $state",
                        combined.queueGuardAlone.onListPlay(state),
                        policy.onListPlay(state),
                    )
                }
            }
        }
    }

    /** Plan section 6, AC-15: `onListChoice` likewise. */
    @Test
    fun ac15_listDialogChoicesGetQueueGuardsAnswerForAllNineCombinationsOfTheTwoSettings() {
        combined.forEach(combinations()) { setting ->
            for (state in anyState) {
                for (choice in ALL_CHOICES) {
                    assertEquals(
                        "onListChoice($choice) under $setting, $state",
                        combined.queueGuardAlone.onListChoice(choice, state),
                        policy.onListChoice(choice, state),
                    )
                }
            }
        }
    }

    /**
     * AC-15 in the values of queue-guard's own criteria: with a queue worth guarding a play button opens
     * the dialog (QG AC-10), unless the switch is off (QG AC-16); without one, i.e. while nothing is playing
     * or with a single video in the main player, it behaves as today (QG AC-14), with the switch on and
     * off. Neither the default action nor "Start playback in" moves a play button: not to the queue, and
     * not to another player while nothing is playing.
     */
    @Test
    fun ac15_playButtonsAskOrPlayAsTodayWhateverTheTwoSettingsAre() {
        combined.forEach(combinations()) { setting ->
            for (state in NOTHING_PLAYING) {
                assertEquals("onListPlay under $setting, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
            }
            for (state in WORTH_GUARDING) {
                val expected = if (setting.ask) ListEffect.ASK else ListEffect.PLAY_AS_TODAY
                assertEquals("onListPlay under $setting, $state", expected, policy.onListPlay(state))
            }
            for (state in SINGLE_IN_MAIN) {
                assertEquals("onListPlay under $setting, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
            }
        }
    }

    /** AC-15 in the values of queue-guard's own table (QG plan 3.2, `onListChoice`). */
    @Test
    fun ac15_listDialogChoicesDoWhatTheyDoInQueueGuardWhateverTheTwoSettingsAre() {
        combined.forEach(combinations()) { setting ->
            for (state in anyState) {
                val label = "under $setting, $state"
                assertEquals("CANCEL $label", ListEffect.NONE, policy.onListChoice(GuardChoice.CANCEL, state))
                assertEquals(
                    "ADD_TO_QUEUE $label",
                    ListEffect.ENQUEUE_ALL,
                    policy.onListChoice(GuardChoice.ADD_TO_QUEUE, state),
                )
                assertEquals(
                    "PLAY_NOW $label",
                    ListEffect.PLAY_AS_TODAY,
                    policy.onListChoice(GuardChoice.PLAY_NOW, state),
                )
            }
            for (state in SOMETHING_PLAYING) {
                assertEquals(
                    "PLAY_NEXT under $setting, $state",
                    ListEffect.ENQUEUE_ALL_NEXT,
                    policy.onListChoice(GuardChoice.PLAY_NEXT, state),
                )
            }
            for (state in NOTHING_PLAYING) {
                assertEquals(
                    "PLAY_NEXT under $setting, $state",
                    ListEffect.ENQUEUE_ALL,
                    policy.onListChoice(GuardChoice.PLAY_NEXT, state),
                )
            }
        }
    }
}
