# Many subscriptions

Costs that grow with the number of subscriptions. Findings M6, J1, J2 and J3 in
`enhancements/FINDINGS.md`. The owner has about 1600 subscriptions.

## Acceptance criteria

- **AC-1** (M6) The memory a feed refresh holds does not grow with the number of channels
  refreshed (the notification run keeps one small record per channel with new videos): results are stored as they arrive and then released. New-video notifications
  work as today.
- **AC-2** (J1) Drawing a video row never waits for the database on the main thread. The
  watched state and progress bar of a row appear as today, at most a moment after the row.
- **AC-3** (J2) A group feed that is not on screen does not query the database when groups
  or subscriptions change. It shows current data when it is returned to.
- **AC-4** (J3) The Subscriptions tab does not rebuild its whole list for a change that does
  not alter what it shows, and it does no work while it is not on screen. During a feed
  refresh it is not rebuilt at all unless a channel's name or picture changed; subscriber
  counts catch up when the tab is returned to.
- **AC-5** Subscribing, unsubscribing and changing groups show their result in the lists as
  today.
- **AC-6** With 1600 subscriptions, opening the Subscriptions tab and scrolling a channel's
  video list stay smooth on the owner's device (device check; no number is promised).
- **AC-7** No new setting.

## Amendment, 2026-11-25: AC-1 is upstream's now

Upstream 5.6.0 (commit `0e5081f`, "fix: Memory usage keeps growing with the number of
subscriptions during feed refresh") trims every stored batch of a refresh itself: it keeps only
the channels with new videos, slimmed to what the new-video notifications need. That provides
AC-1. The owner chose upstream's version on 2026-11-25; this fix no longer carries its own.

- **AC-1** (replaces the text above) During a refresh, memory does not grow with the number of
  subscriptions. Provided by upstream; this fix adds nothing to the feed loader or the
  notification run.

## Amendment, 2026-11-27: the Subscriptions tab froze the screen

On the owner's phone (debug build, about 1600 subscriptions) the app stopped responding six
times between 24.11 and 27.11. The one complete stack trace (24.11 15:03) shows the main thread
in the Subscriptions tab's list update, reached from a tab switch. On 27.11 the log shows three
freezes of about 3 seconds in two minutes, each when the tab received its list or was shown
again during a feed refresh; the third blocked a touch for 5 seconds. Each update took about the
same time whether or not anything in the list had changed. AC-6 is not met.

- **AC-8** Showing the Subscriptions tab, returning to it, filtering it and updating its list
  do not freeze the screen. The work for one update grows in step with the number of
  subscriptions, not faster; showing again the list it already shows does no work. Rows look,
  change and animate as today.

## Amendment, 2026-11-27: the group editor and a group's channel page too

The owner asked on 2026-11-27 for the same fix in two other lists that show subscriptions with
the same list update: the channel picker of the group editor (all subscriptions, with a search
box) and the page that lists a feed group's channels (opened from a feed page; for "All", every
subscription).

- **AC-9** Opening, searching and updating the group editor's channel list and a group's channel
  page do not freeze the screen. The work for one update grows in step with the number of
  channels listed, not faster. Rows look, change, get selected and animate as today.
