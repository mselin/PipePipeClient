package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.fail
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.GuardSettings
import org.schabi.newpipe.enhancements.queueguard.GuardUi
import org.schabi.newpipe.enhancements.queueguard.ListActions
import org.schabi.newpipe.enhancements.queueguard.ListEffect
import org.schabi.newpipe.enhancements.queueguard.OpenWay
import org.schabi.newpipe.enhancements.queueguard.PlayerKind
import org.schabi.newpipe.enhancements.queueguard.PlayerState
import org.schabi.newpipe.enhancements.queueguard.PlayerStateSource
import org.schabi.newpipe.enhancements.queueguard.QueueGuardController
import org.schabi.newpipe.enhancements.queueguard.QueueGuardPolicy
import org.schabi.newpipe.enhancements.queueguard.TapActions
import org.schabi.newpipe.enhancements.queueguard.TapEffect
import org.schabi.newpipe.enhancements.queueguard.TapRequest
import org.schabi.newpipe.enhancements.queueguard.VideoRef

/*
 * Hand-written fakes and fixtures of the default-open-action tests. JDK, Kotlin stdlib and JUnit only:
 * no Android, no mocking library. Nothing here comes from queue-guard's test package; of queue-guard only
 * the types of its plan (sections 3.1 and 3.2 as its Amendment 2 changed them, A2.1 and A2.2) are used, which
 * its Code layer delivers in `app/src/main`.
 *
 * The letters P, D, C, M used in the test files are those of plan section 6:
 *   P = the combined policy `OpenActionPolicy(QueueGuardPolicy(g), g, settings)` on one ask switch `g`
 *       ([CombinedPolicy]),
 *   D = `OpenActionPolicy` over a recording fake `GuardPolicy` ([RecordingGuardPolicy]) with an ask switch
 *       of its own,
 *   C = `QueueGuardController` on top of P with recording actions and dialog ([Scene]),
 *   M = `RememberChoice` with a recording `RememberStore` ([RecordingRememberStore]).
 */

// ---------------------------------------------------------------------------------------------
// Fixture values
// ---------------------------------------------------------------------------------------------

internal const val SERVICE = 0
internal const val OTHER_SERVICE = 1
internal const val CURRENT_URL = "https://video.example/watch?v=current"
internal const val TAPPED_URL = "https://video.example/watch?v=tapped"
internal const val TAPPED_TITLE = "Tapped video"

/** The three players (QG plan 3.1), spelled out so that no test depends on the enum's own order. */
internal val ALL_KINDS: List<PlayerKind> = listOf(PlayerKind.MAIN, PlayerKind.BACKGROUND, PlayerKind.POPUP)

/** The four things a user can do in the tap dialog (QG plan 3.1). */
internal val ALL_CHOICES: List<GuardChoice> =
    listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW, GuardChoice.CANCEL)

/** The values of "Default action when tapping a video" (spec AC-1), spelled out like [ALL_KINDS]. */
internal val ALL_ACTIONS: List<DefaultTapAction> =
    listOf(DefaultTapAction.PLAY_NOW, DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.PLAY_NEXT)

/** The values of "Start playback in" (spec AC-2). */
internal val ALL_START_INS: List<StartPlaybackIn> =
    listOf(StartPlaybackIn.VIDEO_PAGE, StartPlaybackIn.BACKGROUND_PLAYER, StartPlaybackIn.POPUP_PLAYER)

/** "Ask before replacing the queue" on, then off. */
internal val ASK_ON_AND_OFF: List<Boolean> = listOf(true, false)

/**
 * A player of [kind] that holds [queueSize] items and is on the item [url] of [serviceId]. Since queue-guard's
 * amendment 2 the model does not know how an item got into the queue (plan B2.1: `PlayerState` has no
 * `autoQueuedCount` and no `worthGuarding`), so a queue of three stands for three chosen videos as well as
 * for one chosen video and two that "Auto-enqueue next stream" added.
 */
internal fun playing(
    kind: PlayerKind,
    queueSize: Int = 3,
    serviceId: Int = SERVICE,
    url: String? = CURRENT_URL,
): PlayerState = PlayerState(
    kind = kind,
    queueSize = queueSize,
    currentServiceId = serviceId,
    currentUrl = url,
)

/** Something is playing in the player of [kind]: with a queue of one item and with a queue of several. */
internal fun playingIn(kind: PlayerKind): List<PlayerState> =
    listOf(playing(kind, queueSize = 1), playing(kind, queueSize = 3))

