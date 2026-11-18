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

Wiring on the JVM (added after the code review, finding F13): a test reads the seven upstream
files as text (path `src/main/...`, else `app/src/main/...`) and expects these counts of lines
containing `ENH:keep-position`: `player/Player.java` 2, `player/PlayerTransportController.kt` 2,
`player/PlayerStartController.kt` 1, `player/PlayerClickController.kt` 1,
`util/external_communication/InternalUrlsHandler.java` 1, `player/playqueue/SinglePlayQueue.java`
1, `res/xml/advanced_settings.xml` 1. Each code hook line must name
`org.schabi.newpipe.enhancements.keepposition.KeepPosition` in full and call the function section
4 gives for it (`onReload`, `shouldSeekOnLoad`, `onSynchronize`, `shouldSeekOnSynchronize`,
`shouldSeekInPlace`, `onSwitchToMainPlayer`, `onTimestampLink`, `onLinkStartPosition`). A hook
lost in a rebase then fails the unit tests, as for the other features.

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

## Amendment 2

Plans spec "Amendment 2, 2026-11-18" (AM-1 to AM-4). Line numbers in this section are those of
this branch at `c1f1923` (its base is `dev` at `ed88d77`, not `cd87fc7` as the head of this file
says; in the merged build `Player.java` is up to 8 lines further down). Sections 1 to 9
stay as written except where 10.1 says otherwise. New names: **A** = the address of an item,
`itemAddress(serviceId, url)`; **both on** = always-start on and keep on.

### 10.1 Statements above that are wrong, and what holds instead

| Where | Statement | What holds |
|---|---|---|
| 1, Capture | "upstream calls it next to every reload" | `Player.setRecovery()` (`Player.java:474-487`) writes nothing while the duration is unknown: `min(pos, TIME_UNSET)` is negative. `PlayerSourceController.kt:43-44` calls it after the reload. The synchronization clears the stored position (`PlayerTransportController.kt:278`). So a reload that begins after a synchronization and before the source is prepared has no stored position (AM-3). |
| 1.1, error row | "Other decoder errors close the player: nothing to keep" | A player closed by an error is now kept (AM-1). The closes are listed in 10.2; one of them was not known: the video page stops the service on **every** error except "behind live window". |
| 1.2 | "All are calls of `reloadPlayQueueManager()`, or a service restart" | Three more: `handleIntent` re-initialises the player when the queues differ (`PlayerStartController.kt:152-158`, AM-2); an error closes the player and the next start loads the same video (AM-1); the manager replaces the playing source in place (`playback/MediaSourceManager.java:454-467`, `:523-541`, AM-4, not covered). |
| 1.3 row 1 | "The copy equals the player's queue" | Only while the page holds the player's own queue (`VideoDetailFragment.kt:642-643`). A page reopened from a list holds a fresh `SinglePlayQueue` (`:137`, `:454`, `:580`). |
| 2 | "The feature never stores or applies a position" | It still never applies one. It now stores one in three places (10.4: G1, and the glue of H1 and H6). |
| 2 | "a start never sets a mark"; "If the player is closed first, a new player's load of U does not fit the owner" | True for a player closed by the user. After an error close the next start of the same video gets a mark (AM-1). |
| 4, "Left alone" | "the renewal of an expired source ... nothing is decided" | Nothing is decided, but that the position is kept there is not shown. AM-4. |
| 7, M4 and M5 | "Flight mode ...: continues"; "close the player and press play: 0:00" | M4 depends on the player type and on Autoplay (10.2, E-b); replaced by M9 to M12. M5 holds for a close by the user only. |
| 9, first risk | "Where upstream loses it ... so does the feature" | Overridden by AM-3 while both are on. |

### 10.2 What closes the player after a playback error (AM-1)

Every ExoPlayer error arrives in `PlayerErrorHandler.onPlayerError` (`PlayerErrorHandler.kt:86-196`),
on the main thread, in one uninterrupted call. Inside that call the player is closed in two ways:

| | Close | Path |
|---|---|---|
| E-a | `player.onPlaybackShutdown()` at `:98` (terminal SABR), `:140` (IO error with "Response code: 403"), `:180` (decoder init), `:185` (every unlisted code) | `Player.java:573-579` -> `PlayerService.stopService()` (`PlayerService.java:223-227`) -> `cleanup()` (`:205-221`) -> `Player.destroy()` -> `destroyPlayer()` (`Player.java:414-450`: `stop()`, `release()`, `playQueue.dispose()`). All synchronous: when `:98/:140/:180/:185` returns, the queue is disposed. |
| E-b | `player.listeners.onPlayerError(playerError, isCatchableException)` at `:195`. `isCatchableException` is true only for "behind live window" (`:102`), so this covers every other error, **also the ones just recovered** by `recoverFromNetworkError()` (`:198-201`) | `PlayerListeners.kt:82-84` -> `PlayerHolder.java:259-264` -> `VideoDetailFragment.onPlayerError` (`VideoDetailFragment.kt:702-708`) -> `hideMainPlayerOnLoadingNewStream()` (`:587-600`). For the main player type: if `isAutoplayEnabled()` (`:603-607`) it only stops ExoPlayer (`PlayerService.stopForImmediateReusing`, the pending reload then continues in the same player); otherwise `playerHolder.stopService()` (`:598`, `PlayerHolder.java:143-147`), which destroys the service a moment later through `onDestroy` -> `cleanup()`. |

E-b is the likely cause of the owner's finding: Autoplay is "only on Wi-Fi" by default
(`PlayerHelper.java:406-429`), so `isAutoplayEnabled()` turns false the moment the phone leaves
Wi-Fi; it is also false after the page followed the player to another item
(`VideoDetailFragment.kt:698`). The player type stays "main" while the app is in the background
with "minimize to background" (`Player.java:1385`), so this also closes a video that plays in
the background from its page. The recovery's own reload mark is owned by the dying player and the
next start is a new player: 0:00.

Closes by the user, which must stay starts: `PlayerBroadcastReceiver.kt:90` (notification),
`PopupWindowController.kt:249` (popup dragged away), `PlayerClickController.kt:144` (close
button), `PlayerMediaSession.java:146`, `VideoDetailFragment.kt:323` (leaving the app), `:773`
(page swiped away), `:598` reached from `:415` or `:511` (the page changes to another video),
`sleep/TimerStopReceiver.java:12`, `CarConnectionStateReceiver.java:48`, and
`MediaSourceManager.java:236-238` (the queue was emptied; nothing in the player calls
`PlayQueue.error()`, so only the user empties it). None of them runs inside `onPlayerError`.
That is the distinction the design uses: **a close is "by an error" when it happens, or is
requested, between the first and the last statement of `onPlayerError`.**

### 10.3 Changed and new signatures

`KeepPositionTracker.kt` (plain Kotlin, no Android imports). Unchanged: `KeepPositionSettings`,
`Mark`, `shouldSeek`, `isTimedLinkStart`, `onTimedStart`, `peekMark`, `shouldSeekOnLoad`,
`shouldSeekOnSynchronize`, `MAX_TIMED`. The new parameters have defaults, so every existing test
compiles and keeps its meaning.

```kotlin
const val NO_POSITION = -1L

/** "serviceId|url", or null when url is null or empty. The identity PlayQueue.equals() uses. */
fun itemAddress(serviceId: Int, url: String?): String?

/** Glue rule for H1 and H6: store the mark's position when the queue has none. */
fun shouldStore(markPositionMs: Long, storedPositionMs: Long): Boolean   // mark > 0 && stored <= 0

class KeepPositionTracker(private val settings: KeepPositionSettings) {
    // changed: return value, durationKnown
    fun onReload(player: Any, queueItemUuid: String?, positionMs: Long, isLive: Boolean,
                 durationKnown: Boolean = true): Long
    fun onSwitch(player: Any, queueItemUuid: String?, positionMs: Long, isLive: Boolean,
                 durationKnown: Boolean = true): Long
    // changed: itemAddress
    fun onSynchronize(player: Any, itemUuid: String, itemAddress: String? = null)
    // new
    fun onErrorBegin(player: Any, itemUuid: String?, itemAddress: String?, positionMs: Long,
                     isLive: Boolean, durationKnown: Boolean)
    fun onPageStopsPlayer()
    fun onErrorEnd(player: Any, playerClosed: Boolean)
    fun onStartIntent(player: Any, playingUuid: String?, playingAddress: String?,
                      positionMs: Long, isLive: Boolean, durationKnown: Boolean,
                      newUuid: String?, newAddress: String?,
                      sameQueue: Boolean, playerTypeChanged: Boolean): Long
    fun peekErrorPosition(itemAddress: String): Long   // probe, no change: the error mark's position for A, else NO_POSITION
}
```

`RECOVERY_UNSET` is `Long.MIN_VALUE` (`PlayQueue.java:50`), so "stored <= 0" covers "unset" and a
0 written by a progress save.

New state, next to `timed`, `reload`, `synced`, `grant` of 3.1:

- `carried` = (owner: a player or ANY, uuid, positionMs) or none: the position the last mark for
  that item carried. Unlike `reload` it survives the synchronization of its own item. "Fits
  (p, u)" as for `reload`.
