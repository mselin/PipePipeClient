# Questions: player-memory

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "player-memory" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-3 and AC-7 pull against each other once AC-1 holds. Today all preview sheets of an item (up to
48, about 47 MiB at the default quality) are loaded when the item starts, so every preview is
there when the seekbar is touched. A fixed limit well below that leaves two choices: (a) load
fewer sheets, which makes previews of long videos coarser (the frame shown can be minutes away
from the thumb), or (b) keep today's frames but load sheets on demand while the user drags, which
shows the preview of a newly reached part one download later (until then the nearest loaded frame
is shown). Is (b) what AC-7 "once shown, look as today" allows, and is 8 MiB per item an
acceptable limit?
Planned with: (b), 8 MiB of decoded sheets per item (about 8 sheets at the default quality).

## Q3 plan -> spec
AC-4, three details the code forces a choice on. (1) At the end of the queue the player today
preloads the first item whatever the repeat mode; should "the next two" wrap around the end?
(2) When a queue edit (remove, move, shuffle) pushes a loaded item out of the window, may it stay
loaded until the player next synchronizes with the queue (the next item change or neighbour load,
normally seconds), instead of at once? Unloading at once is not safe: the queue index can be ahead
of what is actually playing. (3) AC-5 names next and previous only: picking an item further away
that was played earlier loads it again (today it starts at once until its source expires). Is
that the intended cost of AC-4?
Planned with: (1) yes, wraps; (2) yes, at the next synchronization; (3) yes, accepted.

## A2 spec
On-demand loading with a limit of 8 MiB of decoded sheets per item, as planned. A newly
reached part of a long video shows its preview one download later than today; AC-7 means the
previews look the same once shown.

## A3 spec
Yes to all three: "the next two" wrap at the end of the queue as the player's own order
does; an item pushed out of the window by a queue edit may stay loaded until the next
synchronization; picking a far item that was played earlier loads it again.

## Q4 tests -> plan
PROCESS.md, on defect fixes inside upstream code: "Where it has no decision logic, instrumented
tests and a device script take the place of unit tests." Section 4 gives AC-6 (M8) the device
script D1 only and names no instrumented test for any criterion. Is an instrumented test wanted
for AC-6 (for example: after `initPlayback` twice, one active `MediaSessionCompat` of the app)?
Working assumption: none. No file was added under `app/src/androidTest`; AC-6 is covered by the
source-scan tests `PlayerMemoryWiringTest.ac6_*` (the two marked lines, their place, one caller of
`setupMediaSession` left) and by D1.

## Q5 tests -> plan
`PreviewLoadState`, three points section 3 leaves open. (1) `request` for a cached page, or for
the page in flight, while another page is pending: is the pending page kept? (2) A download that
fails (Picasso throws or returns null): section 3 only describes a result with a bitmap. If the
glue does not call `onLoaded` for a failure, the page stays in flight and the item never loads
another sheet. (3) `refresh(player.binding.playbackSeekBar, progress, true)`: which `progress`?
Working assumptions: (1) yes, read literally ("null when not armed, when `cached`, or when
`page` is the page in flight" changes nothing); pinned by
`PreviewLoadStateTest.ac1_cachedOrInFlightRequests_doNotDropThePendingPage`. (2) the glue calls
`onLoaded` for every finished download and puts nothing into the cache when there is no bitmap;
not testable on the JVM, to be checked in D4 with the network off. (3) the seekbar's own
`progress`; not tested.

## Q6 tests -> plan
Section 7 has `LoadWindowTest` green after code commit 2 (M5) and the remaining tests green
after commit 3 (M4). All tests of the package are one Gradle source set: it compiles only when
all four plain classes and `LazySeekbarPreviews.MAX_SHEET_BYTES` exist, so after commits 1 and 2
alone no unit test of the app can run. The wiring tests also count all six marked lines at once.
Working assumption: accepted; the tests are run after commit 3 (or the Code layer adds the new
files of step 3 before the hook lines). The tests were not split to match the three commits.

## A4 plan
None. The media-session fix has no observable state an instrumented test could reach without
a running player service and a real session; device step D1 covers it.

## A5 plan
1. Yes, a cached or in-flight request keeps the pending page.
2. Yes. A failed download calls `onLoaded` like a successful one, with no sheet; the glue
   logs the failure under the tag `PlayerMemory`.
3. The seekbar's own progress.

## A6 plan
Accepted. The code is delivered as one commit, and the tests run on it.
