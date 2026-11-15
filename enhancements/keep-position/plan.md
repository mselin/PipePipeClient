# Keep position: plan

How `spec.md` (AC-1..AC-14) is built. Paths are relative to
`app/src/main/java/org/schabi/newpipe/` unless they start with `res/` (`app/src/main/res/`). Line
numbers are those of `dev` at `cd87fc7`. `KP` stands for
`org.schabi.newpipe.enhancements.keepposition.KeepPosition`; hooks spell it out in full so that no
import line is added. "Always-start" is the upstream switch, "keep" the new one.

## 1. How the code works today

- **Store.** A queue keeps one recovery position per item, keyed by the item's uuid
  (`player/playqueue/PlayQueue.java:63`, `:457-495`). Both are serialized with the queue and
  survive the copy every intent makes (`util/SerializedCache.java:70`,
  `util/NavigationHelper.java:88`, `:601`).
- **Capture.** `Player.setRecovery()` (`player/Player.java:474-487`) writes the ExoPlayer position
  of the queue's current item, if above 0; upstream calls it next to every reload in 1.2. Progress
  saves (`player/PlayerHistoryController.kt:70`) and a history resume
  (`player/PlayerStartController.kt:147`) write it too, and only applying it clears it. So a
  stored position never tells a reload from a start.
- **Apply.** A load is a new `MediaSourceManager` (`Player.java:500-508`). It blocks the player
  (`player/PlayerPlaybackStateController.kt:117-130`), builds the source (`Player.sourceOf`,
  `Player.java:1224-1232`, on an io thread, `player/mediaitem/StreamInfoResolver.kt:26`), then
  synchronizes (`player/PlayerTransportController.kt:216-282`): `:271-280` seeks to the stored
  position if `shouldSeek()`, else to the default position, 0:00.
- **Gate.** `Player.shouldSeek()` (`Player.java:1068-1070`) is "always-start is off". Callers:
  `Player.java:1227` (start position given to the source), `PlayerTransportController.kt:272`
  (the seek), `PlayerStartController.kt:114` (seek in place).

### 1.1 The spec's "Today" claims

| Claim | Verdict from the code |
|---|---|
| Always-start (Advanced, default off) starts at 0:00 | Holds: `res/xml/advanced_settings.xml:35-40`, the gate above. |
| Player switch restarts at 0:00 | Holds whenever the switch reloads (1.3). No reload, and the place is kept, when background/foreground only toggles tracks (`player/PlayerSourceController.kt:56-77`). |
| Quality change restarts at 0:00 | Holds: `player/PlayerMenuController.kt:449-452`. |
| Error recovery restarts at 0:00 | Holds for network-type errors (`player/PlayerErrorHandler.kt:125-151`, `:198-201`) and a decoder failure after a released surface (`:153-165`). Other decoder errors close the player (`:166-185`): nothing to keep. |
| A time in a description or comments is not honoured | Holds only for times that are links (1.4 b). **Refuted** for times written as text (1.4 a): they seek the running player directly, ungated. See Q5. |
| A video link with a start time is not honoured | Holds (1.4 c). |
| With always-start off all of these work | Holds: every path uses the gate. |

### 1.2 Every path that loads the playing video again

All are calls of `Player.reloadPlayQueueManager()`, or a service restart (1.3 row 5).

| Caller | Cause | Kind |
|---|---|---|
| `PlayerStartController.kt:163` | player type changed in `handleIntent` | reload; a start if `initPlayback` ran just before |
| `PlayerStartController.kt:208` | `initPlayback`: new ExoPlayer, new or re-opened queue | start |
| `PlayerSourceController.kt:43`, `:59` | video source switched off/on | reload |
| `PlayerMenuController.kt:452` | quality | reload |
| `player/PlayerTracksController.kt:150` | audio track | reload |
| `PlayerErrorHandler.kt:165`, `:200` | error recovery | reload |
| `PlayerErrorHandler.kt:237`, `Player.java:560` | live window lost, live resumed | reload of a live stream |