- `inError` = (player, address, positionMs) or none: an error is being handled for that player.
- `errorMark` = (address, positionMs, source player) or none: the any-owner mark of AM-1. Players
  are held weakly, as before.

**Position of a mark**, `position(p, u, positionMs, durationKnown)`, first match:

| # | Condition | Position |
|---|---|---|
| 1 | `positionMs > 0` | `positionMs` |
| 2 | `!durationKnown` and `carried` fits (p, u) | `carried.positionMs` |
| 3 | otherwise | none |

Row 2 is the answer to "the player is still loading the previous reload": a player that reports
0 without a duration has not got its position back yet, and the position the previous mark
carried is the one the video had. With a known duration a 0 is a real 0 (no mark, as before).

**Methods** (all `@Synchronized`; "both on" is read from the settings inside the call):

| Method | Effect | Returns |
|---|---|---|
| `onReload(p, u, pos, live, dur)` | No change unless `u != null && !live && synced == (p, u)` and `position(p, u, pos, dur)` is some P. Then `reload = (u, p, flag)` (flag as in 3.1), `carried = (p, u, P)`. | P if a mark was set and both on, else `NO_POSITION` |
| `onSwitch(...)` | The same with owner ANY for `reload` and `carried`. | the same |
| `shouldSeekInPlace(...)` | As in 3.1; additionally `carried = none` when it returns true (the seek in place moved the position). | as in 3.1 |
| `onSynchronize(p, u, a)` | As in 3.1, then: `carried = (p, u, its position)` if it fits (p, u), else none. `errorMark` stays only if `a != null`, `errorMark.address == a` and `errorMark.source === p`; else none. `inError = none`. | |
| `onErrorBegin(p, u, a, pos, live, dur)` | `inError = none`. Then, if `u != null && a != null && !live && synced == (p, u)` and `position(p, u, pos, dur)` is some P: `inError = (p, a, P)`. | |
| `onPageStopsPlayer()` | If `inError` is set: `errorMark = (inError.address, inError.positionMs, inError.player)`. Else nothing. | |
| `onErrorEnd(p, closed)` | If `closed` and `inError.player === p`: `errorMark` as in the row above. Then always `inError = none`. | |
| `onStartIntent(...)` | table below | |
| `peekErrorPosition(a)` | no change | `errorMark.positionMs` if its address is a, else `NO_POSITION` |

`onStartIntent`, first match wins. P4 = `position(p, playingUuid, positionMs, durationKnown)`.

| # | Condition | Effect | Returns |
|---|---|---|---|
| 1 | `newUuid == null` or `newAddress == null` | none | `NO_POSITION` |
| 2 | `sameQueue` | none (the player keeps its queue; H1 and H5 handle it) | `NO_POSITION` |
| 3 | `newUuid` is in `timed` | `errorMark = none` (a timed start wins, AC-12, AC-13) | `NO_POSITION` |
| 4 | `playerTypeChanged && playingUuid != null && playingAddress == newAddress && !isLive && synced == (p, playingUuid)` and P4 is some P | `errorMark = none`; `reload = (newUuid, p, false)`; `carried = (p, newUuid, P)` | P if both on, else `NO_POSITION` |
| 5 | `errorMark != null && errorMark.address == newAddress` | P = its position; `errorMark = none`; `reload = (newUuid, ANY, false)`; `carried = (ANY, newUuid, P)` | P if both on, else `NO_POSITION` |
| 6 | otherwise (another video, or the same one without a claim) | `errorMark = none` | `NO_POSITION` |

Rows 4 and 5 set an ordinary reload mark for the **new** queue's item, so the existing H2, H3 and
H4 do the rest, and `shouldSeek` rule 3 keeps deciding (keep off: 0:00, AC-8; always-start off:
upstream, AC-9). Row 6 never touches `reload`: the mark of the popup's fullscreen button (H6)
must survive the intent that follows it. The error mark is dropped by row 3, 4, 5 or 6 (the next
start, whatever it plays) and by the synchronization of anything but its own video in its own
dying player: "used once, or another video was started".

**State machine for AM-3.** Both on, U playing in p at 5000 ms, `synced = (p, U)`. "Queue" is what
upstream has stored for U; the glue column is 10.5.

