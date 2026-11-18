# Questions: crash-fixes-more

## Q1 plan -> spec
AC-3, second sentence ("Reading the thumbnail of a playlist that no longer exists gives 'no
thumbnail', not a crash"). The read is `LocalPlaylistManager.java:146` (`get(0)`). The plan
could not find a way to reach it for a deleted playlist: every caller outside a `try/catch`
runs once per item of the playlist, a deleted playlist has no items (the join rows are deleted
with it and the open page is told), and a playlist cannot be deleted while its page is open
(deleting happens only in Bookmarks, which cannot be opened on top of a playlist page). The
home-screen shortcut of a deleted playlist opens an empty page; its only read is already inside
`try/catch`.
Assumption used in plan.md (C3b): no edit; the wiring test expects no marked line in
`LocalPlaylistManager.java`. A one-line guard is written out in the plan and can be added if
the criterion should hold regardless of reachability.

## Q2 plan -> spec
AC-4 says "when the stream was playing before the seek". In the code a stream that is playing
when the seek bar is touched never goes idle (the drag pauses it in a state that does not
release the source), so that case already works. The raw "play" on an idle live stream is
reached the other way: the stream is paused (idle) and the seek bar is dragged to its right
end, which the code treats as "play".
Assumption used in plan.md (C4): the criterion is "a paused live stream whose seek bar is
dragged to the end plays again, without a further tap". The one changed line covers both
conditions of the existing `if`, so the spec's wording holds too wherever it can occur.

## Q3 plan -> spec
AC-1, share menu: "The dialog is dropped then." The share screen is invisible and ends only when
a dialog is dismissed. If the dialog is merely not shown, returning to it shows the chooser a
second time, and cancelling that leaves an invisible screen that swallows touches.
Assumption used in plan.md (C1b, C1c): when the dialog cannot be shown, the share screen is
finished; the user shares the link again. Also assumed: "Download from the player" and
"Download from a selection" need no change (the player has no download dialog of its own, the
selection shows a plain dialog at once), and the video page's Download is not changed either
(shown at once, and already inside a `try/catch`; plan C1e).

## Q4 plan -> spec
AC-2, "it is ignored, or shown when the page is returned to". Reading the page's context cannot
crash (the page is attached whenever a result can arrive; plan C2a), so nothing changes there.
Going back for a blocked channel does crash when the app is in the background. If that result
were only ignored, the user would return to an empty page of the blocked channel.
Assumption used in plan.md (C2b): a blocked result that arrives while the app is in the
background, or while another page is on top, is kept and handled on return: toast and back, as
for a result that arrives in time. This costs 10 marked lines instead of 1.

## Q5 plan -> spec
Not about the spec itself; it needs a decision by whoever owns the parent's tests. The wiring
test of `crash-fixes` (`CrashFixesWiringTest.kt`) counts lines that contain `ENH:crash-fixes`.
`ENH:crash-fixes-more` contains that text, so the first marked line of this package in
`ChannelFragment.java` (parent expects exactly 9) or `LocalPlaylistFragment.java` (exactly 8)
makes the parent's test fail. Both files have to be edited (AC-1, AC-2, AC-5), and the marker
name is fixed by `PROCESS.md` and `check-layers.sh`.
Assumption used in plan.md (prerequisite P0): a `test(crash-fixes):` commit on this branch,
before any code commit, changes the filter in that test to
`it.contains(MARKER) && !it.contains("$MARKER-")`. This package's layers do not write that file.

## Q6 plan -> spec
AC-3, first sentence, and AC-5.
(a) When a video is added to a playlist without thumbnail: does the playlist stay without one,
or take the video's? Assumption used in plan.md (C3a): it stays as it is; only the crash is
removed (one changed line).
(b) AC-5 "it shows the tabs that exist". Reading the pager library shows that after such a
failure the pager of that page can add no further tab, and that removing only the crash at the
missing tab would let the page crash one frame later. Assumption used in plan.md (C5b): the
tab adapter also swallows and logs the failure outside its existing catch (12 lines in
`TabAdapter.java`). The page then shows the tab bar with all titles, the failed tab as far as
it got and the other tabs empty until the page is reopened. This is derived from the compiled
library and not observed; instrumented test I2 is its only check.

## A1 spec
Agreed, no edit. AC-3's second sentence is removed from the spec.

## A2 spec
Agreed. AC-4 now names the real trigger: a paused, idle live stream dragged to the end.

## A3 spec
Agreed: the share screen closes with the dropped dialog. AC-1 says so.

## A4 spec
Agreed: the late blocked-channel result is kept and handled on return. AC-2 says so.

## A5 spec
Done as a `test(crash-fixes)` commit on `feature/crash-fixes`: its wiring test now counts
`ENH:crash-fixes` only when no `-` or word character follows. This branch is rebased onto it.

## A6 spec
(a) Agreed, and stated in AC-3. (b) Agreed: the `finishUpdate` override is in scope; AC-5 now
describes what the user sees after such a failure. Instrumented test I2 must be run on a
device before the pull request is merged.

## Q7 tests -> plan
Instrumented tests I1 and I2 could not be written exactly as the plan words them. None of this
changes an edit of the plan. The tests are compiled, not run.
(a) I2, `ChannelInfo(0, id, url, url, name)`: the constructor of the extractor this branch
builds against takes a sixth argument, a `ListLinkHandler`. Assumption used: a handler with the
same url and id, no filters.
(b) I2 throws in `onFragmentPreCreated`, where the tab fragment has no view yet. C5b's reasoning
("the failed tab's view was added to the pager before the fragment threw, so a layout pass is
pending") and the crash on the device are about a tab that fails in or after `onViewCreated`.
With the failure before creation nothing guarantees a pending layout pass, so the test would
not reliably reach the `finishUpdate` that C5b is about. Assumption used: the test asks for the
layout itself (`requestLayout()` and one `measure(..)` of the pager on the main thread, so a
`Fragment already added` there is a test failure with a stack trace and not a dead process),
then waits a second of real frames; and there is a second test,
`ac5_tabFailingInViewCreated_channelPageSurvives`, that throws in `onFragmentViewCreated`, the
stage of the device crash. In that one the first tab's own code runs up to `onViewCreated`
against a made-up channel.
(c) The plan's row "The page survives the further lifecycle of a half-created tab fragment:
assumed; test I2" is a separate test, `ac5_failingTab_pageSurvivesStopStartAndLeaving` (stop,
start, Back). Read from the fragment library, not observed: at the stop the child fragment
manager moves the half-created tab on through `onCreate`..`onViewCreated`, so from there the
tab's own upstream code runs against the made-up channel. If this test fails on a device while
the two above pass, is that inside AC-5 ("the page does not crash") or the tab's own code, which
the spec excludes?
(d) I1 "expect one more join row": `onPlaylistSelected` starts the write and dismisses the
dialog at once, and the dialog's `onDestroyView` disposes that subscription. Upstream relies on
the write having started by then. Assumption used: the test waits up to five seconds for the
row; if only that assertion fails, without an exception, it is this upstream race and not C3a.

## Q8 tests -> plan
Tests beyond the plan's list ("there is one class, the source scan"). Say if any of them pins
more than the plan intends; the Code layer cannot change them.
(a) `TabAdapterFinishUpdateTest` (JVM) runs the real `TabAdapter.finishUpdate` on the real
`FragmentPagerAdapter` with a pending transaction that throws. Beyond the text of the 12 lines
it requires: an `IllegalStateException` never leaves `finishUpdate`; it is passed to
`Log.e("TabAdapter", .., e)` for the first failure only; any other exception still leaves the
method; `super.finishUpdate` is still called on every later pass (the plan's "self-healing").
A real `ViewPager` or `FragmentManager` cannot be built on the JVM (the unit-test `android.jar`
has no implementation), so the chain "one failed tab, then `Fragment already added` on every
pass" stays with I2.
(b) `ChannelPageLateBlockedResultTest` (instrumented, AC-2): blocked channel, activity stopped,
`handleResult` called, activity resumed. It reads the field `lateBlockedResult` by name and
covers only the first row of the table under C2 (app in background, view alive, without
`release-views`).
(c) The wiring test compares every marked line with the plan's line (whitespace ignored) and
checks the neighbouring line where the plan gives a position. Only the text of the log message
in `TabAdapter` is free. It also fails on any file under
`app/src/main/java/org/schabi/newpipe/enhancements/crashfixesmore` (plan section 0: no new file).

## A7 plan
(a) Use the six-argument constructor. (b) Agreed: the forced layout pass and the
`onFragmentViewCreated` variant are the better check. (c) A failure of the stop/start/Back
test alone, with the other two passing, is the tab's own code running against made-up data
and is outside AC-5; report it with the stack trace all the same. (d) Accepted; the test
relies on the same order the app does.

## A8 plan
No objection. The behaviour test of `TabAdapter`, the AC-2 instrumented test and the strict
wiring test are part of the contract.

## Q9 plan -> spec
AC-7, "a feed page that is left ... the lookup's result is dropped". Two readings of "left".
(a) Back: the page is destroyed. This is where the crash is.
(b) Another page is opened on top: the feed page still exists. Today a result that arrives then
shows its "could not load feed" dialog (or the snackbar for the other errors) over the page on
top and does not crash; the errors of one refresh are handled only once, so dropping the result
here would mean the user never gets that dialog for this refresh.
Assumption used in plan.md (C7): the result is dropped only in (a); (b) stays as it is today.
Two guard lines, each `if (context == null) return`.
Also assumed: the lookup is not added to the page's disposables, as the investigation proposed.
Reading the library shows that this would turn two errors of the lookup that are only logged
today ("subscription not found", and the page's context read on the background thread) into
crashes when the page is left at that moment; undoing that needs three more lines. The plan has
them ready.
Not covered, say if it should be: the buttons of that dialog read the page's context when
tapped. If the page is removed from outside while the dialog is open (a link or a notification
that resets the navigation), "Unsubscribe" would crash. That is after the lookup has returned.

## Q10 plan -> spec
AC-8 cannot be met as written; three points.
(a) "whatever caused it". The handler learns that nobody waits only through the wrapper RxJava
puts around an error, and RxJava does not wrap an error that is itself a NullPointerException,
IllegalStateException or IllegalArgumentException (nor four of its own types). Such an error from
a load that was left arrives looking exactly like a bug in a callback. Assumption used in plan.md
(C8): these close the app as today; the criterion holds for every other error, including one that
only has such an exception as its cause (the case of the investigation).
(b) "An error that still has a receiver but no handler closes the app as today": kept exactly.
But RxJava puts the same wrapper around an exception that a receiver's own callback throws, if it
is not one of the types in (a). Today that closes the app when one of those types is among its
causes, and is dropped without any trace otherwise. Assumption: both become a log line, because
they cannot be told from a load that was left. This is the main thing the change can hide.
(c) "is written to the log". An undeliverable error caused by a network failure or an
interruption (a cancelled request) is ignored today and stays ignored, without a stack trace:
there is one for nearly every page left while loading. Everything else undeliverable gets one
log entry with the tag `UndeliveredErrors` and the stack trace, including the errors that are
dropped silently today.
With the debug setting on, nothing changes at all.

## A9 spec
Agreed on all three points: "left" means closed; no join, since it would turn two logged
errors into crashes; the dialog-button case is out of scope. AC-7 is reworded.

## A10 spec
Accepted with the limits stated. AC-8 now says what the mechanism can deliver: errors RxJava
reports as undeliverable are logged; the two limits (a) and (b) are named in the criterion;
(c) is as intended.