### 1.3 Player switches, end to end

| # | Switch | Path |
|---|---|---|
| 1 | Page buttons Background / Popup | `fragments/detail/VideoDetailFragment.kt:523-551` -> `NavigationHelper.java:162-186` -> running service, `handleIntent`. The copy equals the player's queue (`PlayQueue.java:599-617`), so branch `:101` or `:121` keeps the ExoPlayer; then `:161-163` reloads. |
| 2 | Thumbnail tap while background/popup plays | `VideoDetailFragment.kt:835-841` -> `:553-571` -> as 1. |
| 3 | Queue screen "Switch to main / popup" (offered only for the background player, `player/PlayQueueActivity.java:106-112`; "Switch to background" is never shown) | `PlayQueueActivity.java:140-151`; for main an activity intent (`NavigationHelper.java:585-607`, `MainActivity.java:857-870`, `NavigationHelper.java:427-432`) -> `VideoDetailFragment.kt:553-571` -> as 1. |
| 4 | Minimize to popup | `Player.java:1386-1388` -> as 1. |
| 5 | Popup's fullscreen button | `player/PlayerClickController.kt:123-130`: `setRecovery()`, then **stops the service** (`player/PlayerService.java:223-227`, `:205-221`; `PlayerHolder` unbinds, `player/helper/PlayerHolder.java:307-312`), then an activity intent with a queue copy. A new service and `Player` get it: `playQueue == null`, type VIDEO to VIDEO, not the history branch (`PlayerStartController.kt:140`: a position is stored), so `initPlayback` at `:155`. For the player this is identical to "play after the player was closed". |
| 6 | Minimize to background, coming back, popup screen off/on | `player/PlayerBroadcastReceiver.kt:44-51`, `:142-155` -> `Player.java:1380-1385` -> `PlayerSourceController.kt:25-80`: reloads only at `:43` / `:59`. |

### 1.4 Where a link's time enters

| | Kind | Path | Gated |
|---|---|---|---|
| a | Time written as text: description (`util/external_communication/TextLinkifier.java:195`), comments (`info_list/holder/CommentsMiniInfoItemHolder.java:287` -> `InternalUrlsHandler.java:101-103`) | `DetailPlayerEvents.seekTo` -> `PlayerBroadcastReceiver.kt:38-43` seeks the running player; without one nothing happens | no |
| b | Timestamp link: address ending in `&t=<digits>` (description, `TextLinkifier.java:244`) or `#timestamp=<digits>` (comments, `util/CommentTextOnTouchListener.java:55`) | `util/external_communication/InternalUrlsHandler.java:97-135` -> `playOnMain` `:201-222` -> `SinglePlayQueue(info, ms)` (`player/playqueue/SinglePlayQueue.java:23-26`, stores the position) -> main player page -> `handleIntent` | yes |
| c | Video link with a start time: any other link tapped in the app (`InternalUrlsHandler.java:136-151`) or sent by another app, both via `RouterActivity` | "Show info": `NavigationHelper.java:682-698`; the page loads the raw address (`fragments/detail/VideoDetailViewModel.kt:69-80`) and builds `SinglePlayQueue(info)` (`VideoDetailFragment.kt:454`, `:580`). A player choice: `RouterActivity.java:746`, `:796`, `:806-810`. Both get the time only in `SinglePlayQueue.java:18-20`, from `info.getStartPosition()`, which the extractor parses from the address | yes |

In `handleIntent`, b and c seek in place (`:101-120`) only if the playing queue holds exactly that
one video; otherwise `initPlayback` replaces the queue (`:152-158`); never the history branch.
Stored positions that are **not** timed starts: history resume, progress saves, the cast receiver
(`cast/CastReceiver.kt:215`), Bilibili round play (its extractor reports the round's elapsed time
as start position, without a link).

