# Crash and stuck-state fixes

Findings C1 to C6 in `enhancements/FINDINGS.md`.

## Acceptance criteria

- **AC-1** (C1) A long-press action that first has to load the video's details (Download,
  Enqueue, Enqueue next, Start in background, Start in popup, Open channel, Add to playlist)
  never crashes when the page was left before the details arrive. Actions that need no page
  (enqueue, start playing) are still carried out; actions that need the page are dropped.
- **AC-2** (C2) A search that was interrupted by leaving the app continues or restarts when
  the app is returned to: the spinner does not stay forever, and loading more results works.
- **AC-3** (C3) A channel page whose data arrived while the app was in the background shows
  its tabs and content when the app is returned to.
- **AC-4** (C4) A live stream that was paused by a call or alarm resumes afterwards when
  "resume on focus gain" is on; when it is off, one tap on play resumes it.
- **AC-5** (C5) A download or post-processing error without a message is shown as a failed
  download, not as a crash.
- **AC-6** (C6) Deleting an item from a local playlist works when the playlist or the item
  has no thumbnail.
- **AC-7** Nothing else changes for the user.
