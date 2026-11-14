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

## Q3 plan -> spec
While the main player is in use, "Ask before replacing the queue" is off and the default
action is *Add to queue* or *Play next* (AC-3), a tap enqueues, and nothing in a list plays
another video at once or opens its page: AC-11 adds "Show stream details" only while nothing
is playing, queue-guard's AC-17 only for the background and popup player. Opening a page while
the main player is in use is Play now (NavigationHelper.java:414-416). Is that intended, or
should the long-press menu offer a way there too? Planned with: the spec as written, no entry.

## Q4 plan -> spec
AC-13 says *Play now* "follows AC-5" if the player was closed while the dialog was open. In a
local playlist with Music Player Mode on, does AC-6 apply instead (what a tap does there
today), as queue-guard's AC-9 has it? Planned with: yes, AC-6 there; "Start playback in"
decides in every other list.

## Q5 plan -> spec
AC-5, *Popup player*: the long-press menu offers "Start playing in a popup" only for items
that are not audio-only (InfoItemDialog.java:287-290). What does a tap on an audio-only item
do while nothing is playing? Planned with: it starts in the popup player like any other item,
as queue-guard's Play now does while the popup player is in use. Any other answer needs the
stream type in the tap request of queue-guard's plan (3.1), which has none.

## Q6 plan -> spec
About queue-guard's plan, section 3.3: its dialog calls the checkbox add-on "before the choice
is carried out, never CANCEL", and it drops a row click while the list's screen is not at
least started. Is the add-on called for a dropped click? AC-8 saves a choice only together
with carrying it out. Planned with: no, the add-on is called only for a click that is then
carried out; a dropped click saves nothing (AC-8, AC-9).

## A3 spec
Not intended: there must be a way to play a video at once. "Show stream details" is now
offered whenever a tap would neither open the page nor ask, the main player included (AC-11).
There it does what a tap does today, which plays the video and replaces the queue; the user
has switched asking off.

## A4 spec
Yes. AC-13 now reads: AC-6 in a local playlist with Music Player Mode on, AC-5 everywhere
else.

## A5 spec
As planned: an audio-only item starts in the popup player like any other item. No stream type
is needed in the tap request.

## A6 spec
Answered by queue-guard's plan, which now says the add-on is called only for a row click that
is then carried out, never for a dropped click (its section 3.3). The assumption stands.