/**
 * Every state in which something is playing: each player, with a queue of one item and of several. Since
 * amendment 2 this is also every state with "a queue worth guarding" (spec AM-2: "any loaded item is worth
 * guarding"; plan B2.1: it is `somethingPlaying`), so it is the fixture of every test that expects the
 * dialog. It replaces the fixtures `WORTH_GUARDING` and `SINGLE_IN_MAIN` of before the amendment (plan B2.7).
 */
internal val SOMETHING_PLAYING: List<PlayerState> = ALL_KINDS.flatMap { playingIn(it) }

/**
 * "A single video in the main player" (spec AC-3): the main player with one item. Before amendment 2 this
 * state was not worth guarding and had rules of its own (row 6b of plan 4.2); now it is one of
 * [SOMETHING_PLAYING] and is kept as a fixture for the tests that name it. On [CURRENT_URL].
 */
internal val ONE_IN_MAIN: List<PlayerState> = listOf(playing(PlayerKind.MAIN, queueSize = 1))

/**
 * Every state in which nothing is playing (plan 4.2: "false for `PlayerState.IDLE` and for any kind with
 * `queueSize == 0`"; QG plan 3.1: `kind != null && queueSize > 0`):
 * no player at all; each player open without a queue; and the same two with the leftovers of an item
 * that is gone. In the last ones the url is [CURRENT_URL], which makes no item "current": while nothing is
 * playing the rule tables have no current item (column "current": "-").
 */
internal val NOTHING_PLAYING: List<PlayerState> =
    listOf(PlayerState.IDLE) +
        ALL_KINDS.map { PlayerState(kind = it, queueSize = 0, currentServiceId = -1, currentUrl = null) } +
        ALL_KINDS.map { PlayerState(kind = it, queueSize = 0, currentServiceId = SERVICE, currentUrl = CURRENT_URL) } +
        PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)

/** A tap on a video that is not the current item of [playing] (unless [url] and [serviceId] say so). */
internal fun tapOn(
    url: String = TAPPED_URL,
    serviceId: Int = SERVICE,
    title: String = TAPPED_TITLE,
    startsListInBackground: Boolean = false,
): TapRequest =
    TapRequest(serviceId = serviceId, url = url, title = title, startsListInBackground = startsListInBackground)

/** A tap on the very item the players of [playing] are on. */
internal fun tapOnCurrent(startsListInBackground: Boolean = false): TapRequest =
    tapOn(url = CURRENT_URL, serviceId = SERVICE, startsListInBackground = startsListInBackground)

/**
 * Taps on both urls of the fixtures. For the states of [NOTHING_PLAYING], where neither is a current item,
 * and for the `onTapChoice` rows, on which "current" has no influence (plan 4.2).
 * [musicPlayerMode]: the tap comes from a local playlist with Music Player Mode on ("MPM" in the plan).
 */
internal fun tapsOnBothUrls(musicPlayerMode: Boolean): List<TapRequest> =
    listOf(tapOn(startsListInBackground = musicPlayerMode), tapOnCurrent(startsListInBackground = musicPlayerMode))

/** The video the players of [playing] are on, as `PlayerState.isCurrent` compares it. */
internal val CURRENT_VIDEO = VideoRef(SERVICE, CURRENT_URL)

/** A video no player of [playing] is on, and not the tapped one either. */
internal val OTHER_VIDEO = VideoRef(SERVICE, "https://video.example/watch?v=other-page")

/** The five ways of QG plan A2.1, spelled out so that no test depends on the enum's own order. */
internal val ALL_WAYS: List<OpenWay> = listOf(
    OpenWay.LIST_TAP,
    OpenWay.PAGE_CONTROL,
    OpenWay.VIDEO_PAGE,
    OpenWay.BACKGROUND_PLAYER,
    OpenWay.POPUP_PLAYER,
)

/**
 * Every way but the video tap in a list: the paths queue-guard's amendment adds (the video page's controls,
 * links, an address typed in search, a video from another app). Spec AM-3, plan B2.2: neither the default
 * action nor "Start playback in" applies to a request of one of these ways.
 */
internal val NOT_LIST_WAYS: List<OpenWay> = ALL_WAYS - OpenWay.LIST_TAP

/**
 * A request that reaches the guard by [way], for a video that is not the current item of [playing] (unless
 * [url] and [serviceId] say so). `requestBy(OpenWay.LIST_TAP)` equals [tapOn].
 */
