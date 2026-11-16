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
