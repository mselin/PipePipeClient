package org.schabi.newpipe.enhancements.subscriptionscale

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.feed.service.FeedUpdateInfo

/**
 * AC-1 (finding M6, plan section 3): what a feed refresh keeps of each stored batch.
 * `FeedResultRetention.retain(batch, retainNewStreams)`:
 * a manual refresh (`false`) keeps nothing; the notification run (`true`) keeps one slim record
 * per channel that has new videos (name, address, picture, its new items) and nothing else.
 */
class FeedResultRetentionTest {

    private fun stream(channel: Long, n: Int) =
        StreamInfoItem(0, "https://example.invalid/watch/$channel/$n", "Video $n of $channel", StreamType.VIDEO_STREAM)

    /** A fetched channel with [streamCount] videos, the first [newCount] of them new; `null` leaves `newStreams` unset. */
    private fun info(uid: Long, streamCount: Int = 3, newCount: Int? = 0, errors: List<Throwable> = emptyList()): FeedUpdateInfo {
        val streams = (1..streamCount).map { stream(uid, it) }
        val info = FeedUpdateInfo(
            uid = uid,
            notificationMode = 1,
            name = "Channel $uid",
            avatarUrl = "https://img.invalid/$uid.jpg",
            url = "https://example.invalid/channel/$uid",
            serviceId = 3,
            description = "d".repeat(200),
            subscriberCount = 5000 + uid,
            streams = streams,
            errors = errors
        )
        if (newCount != null) info.newStreams = streams.take(newCount)
        return info
    }

    private fun next(info: FeedUpdateInfo) = Notification.createOnNext(info)

    @Test
    fun ac1_manualRefresh_keepsNothing() {
        val batch = listOf(next(info(1, newCount = 2)), next(info(2, newCount = 0)), next(info(3, newCount = 3)))

        assertEquals(
            "AC-1: with retainNewStreams = false nothing of a batch is kept",
            emptyList<Notification<FeedUpdateInfo>>(),
            FeedResultRetention.retain(batch, false)
        )
    }

    @Test
    fun ac1_manualRefresh_keepsNothing_alsoOfErrorsAndUnsetChannels() {
        val batch = listOf(
            Notification.createOnError<FeedUpdateInfo>(RuntimeException("fetch failed")),
            next(info(1, newCount = null))
        )

        assertTrue("AC-1: nothing is kept", FeedResultRetention.retain(batch, false).isEmpty())
    }

    @Test
    fun ac1_emptyBatch_staysEmpty() {
        assertTrue(FeedResultRetention.retain(emptyList(), true).isEmpty())
        assertTrue(FeedResultRetention.retain(emptyList(), false).isEmpty())
    }

    @Test
    fun ac1_notificationRun_keepsOnlyChannelsWithNewVideos_inOrder() {
        val batch = listOf(
            next(info(1, newCount = 0)),
            next(info(2, newCount = 1)),
            next(info(3, newCount = 0)),
            next(info(4, newCount = 3)),
            next(info(5, newCount = 2))
        )

        val kept = FeedResultRetention.retain(batch, true)

        assertTrue("AC-1: every kept entry is an onNext notification", kept.all { it.isOnNext })
        assertEquals(
            "AC-1: only channels with new videos are kept, in the order of the batch",
            listOf(2L, 4L, 5L),
            kept.map { it.value!!.uid }
        )
    }

    @Test
    fun ac1_notificationRun_dropsTheVideoListAndTheErrors() {
        val batch = listOf(next(info(7, streamCount = 60, newCount = 2, errors = listOf(RuntimeException("partly failed")))))

        val kept = FeedResultRetention.retain(batch, true).single().value!!

        assertEquals("AC-1: the channel's whole video list is not kept", emptyList<StreamInfoItem>(), kept.streams)
        assertEquals("AC-1: the channel's errors are not kept", emptyList<Throwable>(), kept.errors)
    }

    /** "New-video notifications work as today": everything `NotificationHelper` reads is still there. */
    @Test
    fun ac1_notificationRun_keepsWhatTheNotificationShows() {
        val original = info(7, streamCount = 60, newCount = 2)

        val kept = FeedResultRetention.retain(listOf(next(original)), true).single().value!!

        assertEquals("AC-1: name", "Channel 7", kept.name)
        assertEquals("AC-1: url", "https://example.invalid/channel/7", kept.url)
        assertEquals("AC-1: avatarUrl", "https://img.invalid/7.jpg", kept.avatarUrl)
        assertEquals("AC-1: serviceId", 3, kept.serviceId)
        assertEquals("AC-1: pseudoId (the notification id)", original.pseudoId, kept.pseudoId)
        assertEquals("AC-1: uid", 7L, kept.uid)
        assertEquals("AC-1: notificationMode", 1, kept.notificationMode)
        assertEquals("AC-1: the number of new videos", 2, kept.newStreams.size)
        assertEquals("AC-1: the titles of the new videos", listOf("Video 1 of 7", "Video 2 of 7"), kept.newStreams.map { it.name })
        for (i in 0..1) {
            assertSame("AC-1: the new items themselves are kept, not copies", original.newStreams[i], kept.newStreams[i])
        }
    }

