package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.enhancements.queueguard.GuardChoice

/**
 * The two enums of plan 4.1, `DefaultTapAction` and `StartPlaybackIn`: their values and order, the
 * strings stored in the preferences, the defaults, and the mapping to and from queue-guard's dialog
 * choices.
 *
 * The order matters: plan section 5 delivers the value arrays "in the order shown, which is the order of
 * the enums", and the resource tests compare the arrays with `values()`.
 */
class OpenActionModelTest {

    // =============================================================================================
    // DefaultTapAction: "Default action when tapping a video"
    // =============================================================================================

    /** AC-1: "Values: Play now (default), Add to queue, Play next", in the order of plan 4.1. */
    @Test
    fun ac1_defaultActionHasTheValuesPlayNowAddToQueueAndPlayNextInThatOrder() {
        assertEquals(
            listOf(DefaultTapAction.PLAY_NOW, DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.PLAY_NEXT),
            DefaultTapAction.values().toList(),
        )
    }

    /** Plan 4.1 and section 5: what the preference `enh_default_open_action` holds for each value. */
    @Test
    fun ac1_defaultActionIsStoredAsPlayNowAddToQueueOrPlayNext() {
        assertEquals("play_now", DefaultTapAction.PLAY_NOW.prefValue)
        assertEquals("add_to_queue", DefaultTapAction.ADD_TO_QUEUE.prefValue)
        assertEquals("play_next", DefaultTapAction.PLAY_NEXT.prefValue)
    }

    /** AC-1: "*Play now* (default)". AC-14 rests on it: *Play now* is what queue-guard alone does. */
    @Test
    fun ac1_ac14_defaultActionIsPlayNowUnlessTheUserChoseAnother() {
        assertEquals(DefaultTapAction.PLAY_NOW, DefaultTapAction.DEFAULT)
    }

    /** Plan 4.1, `fromPrefValue`: each stored value gives its action back. */
    @Test
    fun ac1_defaultActionIsReadBackFromEachStoredValue() {
        assertEquals(DefaultTapAction.PLAY_NOW, DefaultTapAction.fromPrefValue("play_now"))
        assertEquals(DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.fromPrefValue("add_to_queue"))
        assertEquals(DefaultTapAction.PLAY_NEXT, DefaultTapAction.fromPrefValue("play_next"))
    }

    /** Plan 4.1: "null ... -> DEFAULT". Nothing stored yet: the app behaves as with queue-guard alone. */
    @Test
    fun ac1_ac14_defaultActionIsPlayNowWhileNothingIsStored() {
        assertEquals(DefaultTapAction.PLAY_NOW, DefaultTapAction.fromPrefValue(null))
    }

    /**
     * Plan 4.1: "unknown -> DEFAULT"; plan section 8: "Stored values outside the lists (an imported
     * backup) fall back to the defaults". The list is `play_now`, `add_to_queue`, `play_next` and nothing
     * else: not the other setting's values, not the labels, not the enum constants' names.
     */
    @Test
    fun ac1_defaultActionIsPlayNowForAStoredValueOutsideTheList() {
        val outsideTheList = listOf(
            "",
            " ",
            "enqueue",
            "Play next",
            "PLAY_NEXT",
            "ADD_TO_QUEUE",
            "video_page",
            "background_player",
            "popup_player",
            "true",
        )
        for (stored in outsideTheList) {
            assertEquals("stored value \"$stored\"", DefaultTapAction.PLAY_NOW, DefaultTapAction.fromPrefValue(stored))
        }
    }

    /**
     * AC-3: "Each action gives the same result as the queue-guard dialog choice of the same name". Plan
     * 4.1: every action carries that choice.
     */
    @Test
    fun ac3_everyDefaultActionIsTheDialogChoiceOfTheSameName() {
        assertEquals(GuardChoice.PLAY_NOW, DefaultTapAction.PLAY_NOW.choice)
        assertEquals(GuardChoice.ADD_TO_QUEUE, DefaultTapAction.ADD_TO_QUEUE.choice)
        assertEquals(GuardChoice.PLAY_NEXT, DefaultTapAction.PLAY_NEXT.choice)
    }

