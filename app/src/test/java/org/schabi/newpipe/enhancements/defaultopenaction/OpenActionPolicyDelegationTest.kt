package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.ListEffect
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.TapEffect

/**
 * How `OpenActionPolicy` composes with the policy it wraps (plan 4.2, "How it composes with `base`"),
 * checked over a [RecordingGuardPolicy] instead of queue-guard's rules ("D" in plan section 6).
 *
 * The rule tables ([OpenActionPolicyTest]) cannot tell a wrapper that delegates from one that carries a
 * second copy of queue-guard's rules. Here the wrapped policy gives answers queue-guard never gives in
 * that situation, so a matching result can only have been passed on. The plan sentences each test rests
 * on are quoted at the test.
 *
 * Queue-guard's switch "Ask before replacing the queue" is a [FakeAskSwitch] of this class, which the
 * wrapped fake does not know: what the wrapper does with the switch on or off is its own reading of it
 * (plan 4.2: "Asking is on" is `guardSettings.askBeforeReplacing()` ... "no longer inferred from an answer
 * of `base`"). A new switch is on.
 *
 * "`base` is pure; how often it is called is not part of the contract": no test counts calls.
 */
class OpenActionPolicyDelegationTest {

    private val base = RecordingGuardPolicy()
    private val settings = FakeOpenActionSettings()
    private val askSwitch = FakeAskSwitch()
    private val policy: GuardPolicy = OpenActionPolicy(base, askSwitch, settings)

    /**
     * Answers to the three dialog choices that queue-guard's own rules never give for them (QG plan 3.2:
     * `ENQUEUE`; `ENQUEUE_NEXT` or `ENQUEUE`; `OPEN_AS_TODAY` or `START_...`), and that differ from every
     * answer the tests script for the tap itself. *Cancel* keeps queue-guard's `NONE`.
     */
    private val unusualChoiceAnswers: Map<GuardChoice, TapEffect> = mapOf(
        GuardChoice.ADD_TO_QUEUE to TapEffect.ENQUEUE_NEXT,
        GuardChoice.PLAY_NEXT to TapEffect.NONE,
        GuardChoice.PLAY_NOW to TapEffect.ENQUEUE,
        GuardChoice.CANCEL to TapEffect.NONE,
    )

    /** What queue-guard answers to a tap on another video while something is playing and it does not ask. */
    private val answersToATapWithoutAsking =
        listOf(TapEffect.OPEN_AS_TODAY, TapEffect.START_BACKGROUND, TapEffect.START_POPUP)

    /**
     * Everything queue-guard can answer to a tap while something is playing: `ASK`, and the three above.
     * None of them is among the values of [unusualChoiceAnswers] for the three default actions.
     */
    private val answersToATap = listOf(TapEffect.ASK) + answersToATapWithoutAsking

    private val anotherVideoInAnyList =
        listOf(tapOn(startsListInBackground = false), tapOn(startsListInBackground = true))
    private val anyTap = tapsOnBothUrls(musicPlayerMode = false) + tapsOnBothUrls(musicPlayerMode = true)
    private val anyState = NOTHING_PLAYING + SOMETHING_PLAYING

    /** Runs [check] under all 9 combinations of this feature's two settings. */
    private fun forAllNineSettings(check: (label: String) -> Unit) {
        for (action in ALL_ACTIONS) {
            for (startIn in ALL_START_INS) {
                settings.action = action
                settings.startIn = startIn
                check("[default action $action, start playback in $startIn]")
            }
        }
    }

    // =============================================================================================
    // onTap
    // =============================================================================================

