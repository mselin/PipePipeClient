package org.schabi.newpipe.enhancements.defaultopenaction

import org.schabi.newpipe.enhancements.queueguard.GuardChoice

// Plain Kotlin, no Android imports: the model of default-open-action (plan 4.1).

/**
 * "Default action when tapping a video" (AC-1). Each value is the queue-guard dialog choice of the same
 * name (AC-3). The order is that of the preference's value array (plan section 5).
 */
enum class DefaultTapAction(val prefValue: String, val choice: GuardChoice) {
    PLAY_NOW("play_now", GuardChoice.PLAY_NOW),
    ADD_TO_QUEUE("add_to_queue", GuardChoice.ADD_TO_QUEUE),
    PLAY_NEXT("play_next", GuardChoice.PLAY_NEXT);

    companion object {
        val DEFAULT: DefaultTapAction = PLAY_NOW

        /** `null` or a value outside the list (an imported backup, for instance): [DEFAULT]. */
        fun fromPrefValue(value: String?): DefaultTapAction =
            values().firstOrNull { it.prefValue == value } ?: DEFAULT

        /** The action of the same name; `null` for [GuardChoice.CANCEL]. */
        fun fromChoice(choice: GuardChoice): DefaultTapAction? = values().firstOrNull { it.choice == choice }
    }
}

/** "Start playback in" (AC-2): where a tap starts the video while nothing is playing. */
enum class StartPlaybackIn(val prefValue: String) {
    VIDEO_PAGE("video_page"),
    BACKGROUND_PLAYER("background_player"),
    POPUP_PLAYER("popup_player");

    companion object {
        val DEFAULT: StartPlaybackIn = VIDEO_PAGE

        /** `null` or a value outside the list: [DEFAULT]. */
        fun fromPrefValue(value: String?): StartPlaybackIn =
            values().firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}

/** The two settings of this feature. Both are read on every call, never cached. */
interface OpenActionSettings {
    fun defaultAction(): DefaultTapAction

    fun startPlaybackIn(): StartPlaybackIn
}