## 2. Mechanism

The feature never stores or applies a position. It lifts the always-start gate, for one item and
one load, when that item carries a **mark**. Position, seek and "Use fast inexact seek" stay
upstream's, so a marked load behaves as with always-start off (AC-5, AC-6, AC-14). State is one
process-wide tracker (UI, service and every `Player` share a process: no `android:process` in the
manifest). A mark names an item by uuid, which survives queue copies and service restarts.

- **Reload mark** (at most one): item uuid + owner. Set inside `Player.reloadPlayQueueManager()`
  (H1), so by every present and future reload, owned by that `Player`; and by the popup's
  fullscreen button (H6) with owner "any player", because that reload continues in a new service.
  Set only if all hold: the last synchronization, of any player, was this player on the queue's current item (the
  ExoPlayer is on it), the ExoPlayer position is above 0, the item is not live. `initPlayback`
  and the reload right after it always fail this (new ExoPlayer, position 0): a start never sets
  a mark. A failed attempt changes nothing.
- **Timed mark** (a set of up to 16 uuids, oldest dropped): set where a queue is built from a
  timestamp link (H7) or from a start time in a video's address (H8), not for live streams or
  round play. Nothing else sets it.
- **Consumption.** Every synchronization of any item in any player (H3) moves that item's marks
  into a grant that lives for that one call and is read at the seek decision (H4), and **always**
  discards the reload mark. The seek in place (H5) consumes a timed mark too.

**A start is never taken for a reload:** a reload mark exists only from the instant a reload of
the playing item U began until the next synchronization. If that is another item (next, previous,
pick, new video), the mark is gone, so returning to U later starts at 0:00. If the player is
closed first, a new player's load of U does not fit the owner. Marks are not derived from stored
positions, so history resume and earlier-played items never fit. Items preloaded during a reload
have another uuid. **A reload is never taken for a start:** the reload's own manager
synchronizes U next, unless the user starts something else first. **A timed start is the only
exempt start:** only H7/H8 set the mark, for the uuid of the queue built for that tap; its first
load consumes it, so a later start of the same item has none (AC-14). Accepted overlap: a start
of U itself while its own reload is in flight (sub-second, same player) continues; and Q6.

## 3. New files

In `app/src/main/java/org/schabi/newpipe/enhancements/keepposition/`; tests in the same package
under `app/src/test/java/`.

### 3.1 `KeepPositionTracker.kt`: plain Kotlin, no Android imports

```kotlin
interface KeepPositionSettings {
    val alwaysStartFromBeginning: Boolean   // upstream switch, default false
    val keepPosition: Boolean               // new switch, default true
}
enum class Mark { NONE, RELOAD, TIMED }

fun shouldSeek(alwaysStartFromBeginning: Boolean, keepPosition: Boolean, mark: Mark): Boolean
fun isTimedLinkStart(startPositionSeconds: Long, isRoundPlay: Boolean, isLive: Boolean): Boolean

class KeepPositionTracker(private val settings: KeepPositionSettings) {
    fun onTimedStart(itemUuid: String)
    fun onReload(player: Any, queueItemUuid: String?, positionMs: Long, isLive: Boolean)
    fun onSwitch(player: Any, queueItemUuid: String?, positionMs: Long, isLive: Boolean)
    fun shouldSeekInPlace(player: Any, linkItemUuid: String?, playingItemUuid: String?,
                          reloadFollows: Boolean): Boolean
    fun peekMark(player: Any, itemUuid: String): Mark
    fun shouldSeekOnLoad(player: Any, itemUuid: String): Boolean
    fun onSynchronize(player: Any, itemUuid: String)
    fun shouldSeekOnSynchronize(player: Any, itemUuid: String): Boolean
    companion object { const val MAX_TIMED = 16 }
}
```

`shouldSeek`, first match wins. Settings are read at decision time, never when a mark is set.