    /**
     * Plan section 6, AC-3, D: "with the switch off the result is `base.onTapChoice(defaultAction.choice,
     * tap, player)`, whatever `base.onTap` would answer (`OPEN_AS_TODAY`, `START_BACKGROUND`,
     * `START_POPUP`)". Plan 4.2, `onTap` while something is playing: "`guardSettings.askBeforeReplacing()`
     * or `player.isCurrent(tap)` -> `base.onTap(tap, player)` ...; otherwise
     * `base.onTapChoice(settings.defaultAction().choice, tap, player)`".
     */
    @Test
    fun ac3_delegation_theDefaultActionGetsTheWrappedPolicysAnswerToTheDialogChoiceOfTheSameName() {
        askSwitch.ask = false
        base.tapChoiceAnswers = unusualChoiceAnswers
        for (answerToTheTap in answersToATapWithoutAsking) {
            base.tapAnswer = answerToTheTap
            forAllNineSettings { label ->
                for (state in SOMETHING_PLAYING) {
                    for (tap in anotherVideoInAnyList) {
                        assertEquals(
                            "onTap under $label, wrapped policy answers $answerToTheTap to the tap, $state, $tap",
                            unusualChoiceAnswers.getValue(settings.action.choice),
                            policy.onTap(tap, state),
                        )
                    }
                }
            }
        }
    }

    /** As above: the wrapped policy is asked with that choice and with the tap and the player state as they came in. */
    @Test
    fun ac3_delegation_theWrappedPolicyGetsTheChoiceOfTheDefaultActionWithTheTapAndThePlayerStateUnchanged() {
        askSwitch.ask = false
        base.tapChoiceAnswers = unusualChoiceAnswers
        base.tapAnswer = TapEffect.START_BACKGROUND
        forAllNineSettings { label ->
            for (state in SOMETHING_PLAYING) {
                for (tap in anotherVideoInAnyList) {
                    base.forget()

                    policy.onTap(tap, state)

                    val expected = RecordingGuardPolicy.TapChoiceCall(settings.action.choice, tap, state)
                    assertTrue(
                        "onTap under $label must ask the wrapped policy onTapChoice$expected, " +
                            "it asked ${base.tapChoiceCalls}",
                        expected in base.tapChoiceCalls,
                    )
                }
            }
        }
    }

    /**
     * Plan section 6, AC-4, D: "with the switch on the result is `base.onTap`'s answer whatever it is
     * (`ASK`, `OPEN_AS_TODAY`, `START_BACKGROUND`, `START_POPUP`), never the default action's". Plan 4.2:
     * "`guardSettings.askBeforeReplacing()` ... -> `base.onTap(tap, player)`, whatever that answer is".
     * AC-4: "a video tap behaves exactly as queue-guard specifies and the default action is not used":
     * whether the dialog opens or the video opens as today is the wrapped policy's answer to the tap.
     */
    @Test
    fun ac4_delegation_whetherToAskIsTheWrappedPolicysAnswerToTheTap() {
        askSwitch.ask = true
        base.tapChoiceAnswers = unusualChoiceAnswers
        for (answerToTheTap in answersToATap) {
            base.tapAnswer = answerToTheTap
            forAllNineSettings { label ->
                for (state in SOMETHING_PLAYING) {
                    for (tap in anotherVideoInAnyList) {
                        base.forget()

                        assertEquals(
                            "onTap with the switch on under $label, wrapped policy answers $answerToTheTap " +
                                "to the tap, $state, $tap",
                            answerToTheTap,
                            policy.onTap(tap, state),
                        )

                        val expected = RecordingGuardPolicy.TapCall(tap, state)
                        assertTrue(
                            "onTap under $label must ask the wrapped policy onTap$expected, it asked ${base.tapCalls}",
                            expected in base.tapCalls,
                        )
                    }
                }
            }
        }
    }

