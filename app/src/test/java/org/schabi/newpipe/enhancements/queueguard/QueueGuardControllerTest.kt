package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QueueGuardController] on top of the real [QueueGuardPolicy], one acceptance criterion after the other
 * (plan section 5, column "Verified by", the checks marked C).
 *
 * Every test compares the whole journal of the fakes with the expected list, so each one also proves the
 * order of the calls and that nothing else was called: no dialog, no other action, nothing on the
 * objects of another tap.
 *
 * Amendment 2 (AM-1): a dialog opens whenever something is playing, a single video in the main player
 * included. Tests that expect one run over [SOMETHING_PLAYING]. The section "A queue worth guarding, as
 * amended" holds the tests that pinned the opposite before (answers A11 and A13), now inverted, and the
 * last sections cover the requests Amendment 2 added: page controls, links and router actions
 * ([OpenWay]), and the related list (AM-2).
 */
class QueueGuardControllerTest {

    private val settings = SettableGuardSettings(ask = true)

    private fun scene(state: PlayerState): GuardScene = GuardScene(QueueGuardPolicy(settings), state)

    private val tap = tapOn(title = "Tapped video")
    private val musicModeTap = tapOn(title = "Tapped video", startsListInBackground = true)

    /**
     * What happens when the glue runs `replay` inside `openAsToday()`: upstream's tap handler runs again
     * and reaches the hook a second time. The glue builds its objects anew for that call (plan 3.3,
     * `QueueGuard.onVideoTap`), so the replay arrives with an equal but new [TapRequest], new actions
     * and a new ui. Its journal entries carry the label `replay`.
     */
    private class ReplayedTap(private val scene: GuardScene, private val request: TapRequest) {
        val actions = RecordingTapActions(scene.journal, label = "replay")
        val ui = FakeGuardUi(scene.journal, label = "replay")

        /** What `onVideoTap` answered to each replayed call. */
        val answers = mutableListOf<Boolean>()

        fun run() {
            answers += scene.controller.onVideoTap(request.copy(), actions, ui)
        }
    }

    /** Arms [scene] so that `openAsToday()` replays [request] as the glue does. */
    private fun replayOnOpenAsToday(scene: GuardScene, request: TapRequest): ReplayedTap {
        val replay = ReplayedTap(scene, request)
        scene.tapActions.duringOpenAsToday = replay::run
        return replay
    }

    // =============================================================================================
    // Video taps
    // =============================================================================================

    /** AC-1: "opens the queue-guard dialog. Until the user chooses, nothing else happens." */
    @Test
    fun ac1_tapWhileSomethingIsPlayingIsTakenOverAndOnlyOpensTheDialog() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            val takenOver = scene.tap(tap)

