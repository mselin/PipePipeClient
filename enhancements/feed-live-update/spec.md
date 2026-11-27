# Feed stays visible while it updates

Asked for by the owner on 2026-11-18. Built on `feature/subscription-scale`, whose
"on screen" gate it uses.

## Today

- Refreshing a feed ("All" or a channel group) replaces the feed's video list by a
  full-screen progress display until the whole refresh has ended. With many subscriptions
  that takes minutes, and nothing can be watched or opened from the feed meanwhile; taps on
  items are switched off.
- The results are stored as they arrive, in batches of channels, long before the end. Only
  the page does not show them.
- Progress is announced app-wide. Every open feed page shows the progress display, also the
  page of a group that is not being refreshed.

## Terms

- **A refresh concerns a feed page** when it is a refresh of "All", or a refresh of the
  group the page shows. A refresh of group A does not concern the page of group B. A refresh
  of a group concerns the "All" page, because that page shows the group's channels too. The
  background check for new videos, which the notifications use, concerns every page.
- **At the top**: the list is scrolled to its first item.

## Acceptance criteria

- **AC-1** While a refresh that concerns it is running, a feed page keeps showing its video
  list as it was before the refresh. The list is not hidden and not replaced.
- **AC-2** As the results of the refresh are stored, the list shows them: an update follows
  each stored batch, and the list is rebuilt at most once in two seconds. When the refresh
  ends the list shows the final state, as it does today.
- **AC-3** While the refresh runs, its progress (the counts shown today) stays visible in a
  form that does not cover the list: in the line above the list, with the existing texts.
- **AC-4** While the refresh runs, everything that works on the list when no refresh runs
  works too: tapping a video, the long-press menu, the play buttons above the list,
  scrolling, selecting.
- **AC-5** What the user is looking at does not move. If the list is not at the top when an
  update arrives, the items on screen stay where they are, and the page's existing "new
  items" indicator shows that there is something above. At the top, new videos appear at
  the top, and the list stays at the top; this holds for every update of the list, the final
  one included. In grid view the rows stay where they are, but videos can move between
  columns when the number of new videos above is not a multiple of the column count.
- **AC-6** A feed page that has nothing to show yet (never loaded, or empty) shows the
  progress display of today until the first results are stored.
- **AC-7** A refresh that does not concern a feed page changes nothing on that page: no
  progress, no reload.
- **AC-8** The end of a refresh is as today: the messages about channels that could not be
  loaded, the count of feeds not loaded, the highlighting of new items, the time of the
  oldest update.
- **AC-9** A selection made during or before the refresh keeps the selected videos that
  still exist. A search text that is set keeps applying to every update of the list, during
  a refresh and outside one. (Today any update of the list while a search text is set shows
  the whole list again; that is corrected with this feature.)
- **AC-10** A feed page that is not on screen does no work for a running refresh; it shows
  the current state when it is returned to, as `subscription-scale` already provides.
- **AC-11** Starting, cancelling and failing a refresh, and pulling to refresh while one
  runs, behave as today. A refresh asked for on a page that the running refresh does not
  concern is not started, as today; the pull indicator goes back and nothing is said.
- **AC-12** No setting. Texts are added only if AC-3 cannot be met with the existing ones.

## Not covered

- Showing per group or per subscription whether it is waiting, being updated or done.
- Changing how many channels are stored per batch, or how many are fetched at once.
- The feed losing a channel's older videos when that channel is refreshed (finding D4):
  it becomes visible during a refresh, and is unchanged.
