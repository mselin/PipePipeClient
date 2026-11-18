package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.OpenWay
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.TapRequest

/**
 * What a tap, a dialog choice and a play button end up doing once the combined policy sits in
 * queue-guard's controller, as hook G1 puts it there ("C" in plan section 6:
 * `QueueGuardController(P, mutable fake PlayerStateSource)` with recording `TapActions`, `ListActions`,
 * `GuardUi`).
 *
 * Each test compares the whole [CallLog] of a [Scene] with the expected list, so "only `enqueue()`", "no
 * UI" and "calls nothing" are all the same assertion. The value a tap returns is queue-guard's contract
 * with its hook (QG plan 2, form A): `true` = taken over, `false` = upstream's own statements run, which
 * is "as today".
 *
 * Amendment 2 (spec AM-2, AM-3; plan B2.7): every playing state is guarded, so the tests that ran over the
 * fixtures `WORTH_GUARDING` and `SINGLE_IN_MAIN` run over [SOMETHING_PLAYING] or [ONE_IN_MAIN]; the two
 * tests that expected today's tap for a single video in the main player with asking on now expect the
 * dialog and say so in their KDoc; the two tests about queues with auto-enqueued videos are gone with the
 * model field they were about. New: requests of another way than a list tap (`am3_...`).
 */
class OpenActionControllerTest {

    private val combined = CombinedPolicy()

    private val on = listOf(true)
    private val off = listOf(false)

    private val anotherVideoInAnyList =
        listOf(tapOn(startsListInBackground = false), tapOn(startsListInBackground = true))
    private val anyTap = tapsOnBothUrls(musicPlayerMode = false) + tapsOnBothUrls(musicPlayerMode = true)

    private val noCalls = emptyList<String>()

    private fun scene(state: PlayerState): Scene = Scene(combined.policy, state)

    /**
     * A tap under each of [settings], in each of [states], with each of [taps], every time on a fresh
     * [Scene]: the hook gets [takenOver] back and the fakes saw exactly [calls].
     */
    private fun expectTap(
        row: String,
        settings: List<Combination>,
        states: List<PlayerState>,
        taps: List<TapRequest>,
        takenOver: Boolean,
        calls: (PlayerState) -> List<String>,
    ) {
        combined.forEach(settings) { setting ->
            for (state in states) {
                for (tap in taps) {
                    val scene = scene(state)

                    val answer = scene.tap(tap)

                    val label = "$row: tap under $setting, $state, $tap"
                    assertEquals("$label: calls", calls(state), scene.log.all)
                    assertEquals("$label: taken over", takenOver, answer)
                }
            }
        }
    }

    // =============================================================================================
    // AC-3: ask off, something is playing: the default action, directly
    // =============================================================================================

    /** Plan section 6, AC-3, C: "only `enqueue()` ... is called, `true` is returned, no UI". */
    @Test
    fun ac3_row7_askOff_addToQueueCallsOnlyEnqueueAndOpensNoDialog() {
        expectTap(
            row = "row 7",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = SOMETHING_PLAYING,
            taps = anotherVideoInAnyList,
            takenOver = true,
            calls = { listOf("enqueue") },
        )
    }

    /** As row 7 for *Play next*: only `enqueueNext()`. */
    @Test
    fun ac3_row8_askOff_playNextCallsOnlyEnqueueNextAndOpensNoDialog() {
        expectTap(
            row = "row 8",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            states = SOMETHING_PLAYING,
            taps = anotherVideoInAnyList,
            takenOver = true,
            calls = { listOf("enqueueNext") },
        )
    }