    /**
     * Plan 4.2: "`guardSettings.askBeforeReplacing()` or `player.isCurrent(tap)` -> `base.onTap(tap,
     * player)`". AC-12: the current item behaves as today "whatever the settings", so the default action
     * must not reach it, with the switch on and, which is the case that proves it, with the switch off.
     * The answer is what queue-guard gives for the current item (QG plan 3.2: `OPEN_AS_TODAY`).
     */
    @Test
    fun ac12_delegation_theCurrentItemGetsTheWrappedPolicysAnswerToTheTapNotTheDefaultAction() {
        base.tapChoiceAnswers = unusualChoiceAnswers
        base.tapAnswer = TapEffect.OPEN_AS_TODAY
        for (ask in ASK_ON_AND_OFF) {
            askSwitch.ask = ask
            forAllNineSettings { label ->
                for (state in SOMETHING_PLAYING) {
                    for (musicPlayerMode in listOf(false, true)) {
                        val tap = tapOnCurrent(startsListInBackground = musicPlayerMode)
                        assertEquals(
                            "onTap with the switch ${if (ask) "on" else "off"} under $label, $state, $tap",
                            TapEffect.OPEN_AS_TODAY,
                            policy.onTap(tap, state),
                        )
                    }
                }
            }
        }
    }

    /**
     * Plan 4.2: "this class never reads `player.worthGuarding` and repeats none of QG's rules, so whether
     * a queue is worth guarding is decided in one place". The three expectations of this class that depend
     * on the switch, over [WORTH_GUARDING] and [SINGLE_IN_MAIN] with a wrapped policy that answers the same
     * in all of them: a state that is or is not worth guarding gets the same result.
     * - Switch off, another video: the wrapped policy's answer to the dialog choice of the default action
     *   (AC-3: "also on a single video in the main player").
     * - Switch on: the wrapped policy's answer to the tap, here `ASK` also for a single video, where
     *   queue-guard would not ask, and `OPEN_AS_TODAY` also for a queue, where it would (AC-4).
     * - The menu entry while the wrapped policy does not offer it: "switch off and not `PLAY_NOW`" (AC-11).
     * One policy object while the switch changes: it is read on every call (plan section 6, AC-4, D).
     */
    @Test
    fun ac3_ac4_ac11_delegation_theWrapperDoesNotJudgeTheQueueItself() {
        base.tapChoiceAnswers = unusualChoiceAnswers
        base.streamDetailsAnswer = false
        for (answerToTheTap in listOf(TapEffect.ASK, TapEffect.OPEN_AS_TODAY)) {
            base.tapAnswer = answerToTheTap
            forAllNineSettings { label ->
                for (state in WORTH_GUARDING + SINGLE_IN_MAIN) {
                    for (tap in anotherVideoInAnyList) {
                        val where = "under $label, wrapped policy answers $answerToTheTap to the tap, $state, $tap"

                        askSwitch.ask = false
                        assertEquals(
                            "onTap with the switch off $where",
                            unusualChoiceAnswers.getValue(settings.action.choice),
                            policy.onTap(tap, state),
                        )
                        askSwitch.ask = true
                        assertEquals("onTap with the switch on $where", answerToTheTap, policy.onTap(tap, state))
                    }

                    val where = "under $label, $state"
                    askSwitch.ask = false
                    assertEquals(
                        "offersStreamDetails with the switch off $where",
                        settings.action != DefaultTapAction.PLAY_NOW,
                        policy.offersStreamDetails(state),
                    )
                    askSwitch.ask = true
                    assertEquals(
                        "offersStreamDetails with the switch on $where",
                        false,
                        policy.offersStreamDetails(state),
                    )
                }
            }
        }
    }

    // =============================================================================================
    // onTapChoice
    // =============================================================================================