    /**
     * Plan section 3: `newStreams` stays unset for a channel unsubscribed during the refresh.
     * Reading it throws; today that fails the notification run. Such a channel is dropped.
     */
    @Test
    fun ac1_notificationRun_dropsAChannelWhoseNewStreamsWereNeverSet() {
        val batch = listOf(next(info(1, newCount = null)), next(info(2, newCount = 1)), next(info(3, newCount = null)))

        val kept = FeedResultRetention.retain(batch, true)

        assertEquals("AC-1: the unset channels are dropped without an exception", listOf(2L), kept.map { it.value!!.uid })
    }

    @Test
    fun ac1_notificationRun_dropsFailedChannelsAndOtherNotifications() {
        val batch = listOf(
            Notification.createOnError<FeedUpdateInfo>(RuntimeException("fetch failed")),
            next(info(2, newCount = 1)),
            Notification.createOnComplete<FeedUpdateInfo>(),
            Notification.createOnError<FeedUpdateInfo>(IllegalStateException("another"))
        )

        val kept = FeedResultRetention.retain(batch, true)

        assertEquals("AC-1: onError entries are not kept", listOf(2L), kept.map { it.value!!.uid })
    }

    /** What `NotificationWorker` does with the result: it reads `newStreams` of every value. */
    @Test
    fun ac1_notificationRun_everyKeptEntryCanBeReadAsTheWorkerDoes() {
        val batch = (1L..20L).map { uid ->
            when {
                uid % 5 == 0L -> Notification.createOnError(RuntimeException("failed $uid"))
                uid % 3 == 0L -> next(info(uid, newCount = null))
                else -> next(info(uid, newCount = (uid % 2).toInt()))
            }
        }

        val withNew = FeedResultRetention.retain(batch, true).mapNotNull {
            it.value?.takeIf { info -> info.newStreams.isNotEmpty() }
        }

        assertEquals(
            "AC-1: the worker finds exactly the channels with new videos",
            listOf(1L, 7L, 11L, 13L, 17L, 19L),
            withNew.map { it.uid }
        )
    }

    // ---- at the owner's scale: 1600 channels in 80 batches, 60 videos each ----

    /** The refresh of plan section 3, with RxJava for real: buffer, store, retain, collect, flatten. */
    private fun refresh(infos: List<FeedUpdateInfo>, retainNewStreams: Boolean): List<Notification<FeedUpdateInfo>> =
        Flowable.fromIterable(infos)
            .map { Notification.createOnNext(it) }
            .buffer(BATCH)
            .map { batch -> FeedResultRetention.retain(batch, retainNewStreams) }
            .toList()
            .map { it.flatten() }
            .blockingGet()

    @Test
    fun ac1_scale_manualRefreshOf1600ChannelsHoldsNothingAtTheEnd() {
        val infos = (1L..SUBSCRIPTIONS).map { info(it, streamCount = 60, newCount = (it % 4).toInt()) }

        val held = refresh(infos, retainNewStreams = false)

        assertEquals("AC-1: 1600 channels in 80 batches, 96000 videos fetched: nothing is held at the end", 0, held.size)
    }

    @Test
    fun ac1_scale_notificationRunHoldsOneSlimRecordPerChannelWithNewVideos() {
        // every 64th channel has two new videos: 25 of 1600
        val infos = (1L..SUBSCRIPTIONS).map { info(it, streamCount = 60, newCount = if (it % 64 == 0L) 2 else 0) }

        val held = refresh(infos, retainNewStreams = true)

        assertEquals("AC-1: one record per channel with new videos, not per channel refreshed", 25, held.size)
        assertEquals("AC-1: of the 96000 videos fetched, none is held through `streams`", 0, held.sumOf { it.value!!.streams.size })
        assertEquals("AC-1: only the new videos are held", 50, held.sumOf { it.value!!.newStreams.size })
        assertEquals("AC-1: no error is held per channel", 0, held.sumOf { it.value!!.errors.size })
        assertEquals("AC-1: in refresh order", (64L..SUBSCRIPTIONS step 64).toList(), held.map { it.value!!.uid })
    }

    /** What is held does not depend on how many channels without new videos were refreshed. */
    @Test
    fun ac1_scale_whatIsHeldDoesNotGrowWithTheNumberOfChannelsRefreshed() {
        fun heldAfter(channels: Int): Int {
            val infos = (1L..channels).map { info(it, streamCount = 30, newCount = if (it <= 3) 1 else 0) }
            val held = refresh(infos, retainNewStreams = true)
            return held.size + held.sumOf { it.value!!.streams.size + it.value!!.newStreams.size + it.value!!.errors.size }
        }

        assertEquals("AC-1: 100 and 1600 channels refreshed, three with a new video: the same is held", heldAfter(100), heldAfter(SUBSCRIPTIONS))
        assertEquals(6, heldAfter(SUBSCRIPTIONS))
    }
}