| # | Condition | Result | AC |
|---|---|---|---|
| 1 | `alwaysStartFromBeginning == false` | `true` | AC-9 |
| 2 | `mark == TIMED` | `true` | AC-12, AC-13, AC-14, the exception in AC-8 |
| 3 | `mark == RELOAD && keepPosition` | `true` | AC-2, AC-3, AC-4 |
| 4 | otherwise | `false` | AC-7, AC-8, AC-10 |

`isTimedLinkStart` = `startPositionSeconds > 0 && !isRoundPlay && !isLive`.

Tracker state: `timed` (insertion-ordered uuids, the 17th drops the oldest); `reload` = (uuid,
owner, timedFlag) or none, owner being a player or ANY; `synced` = (player, uuid) or none;
`grant` = (uuid, Mark) or none. `player` is an opaque token compared by identity and held weakly
(tests keep their `Any()` in a `val`). "`reload` fits (p, u)": its uuid is u and its owner is p
or ANY. All methods are `@Synchronized`.

| Method | Effect |
|---|---|
| `onTimedStart(u)` | add u to `timed`. |
| `onReload(p, u, pos, live)` | no change unless `u != null && pos > 0 && !live && synced == (p, u)`. Then `reload = (u, p, flag)`; flag = timedFlag of the previous `reload` if that fits (p, u), else false. |
| `onSwitch(p, u, pos, live)` | the same with owner ANY. |
| `shouldSeekInPlace(p, link, playing, reloadFollows)` | `t` = link was in `timed`; remove it. If `t && reloadFollows && playing != null`: `reload = (playing, p, true)`. Returns `shouldSeek(settings, TIMED if t else NONE)`. |
| `peekMark(p, u)` | no change. TIMED if u is in `timed`, or `reload` fits (p, u) with timedFlag; RELOAD if `reload` fits (p, u); else NONE. |
| `shouldSeekOnLoad(p, u)` | `shouldSeek(settings, peekMark(p, u))`. |
| `onSynchronize(p, u)` | `m = peekMark(p, u)`; remove u from `timed`; `grant = (u, m)` if m is not NONE, else none; `reload = none`; `synced = (p, u)`. |
| `shouldSeekOnSynchronize(p, u)` | m = the grant's mark if its uuid is u, else NONE; `grant = none`; returns `shouldSeek(settings, m)`. |

### 3.2 `KeepPosition.kt`: Android glue, not unit tested

`class PrefsKeepPositionSettings(context: Context) : KeepPositionSettings` reads
`always_start_from_beginning_key` (default false) and `enh_keep_position_key` (default true) from
the default shared preferences on every access. `object KeepPosition` holds a lazily created
`KeepPositionTracker(PrefsKeepPositionSettings(App.getApp()))` and one `@JvmStatic` function per
hook that only unwraps arguments:

| Function | Calls |
|---|---|
| `onReload(player: Player)` | `tracker.onReload(player, player.playQueue?.item?.uuid, player.currentPosition, isLive(player))` |
| `onSwitchToMainPlayer(player: Player)` | `tracker.onSwitch(...)` with the same arguments |
| `shouldSeekOnLoad(player: Player, item: PlayerMediaItem): Boolean` | `tracker.shouldSeekOnLoad(player, item.uuid)` |
| `onSynchronize(player: Player, item: PlayerMediaItem)` | `tracker.onSynchronize(player, item.uuid)` |
| `shouldSeekOnSynchronize(player: Player, item: PlayerMediaItem): Boolean` | `tracker.shouldSeekOnSynchronize(player, item.uuid)` |
| `shouldSeekInPlace(player: Player, linkQueue: PlayQueue, oldPlayerType: PlayerService.PlayerType): Boolean` | `tracker.shouldSeekInPlace(player, linkQueue.item?.uuid, player.playQueue?.item?.uuid, oldPlayerType != player.playerType)` |
| `onTimestampLink(queue: PlayQueue)` | nothing for an empty queue; else `tracker.onTimedStart(uuid)` if `isTimedLinkStart(queue.getRecoveryPosition(item) / 1000, false, live(item.streamType))`: the same rule function as the next row, so the live exception of AC-10 is one unit-tested rule for both kinds of timed start |
| `onLinkStartPosition(queue: PlayQueue, info: StreamInfo)` | `tracker.onTimedStart(uuid)` if `isTimedLinkStart(info.startPosition, info.isRoundPlayStream, live(info.streamType))` |

