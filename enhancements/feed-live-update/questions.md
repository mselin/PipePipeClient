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