internal fun requestBy(
    way: OpenWay,
    url: String = TAPPED_URL,
    serviceId: Int = SERVICE,
    title: String = TAPPED_TITLE,
): TapRequest = TapRequest(serviceId = serviceId, url = url, title = title, way = way)

/** A request by [way] for the very item the players of [playing] are on. */
internal fun requestForCurrentBy(way: OpenWay): TapRequest = requestBy(way, url = CURRENT_URL, serviceId = SERVICE)

/** Requests by each of [NOT_LIST_WAYS], each for another video and for the current item: 8 requests. */
internal val REQUESTS_BY_OTHER_WAYS: List<TapRequest> =
    NOT_LIST_WAYS.flatMap { listOf(requestBy(it), requestForCurrentBy(it)) }

/**
 * A tap in the list of related videos on the page of [pageOf], by default the page of the current item
 * (queue-guard's AM-2; row 6c of plan B2.2). The tapped video itself is another one. It is a list tap
 * (`way == LIST_TAP`).
 */
internal fun relatedTap(
    pageOf: VideoRef = CURRENT_VIDEO,
    url: String = TAPPED_URL,
    title: String = TAPPED_TITLE,
    startsListInBackground: Boolean = false,
): TapRequest = TapRequest(
    serviceId = SERVICE,
    url = url,
    title = title,
    startsListInBackground = startsListInBackground,
    relatedTo = pageOf,
)

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

/** One setting of the three that decide what a tap does. Its text is what failure messages show. */
internal data class Combination(val ask: Boolean, val action: DefaultTapAction, val startIn: StartPlaybackIn) {
    override fun toString(): String =
        "[ask ${if (ask) "on" else "off"}, default action $action, start playback in $startIn]"
}

/** Every combination of the given values; by default all 18. Pass one-element lists to fix a column. */
internal fun combinations(
    asks: List<Boolean> = ASK_ON_AND_OFF,
    actions: List<DefaultTapAction> = ALL_ACTIONS,
    startIns: List<StartPlaybackIn> = ALL_START_INS,
): List<Combination> =
    asks.flatMap { ask -> actions.flatMap { action -> startIns.map { Combination(ask, action, it) } } }

/** Queue-guard's switch "Ask before replacing the queue", settable while a test runs. */
internal class FakeAskSwitch(var ask: Boolean = true) : GuardSettings {
    override fun askBeforeReplacing(): Boolean = ask
}

/**
 * The two settings of this feature, settable while a test runs. A new one holds *Play now* and *Video
 * page*, the defaults of spec AC-1 and AC-2, written out here instead of taken from the enums' `DEFAULT`.
 */
internal class FakeOpenActionSettings(
    var action: DefaultTapAction = DefaultTapAction.PLAY_NOW,
    var startIn: StartPlaybackIn = StartPlaybackIn.VIDEO_PAGE,
) : OpenActionSettings {
    override fun defaultAction(): DefaultTapAction = action
    override fun startPlaybackIn(): StartPlaybackIn = startIn
}

/**
 * P of plan section 6: `OpenActionPolicy(QueueGuardPolicy(g), g, settings)` with `g = GuardSettings { ask }`,
 * the policy the rule tables of plan 4.2 describe, with all three settings changeable. One [askSwitch]
 * serves the wrapped policy and the wrapper, as hook G1 wires it (plan section 2). [policy] is built once:
 * the settings must be read on every call (plan 4.1, 4.2), so changing them here must change its answers.
 */
internal class CombinedPolicy {
    val askSwitch = FakeAskSwitch(ask = true)
    val settings = FakeOpenActionSettings()
    val policy: GuardPolicy = OpenActionPolicy(QueueGuardPolicy(askSwitch), askSwitch, settings)

    /** Queue-guard's policy without this feature, on the same switch. The yardstick of AC-14 and AC-15. */
    val queueGuardAlone: GuardPolicy = QueueGuardPolicy(askSwitch)

    fun set(combination: Combination) {
        askSwitch.ask = combination.ask
        settings.action = combination.action
        settings.startIn = combination.startIn
    }

    /** Runs [check] once under each of [combinations]. */
    fun forEach(combinations: List<Combination>, check: (Combination) -> Unit) {
        for (combination in combinations) {
            set(combination)
            check(combination)
        }
    }
}

