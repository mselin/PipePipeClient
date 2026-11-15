package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.fail

/*
 * Hand-written fakes and fixtures of the queue-guard tests. JDK, Kotlin stdlib and JUnit only:
 * no Android, no mocking library.
 *
 * All recording fakes can write to one shared [Journal]. Comparing the whole journal with the
 * expected list proves three things at once: what was called, in which order, and that nothing
 * else was called.
 */

// ---------------------------------------------------------------------------------------------
// Fixture values
// ---------------------------------------------------------------------------------------------

internal const val SERVICE = 0
internal const val OTHER_SERVICE = 1
internal const val CURRENT_URL = "https://video.example/watch?v=current"
internal const val TAPPED_URL = "https://video.example/watch?v=tapped"

/** The three players of plan 3.1, spelled out so that a test never depends on the enum's own order. */
internal val ALL_KINDS: List<PlayerKind> = listOf(PlayerKind.MAIN, PlayerKind.BACKGROUND, PlayerKind.POPUP)

/** A player of [kind] that holds [queueSize] items and is on the item [url] of [serviceId]. */
internal fun playing(
    kind: PlayerKind,
    queueSize: Int = 3,
    serviceId: Int = SERVICE,
    url: String? = CURRENT_URL,
): PlayerState = PlayerState(kind = kind, queueSize = queueSize, currentServiceId = serviceId, currentUrl = url)

/** A player of [kind] that is open but holds no item. By the spec's terms nothing is playing. */
internal fun openButEmpty(kind: PlayerKind): PlayerState =
    PlayerState(kind = kind, queueSize = 0, currentServiceId = -1, currentUrl = null)

/** Every state in which nothing is playing: no player at all, and each player without a queue. */
internal val NOTHING_PLAYING: List<PlayerState> = listOf(PlayerState.IDLE) + ALL_KINDS.map { openButEmpty(it) }

/** Every state in which something is playing: each player, with a queue of one item and of several. */
internal val SOMETHING_PLAYING: List<PlayerState> =
    ALL_KINDS.flatMap { listOf(playing(it, queueSize = 1), playing(it, queueSize = 3)) }

/** A tap on a video that is not the current item of [playing] (unless [url] and [serviceId] say so). */
internal fun tapOn(
    url: String = TAPPED_URL,
    serviceId: Int = SERVICE,
    title: String = "Tapped video",
    startsListInBackground: Boolean = false,
): TapRequest =
    TapRequest(serviceId = serviceId, url = url, title = title, startsListInBackground = startsListInBackground)

/** A tap on the very item the players of [playing] are on. */
internal fun tapOnCurrent(startsListInBackground: Boolean = false): TapRequest =
    tapOn(url = CURRENT_URL, serviceId = SERVICE, startsListInBackground = startsListInBackground)

// ---------------------------------------------------------------------------------------------
// Journal
// ---------------------------------------------------------------------------------------------

/** Ordered record of everything the fakes of one test were asked to do. */
class Journal {
    private val entries = mutableListOf<String>()

    fun add(entry: String) {
        entries += entry
    }

    /** A snapshot, oldest entry first. */
    val all: List<String> get() = entries.toList()
}

/** The journal entry of [GuardUi.askForTap]. */
internal fun askedForTap(title: String, queueSize: Int, label: String = ""): String =
    labelled(label, "askForTap(title=$title, queueSize=$queueSize)")

/** The journal entry of [GuardUi.askForList]. */
internal fun askedForList(videoCount: Int, queueSize: Int, label: String = ""): String =
    labelled(label, "askForList(videoCount=$videoCount, queueSize=$queueSize)")

private fun labelled(label: String, entry: String): String = if (label.isEmpty()) entry else "$label.$entry"

// ---------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------

/** The switch "Ask before replacing the queue", settable while a test runs. */
class SettableGuardSettings(var ask: Boolean) : GuardSettings {
    override fun askBeforeReplacing(): Boolean = ask
}

/** The player as the test wants it to be right now. */
class MutablePlayerStateSource(var state: PlayerState) : PlayerStateSource {
    override fun current(): PlayerState = state
}

/**
 * Records the tap actions as journal entries `openAsToday`, `enqueue`, `enqueueNext`,
 * `startInBackground`, `startInPopup`, prefixed with `label.` when a [label] is given.
 */
class RecordingTapActions(private val journal: Journal = Journal(), private val label: String = "") : TapActions {
    private val own = mutableListOf<String>()

    /** Runs inside [openAsToday], where the glue replays upstream's own tap handler. */
    var duringOpenAsToday: () -> Unit = {}

    /** The calls this object received, without label. */
    val calls: List<String> get() = own.toList()

    private fun record(call: String) {
        own += call
        journal.add(labelled(label, call))
    }

    override fun openAsToday() {
        record("openAsToday")
        duringOpenAsToday()
    }

    override fun enqueue() = record("enqueue")

    override fun enqueueNext() = record("enqueueNext")

    override fun startInBackground() = record("startInBackground")

    override fun startInPopup() = record("startInPopup")
}

/** Records the list actions as journal entries `playAsToday`, `enqueueAll`, `enqueueAllNext`. */
class RecordingListActions(private val journal: Journal = Journal(), private val label: String = "") : ListActions {
    private val own = mutableListOf<String>()

