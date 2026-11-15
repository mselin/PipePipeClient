package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.QueueGuardPolicy

/**
 * "Remember my choice" from the tap to the tap after it: the scenario of plan section 6, AC-8, "with one
 * in-memory fake that is `GuardSettings`, `OpenActionSettings` and `RememberStore`" ([InMemoryPreferences],
 * standing for the app's preferences), the combined policy reading it and queue-guard's controller on top.
 *
 * The Android dialog is replaced by its contract (QG plan 3.3): for a row click that is carried out, the
 * add-on is called "right before it" with the choice, then the choice goes to the controller; for
 * *Cancel* and every other dismissal the add-on is not called. [clickRow] plays the first, a bare
 * `dialog.choose(CANCEL)` or no call at all the second.
 */
class RememberChoiceScenarioTest {

    private val nextVideo = tapOn(url = "https://video.example/watch?v=next", title = "Next video")

    /** One run of the app: preferences, policy and controller, with something playing in [kind]. */
    private class Run(kind: PlayerKind, val preferences: InMemoryPreferences = InMemoryPreferences()) {
        val policy: GuardPolicy = OpenActionPolicy(QueueGuardPolicy(preferences), preferences)
        val scene = Scene(policy, playing(kind))

        /** The user taps a video and the dialog opens, as it must with ask on (AC-4). */
        fun tapAndGetTheDialog() {
            assertTrue("the first tap must be taken over", scene.tap(tapOn()))
            assertEquals("the first tap must only open the dialog", listOf(askedForTap(queueSize = 3)), scene.log.all)
        }

        /** The user clicks the row [choice] with the checkbox [checked]: add-on first, then it is carried out. */
        fun clickRow(choice: GuardChoice, checked: Boolean) {
            RememberChoice(preferences).onChoice(choice, checked)
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
        assertTrue("\"Ask before replacing the queue\" stays on", run.preferences.ask)
        assertEquals("default action stays", DefaultTapAction.PLAY_NEXT, run.preferences.action)
    }
}
