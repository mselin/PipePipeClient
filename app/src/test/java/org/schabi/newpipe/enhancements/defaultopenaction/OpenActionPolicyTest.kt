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
 * `OpenActionPolicy(QueueGuardPolicy(GuardSettings { ask }), settings)` ("P" in plan section 6), one test
 * per row, named after the row and the acceptance criterion in its last column. (Rows 14 to 16 name only
 * criteria of queue-guard there; their tests carry AC-4, see row 14 and questions.md, Q7.)
 *
 * Every expected value is the table's. Where a row says "any" in a column, the test runs through every
 * value of that column: both positions of the ask switch, the three default actions, the three "Start
 * playback in" values, Music Player Mode on and off, and every player state of the row's "playing" value
 * ([SOMETHING_PLAYING]: each player with one item and with several; [NOTHING_PLAYING]: no player, and each
 * player without a queue). A failure message starts with the row and names the combination.
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

    /** Row 6. AC-4: with ask on the dialog opens "as before. The default action is not used." */
    @Test
    fun ac4_row6_askOn_tapAsksForAllNineCombinationsOfTheTwoSettings() {
        val nine = combinations(asks = on)
        assertEquals("combinations of the two settings", 9, nine.size)
        expectOnTap(
            row = "row 6",
            expected = TapEffect.ASK,
            settings = nine,
            states = SOMETHING_PLAYING,
            taps = anotherVideoInAnyList,
        )
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
     * Rows 6 to 8 and 11 at the edge of row 5's "current" (QG plan 3.1: same url and same service id): the
     * url of the current item on another service is another video. It is asked about, or gets the default
     * action.
     */
    @Test
    fun ac12_ac3_rows6to8and11_sameUrlOnAnotherServiceIsNotTheCurrentItem() {
        val tap = listOf(tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE))
        expectOnTap("row 6", TapEffect.ASK, combinations(asks = on), SOMETHING_PLAYING, tap)
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
     * AC-15 in the values of queue-guard's own table (QG plan 3.2, `onListPlay`): "not playing, or switch
     * off -> `PLAY_AS_TODAY`; otherwise -> `ASK`". Neither the default action nor "Start playback in"
     * moves a play button: not to the queue, and not to another player while nothing is playing.
     */
    @Test
    fun ac15_playButtonsAskOrPlayAsTodayWhateverTheTwoSettingsAre() {
        combined.forEach(combinations()) { setting ->
            for (state in NOTHING_PLAYING) {
                assertEquals("onListPlay under $setting, $state", ListEffect.PLAY_AS_TODAY, policy.onListPlay(state))
            }
            for (state in SOMETHING_PLAYING) {
                val expected = if (setting.ask) ListEffect.ASK else ListEffect.PLAY_AS_TODAY
                assertEquals("onListPlay under $setting, $state", expected, policy.onListPlay(state))
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
