package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases the owner reported after the device test of build 5 (issue mselin/PipePipe#24, spec
 * "Amendment 2"), each written as one scenario from the tap to what the guard answers, so that the
 * complaint cannot come back unnoticed. A scenario runs the real [QueueGuardPolicy] under the real
 * [QueueGuardController], and, where the queue is kept by a hook rather than by a rule, also checks that
 * the hook line is in the upstream file (the same lines [QueueGuardWiringTest] pins in full).
 *
 * What a scenario cannot show on the JVM is the player itself keeping its queue and position; that is
 * device script steps 1, 9, 10 and 11 (plan A2.11, A2.15, A2.16).
 */
class QueueGuardReportedCasesTest {

    private val marker = "ENH:queue-guard"
    private val queueGuard = "org.schabi.newpipe.enhancements.queueguard.QueueGuard"
    private val videoDetailFragment = "java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.kt"

    private val settings = SettableGuardSettings(ask = true)

    private fun scene(state: PlayerState): GuardScene = GuardScene(QueueGuardPolicy(settings), state)

    private fun videoPageLines(): List<String> =
        MainSources.file(videoDetailFragment, "the video page, which hooks P1 to P6 change").readLines()

    /** A video tap in the feed on [url] (hook T4): a plain list tap, not in a related list. */
    private fun feedTap(url: String, title: String = "Video in the feed"): TapRequest =
        TapRequest(SERVICE, url, title, false)

    // ---- Reported: "I watch one video, auto-enqueue adds the next ones, I tap a video in the feed and
    //      my queue is gone without a question" ----

    /**
     * AM-1: "one video counts, and so do videos added by auto-enqueue next stream". The main player
     * holds one chosen video and zero to three auto-enqueued ones. A tap on another video in the feed is
     * taken over, only the dialog opens, and it shows the whole queue. Before Amendment 2 every one of
     * these taps replaced the queue at once (answers A11, A13).
     */
    @Test
    fun am1_reported_oneChosenVideoPlusAutoQueuedOnesInTheMainPlayer_aTapInTheFeedAsks() {
        for (autoQueued in 0..3) {
            val queueSize = 1 + autoQueued
            val scene = scene(playing(PlayerKind.MAIN, queueSize = queueSize))

            val takenOver = scene.tap(feedTap(TAPPED_URL))

            assertTrue("1 chosen + $autoQueued auto-enqueued: the tap must not go through", takenOver)
            assertEquals(
                "1 chosen + $autoQueued auto-enqueued: only the dialog, with the whole queue",
                listOf(askedForTap("Video in the feed", queueSize)),
                scene.journal.all,
            )

            scene.ui.choose(GuardChoice.CANCEL)
            assertEquals(
                "1 chosen + $autoQueued auto-enqueued: Cancel leaves everything as it was (AC-6)",
                listOf(askedForTap("Video in the feed", queueSize)),
                scene.journal.all,
            )
        }
    }

    /** AM-1 for the play buttons of the feed (hook B5): the same queue is asked about before Play All replaces it. */
    @Test
    fun am1_reported_oneChosenVideoPlusAutoQueuedOnesInTheMainPlayer_playAllInTheFeedAsks() {
        for (autoQueued in 0..3) {
            val queueSize = 1 + autoQueued
            val scene = scene(playing(PlayerKind.MAIN, queueSize = queueSize))

            scene.pressPlayButton(videoCount = 30)

            assertEquals(
                "1 chosen + $autoQueued auto-enqueued",
                listOf(askedForList(30, queueSize)),
                scene.journal.all,
            )
        }
    }

    /**
     * AM-1, the other side of the same report: nothing in the model or in the upstream files can make an
     * auto-enqueued video count less than a chosen one. The state carries no such number and
     * `PlayQueue.java` reports nothing to the guard (hook Q1 removed).
     */
    @Test
    fun am1_reported_nothingTellsAutoQueuedVideosFromChosenOnesAnyMore() {
        val fields = PlayerState::class.java.declaredFields.map { it.name }
        assertFalse("PlayerState.autoQueuedCount is removed", "autoQueuedCount" in fields)
        val playQueue = MainSources.file(
            "java/org/schabi/newpipe/player/playqueue/PlayQueue.java",
            "upstream's play queue, which hook Q1 used to change",
        ).readLines()
        assertEquals(
            "lines of PlayQueue.java marked $marker or naming QueueGuard",
            emptyList<String>(),
            playQueue.filter { marker in it || "QueueGuard" in it }.map { it.trim() },
        )
    }

    // ---- Reported: "I tap the video that is playing in the feed and the rest of my queue is gone" ----

