# More crash fixes

A follow-up to `crash-fixes`. The code author of that package found more places with the same
defects, by reading; one more comes from a crash on the owner's device. This branch is built
on `feature/crash-fixes` and may use its helpers.

## Today

- **Dialogs shown after a wait.** "Add to playlist" and "Download" from the player, from the
  video detail page and from the share menu, and "Add to playlist" from a selection or from a
  playlist page, show a dialog after loading something first. If the screen was left or the
  app went to the background in between, showing the dialog can crash.
- **A late channel result.** A channel's data that arrives after its page is gone reads the
  page's context and, for a blocked channel, navigates back. Both can crash.
- **Playlists without a thumbnail.** Adding a video to a local playlist that has no thumbnail
  compares against a missing value and crashes. Reading the thumbnail of a playlist that was
  deleted in the meantime crashes too.
- **An idle live stream.** After seeking, the player can call "play" directly on a live
  stream that has gone idle, which does nothing; `crash-fixes` closed the same gap for
  resuming after a call and from the notification.
- **Swallowed tab errors.** The tab adapter catches an error from the pager and only prints
  it. The channel page then assumes one tab per page and crashes on a missing tab. On the
  owner's device this turned an error inside one tab into a crash of every channel page
  (2026-11-15; the error itself is fixed in `release-views`).

## Acceptance criteria

- **AC-1** "Add to playlist" and "Download", from the player, the video detail page, the
  share menu, a selection and a playlist page, never crash when the screen was left or the
  app went to the background before the dialog could be shown. The dialog is dropped then.
  From the share menu the share screen closes with it, and the link has to be shared again.
- **AC-2** A channel result that arrives while its page cannot navigate never crashes. For a
  blocked channel the result is kept, and the message and the step back happen when the page
  is returned to.
- **AC-3** Adding a video to a local playlist works when the playlist has no thumbnail. The
  playlist stays without a thumbnail, as before the add.
- **AC-4** Dragging the seek bar to its end on a paused live stream that has gone idle
  resumes it live.
- **AC-5** When a tab fails while a channel page builds its tabs, the page does not crash,
  now or at the next layout. It shows the tab bar; the failed tab is shown as far as it got
  and other tabs may stay empty until the page is reopened. The failure is written to the
  log once.
- **AC-6** Nothing else changes for the user: no setting, no text.

## Amendment, 2026-11-18

After the owner's crash on build 5 while playing, refreshing a group feed and navigating
(issue mselin/PipePipe#26; no log). Reading found two candidates in upstream code.

- **AC-7** A feed page that is closed (not merely covered by another page) before its "channel not available" lookup has returned
  never crashes; the lookup's result is dropped.
- **AC-8** An error that RxJava reports as undeliverable, because the load it belongs to was
  abandoned when its page was left, is written to the log and does not close the app, unless
  it was caused by the network or an interruption, which stay ignored as today. With the
  debug setting that reports such errors switched on, today's behaviour is kept. Two limits
  follow from how RxJava classifies errors and are accepted: an abandoned load that fails
  with a bare `NullPointerException`, `IllegalStateException` or `IllegalArgumentException`
  still closes the app; and an exception of another type thrown inside a live callback is
  also logged where today it could close the app.

## Not covered

- The cause of any pager failure. This package only stops such a failure from crashing the
  channel page.
- The video detail page's own use of the tab adapter, unless the plan finds the same crash
  there.
- Moving the blocking database read of the multi-delete in local playlists off the main
  thread.