/**
 * Stands for the app's preferences in the scenario of plan section 6, AC-8: one in-memory object that is
 * queue-guard's switch, this feature's two settings and the store "Remember my choice" writes to.
 */
internal class InMemoryPreferences(
    var ask: Boolean = true,
    var action: DefaultTapAction = DefaultTapAction.PLAY_NOW,
    var startIn: StartPlaybackIn = StartPlaybackIn.VIDEO_PAGE,
) : GuardSettings, OpenActionSettings, RememberStore {
    /** How often something was saved. */
    var saves = 0
        private set

    override fun askBeforeReplacing(): Boolean = ask
    override fun defaultAction(): DefaultTapAction = action
    override fun startPlaybackIn(): StartPlaybackIn = startIn

    override fun save(defaultAction: DefaultTapAction, askBeforeReplacing: Boolean) {
        saves++
        action = defaultAction
        ask = askBeforeReplacing
    }
}

/** M of plan section 6: records every `save` call. */
internal class RecordingRememberStore : RememberStore {
    data class Saved(val defaultAction: DefaultTapAction, val askBeforeReplacing: Boolean)

    private val recorded = mutableListOf<Saved>()

    /** Every call so far, oldest first. */
    val saved: List<Saved> get() = recorded.toList()

    override fun save(defaultAction: DefaultTapAction, askBeforeReplacing: Boolean) {
        recorded += Saved(defaultAction, askBeforeReplacing)
    }
}

// ---------------------------------------------------------------------------------------------
// A wrapped policy that answers what the test says (D)
// ---------------------------------------------------------------------------------------------

/**
 * D of plan section 6: a [GuardPolicy] to wrap in [OpenActionPolicy] that answers what the test tells it
 * to and records the arguments it was asked with. With answers queue-guard's own policy would never give
 * in that situation, a result can only have come from here, which is how the tests tell delegation from a
 * second implementation of queue-guard's rules.
 *
 * Plan 4.2: "`base` is pure; how often it is called is not part of the contract". So tests look at the
 * arguments of the recorded calls, never at their number.
 */
internal class RecordingGuardPolicy(
    var tapAnswer: TapEffect = TapEffect.OPEN_AS_TODAY,
    var tapChoiceAnswers: Map<GuardChoice, TapEffect> = ALL_CHOICES.associateWith { TapEffect.NONE },
    var listAnswer: ListEffect = ListEffect.PLAY_AS_TODAY,
    var listChoiceAnswers: Map<GuardChoice, ListEffect> = ALL_CHOICES.associateWith { ListEffect.NONE },
    var streamDetailsAnswer: Boolean = false,
) : GuardPolicy {
    data class TapCall(val tap: TapRequest, val player: PlayerState)
    data class TapChoiceCall(val choice: GuardChoice, val tap: TapRequest, val player: PlayerState)
    data class ListChoiceCall(val choice: GuardChoice, val player: PlayerState)

    val tapCalls = mutableListOf<TapCall>()
    val tapChoiceCalls = mutableListOf<TapChoiceCall>()
    val listCalls = mutableListOf<PlayerState>()
    val listChoiceCalls = mutableListOf<ListChoiceCall>()
    val streamDetailsCalls = mutableListOf<PlayerState>()

    /** Forgets the recorded calls; the answers stay. */
    fun forget() {
        tapCalls.clear()
        tapChoiceCalls.clear()
        listCalls.clear()
        listChoiceCalls.clear()
        streamDetailsCalls.clear()
    }

    override fun onTap(tap: TapRequest, player: PlayerState): TapEffect {
        tapCalls += TapCall(tap, player)
        return tapAnswer
    }

    override fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect {
        tapChoiceCalls += TapChoiceCall(choice, tap, player)
        return tapChoiceAnswers.getValue(choice)
    }

    override fun onListPlay(player: PlayerState): ListEffect {
        listCalls += player
        return listAnswer
    }

    override fun onListChoice(choice: GuardChoice, player: PlayerState): ListEffect {
        listChoiceCalls += ListChoiceCall(choice, player)
        return listChoiceAnswers.getValue(choice)
    }

    override fun offersStreamDetails(player: PlayerState): Boolean {
        streamDetailsCalls += player
        return streamDetailsAnswer
    }
}

// ---------------------------------------------------------------------------------------------
// The controller's surroundings (C)
// ---------------------------------------------------------------------------------------------

/**
 * Ordered record of everything the fakes of one [Scene] were asked to do. Comparing the whole log with
 * the expected list proves three things at once: what was called, in which order, and that nothing else
 * was called ("only `enqueue()` is called ... no UI", plan section 6).
 */
