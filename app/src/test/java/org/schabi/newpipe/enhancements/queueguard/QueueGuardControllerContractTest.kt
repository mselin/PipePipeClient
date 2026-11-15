package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract of [QueueGuardController] towards any [GuardPolicy] (plan 3.2, the paragraph
 * "`QueueGuardController`"), checked with a [ScriptedGuardPolicy] instead of the real rules.
 *
 * [QueueGuardControllerTest] can only reach the effects [QueueGuardPolicy] returns. The controller takes
 * the interface, and default-open-action plugs in its own policy (plan section 6): its `onTap` answers
 * `ENQUEUE`, `ENQUEUE_NEXT`, `START_BACKGROUND` or `START_POPUP` directly. So every effect, the
 * arguments handed to the policy and the replay rule are pinned down here independently of the rules.
 */
class QueueGuardControllerContractTest {

    private val state = playing(PlayerKind.BACKGROUND, queueSize = 4)
    private val laterState = playing(PlayerKind.POPUP, queueSize = 9, url = "$CURRENT_URL-later")
    private val tap = tapOn(title = "Tapped video")

    private fun scene(policy: ScriptedGuardPolicy, initial: PlayerState = state) = GuardScene(policy, initial)

    private val allChoices =
        listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW, GuardChoice.CANCEL)

    // =============================================================================================
    // onVideoTap: what the policy answers to the tap
    // =============================================================================================

    /** "`s = player.current()` and `policy.onTap(tap, s)`". */
    @Test
    fun ac1_policyIsAskedOnceWithTheTapAndTheCurrentPlayerState() {
        val policy = ScriptedGuardPolicy(tapEffect = TapEffect.ASK)
        val scene = scene(policy)

        scene.tap(tap)

        assertEquals(listOf(ScriptedGuardPolicy.TapCall(tap, state)), policy.tapCalls)
        assertEquals("no choice yet", emptyList<ScriptedGuardPolicy.TapChoiceCall>(), policy.tapChoiceCalls)
    }

    /** "`ASK` calls only `ui.askForTap(tap.title, s.queueSize, cb)` and returns `true`". */
    @Test
    fun ac1_ac2_askOpensOnlyTheTapDialogWithTitleAndQueueSizeAndTakesTheTapOver() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.ASK))

        val takenOver = scene.tap(tap)

        assertTrue(takenOver)
        assertEquals(listOf(askedForTap("Tapped video", 4)), scene.journal.all)
    }

    /** "`OPEN_AS_TODAY` returns `false` and calls nothing": upstream's own statements do the work. */
    @Test
    fun ac7_ac8_openAsTodayForATapReturnsFalseAndCallsNothing() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.OPEN_AS_TODAY))

        val takenOver = scene.tap(tap)

        assertFalse(takenOver)
        assertEquals("not even openAsToday(): the caller goes on by itself", emptyList<String>(), scene.journal.all)
    }

    /** "any other effect is performed and returns `true`": `START_BACKGROUND`. */
    @Test
    fun ac16_startBackgroundForATapIsPerformedAtOnceAndTakesTheTapOver() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.START_BACKGROUND))

        assertTrue(scene.tap(tap))

        assertEquals(listOf("startInBackground"), scene.journal.all)
    }

    /** "any other effect is performed and returns `true`": `START_POPUP`. */
    @Test
    fun ac16_startPopupForATapIsPerformedAtOnceAndTakesTheTapOver() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.START_POPUP))

        assertTrue(scene.tap(tap))

        assertEquals(listOf("startInPopup"), scene.journal.all)
    }

    /** "any other effect is performed and returns `true`": `ENQUEUE` (used by default-open-action). */
    @Test
    fun ac3_enqueueForATapIsPerformedAtOnceAndTakesTheTapOver() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.ENQUEUE))

        assertTrue(scene.tap(tap))

        assertEquals(listOf("enqueue"), scene.journal.all)
    }

    /** "any other effect is performed and returns `true`": `ENQUEUE_NEXT` (used by default-open-action). */
    @Test
    fun ac4_enqueueNextForATapIsPerformedAtOnceAndTakesTheTapOver() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.ENQUEUE_NEXT))

        assertTrue(scene.tap(tap))

        assertEquals(listOf("enqueueNext"), scene.journal.all)
    }

    /** "any other effect is performed and returns `true`", and performing `NONE` is "nothing". */
    @Test
    fun ac6_noneForATapTakesTheTapOverAndCallsNothing() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.NONE))

        assertTrue(scene.tap(tap))

        assertEquals(emptyList<String>(), scene.journal.all)
    }

    // =============================================================================================
    // onVideoTap: the choice
    // =============================================================================================

    /**
     * "`cb(choice)` performs `policy.onTapChoice(choice, tap, player.current())`, the state being read at
     * that moment".
     */
    @Test
    fun ac9_policyDecidesEveryChoiceWithTheTapAndThePlayerStateAtThatMoment() {
        for (choice in allChoices) {
            val policy = ScriptedGuardPolicy(tapEffect = TapEffect.ASK)
            val scene = scene(policy)
            scene.tap(tap)
            scene.player.state = laterState

            scene.ui.choose(choice)

            assertEquals(
                "$choice",
                listOf(ScriptedGuardPolicy.TapChoiceCall(choice, tap, laterState)),
                policy.tapChoiceCalls,
            )
        }
    }

    @Test
    fun ac9_playerClosedBeforeTheChoiceReachesThePolicyAsIdle() {
        val policy = ScriptedGuardPolicy(tapEffect = TapEffect.ASK)
        val scene = scene(policy)
        scene.tap(tap)
        scene.player.state = PlayerState.IDLE

        scene.ui.choose(GuardChoice.PLAY_NEXT)

        assertEquals(
            listOf(ScriptedGuardPolicy.TapChoiceCall(GuardChoice.PLAY_NEXT, tap, PlayerState.IDLE)),
            policy.tapChoiceCalls,
        )
    }

    private fun journalAfterChoiceEffect(effect: TapEffect): List<String> {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.ASK, tapChoiceEffect = effect))
        scene.tap(tap)
        scene.ui.choose(GuardChoice.ADD_TO_QUEUE) // which choice does not matter: the scripted policy decides
        return scene.journal.all
    }

    private val asked = askedForTap("Tapped video", 4)

    /** Perform: "`ENQUEUE` ... -> `enqueue()`". */
    @Test
    fun ac3_enqueueForAChoiceCallsOnlyEnqueue() {
        assertEquals(listOf(asked, "enqueue"), journalAfterChoiceEffect(TapEffect.ENQUEUE))
    }

    /** Perform: "`ENQUEUE_NEXT` ... -> `enqueueNext()`". */
    @Test
    fun ac4_enqueueNextForAChoiceCallsOnlyEnqueueNext() {
        assertEquals(listOf(asked, "enqueueNext"), journalAfterChoiceEffect(TapEffect.ENQUEUE_NEXT))
    }

    /** Perform: "`START_BACKGROUND` ... -> `startInBackground()`". */
    @Test
    fun ac5_startBackgroundForAChoiceCallsOnlyStartInBackground() {
        assertEquals(listOf(asked, "startInBackground"), journalAfterChoiceEffect(TapEffect.START_BACKGROUND))
    }

    /** Perform: "`START_POPUP` ... -> `startInPopup()`". */
    @Test
    fun ac5_startPopupForAChoiceCallsOnlyStartInPopup() {
        assertEquals(listOf(asked, "startInPopup"), journalAfterChoiceEffect(TapEffect.START_POPUP))
    }

    /** Perform: "`OPEN_AS_TODAY` -> `openAsToday()`". */
    @Test
    fun ac5_openAsTodayForAChoiceCallsOnlyOpenAsToday() {
        assertEquals(listOf(asked, "openAsToday"), journalAfterChoiceEffect(TapEffect.OPEN_AS_TODAY))
    }

    /** Perform: "`NONE`, `ASK` -> nothing". */
    @Test
    fun ac6_noneForAChoiceCallsNothing() {
        assertEquals(listOf(asked), journalAfterChoiceEffect(TapEffect.NONE))
    }

    /** Perform: "`NONE`, `ASK` -> nothing": `onTapChoice` is "never ASK", and if it were, no second dialog opens. */
    @Test
    fun ac6_askForAChoiceOpensNoSecondDialogAndCallsNothing() {
        assertEquals(listOf(asked), journalAfterChoiceEffect(TapEffect.ASK))
    }

    /** "A second call of `cb` is ignored": the policy is not even asked again. */
    @Test
    fun ac3_secondCallOfTheCallbackIsIgnored() {
        val policy = ScriptedGuardPolicy(tapEffect = TapEffect.ASK, tapChoiceEffect = TapEffect.ENQUEUE)
        val scene = scene(policy)
        scene.tap(tap)

        scene.ui.choose(GuardChoice.ADD_TO_QUEUE)
        policy.tapChoiceEffect = TapEffect.START_POPUP
        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(asked, "enqueue"), scene.journal.all)
        assertEquals(
            listOf(ScriptedGuardPolicy.TapChoiceCall(GuardChoice.ADD_TO_QUEUE, tap, state)),
            policy.tapChoiceCalls,
        )
    }

    // =============================================================================================
    // onVideoTap: the replay
    // =============================================================================================

    /**
     * "While the controller is inside `actions.openAsToday()` it returns `false` at once (the replay)",
     * whatever the policy would answer to the replayed tap. Here it would ask again: with the real rules
     * that is exactly what happens in the main player while the switch is on.
     */
    @Test
    fun ac5_replayedTapIsLetThroughEvenIfThePolicyWouldAskAgain() {
        val scene = scene(ScriptedGuardPolicy(tapEffect = TapEffect.ASK, tapChoiceEffect = TapEffect.OPEN_AS_TODAY))
        val replayActions = RecordingTapActions(scene.journal, label = "replay")
        val replayUi = FakeGuardUi(scene.journal, label = "replay")
        val replayAnswers = mutableListOf<Boolean>()
        scene.tapActions.duringOpenAsToday = {
            replayAnswers += scene.controller.onVideoTap(tap.copy(), replayActions, replayUi)
        }
        scene.tap(tap)

        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(false), replayAnswers)
        assertEquals(listOf(asked, "openAsToday"), scene.journal.all)
    }

    /** The same, with a policy that would perform an effect for the replayed tap (default-open-action). */
    @Test
    fun ac5_replayedTapIsLetThroughEvenIfThePolicyWouldStartAPlayer() {
        val policy = ScriptedGuardPolicy(tapEffect = TapEffect.ASK, tapChoiceEffect = TapEffect.OPEN_AS_TODAY)
        val scene = scene(policy)
        val replayActions = RecordingTapActions(scene.journal, label = "replay")
        val replayUi = FakeGuardUi(scene.journal, label = "replay")
        val replayAnswers = mutableListOf<Boolean>()
        scene.tapActions.duringOpenAsToday = {
            replayAnswers += scene.controller.onVideoTap(tap.copy(), replayActions, replayUi)
        }
        scene.tap(tap)
        policy.tapEffect = TapEffect.START_BACKGROUND

        scene.ui.choose(GuardChoice.PLAY_NOW)

        assertEquals(listOf(false), replayAnswers)
        assertEquals(listOf(asked, "openAsToday"), scene.journal.all)
    }

    /** "With the replay flag set, cleared in `finally`": only the call from inside `openAsToday()` is let through. */
    @Test
    fun ac5_ac1_tapAfterTheReplayIsDecidedByThePolicyAgain() {
        val policy = ScriptedGuardPolicy(tapEffect = TapEffect.ASK, tapChoiceEffect = TapEffect.OPEN_AS_TODAY)
        val scene = scene(policy)
        scene.tap(tap)
        scene.ui.choose(GuardChoice.PLAY_NOW)

        val takenOver = scene.tap(tap)

        assertTrue(takenOver)
        assertEquals(listOf(asked, "openAsToday", asked), scene.journal.all)
    }

    // =============================================================================================
    // onListPlay
    // =============================================================================================

    /** "Otherwise `policy.onListPlay(s)`". */
    @Test
    fun ac10_policyIsAskedOnceWithTheCurrentPlayerState() {
        val policy = ScriptedGuardPolicy(listEffect = ListEffect.ASK)
        val scene = scene(policy)

        scene.pressPlayButton(videoCount = 12)

        assertEquals(listOf(state), policy.listCalls)
        assertEquals("no choice yet", emptyList<ScriptedGuardPolicy.ListChoiceCall>(), policy.listChoiceCalls)
    }

    /** "`ASK` -> `ui.askForList(videoCount, s.queueSize, cb)`". */
    @Test
    fun ac10_askOpensOnlyTheListDialogWithVideoCountAndQueueSize() {
        val scene = scene(ScriptedGuardPolicy(listEffect = ListEffect.ASK))

        scene.pressPlayButton(videoCount = 12)

        assertEquals(listOf(askedForList(12, 4)), scene.journal.all)
    }

    /** "`PLAY_AS_TODAY` -> `playAsToday()`". */
    @Test
    fun ac14_playAsTodayForAPlayButtonCallsOnlyPlayAsToday() {
        val scene = scene(ScriptedGuardPolicy(listEffect = ListEffect.PLAY_AS_TODAY))

        scene.pressPlayButton(videoCount = 12)

        assertEquals(listOf("playAsToday"), scene.journal.all)
    }

    /** "`videoCount <= 0` -> `playAsToday()`" comes first: an empty list never asks, whatever the policy says. */
    @Test
    fun ac10_listWithNoVideosPlaysAsTodayEvenIfThePolicyWouldAsk() {
        for (videoCount in listOf(0, -1)) {
            val scene = scene(ScriptedGuardPolicy(listEffect = ListEffect.ASK))

            scene.pressPlayButton(videoCount)

            assertEquals("videoCount $videoCount", listOf("playAsToday"), scene.journal.all)
        }
    }

    /** "whose `cb` performs `policy.onListChoice(choice, player.current())`": the state at that moment. */
    @Test
    fun ac11_policyDecidesEveryListChoiceWithThePlayerStateAtThatMoment() {
        for (choice in allChoices) {
            val policy = ScriptedGuardPolicy(listEffect = ListEffect.ASK)
            val scene = scene(policy)
            scene.pressPlayButton(videoCount = 12)
            scene.player.state = laterState

            scene.ui.choose(choice)

            assertEquals(
                "$choice",
                listOf(ScriptedGuardPolicy.ListChoiceCall(choice, laterState)),
                policy.listChoiceCalls,
            )
        }
    }

    private fun journalAfterListChoiceEffect(effect: ListEffect): List<String> {
        val scene = scene(ScriptedGuardPolicy(listEffect = ListEffect.ASK, listChoiceEffect = effect))
        scene.pressPlayButton(videoCount = 12)
        scene.ui.choose(GuardChoice.PLAY_NEXT) // which choice does not matter: the scripted policy decides
        return scene.journal.all
    }

    private val askedList = askedForList(12, 4)

    /** "`ENQUEUE_ALL` -> `enqueueAll()`". */
    @Test
    fun ac11_enqueueAllForAListChoiceCallsOnlyEnqueueAll() {
        assertEquals(listOf(askedList, "enqueueAll"), journalAfterListChoiceEffect(ListEffect.ENQUEUE_ALL))
    }

    /** "`ENQUEUE_ALL_NEXT` -> `enqueueAllNext()`". */
    @Test
    fun ac11_enqueueAllNextForAListChoiceCallsOnlyEnqueueAllNext() {
        assertEquals(listOf(askedList, "enqueueAllNext"), journalAfterListChoiceEffect(ListEffect.ENQUEUE_ALL_NEXT))
    }

    /** "`PLAY_AS_TODAY` -> `playAsToday()`". */
    @Test
    fun ac12_playAsTodayForAListChoiceCallsOnlyPlayAsToday() {
        assertEquals(listOf(askedList, "playAsToday"), journalAfterListChoiceEffect(ListEffect.PLAY_AS_TODAY))
    }

    /** `NONE` is the effect of *Cancel*: AC-13 "changes nothing". */
    @Test
    fun ac13_noneForAListChoiceCallsNothing() {
        assertEquals(listOf(askedList), journalAfterListChoiceEffect(ListEffect.NONE))
    }

    // =============================================================================================
    // offersStreamDetails
    // =============================================================================================

    /** "`offersStreamDetails()`: `policy.offersStreamDetails(player.current())`". */
    @Test
    fun ac17_streamDetailsOfferIsThePolicysAnswerForTheCurrentPlayerState() {
        val policy = ScriptedGuardPolicy(streamDetails = true)
        val scene = scene(policy)

        assertTrue(scene.controller.offersStreamDetails())
        assertEquals(listOf(state), policy.streamDetailsCalls)

        policy.streamDetails = false
        scene.player.state = laterState

        assertFalse(scene.controller.offersStreamDetails())
        assertEquals(listOf(state, laterState), policy.streamDetailsCalls)
    }
}
