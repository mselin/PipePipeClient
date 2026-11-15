# Questions: subscription-scale

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "subscription-scale" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-4 contradicts itself for the default fetch method. The Subscriptions tab shows each
channel's subscriber count (list and grid rows), and a feed refresh that does not use the
dedicated fetch method rewrites that count for almost every channel. So "does not rebuild for
a change that does not alter what it shows" would rebuild on nearly every batch, while "during
a feed refresh it is not rebuilt at all unless a channel's name or picture changed" forbids it.
Planned with: the second sentence wins. While the tab stays on screen, only a change of the
set of channels, a name or a picture rebuilds the list; subscriber counts are brought up to
date when the tab is returned to, or with the next such change (plan 5.3). If counts must
follow a refresh live, `SubscriptionRows.sameNamesAndPictures` also compares the count and the
tab is rebuilt per batch again (cheaper than today, but not "not at all").

## Q3 plan -> spec
AC-3 and AC-4 say "not on screen" without defining it. Planned with: on screen = the screen's
fragment is resumed. That makes a hidden main tab, a page on the back stack and a stopped app
"not on screen". A feed or the Subscriptions tab that is only covered by the expanded player
stays resumed and keeps updating as today; detecting that would need a hook into the player
sheet. Also planned: the first load of a screen is allowed while it is not on screen yet, so
a tab that has never been shown has its list ready when first opened, as today.

## Q4 plan -> spec
AC-1 says the memory held "does not grow with the number of channels refreshed" and also
"new-video notifications work as today". The notification check shows its notifications after
the whole check, so it must keep something until the end. Planned with: a manual refresh
keeps nothing per channel; the notification check keeps, per notification-enabled channel
that has new videos, the channel's name, address, picture address and its new items only
(plan section 3). That still grows with the number of such channels, but not with the number
of channels refreshed or the size of their video lists. Showing each notification as soon as
its batch is stored would remove the remainder and change when notifications appear.

## A2 spec
"Name or picture only", as planned. Subscriber counts shown on the tab catch up when the tab
is returned to. AC-4 now says so.

## A3 spec
As planned: "not on screen" means the page is not resumed. A page under the expanded player
still counts as on screen, and the first load of a tab that was never shown is allowed.

## A4 spec
Accepted: the notification run keeps one small record per notification-enabled channel with
new videos. AC-1 now says so.

## Q5 tests -> plan
Plan 5.1 says the gate lets the first load through while "off screen and nothing delivered
yet", and 5.3 puts two streams (groups and channel list) through the one gate of
`SubscriptionViewModel`. It does not say whether "nothing delivered yet" is kept per gate or
per gated stream. Per gate, the stream that loads first would take the other's first load away
on a tab that was never shown.
Working assumption, pinned by `OnScreenGateTest.ac4_oneGateTwoStreams_*`: per stream returned
by `gate(source)`. Each gets its own first load off screen.

## Q6 tests -> plan
Three places where section 4 cannot be taken literally. The wiring test accepts the reading
given here.
(a) `ComposeInfoItemHolder.kt`, last line: `if (infoItem is StreamInfoItem) ...`. The file does
not import `StreamInfoItem` and section 2 allows no import lines. Assumed: the fully qualified
name is written, as the file already does in its click handlers. The test asks for
`if (infoItem is`, then `StreamInfoItem) watched.refresh(infoItem, historyRecordManager) { updateFromItem(infoItem, historyRecordManager) }`.
(b) Java holders: "`cancel()` is the first statement of every bind" but the line goes "after
`:53`", which is after the `instanceof` guard and its `return`. Assumed: the place is as the
line number says. The test asks for `cancel()` inside `updateFromItem` and before the first
`watched.cached`. In `ComposeInfoItemHolder.kt` it asks for the first line of the function.
(c) "An error is logged", but `LatestRequest.start` has no error callback and `LatestRequest.kt`
is a plain file that may not use `android.util.Log`. Assumed: `LatestRequest` consumes the
error itself (no callback, nothing reaches RxJava's global error handler, the next `start`
works); whether and how it is logged is left to Code and not tested.
Also: the marked-line counts are asserted exactly (36 lines, per file as listed), so the two
long lines of 5.3 (`channelItems`, `stateItemsDisposable`) must stay on one line each.

## Q7 tests -> plan
Two things the tests pin that the plan does not state.
(a) Work of `ReusingMapper.map`: for 1600 rows at most 4 calls of `idOf` and 4 row comparisons
(`equals`) per row and call, and at least one comparison per reused row. The plan gives no
bound; the bound is there so that a per-row search through the previous list (about 1.28
million comparisons per reload) does not pass. `sameNamesAndPictures` has no such test: its
comparisons cannot be counted from outside.
(b) `SubscriptionRowsQueryTest` (instrumented, the "optional" test of section 7) calls
`SubscriptionDAO.getAllRows()`, so `:app:compileDebugAndroidTestKotlin` fails until step 4 of
section 9 is in. Likewise the unit-test source set compiles only once all six plain files
exist: steps 1 to 3 each build `:app:assembleDebug` alone, but `:app:testDebugUnitTest` runs
from step 4 on (earlier with `RowWatchState`'s helpers present and the others stubbed).
Assumed: acceptable; the four commits are checked with the unit tests after the fourth.

## A5 plan
Per gated stream, as assumed.

## A6 plan
All three as the tests assume: the fully qualified name of `StreamInfoItem` where no import
exists; `cancel()` anywhere inside `updateFromItem` before the first `cached`, which is after
the `instanceof` guard; and an error of a row request is consumed without a callback (it is
not logged, because the plain class may not use Android logging).

## A7 plan
Accepted: the linear-work bound on `ReusingMapper` is now part of the contract. "Each commit
builds alone" is meant for the app; the code is delivered as one commit anyway.
