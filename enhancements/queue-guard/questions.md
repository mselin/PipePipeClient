# Questions: queue-guard

## Q1 spec -> user
When does a queue count as worth protecting? The spec says two or more items, ignoring an
auto-enqueued one (AC-1, AC-7). The alternative is to ask whenever anything is loaded in the
player, which would also interrupt plain "watch one video, tap the next" browsing.

## Q2 spec -> user
Should the Play all / Background / Popup buttons in playlist, channel and feed headers also
ask before replacing a built queue? The spec leaves them as they are ("Not covered").

## Q3 spec -> user
Should the dialog have a "remember my choice" option? The spec has none: the setting (AC-10)
is the one place that decides, and default-open-action adds the enqueue choices to it.

## A1 user
"Anything loaded in player, no matter if it is playing or paused."
Applied: the term "built queue" is gone; the guard applies while something is playing
(Terms, AC-1, AC-7).

## A2 user
"Yes, should ask. There should be an Enqueue button, and possibly Enqueue next button. The
asking should be configurable. Would configure to be on by default. But can be turned off."
Follow-up choice: "Ask, one shared switch".
Applied: the play buttons ask, with *Add all to queue* and *Play all next* in the dialog
(AC-10 to AC-14). One switch, on by default, covers taps and play buttons (AC-15, AC-16).

## A3 user
"Setting remembers choice." Follow-up choice: "Yes, checkbox".
Applied in default-open-action (its AC-6), because the checkbox stores the choice in the
default-action setting that feature adds. Queue-guard alone has no checkbox.

## Q4 plan -> spec
Play now in the background player (Terms; AC-5, AC-16). The spec calls it "the tapped video
replaces the queue" and also "what the long-press action 'Start playing in the background'
does today". In a remote playlist, a local playlist and the history that long-press action
plays the whole list from the tapped video, not the video alone (PlaylistFragment.java:163,
LocalPlaylistFragment.java:1356, StatisticsPlaylistFragment.java:501). Which is meant there?
Planned with: the tapped video alone, in every list.

## Q5 plan -> spec
Play buttons (AC-11, AC-12): what do the choices do if the player was closed while the list
dialog was open? AC-9 covers video taps only. Planned with the same rule as AC-9: *Add all to
queue* and *Play all next* do what "Enqueue" does today while nothing is playing (the list
starts in the background player); *Replace queue* does what the pressed button does today.

## Q6 plan -> spec
AC-11 asks for "list order" and for the result of the matching single-video action. While the
player's queue is shuffled, today's "Enqueue" adds videos in random order
(PlayQueue.java:327-330). Must the list choices keep list order even then? Planned with: as
today's Enqueue (random order while the queue is shuffled, list order otherwise).

## Q7 plan -> spec
AC-15 places the switch under "Settings > Video & audio". The screen that holds "Preferred
'open' action" is titled "Player" in this app (res/xml/main_settings.xml:10,
res/xml/video_audio_settings.xml:4). Planned with: that screen, directly below "Preferred
'open' action".

## Q8 plan -> spec
AC-10: a play button pressed while something is playing and the list has no videos loaded.
A dialog offering to add 0 videos, or today's behaviour? Planned with: today's behaviour, no
dialog.

## Q9 plan -> spec
Play now in the main player (Terms; AC-5, AC-16) is "the tapped video ... plays" and also "what
a video tap does today". While autoplay is not allowed (setting "never", or "only on Wi-Fi" on
mobile data), today's tap opens the page, stops the running player and does not start the
tapped video (NavigationHelper.java:414-416, VideoDetailFragment.kt:587-600). Is that what
*Play now* should do then? Planned with: yes, exactly what a tap does today.
