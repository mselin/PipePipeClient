# Questions: default-open-action

## Q1 spec -> user
With *Add to queue* or *Play next* selected and nothing playing, what should a tap do? The
spec opens the video as today (AC-4). The alternative is to start the video in the background
player, which is what the long-press "Enqueue" does when no player is open.

## Q2 spec -> user
With *Add to queue* or *Play next* selected, a tap no longer opens a video while something
plays. The spec adds "Show stream details" to the long-press menu for that (AC-6). Is the
long-press menu the right place?

## A1 user
"Opens the video or starts in background or pop-up player, defined by the setting."
Applied: the setting "Start playback in" decides what a tap does while nothing is playing
(AC-2, AC-5).

## A2 user
"The default action can be set. Default can be Open Video (clearing the queue and all, like
now), Enqueue, Enqueue Next. And then there is another option to select how to play by
default. It should play by default in same mode that items are playing, and default mode."
Follow-up choice: "Same player, setting when idle".
Applied: default action with three values (AC-1, AC-3). While something is playing, playback
stays in the player in use; "Start playback in" applies only while nothing is playing (AC-2).
The spec keeps "Show stream details" in the long-press menu as the way to the video page
(AC-11, queue-guard AC-17); the answer did not name another place.
