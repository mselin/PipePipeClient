package org.schabi.newpipe.enhancements.queueguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hooks of plan section 2, checked on the JVM by reading the 8 upstream files as text (plan
 * section 5, "Wiring on the JVM"). The glue itself cannot run without Android, but a hook that is
 * missing can be seen: each file must hold the planned number of lines marked `ENH:queue-guard`.
 *
 * Beyond the counts, two things the plan's hook tables state literally are checked: a code hook names
 * `org.schabi.newpipe.enhancements.queueguard.QueueGuard` in full on the marked line (so that no import
 * line is added), and the tap and menu hooks call `onVideoTap(` and `addStreamDetailsEntry(`.
 *
 * Until the Code layer has added the hooks, every test here fails with "expected n but was 0".
 */
class QueueGuardWiringTest {

    private val marker = "ENH:queue-guard"
    private val queueGuard = "org.schabi.newpipe.enhancements.queueguard.QueueGuard"

    private fun hookLines(path: String): List<String> =
        MainSources.file(path, "upstream file that plan section 2 hooks").readLines().filter { marker in it }

    /** [path] as failure messages show it. */
    private fun shown(path: String): String = "app/src/main/$path"

    /**
     * [path] holds [total] hook lines, [taps] of them video-tap hooks (form A, `QueueGuard.onVideoTap(`);
     * the others are play-button hooks (form B, the receiver swapped to `QueueGuard`). Each names the
     * class in full.
     */
    private fun assertCodeHooks(path: String, hooks: String, total: Int, taps: Int) {
        val lines = hookLines(path)
        assertEquals("$hooks: lines marked $marker in ${shown(path)}", total, lines.size)
        for (line in lines) {
            assertTrue(
                "$hooks: a hook line in ${shown(path)} does not name $queueGuard: ${line.trim()}",
                queueGuard in line,
            )
        }
        assertEquals(
            "$hooks: hook lines in ${shown(path)} that call $queueGuard.onVideoTap(",
            taps,
            lines.count { "$queueGuard.onVideoTap(" in it },
        )
    }

    // ---- Video taps: T1-T4 (AC-1); play buttons: B1-B5 (AC-10) ----

    /** T1: search, trending, channel videos and tabs, remote playlists, related videos. */
    @Test
    fun ac1_hookT1_baseListFragmentGuardsItsVideoTap() {
        assertCodeHooks("java/org/schabi/newpipe/fragments/list/BaseListFragment.java", "T1", total = 1, taps = 1)
    }

    /** T2 and B3 (main, popup, background shuffled, background). */
    @Test
    fun ac1_ac10_hooksT2B3_localPlaylistFragmentGuardsItsVideoTapAndItsFourPlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java",
            "T2, B3",
            total = 5,
            taps = 1,
        )
    }

    /** T3 and B4 (main, popup, background). */
    @Test
    fun ac1_ac10_hooksT3B4_statisticsPlaylistFragmentGuardsItsVideoTapAndItsThreePlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/local/history/StatisticsPlaylistFragment.java",
            "T3, B4",
            total = 4,
            taps = 1,
        )
    }

    /** T4 and B5 (main, popup, background shuffled, background). */
    @Test
    fun ac1_ac10_hooksT4B5_feedFragmentGuardsItsVideoTapAndItsFourPlayButtonCalls() {
        assertCodeHooks("java/org/schabi/newpipe/local/feed/FeedFragment.kt", "T4, B5", total = 5, taps = 1)
    }

    /** B1 (main, popup, background). The video tap of a remote playlist is T1. */
    @Test
    fun ac10_hookB1_playlistFragmentGuardsItsThreePlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/fragments/list/playlist/PlaylistFragment.java",
            "B1",
            total = 3,
            taps = 0,
        )
    }

    /** B2 (main, popup, background). The video tap of a channel is T1. */
    @Test
    fun ac10_hookB2_channelVideosFragmentGuardsItsThreePlayButtonCalls() {
        assertCodeHooks(
            "java/org/schabi/newpipe/fragments/list/channel/ChannelVideosFragment.java",
            "B2",
            total = 3,
            taps = 0,
        )
    }

    // ---- "Show stream details": M1 (AC-17) ----

    @Test
    fun ac17_hookM1_infoItemDialogAddsTheStreamDetailsEntry() {
        val path = "java/org/schabi/newpipe/info_list/dialog/InfoItemDialog.java"
        val lines = hookLines(path)
        assertEquals("M1: lines marked $marker in ${shown(path)}", 1, lines.size)
        assertTrue(
            "M1: the hook line in ${shown(path)} must call $queueGuard.addStreamDetailsEntry(: " +
                lines.single().trim(),
            "$queueGuard.addStreamDetailsEntry(" in lines.single(),
        )
    }

    // ---- The switch: S1 (AC-15) ----

    /** S1 is XML and one line; where it sits and what it holds is checked in [QueueGuardResourcesTest]. */
    @Test
    fun ac15_hookS1_playerSettingsScreenHasTheSwitchOnOneMarkedLine() {
        val path = "res/xml/video_audio_settings.xml"
        val lines = hookLines(path)
        assertEquals("S1: lines marked $marker in ${shown(path)}", 1, lines.size)
        assertTrue(
            "S1: the marked line in ${shown(path)} must hold the preference with key " +
                "@string/enh_queue_guard_ask_key: ${lines.single().trim()}",
            "android:key=\"@string/enh_queue_guard_ask_key\"" in lines.single(),
        )
    }
}