    /**
     * Plan 4.2, `onTapChoice`: "`b = base.onTapChoice(choice, tap, player)`; `choice == PLAY_NOW` and not
     * `player.somethingPlaying` -> *idle* over `b`; otherwise `b`". Plan section 1: "everything else goes
     * to the wrapped policy". Rows 13 to 18 are queue-guard's dialog, passed on (AC-4: "as before"; that
     * rows 14 to 16 belong to AC-4 is the assumption of questions.md, Q7).
     */
    @Test
    fun ac4_delegation_aDialogChoiceGetsTheWrappedPolicysAnswer() {
        base.tapChoiceAnswers = unusualChoiceAnswers
        val notPlayNow = listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.CANCEL)
        forAllNineSettings { label ->
            for (tap in anyTap) {
                for (state in SOMETHING_PLAYING) {
                    for (choice in ALL_CHOICES) {
                        assertEquals(
                            "onTapChoice($choice) under $label, $state, $tap",
                            unusualChoiceAnswers.getValue(choice),
                            policy.onTapChoice(choice, tap, state),
                        )
                    }
                }
                for (state in NOTHING_PLAYING) {
                    for (choice in notPlayNow) {
                        assertEquals(
                            "onTapChoice($choice) under $label, $state, $tap",
                            unusualChoiceAnswers.getValue(choice),
                            policy.onTapChoice(choice, tap, state),
                        )
                    }
                }
            }
        }
    }

    /** As above: choice, tap and player state reach the wrapped policy as they came in. */
    @Test
    fun ac4_delegation_theWrappedPolicyGetsTheDialogChoiceWithTheTapAndThePlayerStateUnchanged() {
        base.tapChoiceAnswers = unusualChoiceAnswers
        forAllNineSettings { label ->
            for (tap in anyTap) {
                for (state in SOMETHING_PLAYING) {
                    for (choice in listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW)) {
                        base.forget()

                        policy.onTapChoice(choice, tap, state)

                        val expected = RecordingGuardPolicy.TapChoiceCall(choice, tap, state)
                        assertTrue(
                            "onTapChoice($choice) under $label must ask the wrapped policy onTapChoice$expected, " +
                                "it asked ${base.tapChoiceCalls}",
                            expected in base.tapChoiceCalls,
                        )
                    }
                }
            }
        }
    }

    // =============================================================================================
    // onListPlay, onListChoice
    // =============================================================================================

    /**
     * Plan 4.2: "`onListPlay`, `onListChoice`: the base call, arguments and result unchanged." AC-15: "The
     * play buttons are not affected by either setting." Every `ListEffect` is passed back, also where
     * queue-guard would not give it.
     */
    @Test
    fun ac15_delegation_aPlayButtonGetsTheWrappedPolicysAnswerAndThePlayerStateIsPassedOnUnchanged() {
        val everyAnswer = listOf(
            ListEffect.NONE,
            ListEffect.PLAY_AS_TODAY,
            ListEffect.ASK,
            ListEffect.ENQUEUE_ALL,
            ListEffect.ENQUEUE_ALL_NEXT,
        )
        for (answer in everyAnswer) {
            base.listAnswer = answer
            forAllNineSettings { label ->
                for (state in anyState) {
                    base.forget()

                    assertEquals("onListPlay under $label, $state", answer, policy.onListPlay(state))

                    assertTrue(
                        "onListPlay under $label must ask the wrapped policy onListPlay($state) and nothing " +
                            "else, it asked onListPlay ${base.listCalls}",
                        base.listCalls.isNotEmpty() && base.listCalls.all { it == state },
                    )
                }
            }
        }
    }

    /** As above for the list dialog's choices; answers queue-guard never gives for them. */
    @Test
    fun ac15_delegation_aListDialogChoiceGetsTheWrappedPolicysAnswerAndItsArgumentsArePassedOnUnchanged() {
        val unusualListAnswers = mapOf(
            GuardChoice.ADD_TO_QUEUE to ListEffect.PLAY_AS_TODAY,
            GuardChoice.PLAY_NEXT to ListEffect.NONE,
            GuardChoice.PLAY_NOW to ListEffect.ENQUEUE_ALL_NEXT,
            GuardChoice.CANCEL to ListEffect.ENQUEUE_ALL,
        )
        base.listChoiceAnswers = unusualListAnswers
        forAllNineSettings { label ->
            for (state in anyState) {
                for (choice in ALL_CHOICES) {
                    base.forget()

                    assertEquals(
                        "onListChoice($choice) under $label, $state",
                        unusualListAnswers.getValue(choice),
                        policy.onListChoice(choice, state),
                    )

                    val expected = RecordingGuardPolicy.ListChoiceCall(choice, state)
                    assertTrue(
                        "onListChoice($choice) under $label must ask the wrapped policy onListChoice$expected " +
                            "and nothing else, it asked onListChoice ${base.listChoiceCalls}",
                        base.listChoiceCalls.isNotEmpty() && base.listChoiceCalls.all { it == expected },
                    )
                }
            }
        }
    }

    // =============================================================================================
    // offersStreamDetails
    // =============================================================================================

    /** Plan 4.2, `offersStreamDetails`: "`base.offersStreamDetails(player)`, or ...": queue-guard's AC-17 stays. */
    @Test
    fun ac11_delegation_whereTheWrappedPolicyOffersStreamDetailsTheEntryStays() {
        base.streamDetailsAnswer = true
        for (answerToAPlayButton in listOf(ListEffect.ASK, ListEffect.PLAY_AS_TODAY)) {
            base.listAnswer = answerToAPlayButton
            forAllNineSettings { label ->
                for (state in anyState) {
                    assertEquals("offersStreamDetails under $label, $state", true, policy.offersStreamDetails(state))
                }
            }
        }
    }

    /**
     * Plan 4.2, `offersStreamDetails`: "... or `player.somethingPlaying` and not
     * `guardSettings.askBeforeReplacing()` and `settings.defaultAction() != PLAY_NOW`. It no longer calls
     * `base.onListPlay`", and "`base.onListPlay`'s answer has no influence on `offersStreamDetails`".
     * Plan section 6, AC-11, D: "while something is playing and `base` does not offer the entry, the result
     * is "switch off and not `PLAY_NOW`", whatever `base.onListPlay` answers". Queue-guard does not ask for
     * a play button on a single video in the main player with the switch on (QG AC-14); taking that for
     * "ask is off" would offer the entry where the tap opens the page (row 21b).
     */
    @Test
    fun ac11_delegation_askOffIsTheSwitchNotTheWrappedPolicysAnswerToAPlayButton() {
        base.streamDetailsAnswer = false
        for (state in playingIn(PlayerKind.MAIN)) {
            for (answerToAPlayButton in listOf(ListEffect.ASK, ListEffect.PLAY_AS_TODAY)) {
                base.listAnswer = answerToAPlayButton
                val where = "while the wrapped policy answers $answerToAPlayButton to a play button, $state"

                askSwitch.ask = true
                forAllNineSettings { label ->
                    assertEquals(
                        "offersStreamDetails with the switch on under $label $where",
                        false,
                        policy.offersStreamDetails(state),
                    )
                }
                askSwitch.ask = false
                forAllNineSettings { label ->
                    assertEquals(
                        "offersStreamDetails with the switch off under $label $where",
                        settings.action != DefaultTapAction.PLAY_NOW,
                        policy.offersStreamDetails(state),
                    )
                }
            }
        }
    }

    /**
     * Plan 4.2, `offersStreamDetails`: "... or not `player.somethingPlaying` and
     * `settings.startPlaybackIn() != VIDEO_PAGE`". While nothing is playing queue-guard never asks for a
     * play button (QG plan 3.2: `PLAY_AS_TODAY`); that must not be taken for "ask is off, the tap
     * enqueues": the default action is not used then (rows 22 and 23 say "any").
     */
    @Test
    fun ac11_delegation_whileNothingIsPlayingOnlyStartPlaybackInDecides() {
        base.streamDetailsAnswer = false
        base.listAnswer = ListEffect.PLAY_AS_TODAY
        forAllNineSettings { label ->
            for (state in NOTHING_PLAYING) {
                assertEquals(
                    "offersStreamDetails under $label, $state",
                    settings.startIn != StartPlaybackIn.VIDEO_PAGE,
                    policy.offersStreamDetails(state),
                )
            }
        }
    }
}