    /** AC-8: a remembered choice "becomes the default action". Plan 4.1, `fromChoice`. */
    @Test
    fun ac8_everyChoiceThatCanBeRememberedIsTheDefaultActionOfTheSameName() {
        assertEquals(DefaultTapAction.ADD_TO_QUEUE, DefaultTapAction.fromChoice(GuardChoice.ADD_TO_QUEUE))
        assertEquals(DefaultTapAction.PLAY_NEXT, DefaultTapAction.fromChoice(GuardChoice.PLAY_NEXT))
        assertEquals(DefaultTapAction.PLAY_NOW, DefaultTapAction.fromChoice(GuardChoice.PLAY_NOW))
    }

    /** AC-8 with AC-3: what is remembered is carried out again by the next tap, so the two mappings agree. */
    @Test
    fun ac3_ac8_rememberedChoiceAndDefaultActionMapBackAndForth() {
        for (action in ALL_ACTIONS) {
            assertEquals("fromChoice($action.choice)", action, DefaultTapAction.fromChoice(action.choice))
        }
    }

    /** AC-9: *Cancel* can never become the default action. Plan 4.1: "CANCEL -> null". */
    @Test
    fun ac9_cancelIsNoDefaultAction() {
        assertNull(DefaultTapAction.fromChoice(GuardChoice.CANCEL))
    }

    // =============================================================================================
    // StartPlaybackIn: "Start playback in"
    // =============================================================================================

    /** AC-2: "Values: Video page (default), Background player, Popup player", in the order of plan 4.1. */
    @Test
    fun ac2_startPlaybackInHasTheValuesVideoPageBackgroundPlayerAndPopupPlayerInThatOrder() {
        assertEquals(
            listOf(StartPlaybackIn.VIDEO_PAGE, StartPlaybackIn.BACKGROUND_PLAYER, StartPlaybackIn.POPUP_PLAYER),
            StartPlaybackIn.values().toList(),
        )
    }

    /** Plan 4.1 and section 5: what the preference `enh_default_open_action_start_in` holds for each value. */
    @Test
    fun ac2_startPlaybackInIsStoredAsVideoPageBackgroundPlayerOrPopupPlayer() {
        assertEquals("video_page", StartPlaybackIn.VIDEO_PAGE.prefValue)
        assertEquals("background_player", StartPlaybackIn.BACKGROUND_PLAYER.prefValue)
        assertEquals("popup_player", StartPlaybackIn.POPUP_PLAYER.prefValue)
    }

    /** AC-2: "*Video page* (default)". AC-14 rests on it: the video page is where a tap goes today. */
    @Test
    fun ac2_ac14_startPlaybackInIsVideoPageUnlessTheUserChoseAnother() {
        assertEquals(StartPlaybackIn.VIDEO_PAGE, StartPlaybackIn.DEFAULT)
    }

    /** Plan 4.1, `fromPrefValue`: each stored value gives its value back. */
    @Test
    fun ac2_startPlaybackInIsReadBackFromEachStoredValue() {
        assertEquals(StartPlaybackIn.VIDEO_PAGE, StartPlaybackIn.fromPrefValue("video_page"))
        assertEquals(StartPlaybackIn.BACKGROUND_PLAYER, StartPlaybackIn.fromPrefValue("background_player"))
        assertEquals(StartPlaybackIn.POPUP_PLAYER, StartPlaybackIn.fromPrefValue("popup_player"))
    }

    /** Plan 4.1: "null ... -> DEFAULT". */
    @Test
    fun ac2_ac14_startPlaybackInIsVideoPageWhileNothingIsStored() {
        assertEquals(StartPlaybackIn.VIDEO_PAGE, StartPlaybackIn.fromPrefValue(null))
    }

    /** Plan 4.1: "unknown -> DEFAULT"; plan section 8, as for the default action. */
    @Test
    fun ac2_startPlaybackInIsVideoPageForAStoredValueOutsideTheList() {
        val outsideTheList = listOf(
            "",
            " ",
            "background",
            "popup",
            "Popup player",
            "POPUP_PLAYER",
            "BACKGROUND_PLAYER",
            "play_now",
            "add_to_queue",
            "play_next",
            "true",
        )
        for (stored in outsideTheList) {
            assertEquals("stored value \"$stored\"", StartPlaybackIn.VIDEO_PAGE, StartPlaybackIn.fromPrefValue(stored))
        }
    }
}
