package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model of plan 3.1 as changed by plan A2.1 (`GuardModel.kt`): what "something is playing" and
 * "current item" mean, the ways a request can reach the guard ([OpenWay]), the three tables of A2.1
 * ([replacesQueue], [openWayOfRouterChoice], [queueItemDetails]), and the two pure functions behind
 * *Play all next* (AC-11) and the "Show stream details" entry (AC-17).
 *
 * Amendment 2 (AM-1): "a queue worth guarding" now equals "something is playing". `worthGuarding`,
 * `autoQueuedCount`, `AutoQueuedRecord` and `AUTO_QUEUED_RECORD_LIMIT` are removed from the model, and
 * the tests of them went with them (plan A2.10): the eight `worthGuarding` tests
 * (`ac7_ac14_noQueueIsWorthGuardingWhileNothingIsPlaying` to
 * `ac1_ac7_aQueueWorthGuardingAlwaysMeansSomethingIsPlaying`),
 * `ac1_ac7_noVideoIsAutoQueuedInIdleAndInAStateBuiltWithFourArguments`,
 * `ac1_ac7_somethingPlayingDoesNotDependOnTheAutoQueuedCount`, the ten `ac1_ac7_record...` tests and
 * `ac1_ac7_watchingOneVideoAndLettingTheNextOnesFollowIsNotAQueueUntilTheUserAddsAVideo`: 21 tests.
 * `ac8_currentItemDoesNotDependOnTheAutoQueuedCount` still holds and stays under a new name. What
 * replaces the removed ones is the section "A queue worth guarding, as amended" below.
 */
class GuardModelTest {

    // ---- "Something is playing" (spec, Terms): a player is open and holds at least one item ----

