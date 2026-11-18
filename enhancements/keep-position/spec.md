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
  - when its quality or audio track is changed, and when the player recovers from a network
    error or from a decoder failure it can recover from (confirmed in the code by the plan,
    not yet seen on a device).
- While that switch is on, a link whose address names a time does not go to it: the video
  stays where it is or starts at 0:00. This holds for a link in a description or comments
  whose address ends in a time (`&t=90`, `#timestamp=90`) and for a video link whose address
  carries a start time (confirmed in the code by the plan, not yet seen on a device).
- A time written as text in a description or comments (`1:30`) is different: it jumps the
  running player directly and already works whatever that switch is set to.
- While that switch is off, reloads continue at the position the video had and links that
  name a time go to it.

## Terms

- **Playing video**: the queue item the player is on, playing or paused.
- **Start**: the user or the queue chooses a video to play: a video tap, an item picked in the
  queue, next, previous, the automatic move to the next item, a video opened from the history
  or from a link, and pressing play after the player was closed.
- **Timed start**: a start that names its own time. There are two kinds:
  - a **timestamp link**: a link in a video's description or comments whose address ends in a
    time (`&t=90`, `#timestamp=90`). A time written as text (`1:30`) is not one: it works
    today and is not changed;
  - a **video link with a start time**: a link to a video whose address carries a start time
    (such as `?t=90`), opened from another app (Share, Open with) or tapped inside the app.
- **Reload**: the player loads the playing video again without a start in between. The known
  reloads are:
  - **player switch**: the playing video moves between the main, background and popup player,
    by a button (Background and Popup on the video's page, the popup's fullscreen button,
    "Switch to ..." in the queue screen) or automatically ("Minimize on app switch" and
    coming back to the app);
  - **quality or audio track change**: another quality or audio track is picked in the
    player;
  - **error recovery**: the player reloads the video after a network or decoder error.

## Acceptance criteria

- **AC-1** A switch "Keep position of the playing video" sits directly below "Always start
  play from beginning" (Settings > Advanced). It is on by default. Its summary says that the
  video continues where it was when it moves between players, changes quality or recovers
  from an error.
- **AC-2** With both switches on, after a player switch the video continues at the position
  it had, in every direction between the main, background and popup player, for the button
  and for the automatic ways. Where the main player does not start by itself after the
  popup's fullscreen button (the Autoplay setting decides that, as today), pressing play then
  still continues at that position, unless another video was started in between.
- **AC-3** With both switches on, after a quality or audio track change the video continues at
  the position it had.
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
- **AC-10** Live streams behave as today: there is no position to keep, and a timed start of
  a live stream is not exempt either.
- **AC-11** All new texts are translatable. English is delivered; other languages fall back
  to English.

Timed starts

- **AC-12** Tapping a timestamp link plays that video from the link's time, whatever "Always
  start play from beginning" and the new switch are set to. If that video is the playing
  video and alone in its queue, it jumps to the time without restarting; in a longer queue
  the tap replaces the queue by that video, as it does today.
- **AC-13** Opening a video link with a start time plays the video from that time, whatever
  the two switches are set to.
- **AC-14** Everything else about a timed start (which player is used, what happens to the
  queue, whether playback starts by itself) is as it is today while "Always start play from
  beginning" is off. A timed start is the only start that is exempt from "Always start play
  from beginning": after it, AC-7 applies again and the next start begins at 0:00.

## Not covered

- What "Always start play from beginning" does to every other start (AC-7 keeps it). That
  includes a start position that comes without a link: a cast session handed to the app, and
  Bilibili round play.
- Times written as text in descriptions and comments. They already work.
- The "Resume playback" setting of the watch history.

## Amendment 2, 2026-11-18

After the owner's device test of build 5 (issue mselin/PipePipe#25): after a network change
playback restarted from the beginning, and so did a move between foreground and background.
Reading the code found the hooked reloads correct and three uncovered paths. Where this
section contradicts the text above, this section holds. All of it applies only while both
switches are on.

- **AM-1** (changes AC-7) When the player is closed because of a playback error, and the
  next start plays the same video, the video continues at the position it had when the error
  occurred. "Closed because of an error" covers three cases: the player closes itself on the
  error; the video page stops the player in reaction to the error; and the user closes a
  player that has been failing since an error without having played on. This holds until
  another video is started or the app's process ends. A player closed by the user while it
  was playing or paused normally, and a start of any other video, stay starts.
- **AM-2** (extends AC-2) A start of the video that is playing keeps its position: a switch
  between the main, popup and background player also when the queue it is started with
  differs from the player's queue (for example a video page reopened from a list after a
  next video was queued), and a tap that starts the playing video again in the same player.
  What happens to the queue is as today.
- **AM-3** (extends AC-3 and AC-4) A reload that begins before the previous reload has
  finished loading keeps the position the video had.
- **AM-4** Not covered for now: a playing stream replaced in place after a failed or expired
  source, without a reload. It is taken up if a log shows a restart there.
