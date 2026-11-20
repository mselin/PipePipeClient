package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.OpenWay
import org.schabi.newpipe.enhancements.queueguard.QueueGuardPolicy
import org.schabi.newpipe.enhancements.queueguard.TapRequest

/**
 * "Remember my choice" from the tap to the tap after it: the scenario of plan section 6, AC-8, "with one
 * in-memory fake that is `GuardSettings`, `OpenActionSettings` and `RememberStore`" ([InMemoryPreferences],
 * standing for the app's preferences), the combined policy reading it and queue-guard's controller on top.
 *
 * The Android dialog is replaced by its contract (QG plan 3.3): for a row click that is carried out, the
 * add-on is called "right before it" with the choice, then the choice goes to the controller; for
 * *Cancel* and every other dismissal the add-on is not called. [clickRow] plays the first, a bare
 * `dialog.choose(CANCEL)` or no call at all the second.
 *
 * Amendment 2 (spec AM-1, plan B2.3, B2.4): the add-on shows the notice "asking is now off" exactly when
 * `RememberChoice.onChoice` returns `true`. [Run.clickRow] counts those results in [Run.noticesDue], which
 * stands for the toasts shown. The ten tests of before stay; where they remember a choice or do not, they
 * now also say how many notices were due. New: `am1_...`.
 */
class RememberChoiceScenarioTest {

    private val nextVideo = tapOn(url = "https://video.example/watch?v=next", title = "Next video")

    /**
     * One run of the app: preferences, policy and controller, with something playing in [kind] and
     * [queueSize] items in its queue.
     */
    private class Run(
        kind: PlayerKind,
        val preferences: InMemoryPreferences = InMemoryPreferences(),
        private val queueSize: Int = 3,
    ) {
        val policy: GuardPolicy = OpenActionPolicy(QueueGuardPolicy(preferences), preferences, preferences)
        val scene = Scene(policy, playing(kind, queueSize = queueSize))

        /** How often the notice "asking is now off" was due: the `true` results of `onChoice` (AM-1). */
        var noticesDue = 0
            private set

        /** The user makes [request] and the dialog opens, as it must with ask on (AC-4, AM-2). */
        fun tapAndGetTheDialog(request: TapRequest = tapOn()) {
            assertTrue("the first tap must be taken over", scene.tap(request))
            assertEquals(
                "the first tap must only open the dialog",
                listOf(askedForTap(queueSize = queueSize)),
                scene.log.all,
            )
        }

        /**
         * The user clicks the row [choice] with the checkbox [checked]: add-on first, which shows the notice
         * if `onChoice` says so (plan B2.4), then the choice is carried out.
         */
        fun clickRow(choice: GuardChoice, checked: Boolean) {
            if (RememberChoice(preferences).onChoice(choice, checked)) {
                noticesDue++
            }
            scene.dialog.choose(choice)
        }
    }

    private val asked = askedForTap(queueSize = 3)
    private val askedForNextVideo = askedForTap(title = "Next video", queueSize = 3)

    // =============================================================================================
    // AC-8
    // =============================================================================================

    /**
     * Plan section 6, AC-8: "ask on, playing; tap -> prompt; `RememberChoice.onChoice(PLAY_NEXT, true)`,
     * then the prompt's callback -> only `enqueueNext()`; the fake holds `PLAY_NEXT` and ask off; a second
     * tap -> `enqueueNext()` without UI."
     */
    @Test
    fun ac8_rememberedPlayNextIsCarriedOutSavedAndDoneByTheNextTapWithoutDialog() {
        for (kind in ALL_KINDS) {
            val run = Run(kind)
            run.tapAndGetTheDialog()

            run.clickRow(GuardChoice.PLAY_NEXT, checked = true)

            assertEquals("$kind: the choice is carried out", listOf(asked, "enqueueNext"), run.scene.log.all)
            assertEquals("$kind: default action", DefaultTapAction.PLAY_NEXT, run.preferences.action)
            assertFalse("$kind: \"Ask before replacing the queue\" must be off", run.preferences.ask)
            assertEquals("$kind: saved once", 1, run.preferences.saves)
            assertEquals("$kind: the notice \"asking is now off\" is due once (AM-1)", 1, run.noticesDue)

            assertTrue("$kind: the second tap is taken over", run.scene.tap(nextVideo))
            assertEquals(
                "$kind: the second tap plays next without a dialog",
                listOf(asked, "enqueueNext", "enqueueNext"),
                run.scene.log.all,
            )
        }
    }

