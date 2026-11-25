# Player memory

Findings M4, M5 and M8 in `enhancements/FINDINGS.md`.

## Today

- For every item played, up to 48 seekbar preview sheets are downloaded and kept decoded,
  also in the background player where no seekbar is shown.
- Every queue item that was loaded stays fully loaded for the life of the queue.
- Every playback start leaves one media session behind that is never released.

## Acceptance criteria

- **AC-1** Seekbar preview images are loaded only when a seekbar preview can be shown: not
  in the background player, and in the other players not before the user first touches the
  seekbar of that item.
- **AC-2** Preview images of an item are released when the player moves to another item, and
  a load still running for the old item stops.
- **AC-3** The memory held for preview images of one item has a fixed upper limit, whatever
  the video's length.
- **AC-4** Only the current item and its neighbours (the previous one and the next two) stay
  loaded. An item further away is unloaded and loads again when the player reaches it, as an
  item does today that was never loaded.
- **AC-5** Moving to the next or previous item is as fast as today.
- **AC-6** Starting playback creates one media session and releases the one before. Lock
  screen, notification, headset and Bluetooth controls work as today.
- **AC-7** Seekbar previews, once shown, look as today.
- **AC-8** No new setting.

## Amendment, 2026-11-25: on upstream 5.6.0

Upstream 5.6.0 (commit `aa31606`, "perf: load seekbar previews on first drag") no longer loads
an item's seekbar previews when the player reaches it. On a new item it drops the old item's
previews; at the first touch of the seekbar it downloads up to 48 sheets, as before, and keeps
them decoded. So "Today" now reads:

- For every item whose seekbar is touched, up to 48 seekbar preview sheets are downloaded and
  kept decoded. An item whose seekbar is never touched, and the background player, load none.

AC-1 is now mostly upstream's; AC-2 and AC-3 are not (the 48 sheets are still loaded at once,
about 47 MiB for large sheets, and a load for the old item still finishes the sheet in flight).
The owner chose on 2026-11-25 to keep this fix on top of upstream's: the acceptance criteria
stay as they are, and upstream's first-touch rule is kept, not undone.
