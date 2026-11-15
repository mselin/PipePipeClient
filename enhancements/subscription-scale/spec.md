# Many subscriptions

Costs that grow with the number of subscriptions. Findings M6, J1, J2 and J3 in
`enhancements/FINDINGS.md`. The owner has about 1600 subscriptions.

## Acceptance criteria

- **AC-1** (M6) The memory a feed refresh holds does not grow with the number of channels
  refreshed: results are stored as they arrive and then released. New-video notifications
  work as today.
- **AC-2** (J1) Drawing a video row never waits for the database on the main thread. The
  watched state and progress bar of a row appear as today, at most a moment after the row.
- **AC-3** (J2) A group feed that is not on screen does not query the database when groups
  or subscriptions change. It shows current data when it is returned to.
- **AC-4** (J3) The Subscriptions tab does not rebuild its whole list for a change that does
  not alter what it shows, and it does no work while it is not on screen. During a feed
  refresh it is not rebuilt at all unless a channel's name or picture changed.
- **AC-5** Subscribing, unsubscribing and changing groups show their result in the lists as
  today.
- **AC-6** With 1600 subscriptions, opening the Subscriptions tab and scrolling a channel's
  video list stay smooth on the owner's device (device check; no number is promised).
- **AC-7** No new setting.