            assertTrue("$state: the tap must be taken over (true), or the page opens behind the dialog", takenOver)
            assertEquals("$state", listOf(askedForTap("Tapped video", state.queueSize)), scene.journal.all)
            assertEquals("$state: no action before a choice", emptyList<String>(), scene.tapActions.calls)
        }
    }

    @Test
    fun ac1_tapWithMusicPlayerModeIsTakenOverAndOnlyOpensTheDialog() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            val takenOver = scene.tap(musicModeTap)

            assertTrue("$state", takenOver)
            assertEquals("$state", listOf(askedForTap("Tapped video", state.queueSize)), scene.journal.all)
        }
    }

    /** The controller is one object for the whole app: each tap has its own dialog, choice and actions. */
    @Test
    fun ac1_ac3_everyTapGetsItsOwnDialogAndItsChoiceGoesToItsOwnActions() {
        val scene = scene(playing(PlayerKind.BACKGROUND))
        val firstActions = RecordingTapActions(scene.journal, label = "first")
        val firstUi = FakeGuardUi(scene.journal, label = "first")
        val secondActions = RecordingTapActions(scene.journal, label = "second")
        val secondUi = FakeGuardUi(scene.journal, label = "second")

        assertTrue(scene.controller.onVideoTap(tapOn(url = "$TAPPED_URL-1", title = "First"), firstActions, firstUi))
        firstUi.choose(GuardChoice.CANCEL)
        assertTrue(scene.controller.onVideoTap(tapOn(url = "$TAPPED_URL-2", title = "Second"), secondActions, secondUi))
        secondUi.choose(GuardChoice.ADD_TO_QUEUE)

        assertEquals(
            listOf(
                askedForTap("First", 3, label = "first"),
                askedForTap("Second", 3, label = "second"),
                "second.enqueue",
            ),
            scene.journal.all,
        )
    }

    /** AC-2: the dialog shows "the title of the tapped video, the number of items in the queue". */
    @Test
    fun ac2_dialogGetsTheTitleOfTheTappedVideoAndTheNumberOfItemsInTheQueue() {
        val scene = scene(playing(PlayerKind.POPUP, queueSize = 7))

        scene.tap(tapOn(title = "How to fold a paper crane"))

        assertEquals(
            listOf(FakeGuardUi.TapPrompt(title = "How to fold a paper crane", queueSize = 7)),
            scene.ui.tapPrompts,
        )
        assertEquals("the tap form, not the list form", emptyList<FakeGuardUi.ListPrompt>(), scene.ui.listPrompts)
    }

    /**
     * AC-2 for every player from a queue of one on. Answer A11 had made the main player start at two;
     * AM-1 withdraws that (plan A2.10: "uses one item for the main player again").
     */
    @Test
    fun ac2_dialogGetsTheQueueSizeOfEachPlayer() {
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(1, 2, 40)) {
                val scene = scene(playing(kind, queueSize = queueSize))

                scene.tap(tap)

                assertEquals(
                    "$kind, queue of $queueSize",
                    listOf(FakeGuardUi.TapPrompt("Tapped video", queueSize)),
                    scene.ui.tapPrompts,
                )
            }
        }
    }

    /** AC-3: the result equals the long-press action "Enqueue". */
    @Test
    fun ac3_addToQueueCallsOnlyEnqueue() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.tap(tap)

            scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

            assertEquals("$state", listOf(askedForTap("Tapped video", state.queueSize), "enqueue"), scene.journal.all)
        }
    }

    /** AC-4: the result equals the long-press action "Enqueue next". */
    @Test
    fun ac4_playNextCallsOnlyEnqueueNext() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.tap(tap)

            scene.ui.choose(GuardChoice.PLAY_NEXT)

            assertEquals(
                "$state",
                listOf(askedForTap("Tapped video", state.queueSize), "enqueueNext"),
                scene.journal.all,
            )
        }
    }

    /**
     * AC-5, main player: Play now is today's tap. `openAsToday()` replays upstream's tap handler, which
     * reaches the hook again; that one call must be let through (`false`), or the dialog would open
     * forever and the page never.
     */
    @Test
    fun ac5_playNowInMainPlayerReplaysTheTapAndLetsTheReplayThrough() {
        val state = playing(PlayerKind.MAIN)
        val scene = scene(state)
        val replay = replayOnOpenAsToday(scene, tap)
        scene.tap(tap)

        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(
            "one dialog, then today's tap; the replay asks nothing and performs nothing",
            listOf(askedForTap("Tapped video", 3), "openAsToday"),
            scene.journal.all,
        )
        assertEquals(
            "the replayed tap must return false so that upstream's own code runs",
            listOf(false),
            replay.answers,
        )
    }

    @Test
    fun ac5_playNowInBackgroundPlayerCallsOnlyStartInBackground() {
        for (queueSize in listOf(1, 3)) {
            val scene = scene(playing(PlayerKind.BACKGROUND, queueSize = queueSize))
            scene.tap(tap)

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals(
                "queue of $queueSize",
                listOf(askedForTap("Tapped video", queueSize), "startInBackground"),
                scene.journal.all,
            )
        }
    }

    @Test
    fun ac5_playNowInPopupPlayerCallsOnlyStartInPopup() {
        for (queueSize in listOf(1, 3)) {
            val scene = scene(playing(PlayerKind.POPUP, queueSize = queueSize))
            scene.tap(tap)

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals(
                "queue of $queueSize",
                listOf(askedForTap("Tapped video", queueSize), "startInPopup"),
                scene.journal.all,
            )
        }
    }

    /** AC-5, Music Player Mode: Play now is what a tap does there today, "whichever player is in use". */
    @Test
    fun ac5_playNowWithMusicPlayerModeReplaysTheTapInEveryPlayer() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            val replay = replayOnOpenAsToday(scene, musicModeTap)
            scene.tap(musicModeTap)

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals(
                "$state",
                listOf(askedForTap("Tapped video", state.queueSize), "openAsToday"),
                scene.journal.all,
            )
            assertEquals("$state: the replayed tap must be let through", listOf(false), replay.answers)
        }
    }

    /** Plan 3.2: the choice is decided with the player state "read at that moment", not the one of the tap. */
    @Test
    fun ac5_playNowGoesToThePlayerThatIsInUseWhenTheUserChooses() {
        val scene = scene(playing(PlayerKind.BACKGROUND))
        scene.tap(tap)
        scene.player.state = playing(PlayerKind.POPUP)

        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForTap("Tapped video", 3), "startInPopup"), scene.journal.all)
    }

    /** The replay is let through only while `openAsToday()` runs. The tap after it is guarded again. */
    @Test
    fun ac5_ac1_afterTheReplayTheNextTapIsGuardedAgain() {
        val scene = scene(playing(PlayerKind.MAIN))
        replayOnOpenAsToday(scene, tap)
        scene.tap(tap)
        scene.ui.choose(GuardChoice.PLAY_NOW)

        val nextActions = RecordingTapActions(scene.journal, label = "next")
        val nextUi = FakeGuardUi(scene.journal, label = "next")
        val nextTap = tapOn(url = "$TAPPED_URL-next", title = "Next video")
        val takenOver = scene.controller.onVideoTap(nextTap, nextActions, nextUi)

        assertTrue("a tap after the replay is an ordinary tap again", takenOver)
        assertEquals(
            listOf(askedForTap("Tapped video", 3), "openAsToday", askedForTap("Next video", 3, label = "next")),
            scene.journal.all,
        )
    }

    /**
     * Plan 3.2 and section 8: the replay flag "must be cleared in `finally`". If upstream's tap handler
     * throws, the guard must not stay switched off for the rest of the app's life. Whether the exception
     * reaches the caller is not part of this test.
     */
    @Test
    fun ac5_ac1_guardStaysOnAfterTodaysTapHandlerThrows() {
        val scene = scene(playing(PlayerKind.MAIN))
        scene.tapActions.duringOpenAsToday = { throw IllegalStateException("upstream tap handler failed") }
        scene.tap(tap)
        try {
            scene.ui.choose(GuardChoice.PLAY_NOW)
        } catch (expected: IllegalStateException) {
            assertEquals("upstream tap handler failed", expected.message)
        }

        val nextActions = RecordingTapActions(scene.journal, label = "next")
        val nextUi = FakeGuardUi(scene.journal, label = "next")
        val nextTap = tapOn(url = "$TAPPED_URL-next", title = "Next video")
        val takenOver = scene.controller.onVideoTap(nextTap, nextActions, nextUi)

        assertTrue("the replay flag was not cleared: the next tap slipped through", takenOver)
        assertEquals(listOf(FakeGuardUi.TapPrompt("Next video", 3)), nextUi.tapPrompts)
    }

    /** AC-6: "Cancel ... changes nothing." */
    @Test
    fun ac6_cancelCallsNoAction() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.tap(tap)

            scene.ui.choose(GuardChoice.CANCEL)

            assertEquals("$state", listOf(askedForTap("Tapped video", state.queueSize)), scene.journal.all)
        }
    }

    /** AC-6: Back, a tap outside and a rotation dismiss the dialog without any callback (plan 3.2, `GuardUi`). */
    @Test
    fun ac6_dismissalWithoutCallbackCallsNoActionAndLeavesTheGuardWorking() {
        val scene = scene(playing(PlayerKind.BACKGROUND))
        scene.tap(tap)
        // the dialog is dismissed: nobody calls onChoice

        assertEquals(listOf(askedForTap("Tapped video", 3)), scene.journal.all)

        val nextActions = RecordingTapActions(scene.journal, label = "next")
        val nextUi = FakeGuardUi(scene.journal, label = "next")
        assertTrue(scene.controller.onVideoTap(tapOn(title = "Next video"), nextActions, nextUi))
        nextUi.choose(GuardChoice.PLAY_NEXT)

        assertEquals(
            listOf(askedForTap("Tapped video", 3), askedForTap("Next video", 3, label = "next"), "next.enqueueNext"),
            scene.journal.all,
        )
    }

    /** Plan 3.2: "A second call of `cb` is ignored." */
    @Test
    fun ac3_onlyTheFirstChoiceOfADialogCounts() {
        val scene = scene(playing(PlayerKind.BACKGROUND))
        scene.tap(tap)

        scene.ui.choose(GuardChoice.ADD_TO_QUEUE)
        scene.ui.choose(GuardChoice.ADD_TO_QUEUE)
        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForTap("Tapped video", 3), "enqueue"), scene.journal.all)
    }

    /** Plan 3.2: "A second call of `cb` is ignored", also when the first one was *Cancel*. */
    @Test
    fun ac6_nothingHappensAfterCancelEvenIfTheDialogCallsBackAgain() {
        val scene = scene(playing(PlayerKind.POPUP))
        scene.tap(tap)

        scene.ui.choose(GuardChoice.CANCEL)
        scene.ui.choose(GuardChoice.PLAY_NOW)
        scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

        assertEquals(listOf(askedForTap("Tapped video", 3)), scene.journal.all)
    }

    /** AC-7: "a video tap behaves as today and no dialog appears": `false`, upstream's code goes on. */
    @Test
    fun ac7_tapWhileNothingIsPlayingIsNotTakenOver() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (state in NOTHING_PLAYING) {
                for (request in listOf(tap, musicModeTap)) {
                    val scene = scene(state)

                    val takenOver = scene.tap(request)

                    assertFalse("switch $ask, $state, $request", takenOver)
                    assertEquals("switch $ask, $state, $request", emptyList<String>(), scene.journal.all)
                }
            }
        }
    }

    /** AC-8: "A video tap on the current item behaves as today and no dialog appears." */
    @Test
    fun ac8_tapOnTheCurrentItemIsNotTakenOver() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (state in SOMETHING_PLAYING) {
                val scene = scene(state)

                val takenOver = scene.tap(tapOnCurrent())

                assertFalse("switch $ask, $state", takenOver)
                assertEquals("switch $ask, $state", emptyList<String>(), scene.journal.all)
            }
        }
    }

    /** AC-9: "Add to queue ... do[es] what Enqueue does today when nothing is playing." */
    @Test
    fun ac9_addToQueueAfterThePlayerWasClosedCallsOnlyEnqueue() {
        for (state in SOMETHING_PLAYING) {
            for (closed in NOTHING_PLAYING) {
                val scene = scene(state)
                scene.tap(tap)
                scene.player.state = closed

                scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

                assertEquals(
                    "$state -> $closed",
                    listOf(askedForTap("Tapped video", state.queueSize), "enqueue"),
                    scene.journal.all,
                )
            }
        }
    }

    /** AC-9: *Play next* becomes "Enqueue" too. "Enqueue next" has no current item to follow. */
    @Test
    fun ac9_playNextAfterThePlayerWasClosedCallsEnqueueInsteadOfEnqueueNext() {
        for (state in SOMETHING_PLAYING) {
            for (closed in NOTHING_PLAYING) {
                val scene = scene(state)
                scene.tap(tap)
                scene.player.state = closed

                scene.ui.choose(GuardChoice.PLAY_NEXT)

                assertEquals(
                    "$state -> $closed",
                    listOf(askedForTap("Tapped video", state.queueSize), "enqueue"),
                    scene.journal.all,
                )
            }
        }
    }

    /**
     * AC-9: "Play now does what a video tap does while nothing is playing": today's tap, in every player
     * the dialog was opened for. The replayed tap is let through, and with nothing playing it would not
     * be guarded anyway.
     */
    @Test
    fun ac9_playNowAfterThePlayerWasClosedReplaysTheTapAsToday() {
        for (state in SOMETHING_PLAYING) {
            for (closed in NOTHING_PLAYING) {
                val scene = scene(state)
                val replay = replayOnOpenAsToday(scene, tap)
                scene.tap(tap)
                scene.player.state = closed

                scene.ui.choose(GuardChoice.PLAY_NOW)

                assertEquals(
                    "$state -> $closed",
                    listOf(askedForTap("Tapped video", state.queueSize), "openAsToday"),
                    scene.journal.all,
                )
                assertEquals("$state -> $closed", listOf(false), replay.answers)
            }
        }
    }

    /** AC-16, main player: Play now directly is today's tap, so the tap is simply not taken over. */
    @Test
    fun ac16_switchOff_tapInMainPlayerIsNotTakenOver() {
        settings.ask = false
        for (queueSize in listOf(1, 3)) {
            val scene = scene(playing(PlayerKind.MAIN, queueSize = queueSize))

            val takenOver = scene.tap(tap)

            assertFalse("queue of $queueSize", takenOver)
            assertEquals("queue of $queueSize", emptyList<String>(), scene.journal.all)
        }
    }

    @Test
    fun ac16_switchOff_tapInBackgroundPlayerStartsInBackgroundWithoutDialog() {
        settings.ask = false
        for (queueSize in listOf(1, 3)) {
            val scene = scene(playing(PlayerKind.BACKGROUND, queueSize = queueSize))

            val takenOver = scene.tap(tap)

            assertTrue("queue of $queueSize: taken over, or the page would open as well", takenOver)
            assertEquals("queue of $queueSize", listOf("startInBackground"), scene.journal.all)
        }
    }

    @Test
    fun ac16_switchOff_tapInPopupPlayerStartsInPopupWithoutDialog() {
        settings.ask = false
        for (queueSize in listOf(1, 3)) {
            val scene = scene(playing(PlayerKind.POPUP, queueSize = queueSize))

            val takenOver = scene.tap(tap)

            assertTrue("queue of $queueSize: taken over, or the page would open as well", takenOver)
            assertEquals("queue of $queueSize", listOf("startInPopup"), scene.journal.all)
        }
    }

    /** AC-16 with Music Player Mode: Play now is today's tap in every player, so nothing is taken over. */
    @Test
    fun ac16_switchOff_tapWithMusicPlayerModeIsNotTakenOver() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            val takenOver = scene.tap(musicModeTap)

            assertFalse("$state", takenOver)
            assertEquals("$state", emptyList<String>(), scene.journal.all)
        }
    }

    // =============================================================================================
    // Play buttons
    // =============================================================================================

    /** AC-10: "pressing a play button opens the dialog in its list form." Nothing plays before a choice. */
    @Test
    fun ac10_playButtonWhileSomethingIsPlayingOnlyOpensTheListDialog() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            scene.pressPlayButton(videoCount = 12)

            assertEquals("$state", listOf(askedForList(12, state.queueSize)), scene.journal.all)
            assertEquals("$state: no action before a choice", emptyList<String>(), scene.listActions.calls)
        }
    }

    /** AC-10: "It shows how many videos the list would add, the number of items in the queue." */
    @Test
    fun ac10_listDialogGetsTheNumberOfVideosAndTheNumberOfItemsInTheQueue() {
        val scene = scene(playing(PlayerKind.MAIN, queueSize = 5))

        scene.pressPlayButton(videoCount = 148)

        assertEquals(listOf(FakeGuardUi.ListPrompt(videoCount = 148, queueSize = 5)), scene.ui.listPrompts)
        assertEquals("the list form, not the tap form", emptyList<FakeGuardUi.TapPrompt>(), scene.ui.tapPrompts)
    }

    @Test
    fun ac10_listOfOneVideoAsksToo() {
        val scene = scene(playing(PlayerKind.BACKGROUND, queueSize = 1))

        scene.pressPlayButton(videoCount = 1)

        assertEquals(listOf(askedForList(1, 1)), scene.journal.all)
    }

    /** AC-10 and answer A8: "A play button on a list with no videos loaded behaves as today, without a dialog." */
    @Test
    fun ac10_playButtonOnAListWithNoVideosPlaysAsTodayWithoutDialog() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            scene.pressPlayButton(videoCount = 0)

            assertEquals("$state", listOf("playAsToday"), scene.journal.all)
        }
    }

    /** Plan 3.2: `videoCount <= 0`. */
    @Test
    fun ac10_negativeVideoCountIsTreatedAsAnEmptyList() {
        val scene = scene(playing(PlayerKind.POPUP))

        scene.pressPlayButton(videoCount = -1)

        assertEquals(listOf("playAsToday"), scene.journal.all)
    }

    /** AC-11: *Add all to queue*. */
    @Test
    fun ac11_addAllToQueueCallsOnlyEnqueueAll() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.pressPlayButton(videoCount = 12)

            scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

            assertEquals("$state", listOf(askedForList(12, state.queueSize), "enqueueAll"), scene.journal.all)
        }
    }

    /** AC-11: *Play all next*. */
    @Test
    fun ac11_playAllNextCallsOnlyEnqueueAllNext() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.pressPlayButton(videoCount = 12)

            scene.ui.choose(GuardChoice.PLAY_NEXT)

            assertEquals("$state", listOf(askedForList(12, state.queueSize), "enqueueAllNext"), scene.journal.all)
        }
    }

    /** AC-11, last sentence, and answer A5: with the player closed, *Add all* is "Enqueue" of the list. */
    @Test
    fun ac11_addAllToQueueAfterThePlayerWasClosedCallsOnlyEnqueueAll() {
        for (state in SOMETHING_PLAYING) {
            for (closed in NOTHING_PLAYING) {
                val scene = scene(state)
                scene.pressPlayButton(videoCount = 12)
                scene.player.state = closed

                scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

                assertEquals(
                    "$state -> $closed",
                    listOf(askedForList(12, state.queueSize), "enqueueAll"),
                    scene.journal.all,
                )
            }
        }
    }

    /** AC-11, last sentence, and answer A5: with the player closed, *Play all next* is "Enqueue" as well. */
    @Test
    fun ac11_playAllNextAfterThePlayerWasClosedCallsEnqueueAllInsteadOfEnqueueAllNext() {
        for (state in SOMETHING_PLAYING) {
            for (closed in NOTHING_PLAYING) {
                val scene = scene(state)
                scene.pressPlayButton(videoCount = 12)
                scene.player.state = closed

                scene.ui.choose(GuardChoice.PLAY_NEXT)

                assertEquals(
                    "$state -> $closed",
                    listOf(askedForList(12, state.queueSize), "enqueueAll"),
                    scene.journal.all,
                )
            }
        }
    }

    /** AC-12: "Replace queue does what the pressed button does today." */
    @Test
    fun ac12_replaceQueueCallsOnlyPlayAsToday() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.pressPlayButton(videoCount = 12)

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals("$state", listOf(askedForList(12, state.queueSize), "playAsToday"), scene.journal.all)
        }
    }

    /** AC-12 and answer A5: the same with the player closed in the meantime. */
    @Test
    fun ac12_replaceQueueAfterThePlayerWasClosedCallsOnlyPlayAsToday() {
        for (state in SOMETHING_PLAYING) {
            for (closed in NOTHING_PLAYING) {
                val scene = scene(state)
                scene.pressPlayButton(videoCount = 12)
                scene.player.state = closed

                scene.ui.choose(GuardChoice.PLAY_NOW)

                assertEquals(
                    "$state -> $closed",
                    listOf(askedForList(12, state.queueSize), "playAsToday"),
                    scene.journal.all,
                )
            }
        }
    }

    /** AC-13: "Cancel ... change[s] nothing." */
    @Test
    fun ac13_cancelInTheListDialogCallsNoAction() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.pressPlayButton(videoCount = 12)

            scene.ui.choose(GuardChoice.CANCEL)

            assertEquals("$state", listOf(askedForList(12, state.queueSize)), scene.journal.all)
        }
    }

    /** AC-13: the other dismissals call nothing back. */
    @Test
    fun ac13_dismissalOfTheListDialogWithoutCallbackCallsNoActionAndLeavesTheGuardWorking() {
        val scene = scene(playing(PlayerKind.MAIN))
        scene.pressPlayButton(videoCount = 12)
        // the dialog is dismissed: nobody calls onChoice

        assertEquals(listOf(askedForList(12, 3)), scene.journal.all)

        scene.pressPlayButton(videoCount = 4)
        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForList(12, 3), askedForList(4, 3), "playAsToday"), scene.journal.all)
    }

    /**
     * Assumption, asked as Q10 (tests -> plan): the list dialog's callback is guarded like the tap
     * dialog's, i.e. only its first call counts. Plan 3.2 says "A second call of `cb` is ignored" in the
     * `onVideoTap` paragraph only; the `GuardUi` contract ("onChoice: at most once") covers both forms.
     */
    @Test
    fun ac11_ac12_onlyTheFirstChoiceOfAListDialogCounts() {
        val scene = scene(playing(PlayerKind.BACKGROUND))
        scene.pressPlayButton(videoCount = 12)

        scene.ui.choose(GuardChoice.ADD_TO_QUEUE)
        scene.ui.choose(GuardChoice.ADD_TO_QUEUE)
        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForList(12, 3), "enqueueAll"), scene.journal.all)
    }

    /** Same assumption as above (Q10), first call *Cancel*: AC-13 "changes nothing" must hold afterwards. */
    @Test
    fun ac13_nothingHappensAfterCancelEvenIfTheListDialogCallsBackAgain() {
        val scene = scene(playing(PlayerKind.MAIN))
        scene.pressPlayButton(videoCount = 12)

        scene.ui.choose(GuardChoice.CANCEL)
        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForList(12, 3)), scene.journal.all)
    }

    /** AC-14: "While nothing is playing, the play buttons behave as today and no dialog appears." */
    @Test
    fun ac14_playButtonWhileNothingIsPlayingPlaysAsTodayAtOnce() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (state in NOTHING_PLAYING) {
                val scene = scene(state)

                scene.pressPlayButton(videoCount = 12)

                assertEquals("switch $ask, $state", listOf("playAsToday"), scene.journal.all)
            }
        }
    }

    /** AC-16: with the switch off "the play buttons behave as today". */
    @Test
    fun ac16_switchOff_playButtonPlaysAsTodayAtOnceInEveryPlayer() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            scene.pressPlayButton(videoCount = 12)

            assertEquals("$state", listOf("playAsToday"), scene.journal.all)
        }
    }

    // =============================================================================================
    // "Show stream details"
    // =============================================================================================

    /** AC-17: the entry is offered while the background or popup player is in use; read at every call. */
    @Test
    fun ac17_streamDetailsOfferFollowsThePlayerThatIsInUse() {
        val scene = scene(PlayerState.IDLE)
        assertFalse("nothing playing", scene.controller.offersStreamDetails())

        scene.player.state = playing(PlayerKind.BACKGROUND)
        assertTrue("background player", scene.controller.offersStreamDetails())

        scene.player.state = playing(PlayerKind.MAIN)
        assertFalse("main player", scene.controller.offersStreamDetails())

        scene.player.state = playing(PlayerKind.POPUP)
        assertTrue("popup player", scene.controller.offersStreamDetails())

        scene.player.state = openButEmpty(PlayerKind.POPUP)
        assertFalse("popup player without a queue", scene.controller.offersStreamDetails())

        assertEquals("asking opens no dialog and performs nothing", emptyList<String>(), scene.journal.all)
    }

    // =============================================================================================
    // A queue worth guarding, as amended (AM-1)
    // =============================================================================================

    private fun main(queueSize: Int) = playing(PlayerKind.MAIN, queueSize = queueSize)

    /**
     * The main player from a single video on: one video, and the lengths that used to stand for "it and
     * the auto-enqueued next ones" (the removed fixture `SINGLE_IN_MAIN`).
     */
    private val mainFromOneVideoOn = listOf(main(1), main(2), main(3))

    /**
     * INVERTED by AM-1. Was `ac7_tapWithASingleVideoInTheMainPlayerIsNotTakenOver` and expected `false`
     * and an untouched ui. Now the tap is taken over and only the dialog opens; for one video it shows
     * "1" (device script step 1: "the dialog opens and says 1 item in the queue").
     */
    @Test
    fun am1_tapWithASingleVideoInTheMainPlayerIsTakenOverAndOpensTheDialog() {
        for (state in mainFromOneVideoOn) {
            for (request in listOf(tap, musicModeTap)) {
                val scene = scene(state)

                val takenOver = scene.tap(request)

                assertTrue("$state, $request", takenOver)
                assertEquals(
                    "$state, $request",
                    listOf(askedForTap("Tapped video", state.queueSize)),
                    scene.journal.all,
                )
            }
        }
        val single = scene(main(1))
        single.tap(tap)
        assertEquals(listOf(FakeGuardUi.TapPrompt("Tapped video", 1)), single.ui.tapPrompts)
    }

    /**
     * INVERTED by AM-1. Was `ac14_playButtonWithASingleVideoInTheMainPlayerPlaysAsTodayAtOnce` and
     * expected `playAsToday` at once. Now the list dialog opens (AC-10) and nothing plays before a choice.
     */
    @Test
    fun am1_ac10_playButtonWithASingleVideoInTheMainPlayerOpensTheListDialog() {
        for (state in mainFromOneVideoOn) {
            val scene = scene(state)

            scene.pressPlayButton(videoCount = 12)

            assertEquals("$state", listOf(askedForList(12, state.queueSize)), scene.journal.all)
            assertEquals("$state: no action before a choice", emptyList<String>(), scene.listActions.calls)
        }
    }

    /**
     * INVERTED by AM-1. Was `ac1_ac7_stateIsReadAtEveryTap_theSecondVideoOfTheUsersBringsTheDialog`: one
     * video went through, the second brought the dialog. Now (plan A2.10) nothing playing: not taken
     * over; one video: dialog; nothing playing again: through again. The player is still read at every tap.
     */
    @Test
    fun am1_stateIsReadAtEveryTap_theFirstVideoBringsTheDialog() {
        val scene = scene(openButEmpty(PlayerKind.MAIN))

        assertFalse("nothing playing: not taken over", scene.tap(tap))
        assertEquals(emptyList<String>(), scene.journal.all)

        scene.player.state = main(1)
        assertTrue("one video: taken over", scene.tap(tap))
        assertEquals(listOf(askedForTap("Tapped video", 1)), scene.journal.all)
        scene.ui.choose(GuardChoice.CANCEL)

        scene.player.state = PlayerState.IDLE
        assertFalse("the player was closed: not taken over", scene.tap(tap))
        assertEquals(listOf(askedForTap("Tapped video", 1)), scene.journal.all)
    }

    /**
     * AM-1, the opposite of the removed
     * `ac1_ac7_ac10_ac14_stateIsReadAtEveryTapAndPress_autoQueuedVideosNeverBringTheDialog`: while the
     * main player's queue grows by itself behind one chosen video, every tap and every press asks, and
     * the dialog shows the whole queue (AC-2, AC-10).
     */
    @Test
    fun am1_ac2_ac10_everyTapAndPressAsksWhileTheQueueGrowsByItself() {
        val scene = scene(main(1))
        val expected = mutableListOf<String>()
        for (queueSize in 1..4) {
            scene.player.state = main(queueSize)
            assertTrue("queue of $queueSize", scene.tap(tap))
            scene.pressPlayButton(videoCount = 12)
            expected += askedForTap("Tapped video", queueSize)
            expected += askedForList(12, queueSize)
        }
        assertEquals(expected, scene.journal.all)
    }

    // Rows 8 to 12 judge a choice on "playing" at the moment it is made (plan A2.2). Here the dialog
    // opens in the main player with three videos, and the player is down to a single video when the user
    // chooses. Plan A2.10: these eight tests "keep their results on plain states".

    private val downToOneVideo = listOf(main(1))

    /** AC-3: *Add to queue* is still "Enqueue". */
    @Test
    fun ac3_addToQueueCallsOnlyEnqueueWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            scene.tap(tap)
            scene.player.state = later

            scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

            assertEquals("-> $later", listOf(askedForTap("Tapped video", 3), "enqueue"), scene.journal.all)
        }
    }

    /** AC-4: *Play next* is still "Enqueue next": something is playing, so there is a current item. */
    @Test
    fun ac4_playNextCallsOnlyEnqueueNextWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            scene.tap(tap)
            scene.player.state = later

            scene.ui.choose(GuardChoice.PLAY_NEXT)

            assertEquals("-> $later", listOf(askedForTap("Tapped video", 3), "enqueueNext"), scene.journal.all)
        }
    }

    /**
     * AC-5: *Play now* is today's tap; the replayed tap is let through and opens no second dialog,
     * although a single video in the main player would now ask (AM-1) if it were an ordinary tap.
     */
    @Test
    fun ac5_playNowReplaysTheTapWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            val replay = replayOnOpenAsToday(scene, tap)
            scene.tap(tap)
            scene.player.state = later

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals("-> $later", listOf(askedForTap("Tapped video", 3), "openAsToday"), scene.journal.all)
            assertEquals("-> $later: the replayed tap must be let through", listOf(false), replay.answers)
        }
    }

    /** AC-5: a dialog opened in the background player, the player switched to main with one video since. */
    @Test
    fun ac5_playNowReplaysTheTapWhenThePlayerSwitchedToMainWithOneVideoAtTheChoice() {
        val scene = scene(playing(PlayerKind.BACKGROUND))
        val replay = replayOnOpenAsToday(scene, tap)
        scene.tap(tap)
        scene.player.state = main(1)

        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForTap("Tapped video", 3), "openAsToday"), scene.journal.all)
        assertEquals(listOf(false), replay.answers)
    }

    /** AC-6: *Cancel* still changes nothing. */
    @Test
    fun ac6_cancelCallsNoActionWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            scene.tap(tap)
            scene.player.state = later

            scene.ui.choose(GuardChoice.CANCEL)

            assertEquals("-> $later", listOf(askedForTap("Tapped video", 3)), scene.journal.all)
        }
    }

    /** AC-11: *Add all to queue* is still carried out. */
    @Test
    fun ac11_addAllToQueueCallsOnlyEnqueueAllWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            scene.pressPlayButton(videoCount = 12)
            scene.player.state = later

            scene.ui.choose(GuardChoice.ADD_TO_QUEUE)

            assertEquals("-> $later", listOf(askedForList(12, 3), "enqueueAll"), scene.journal.all)
        }
    }

    /** AC-11: *Play all next* stays "all next": something is playing, so there is a current item. */
    @Test
    fun ac11_playAllNextCallsOnlyEnqueueAllNextWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            scene.pressPlayButton(videoCount = 12)
            scene.player.state = later

            scene.ui.choose(GuardChoice.PLAY_NEXT)

            assertEquals("-> $later", listOf(askedForList(12, 3), "enqueueAllNext"), scene.journal.all)
        }
    }

    /** AC-12: *Replace queue* is the pressed button as today, without a second dialog. */
    @Test
    fun ac12_replaceQueueCallsOnlyPlayAsTodayWhenTheMainPlayerIsDownToOneVideoAtTheChoice() {
        for (later in downToOneVideo) {
            val scene = scene(main(3))
            scene.pressPlayButton(videoCount = 12)
            scene.player.state = later

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals("-> $later", listOf(askedForList(12, 3), "playAsToday"), scene.journal.all)
        }
    }

    /**
     * AC-16: with the switch off a tap in the main player is today's tap, whatever its queue. Was
     * `ac16_switchOff_tapInMainPlayerIsNotTakenOverWhetherTheQueueIsWorthGuardingOrNot`; same result.
     */
    @Test
    fun ac16_switchOff_tapInMainPlayerIsNotTakenOverWhateverItsQueue() {
        settings.ask = false
        for (state in mainFromOneVideoOn + main(5)) {
            val scene = scene(state)

            assertFalse("$state", scene.tap(tap))
            scene.pressPlayButton(videoCount = 12)

            assertEquals("$state", listOf("playAsToday"), scene.journal.all)
        }
    }

    // =============================================================================================
    // Amendment 2: the related list (AM-2)
    // =============================================================================================

    /**
     * AM-2: "a video tap in the list of related videos on the video page of the current item opens the
     * video as today, without a dialog": the hook gets `false`, nothing else is called.
     */
    @Test
    fun am2_relatedTapOnThePageOfTheCurrentItemIsNotTakenOver() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            val takenOver = scene.tap(relatedTap(pageOf = CURRENT_VIDEO))

            assertFalse("$state", takenOver)
            assertEquals("$state", emptyList<String>(), scene.journal.all)
        }
    }

    /** AM-2 is one exception: the related list on another video's page asks (device script step 2). */
    @Test
    fun am2_relatedTapOnThePageOfAnotherVideoOpensTheDialog() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            val takenOver = scene.tap(relatedTap(pageOf = OTHER_VIDEO))

            assertTrue("$state", takenOver)
            assertEquals("$state", listOf(askedForTap("Tapped video", state.queueSize)), scene.journal.all)
        }
    }

    /**
     * AM-2 with the switch off (answer A18): the tap is played now. Beside the background or popup player
     * the video starts there and the tap is taken over; in the main player it is today's tap.
     */
    @Test
    fun am2_ac16_switchOff_relatedTapOnThePageOfTheCurrentItemIsPlayedNow() {
        settings.ask = false
        val expected = mapOf(
            PlayerKind.MAIN to (false to emptyList<String>()),
            PlayerKind.BACKGROUND to (true to listOf("startInBackground")),
            PlayerKind.POPUP to (true to listOf("startInPopup")),
        )
        for ((kind, outcome) in expected) {
            val scene = scene(playing(kind))

            val takenOver = scene.tap(relatedTap(pageOf = CURRENT_VIDEO))

            assertEquals("$kind: taken over", outcome.first, takenOver)
            assertEquals("$kind", outcome.second, scene.journal.all)
        }
    }

    // =============================================================================================
    // Amendment 2: page controls (AM-3, AM-9)
    // =============================================================================================

    private val pageControl = requestBy(OpenWay.PAGE_CONTROL, title = "Video of the page")

    /**
     * AM-3: on the page of a video that is not the current item a play control "open[s] the video-tap
     * dialog for that video", with its title, in every player. Until the user chooses nothing else
     * happens (AC-1): the hook gets `true` and skips the control's own statements.
     */
    @Test
    fun am3_pageControlOfAnotherVideoIsTakenOverAndOnlyOpensTheDialog() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            val takenOver = scene.tap(pageControl)

            assertTrue("$state", takenOver)
            assertEquals("$state", listOf(askedForTap("Video of the page", state.queueSize)), scene.journal.all)
            assertEquals("$state: the tap form", emptyList<FakeGuardUi.ListPrompt>(), scene.ui.listPrompts)
        }
    }

    /**
     * AM-3: "*Play now* does what the pressed control does today": `openAsToday()`, the replay of the
     * control, in every player; never a direct start in the player in use. The replay reaches the hook
     * again and that call returns `false`, so the control's own statements run.
     */
    @Test
    fun am3_playNowOfAPageControlReplaysTheControlInEveryPlayerAndLetsTheReplayThrough() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            val replay = replayOnOpenAsToday(scene, pageControl)
            scene.tap(pageControl)

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals(
                "$state",
                listOf(askedForTap("Video of the page", state.queueSize), "openAsToday"),
                scene.journal.all,
            )
            assertEquals("$state: the replayed control must be let through", listOf(false), replay.answers)
        }
    }

    /** AM-3 with AC-9: *Play now* after the player was closed is the control as today as well. */
    @Test
    fun am3_ac9_playNowOfAPageControlAfterThePlayerWasClosedReplaysTheControl() {
        for (closed in NOTHING_PLAYING) {
            val scene = scene(playing(PlayerKind.BACKGROUND))
            val replay = replayOnOpenAsToday(scene, pageControl)
            scene.tap(pageControl)
            scene.player.state = closed

            scene.ui.choose(GuardChoice.PLAY_NOW)

            assertEquals("-> $closed", listOf(askedForTap("Video of the page", 3), "openAsToday"), scene.journal.all)
            assertEquals("-> $closed", listOf(false), replay.answers)
        }
    }

    /** AM-3 with AC-3 and AC-4: *Add to queue* and *Play next* call only `enqueue()` / `enqueueNext()`. */
    @Test
    fun am3_ac3_ac4_addToQueueAndPlayNextOfAPageControlCallOnlyEnqueueAndEnqueueNext() {
        for (state in SOMETHING_PLAYING) {
            val adding = scene(state)
            adding.tap(pageControl)
            adding.ui.choose(GuardChoice.ADD_TO_QUEUE)
            assertEquals(
                "$state, add to queue",
                listOf(askedForTap("Video of the page", state.queueSize), "enqueue"),
                adding.journal.all,
            )

            val next = scene(state)
            next.tap(pageControl)
            next.ui.choose(GuardChoice.PLAY_NEXT)
            assertEquals(
                "$state, play next",
                listOf(askedForTap("Video of the page", state.queueSize), "enqueueNext"),
                next.journal.all,
            )
        }
    }

    /** AM-3 with AC-6: *Cancel* on the dialog of a page control changes nothing. */
    @Test
    fun am3_ac6_cancelOfAPageControlCallsNoAction() {
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)
            scene.tap(pageControl)

            scene.ui.choose(GuardChoice.CANCEL)

            assertEquals("$state", listOf(askedForTap("Video of the page", state.queueSize)), scene.journal.all)
        }
    }

    /**
     * AM-7: with the switch off a page control is not taken over in any player (`false`, nothing
     * called), also beside the background or popup player, where a list tap would be played now.
     */
    @Test
    fun am7_switchOff_pageControlIsNotTakenOver() {
        settings.ask = false
        for (state in SOMETHING_PLAYING) {
            val scene = scene(state)

            assertFalse("$state", scene.tap(pageControl))

            assertEquals("$state", emptyList<String>(), scene.journal.all)
        }
    }

    /**
     * AM-3, last sentence, and AM-9: "On the page of the current item these controls behave as today":
     * the hook gets `false` whatever the switch and the queue length, so the control's own statements
     * run (and hook P5 hands them the player's queue).
     */
    @Test
    fun am3_am9_pageControlOnThePageOfTheCurrentItemIsNotTakenOver() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (state in SOMETHING_PLAYING + playing(PlayerKind.BACKGROUND, queueSize = 40)) {
                val scene = scene(state)

                assertFalse("switch $ask, $state", scene.tap(requestForCurrentBy(OpenWay.PAGE_CONTROL)))

                assertEquals("switch $ask, $state", emptyList<String>(), scene.journal.all)
            }
        }
    }

    /** AM-1: a page control while nothing is playing is not taken over. */
    @Test
    fun am1_pageControlWhileNothingIsPlayingIsNotTakenOver() {
        for (state in NOTHING_PLAYING) {
            val scene = scene(state)

            assertFalse("$state", scene.tap(pageControl))

            assertEquals("$state", emptyList<String>(), scene.journal.all)
        }
    }

    // =============================================================================================
    // Amendment 2: links, typed addresses, the router (AM-5, AM-6, AM-7)
    // =============================================================================================

    private val address = "https://video.example/watch?v=linked"

    /** A request that knows no title: the dialog shows the address (answer A20, plan A2.6). */
    private fun linkBy(way: OpenWay) = requestBy(way, url = address, title = address)

    /**
     * AM-5, AM-6: with the main player, a timed link, a typed address and "show info" / "video player"
     * for a video of another app open the dialog. Spec: "the dialog shows the address where it shows a
     * title for a list item".
     */
    @Test
    fun am5_am6_openingAVideoPageInTheMainPlayerOpensTheDialogWithTheAddress() {
        for (queueSize in listOf(1, 3)) {
            val scene = scene(main(queueSize))

            val takenOver = scene.tap(linkBy(OpenWay.VIDEO_PAGE))

            assertTrue("queue of $queueSize", takenOver)
            assertEquals("queue of $queueSize", listOf(askedForTap(address, queueSize)), scene.journal.all)
        }
    }

    /**
     * AM-5 and AM-6, one rule (plan A2.10: "A `VIDEO_PAGE` request beside the background player returns
     * `false`"): beside the background or popup player the page opens as today, without a dialog.
     */
    @Test
    fun am5_am6_openingAVideoPageBesideTheBackgroundOrPopupPlayerIsNotTakenOver() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 3)) {
                val scene = scene(playing(kind, queueSize = queueSize))

                assertFalse("$kind, queue of $queueSize", scene.tap(linkBy(OpenWay.VIDEO_PAGE)))

                assertEquals("$kind, queue of $queueSize", emptyList<String>(), scene.journal.all)
            }
        }
    }

    /**
     * AM-6: "background player" and "popup player" chosen for a video of another app open the dialog in every player.
     */
    @Test
    fun am6_playerActionOfAnotherAppOpensTheDialogInEveryPlayer() {
        for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
            for (state in SOMETHING_PLAYING) {
                val scene = scene(state)

                assertTrue("$way, $state", scene.tap(linkBy(way)))

                assertEquals("$way, $state", listOf(askedForTap(address, state.queueSize)), scene.journal.all)
            }
        }
    }

    /**
     * AM-5, AM-6: the three choices for a link or a router action. *Play now* is "what the path does
     * today" (answer A17): the replay, let through. *Add to queue* and *Play next* only enqueue.
     */
    @Test
    fun am5_am6_choicesOfALinkOrRouterRequestReplayOrEnqueue() {
        for (way in listOf(OpenWay.VIDEO_PAGE, OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
            val request = linkBy(way)
            val asked = askedForTap(address, 3)

            val playNow = scene(main(3))
            val replay = replayOnOpenAsToday(playNow, request)
            playNow.tap(request)
            playNow.ui.choose(GuardChoice.PLAY_NOW)
            assertEquals("$way, play now", listOf(asked, "openAsToday"), playNow.journal.all)
            assertEquals("$way: the replay must be let through", listOf(false), replay.answers)

            val add = scene(main(3))
            add.tap(request)
            add.ui.choose(GuardChoice.ADD_TO_QUEUE)
            assertEquals("$way, add to queue", listOf(asked, "enqueue"), add.journal.all)

            val next = scene(main(3))
            next.tap(request)
            next.ui.choose(GuardChoice.PLAY_NEXT)
            assertEquals("$way, play next", listOf(asked, "enqueueNext"), next.journal.all)

            val cancel = scene(main(3))
            cancel.tap(request)
            cancel.ui.choose(GuardChoice.CANCEL)
            assertEquals("$way, cancel", listOf(asked), cancel.journal.all)
        }
    }

    /**
     * AM-6: *Play now* of "popup player" chosen in another app while the background player is in use
     * replays the router's action; it does not start the video in the background player, which is what
     * *Play now* of a list tap does there (AC-5).
     */
    @Test
    fun am6_playNowOfAPlayerActionOfAnotherAppIsNotRedirectedToThePlayerInUse() {
        val request = linkBy(OpenWay.POPUP_PLAYER)
        val scene = scene(playing(PlayerKind.BACKGROUND))
        scene.tap(request)

        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(askedForTap(address, 3), "openAsToday"), scene.journal.all)
    }

    /** AM-7: with the switch off "AM-3, AM-5 and AM-6 do not apply": no such request is taken over in any player. */
    @Test
    fun am7_switchOff_noLinkOrRouterRequestIsTakenOver() {
        settings.ask = false
        for (way in NOT_LIST_WAYS) {
            for (state in SOMETHING_PLAYING) {
                val scene = scene(state)

                assertFalse("$way, $state", scene.tap(linkBy(way)))

                assertEquals("$way, $state", emptyList<String>(), scene.journal.all)
            }
        }
    }

    /**
     * AM-5, AM-10, AM-11: a typed address, a link or "show info" for the current item is not taken over,
     * whatever the queue length and the switch: no dialog, and the page is opened as today (where P6
     * keeps the player).
     */
    @Test
    fun am5_am11_openingThePageOfTheCurrentItemIsNotTakenOver() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (state in SOMETHING_PLAYING) {
                val scene = scene(state)

                assertFalse("switch $ask, $state", scene.tap(requestForCurrentBy(OpenWay.VIDEO_PAGE)))

                assertEquals("switch $ask, $state", emptyList<String>(), scene.journal.all)
            }
        }
    }

    /**
     * AM-6 for the current item (plan A2.15): "background player" / "popup player" for the playing video
     * goes through while it is the whole queue, and asks when the queue is longer, since "a longer queue
     * would be replaced by the video alone".
     */
    @Test
    fun am6_playerActionOfAnotherAppForTheCurrentItemAsksOnlyWhenTheQueueIsLonger() {
        for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
            for (kind in ALL_KINDS) {
                val alone = scene(playing(kind, queueSize = 1))
                assertFalse("$way, $kind, the whole queue", alone.tap(requestForCurrentBy(way)))
                assertEquals("$way, $kind, the whole queue", emptyList<String>(), alone.journal.all)

                val longer = scene(playing(kind, queueSize = 3))
                assertTrue("$way, $kind, a longer queue", longer.tap(requestForCurrentBy(way)))
                assertEquals("$way, $kind, a longer queue", listOf(askedForTap("Tapped video", 3)), longer.journal.all)
            }
        }
    }
}
