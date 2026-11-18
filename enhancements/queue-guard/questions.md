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

## A4 spec
The tapped video alone, in every list. "The tapped video replaces the queue" is the rule; the
reference to the long-press action was imprecise, since that action plays the whole list in
playlists and history. Terms now say so. Starting a list from a video stays available in the
long-press menu.

## A5 spec
As planned, by the rule of AC-9: *Add all to queue* and *Play all next* start the list in the
background player, *Replace queue* does what the button does today. AC-11 now says so; AC-12
already covers *Replace queue*.

## A6 spec
As today's "Enqueue": mixed in while the player's queue is shuffled, list order otherwise.
AC-11 now says so.

## A7 spec
The screen is "Player". AC-15 now reads "directly below Preferred 'open' action
(Settings > Player)".

## A8 spec
Today's behaviour, no dialog. AC-10 now says so.

## A9 spec
Yes, exactly what a tap does today: the autoplay setting keeps deciding whether the video
starts by itself. Terms now say so.

## Q10 tests -> plan
Section 3.2 says "A second call of `cb` is ignored" in the `onVideoTap` paragraph only. Is the
callback handed to `ui.askForList` guarded the same way, so that only its first call is
performed? The `GuardUi` comment ("onChoice: at most once") covers both dialog forms. Tested
with: yes, the same rule (QueueGuardControllerTest:
ac11_ac12_onlyTheFirstChoiceOfAListDialogCounts,
ac13_nothingHappensAfterCancelEvenIfTheListDialogCallsBackAgain).

## A10 plan
Yes, the same rule: only the first call of the list dialog's callback is performed. Section
3.2 now says so in the `onListPlay` paragraph too. The tests stand.

## Q11 spec -> user
The code review (finding F4 in `enhancements/FINDINGS.md`) found that the guard asks on every
tap of a related video while one video is being watched. Narrow it?

## A11 user
"Option 1. Don't ask in the main player when the queue holds one video, not counting an
auto-queued one. Tapping related videos while watching then behaves as upstream; a real
queue is still protected."
Applied: the term "a queue worth guarding" and AC-1, AC-7, AC-10, AC-14 and AC-16. This
changes answer A1 for the main player only. Plan, tests and code follow.

## Q12 plan -> spec
"Not counting one appended by Auto-enqueue next stream" (Terms) needs to know which queue item
was auto-enqueued. Upstream keeps that in a private field with no getter
(PlayQueue.java:70, `autoQueuedUuid`), and no public member tells an auto-enqueued second video
from one the user enqueued (both appends emit the same event, the item carries no flag). The
plan therefore adds one marked line to PlayQueue.java, an accessor
(`enhAutoQueuedUuid()`, plan section 2, hook Q1). PROCESS.md lists three hook forms (an added
call into enhancements/, a redirected call, an XML element) and an accessor is none of them;
check-layers.sh accepts it because it tests only for the marker. Is this fourth form accepted?
Planned with: yes. The alternatives are reading the private field by reflection (no hook, but
the release build is minified and a rename would fail silently) or not discounting the
auto-enqueued video at all (then the guard asks on every related-video tap whenever
"Auto-enqueue next stream" is on, which is what A11 set out to remove).

## Q13 plan -> spec
"Its queue holds one video, not counting one appended by Auto-enqueue next stream": what about
a queue that grew only through auto-enqueue? Watch video A in the main player with
auto-enqueue on; A ends, the auto-enqueued B plays and C is auto-enqueued. The queue is A, B,
C. The user never enqueued anything, but without the one auto-enqueued video (C) it holds two.
Does a tap on a related video ask now? Upstream marks one video only, the latest auto-enqueued
one, and the mark stays on it after playback has moved on to it. Planned with the words of the
spec: only the one video upstream marks is not counted, so A, B, C is worth guarding and the
tap asks ("3 items in the queue"); A alone, A with auto-enqueued B, and A with B while B plays
and nothing new was auto-enqueued are not. If instead every video that came from auto-enqueue
should be left out, the model stays as planned (it takes a count) and the glue has to keep its
own record of those videos, which needs a second hook.

## Q14 plan -> spec
Conflict with default-open-action's spec, which uses this spec's term "something is playing"
and was written before A11. State: main player, one video, tap on another video.
- Its AC-4: with "Ask before replacing the queue" on, "a video tap opens the queue-guard dialog
  as before. The default action is not used." This spec's AC-7 now says no dialog, the tap
  behaves as today. Both cannot hold.
- Its AC-3: with the switch off the tap carries out the default action "while something is
  playing", so *Add to queue* enqueues and a queue can be started by tapping from a single
  video. This spec's AC-7 says a tap "behaves as today" without a queue worth guarding. Here
  its AC-3 is taken to override, as it already overrides AC-16.
Planned with: default-open-action keeps "something is playing" for its AC-3, AC-5, AC-11 and
AC-13 (not "a queue worth guarding"); in the state above with the switch on the tap behaves as
today and the default action is not used; with the switch off the default action applies. Its
spec, plan (row 6 of its rule table and how it detects "ask is off"), tests and code need an
amendment for that state; left as built, its code would carry out the default action there even
with the switch on. Which behaviour is wanted with the switch on and the default action *Add to
queue* or *Play next*: today's tap (as planned), or the default action?