| Step | Call | `reload` | `carried` | Returns | Queue after the glue |
|---|---|---|---|---|---|
| a. reload begins | `onReload(p, U, 5000, false, true)` | (U, p) | (p, U, 5000) | 5000 | 5000 (upstream's own value, or stored by the glue if it had none) |
| b. second reload before a. was synchronized | `onReload(p, U, 5000 or 0, false, false)` | (U, p) | (p, U, 5000) | 5000 | 5000 (kept) |
| c. load, synchronize | `shouldSeekOnLoad` true, `onSynchronize(p, U, A)`, `shouldSeekOnSynchronize` true | none | (p, U, 5000) | | upstream seeks to 5000 and clears it: unset |
| d. reload before the source of c. is prepared | `onReload(p, U, 5000, false, false)`; or `(p, U, 0, false, false)` | (U, p) | (p, U, 5000) | 5000 | 5000, stored by the glue: this is the fix |
| e. load, synchronize | as c. | none | (p, U, 5000) | | seek to 5000 |
| f. playing again, later reload at 9000 | `onReload(p, U, 9000, false, true)` | (U, p) | (p, U, 9000) | 9000 | 9000 |
| g. another item | `onSynchronize(p, V, B)` | none | none | | |
| h. back at U, at 0 without duration | `onSynchronize(p, U, A)`, `onReload(p, U, 0, false, false)` | none | none | `NO_POSITION` | untouched |

Without the amendment step d. leaves the queue unset and e. seeks to the default position, 0:00.

### 10.4 Hooks

Three new lines in two files that had none, one new line in `PlayerStartController.kt`. The nine
existing hook lines keep their exact text; H1, H3 and H6 change only in the glue.

**G1, `player/PlayerStartController.kt`**, new line between `:99` and `:100`:

```kotlin
         * */
        org.schabi.newpipe.enhancements.keepposition.KeepPosition.onStartIntent(player, newQueue, oldPlayerType) // ENH:keep-position
        // seek to timestamp if stream is already playing
```

Why here: after the two enqueue returns (`:42-55`), which are not starts; after `:35` set the new
player type (`oldPlayerType` is `:34`); before the first read of the new queue's stored position
(`:105`, and `:140`), and before `initPlayback` (`:155`, `:203`) destroys the ExoPlayer whose
position row 4 reads. Nothing between `:77` and here changes either queue, so the glue's
`player.playQueue == newQueue` equals `samePlayQueue`. Call order after it, when the glue stored
a position in `newQueue` (rows 4 and 5, both on): branch `:101` is not entered (two one-item
queues with the same url are equal, so row 2 applied; with unequal service ids and the same url
it is entered, H5 refuses without a timed mark, and `:161-163` reloads through H1, which marks
again: same result); branch `:121` needs `samePlayQueue`; the history branch `:135` needs an
unset position and is skipped; `:155` runs `initPlayback(newQueue, ...)`, the call the history
branch would have made after its lookup. So the queue is replaced exactly as today, only the
lookup, whose single effect is a stored position, is left out. Then `initPlayback` and
`:161-163` call H1 twice with the new ExoPlayer: `synced` is not (p, new uuid), nothing changes.
H2 sees the mark, H3 takes it, H4 seeks to what G1 stored. G1 and H5 never both act: G1 does
nothing for equal queues (row 2) and nothing but dropping the error mark for a timed queue
(row 3).

**E1 and E2, `player/PlayerErrorHandler.kt`**, `onPlayerError`:

```kotlin
        Log.e(Player.TAG, "ExoPlayer - onPlayerError() called with:", error)
        org.schabi.newpipe.enhancements.keepposition.KeepPosition.onErrorBegin(player) // ENH:keep-position
```

new line after `:87`: the first statement that can read the position, before any branch stops,
reloads or destroys the player. ExoPlayer is idle after the error and still holds the position
(10.8).

```kotlin
        player.listeners.onPlayerError(playerError, isCatchableException)
        org.schabi.newpipe.enhancements.keepposition.KeepPosition.onErrorEnd(player) // ENH:keep-position
```

new last statement after `:195`: both closes of 10.2 have happened or been requested by then. The
function has no `return` between the two lines.

**F1, `fragments/detail/VideoDetailFragment.kt`**, `hideMainPlayerOnLoadingNewStream`, new line
between `:597` and `:598`:

```kotlin
        } else {
            org.schabi.newpipe.enhancements.keepposition.KeepPosition.onPageStopsPlayer() // ENH:keep-position
            playerHolder.stopService()
```

The exact statement that closes the player in E-b. Its two other callers (`:415`, `:511`) are
never inside `onPlayerError`, so `inError` is none there and the call does nothing. A hook in
`Player.onPlaybackShutdown()` alone, as the investigation proposed, would miss E-b, and a hook in
`Player.destroy()` could not tell an error from the user.

Order for E-a: E1 (`inError`) -> `:98/:140/:180/:185` destroys the player -> E2 sees the disposed
queue -> `errorMark`. Order for E-b: E1 -> `:199-200` (H1: reload mark, owned by p) -> `:195` ->
F1 -> `errorMark` -> `stopService` requested -> E2 (queue not disposed: `inError = none`) ->
later, in any order, the reload's synchronization (same video, same player: `errorMark` stays)
and the service's `onDestroy`. Then for both: the user starts the video -> new service ->
`handleIntent` -> G1 row 5.

### 10.5 `KeepPosition.kt` (glue)

| Function | Body |
|---|---|
| `onReload(player)` (H1) | `q = player.playQueue`, `item = q?.item`; `P = tracker.onReload(player, item?.uuid, player.currentPosition, isLive(player), player.duration > 0)`; `if (q != null && item != null && shouldStore(P, q.getRecoveryPosition(item))) q.setRecovery(q.index, P)` |
| `onSwitchToMainPlayer(player)` (H6) | the same with `tracker.onSwitch` |
| `onSynchronize(player, item)` (H3) | `tracker.onSynchronize(player, item.uuid, itemAddress(item.serviceId, item.url))` |
| `onErrorBegin(player: Player)` (E1) | `item = player.playQueue?.item`; `tracker.onErrorBegin(player, item?.uuid, item?.let { itemAddress(it.serviceId, it.url) }, player.currentPosition, isLive(player), player.duration > 0)` |
| `onErrorEnd(player: Player)` (E2) | `tracker.onErrorEnd(player, player.playQueue?.isDisposed == true)` |
| `onPageStopsPlayer()` (F1) | `tracker.onPageStopsPlayer()` |
| `onStartIntent(player: Player, newQueue: PlayQueue, oldPlayerType: PlayerService.PlayerType)` (G1) | `playing = player.playQueue?.item`, `new = newQueue.item`; `P = tracker.onStartIntent(player, playing?.uuid, playing address, player.currentPosition, isLive(player), player.duration > 0, new?.uuid, new address, player.playQueue == newQueue, oldPlayerType != player.playerType)`; `if (new != null && P > 0) newQueue.setRecovery(newQueue.index, P)` |

`player.duration` is `Player.getDuration()` (`Player.java:1755-1757`): 0 without an ExoPlayer,
`C.TIME_UNSET` (negative) while unknown. G1 overwrites whatever the new queue has stored: the
position of the mark is "the position it had"; H1 and H6 only fill a gap, so upstream's own value
stays where it has one (AC-5). `setRecovery` before `queue.init()` is safe (`PlayQueue.java:637-641`,
and upstream does it at `PlayerStartController.kt:147`). Still only public members of upstream
classes are used (`getDuration`, `PlayQueue.isDisposed`, `getIndex`, `setRecovery`,
`getRecoveryPosition`, `PlayerMediaItem.serviceId`, `url`).

### 10.6 Marked lines after the amendment

9 upstream files, 12 lines (was 7 and 9). Per file, in file order, the function each line calls:

| File | Count | Functions |
|---|---|---|
| `player/Player.java` | 2 | `onReload`, `shouldSeekOnLoad` (unchanged text) |
| `player/PlayerTransportController.kt` | 2 | `onSynchronize`, `shouldSeekOnSynchronize` (unchanged text) |
| `player/PlayerStartController.kt` | 2 | `onStartIntent`, `shouldSeekInPlace` |
| `player/PlayerClickController.kt` | 1 | `onSwitchToMainPlayer` (unchanged text) |
| `player/PlayerErrorHandler.kt` | 2 | `onErrorBegin`, `onErrorEnd` |
| `fragments/detail/VideoDetailFragment.kt` | 1 | `onPageStopsPlayer` |
| `util/external_communication/InternalUrlsHandler.java` | 1 | `onTimestampLink` (unchanged) |
| `player/playqueue/SinglePlayQueue.java` | 1 | `onLinkStartPosition` (unchanged) |
| `res/xml/advanced_settings.xml` | 1 | unchanged |

Exact text of the four new lines, without indentation (8 spaces for G1, E1, E2; 12 for F1):

```
org.schabi.newpipe.enhancements.keepposition.KeepPosition.onStartIntent(player, newQueue, oldPlayerType) // ENH:keep-position
org.schabi.newpipe.enhancements.keepposition.KeepPosition.onErrorBegin(player) // ENH:keep-position
org.schabi.newpipe.enhancements.keepposition.KeepPosition.onErrorEnd(player) // ENH:keep-position
org.schabi.newpipe.enhancements.keepposition.KeepPosition.onPageStopsPlayer() // ENH:keep-position
```

`git grep "ENH:keep-position" -- app/src/main` lists 12 lines.

### 10.7 Tests

**Existing tests.** All compile unchanged and stay green (defaults: `durationKnown = true`,
`itemAddress = null`). Their meaning narrows in one place: AC-7's "pressing play after the player
was closed begins at 0:00" (`KeepPositionTrackerStartTest.ac7_playAfterThePlayerWasClosedBeginsAtZero`,
and the class comment "a start never sets a mark") now means closed **by the user**; the KDoc
should say so and point to the AM-1 tests. `ac7_reloadMarkOfOnePlayerDoesNotFitAnotherPlayer`
stays true: the error mark is a separate mark, not a wider owner.

