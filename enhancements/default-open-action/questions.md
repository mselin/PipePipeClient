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

## Q7 tests -> plan
Rows 14, 15 and 16 of the `onTapChoice` table (plan 4.2) name only queue-guard criteria in
their AC column (QG 3, QG 4, QG 9), and section 6 lists these rows under no AC of this
feature. A test is named after the AC it proves: which one is it for these rows? Tested with:
AC-4, since the dialog is queue-guard's "as before" and neither the default action nor "Start
playback in" changes its *Add to queue* and *Play next*, also after the player was closed
(OpenActionPolicyTest: ac4_row14_..., ac4_row15_..., ac4_row16_...; OpenActionPolicyDelegationTest:
ac4_delegation_aDialogChoiceGetsTheWrappedPolicysAnswer). The expected values are the table's;
only the test names depend on the answer.

## A7 plan
AC-4. The dialog is queue-guard's "as before": neither setting changes what *Add to queue* and
*Play next* do. Rows 14 to 16 now name AC-4 next to queue-guard's criteria. The test names
stand.

## Q8 spec -> user
Queue-guard no longer asks for a single video in the main player. With asking on and the
default action set to *Add to queue* or *Play next*, what should a tap do there?

## A8 user
"Open it as today."
Applied: AC-4 (asking on: exactly queue-guard's behaviour, the default action is not used)
and AC-3 (asking off: the default action applies, also on a single video). Plan, tests and
code follow; queue-guard's plan section 6 describes what changes here.

## Q9 plan -> spec
Two things the amendment leaves open about the paths queue-guard's amendment adds (video page
controls, links, videos from other apps). Their dialog is the video-tap dialog, so it carries
"Remember my choice" (AC-7).
- A choice remembered there: is it saved like one from a list tap? Planned with: yes, the same
  two settings (AC-8) and the same message (AM-1). The saved default action then applies to
  video taps in lists only, and with asking off those paths behave as today (AM-3).
- A tap in the list of related videos on the page of the current item (queue-guard's AM-2) with
  asking off: the default action, or "opens the video as today"? Planned with: the default
  action, as AC-3 says for every tap on anything but the current item; queue-guard's AM-2 only
  removes the dialog (its Q18).

## Q10 plan -> spec
AM-1, "a short message". Planned with: a toast, shown right before the choice is carried out, so
before that choice's own toast ("Enqueued", ...). English text, with the names taken from the
app's own strings: "Asking is now off. To switch it on again: Settings > Player > Ask before
replacing the queue". It is shown for every remembered choice, also when asking was already off
or the remembered action is the one already set.

## A9 spec
Accepted.

## A10 spec
Accepted: a toast with that text, for every remembered choice.

## Q11 tests -> plan
`DefaultOpenActionResourcesTest` and `DefaultOpenActionWiringTest` read files below `app/src/main`
as text or XML (B2.6: "read as text"). Gradle does not count those files as inputs of
`:app:testDebugUnitTest`: after a change to `res/values/enhancements_default_open_action.xml`
alone the task came `FROM-CACHE` with the old result (seen while checking the new string's tests
against a wrong text). Should the plan's command for these tests say so? Tested with: the tests
as B2.7 has them, run with `--rerun` after the `--tests` argument whenever only a resource file
changed; no build file is touched.

## A11 plan
Accepted: run the test task with `--rerun` when only a resource file changed. The full runs
before a commit are made that way.
