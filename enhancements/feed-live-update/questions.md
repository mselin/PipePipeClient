# Questions: feed-live-update

## Q1 plan -> spec
The terms name two kinds of refresh, "All" and a group. There is a third: the notification
check refreshes the channels that have notifications switched on, through the same loader,
and is announced like the others (today every feed page shows its progress). Its channels
can belong to any group.
Planned with: it concerns every feed page, like a refresh of "All" (plan section 4). The
alternative, "All" only, is one term less in `FeedRefreshLog.concerns`.

## Q2 plan -> spec
AC-5 cannot be met fully in grid mode. The list keeps the rows on screen at their height,
but the grid is filled row by row from the first item: when the number of videos inserted
above is not a multiple of the column count, every video below moves by that many columns.
Keeping them would need empty cells in the grid.
Planned with: accepted for the grid; list and card mode keep the items exactly (plan
section 7).

## Q3 plan -> spec
AC-3 asks for the progress "in a form that does not cover the list". Planned with (plan
6.1): the counts replace the header line "Feed last updated: …" during the refresh, as
"Loading feed… 12/1600", then "Processing feed…", both existing texts; the progress bar in
the middle of the page is not shown in this mode, because it lies over the list and moving
it needs a layout change. The round pull-to-refresh indicator stays over the top edge of the
list for the whole refresh, as it does today: it is what makes a pull during a refresh do
nothing (AC-11). If it counts as "covering", it can be switched off during the refresh; a
pull would then show it for a moment and start nothing.

## Q4 plan -> spec
AC-9 says a filter that is set "keeps applying to every update". Today it does not: any
list update while a search text is set shows the whole list again, although the search
field still holds the text (`FeedFragment.kt:829-837`). Before this feature that was rare,
because the list was not updated during a refresh.
Planned with: the filter is applied to every list update, also to the final one and to
updates outside a refresh (plan 6.2). This changes today's behaviour beyond the refresh.

## Q5 plan -> spec
AC-5 speaks of "an update" during the refresh. The final list of a refresh is built by the
same code as any reload (return to the page, the "show watched" switch), and today a reader
at the top does not see the new videos there: they are inserted above and the "new items"
button points to them.
Planned with: "at the top, new videos appear at the top" holds for every list update,
including the final one and reloads outside a refresh. The button for "something above" is
added only during a refresh; for the final state it is shown as today (plan section 7).

## Q6 plan -> spec
AC-7 makes a page that is not concerned look idle, so its refresh controls can be used
while another group is being refreshed. The loader runs one refresh at a time and drops a
second request without notice; today this cannot be seen, because that page shows the
progress display. AC-11 does not cover it.
Planned with: the request is dropped as today and the pull indicator goes back at once;
nothing is said, because no existing text fits and AC-12 asks for no new one (plan 6.3).
Queueing the second refresh would change the loader and is not planned.

## A1 spec
Agreed: the notification check concerns every page. Added to the terms.

## A2 spec
Accepted, and stated in AC-5.

## A3 spec
Accepted: the counts in the line above the list, with the existing texts. AC-3 says so.

## A4 spec
Agreed: the search text applies to every update of the list, also outside a refresh. The
spec's "as today" was wrong; AC-9 is corrected and names the change.

## A5 spec
Agreed, for every update including the final one. AC-5 says so.

## A6 spec
Accepted as today's behaviour. AC-11 names it.

## Q7 tests -> plan
`FeedRefreshLog`: the plan marks only `batchStored()` as synchronized. `started()` is called on
the thread that subscribes to the loader's chain and `ended()` from `doFinally`, on the thread
that terminates or disposes it, and the notification check can run beside a manual refresh
(risk 7), so the running count is written from more than one thread; `groupId` and
`runningElsewhere` are read on the main thread and on the page's background thread.
Tested with: the running count survives concurrent calls
(`FeedRefreshLogTest.ac11_startedAndEnded_fromSeveralThreads_balance`: eight threads, as many
`ended()` as `started()`, then nothing is running and the next refresh is seen). An atomic
counter or a synchronized pair meets it. If that is more than the plan wants, the test goes.

## Q8 tests -> plan
`FeedListRules.progressText` with neither counts nor a message: plan 6.1 reads "without counts
the event's message ("Processing feed…"), else "∞/∞"". Not said: whether the loading text
stands before it ("Loading feed… ∞/∞"), and what "without counts" is when only one of the two
numbers is -1.
Tested with: `"∞/∞"` alone, as `FeedFragment.kt:753-759` shows it today
(`FeedListRulesTest.ac3_progressText_withNeither_isInfinity`); "without counts" is tested only
for both numbers -1, the one case the loader posts (`ProgressEvent(@StringRes)`), and a single
-1 is left open. Also left open: `matches(null, null, "")`, which the page never asks.

## Q9 tests -> plan
A result that no concerned page has consumed, and the next refresh. Refresh of group 9 ends
while only the page of group 7 is on screen: that page does not consume the result (5.2), so
it keeps `SuccessResultEvent` (with group 9's errors) as its latest event. When the next
refresh starts, `started(7)` or `started(-2)` changes `log.groupId` at once, but the first
event of the new refresh is posted only after the outdated-subscriptions query and a hop to
the main thread (`FeedLoadManager.kt:113-127`). If the page's stream emits in between (a Room
re-emission, the "show watched" switch), the old result is decided on as a concerned SUCCESS:
the page shows group 9's error messages as the end of a refresh and `reset()` takes the
result away from "All". Section 4 says "the group is always set before the first event of a
refresh", which holds; the case here is the page's old latest event, decided on before that
first event arrives. AC-7 does not allow it; risk 1 does not name it.
Tested with: nothing pins this sequence, in either direction. The tests pin that a page not
concerned posts nothing and does not consume (`FeedLivePageTest.ac7_…`), and that the group
stays the last started one after `ended()` (which the end of a refresh needs).
