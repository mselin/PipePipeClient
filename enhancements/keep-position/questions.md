# Questions: keep-position

## Q1 spec -> user
The app has a switch "Always start play from beginning" (Settings > Advanced). In the code it
also discards the position when the playing video moves between players. Is it on in your
app?

## A1 user
"Yes, it is on."
Applied: that switch is the cause; the feature keeps the position while it is on (Today,
AC-2).

## Q2 spec -> user
What should the new setting be by default?

## A2 user
"On."
Applied: AC-1.

## Q3 spec -> user
The same cause seems to restart the video on a quality change and on error recovery. Should
the setting cover those too, or only player switches?

## A3 user
"All reloads of the playing video."
Applied: one switch, "Keep position of the playing video", for player switches, quality
changes and error recovery (Terms, AC-2 to AC-4).

## Q4 plan -> spec
AC-12 says a timestamp link for the playing video "jumps to the time without restarting". The
code seeks in place only when the playing queue holds that one video
(`PlayerStartController.kt:101-105`). In a queue of several videos the same tap, today with
"Always start play from beginning" off, replaces the queue by that one video and loads it anew
at the link's time (`:152-158`), and AC-14 keeps the queue handling as today. Planned with: as
today with the switch off (queue replaced, loaded at the link's time, no seek in place). Affects
AC-12, AC-14. Is that accepted, or must the tap seek in place inside a longer queue?

## Q5 plan -> spec
"Today" says a time in a description or comments does not go to its time while "Always start
play from beginning" is on. In the code this holds only for times that are links (an address
ending in `&t=90` or `#timestamp=90`, `InternalUrlsHandler.java:97-135`). A time written as text
(`1:30`) seeks the running player directly and ignores the switch (`TextLinkifier.java:195`,
`CommentsMiniInfoItemHolder.java:287`, `PlayerBroadcastReceiver.kt:38-43`): it works today, does
nothing when no player runs, and seeks whatever video the player is on, even another one than
the page shows. Planned with: times written as text stay untouched. Affects AC-12, AC-14. Is
that accepted?

## Q6 plan -> spec
The popup's fullscreen button closes the popup's player before the main player starts
(`PlayerClickController.kt:124-129`). Whether the main player then starts by itself follows the
Autoplay setting, not the play/pause state (`NavigationHelper.java:408-410`). If it does not
start, the video waits on its page until play is pressed. Is that press the end of the player
switch (AC-2: continue at the kept position) or "pressing play after the player was closed"
(AC-7: 0:00)? Planned with: the end of the switch, unless another video was started in between.
Affects AC-2, AC-7.

## Q7 plan -> spec
AC-10 says live streams behave as today. AC-12 and AC-13 say a timed start goes to its time
whatever the switches are set to. Which holds for a timed start of a live stream? Planned with:
AC-10. A live stream gets no exemption, so a link's time is ignored for it while "Always start
play from beginning" is on, as today. Affects AC-10, AC-12, AC-13.

## A4 spec
Accepted as today: only a video that is alone in its queue is sought in place; in a longer
queue the tap replaces the queue and loads the video at the link's time. AC-12 now says so.

## A5 spec
Accepted. Times written as text already work and stay untouched. "Today", the term
"timestamp link" and "Not covered" now say that a timestamp link is a link whose address ends
in a time.

## A6 spec
The end of the switch: pressing play continues at the kept position unless another video was
started in between. AC-2 now says so.

## A7 spec
AC-10 holds. A live stream gets no exemption, also for a timed start. AC-10 now says so.

## Q8 spec -> user
From the code, a timestamp link for the playing video is ignored while "Always start play
from beginning" is on. The first draft left that out as a separate fault. Fix it here too?

## A8 user
"Fix the timestamp link too."
Applied: AC-12, with no setting of its own (AC-8).

## Q9 spec -> user
A start time carried by a link opened from another app (such as `?t=90`) is discarded the same
way. Honour it too?

## A9 user
"That should be honored too."
Applied: the term "timed start" with its two kinds, AC-13 and AC-14.

## Q10 tests -> plan
AC-10 says "a timed start of a live stream is not exempt either". A timed start has two entry
points. For a video link with a start time (H8) the live check is `isTimedLinkStart`, which
the unit tests cover. For a timestamp link (H7) it is inside the glue
(`KeepPosition.onTimestampLink`, section 3.2: "unless the queue is empty or its item's
`streamType` is live"), which section 3.2 keeps out of the unit tests; the tracker cannot
tell, because `onTimedStart` takes only a uuid; and M7 does not tap a timestamp link. So no
test and no manual step proves AC-10 for a timestamp link to a live stream. Written with: the
unit tests prove the live exception through `isTimedLinkStart(90,false,true)` only, and say
so in the KDoc of `ac10_startTimeOfALiveStreamIsNotATimedStart`. Should the H7 check go
through a function the JVM tests can call, or get a manual step? Affects AC-10.

## A10 plan
Through a function the tests can call. `KeepPosition.onTimestampLink` now decides with
`isTimedLinkStart`, the rule the video-link hook already uses, so the live exception is one
unit-tested rule for both kinds of timed start (section 3.2). What stays manual is that the
glue passes the live flag: M7 now opens a timed link to a live stream. The tests stand.
Section 2 also says now that `synced` is one pair for all players, as the table in 3.1 has it.

## Q11 spec -> plan
The code review (finding F13 in `enhancements/FINDINGS.md`) found that this is the only
feature without a test that counts its hook lines, so a hook lost in a rebase goes unnoticed.
Add one?

## A11 plan
Yes. Section 6 now specifies a wiring test with the per-file counts and the function each
hook line must call. The Tests layer adds it; no code changes.

## Q12 plan -> spec
AM-2 says "a switch between the main, popup and background player ... also when the queue it is
started with differs". The code path it names (`PlayerStartController.kt:152-158`) is also taken
without a switch: the main player plays a video, a next video is queued, and the same video is
tapped again in a list. The player type stays "main", the queue differs, the video restarts.
AC-7 calls a video tap a start. Planned with: the position is carried only when the player type
changes (a tap on the playing video in the same player stays a start, 0:00). It is one
condition in the tracker (`playerTypeChanged`, plan 10.3, `onStartIntent` row 4). Should the
same-player case continue too? Affects AM-2, AC-7.

## Q13 plan -> spec
AM-1 says "closed by a playback error, not by the user". Reading the code, an error closes the
player in two ways, both planned as "by an error": the error handler shuts it down, and the
video page stops the main player on any playback error when Autoplay is not allowed (the
default "only on Wi-Fi" after leaving Wi-Fi). A third case is planned as "by the user": the
player survives the error and keeps retrying without success (network gone, or the stream
addresses refused on the new network), and the user closes that stuck player and starts the
video again. As written that is 0:00. It is likely after a network change with the background
or popup player. Covering it needs one more hook (where the player is destroyed) and a rule
such as "closed before it played again after an error". Planned with: 0:00, as the spec says.
Is that wanted? Affects AM-1, AC-7.