    /** The calls this object received, without label. */
    val calls: List<String> get() = own.toList()

    private fun record(call: String) {
        own += call
        journal.add(labelled(label, call))
    }

    override fun playAsToday() = record("playAsToday")

    override fun enqueueAll() = record("enqueueAll")

    override fun enqueueAllNext() = record("enqueueAllNext")
}

/**
 * A dialog that never shows: it records what it was asked to show ([askedForTap], [askedForList] in the
 * journal) and keeps `onChoice`, so that the test plays the user with [choose], or never answers at all
 * (a dismissal that calls nothing).
 */
class FakeGuardUi(private val journal: Journal = Journal(), private val label: String = "") : GuardUi {
    data class TapPrompt(val title: String, val queueSize: Int)
    data class ListPrompt(val videoCount: Int, val queueSize: Int)

    private val tapAsked = mutableListOf<TapPrompt>()
    private val listAsked = mutableListOf<ListPrompt>()
    private val callbacks = mutableListOf<(GuardChoice) -> Unit>()

    val tapPrompts: List<TapPrompt> get() = tapAsked.toList()
    val listPrompts: List<ListPrompt> get() = listAsked.toList()

    /** Dialogs opened so far, of both forms. */
    val promptCount: Int get() = callbacks.size

    override fun askForTap(title: String, queueSize: Int, onChoice: (GuardChoice) -> Unit) {
        tapAsked += TapPrompt(title, queueSize)
        callbacks += onChoice
        journal.add(askedForTap(title, queueSize, label))
    }

    override fun askForList(videoCount: Int, queueSize: Int, onChoice: (GuardChoice) -> Unit) {
        listAsked += ListPrompt(videoCount, queueSize)
        callbacks += onChoice
        journal.add(askedForList(videoCount, queueSize, label))
    }

    /**
     * The user picks [choice] in dialog number [prompt] (default: the one opened last). The fake does not
     * stop a second pick in the same dialog: ignoring it is the controller's job.
     */
    fun choose(choice: GuardChoice, prompt: Int = callbacks.lastIndex) {
        if (prompt !in callbacks.indices) {
            fail("cannot choose $choice: the controller has not opened dialog #$prompt (opened: ${callbacks.size})")
        }
        callbacks[prompt](choice)
    }
}

/**
 * A [GuardPolicy] that answers what the test tells it to and records what it was asked. It lets the
 * controller be tested against every effect of plan 3.2, also those [QueueGuardPolicy] never returns
 * from `onTap` (default-open-action will, plan section 6).
 */
class ScriptedGuardPolicy(
    var tapEffect: TapEffect = TapEffect.OPEN_AS_TODAY,
    var tapChoiceEffect: TapEffect = TapEffect.NONE,
    var listEffect: ListEffect = ListEffect.PLAY_AS_TODAY,
    var listChoiceEffect: ListEffect = ListEffect.NONE,
    var streamDetails: Boolean = false,
) : GuardPolicy {
    data class TapCall(val tap: TapRequest, val player: PlayerState)
    data class TapChoiceCall(val choice: GuardChoice, val tap: TapRequest, val player: PlayerState)
    data class ListChoiceCall(val choice: GuardChoice, val player: PlayerState)

    val tapCalls = mutableListOf<TapCall>()
    val tapChoiceCalls = mutableListOf<TapChoiceCall>()
    val listCalls = mutableListOf<PlayerState>()
    val listChoiceCalls = mutableListOf<ListChoiceCall>()
    val streamDetailsCalls = mutableListOf<PlayerState>()

    override fun onTap(tap: TapRequest, player: PlayerState): TapEffect {
        tapCalls += TapCall(tap, player)
        return tapEffect
    }

    override fun onTapChoice(choice: GuardChoice, tap: TapRequest, player: PlayerState): TapEffect {
        tapChoiceCalls += TapChoiceCall(choice, tap, player)
        return tapChoiceEffect
    }

    override fun onListPlay(player: PlayerState): ListEffect {
        listCalls += player
        return listEffect
    }

    override fun onListChoice(choice: GuardChoice, player: PlayerState): ListEffect {
        listChoiceCalls += ListChoiceCall(choice, player)
        return listChoiceEffect
    }

    override fun offersStreamDetails(player: PlayerState): Boolean {
        streamDetailsCalls += player
        return streamDetails
    }
}

/**
 * One [QueueGuardController] on top of [policy], with a player the test can change and one set of
 * recording fakes that all write to [journal].
 */
class GuardScene(val policy: GuardPolicy, initial: PlayerState) {
    val journal = Journal()
    val player = MutablePlayerStateSource(initial)
    val controller = QueueGuardController(policy, player)
    val tapActions = RecordingTapActions(journal)
    val listActions = RecordingListActions(journal)
    val ui = FakeGuardUi(journal)

    /** A video tap reaches the hook. `true`: the guard took the tap over. */
    fun tap(request: TapRequest): Boolean = controller.onVideoTap(request, tapActions, ui)

    /** A play button above a list of [videoCount] loaded videos is pressed. */
    fun pressPlayButton(videoCount: Int) = controller.onListPlay(videoCount, listActions, ui)
}
