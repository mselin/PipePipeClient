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
}