`isLive(player)` mirrors the private `Player.isCurrentStreamLive()` (`Player.java:563-570`):
`StreamTypeUtil.isLiveStream` of the queue item's `streamType`, or of `player.currentStreamInfo`'s.
Only public members of `Player` are used (`getPlayQueue`, `getCurrentPosition`,
`getCurrentStreamInfo`, `getPlayerType`).

## 4. Hook points

7 upstream files, 9 lines marked `ENH:keep-position`: 6 added, 3 changed in place. Each was
checked against the cited line.

| # | Where | Line |
|---|---|---|
| H1 | `player/Player.java`, `reloadPlayQueueManager()`, new first statement before `:501` | `KP.onReload(this); // ENH:keep-position` |
| H2 | `player/Player.java:1227`, `sourceOf`, replaces `final long initialPositionMs = shouldSeek()` | `final long initialPositionMs = KP.shouldSeekOnLoad(this, item) // ENH:keep-position` |
| H3 | `player/PlayerTransportController.kt`, new line after `:240` `player.setCurrentItem(item)` | `KP.onSynchronize(player, item) // ENH:keep-position` |
| H4 | `player/PlayerTransportController.kt:272`, replaces `&& player.shouldSeek()` | `&& KP.shouldSeekOnSynchronize(player, item) // ENH:keep-position` |
| H5 | `player/PlayerStartController.kt:114`, replaces `if (player.shouldSeek()) {` | `if (KP.shouldSeekInPlace(player, newQueue, oldPlayerType)) { // ENH:keep-position` |
| H6 | `player/PlayerClickController.kt`, new line after `:124` `player.setRecovery()`, before the service is stopped | `KP.onSwitchToMainPlayer(player) // ENH:keep-position` |
| H7 | `util/external_communication/InternalUrlsHandler.java`, `playOnMain`, new line after `:221` | `KP.onTimestampLink(playQueue); // ENH:keep-position` |
| H8 | `player/playqueue/SinglePlayQueue.java`, new line after `:19`, inside the `if` | `KP.onLinkStartPosition(this, info); // ENH:keep-position` |
| H9 | `res/xml/advanced_settings.xml`, new line after `:40` | see section 5 |

H2 and H4 are continuation lines; the trailing comment is legal there in Java and Kotlin. H3 is
needed besides H4 because `:272` is not reached when no position is stored, in the error branches
(`:243-258`) or on a gapless move to the next item (`:259-261` false), and marks must be
consumed regardless. All nine hooks are single lines. H5 passes `oldPlayerType` (`:34`)
because the reload at `:161-163` after a seek in place must keep the link's time even with keep
off (AC-12). After the hooks `Player.shouldSeek()` has no callers; it stays.

Left alone on purpose: the ten call sites of 1.2 (H1 is their one common point); the other
`setRecovery()` callers (`VideoDetailFragment.kt:526`, `:546`, `:839`,
`PlayQueueActivity.java:141-153`, `Player.java:1387`, `PlayerService.java:172`, `:214`,
`PlayerServiceForAuto.java:183`, `:231`), which end in `handleIntent` and H1 or stop the player;
the track toggle (`PlayerSourceController.kt:56-77`) and the `prepare()` of a stopped player
(`PlayerStartController.kt:109-113`, `:129-133`): no reload, ExoPlayer keeps its place; the
renewal of an expired source (`player/playback/MediaSourceManager.java:523-541`): the item is
unchanged, so nothing is decided (`PlayerTransportController.kt:237-239`); live reloads (AC-10,
H1 refuses them); text times (1.4 a, ungated already); `InternalUrlsHandler.playOnPopup`
(`:165-199`, no caller); `CastReceiver.kt:215` and round play (not timed starts).