**New tracker tests** (both on unless stated; `sync(p, U, A)` is `onSynchronize`; "load" as in
section 6; p1, p2 are different players; U, N are uuids of the same video with address A; V has
address B).

AM-1:

| # | Sequence | Expected |
|---|---|---|
| 1 | `sync(p1,U,A)`, `onErrorBegin(p1,U,A,5000,false,true)`, `onErrorEnd(p1,true)` | `peekErrorPosition(A)` 5000 |
| 2 | then `onStartIntent(p2,null,null,0,false,false,N,A,false,false)` | returns 5000; `peekMark(p2,N)` RELOAD; `peekErrorPosition(A)` `NO_POSITION`; load N in p2 seeks; a second load of N does not |
| 3 | as 1, with `onErrorEnd(p1,false)` (error recovered in place) | `peekErrorPosition(A)` `NO_POSITION`; the start of 2 returns `NO_POSITION`, load N does not seek |
| 4 | `sync`, `onErrorBegin`, `onPageStopsPlayer()`, `onErrorEnd(p1,false)` (the page closed it) | 5000; then as 2 |
| 5 | as 4, then `onReload(p1,U,5000,false,true)` and load U in p1 with address A (the dying player's reload still synchronizes) | load seeks; `peekErrorPosition(A)` still 5000; the start of 2 still returns 5000 |
| 6 | `onPageStopsPlayer()` with no error being handled (the page changed to another video) | no error mark; a start of the same video returns `NO_POSITION` |
| 7 | as 1, then `onPageStopsPlayer()` (after `onErrorEnd`, a later user action) | unchanged by it: only one mark, from step 1 |
| 8 | as 1, then `onStartIntent(p2,...,V-uuid,B,false,false)` then the start of 2 | first `NO_POSITION` and the mark is gone; second `NO_POSITION` (another video was started) |
| 9 | as 1, then `sync(p2,V,B)` | mark gone |
| 10 | as 1, then `sync(p2,N,A)` (same video, another player, no intent) | mark gone |
| 11 | error of a live stream (`isLive = true`); error with `itemUuid` or `itemAddress` null; error in a player that never synchronized the item | no error mark |
| 12 | error at position 0 with a known duration | no error mark |
| 13 | `sync(p1,U,A)`, `onReload(p1,U,5000,false,true)`, load U, then `onErrorBegin(p1,U,A,0,false,false)`, `onErrorEnd(p1,true)` | 5000 (the carried position) |
| 14 | 2 with keep off; with always-start off | returns `NO_POSITION` both times; keep off: load N does not seek; always-start off: every decision seeks (AC-9) |
| 15 | as 1, `onTimedStart(N)`, then the start of 2 | `NO_POSITION`, error mark gone, load N is TIMED and seeks (the link's time, stored by upstream) |
| 16 | as 1, then `onStartIntent(p2,...,N,A,sameQueue = true,false)` | `NO_POSITION`, the mark stays |
| 17 | `onErrorEnd(p2,true)` while `inError` belongs to p1 | no error mark; a following `onPageStopsPlayer()` does nothing |
| 18 | two errors: A at 5000 closed, then B at 7000 closed | only B's mark |

AM-2 (U playing in p at 5000, `sync(p,U,A)` done):

| # | Sequence | Expected |
|---|---|---|
| 1 | `onStartIntent(p,U,A,5000,false,true,N,A,false,true)` | 5000; `peekMark(p,N)` RELOAD, `peekMark(p,U)` NONE |
| 2 | then `onReload(p,N,0,false,false)` twice (`initPlayback`, `:161-163`), load N in p | mark unchanged by the reloads; load seeks; second load does not |
| 3 | same call with `playerTypeChanged = false` | `NO_POSITION`, no mark (a tap on the playing video is a start, Q12) |
| 4 | with `sameQueue = true` | `NO_POSITION`, no mark, an existing reload mark for U untouched |
| 5 | new address B | `NO_POSITION`, no mark |
| 6 | `isLive = true`; position 0 with known duration; a player that is not synchronized on U | `NO_POSITION`, no mark |
| 7 | N is timed | `NO_POSITION`; load N is TIMED |
| 8 | keep off; always-start off | `NO_POSITION`; mark set; load N: keep off no seek, always-start off seeks |
| 9 | `sync(p1,U,A)`, `onSwitch(p1,U,5000,false)` (H6), then `onStartIntent(p2,null,null,0,false,false,U,A,false,true)` | `NO_POSITION` and `peekMark(p2,U)` still RELOAD (row 6 leaves `reload` alone) |
| 10 | `onErrorBegin(p,U,A,4000,false,true)`, `onErrorEnd(p,true)` (an error mark for A at 4000), then 1 | row 4 wins: 5000 from the player, error mark gone |

AM-3: the table "State machine for AM-3" in 10.3, one test per step sequence a-c, a-e, f, g-h;
plus the three rows of "Position of a mark"; `onSwitch` with the same rows; `onReload` returns
`NO_POSITION` with keep off and with always-start off while `peekMark` is RELOAD; `carried` of p1
gives nothing to p2 (`sync(p1,U)`, reload at 5000, load, then `sync(p2,U)`,
`onReload(p2,U,0,false,false)`: `NO_POSITION`, no mark); after `shouldSeekInPlace` returned true
a reload at 0 without duration sets no mark from an older `carried`.

Rule functions: `shouldStore(5000, Long.MIN_VALUE)` true, `(5000, 0)` true, `(5000, 4000)` false,
`(NO_POSITION, Long.MIN_VALUE)` false, `(0, 0)` false. `itemAddress(0, "u")` is `"0|u"`,
differs from `itemAddress(1, "u")`; null for a null or empty url.

**Wiring test.** The map of `KeepPositionWiringTest` becomes the table of 10.6 (two new files,
`onStartIntent` before `shouldSeekInPlace`). Add placement checks on the file text: in
`PlayerErrorHandler.kt` the `onErrorBegin` line is before the first line containing
`player.onPlaybackShutdown()` and the `onErrorEnd` line is after the line containing
`player.listeners.onPlayerError(`; in `VideoDetailFragment.kt` the next non-blank line after the
hook is `playerHolder.stopService()` and the previous one is `} else {`; in
`PlayerStartController.kt` the `onStartIntent` line is after the line containing
`val samePlayQueue` and before the first line containing `newQueue.getRecoveryPosition(`.

**Device script** (adds to section 7; both on, a video of 10+ minutes at about 2:00, a debug
build). M4 is replaced by M9 and M11.

- **M9 (AM-1, the page closes the player).** Autoplay "Only on Wi-Fi" (the default), mobile data
  available. Play on the video's page in the main player on Wi-Fi. Switch Wi-Fi off. The player
  disappears from the page (thumbnail) and an error notification may show. Press play on the
  page: continues within a few seconds of where it was. Repeat with the app in the background
  when Wi-Fi goes ("Minimize on app switch": background), then return and press play.
- **M10 (AM-1, the error shuts the player down).** Settings > Debug: show "Crash the player".
  While playing, page menu "Crash the player" > "Remote". The player closes. Press play:
  continues. Then: close the player with its close button or the notification and press play:
  0:00. Then: crash it again, play **another** video, come back to the first: 0:00.
- **M11 (AM-3).** Background player, flight mode on for 30 seconds (several failed recoveries in
  a row), flight mode off: continues where it was, not at 0:00. Also: pick another quality and,
  before the picture is back, pick a third one: continues.
- **M12 (AM-2).** Play U in the background player. Long-press another video in a list and
  enqueue it (or wait until autoplay queued a next video). Tap U in a list or in the history
  (confirm the queue question if asked): its page opens without playing. Tap the thumbnail:
  the main player continues where the background player was; the queue holds only U, as
  before this change. Repeat from the popup player. Then, with the main player on U and U
  tapped again in a list: 0:00 (a start; Q12).
- **M13 (switches).** M9 and M12 with keep off: 0:00. With always-start off: as upstream.
- **M14 (live).** M9 with a live stream: at the live edge.

### 10.8 Merge check

`git diff origin/dev <branch>` for every local branch, on the files of this amendment:

| File | Edit here | Other branches | Result |
|---|---|---|---|
| `player/PlayerStartController.kt` | one line added after `:99` | `feature/player-memory` replaces `:217` (`player.setupMediaSession()`) inside `initPlayback` | 117 lines apart: merges cleanly. In the merged build `:99-100` are the same text. |
| `player/PlayerErrorHandler.kt` | lines added after `:87` and `:195` | none (identical to `dev` on every branch and in `build/device-test`) | clean |
| `fragments/detail/VideoDetailFragment.kt` | one line added after `:597` | none | clean |
| `player/Player.java` | none (H1, H2 unchanged) | `crash-fixes`, `crash-fixes-more` add `:353-359`; `player-memory` adds `:378` | nothing to merge |
| `player/PlayerClickController.kt` | none (H6 unchanged) | `crash-fixes-more` changes `:184` | nothing to merge |
| `player/PlayerTransportController.kt`, `player/PlayerProgressController.kt`, `player/playqueue/PlayQueue.java` | none | `player-memory`, `crash-fixes-more` (progress), `queue-guard` (`PlayQueue.java:326`) | nothing to merge |

No conflict is expected. The amendment deliberately adds nothing to `Player.java`.

### 10.9 Risks, and what was checked

ExoPlayer 2.18.7, read with `javap -c` in the cached `exoplayer-core-2.18.7` runtime jar:

| Behaviour relied on | Status |
|---|---|
| `getCurrentPosition()` inside `onPlayerError` still returns the position: the internal player stops with "reset position" false, and `stop()` / `stopInternal(false, ...)` copy the playback info with only the state set to idle | checked in `ExoPlayerImpl.stopInternal`, `getCurrentPositionUsInternal`, `ExoPlayerImplInternal.stopInternal` -> `resetInternal(.., false, ..)` |
| After `stop()` (the block of a reload) the position and the timeline, so the duration, are kept | checked, same methods |
| `onPlayerError` is delivered before `onPlaybackStateChanged(IDLE)`, in one batch | checked in `ExoPlayerImpl.updatePlaybackInfo` (event 10 is queued before event 4). Not relied on: E1 only reads. |
| `setMediaSource(source, false)` keeps window index and position as a masked position; `seekTo` is masked at once | checked in `ExoPlayerImpl.setMediaSourcesInternal` (reads `getCurrentPosition()`). That the app's playlist source always offers that window index is **assumed**; if not, ExoPlayer falls back to 0 and "Position of a mark" row 2 takes over. |
| `getDuration()` is `TIME_UNSET` until the new source is prepared | assumed (standard behaviour, not traced through the app's sources) |
| A released player: `release()` keeps the playback info, `getCurrentPosition()` does not throw | read in `ExoPlayerImpl.release`; **not relied on**: nothing reads a released player (E2 reads only `PlayQueue.isDisposed`) |

App behaviour, read in the code and not run: the page's `onPlayerError` is called synchronously
from `:195`; `PlayerService.stopService()` destroys synchronously; `context.stopService()` does
not.

Risks:

- **Which cause the owner hit is not known.** E-b fits "network change" and "background" best.
  A log of one reproduction would settle it: `ExoPlayer - onPlayerError()` followed by
  `destroy() called` without `onPlaybackShutdown() called` is E-b; with it, E-a;
  `Playback - Rewinding to correct index` with no `Setting recovery` before it is AM-3.
- **A player stuck in a recovery loop that the user closes** (network gone for good, or the
  cached stream addresses refused on the new network with a status the handler retries) is a
  close by the user: the next start is 0:00. AM-3 keeps the position inside the loop for as
  long as the player lives. See Q13.
- **AM-4 is not covered.** A log would show `MediaSource - Reloading currently playing` or
  `MediaSource - Loaded=[...]` / `Updating index=[n]` for the playing index with no
  `Playback - onPlaybackBlock() called` before it and no `Playback - Rewinding to correct index`
  after it, and the position back at 0. It needs an edit inside `MediaSourceManager`.
- The autoplay branch of E-b (`stopForImmediateReusing`) keeps the player alive; the reload
  mark covers it. If the user closes that player before its reload synchronized, the next
  start is 0:00.
- `carried` can be stale for a video that never reports a duration and sits at 0: a reload
  there would go back to the last mark's position. Live streams are refused, so this needs a
  non-live source without duration.
- A seek the user makes while a reload is loading is kept (the masked position is above 0 and
  wins over `carried`).
- An exception between E1 and E2 leaves `inError` set until the next synchronization or error;
  a page change in that window would set an error mark for that video.
- G1 skips the history lookup when it stored a position; with "Resume playback" on, the
  history's own position is not used for that one start.
- E1, E2 and F1 run on the main thread; G1 too; the tracker stays synchronized for H2.
- The error mark lives in memory: after process death the next start is 0:00, as the spec says.

### 10.10 Order of work

1. Tests: 10.7 (they fail until the code exists). 2. `KeepPositionTracker.kt`: 10.3.
3. `KeepPosition.kt`: 10.5. 4. G1, E1, E2, F1: exactly the lines of 10.4 and 10.6. 5. Unit
tests, `check-layers.sh origin/dev`, `git grep "ENH:keep-position" -- app/src/main` lists 12
lines. 6. M9 to M14 on a device.

AC coverage added: AM-1 = E1, E2, F1, G1 row 5, H2 to H4 (M9, M10); AM-2 = G1 row 4, H2 to H4
(M12); AM-3 = glue of H1 and H6, `carried` (M11); AM-4 = none.