    @Test
    fun ac1_ac7_somethingIsPlayingWhenAnOpenPlayerHoldsAtLeastOneItem() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind with one item", playing(kind, queueSize = 1).somethingPlaying)
            assertTrue("$kind with several items", playing(kind, queueSize = 25).somethingPlaying)
        }
    }

    @Test
    fun ac7_nothingIsPlayingWhileNoPlayerIsOpen() {
        assertFalse("IDLE", PlayerState.IDLE.somethingPlaying)
        val noPlayerButStaleQueueData =
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("kind == null decides, whatever the other fields say", noPlayerButStaleQueueData.somethingPlaying)
    }

    @Test
    fun ac7_nothingIsPlayingWhileTheOpenPlayerHoldsNoItem() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind with an empty queue", openButEmpty(kind).somethingPlaying)
        }
    }

    @Test
    fun ac7_idleIsNoPlayerNoQueueNoCurrentItem() {
        assertEquals(
            PlayerState(kind = null, queueSize = 0, currentServiceId = -1, currentUrl = null),
            PlayerState.IDLE,
        )
    }

    // ---- "A queue worth guarding", as amended (AM-1): the player holds at least one item ----
    //
    // Plan A2.1: `worthGuarding` "is removed, not kept as an alias: as amended it equals
    // `somethingPlaying`, and one name for it is enough", and `PlayerState` loses `autoQueuedCount`.

    /**
     * AM-1: "How the items got there no longer matters: one video counts, and so do videos added by
     * auto-enqueue next stream." The state has no field left that could tell them apart: exactly the four
     * of plan A2.1. A fifth field (the old `autoQueuedCount`) fails here.
     */
    @Test
    fun am1_playerStateHoldsKindQueueSizeAndCurrentItemAndNothingAboutHowItemsGotThere() {
        val fields = PlayerState::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("currentServiceId", "currentUrl", "kind", "queueSize"), fields)
        assertEquals(
            "the constructor takes those four and nothing else",
            PlayerState(PlayerKind.MAIN, 3, SERVICE, CURRENT_URL),
            PlayerState(kind = PlayerKind.MAIN, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL),
        )
    }

    /**
     * AM-1, plan A2.1: there is one name for "a queue worth guarding", `somethingPlaying`. A second
     * property that could drift away from it (the old `worthGuarding`, with its exception for a single
     * video in the main player) must not come back.
     */
    @Test
    fun am1_playerStateHasNoSeparateWorthGuardingAndNoAutoQueuedCount() {
        val getters = PlayerState::class.java.methods.map { it.name }
        assertFalse("PlayerState.worthGuarding must be removed (plan A2.1)", "getWorthGuarding" in getters)
        assertFalse("PlayerState.autoQueuedCount must be removed (plan A2.1)", "getAutoQueuedCount" in getters)
        assertTrue("PlayerState.somethingPlaying stays", "getSomethingPlaying" in getters)
    }

    /**
     * AM-1, the changed term: "the player (main, popup or background) holds at least one item". The main
     * player with one video is worth guarding like every other state; before Amendment 2 it was the
     * exception (answer A11).
     */
    @Test
    fun am1_oneVideoInTheMainPlayerIsAQueueWorthGuardingLikeEveryOtherLoadedQueue() {
        assertTrue("main player, one video", playing(PlayerKind.MAIN, queueSize = 1).somethingPlaying)
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(1, 2, 3, 25, 500)) {
                assertTrue("$kind, $queueSize", playing(kind, queueSize = queueSize).somethingPlaying)
            }
        }
    }

    /** The fixture every "asks" test runs over (plan A2.10): each player with one item and with three. */
    @Test
    fun am1_theFixtureSomethingPlayingHoldsEachPlayerWithOneItemAndWithThree() {
        assertEquals(
            ALL_KINDS.flatMap { listOf(it to 1, it to 3) },
            SOMETHING_PLAYING.map { it.kind to it.queueSize },
        )
        for (state in SOMETHING_PLAYING) {
            assertTrue("$state", state.somethingPlaying)
            assertEquals("$state", SERVICE, state.currentServiceId)
            assertEquals("$state", CURRENT_URL, state.currentUrl)
        }
        for (state in NOTHING_PLAYING) {
            assertFalse("$state", state.somethingPlaying)
        }
    }

    /**
     * AC-8: the current item is recognised in every state in which something is playing. This test ran
     * over the two removed fixtures and was called `ac8_currentItemDoesNotDependOnTheAutoQueuedCount`;
     * what it pins is unchanged.
     */
    @Test
    fun ac8_currentItemIsRecognisedInEveryStateInWhichSomethingIsPlaying() {
        for (state in SOMETHING_PLAYING) {
            assertTrue("$state", state.isCurrent(tapOnCurrent()))
            assertFalse("$state, another video", state.isCurrent(tapOn(url = TAPPED_URL)))
            assertFalse(
                "$state, same url on another service",
                state.isCurrent(tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)),
            )
        }
    }

    // ---- "Current item" (AC-8): matched by service id and exact url ----

    @Test
    fun ac8_currentItemIsTheOneWithTheSameServiceAndTheSameUrl() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind", playing(kind).isCurrent(tapOnCurrent()))
            assertTrue("$kind, queue of one", playing(kind, queueSize = 1).isCurrent(tapOnCurrent()))
            assertTrue(
                "$kind, Music Player Mode does not change which item is current",
                playing(kind).isCurrent(tapOnCurrent(startsListInBackground = true)),
            )
        }
    }

    @Test
    fun ac8_currentItemIsRecognisedWhateverTitleTheListShows() {
        val tap = tapOn(url = CURRENT_URL, title = "Another title for the same video")
        assertTrue(playing(PlayerKind.MAIN).isCurrent(tap))
    }

    @Test
    fun ac8_anotherUrlIsNotTheCurrentItem() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind", playing(kind).isCurrent(tapOn(url = TAPPED_URL)))
        }
    }

    /** Plan section 8: the url is compared exactly, as upstream does; another form of the url is another item. */
    @Test
    fun ac8_urlIsComparedExactly() {
        val state = playing(PlayerKind.BACKGROUND, url = CURRENT_URL)
        assertFalse("extra parameter", state.isCurrent(tapOn(url = "$CURRENT_URL&t=10")))
        assertFalse("other case", state.isCurrent(tapOn(url = CURRENT_URL.uppercase())))
    }

    @Test
    fun ac8_sameUrlOnAnotherServiceIsNotTheCurrentItem() {
        for (kind in ALL_KINDS) {
            val tap = tapOn(url = CURRENT_URL, serviceId = OTHER_SERVICE)
            assertFalse("$kind", playing(kind, serviceId = SERVICE).isCurrent(tap))
        }
    }

    @Test
    fun ac8_noItemIsCurrentWhileNothingIsPlaying() {
        val urlOfIdle = tapOn(url = CURRENT_URL, serviceId = -1)
        assertFalse("IDLE", PlayerState.IDLE.isCurrent(urlOfIdle))
        for (kind in ALL_KINDS) {
            val emptyQueueWithStaleItem =
                PlayerState(kind = kind, queueSize = 0, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
            assertFalse("$kind with an empty queue", emptyQueueWithStaleItem.isCurrent(tapOnCurrent()))
        }
        val noPlayerWithStaleItem =
            PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("no player", noPlayerWithStaleItem.isCurrent(tapOnCurrent()))
    }

    @Test
    fun ac8_noItemIsCurrentWhileThePlayerReportsNoCurrentUrl() {
        for (kind in ALL_KINDS) {
            assertFalse("$kind", playing(kind, url = null).isCurrent(tapOnCurrent()))
        }
    }

    // ---- TapRequest ----

    /** Plan 3.1: `startsListInBackground` defaults to `false`; only Music Player Mode in a list sets it. */
    @Test
    fun ac5_tapStartsNoListInTheBackgroundUnlessToldSo() {
        val tap = TapRequest(SERVICE, TAPPED_URL, "Tapped video")
        assertFalse(tap.startsListInBackground)
        assertEquals(TapRequest(SERVICE, TAPPED_URL, "Tapped video", false), tap)
    }

    /**
     * Plan A2.1: "The two new fields of `TapRequest` are last and have defaults: every existing
     * four-argument call means a list tap outside a related list." AC-1 to AC-16 keep their meaning for
     * the hooks T1-T4, which were written before Amendment 2.
     */
    @Test
    fun ac1_am2_aRequestBuiltWithoutWayIsAListTapOutsideARelatedList() {
        val three = TapRequest(SERVICE, TAPPED_URL, "Tapped video")
        assertEquals(OpenWay.LIST_TAP, three.way)
        assertNull(three.relatedTo)

        val four = TapRequest(SERVICE, TAPPED_URL, "Tapped video", true)
        assertTrue(four.startsListInBackground)
        assertEquals(OpenWay.LIST_TAP, four.way)
        assertNull(four.relatedTo)

        assertEquals(
            "the fifth argument is the way, the sixth the related video",
            TapRequest(SERVICE, TAPPED_URL, "Tapped video", false, way = OpenWay.LIST_TAP, relatedTo = CURRENT_VIDEO),
            TapRequest(SERVICE, TAPPED_URL, "Tapped video", false, OpenWay.LIST_TAP, CURRENT_VIDEO),
        )
    }

    /** AM-3, AM-5, AM-6: a request carries the way it came by; two ways are two different requests. */
    @Test
    fun am3_am5_am6_aRequestCarriesItsWay() {
        for (way in ALL_WAYS) {
            assertEquals("$way", way, requestBy(way).way)
            assertNull("$way: no related list", requestBy(way).relatedTo)
        }
        assertEquals("requestBy(LIST_TAP) is tapOn()", tapOn(), requestBy(OpenWay.LIST_TAP))
        assertEquals(ALL_WAYS.size, ALL_WAYS.map { requestBy(it) }.toSet().size)
    }

    /** AM-2: a tap in a related list carries the video whose page shows that list. */
    @Test
    fun am2_aRelatedTapCarriesTheVideoOfThePageItWasTappedOn() {
        assertEquals(CURRENT_VIDEO, relatedTap().relatedTo)
        assertEquals(OTHER_VIDEO, relatedTap(pageOf = OTHER_VIDEO).relatedTo)
        assertEquals(OpenWay.LIST_TAP, relatedTap().way)
        assertEquals("the tapped video is not the page's video", TAPPED_URL, relatedTap().url)
    }

    /** Plan A2.1: exactly five ways. A sixth needs a row in every table below. */
    @Test
    fun am3_am5_am6_thereAreExactlyFiveWaysToOpenAVideo() {
        assertEquals(
            setOf("LIST_TAP", "PAGE_CONTROL", "VIDEO_PAGE", "BACKGROUND_PLAYER", "POPUP_PLAYER"),
            OpenWay.values().map { it.name }.toSet(),
        )
        assertEquals("the fixture ALL_WAYS covers them all", OpenWay.values().toSet(), ALL_WAYS.toSet())
    }

    // ---- isCurrent(VideoRef) (AM-9, AM-10, AM-11; plan A2.15) ----
    //
    // This is the whole decision behind `QueueGuard.playersQueueFor` (hooks P5 and P6) and the seek step
    // of `onTimedLink`: "the player holds a queue and its current item is this video". The plan separates
    // no other plain function for them. Queue length must not matter.

    /**
     * AM-9, AM-11: the page of the current item is recognised by service and address in every player and
     * for every queue length, so that the page's controls and the page's own start get the player's
     * queue instead of the one video.
     */
    @Test
    fun am9_am11_theVideoThePlayerIsOnIsCurrentInEveryPlayerWhateverTheQueueLength() {
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(1, 2, 3, 40)) {
                val state = playing(kind, queueSize = queueSize)
                assertTrue("$state", state.isCurrent(CURRENT_VIDEO))
                assertTrue("$state, an equal ref", state.isCurrent(VideoRef(SERVICE, CURRENT_URL)))
            }
        }
    }

    /** AM-10: a link to a time in the current item is recognised although the queue is longer than one. */
    @Test
    fun am10_theCurrentItemOfALongerQueueIsCurrentForATimeLink() {
        for (kind in ALL_KINDS) {
            assertTrue("$kind, queue of three", playing(kind, queueSize = 3).isCurrent(CURRENT_VIDEO))
            assertTrue("$kind, queue of one", playing(kind, queueSize = 1).isCurrent(CURRENT_VIDEO))
        }
    }

    /**
     * Plan A2.15, tests: `isCurrent(VideoRef)` "is `false` while nothing is playing, for another service
     * with the same address and for another address". Where it is `false`, P5 and P6 give nothing and the
     * page behaves as before (AM-9, AM-11), and a time link is no seek (AM-10).
     */
    @Test
    fun am9_am10_am11_noVideoIsCurrentWhileNothingIsPlayingNorOnAnotherServiceNorAtAnotherAddress() {
        for (state in NOTHING_PLAYING) {
            assertFalse("$state", state.isCurrent(CURRENT_VIDEO))
        }
        for (kind in ALL_KINDS) {
            val staleItem = PlayerState(
                kind = kind,
                queueSize = 0,
                currentServiceId = SERVICE,
                currentUrl = CURRENT_URL,
            )
            assertFalse("$kind, empty queue with a stale item", staleItem.isCurrent(CURRENT_VIDEO))
            assertFalse("$kind, no current url", playing(kind, url = null).isCurrent(CURRENT_VIDEO))
        }
        val noPlayer = PlayerState(kind = null, queueSize = 3, currentServiceId = SERVICE, currentUrl = CURRENT_URL)
        assertFalse("no player, stale queue data", noPlayer.isCurrent(CURRENT_VIDEO))
        for (state in SOMETHING_PLAYING) {
            assertFalse("$state, another service", state.isCurrent(VideoRef(OTHER_SERVICE, CURRENT_URL)))
            assertFalse("$state, another address", state.isCurrent(VideoRef(SERVICE, TAPPED_URL)))
            assertFalse("$state, a longer address", state.isCurrent(VideoRef(SERVICE, "$CURRENT_URL&t=90")))
        }
    }

    /** Plan A2.1: `isCurrent(tap)` is `isCurrent(VideoRef(tap.serviceId, tap.url))`, whatever else the request says. */
    @Test
    fun ac8_am9_aRequestIsCurrentExactlyWhenItsVideoIs() {
        val videos = listOf(CURRENT_VIDEO, OTHER_VIDEO, VideoRef(OTHER_SERVICE, CURRENT_URL))
        for (state in NOTHING_PLAYING + SOMETHING_PLAYING) {
            for (video in videos) {
                for (way in ALL_WAYS) {
                    val request = requestBy(way, url = video.url, serviceId = video.serviceId)
                    assertEquals("$state, $request", state.isCurrent(video), state.isCurrent(request))
                }
                val related = relatedTap(pageOf = CURRENT_VIDEO, url = video.url).copy(serviceId = video.serviceId)
                assertEquals("$state, $related", state.isCurrent(video), state.isCurrent(related))
            }
        }
    }

    // ---- replacesQueue (AM-3, AM-5, AM-6; plan A2.1, one test per row of its table) ----

    /** "not playing: `false` in every row": with no queue there is nothing to replace (AM-1). */
    @Test
    fun am1_noWayReplacesAQueueWhileNothingIsPlaying() {
        for (state in NOTHING_PLAYING) {
            for (way in ALL_WAYS) {
                assertFalse("$way, $state", replacesQueue(way, state))
            }
        }
    }

    /** Row `PAGE_CONTROL`: "the control sends the page's own queue to the player", in every player (AM-3). */
    @Test
    fun am3_aPageControlReplacesTheQueueInEveryPlayer() {
        for (state in SOMETHING_PLAYING) {
            assertTrue("$state", replacesQueue(OpenWay.PAGE_CONTROL, state))
        }
    }

    /** Row `VIDEO_PAGE`, `MAIN`: "opening a page starts or stops the main player" (AM-5, AM-6, answer A17). */
    @Test
    fun am5_am6_openingAVideoPageReplacesTheQueueOfTheMainPlayer() {
        for (queueSize in listOf(1, 3, 40)) {
            assertTrue("queue of $queueSize", replacesQueue(OpenWay.VIDEO_PAGE, playing(PlayerKind.MAIN, queueSize)))
        }
    }

    /**
     * Row `VIDEO_PAGE`, `BACKGROUND` and `POPUP`: "beside another player it only shows the page". Spec,
     * "AM-5 and AM-6, one rule": such a link "opens it as today".
     */
    @Test
    fun am5_am6_openingAVideoPageBesideTheBackgroundOrPopupPlayerReplacesNothing() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 3, 40)) {
                assertFalse("$kind, queue of $queueSize", replacesQueue(OpenWay.VIDEO_PAGE, playing(kind, queueSize)))
            }
        }
    }

    /** Rows `BACKGROUND_PLAYER`, `POPUP_PLAYER`: "the video alone is sent to that player", in every player (AM-6). */
    @Test
    fun am6_thePlayerActionsOfAnotherAppReplaceTheQueueInEveryPlayer() {
        for (way in listOf(OpenWay.BACKGROUND_PLAYER, OpenWay.POPUP_PLAYER)) {
            for (state in SOMETHING_PLAYING) {
                assertTrue("$way, $state", replacesQueue(way, state))
            }
        }
    }

    /** Row `LIST_TAP`: "not used by the rules; listed for completeness": `true` while something is playing. */
    @Test
    fun ac1_aListTapReplacesTheQueueInEveryPlayer() {
        for (state in SOMETHING_PLAYING) {
            assertTrue("$state", replacesQueue(OpenWay.LIST_TAP, state))
        }
    }

    /**
     * The whole table of A2.1 at once: the only `false` cells while playing are `VIDEO_PAGE` beside background and
     * popup.
     */
    @Test
    fun am3_am5_am6_replacesQueueIsFalseOnlyForAVideoPageBesideTheBackgroundOrPopupPlayer() {
        val falseCells = mutableListOf<Pair<OpenWay, PlayerKind?>>()
        for (way in ALL_WAYS) {
            for (state in SOMETHING_PLAYING) {
                if (!replacesQueue(way, state)) falseCells += way to state.kind
            }
        }
        assertEquals(
            listOf(
                OpenWay.VIDEO_PAGE to PlayerKind.BACKGROUND,
                OpenWay.VIDEO_PAGE to PlayerKind.BACKGROUND,
                OpenWay.VIDEO_PAGE to PlayerKind.POPUP,
                OpenWay.VIDEO_PAGE to PlayerKind.POPUP,
            ),
            falseCells,
        )
    }

    // ---- openWayOfRouterChoice (AM-6; plan A2.1) ----
    //
    // The keys are the values of the preference "Preferred 'open' action"; any distinct strings do.

    private val showInfoKey = "show_info"
    private val videoPlayerKey = "video_player"
    private val backgroundPlayerKey = "background_player"
    private val popupPlayerKey = "popup_player"

    private fun wayOf(choice: String): OpenWay? = openWayOfRouterChoice(
        choice = choice,
        showInfo = showInfoKey,
        videoPlayer = videoPlayerKey,
        backgroundPlayer = backgroundPlayerKey,
        popupPlayer = popupPlayerKey,
    )

    /** "`showInfo` and `videoPlayer` -> `VIDEO_PAGE`": both open the video's page (answer A17). */
    @Test
    fun am6_showInfoAndVideoPlayerOpenTheVideoPage() {
        assertEquals(OpenWay.VIDEO_PAGE, wayOf(showInfoKey))
        assertEquals(OpenWay.VIDEO_PAGE, wayOf(videoPlayerKey))
    }

    /** "`backgroundPlayer` -> `BACKGROUND_PLAYER`; `popupPlayer` -> `POPUP_PLAYER`". */
    @Test
    fun am6_backgroundPlayerAndPopupPlayerAreTheirOwnWays() {
        assertEquals(OpenWay.BACKGROUND_PLAYER, wayOf(backgroundPlayerKey))
        assertEquals(OpenWay.POPUP_PLAYER, wayOf(popupPlayerKey))
    }

    /**
     * AM-6: "Actions that only add to the queue, download or show information behave as today."
     * "anything else -> `null`": download, add to playlist, "always ask" and unknown keys never play.
     */
    @Test
    fun am6_downloadAddToPlaylistAndUnknownChoicesAreNoWayToPlay() {
        for (choice in listOf("download", "add_to_playlist", "always_ask", "", "SHOW_INFO", "show_info ", "popup")) {
            assertNull("'$choice'", wayOf(choice))
        }
    }

    /** The choice is compared with the four keys it is given, in the order of plan A2.1's signature. */
    @Test
    fun am6_routerChoiceIsComparedWithTheKeysInTheOrderShowInfoVideoPlayerBackgroundPopup() {
        assertEquals(OpenWay.VIDEO_PAGE, openWayOfRouterChoice("a", "a", "b", "c", "d"))
        assertEquals(OpenWay.VIDEO_PAGE, openWayOfRouterChoice("b", "a", "b", "c", "d"))
        assertEquals(OpenWay.BACKGROUND_PLAYER, openWayOfRouterChoice("c", "a", "b", "c", "d"))
        assertEquals(OpenWay.POPUP_PLAYER, openWayOfRouterChoice("d", "a", "b", "c", "d"))
        assertNull(openWayOfRouterChoice("e", "a", "b", "c", "d"))
    }

    /** The router never yields a list tap or a page control: those have their own hooks. */
    @Test
    fun am6_routerChoiceIsNeverAListTapOrAPageControl() {
        val ways = listOf(showInfoKey, videoPlayerKey, backgroundPlayerKey, popupPlayerKey, "download")
            .map { wayOf(it) }
        assertFalse(OpenWay.LIST_TAP in ways)
        assertFalse(OpenWay.PAGE_CONTROL in ways)
    }

    // ---- queueItemDetails (AM-4; plan A2.1, first match wins, one test per row) ----

    /** Exactly the four effects of plan A2.1. */
    @Test
    fun am4_thereAreExactlyFourDetailsEffects() {
        assertEquals(
            setOf("AS_TODAY", "SHOW_PLAYING_PAGE", "SELECT_THEN_SHOW_PAGE", "NONE"),
            DetailsEffect.values().map { it.name }.toSet(),
        )
    }

    /**
     * Row 1, second half, `player.kind != MAIN`. AM-4, made precise: "With the background or popup
     * player, Details opens the page and touches nothing": upstream's statement runs, for the playing
     * item and for any other.
     */
    @Test
    fun am4_detailsBesideTheBackgroundOrPopupPlayerIsAsToday() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (queueSize in listOf(1, 3)) {
                val state = playing(kind, queueSize = queueSize)
                for (currentIndex in 0 until queueSize) {
                    for (itemIndex in 0 until queueSize) {
                        assertEquals(
                            "$state, item $itemIndex, current $currentIndex",
                            DetailsEffect.AS_TODAY,
                            queueItemDetails(state, itemIndex, currentIndex),
                        )
                    }
                }
            }
        }
    }

    /** Row 1, first half, "not `player.somethingPlaying`": nothing to keep, upstream's statement runs. */
    @Test
    fun am4_detailsWhileNothingIsPlayingIsAsToday() {
        for (state in NOTHING_PLAYING) {
            for ((itemIndex, currentIndex) in listOf(0 to 0, 1 to 0, -1 to 0, -1 to -1, 0 to -1)) {
                assertEquals(
                    "$state, item $itemIndex, current $currentIndex",
                    DetailsEffect.AS_TODAY,
                    queueItemDetails(state, itemIndex, currentIndex),
                )
            }
        }
    }

    /** Row 2, `itemIndex < 0` in the main player: the item left the queue while its menu was open; nothing happens. */
    @Test
    fun am4_detailsOfAnItemThatLeftTheMainPlayersQueueDoesNothing() {
        for (queueSize in listOf(1, 3)) {
            for (currentIndex in 0 until queueSize) {
                val state = playing(PlayerKind.MAIN, queueSize = queueSize)
                assertEquals(
                    "$state, current $currentIndex",
                    DetailsEffect.NONE,
                    queueItemDetails(state, -1, currentIndex),
                )
            }
        }
        assertEquals(DetailsEffect.NONE, queueItemDetails(playing(PlayerKind.MAIN), -5, 0))
    }

    /**
     * Row 3, `itemIndex == currentIndex` in the main player. AM-4, made precise: "Details on the playing
     * item shows its page without a restart".
     */
    @Test
    fun am4_detailsOfThePlayingItemInTheMainPlayerShowsItsPage() {
        for (queueSize in listOf(1, 3, 40)) {
            val state = playing(PlayerKind.MAIN, queueSize = queueSize)
            for (index in listOf(0, queueSize / 2, queueSize - 1)) {
                assertEquals(
                    "$state, item and current $index",
                    DetailsEffect.SHOW_PLAYING_PAGE,
                    queueItemDetails(state, index, index),
                )
            }
        }
    }

    /**
     * Row 4, otherwise. AM-4, made precise: "on another item it moves playback to that item inside the
     * queue and shows its page".
     */
    @Test
    fun am4_detailsOfAnotherItemInTheMainPlayerSelectsItAndShowsItsPage() {
        val state = playing(PlayerKind.MAIN, queueSize = 3)
        for (currentIndex in 0..2) {
            for (itemIndex in (0..2).filter { it != currentIndex }) {
                assertEquals(
                    "item $itemIndex, current $currentIndex",
                    DetailsEffect.SELECT_THEN_SHOW_PAGE,
                    queueItemDetails(state, itemIndex, currentIndex),
                )
            }
        }
    }

    /**
     * Row order, row 1 before row 2: beside the background or popup player, and while nothing is playing,
     * an item that is no longer in the queue is still `AS_TODAY`, not `NONE`. Fails if the two rows are
     * swapped.
     */
    @Test
    fun am4_rowOrder_theKindOfPlayerIsLookedAtBeforeTheItemsPlaceInTheQueue() {
        for (state in NOTHING_PLAYING + playing(PlayerKind.BACKGROUND) + playing(PlayerKind.POPUP)) {
            assertEquals("$state", DetailsEffect.AS_TODAY, queueItemDetails(state, -1, 0))
        }
    }

    /**
     * Row order, row 2 before row 3: in the main player an item that is no longer in the queue (-1) is
     * `NONE` also when the queue reports no current index (-1 as well). Fails if the two rows are swapped.
     */
    @Test
    fun am4_rowOrder_anItemThatLeftTheQueueIsNeverTakenForThePlayingItem() {
        assertEquals(DetailsEffect.NONE, queueItemDetails(playing(PlayerKind.MAIN), -1, -1))
        assertEquals(DetailsEffect.NONE, queueItemDetails(playing(PlayerKind.MAIN, queueSize = 1), -1, -1))
    }

    /**
     * Row order, row 1 before rows 3 and 4: the playing item beside another player is `AS_TODAY`, not a page switch.
     */
    @Test
    fun am4_rowOrder_thePlayingItemBesideAnotherPlayerIsNotShownAsThePlayingPage() {
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            assertEquals("$kind, the playing item", DetailsEffect.AS_TODAY, queueItemDetails(playing(kind), 1, 1))
            assertEquals("$kind, another item", DetailsEffect.AS_TODAY, queueItemDetails(playing(kind), 2, 1))
        }
    }

    /**
     * AM-4: "leaves the queue as it is, in every player". No effect of the table replaces the queue, and
     * the function has no settings parameter: AM-7, "AM-4 applies whatever the switch says".
     */
    @Test
    fun am4_am7_detailsDependsOnlyOnThePlayerAndTheTwoIndexes() {
        val parameters = Class.forName("org.schabi.newpipe.enhancements.queueguard.GuardModelKt")
            .methods.single { it.name == "queueItemDetails" }.parameterTypes.toList()
        assertEquals(
            listOf<Class<*>>(PlayerState::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            parameters,
        )
    }

    // ---- movesForPlayNext (AC-11, "Play all next") ----
    //
    // The glue appends the list to the player's queue and then moves the appended videos, one move per
    // video, to just behind the current item. A move is applied as PlayQueue.move does on its list:
    // add(to, removeAt(from)).

    private fun <T> MutableList<T>.applyMoves(moves: List<QueueMove>) {
        for (move in moves) add(move.to, removeAt(move.from))
    }

    private fun queueAfterPlayAllNext(queue: List<String>, currentIndex: Int, list: List<String>): List<String> {
        val appended = (queue + list).toMutableList()
        appended.applyMoves(movesForPlayNext(appended.size, list.size, currentIndex))
        return appended
    }

    @Test
    fun ac11_playAllNext_currentItemFirst_videosLandRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "x", "y", "b", "c"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 0, list = listOf("x", "y")),
        )
    }

    @Test
    fun ac11_playAllNext_currentItemInTheMiddle_videosLandRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "b", "x", "y", "z", "c", "d"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c", "d"), currentIndex = 1, list = listOf("x", "y", "z")),
        )
    }

    @Test
    fun ac11_playAllNext_currentItemLast_videosStayRightBehindItInListOrder() {
        assertEquals(
            listOf("a", "b", "c", "x", "y"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 2, list = listOf("x", "y")),
        )
    }

    @Test
    fun ac11_playAllNext_queueOfOneItem_videosFollowIt() {
        assertEquals(
            listOf("a", "x", "y", "z"),
            queueAfterPlayAllNext(queue = listOf("a"), currentIndex = 0, list = listOf("x", "y", "z")),
        )
    }

    @Test
    fun ac11_playAllNext_listOfOneVideo_landsRightBehindTheCurrentItem() {
        assertEquals(
            listOf("a", "b", "x", "c"),
            queueAfterPlayAllNext(queue = listOf("a", "b", "c"), currentIndex = 1, list = listOf("x")),
        )
    }

    /** Plan 3.1: i in 0 until added -> (sizeAfterAppend - added + i, currentIndex + 1 + i). */
    @Test
    fun ac11_movesForPlayNext_isOneMovePerVideoFromTheTailToBehindTheCurrentItem() {
        assertEquals(
            "queue of 3 on its first item, 2 videos appended",
            listOf(QueueMove(3, 1), QueueMove(4, 2)),
            movesForPlayNext(5, 2, 0),
        )
        assertEquals(
            "queue of 4 on its second item, 3 videos appended",
            listOf(QueueMove(4, 2), QueueMove(5, 3), QueueMove(6, 4)),
            movesForPlayNext(7, 3, 1),
        )
        assertEquals(
            "queue of 3 on its second item, 1 video appended",
            listOf(QueueMove(3, 2)),
            movesForPlayNext(4, 1, 1),
        )
    }

    /** Plan 3.1: "minus from == to". With the current item last, the appended videos are in place already. */
    @Test
    fun ac11_movesForPlayNext_leavesOutMovesThatMoveNothing() {
        assertEquals("current item last", emptyList<QueueMove>(), movesForPlayNext(5, 2, 2))
        assertEquals("queue of one item", emptyList<QueueMove>(), movesForPlayNext(4, 3, 0))
    }

    @Test
    fun ac11_movesForPlayNext_nothingAddedNeedsNoMove() {
        assertEquals(emptyList<QueueMove>(), movesForPlayNext(3, 0, 1))
    }

    /** Every queue of 1..6 items, every current index, every list of 1..5 videos. */
    @Test
    fun ac11_movesForPlayNext_putsTheListBehindTheCurrentItemForEveryQueueAndPosition() {
        for (queueSize in 1..6) {
            for (currentIndex in 0 until queueSize) {
                for (added in 1..5) {
                    val queue = List(queueSize) { "q$it" }
                    val list = List(added) { "new$it" }
                    val case = "queue of $queueSize on index $currentIndex, $added added"

                    val moves = movesForPlayNext(queueSize + added, added, currentIndex)
                    val result = (queue + list).toMutableList().apply { applyMoves(moves) }

                    val expected =
                        queue.subList(0, currentIndex + 1) + list + queue.subList(currentIndex + 1, queueSize)
                    assertEquals("$case: order", expected, result)
                    assertEquals("$case: the current item keeps its index", "q$currentIndex", result[currentIndex])
                    assertTrue("$case: no move with from == to in $moves", moves.none { it.from == it.to })
                    val expectedMoves = if (currentIndex == queueSize - 1) 0 else added
                    assertEquals("$case: one move per video that has to move", expectedMoves, moves.size)
                }
            }
        }
    }

    // ---- streamDetailsInsertIndex (AC-17) ----
    //
    // Menus are given as string resource ids. Any ints do.

    private val details = 7001
    private val anchor = 7002
    private val other1 = 7003
    private val other2 = 7004
    private val other3 = 7005

    /** AC-17: "Where the menu has that entry already, nothing changes." */
    @Test
    fun ac17_streamDetailsIsNotInsertedWhereTheMenuHasItAlready() {
        assertEquals("only entry", -1, streamDetailsInsertIndex(listOf(details), details, anchor))
        assertEquals("no anchor", -1, streamDetailsInsertIndex(listOf(other1, details, other2), details, anchor))
        assertEquals("before anchor", -1, streamDetailsInsertIndex(listOf(other1, details, anchor), details, anchor))
        assertEquals("behind anchor", -1, streamDetailsInsertIndex(listOf(anchor, other1, details), details, anchor))
    }

    @Test
    fun ac17_streamDetailsGoesToTheIndexOfTheAnchorEntry() {
        assertEquals("anchor first", 0, streamDetailsInsertIndex(listOf(anchor, other1, other2), details, anchor))
        assertEquals(
            "anchor in the middle",
            2,
            streamDetailsInsertIndex(listOf(other1, other2, anchor, other3), details, anchor),
        )
        assertEquals("anchor last", 2, streamDetailsInsertIndex(listOf(other1, other2, anchor), details, anchor))
        assertEquals("anchor alone", 0, streamDetailsInsertIndex(listOf(anchor), details, anchor))
    }

    @Test
    fun ac17_streamDetailsIsAppendedWhereTheMenuHasNoAnchorEntry() {
        assertEquals("three others", 3, streamDetailsInsertIndex(listOf(other1, other2, other3), details, anchor))
        assertEquals("one other entry", 1, streamDetailsInsertIndex(listOf(other1), details, anchor))
        assertEquals("empty menu", 0, streamDetailsInsertIndex(emptyList(), details, anchor))
    }

    /** Inserting at the returned index puts the entry directly in front of the anchor and keeps the rest. */
    @Test
    fun ac17_streamDetailsInsertedAtTheReturnedIndexSitsInFrontOfTheAnchor() {
        val menu = mutableListOf(other1, anchor, other2)
        menu.add(streamDetailsInsertIndex(menu, details, anchor), details)
        assertEquals(listOf(other1, details, anchor, other2), menu)
    }
}