## 5. New resources

`res/values/enhancements_keep_position.xml`, in `values/` only (other languages fall back, AC-11):

| Name | English text |
|---|---|
| `enh_keep_position_key` (`translatable="false"`) | `enh_keep_position_key` |
| `enh_keep_position_title` | `Keep position of the playing video` |
| `enh_keep_position_summary` | `The video continues where it was when it moves between players, changes quality or recovers from an error` |

Boolean preference `enh_keep_position_key`, default `true` in the XML and in the glue. H9 is one
line directly after the always-start element (`advanced_settings.xml:35-40`):

```xml
        <SwitchPreferenceCompat android:defaultValue="true" android:key="@string/enh_keep_position_key" android:title="@string/enh_keep_position_title" android:summary="@string/enh_keep_position_summary" app:singleLineTitle="false" app:iconSpaceReserved="false" /> <!-- ENH:keep-position -->
```

## 6. AC coverage

Unit tests are JUnit 4 against `KeepPositionTracker.kt`, with a hand-written
`KeepPositionSettings` (both on unless stated) and plain `Any()` players. `sync(p,U)` is
`onSynchronize`; "load U in p" is `shouldSeekOnLoad`, `onSynchronize`, `shouldSeekOnSynchronize`,
and its result is that of the two `shouldSeek*` calls. Manual steps: section 7.

| AC | Piece | Verified by |
|---|---|---|
| AC-1 | H9, section 5 | Unit: parse both XML files with `javax.xml` (path `src/main/res/...`, else `app/src/main/res/...`): the new element directly follows the always-start one, default `true`, title and summary texts as in section 5. Manual M1 (how it renders). |
| AC-2 | H1, H6, H3, H4 | Unit: `sync(p,U)`, `onReload(p,U,5000,false)`, load U in p -> true; a second load -> false. `sync(p1,U)`, `onSwitch(p1,U,5000,false)`, `onReload(p2,U,0,false)`, load U in p2 -> true. Manual M2 (the hooks, every direction). |
| AC-3, AC-4 | H1 (same call as AC-2) | Unit: as AC-2; reload and load twice in a row (error loop) -> true both times. Manual M3, M4. |
| AC-5 | upstream's position; guards of `onReload` | Unit: `pos <= 0`, `u == null` or not synchronized -> `peekMark` NONE. Manual M2 (the position, inexact seek). |
| AC-6 | nothing touched | Manual M2 (paused stays paused). |
| AC-7 | rule 4, consumption, owner, guards | Unit: no mark -> false. Reload mark for U, `sync(p,V)`, load U -> false. `peekMark(p,V)` during U's reload -> NONE. Mark of p1, load U in p2 -> false. Mark, `sync(p,U)` without the decision, `sync(p,V)`, load U -> false. Manual M5. |
| AC-8 | rules 2-4 | Unit: keep off: the AC-2 sequences -> false, the AC-12 sequences -> true. Manual M6. |
| AC-9 | rule 1 | Unit: always-start off: `shouldSeek` true for every mark and keep value; all three tracker decisions true without any mark. Manual M6. |
| AC-10 | live guards | Unit: `onReload` / `onSwitch` with `live = true` -> NONE; `isTimedLinkStart(90,false,true)` false (the rule both H7 and H8 go through). Manual M7 (that the glue passes the live flag). |
| AC-11 | section 5 | Unit: the three names exist in `values/`; only the key has `translatable="false"`. |
| AC-12 | H7, H5, H2-H4 | Unit, keep off: `onTimedStart(T)`, load T -> true. `shouldSeekInPlace(p,T,U,false)` true once, then false. `sync(p,U)`, `onTimedStart(T)`, `shouldSeekInPlace(p,T,U,true)`, `onReload(p,U,90000,false)`, load U -> true. Manual M8. |
| AC-13 | H8, `isTimedLinkStart` | Unit: (90,false,false) true; (0,false,false), (90,true,false) false; then as AC-12. Manual M8. |
| AC-14 | consumption; nothing else sets TIMED | Unit: a second load of T -> false. The 17th timed uuid drops the first. After a timed load of T, `onReload(p,T,90000,false)` gives RELOAD, not TIMED. Manual M8. |