    /** Plan section 6, AC-8: "Same for the other two choices", here *Add to queue*. */
    @Test
    fun ac8_rememberedAddToQueueIsCarriedOutSavedAndDoneByTheNextTapWithoutDialog() {
        for (kind in ALL_KINDS) {
            val run = Run(kind)
            run.tapAndGetTheDialog()

            run.clickRow(GuardChoice.ADD_TO_QUEUE, checked = true)

            assertEquals("$kind: the choice is carried out", listOf(asked, "enqueue"), run.scene.log.all)
            assertEquals("$kind: default action", DefaultTapAction.ADD_TO_QUEUE, run.preferences.action)
            assertFalse("$kind: \"Ask before replacing the queue\" must be off", run.preferences.ask)
            assertEquals("$kind: saved once", 1, run.preferences.saves)
            assertEquals("$kind: the notice \"asking is now off\" is due once (AM-1)", 1, run.noticesDue)

            assertTrue("$kind: the second tap is taken over", run.scene.tap(nextVideo))
            assertEquals(
                "$kind: the second tap adds to the queue without a dialog",
                listOf(asked, "enqueue", "enqueue"),
                run.scene.log.all,
            )
        }
    }

    /**
     * *Play now* in the background and in the popup player. The run starts with *Add to queue* as default
     * action (unused while ask is on, AC-4), so that the saved *Play now* is a change.
     */
    @Test
    fun ac8_rememberedPlayNowIsCarriedOutSavedAndDoneByTheNextTapWithoutDialog() {
        val startCall = mapOf(PlayerKind.BACKGROUND to "startInBackground", PlayerKind.POPUP to "startInPopup")
        for ((kind, start) in startCall) {
            val run = Run(kind, InMemoryPreferences(ask = true, action = DefaultTapAction.ADD_TO_QUEUE))
            run.tapAndGetTheDialog()

            run.clickRow(GuardChoice.PLAY_NOW, checked = true)

            assertEquals("$kind: the choice is carried out", listOf(asked, start), run.scene.log.all)
            assertEquals("$kind: default action", DefaultTapAction.PLAY_NOW, run.preferences.action)
            assertFalse("$kind: \"Ask before replacing the queue\" must be off", run.preferences.ask)
            assertEquals("$kind: saved once", 1, run.preferences.saves)
            assertEquals("$kind: the notice \"asking is now off\" is due once (AM-1)", 1, run.noticesDue)

            assertTrue("$kind: the second tap is taken over", run.scene.tap(nextVideo))
            assertEquals(
                "$kind: the second tap plays now in the same player without a dialog",
                listOf(asked, start, start),
                run.scene.log.all,
            )
        }
    }

    /**
     * *Play now* in the main player is today's tap (row 18, row 10): the dialog's choice replays the tap,
     * and from then on a tap is not taken over at all, as before queue-guard.
     */
    @Test
    fun ac8_rememberedPlayNowInTheMainPlayerIsCarriedOutSavedAndTheNextTapIsTodaysTap() {
        val run = Run(PlayerKind.MAIN, InMemoryPreferences(ask = true, action = DefaultTapAction.PLAY_NEXT))
        run.tapAndGetTheDialog()

        run.clickRow(GuardChoice.PLAY_NOW, checked = true)

        assertEquals("the choice is carried out", listOf(asked, "openAsToday"), run.scene.log.all)
        assertEquals("default action", DefaultTapAction.PLAY_NOW, run.preferences.action)
        assertFalse("\"Ask before replacing the queue\" must be off", run.preferences.ask)
        assertEquals("saved once", 1, run.preferences.saves)
        assertEquals("the notice \"asking is now off\" is due once (AM-1)", 1, run.noticesDue)

        assertFalse("the second tap is left to today's tap handler", run.scene.tap(nextVideo))
        assertEquals("the second tap calls nothing, no dialog either", listOf(asked, "openAsToday"), run.scene.log.all)
    }