    /**
     * AC-8, AM-9, AM-11: the playing video tapped in a feed, with a queue of three behind it, in every
     * player, switch on or off, Music Player Mode or not. The guard never asks and never "plays now":
     * the tap goes through (`false`) and not one action runs. In particular the video is not started
     * alone in the background or popup player, which is what the switch-off row does for any other
     * video and would drop the other two.
     */
    @Test
    fun ac8_am11_reported_thePlayingVideoTappedInTheFeed_theGuardNeitherAsksNorStartsItAlone() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (kind in ALL_KINDS) {
                for (music in listOf(false, true)) {
                    val scene = scene(playing(kind, queueSize = 3))

                    val takenOver = scene.tap(TapRequest(SERVICE, CURRENT_URL, "Playing video", music))

                    val case = "switch $ask, $kind, Music Player Mode $music"
                    assertFalse("$case: upstream's tap runs", takenOver)
                    assertEquals("$case: no dialog, no action", emptyList<String>(), scene.journal.all)
                }
            }
        }
    }

    /**
     * AM-9, AM-11: what then keeps the queue when upstream's tap reopens the page of the playing video.
     * The decision of `QueueGuard.playersQueueFor` is `PlayerState.isCurrent(VideoRef)` (plan A2.15):
     * `true` for the page's video in every player, whatever the queue length. And both hooks that use
     * it are in the page: P6, so that loading the page does not close the main player (Autoplay "never",
     * or "only on Wi-Fi" on mobile data), and P5, so that the page's own start and its controls send the
     * player's queue instead of the one video.
     */
    @Test
    fun am9_am11_reported_thePlayingVideoTappedInTheFeed_thePageKeepsThePlayerAndItsQueue() {
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(1, 3, 40)) {
                val state = playing(kind, queueSize = queueSize)
                assertTrue(
                    "$state: the reopened page shows the current item",
                    state.isCurrent(VideoRef(SERVICE, CURRENT_URL)),
                )
            }
        }
        val marked = videoPageLines().filter { marker in it }
        assertEquals(
            "P6: the page that loaded the current item takes the player's queue and only else hides the player",
            1,
            marked.count {
                "$queueGuard.playersQueueFor(result.info)" in it &&
                    "if (playing != null) vm.playQueue = playing else hideMainPlayerOnLoadingNewStream()" in it
            },
        )
        assertEquals(
            "P5: what the page sends is the player's queue while it shows the current item",
            1,
            marked.count { "$queueGuard.playersQueueFor(vm.currentInfo)?.let { return it }" in it },
        )
    }

    /**
     * AM-11 for the other ways to the page of the playing video: "a typed address, a link and show
     * info". None of them is taken over or asked about, whatever the queue length and the switch; the
     * page opens as today and P6 keeps the player.
     */
    @Test
    fun am11_reported_thePageOfThePlayingVideoOpenedByAddressLinkOrShowInfoIsNeverTakenOver() {
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (kind in ALL_KINDS) {
                val scene = scene(playing(kind, queueSize = 3))

                val takenOver = scene.tap(TapRequest(SERVICE, CURRENT_URL, CURRENT_URL, way = OpenWay.VIDEO_PAGE))

                assertFalse("switch $ask, $kind", takenOver)
                assertEquals("switch $ask, $kind", emptyList<String>(), scene.journal.all)
            }
        }
    }

    // ---- Reported: "On the page of the playing video I press Popup (or Background, or play) and the
    //      player drops the other videos" ----

    /**
     * AM-9: "On the video page of the current item, the page's controls never replace a longer queue
     * with that one video." The page of the playing video was reopened from a list, so it holds that
     * video alone; the player holds three. A press on play/thumbnail, Background, Popup or the mini
     * player's play button (all `PAGE_CONTROL`) is not taken over, with the switch on or off: the
     * control's own statements run. They take their queue from `playQueueForIntent()`, and its first
     * line is hook P5, which hands them the player's queue because the page's video is the current item.
     */
    @Test
    fun am9_reported_aPageControlOnTheReopenedPageOfThePlayingVideoSendsThePlayersQueue() {
        val pageVideo = VideoRef(SERVICE, CURRENT_URL)
        for (ask in listOf(true, false)) {
            settings.ask = ask
            for (kind in ALL_KINDS) {
                val state = playing(kind, queueSize = 3)
                val scene = scene(state)

                val takenOver =
                    scene.tap(TapRequest(SERVICE, CURRENT_URL, "Playing video", way = OpenWay.PAGE_CONTROL))

                assertFalse("switch $ask, $kind: the control runs as today", takenOver)
                assertEquals("switch $ask, $kind: no dialog, no action", emptyList<String>(), scene.journal.all)
                assertTrue("switch $ask, $kind: P5 gives the player's queue for this page", state.isCurrent(pageVideo))
            }
        }

        val lines = videoPageLines()
        val head = lines.indexOfFirst { it.trim() == "private fun playQueueForIntent(): PlayQueue? {" }
        assertTrue("VideoDetailFragment.kt defines playQueueForIntent()", head >= 0)
        assertEquals(
            "P5 is the first line of playQueueForIntent(), in front of the page's own queue",
            "$queueGuard.playersQueueFor(vm.currentInfo)?.let { return it } // $marker",
            lines[head + 1].trim(),
        )
        assertEquals(
            "Background, Popup and the main player all send what playQueueForIntent() gives",
            3,
            lines.count { it.trim() == "val queue = playQueueForIntent() ?: return" },
        )
    }

    /**
     * AM-9 does not open a back door for AM-3: where P5 gives nothing, because the page shows another
     * video than the one playing, the same press would send that one video, and so it asks first.
     * `isCurrent` is `false` for that page exactly where the dialog opens.
     */
    @Test
    fun am3_am9_reported_aPageControlOnThePageOfAnotherVideoAsksBecauseThatPageHasOnlyItsOwnVideoToSend() {
        val pageVideo = VideoRef(SERVICE, TAPPED_URL)
        for (kind in ALL_KINDS) {
            val state = playing(kind, queueSize = 3)
            val scene = scene(state)

            val takenOver = scene.tap(TapRequest(SERVICE, TAPPED_URL, "Another video", way = OpenWay.PAGE_CONTROL))

            assertFalse("$kind: P5 gives nothing for this page", state.isCurrent(pageVideo))
            assertTrue("$kind: so the press is taken over", takenOver)
            assertEquals("$kind", listOf(askedForTap("Another video", 3)), scene.journal.all)
        }
    }

    // ---- Reported with Q15: "a link to a time in the playing video restarts it alone" ----

    /**
     * AM-10: "A link to a time in the current item seeks in place and keeps the queue, also when the
     * queue is longer than that one video." The glue of hook L1 seeks when `isCurrent(VideoRef)` is
     * `true` for the link's video; it is, for a queue of one and of three in every player. If the
     * request reached the rules it would still not be asked about or replayed as a new queue by a
     * choice: it is not taken over. A link to another video in the main player is asked about.
     */
    @Test
    fun am10_reported_aTimeLinkToThePlayingVideoOfALongerQueueIsASeekNotANewQueue() {
        for (kind in ALL_KINDS) {
            for (queueSize in listOf(1, 3)) {
                val state = playing(kind, queueSize = queueSize)
                assertTrue(
                    "$state: the link's video is the current item, so the glue seeks",
                    state.isCurrent(CURRENT_VIDEO),
                )

                val scene = scene(state)
                assertFalse(
                    "$state",
                    scene.tap(TapRequest(SERVICE, CURRENT_URL, CURRENT_URL, way = OpenWay.VIDEO_PAGE)),
                )
                assertEquals("$state", emptyList<String>(), scene.journal.all)
            }
        }
        val other = scene(playing(PlayerKind.MAIN, queueSize = 3))
        assertFalse(playing(PlayerKind.MAIN).isCurrent(VideoRef(SERVICE, TAPPED_URL)))
        assertTrue(other.tap(TapRequest(SERVICE, TAPPED_URL, TAPPED_URL, way = OpenWay.VIDEO_PAGE)))
        assertEquals(listOf(askedForTap(TAPPED_URL, 3)), other.journal.all)
    }

    // ---- Reported: "Details in the queue screen throws my queue away" ----

    /**
     * AM-4: "Details in a queue item's menu opens that video's page and leaves the queue as it is, in
     * every player." Main player with three items on the first: no effect of the table is "open as
     * today", which is what replaced the queue (plan A2.5). Background and popup player: as today, which
     * there only opens the page.
     */
    @Test
    fun am4_reported_detailsOfAQueueItemNeverTakesTheRouteThatReplacedTheQueueInTheMainPlayer() {
        val main = playing(PlayerKind.MAIN, queueSize = 3)
        assertEquals("the playing item", DetailsEffect.SHOW_PLAYING_PAGE, queueItemDetails(main, 0, 0))
        assertEquals("the second item", DetailsEffect.SELECT_THEN_SHOW_PAGE, queueItemDetails(main, 1, 0))
        assertEquals("the third item", DetailsEffect.SELECT_THEN_SHOW_PAGE, queueItemDetails(main, 2, 0))
        for (itemIndex in -1..2) {
            assertFalse(
                "main player, item $itemIndex",
                queueItemDetails(main, itemIndex, 0) == DetailsEffect.AS_TODAY,
            )
        }
        for (kind in listOf(PlayerKind.BACKGROUND, PlayerKind.POPUP)) {
            for (itemIndex in 0..2) {
                assertEquals(
                    "$kind, item $itemIndex",
                    DetailsEffect.AS_TODAY,
                    queueItemDetails(playing(kind), itemIndex, 0),
                )
            }
        }
    }
}