internal class CallLog {
    private val entries = mutableListOf<String>()

    fun add(entry: String) {
        entries += entry
    }

    /** A snapshot, oldest entry first. */
    val all: List<String> get() = entries.toList()
}

/** The log entry of [GuardUi.askForTap]. */
internal fun askedForTap(title: String = TAPPED_TITLE, queueSize: Int): String =
    "askForTap(title=$title, queueSize=$queueSize)"

/** The log entry of [GuardUi.askForList]. */
internal fun askedForList(videoCount: Int, queueSize: Int): String =
    "askForList(videoCount=$videoCount, queueSize=$queueSize)"

/** The player as the test wants it to be right now. */
internal class SettablePlayer(var state: PlayerState) : PlayerStateSource {
    override fun current(): PlayerState = state
}

/** Records the tap actions as `openAsToday`, `enqueue`, `enqueueNext`, `startInBackground`, `startInPopup`. */
internal class RecordingTapActions(private val log: CallLog) : TapActions {
    /** Runs inside [openAsToday], where queue-guard's glue replays upstream's own tap handler. */
    var duringOpenAsToday: () -> Unit = {}

    override fun openAsToday() {
        log.add("openAsToday")
        duringOpenAsToday()
    }

    override fun enqueue() = log.add("enqueue")

    override fun enqueueNext() = log.add("enqueueNext")

    override fun startInBackground() = log.add("startInBackground")

    override fun startInPopup() = log.add("startInPopup")
}

/** Records the list actions as `playAsToday`, `enqueueAll`, `enqueueAllNext`. */
internal class RecordingListActions(private val log: CallLog) : ListActions {
    override fun playAsToday() = log.add("playAsToday")

    override fun enqueueAll() = log.add("enqueueAll")

    override fun enqueueAllNext() = log.add("enqueueAllNext")
}

/**
 * A dialog that never shows: it records what it was asked to show ([askedForTap], [askedForList]) and
 * keeps `onChoice`, so that the test plays the user with [choose], or never answers at all (a dismissal
 * that calls nothing).
 */
internal class FakeDialog(private val log: CallLog) : GuardUi {
    private val callbacks = mutableListOf<(GuardChoice) -> Unit>()

    /** Dialogs opened so far, of both forms. */
    val promptCount: Int get() = callbacks.size

    override fun askForTap(title: String, queueSize: Int, onChoice: (GuardChoice) -> Unit) {
        callbacks += onChoice
        log.add(askedForTap(title, queueSize))
    }

    override fun askForList(videoCount: Int, queueSize: Int, onChoice: (GuardChoice) -> Unit) {
        callbacks += onChoice
        log.add(askedForList(videoCount, queueSize))
    }

    /** The user picks [choice] in the dialog opened last. */
    fun choose(choice: GuardChoice) {
        if (callbacks.isEmpty()) {
            fail("cannot choose $choice: the controller has not opened a dialog")
        }
        callbacks.last()(choice)
    }
}

/**
 * C of plan section 6: one `QueueGuardController` on top of [policy], with a player the test can change
 * and one set of recording fakes that all write to [log].
 */
internal class Scene(policy: GuardPolicy, initial: PlayerState) {
    val log = CallLog()
    val player = SettablePlayer(initial)
    val controller = QueueGuardController(policy, player)
    val tapActions = RecordingTapActions(log)
    val listActions = RecordingListActions(log)
    val dialog = FakeDialog(log)

    /** A video tap reaches queue-guard's hook. `true`: the tap was taken over, upstream's handler is skipped. */
    fun tap(request: TapRequest): Boolean = controller.onVideoTap(request, tapActions, dialog)

    /** A play button above a list of [videoCount] loaded videos is pressed. */
    fun pressPlayButton(videoCount: Int) = controller.onListPlay(videoCount, listActions, dialog)

    /**
     * Makes `openAsToday()` do what queue-guard's glue does there (QG plan 3.3: `replay.run()`): upstream's
     * tap handler runs again and reaches the hook a second time with [request]. Returns the answers the
     * hook gave to those replayed taps; `false` means upstream's own statements ran.
     */
    fun replayOnOpenAsToday(request: TapRequest): List<Boolean> {
        val answers = mutableListOf<Boolean>()
        tapActions.duringOpenAsToday = { answers += tap(request) }
        return answers
    }
}
