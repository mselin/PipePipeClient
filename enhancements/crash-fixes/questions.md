# Questions: crash-fixes

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "crash-fixes" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-1 names the long-press actions of a list page and "the page was left". Two things in the code
are not clearly inside or outside that:
(a) The play queue's item menu (`QueueItemMenuUtil.java:61-68`, `:83-87`) shows the Download and
Add-to-playlist dialogs after the same wait and crashes the same way when the app is left.
(b) "Actions that need no page (enqueue, start playing) are still carried out": when the whole
app, not only the page, was left for more than a few seconds and no player runs, Android 12+ may
refuse to start the player service and throw; then the action cannot be carried out at all.
Assumption used in plan.md: (a) is included as its own step 1b. (b) the action is dropped
without a crash (step 1c), and that step is committed only if the crash is reproduced on a
device, because it is derived from the platform rule and was not observed.

## Q3 plan -> spec
Finding C6 has two parts: the null thumbnail and a blocking database read on the main thread.
AC-6 only asks that deleting works. The same null dereference also sits in "Remove duplicates"
(fatal, `LocalPlaylistFragment.java:811`) and "Remove watched" (error panel, `:882`, `:902`).
Assumption used in plan.md: the null handling is fixed in all four places; the blocking read is
left as it is. Also assumed: when the playlist and the deleted item both have no thumbnail, the
playlist takes the first remaining item's thumbnail, as the existing multi-delete does.

## Q4 plan -> spec
AC-4, second half ("when it is off, one tap on play resumes it") already holds in the code. The
defect is with the setting on: the stream stays silent and the first tap on play does nothing.
The finding also names `PlayerBroadcastReceiver.kt:41`, which is not reached by a focus change
but by tapping a timestamp link while a live stream is paused.
Assumption used in plan.md: the second half of AC-4 is a regression check; "with the setting on,
the stream resumes by itself" is the criterion; the timestamp case is fixed as separate step 4b.

## A2 spec
Yes to both: the play queue's item menu is in scope as its own step, and a player start that
Android refuses after the app was left is dropped without a crash. That last step is
committed only if the device check reproduces the crash first, as planned.

## A3 spec
As planned: the null fix goes in all four places; the blocking read stays as it is. When
playlist and item both lack a thumbnail, the playlist takes the first remaining item's.

## A4 spec
As planned: the criterion is that the live stream resumes by itself with "resume on focus
gain" on. With it off, one tap resuming is a regression check. The timestamp case is its
own step.
