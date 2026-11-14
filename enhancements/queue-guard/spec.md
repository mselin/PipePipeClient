# Queue guard

Tapping a video must not silently throw away a play queue the user has built.

## Today

- Tapping a video in a list opens its page. When the main player is in use (or no player is
  open) and autoplay is allowed, the tapped video starts at once and the queue is replaced by
  that one video.
- When the background or popup player is in use, the page opens without playing. The queue
  is replaced as soon as the user presses play, background or popup on that page.
- In a local playlist with Music Player Mode on, a tap replaces the queue with the playlist.
- The user is never asked.

## Terms

- **Queue**: the items loaded in the player (main, background or popup), playing or paused.
- **Built queue**: a queue of two or more items. An item appended by "Auto-enqueue next
  stream" does not count, so watching one video with auto-enqueue on is not a built queue.
- **Video tap**: a tap on a video in any list inside the app (search, trending, channel,
  remote or local playlist, feed, history, related videos) that today opens the video or, in
  Music Player Mode, starts it.
- **Current item**: the queue item the player is on.

## Acceptance criteria

- **AC-1** With a built queue, a video tap on anything but the current item opens the
  queue-guard dialog. Until the user chooses, nothing else happens: no page opens, playback
  and queue are untouched.
- **AC-2** The dialog shows the title of the tapped video, the number of items in the queue,
  and four choices: *Add to queue*, *Play next*, *Open and replace queue*, *Cancel*.
- **AC-3** *Add to queue* appends the video to the end of the queue. The result equals the
  existing long-press action "Enqueue": same toast, the list stays on screen, playback is not
  interrupted.
- **AC-4** *Play next* inserts the video right after the current item. The result equals the
  existing long-press action "Enqueue next".
- **AC-5** *Open and replace queue* continues exactly as the tap would have without this
  feature (see Today).
- **AC-6** *Cancel*, Back, a tap outside the dialog, or the dialog being dismissed by a
  screen rotation changes nothing.
- **AC-7** Without a built queue (no player open, empty queue, one item, or one item plus an
  auto-enqueued one), a video tap behaves as today and no dialog appears.
- **AC-8** A video tap on the current item behaves as today and no dialog appears.
- **AC-9** If the player was closed while the dialog was open, *Add to queue* and *Play next*
  do what "Enqueue" does today when nothing is playing: the video starts in the background
  player.
- **AC-10** A setting "Tapping a video while a queue is playing" sits next to "Preferred
  'open' action" (Settings > Video & audio). Values: *Ask* (default) and *Open and replace
  queue*.
- **AC-11** With the setting on *Open and replace queue* the app behaves exactly as it does
  without this feature.
- **AC-12** All new texts are translatable. English is delivered; other languages fall back
  to English.
- **AC-13** The dialog is a standard dialog: operable by touch, D-pad/remote and screen
  reader like the app's other dialogs.

## Not covered

These keep today's behaviour:

- The long-press menu of a video (its actions are already explicit).
- The Play all / Background / Popup buttons in playlist, channel and feed headers.
- The play, background and popup buttons on a video's page.
- Links opened from other apps (they have their own "Preferred 'open' action").
- Taps inside the queue screen, and casting.