    /**
     * AC-8 switches off the one switch that also covers the play buttons (QG AC-15, QG AC-16; plan section
     * 8, first point): after a remembered choice Play All, Background and Popup replace the queue without
     * asking, whichever choice was remembered.
     */
    @Test
    fun ac8_afterARememberedChoiceThePlayButtonsNoLongerAsk() {
        for (choice in listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW)) {
            val run = Run(PlayerKind.BACKGROUND)
            run.scene.pressPlayButton(videoCount = 25)
            assertEquals(
                "$choice: before, the play button asks",
                listOf(askedForList(videoCount = 25, queueSize = 3)),
                run.scene.log.all,
            )
            run.scene.dialog.choose(GuardChoice.CANCEL)
            run.scene.tap(tapOn())
            run.clickRow(choice, checked = true)
            val before = run.scene.log.all

            run.scene.pressPlayButton(videoCount = 25)

            assertEquals("$choice: afterwards the button plays as today", before + "playAsToday", run.scene.log.all)
        }
    }

    /**
     * AC-8 with AC-5: the remembered action is a default action like any other, so it is not used while
     * nothing is playing. There "Start playback in" decides, which "Remember my choice" does not touch.
     */
    @Test
    fun ac5_ac8_aRememberedActionIsNotUsedOnceNothingIsPlaying() {
        val expected = mapOf(
            StartPlaybackIn.VIDEO_PAGE to (false to emptyList<String>()),
            StartPlaybackIn.BACKGROUND_PLAYER to (true to listOf("startInBackground")),
            StartPlaybackIn.POPUP_PLAYER to (true to listOf("startInPopup")),
        )
        for ((startIn, outcome) in expected) {
            for (choice in listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT)) {
                val run = Run(PlayerKind.BACKGROUND, InMemoryPreferences(ask = true, startIn = startIn))
                run.tapAndGetTheDialog()
                run.clickRow(choice, checked = true)
                assertEquals("\"Start playback in\" after remembering $choice", startIn, run.preferences.startIn)
                val before = run.scene.log.all

                run.scene.player.state = PlayerState.IDLE
                val takenOver = run.scene.tap(nextVideo)

                val (expectedTakenOver, expectedCalls) = outcome
                val label = "$choice remembered, then nothing playing, $startIn"
                assertEquals("$label: calls", before + expectedCalls, run.scene.log.all)
                assertEquals("$label: taken over", expectedTakenOver, takenOver)
            }
        }
    }

    /**
     * Plan section 6, AC-8: "The remembered action also applies once the main player is down to a single
     * video (AC-3)." The dialog opens for three videos in the main player; the remembered choice switches
     * asking off; when a single video is left, the next tap carries the remembered action out without a
     * dialog (rows 7 and 8), instead of asking as it does there while asking is on (row 6, AM-2; before
     * amendment 2: instead of opening the video, row 6b).
     */
    @Test
    fun ac3_ac8_aRememberedActionAlsoAppliesToASingleVideoInTheMainPlayer() {
        val remembered = mapOf(
            GuardChoice.ADD_TO_QUEUE to (DefaultTapAction.ADD_TO_QUEUE to "enqueue"),
            GuardChoice.PLAY_NEXT to (DefaultTapAction.PLAY_NEXT to "enqueueNext"),
        )
        for ((choice, outcome) in remembered) {
            val (action, call) = outcome
            val run = Run(PlayerKind.MAIN)
            run.tapAndGetTheDialog()

            run.clickRow(choice, checked = true)

            assertEquals("$choice: the choice is carried out", listOf(asked, call), run.scene.log.all)
            assertEquals("$choice: default action", action, run.preferences.action)
            assertFalse("$choice: \"Ask before replacing the queue\" must be off", run.preferences.ask)

            run.scene.player.state = playing(PlayerKind.MAIN, queueSize = 1)

            assertTrue("$choice: the tap on a single video in the main player is taken over", run.scene.tap(nextVideo))
            assertEquals(
                "$choice: the tap on a single video in the main player does the remembered action without a dialog",
                listOf(asked, call, call),
                run.scene.log.all,
            )
        }
    }

    // =============================================================================================
    // AC-9
    // =============================================================================================

    /** Plan 4.3: "Not `checked` ...: none". The choice is carried out, both settings stay, the next tap asks again. */
    @Test
    fun ac9_aChoiceWithoutTheCheckboxIsCarriedOutButNothingIsSavedAndTheNextTapAsksAgain() {
        val carriedOut = mapOf(
            GuardChoice.ADD_TO_QUEUE to "enqueue",
            GuardChoice.PLAY_NEXT to "enqueueNext",
            GuardChoice.PLAY_NOW to "startInBackground",
        )
        for ((choice, call) in carriedOut) {
            val run = Run(PlayerKind.BACKGROUND, InMemoryPreferences(ask = true, action = DefaultTapAction.PLAY_NOW))
            run.tapAndGetTheDialog()

            run.clickRow(choice, checked = false)

            assertEquals("$choice: the choice is carried out", listOf(asked, call), run.scene.log.all)
            assertEquals("$choice: nothing is saved", 0, run.preferences.saves)
            assertEquals("$choice: no notice, asking is still on (AM-1)", 0, run.noticesDue)
            assertTrue("$choice: \"Ask before replacing the queue\" stays on", run.preferences.ask)
            assertEquals("$choice: default action stays", DefaultTapAction.PLAY_NOW, run.preferences.action)

            assertTrue("$choice: the second tap is taken over", run.scene.tap(nextVideo))
            assertEquals("$choice: second tap asks again", listOf(asked, call, askedForNextVideo), run.scene.log.all)
        }
    }

    /**
     * AC-9: "*Cancel* or any other dismissal saves nothing, checked or not." With the box ticked, the user
     * cancels (the controller gets `CANCEL`, the add-on is not called) or dismisses the dialog (nothing is
     * called at all): both settings stay and the next tap asks again.
     */
    @Test
    fun ac9_cancelOrDismissalWithTheCheckboxTickedSavesNothingAndTheNextTapAsksAgain() {
        for (cancelled in listOf(true, false)) {
            val how = if (cancelled) "Cancel" else "dismissal"
            val run = Run(PlayerKind.POPUP, InMemoryPreferences(ask = true, action = DefaultTapAction.PLAY_NOW))
            run.tapAndGetTheDialog()

            if (cancelled) run.scene.dialog.choose(GuardChoice.CANCEL)

            assertEquals("$how: nothing is carried out", listOf(asked), run.scene.log.all)
            assertEquals("$how: nothing is saved", 0, run.preferences.saves)
            assertEquals("$how: no notice (AM-1)", 0, run.noticesDue)
            assertTrue("$how: \"Ask before replacing the queue\" stays on", run.preferences.ask)
            assertEquals("$how: default action stays", DefaultTapAction.PLAY_NOW, run.preferences.action)

            assertTrue("$how: the second tap is taken over", run.scene.tap(nextVideo))
            assertEquals("$how: the second tap asks again", listOf(asked, askedForNextVideo), run.scene.log.all)
        }
    }

    /**
     * AC-9 if the add-on were told about a *Cancel* with the box ticked after all (plan 4.3 covers it:
     * "`CANCEL`: none"): still nothing is saved and nothing is carried out.
     */
    @Test
    fun ac9_cancelSavesNothingEvenIfTheCheckboxAddOnIsToldAboutIt() {
        val run = Run(PlayerKind.MAIN, InMemoryPreferences(ask = true, action = DefaultTapAction.PLAY_NEXT))
        run.tapAndGetTheDialog()

        run.clickRow(GuardChoice.CANCEL, checked = true)

        assertEquals("nothing is carried out", listOf(asked), run.scene.log.all)
        assertEquals("nothing is saved", 0, run.preferences.saves)
        assertEquals("no notice (AM-1)", 0, run.noticesDue)
        assertTrue("\"Ask before replacing the queue\" stays on", run.preferences.ask)
        assertEquals("default action stays", DefaultTapAction.PLAY_NEXT, run.preferences.action)
    }

    // =============================================================================================
    // AM-1: the notice "asking is now off"
    // =============================================================================================

    /**
     * AM-1 ("shown for every remembered choice, *Play now* included") with AM-2, the scenario of plan B2.7:
     * "main player with one video, asking on: tap -> dialog; *Play now* with the box checked -> notice due,
     * asking off saved; the next tap opens as today without a dialog". The dialog opens for the single
     * video (AM-2) and shows a queue of 1; *Play now* in the main player is today's tap; the notice is due
     * exactly once in the whole run: the second tap opens no dialog, so no choice and no notice follow.
     */
    @Test
    fun am1_am2_rememberPlayNowOnASingleVideoInTheMainPlayerThenTapAgain() {
        val run = Run(PlayerKind.MAIN, InMemoryPreferences(ask = true), queueSize = 1)
        val asked1 = askedForTap(queueSize = 1)
        run.tapAndGetTheDialog()
        assertEquals("opening the dialog: no notice yet", 0, run.noticesDue)

        run.clickRow(GuardChoice.PLAY_NOW, checked = true)

        assertEquals("the choice is carried out", listOf(asked1, "openAsToday"), run.scene.log.all)
        assertEquals("the notice is due", 1, run.noticesDue)
        assertEquals("default action", DefaultTapAction.PLAY_NOW, run.preferences.action)
        assertFalse("\"Ask before replacing the queue\" must be off", run.preferences.ask)
        assertEquals("saved once", 1, run.preferences.saves)

        assertFalse("the second tap is left to today's tap handler", run.scene.tap(nextVideo))
        assertEquals("the second tap calls nothing, no dialog either", listOf(asked1, "openAsToday"), run.scene.log.all)
        assertEquals("the notice was due exactly once", 1, run.noticesDue)
        assertEquals("nothing more was saved", 1, run.preferences.saves)
    }

    /**
     * AM-1 over a whole session, device script B2.8 step 1: remember *Play now* (notice), switch asking on
     * again in Settings, remember *Add to queue* (notice again), then tap: enqueued without a dialog and
     * without a further notice. One notice per remembered choice, none for anything else.
     */
    @Test
    fun am1_everyRememberedChoiceIsAnnouncedOnceAlsoAfterAskingWasSwitchedOnAgain() {
        val run = Run(PlayerKind.BACKGROUND)
        run.tapAndGetTheDialog()
        run.clickRow(GuardChoice.PLAY_NOW, checked = true)
        assertEquals("after remembering Play now", 1, run.noticesDue)
        assertFalse("asking is off", run.preferences.ask)

        run.preferences.ask = true // the user switches it on again in Settings > Player
        assertTrue("asking on again: the tap is taken over", run.scene.tap(nextVideo))
        assertEquals(
            "asking on again: the tap asks",
            listOf(asked, "startInBackground", askedForNextVideo),
            run.scene.log.all,
        )
        assertEquals("opening a dialog announces nothing", 1, run.noticesDue)

        run.clickRow(GuardChoice.ADD_TO_QUEUE, checked = true)
        assertEquals("after remembering Add to queue", 2, run.noticesDue)
        assertFalse("asking is off again", run.preferences.ask)
        assertEquals("default action", DefaultTapAction.ADD_TO_QUEUE, run.preferences.action)

        assertTrue("the next tap is taken over", run.scene.tap(nextVideo))
        assertEquals(
            "the next tap enqueues without a dialog",
            listOf(asked, "startInBackground", askedForNextVideo, "enqueue", "enqueue"),
            run.scene.log.all,
        )
        assertEquals("a tap that carries out the default action announces nothing", 2, run.noticesDue)
    }

    /**
     * AM-1 with questions.md Q9/A9: "Remember my choice" in the dialog of a video page control. The choice
     * is "saved like one from a list tap ... the same two settings (AC-8) and the same message (AM-1). The
     * saved default action then applies to video taps in lists only, and with asking off those paths
     * behave as today (AM-3)": afterwards the same control is left to today's handler, and a list tap
     * enqueues.
     */
    @Test
    fun am1_am3_aChoiceRememberedInTheDialogOfAPageControlIsSavedAnnouncedAndUsedForListTapsOnly() {
        val control = requestBy(OpenWay.PAGE_CONTROL)
        val run = Run(PlayerKind.BACKGROUND)
        run.tapAndGetTheDialog(control)

        run.clickRow(GuardChoice.ADD_TO_QUEUE, checked = true)

        assertEquals("the choice is carried out", listOf(asked, "enqueue"), run.scene.log.all)
        assertEquals("default action", DefaultTapAction.ADD_TO_QUEUE, run.preferences.action)
        assertFalse("\"Ask before replacing the queue\" must be off", run.preferences.ask)
        assertEquals("the notice is due once", 1, run.noticesDue)

        assertFalse("the page control is left to today's handler", run.scene.tap(control))
        assertEquals("the page control calls nothing", listOf(asked, "enqueue"), run.scene.log.all)

        assertTrue("a list tap is taken over", run.scene.tap(nextVideo))
        assertEquals("a list tap enqueues", listOf(asked, "enqueue", "enqueue"), run.scene.log.all)
        assertEquals("no further notice", 1, run.noticesDue)
    }
}
