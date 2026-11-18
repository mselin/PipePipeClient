# Queue guard

While something is playing, a tap must not silently replace the queue.

## Today

- Tapping a video in a list opens its page. When the main player is in use (or no player is
  open) and autoplay is allowed, the tapped video starts at once and replaces the queue.
- When the background or popup player is in use, the page opens without playing and that
  player carries on. The queue is replaced when the user presses play, background or popup
  on the page.
- In a local playlist with Music Player Mode on, a tap replaces the queue with the playlist,
  played in the background from the tapped video.
- The Play All, Background and Popup buttons above a list replace the queue with that list.
- The user is never asked.

## Terms

- **Something is playing**: a player (main, background or popup) is open and holds at least
  one item, playing or paused.
- **A queue worth guarding**: something is playing, except when the main player is in use
  and its queue holds at most one video the user put there. Videos the player added by itself
  through "Auto-enqueue next stream" never count, whether they are still ahead or have
  already played. Watching one video in the main player and letting the next ones follow by
  themselves is not a queue.
- **Video tap**: a tap on a video in any list inside the app (search, trending, channel,
  remote or local playlist, feed, history, related videos) that today opens the video or, in
  Music Player Mode, starts it.
- **Play buttons**: Play All, Background and Popup above a remote playlist, a channel's
  videos, a local playlist, the history and the feed.
- **Current item**: the queue item the player is on.
- **Play now**: the tapped video replaces the queue and plays in the player that is in use.
  - Main player: what a video tap does today, autoplay setting included: where autoplay is
    not allowed, the page opens and the video waits for the user to press play.
  - Background player: the tapped video alone starts in the background player, as the
    long-press action "Start playing in the background" does in search results. The list
    stays on screen.
  - Popup player: the tapped video alone starts in the popup player, as the long-press action
    "Start playing in a popup" does in search results. The list stays on screen.
  - In a local playlist with Music Player Mode on: what a video tap does there today,
    whichever player is in use.

## Acceptance criteria

Video taps

- **AC-1** With a queue worth guarding, a video tap on anything but the current item opens
  the queue-guard dialog. Until the user chooses, nothing else happens: no page opens,
  playback and queue are untouched.
- **AC-2** The dialog shows the title of the tapped video, the number of items in the queue,
  and four choices: *Add to queue*, *Play next*, *Play now (replaces the queue)*, *Cancel*.
- **AC-3** *Add to queue* appends the video to the end of the queue. The result equals the
  existing long-press action "Enqueue": same toast, the list stays on screen, playback is not
  interrupted.
- **AC-4** *Play next* inserts the video right after the current item. The result equals the
  existing long-press action "Enqueue next".
- **AC-5** *Play now (replaces the queue)* does Play now as defined above.
- **AC-6** *Cancel*, Back, a tap outside the dialog, or the dialog being dismissed by a
  screen rotation changes nothing.
- **AC-7** Without a queue worth guarding (nothing is playing, or a single video in the main
  player), a video tap behaves as today and no dialog appears.
- **AC-8** A video tap on the current item behaves as today and no dialog appears.
- **AC-9** If the player was closed while the dialog was open, *Add to queue* and *Play next*
  do what "Enqueue" does today when nothing is playing (the video starts in the background
  player), and *Play now* does what a video tap does while nothing is playing.

Play buttons

- **AC-10** With a queue worth guarding, pressing a play button opens the dialog in its list
  form. It shows how many videos the list would add, the number of items in the queue, and
  four choices: *Add all to queue*, *Play all next*, *Replace queue*, *Cancel*. A play button
  on a list with no videos loaded behaves as today, without a dialog.
- **AC-11** *Add all to queue* appends the videos loaded in the list, in list order, to the
  end of the queue. *Play all next* inserts them, in list order, right after the current
  item. Both show the toast of the matching single-video action and do not interrupt
  playback. The count shown in the dialog is the number of videos added. While the player's
  queue is shuffled, the added videos are mixed in as "Enqueue" does today. If the player was
  closed while the dialog was open, both do what "Enqueue" does today while nothing is
  playing: the list starts in the background player.
- **AC-12** *Replace queue* does what the pressed button does today.
- **AC-13** *Cancel* and the other dismissals of AC-6 change nothing.
- **AC-14** Without a queue worth guarding, the play buttons behave as today and no dialog
  appears.

Setting

- **AC-15** A switch "Ask before replacing the queue" sits directly below "Preferred 'open'
  action" (Settings > Player). It is on by default and covers video taps and play buttons
  together.
- **AC-16** With the switch off no dialog appears. With a queue worth guarding, a video tap on
  anything but the current item does Play now directly, and the play buttons behave as today.

Reaching the video page

- **AC-17** While the background or popup player is in use, the long-press menu of a video
  contains "Show stream details". It opens the video's page without touching playback, as a
  video tap does today in that situation. Where the menu has that entry already, nothing
  changes.

General

- **AC-18** All new texts are translatable. English is delivered; other languages fall back
  to English.
- **AC-19** The dialog is a standard dialog: operable by touch, D-pad/remote and screen
  reader like the app's other dialogs.

## Differences from the app without this feature

These hold whatever the switch is set to:

- While the background or popup player is in use, a video tap no longer opens the video's
  page. It asks, or with the switch off plays the video in that player. The page is reached
  through the long-press menu (AC-17).

Everything else is unchanged while the switch is off.

## Not covered

These keep today's behaviour:

- The other long-press menu actions of a video (they are already explicit).
- The play, background and popup buttons on a video's page.
- Links opened from other apps (they have their own "Preferred 'open' action").
- Taps inside the queue screen, and casting.

## Amendment 2, 2026-11-18

After the owner's device test of build 5 (issue mselin/PipePipe#24). Where this section
contradicts the text above or an answer in `questions.md` (A11, A13), this section holds.

### Changed term

**A queue worth guarding**: the player (main, popup or background) holds at least one item,
playing or paused. How the items got there no longer matters: one video counts, and so do
videos added by "auto-enqueue next stream".

### Changed and new criteria

- **AM-1** (replaces AC-7 and AC-14) Without a queue worth guarding, that is, while the
  player holds nothing, video taps and play buttons behave as today and no dialog appears.
- **AM-2** One exception: a video tap in the list of related videos on the video page of the
  current item opens the video as today, without a dialog.
- **AM-3** On the video page of a video that is not the current item, with a queue worth
  guarding, the page's play button, its Background and Popup buttons, a tap on its thumbnail
  and the mini player's play button open the video-tap dialog for that video. *Play now* does
  what the pressed control does today. On the page of the current item these controls behave
  as today.
- **AM-4** "Details" in a queue item's menu opens that video's page and leaves the queue as
  it is, in every player.
- **AM-5** With a queue worth guarding, a link to another video in a description or a
  comment (with or without a time), and a video address entered in search, open the
  video-tap dialog for that video. A link to a time in the current item seeks as today.
- **AM-6** With a queue worth guarding, a video opened from another app (shared or "open
  with") opens the video-tap dialog when the action chosen for it would replace the queue.
  Actions that only add to the queue, download or show information behave as today.
- **AM-7** With "Ask before replacing the queue" off, AM-3, AM-5 and AM-6 do not apply and
  those paths behave as today. AM-4 applies whatever the switch says.
- **AM-8** The long-press entries "Start playing in background" and "Start playing in popup"
  and the multi-select actions stay as they are: they are explicit choices.