## A12 spec
Yes. A read-only accessor added to an upstream class on one marked line is accepted as a
hook form.

## A13 user
"Don't ask."
Applied: videos the player added by itself never count, also after they have played. The
term "a queue worth guarding" now says so. The plan must track them beyond the one video
upstream marks; a second small hook is accepted for that.

## A14 user
"Open it as today."
Applied: with asking on and a single video in the main player, a tap opens the video as
today and the default action is not used; it applies once asking is off. This is recorded in
default-open-action's spec as well.

## Q15 plan -> spec
AM-3 ends "On the page of the current item these controls behave as today", and AM-5 "A link to
a time in the current item seeks as today". As today, both can replace a longer queue by that
one video. Read in the code:
- Background or popup player with a queue of three, playing X. The page of X is opened again
  from a list (a tap on the current item, AC-8), from "Show stream details" or from "Details"
  (AM-4, background and popup player). That page holds X alone as its queue
  (VideoDetailFragment.kt:453-458), so its play button, Background and Popup send X alone and the
  player drops the other two videos (PlayerStartController.kt:77, :155). Only a page that was
  never reopened holds the player's queue and merely switches the player.
- Main player with a queue of three, playing X: a link to a time in X builds a queue of X alone
  (InternalUrlsHandler.java:219-222). It seeks in place only when the player's queue is X alone
  (PlayerStartController.kt:101-105); otherwise the queue is replaced by X at that time.
  keep-position's AC-12 describes the same.
Planned with: as written. The current item never asks (plan A2.2, row 2), so in these two cases
the queue is still replaced without a dialog. Alternatives if that is not wanted: ask there too
(one more condition in row 2, "and the queue would stay"), or make the control and the link act
on the player's queue (new behaviour: the page's buttons switch the player with the whole queue;
the link seeks inside the queue).

## Q16 plan -> spec
AM-4, main player. There the video page is the player: it can only show the item that is
playing, and every way of opening another video's page stops the player first
(NavigationHelper.java:400-451, VideoDetailFragment.kt:411-424, :587-600). So "opens that
video's page and leaves the queue as it is" cannot be done for another item without moving
playback. Planned with (plan A2.5): "Details" on the playing item closes the queue screen and
shows its page, playback uninterrupted; "Details" on another item moves playback to that item
inside the queue, as a tap on its row does, and shows its page; the queue keeps its items and
order in both. In the background and popup player "Details" stays as today: the page opens
without playing and the queue is untouched. Is moving playback acceptable for another item in
the main player, or should "Details" there do something else (for example nothing, or only show
the page of the playing item)?

## Q17 plan -> spec
AM-5 asks "with a queue worth guarding" for every link and for an address typed in search; AM-6
asks only "when the action chosen for it would replace the queue" and lets "show information"
behave as today. They meet on one path: a link without a time in a description goes through the
same screen as a link from another app (InternalUrlsHandler.java:150). And they miss two facts:
- Beside the background or popup player, a timed link, an address typed in search, and "show
  info" or "video player" for a link all only open the video's page; nothing is replaced
  (NavigationHelper.java:417-420).
- With the main player, "show info" replaces the queue like every other way of opening a page,
  and it is what the chooser's "video player" entry does there (RouterActivity.java:466-480).
Planned with one rule for AM-5 and AM-6 (plan A2.1, `replacesQueue`): the dialog opens exactly
where carrying the request out as today would replace the queue. That is: timed links, typed
addresses, "show info" and "video player" while the main player is in use; "background player"
and "popup player" in every player. Beside the background or popup player the first group opens
the page as today, without a dialog, and the page's own controls then ask (AM-3). *Play now* is
what the path does today.

## Q18 plan -> spec
AM-2 with "Ask before replacing the queue" off. Planned with: AM-2 only removes the dialog. With
the switch off a tap in the related list of the current item's page does what AC-16 says (Play
now: in the background or popup player the video starts there and the page stays), and with
default-open-action its default action (its AC-3), as before this amendment.

## Q19 plan -> spec
AM-6 says "a video opened from another app". A channel or playlist link opened with "video
player", "background player" or "popup player" replaces the queue by that whole channel or
playlist (RouterActivity.java:797-810). Planned with: as today, no dialog; only video links are
covered. If they should ask, the list dialog (AC-10) would fit, but the number of videos is not
known before the list is loaded.

## Q20 plan -> spec
AC-2: the dialog "shows the title of the tapped video". A timed link, an address typed in search
and a link from another app know only the address until the video is loaded. Planned with: the
dialog shows the address in place of the title; the page controls of AM-3 show the title.
Loading the video first only to show its title would delay the dialog by a network request.

## A15 spec
Neither case may lose the queue; that is the owner's complaint. The spec gains AM-9 (page
controls of the current item act on the player's queue) and AM-10 (a time link in the current
item seeks in place and keeps the queue). The plan is to be extended for both. If acting on
the player's queue cannot be done safely, ask again; do not fall back to replacing.

## A16 spec
Accepted, and written into AM-4.

## A17 spec
Accepted: one rule, ask exactly where the queue would be replaced.

## A18 spec
Accepted.

## A19 spec
Not covered for now; listed in the spec.

## A20 spec
Accepted.