    /** *Play now* while the background player is in use: only `startInBackground()`, for every start-in value. */
    @Test
    fun ac3_row11_askOff_playNowInTheBackgroundPlayerCallsOnlyStartInBackgroundAndOpensNoDialog() {
        expectTap(
            row = "row 11",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.BACKGROUND),
            taps = listOf(tapOn()),
            takenOver = true,
            calls = { listOf("startInBackground") },
        )
    }

    /** *Play now* while the popup player is in use: only `startInPopup()`, for every start-in value. */
    @Test
    fun ac3_row12_askOff_playNowInThePopupPlayerCallsOnlyStartInPopupAndOpensNoDialog() {
        expectTap(
            row = "row 12",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.POPUP),
            taps = listOf(tapOn()),
            takenOver = true,
            calls = { listOf("startInPopup") },
        )
    }

    /** Plan section 6, AC-3, C: "`PLAY_NOW` on `MAIN` returns `false`": upstream's tap handler plays it now. */
    @Test
    fun ac3_row10_askOff_playNowInTheMainPlayerIsLeftToTodaysTapHandler() {
        expectTap(
            row = "row 10",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = playingIn(PlayerKind.MAIN),
            taps = listOf(tapOn()),
            takenOver = false,
            calls = { noCalls },
        )
    }

    /** Row 9: in a local playlist with Music Player Mode on, *Play now* is today's tap there, in every player. */
    @Test
    fun ac3_row9_askOff_playNowWithMusicPlayerModeIsLeftToTodaysTapHandler() {
        expectTap(
            row = "row 9",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            states = SOMETHING_PLAYING,
            taps = listOf(tapOn(startsListInBackground = true)),
            takenOver = false,
            calls = { noCalls },
        )
    }

    /**
     * Rows 7 and 8 on a single video in the main player. AC-3: the default action is carried out "also on a
     * single video in the main player ..., without a dialog": only `enqueue()` or only `enqueueNext()`, the
     * tap is taken over, no dialog.
     */
    @Test
    fun ac3_rows7and8_askOff_theDefaultActionIsCarriedOutOnASingleVideoInTheMainPlayer() {
        expectTap(
            row = "row 7 (single video in the main player)",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = ONE_IN_MAIN,
            taps = anotherVideoInAnyList,
            takenOver = true,
            calls = { listOf("enqueue") },
        )
        expectTap(
            row = "row 8 (single video in the main player)",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            states = ONE_IN_MAIN,
            taps = anotherVideoInAnyList,
            takenOver = true,
            calls = { listOf("enqueueNext") },
        )
    }

    /**
     * Plan section 6, AC-3, C: "a queue is started by tapping (`MAIN` 1/0, *Add to queue*: `enqueue()`,
     * then with 2/0 again `enqueue()`)". AC-3: "this is how a queue is started by tapping". Then asking is
     * switched on: the tap on the queue of two asks (row 6, AC-4), and once a single video is left the tap
     * asks as well (row 6, AM-2), although *Add to queue* is still selected: nothing is enqueued by itself.
     *
     * Inverted by amendment 2 in its last step. Before, the tap on the single video with asking on was
     * left to today's tap handler (`false`, no call, no dialog: row 6b, AC-4 as answer A8 had it). AM-2
     * holds now: any loaded item is worth guarding, so the tap is taken over and only the dialog opens,
     * with "1" as the queue size.
     */
    @Test
    fun ac3_ac4_aQueueIsStartedByTappingAndGuardedOnceItExists() {
        combined.askSwitch.ask = false
        combined.settings.action = DefaultTapAction.ADD_TO_QUEUE
        val scene = scene(playing(PlayerKind.MAIN, queueSize = 1))

        assertTrue("ask off, one video: the tap is taken over", scene.tap(tapOn()))
        assertEquals("ask off, one video: the tap enqueues", listOf("enqueue"), scene.log.all)

        scene.player.state = playing(PlayerKind.MAIN, queueSize = 2)
        assertTrue("ask off, two videos: the tap is taken over", scene.tap(tapOn()))
        assertEquals("ask off, two videos: the tap enqueues", listOf("enqueue", "enqueue"), scene.log.all)

        combined.askSwitch.ask = true
        assertTrue("ask on, two videos: the tap is taken over", scene.tap(tapOn()))
        val withTheDialog = listOf("enqueue", "enqueue", askedForTap(queueSize = 2))
        assertEquals("ask on, two videos: the tap only opens the dialog", withTheDialog, scene.log.all)

        scene.player.state = playing(PlayerKind.MAIN, queueSize = 1)
        assertTrue("ask on, one video: the tap is taken over", scene.tap(tapOn()))
        assertEquals(
            "ask on, one video: the tap only opens the dialog, for a queue of one",
            withTheDialog + askedForTap(queueSize = 1),
            scene.log.all,
        )
    }

    // =============================================================================================
    // AC-4, AM-2: ask on, something is playing: the dialog, for a single video in the main player too
    // =============================================================================================

    /**
     * Plan section 6, AC-4, C: "row 6 only `askForTap`, no action". The dialog shows title and queue size
     * (QG AC-2). Since amendment 2 it opens in every playing state (AM-2; before, over `WORTH_GUARDING`).
     */
    @Test
    fun ac4_row6_askOn_tapOnlyOpensTheDialogForAllNineCombinationsOfTheTwoSettings() {
        expectTap(
            row = "row 6",
            settings = combinations(asks = on),
            states = SOMETHING_PLAYING,
            taps = anotherVideoInAnyList,
            takenOver = true,
            calls = { state -> listOf(askedForTap(queueSize = state.queueSize)) },
        )
    }

    /**
     * Row 6 on a single video in the main player, through the controller. AM-2 with AC-4: with asking on
     * the tap is taken over and only the dialog opens, showing a queue of "1" (plan B2.7: "only the dialog,
     * with "1""; device script B2.8 step 2: "the dialog opens ("1 item in the queue"), nothing is enqueued
     * by itself"), whatever the default action is.
     *
     * Inverted by amendment 2. Before, this was
     * `ac4_row6b_askOn_tapOnASingleVideoInTheMainPlayerIsLeftToTodaysTapHandler` and expected `false`, no
     * dialog and no action (row 6b of plan 4.2, AC-4 as answer A8 had it: "with a single video in the main
     * player the tap opens the video as today"). AM-2 replaces that answer.
     */
    @Test
    fun am2_ac4_row6_askOn_tapOnASingleVideoInTheMainPlayerOnlyOpensTheDialog() {
        val nine = combinations(asks = on)
        assertEquals("combinations of the two settings", 9, nine.size)
        expectTap(
            row = "row 6 (single video in the main player; row 6b before amendment 2)",
            settings = nine,
            states = ONE_IN_MAIN,
            taps = anotherVideoInAnyList,
            takenOver = true,
            calls = { listOf(askedForTap(queueSize = 1)) },
        )
    }

    /**
     * Row 6c through the controller. AM-2 (queue-guard's AM-2): with asking on a tap in the related list of
     * the current item's page is left to today's tap handler, no dialog (device script B2.8 step 2: "it
     * opens and plays, no dialog"). With asking off the same tap gets the default action (rows 7 and 8;
     * questions.md A9).
     */
    @Test
    fun am2_row6c_tapInTheRelatedListOfTheCurrentItemsPageOpensAsTodayWithAskOnAndGetsTheDefaultActionWithAskOff() {
        expectTap(
            row = "row 6c",
            settings = combinations(asks = on),
            states = SOMETHING_PLAYING,
            taps = listOf(relatedTap()),
            takenOver = false,
            calls = { noCalls },
        )
        expectTap(
            row = "row 7 (related tap)",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = SOMETHING_PLAYING,
            taps = listOf(relatedTap()),
            takenOver = true,
            calls = { listOf("enqueue") },
        )
        expectTap(
            row = "row 8 (related tap)",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NEXT)),
            states = SOMETHING_PLAYING,
            taps = listOf(relatedTap()),
            takenOver = true,
            calls = { listOf("enqueueNext") },
        )
    }

    /**
     * AC-4: "The default action is not used." Whatever it is set to, the choice made in the dialog is
     * what happens (rows 14, 15 and 18), and *Cancel* does nothing (row 13).
     */
    @Test
    fun ac4_askOn_theChoiceMadeInTheDialogIsCarriedOutNotTheDefaultAction() {
        val playNowIn = mapOf(
            PlayerKind.MAIN to "openAsToday",
            PlayerKind.BACKGROUND to "startInBackground",
            PlayerKind.POPUP to "startInPopup",
        )
        combined.forEach(combinations(asks = on)) { setting ->
            for (kind in ALL_KINDS) {
                val expectedCalls = mapOf(
                    GuardChoice.ADD_TO_QUEUE to listOf("enqueue"),
                    GuardChoice.PLAY_NEXT to listOf("enqueueNext"),
                    GuardChoice.PLAY_NOW to listOf(playNowIn.getValue(kind)),
                    GuardChoice.CANCEL to noCalls,
                )
                for (choice in ALL_CHOICES) {
                    val scene = scene(playing(kind))
                    scene.tap(tapOn())

                    scene.dialog.choose(choice)

                    assertEquals(
                        "$choice chosen under $setting in the $kind player",
                        listOf(askedForTap(queueSize = 3)) + expectedCalls.getValue(choice),
                        scene.log.all,
                    )
                }
            }
        }
    }

    // =============================================================================================
    // AC-5, AC-6: nothing is playing
    // =============================================================================================

    /** Plan section 6, AC-5, C: "`VIDEO_PAGE` returns `false` and calls nothing". */
    @Test
    fun ac5_row2_nothingPlaying_videoPageIsLeftToTodaysTapHandler() {
        expectTap(
            row = "row 2",
            settings = combinations(startIns = listOf(StartPlaybackIn.VIDEO_PAGE)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
            takenOver = false,
            calls = { noCalls },
        )
    }

    /** Plan section 6, AC-5, C: "`BACKGROUND_PLAYER` calls only `startInBackground()` ... `true` without UI". */
    @Test
    fun ac5_row3_nothingPlaying_backgroundPlayerCallsOnlyStartInBackgroundAndOpensNoDialog() {
        expectTap(
            row = "row 3",
            settings = combinations(startIns = listOf(StartPlaybackIn.BACKGROUND_PLAYER)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
            takenOver = true,
            calls = { listOf("startInBackground") },
        )
    }

    /** Plan section 6, AC-5, C: "`POPUP_PLAYER` only `startInPopup()` ... `true` without UI". */
    @Test
    fun ac5_row4_nothingPlaying_popupPlayerCallsOnlyStartInPopupAndOpensNoDialog() {
        expectTap(
            row = "row 4",
            settings = combinations(startIns = listOf(StartPlaybackIn.POPUP_PLAYER)),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = false),
            takenOver = true,
            calls = { listOf("startInPopup") },
        )
    }

    /** Plan section 6, AC-6, C: "returns `false` for every start-in value". */
    @Test
    fun ac6_row1_nothingPlaying_musicPlayerModeTapIsLeftToTodaysTapHandlerForEveryStartPlaybackInValue() {
        expectTap(
            row = "row 1",
            settings = combinations(),
            states = NOTHING_PLAYING,
            taps = tapsOnBothUrls(musicPlayerMode = true),
            takenOver = false,
            calls = { noCalls },
        )
    }

    // =============================================================================================
    // AC-9: Cancel
    // =============================================================================================

    /**
     * Row 13 through the controller: *Cancel* calls no action, whatever the two settings are. The dialog
     * opens in every playing state (row 6).
     */
    @Test
    fun ac9_row13_cancelInTheDialogCallsNoAction() {
        combined.forEach(combinations(asks = on)) { setting ->
            for (state in SOMETHING_PLAYING) {
                val scene = scene(state)
                scene.tap(tapOn())

                scene.dialog.choose(GuardChoice.CANCEL)

                assertEquals(
                    "Cancel under $setting, $state",
                    listOf(askedForTap(queueSize = state.queueSize)),
                    scene.log.all,
                )
            }
        }
    }

    // =============================================================================================
    // AC-11: "Show stream details"
    // =============================================================================================

    private fun expectOffer(row: String, expected: Boolean, settings: List<Combination>, states: List<PlayerState>) {
        combined.forEach(settings) { setting ->
            for (state in states) {
                val scene = scene(state)
                assertEquals(
                    "$row: controller.offersStreamDetails() under $setting, $state",
                    expected,
                    scene.controller.offersStreamDetails(),
                )
                assertEquals("$row: asking for the menu entry must call nothing", noCalls, scene.log.all)
            }
        }
    }

    /** Plan section 6, AC-11, C: "`offersStreamDetails()`". Queue-guard's menu hook M1 asks the controller. */
    @Test
    fun ac11_rows20to23_controllerOffersStreamDetailsWheneverATapWouldNeitherOpenThePageNorAsk() {
        expectOffer("row 20", true, combinations(), playingIn(PlayerKind.BACKGROUND) + playingIn(PlayerKind.POPUP))
        expectOffer(
            "row 21",
            true,
            combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.PLAY_NEXT)),
            playingIn(PlayerKind.MAIN),
        )
        expectOffer("row 21b (ask on)", false, combinations(asks = on), playingIn(PlayerKind.MAIN))
        expectOffer(
            "row 21b (ask off, PLAY_NOW)",
            false,
            combinations(asks = off, actions = listOf(DefaultTapAction.PLAY_NOW)),
            playingIn(PlayerKind.MAIN),
        )
        expectOffer(
            "row 22",
            true,
            combinations(startIns = listOf(StartPlaybackIn.BACKGROUND_PLAYER, StartPlaybackIn.POPUP_PLAYER)),
            NOTHING_PLAYING,
        )
        expectOffer("row 23", false, combinations(startIns = listOf(StartPlaybackIn.VIDEO_PAGE)), NOTHING_PLAYING)
    }

    /**
     * Rows 21 and 21b on a single video in the main player, all 18 combinations. AC-11: the entry is there
     * "whenever a video tap would neither open the video's page nor ask": with ask off and *Add to queue*
     * or *Play next* (row 21, the tap enqueues), and in no other case (row 21b: with ask on the tap asks,
     * row 6, where before amendment 2 it opened the page, row 6b; with *Play now* it opens the page). The
     * results are those of before the amendment (plan B2.7: "keeps its results").
     */
    @Test
    fun ac11_rows21and21b_singleVideoInTheMainPlayer_controllerOffersStreamDetailsOnlyWithAskOffAndAnEnqueueAction() {
        val all = combinations()
        assertEquals("combinations of the three settings", 18, all.size)
        for (setting in all) {
            val enqueues = setting.action in listOf(DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.PLAY_NEXT)
            val expected = !setting.ask && enqueues
            expectOffer(if (expected) "row 21" else "row 21b", expected, listOf(setting), ONE_IN_MAIN)
        }
    }

    /** The menu is built at every long press: player and settings count as they are at that moment. */
    @Test
    fun ac11_controllerOffersStreamDetailsForThePlayerAndTheSettingsOfTheMoment() {
        val scene = scene(PlayerState.IDLE)
        val offered = { scene.controller.offersStreamDetails() }
        combined.set(Combination(ask = true, action = DefaultTapAction.PLAY_NOW, startIn = StartPlaybackIn.VIDEO_PAGE))
        assertFalse("row 23: nothing playing, Video page", offered())

        combined.settings.startIn = StartPlaybackIn.BACKGROUND_PLAYER
        assertTrue("row 22: nothing playing, Background player", offered())
        combined.settings.startIn = StartPlaybackIn.POPUP_PLAYER
        assertTrue("row 22: nothing playing, Popup player", offered())

        scene.player.state = playing(PlayerKind.MAIN)
        combined.settings.action = DefaultTapAction.ADD_TO_QUEUE
        assertFalse("row 21b: main player, ask on", offered())
        combined.askSwitch.ask = false
        assertTrue("row 21: main player, ask off, Add to queue", offered())
        combined.settings.action = DefaultTapAction.PLAY_NEXT
        assertTrue("row 21: main player, ask off, Play next", offered())
        combined.settings.action = DefaultTapAction.PLAY_NOW
        assertFalse("row 21b: main player, ask off, Play now", offered())

        scene.player.state = playing(PlayerKind.BACKGROUND)
        assertTrue("row 20: background player", offered())
        scene.player.state = playing(PlayerKind.POPUP)
        assertTrue("row 20: popup player", offered())

        combined.settings.startIn = StartPlaybackIn.VIDEO_PAGE
        scene.player.state = PlayerState.IDLE
        assertFalse("row 23: player closed again, Video page", offered())
        assertEquals("asking for the menu entry must call nothing", noCalls, scene.log.all)
    }

    // =============================================================================================
    // AC-12: the current item
    // =============================================================================================

    /** Plan section 6, AC-12, C: "returns `false`", for every combination of the settings, ask and kind. */
    @Test
    fun ac12_row5_tapOnTheCurrentItemIsLeftToTodaysTapHandlerWhateverTheSettings() {
        expectTap(
            row = "row 5",
            settings = combinations(),
            states = SOMETHING_PLAYING,
            taps = listOf(tapOnCurrent(startsListInBackground = false), tapOnCurrent(startsListInBackground = true)),
            takenOver = false,
            calls = { noCalls },
        )
    }

    // =============================================================================================
    // AC-13: Play now after the player was closed while the dialog was open
    // =============================================================================================

    /**
     * Plan section 6, AC-13, C: "the fake player is set to `PlayerState.IDLE` between prompt and choice".
     * Opens the dialog in the player of [kind], closes the player, chooses *Play now*, and returns the
     * scene together with the answers the hook gave to taps replayed from `openAsToday()`.
     */
    private fun playNowAfterThePlayerWasClosed(kind: PlayerKind, tap: TapRequest): Pair<Scene, List<Boolean>> {
        val scene = scene(playing(kind))
        val replayedTaps = scene.replayOnOpenAsToday(tap)
        assertTrue("the tap must open the dialog first", scene.tap(tap))
        assertEquals(listOf(askedForTap(queueSize = 3)), scene.log.all)

        scene.player.state = PlayerState.IDLE
        scene.dialog.choose(GuardChoice.PLAY_NOW)

        return scene to replayedTaps
    }

    /**
     * Row 19 through row 2: "what a video tap does today". Queue-guard carries `OPEN_AS_TODAY` out by
     * replaying the tap (QG plan 3.2); the replayed tap must get `false` so that upstream's handler runs.
     */
    @Test
    fun ac13_row19_playNowAfterThePlayerWasClosed_videoPageReplaysTheTapAsToday() {
        combined.forEach(combinations(asks = on, startIns = listOf(StartPlaybackIn.VIDEO_PAGE))) { setting ->
            for (kind in ALL_KINDS) {
                val (scene, replayedTaps) = playNowAfterThePlayerWasClosed(kind, tapOn())

                val label = "Play now under $setting after the $kind player was closed"
                assertEquals(label, listOf(askedForTap(queueSize = 3), "openAsToday"), scene.log.all)
                assertEquals("$label: the replayed tap is left to today's handler", listOf(false), replayedTaps)
            }
        }
    }

    /** Row 19 through row 3. */
    @Test
    fun ac13_row19_playNowAfterThePlayerWasClosed_backgroundPlayerCallsOnlyStartInBackground() {
        combined.forEach(combinations(asks = on, startIns = listOf(StartPlaybackIn.BACKGROUND_PLAYER))) { setting ->
            for (kind in ALL_KINDS) {
                val (scene, _) = playNowAfterThePlayerWasClosed(kind, tapOn())

                assertEquals(
                    "Play now under $setting after the $kind player was closed",
                    listOf(askedForTap(queueSize = 3), "startInBackground"),
                    scene.log.all,
                )
            }
        }
    }

    /** Row 19 through row 4. */
    @Test
    fun ac13_row19_playNowAfterThePlayerWasClosed_popupPlayerCallsOnlyStartInPopup() {
        combined.forEach(combinations(asks = on, startIns = listOf(StartPlaybackIn.POPUP_PLAYER))) { setting ->
            for (kind in ALL_KINDS) {
                val (scene, _) = playNowAfterThePlayerWasClosed(kind, tapOn())

                assertEquals(
                    "Play now under $setting after the $kind player was closed",
                    listOf(askedForTap(queueSize = 3), "startInPopup"),
                    scene.log.all,
                )
            }
        }
    }

    /** Row 19 through row 1: "AC-6 in a local playlist with Music Player Mode on", for each start-in value. */
    @Test
    fun ac13_ac6_row19_playNowAfterThePlayerWasClosed_musicPlayerModeReplaysTheTapForEveryStartPlaybackInValue() {
        combined.forEach(combinations(asks = on)) { setting ->
            for (kind in ALL_KINDS) {
                val tap = tapOn(startsListInBackground = true)
                val (scene, replayedTaps) = playNowAfterThePlayerWasClosed(kind, tap)

                val label = "Play now under $setting after the $kind player was closed, Music Player Mode"
                assertEquals(label, listOf(askedForTap(queueSize = 3), "openAsToday"), scene.log.all)
                assertEquals("$label: the replayed tap is left to today's handler", listOf(false), replayedTaps)
            }
        }
    }

    // =============================================================================================
    // AC-14: defaults
    // =============================================================================================

    /**
     * Everything a tap leads to on a fresh scene with [policy]: the fakes' log, what the hook returned, and
     * what replayed taps got. With [choice] the user answers the dialog, if one opened; with
     * [closePlayerFirst] the player is closed before that.
     */
    private fun tapTranscript(
        policy: GuardPolicy,
        state: PlayerState,
        tap: TapRequest,
        choice: GuardChoice?,
        closePlayerFirst: Boolean,
    ): List<String> {
        val scene = Scene(policy, state)
        val replayedTaps = scene.replayOnOpenAsToday(tap)
        val takenOver = scene.tap(tap)
        if (choice != null && scene.dialog.promptCount > 0) {
            if (closePlayerFirst) scene.player.state = PlayerState.IDLE
            scene.dialog.choose(choice)
        }
        return scene.log.all + "tap taken over: $takenOver" + "replayed taps taken over: $replayedTaps"
    }

    /**
     * AC-14 seen from the hook: with both settings on their defaults, every tap, every dialog choice and
     * every choice after the player was closed runs exactly as with queue-guard's policy alone.
     */
    @Test
    fun ac14_defaultSettings_tapsAndDialogChoicesRunAsWithQueueGuardAlone() {
        val defaults =
            combinations(actions = listOf(DefaultTapAction.DEFAULT), startIns = listOf(StartPlaybackIn.DEFAULT))
        combined.forEach(defaults) { setting ->
            for (state in NOTHING_PLAYING + SOMETHING_PLAYING) {
                for (tap in anyTap) {
                    for (choice in listOf<GuardChoice?>(null) + ALL_CHOICES) {
                        for (closePlayerFirst in listOf(false, true)) {
                            assertEquals(
                                "under $setting, $state, $tap, choice $choice, player closed first: $closePlayerFirst",
                                tapTranscript(combined.queueGuardAlone, state, tap, choice, closePlayerFirst),
                                tapTranscript(combined.policy, state, tap, choice, closePlayerFirst),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * AC-14 as above for what amendment 2 adds to the grid: the tap in a related list (of the current
     * item's page and of another video's) and the requests of every other way. With both settings on their
     * defaults they run exactly as with queue-guard's policy alone, with ask on and off, playing or not.
     */
    @Test
    fun ac14_defaultSettings_relatedTapsAndRequestsOfOtherWaysRunAsWithQueueGuardAlone() {
        val defaults =
            combinations(actions = listOf(DefaultTapAction.DEFAULT), startIns = listOf(StartPlaybackIn.DEFAULT))
        val requests = REQUESTS_BY_OTHER_WAYS + relatedTap() + relatedTap(pageOf = OTHER_VIDEO)
        assertEquals("requests of other ways and related taps", 10, requests.size)
        combined.forEach(defaults) { setting ->
            for (state in NOTHING_PLAYING + SOMETHING_PLAYING) {
                for (tap in requests) {
                    for (choice in listOf<GuardChoice?>(null) + ALL_CHOICES) {
                        for (closePlayerFirst in listOf(false, true)) {
                            assertEquals(
                                "under $setting, $state, $tap, choice $choice, player closed first: $closePlayerFirst",
                                tapTranscript(combined.queueGuardAlone, state, tap, choice, closePlayerFirst),
                                tapTranscript(combined.policy, state, tap, choice, closePlayerFirst),
                            )
                        }
                    }
                }
            }
        }
    }

    // =============================================================================================
    // AM-3: requests of another way than a video tap in a list
    // =============================================================================================

    /**
     * AM-3 seen from the hook, all 18 combinations: a page control, a link or a video from another app
     * runs exactly as with queue-guard's policy alone, whatever the default action and "Start playback in"
     * are: the tap itself, every dialog choice, and every choice after the player was closed (plan B2.2:
     * "also while nothing is playing and also when the player was closed while their dialog was open").
     */
    @Test
    fun am3_requestsOfOtherWaysRunAsWithQueueGuardAloneForAllEighteenCombinations() {
        val all = combinations()
        assertEquals("combinations of the three settings", 18, all.size)
        combined.forEach(all) { setting ->
            for (state in NOTHING_PLAYING + SOMETHING_PLAYING) {
                for (request in REQUESTS_BY_OTHER_WAYS) {
                    for (choice in listOf<GuardChoice?>(null) + ALL_CHOICES) {
                        for (closePlayerFirst in listOf(false, true)) {
                            assertEquals(
                                "under $setting, $state, $request, choice $choice, " +
                                    "player closed first: $closePlayerFirst",
                                tapTranscript(combined.queueGuardAlone, state, request, choice, closePlayerFirst),
                                tapTranscript(combined.policy, state, request, choice, closePlayerFirst),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * AM-3, plan B2.7: "a page-control request with asking off and *Add to queue*: `false`, nothing
     * called". The control does what it does today (device script B2.8 step 3: "it plays there at once,
     * nothing is enqueued"); the same for *Play next* and for every other way, in every player.
     */
    @Test
    fun am3_askOff_aPageControlIsLeftToTodaysHandlerWhateverTheDefaultActionIs() {
        expectTap(
            row = "PAGE_CONTROL, ask off, ADD_TO_QUEUE",
            settings = combinations(asks = off, actions = listOf(DefaultTapAction.ADD_TO_QUEUE)),
            states = SOMETHING_PLAYING,
            taps = listOf(requestBy(OpenWay.PAGE_CONTROL)),
            takenOver = false,
            calls = { noCalls },
        )
        expectTap(
            row = "every other way, ask off",
            settings = combinations(asks = off),
            states = SOMETHING_PLAYING,
            taps = NOT_LIST_WAYS.map { requestBy(it) },
            takenOver = false,
            calls = { noCalls },
        )
    }

    /**
     * AM-3, plan B2.7: "with asking on: the dialog, *Play now* calls `openAsToday()`". The dialog of a page
     * control is queue-guard's; its *Play now* is what the pressed control does today (QG A2.2 row 11),
     * also while the background or the popup player is in use, where a list tap's *Play now* would start
     * the video in that player (row 18). The replayed request is let through (`false`). *Add to queue* and
     * *Play next* enqueue as in every tap dialog; *Cancel* does nothing.
     */
    @Test
    fun am3_askOn_aPageControlOpensTheDialogAndPlayNowDoesWhatTheControlDoesToday() {
        val request = requestBy(OpenWay.PAGE_CONTROL)
        val expectedCalls = mapOf(
            GuardChoice.ADD_TO_QUEUE to listOf("enqueue"),
            GuardChoice.PLAY_NEXT to listOf("enqueueNext"),
            GuardChoice.PLAY_NOW to listOf("openAsToday"),
            GuardChoice.CANCEL to noCalls,
        )
        combined.forEach(combinations(asks = on)) { setting ->
            for (state in SOMETHING_PLAYING) {
                for (choice in ALL_CHOICES) {
                    val scene = scene(state)
                    val replayed = scene.replayOnOpenAsToday(request)
                    val asked = listOf(askedForTap(queueSize = state.queueSize))
                    val label = "page control under $setting, $state"

                    assertTrue("$label: taken over", scene.tap(request))
                    assertEquals("$label: only the dialog", asked, scene.log.all)

                    scene.dialog.choose(choice)

                    assertEquals("$label: $choice", asked + expectedCalls.getValue(choice), scene.log.all)
                    val expectedReplays = if (choice == GuardChoice.PLAY_NOW) listOf(false) else emptyList()
                    assertEquals("$label: $choice: replayed requests taken over", expectedReplays, replayed)
                }
            }
        }
    }

    /**
     * AM-3, plan B2.7: "nothing playing with *Popup player*: `false`". "Start playback in" is about video
     * taps in lists (AC-5): a page control, a link and a video from another app are left to today's handler
     * while nothing is playing, for every value of the setting (device script B2.8 step 3: "the page opens,
     * no popup starts").
     */
    @Test
    fun am3_nothingPlaying_aRequestOfAnotherWayIsLeftToTodaysHandlerWhateverStartPlaybackInSays() {
        expectTap(
            row = "PAGE_CONTROL, nothing playing, POPUP_PLAYER",
            settings = combinations(startIns = listOf(StartPlaybackIn.POPUP_PLAYER)),
            states = NOTHING_PLAYING,
            taps = listOf(requestBy(OpenWay.PAGE_CONTROL)),
            takenOver = false,
            calls = { noCalls },
        )
        expectTap(
            row = "every other way, nothing playing",
            settings = combinations(),
            states = NOTHING_PLAYING,
            taps = REQUESTS_BY_OTHER_WAYS,
            takenOver = false,
            calls = { noCalls },
        )
    }

    /**
     * AM-3 with AC-13's situation: the player is closed while the dialog of a link is open, then *Play
     * now*. For a list tap "Start playback in" decides (row 19); for a request of another way it does not
     * (plan B2.2: "never `START_BACKGROUND` or `START_POPUP`"): `openAsToday()` under every value.
     */
    @Test
    fun am3_playNowForARequestOfAnotherWayAfterThePlayerWasClosedDoesNotFollowStartPlaybackIn() {
        combined.forEach(combinations(asks = on)) { setting ->
            for (way in listOf(OpenWay.PAGE_CONTROL, OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
                for (kind in ALL_KINDS) {
                    val (scene, replayedTaps) = playNowAfterThePlayerWasClosed(kind, requestBy(way))

                    val label = "Play now for $way under $setting after the $kind player was closed"
                    assertEquals(label, listOf(askedForTap(queueSize = 3), "openAsToday"), scene.log.all)
                    assertEquals("$label: the replayed request is let through", listOf(false), replayedTaps)
                }
            }
        }
    }

    // =============================================================================================
    // AC-15: the play buttons
    // =============================================================================================

    /** As [tapTranscript] for a play button above a list of [videoCount] videos. */
    private fun playButtonTranscript(
        policy: GuardPolicy,
        state: PlayerState,
        videoCount: Int,
        choice: GuardChoice?,
        closePlayerFirst: Boolean,
    ): List<String> {
        val scene = Scene(policy, state)
        scene.pressPlayButton(videoCount)
        if (choice != null && scene.dialog.promptCount > 0) {
            if (closePlayerFirst) scene.player.state = PlayerState.IDLE
            scene.dialog.choose(choice)
        }
        return scene.log.all
    }

    /** Plan section 6, AC-15, C: "`ListActions` as with QG alone", under all 9 combinations, ask on and off. */
    @Test
    fun ac15_playButtonsRunAsWithQueueGuardAloneForAllNineCombinationsOfTheTwoSettings() {
        combined.forEach(combinations()) { setting ->
            for (state in NOTHING_PLAYING + SOMETHING_PLAYING) {
                for (videoCount in listOf(0, 1, 25)) {
                    for (choice in listOf<GuardChoice?>(null) + ALL_CHOICES) {
                        for (closeFirst in listOf(false, true)) {
                            assertEquals(
                                "play button above $videoCount videos under $setting, $state, choice $choice, " +
                                    "player closed first: $closeFirst",
                                playButtonTranscript(combined.queueGuardAlone, state, videoCount, choice, closeFirst),
                                playButtonTranscript(combined.policy, state, videoCount, choice, closeFirst),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * AC-15 in queue-guard's own terms (QG AC-10 to AC-13): with ask on and something playing a play
     * button opens the list dialog, for a single video in the main player too since amendment 2, and its
     * choices add all, play all next, replace the queue or do nothing. The default action does not turn the
     * button into "add all", whatever it is set to.
     */
    @Test
    fun ac15_askOn_playButtonOpensTheListDialogAndItsChoicesAreQueueGuardsWhateverTheTwoSettingsAre() {
        val expectedCalls = mapOf(
            GuardChoice.ADD_TO_QUEUE to listOf("enqueueAll"),
            GuardChoice.PLAY_NEXT to listOf("enqueueAllNext"),
            GuardChoice.PLAY_NOW to listOf("playAsToday"),
            GuardChoice.CANCEL to noCalls,
        )
        combined.forEach(combinations(asks = on)) { setting ->
            for (state in SOMETHING_PLAYING) {
                for (choice in ALL_CHOICES) {
                    val scene = scene(state)

                    val asked = listOf(askedForList(videoCount = 25, queueSize = state.queueSize))

                    scene.pressPlayButton(videoCount = 25)
                    assertEquals("play button under $setting, $state", asked, scene.log.all)

                    scene.dialog.choose(choice)
                    assertEquals(
                        "$choice in the list dialog under $setting, $state",
                        asked + expectedCalls.getValue(choice),
                        scene.log.all,
                    )
                }
            }
        }
    }

    /** AC-15 with ask off (QG AC-16): the button does what it does today, whatever the default action is. */
    @Test
    fun ac15_askOff_playButtonPlaysAsTodayAtOnceWhateverTheDefaultActionIs() {
        combined.forEach(combinations(asks = off)) { setting ->
            for (state in SOMETHING_PLAYING) {
                val scene = scene(state)

                scene.pressPlayButton(videoCount = 25)

                assertEquals("play button under $setting, $state", listOf("playAsToday"), scene.log.all)
            }
        }
    }

    /**
     * AC-15 while nothing is playing (QG AC-14): the button does what it does today. "Start playback in"
     * is about video taps; it does not send Play All to the background or the popup player.
     */
    @Test
    fun ac15_nothingPlaying_playButtonPlaysAsTodayAtOnceWhateverStartPlaybackInSays() {
        combined.forEach(combinations()) { setting ->
            for (state in NOTHING_PLAYING) {
                val scene = scene(state)

                scene.pressPlayButton(videoCount = 25)

                assertEquals("play button under $setting, $state", listOf("playAsToday"), scene.log.all)
            }
        }
    }
}