## 7. Manual device script

Always-start on, keep on, a video of 10+ minutes at about 2:00, unless stated.
- **M1** Advanced settings: the new switch is directly below always-start, on, summary as in 5.
- **M2** Page Background, thumbnail (back to main), Popup, the popup's fullscreen button, queue
  screen "Switch to main" and "Switch to popup"; "Minimize on app switch" set to background, then
  to popup: leave the app and return. Each time it continues within a second of where it was.
  Repeat one step paused (stays paused) and one with "Use fast inexact seek" on.
- **M3** Pick another quality, and another audio track if offered: continues.
- **M4** Flight mode until the player buffers, then back: continues.
- **M5** Next, previous, an earlier item picked in the queue, the video after a switched one, a
  video from history with "Resume playback" on, close the player and press play: all at 0:00.
- **M6** Keep off: M2-M4 restart at 0:00, M8 still goes to its time. Always-start off, keep on
  and off: M2-M5 and M8 behave as upstream.
- **M7** A live stream: switch player and change quality: at the live edge, as before. Open a
  link to a live stream with a start time (`?t=90`), and tap an `&t=` link to one if a
  description has one: at the live edge too.
- **M8** A description with an `&t=` link: tap it for the playing video alone in its queue
  (jumps, no restart), inside a longer queue, and with nothing playing. Share
  `https://youtu.be/<id>?t=90` from a browser once per "open" action. Each goes to its time;
  then close the player and press play: 0:00.

## 8. Order of work

1. Section 5 resources, then H9. 2. `KeepPositionTracker.kt` until the unit tests are green.
3. `KeepPosition.kt`. 4. H1-H8, each exactly the line of section 4. 5.
`./gradlew :app:testDebugUnitTest -DskipFormatKtlint`, `enhancements/tools/check-layers.sh origin/dev`,
and `git grep "ENH:keep-position"` lists 9 lines. 6. Section 7 on a device.

## 9. Assumptions and risks

Planned with these assumptions, all confirmed by the spec:
- **Q4, A4** (AC-12, AC-14): a timestamp link for the playing video inside a longer queue replaces
  the queue and loads at the link's time, as today with always-start off; only a one-video queue
  is sought in place.
- **Q5, A5** (AC-12, AC-14): times written as text stay untouched.
- **Q6, A6** (AC-2, AC-7): after the popup's fullscreen button, if Autoplay does not start the main
  player, pressing play continues at the kept position unless another video was started first.
- **Q7, A7** (AC-10, AC-12, AC-13): a live stream gets no timed mark.

Risks and edge cases:
- Parity, not more: the position is the one upstream stores. Where upstream loses it with
  always-start off (unknown duration in `Player.setRecovery()`, `Player.java:481-486`), so does
  the feature.
- H2 runs on an io thread: the tracker must stay synchronized.
- `handleIntent` reloads the old queue at `:161-163` while a history lookup is pending
  (`:135-151`). That sets a mark for the old item; the new item's synchronization discards it.
- The page rebuilds a queue from the link's info when it has none (`VideoDetailFragment.kt:580`,
  also after a process restore): a new timed start, as today with always-start off.
- Marks live in memory: after process death everything starts at 0:00, the safe side.
- A live stream whose queue item is not typed live, reloaded before its info is known, gets a
  reload mark and seeks as with always-start off.
