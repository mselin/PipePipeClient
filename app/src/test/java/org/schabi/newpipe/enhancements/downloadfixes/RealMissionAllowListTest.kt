package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.streams.io.StoredFileHelper
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.MissionRecoveryInfo
import us.shandian.giga.get.SabrDownloadCheckpoint
import us.shandian.giga.get.SabrResourceCheckpoint
import us.shandian.giga.hls.state.HlsDownloadCheckpoint
import us.shandian.giga.hls.state.HlsResourceCheckpoint
import us.shandian.giga.postprocessing.Postprocessing
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * AC-4 and AC-5, plan section 5: "a real `DownloadMission` (public constructor, null storage)
 * with headers, HLS and SABR checkpoints and `MissionRecoveryInfo`, written and read back".
 *
 * This is the test against the plan's first risk, an allow-list that is too narrow: a real
 * pending download whose state the new reader refuses would be set aside at every start. The
 * classes are upstream's and the extractor's own; nothing of Android is called (the mission
 * is only built, written and read), so the test runs on the JVM against the stub `android.jar`.
 */
class RealMissionAllowListTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sabrInfo(): YoutubeSabrInfo {
        val format = YoutubeSabrInfo.Format.fromParsedFormat(
            ItagItem.getItag(251), 1700000000000L, "acont=original:lang=en", "audio/webm; codecs=\"opus\"",
            "en.4", "English original", false, "https://example.invalid/init", 0L, 258L
        )
        return YoutubeSabrInfo(
            "dQw4w9WgXcQ", "cpn0123456789abc", "2.20251101.00.00", "visitor", "https://example.invalid/sabr",
            "config", arrayListOf(format), byteArrayOf(1, 2, 3), true
        )
    }

    private fun mission(): DownloadMission {
        val urls = arrayOf("https://example.invalid/video.mp4#cookie=SESSDATA%3Dabc", "https://example.invalid/audio.m4a", null)
        val mission = DownloadMission(
            urls,
            StoredFileHelper(null, "video.mp4", "video/mp4", "video"), // the "invalid" storage a mission has while its file is gone
            'v',
            Postprocessing.getAlgorithm(Postprocessing.ALGORITHM_MP4_FROM_DASH_MUXER, arrayOf("arg")),
            null
        )
        mission.source = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        mission.timestamp = 1731628800000L
        mission.length = 123456789L
        mission.nearLength = 223456789L
        mission.done = 5L * 1024 * 1024 * 1024
        mission.offsets = longArrayOf(0L, 123456789L, 0L)
        mission.current = 1
        mission.threadCount = 6
        mission.unknownLength = true
        mission.psState = 1
        mission.enqueued = true
        mission.errCode = DownloadMission.ERROR_TIMEOUT
        mission.errObject = IOException("unexpected end of stream", SocketTimeoutException("timeout"))
        mission.resourceDeliveryMethods = arrayOf("PROGRESSIVE_HTTP", "HLS", "SABR")
        mission.resourceManifestUrls = arrayOf(null, "https://example.invalid/master.m3u8", null)
        mission.resourceIsUrls = booleanArrayOf(true, true, false)
        @Suppress("UNCHECKED_CAST")
        mission.resourceRequestHeaders = arrayOf<HashMap<String, String>?>(
            hashMapOf("Referer" to "https://www.bilibili.com", "Cookie" to "SESSDATA=abc"), null, HashMap()
        ) as Array<HashMap<String, String>>
        mission.hlsCheckpoint = HlsDownloadCheckpoint(
            resources = listOf(
                HlsResourceCheckpoint(1, "https://example.invalid/master.m3u8", "https://example.invalid/media.m3u8", "/tmp/hls_1.ts", 42, 0L, 4242L, 100L, "fingerprint"),
                HlsResourceCheckpoint(2, "https://example.invalid/master2.m3u8", null, "/tmp/hls_2.ts", 0, 0L, 0L, 0L, null),
            ),
            remuxStarted = true,
            remuxOutputPath = "/tmp/remux.mp4",
        )
        mission.sabrCheckpoint = SabrDownloadCheckpoint(
            resources = listOf(SabrResourceCheckpoint(2, 251, "acont=original:lang=en", "/tmp/sabr_2.webm", 17, 170000L, 258))
        )
        mission.sabrStarted = true
        mission.recoveryInfo = arrayOf(
            MissionRecoveryInfo(MediaFormat.MPEG_4, desired = "1080p", isDesired2 = true, kind = 'v', itag = 137),
            MissionRecoveryInfo(MediaFormat.M4A, desiredBitrate = 128, kind = 'a', audioTrackId = "en.4", isHls = true),
            MissionRecoveryInfo(MediaFormat.WEBMA_OPUS, kind = 'a', isSabr = true, itag = 251, deliveryMethodInfo = sabrInfo()),
        )
        return mission
    }

    private fun assertSameMission(expected: DownloadMission, actual: DownloadMission) {
        assertArrayEquals(expected.urls, actual.urls)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.length, actual.length)
        assertEquals(expected.nearLength, actual.nearLength)
        assertEquals(expected.done, actual.done)
        assertArrayEquals(expected.offsets, actual.offsets)
        assertEquals(expected.current, actual.current)
        assertEquals(expected.threadCount, actual.threadCount)
        assertEquals(expected.unknownLength, actual.unknownLength)
        assertEquals(expected.psState, actual.psState)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.errCode, actual.errCode)
        assertEquals(expected.errObject.message, actual.errObject.message)
        assertTrue(actual.errObject.cause is SocketTimeoutException)
        assertArrayEquals(expected.resourceDeliveryMethods, actual.resourceDeliveryMethods)
        assertArrayEquals(expected.resourceManifestUrls, actual.resourceManifestUrls)
        assertArrayEquals(expected.resourceIsUrls, actual.resourceIsUrls)
        assertArrayEquals(expected.resourceRequestHeaders, actual.resourceRequestHeaders)
        assertEquals(expected.hlsCheckpoint, actual.hlsCheckpoint)
        assertEquals(expected.sabrCheckpoint, actual.sabrCheckpoint)
        assertEquals(expected.sabrStarted, actual.sabrStarted)
        assertEquals(expected.psAlgorithm.javaClass, actual.psAlgorithm.javaClass)
        assertEquals(expected.psAlgorithm.name, actual.psAlgorithm.name)
        assertEquals("video.mp4", actual.storage.name)
        assertTrue(actual.storage.isInvalid)
        assertEquals(expected.recoveryInfo.size, actual.recoveryInfo.size)
        for (i in expected.recoveryInfo.indices) {
            assertEquals(expected.recoveryInfo[i].format, actual.recoveryInfo[i].format)
            assertEquals(expected.recoveryInfo[i].desired, actual.recoveryInfo[i].desired)
            assertEquals(expected.recoveryInfo[i].kind, actual.recoveryInfo[i].kind)
            assertEquals(expected.recoveryInfo[i].itag, actual.recoveryInfo[i].itag)
            assertEquals(expected.recoveryInfo[i].isHls, actual.recoveryInfo[i].isHls)
            assertEquals(expected.recoveryInfo[i].isSabr, actual.recoveryInfo[i].isSabr)
        }
        val sabr = actual.recoveryInfo[2].deliveryMethodInfo as YoutubeSabrInfo
        assertEquals(1, sabr.formats.size)
        assertEquals(251, sabr.formats[0].itag)
        assertTrue(sabr.isPostLive)
        // transient fields are not state
        assertNull(actual.metadata)
        assertNull(actual.mHandler)
        assertFalse(actual.running)
    }

    /** AC-4/AC-5: the mission written as upstream writes it (plain `ObjectOutputStream`) is read through the allow-list. */
    @Test
    fun ac4_realMission_isReadThroughTheAllowList() {
        val mission = mission()

        val read = AllowListObjectInputStream(ByteArrayInputStream(serialized(mission))).use { it.readObject() }

        assertTrue(read is DownloadMission)
        assertSameMission(mission, read as DownloadMission)
    }

    /** AC-2/AC-4/AC-5: the same through the files: `writeAtomically`, then `read`; nothing is set aside. */
    @Test
    fun ac5_realMission_writtenAtomically_isReadBack() {
        val dir = temp.newFolder("pending_downloads")
        val file = File(dir, "1731628800000").apply { createNewFile() }
        val mission = mission()

        assertNull(PendingStateFiles.tryWriteAtomically(file, mission))
        val result = PendingStateFiles.read(file) { null }

        if (result is PendingStateFiles.ReadResult.Unreadable) throw AssertionError("a real mission must be readable", result.cause)
        assertSameMission(mission, (result as PendingStateFiles.ReadResult.Ok).value as DownloadMission)
        assertEquals(listOf("1731628800000"), dir.listFiles()!!.map { it.name })
    }

    /** AC-5: a fresh mission, as `DownloadManager.startMission` saves it before anything is known (`DownloadManager.java:253`). */
    @Test
    fun ac5_freshMission_isReadThroughTheAllowList() {
        val mission = DownloadMission(arrayOf("https://example.invalid/file"), null, 'a', null, null)
        mission.timestamp = 1731628800001L

        val read = AllowListObjectInputStream(ByteArrayInputStream(serialized(mission))).use { it.readObject() } as DownloadMission

        assertArrayEquals(arrayOf("https://example.invalid/file"), read.urls)
        assertEquals(1731628800001L, read.timestamp)
        assertNull(read.storage)
        assertNull(read.psAlgorithm)
        assertEquals(DownloadMission.ERROR_NOTHING, read.errCode)
    }
}
