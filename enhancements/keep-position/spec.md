# Keep the position of the playing video

When the player has to load the playing video again, it continues where it was, even while
"Always start play from beginning" is on. Links that name a time go to that time again while
that switch is on.

## Today

- "Always start play from beginning" (Settings > Advanced, off by default) makes a video start
  at 0:00 instead of at a remembered position.
- While that switch is on, the position of the video that is already playing is discarded too
  whenever the player loads it again. The video then restarts at 0:00:
  - when it moves between the main, background and popup player (reported by the user);
  - when its quality is changed, and when the player recovers from a network or decoder error
    (read from the code, not yet seen on a device).
- While that switch is on, a link that names a time does not go to it: the video stays where
  it is or starts at 0:00. This holds for a time in a video's description or comments and for
  a video link whose address carries a start time (read from the code, not yet seen on a
  device).
- While that switch is off, reloads continue at the position the video had and links that
  name a time go to it.

## Terms

- **Playing video**: the queue item the player is on, playing or paused.
- **Start**: the user or the queue chooses a video to play: a video tap, an item picked in the
  queue, next, previous, the automatic move to the next item, a video opened from the history
  or from a link, and pressing play after the player was closed.
- **Timed start**: a start that names its own time. There are two kinds:
  - a **timestamp link**: a time in a video's description or comments;
  - a **video link with a start time**: a link to a video whose address carries a start time
    (such as `?t=90`), opened from another app (Share, Open with) or tapped inside the app.
- **Reload**: the player loads the playing video again without a start in between. The known
  reloads are:
  - **player switch**: the playing video moves between the main, background and popup player,
    by a button (Background and Popup on the video's page, the popup's fullscreen button,
    "Switch to ..." in the queue screen) or automatically ("Minimize on app switch" and
    coming back to the app);
  - **quality change**: another quality is picked in the player;
  - **error recovery**: the player reloads the video after a network or decoder error.

## Acceptance criteria

- **AC-1** A switch "Keep position of the playing video" sits directly below "Always start
  play from beginning" (Settings > Advanced). It is on by default. Its summary says that the
  video continues where it was when it moves between players, changes quality or recovers
  from an error.
- **AC-2** With both switches on, after a player switch the video continues at the position
  it had, in every direction between the main, background and popup player, for the button
  and for the automatic ways.
- **AC-3** With both switches on, after a quality change the video continues at the position
  it had.
- **AC-4** With both switches on, after an error recovery the video continues at the position
  it had.
- **AC-5** "The position it had" is the position at the moment the reload began, as exactly
  as seeking is in the app (the existing "Use fast inexact seek" setting keeps its effect).
- **AC-6** Whether the video plays or stays paused after a reload is as today.
- **AC-7** A start is not a reload. While "Always start play from beginning" is on, every
  start begins at 0:00 as today, whatever the new switch is set to. This includes an item
  that was played earlier in the same queue and the video that follows a reloaded one.
- **AC-8** With the new switch off, the app behaves exactly as it does without this feature,
  except for timed starts (AC-12 to AC-14).
- **AC-9** While "Always start play from beginning" is off, the app behaves exactly as it
  does without this feature, whatever the new switch is set to.
- **AC-10** Live streams behave as today: there is no position to keep.
- **AC-11** All new texts are translatable. English is delivered; other languages fall back
  to English.

Timed starts

- **AC-12** Tapping a timestamp link plays that video from the link's time, whatever "Always
  start play from beginning" and the new switch are set to. If that video is the playing
  video, it jumps to the time without restarting.
- **AC-13** Opening a video link with a start time plays the video from that time, whatever
  the two switches are set to.
- **AC-14** Everything else about a timed start (which player is used, what happens to the
  queue, whether playback starts by itself) is as it is today while "Always start play from
  beginning" is off. A timed start is the only start that is exempt from "Always start play
  from beginning": after it, AC-7 applies again and the next start begins at 0:00.

## Not covered

- What "Always start play from beginning" does to starts (AC-7 keeps it).
- The "Resume playback" setting of the watch history.
