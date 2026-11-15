# Plan: crash and stuck-state fixes

Spec: `spec.md` (AC-1..AC-7). Paths are relative to `app/src/main/java/org/schabi/newpipe/`
unless they start with `us/` (then `app/src/main/java/us/`) or `app/`. Line numbers are those of
`origin/dev` `cd87fc7` (this branch does not change `app/src/main` yet).
Open questions this plan depends on: Q2, Q3, Q4 in `questions.md`; each names the assumption used.

## 0. Shape of the change

- Marked direct edits in upstream code (`PROCESS.md`, defect-fix exception). **Every added or
  changed line ends with `// ENH:crash-fixes`, including lines that hold only a brace; no blank
  line is added** (the check counts an added blank line as unmarked). Changed lines are replaced
  at least one for one, so no file loses more lines than it gains.
- New classes are referenced by their full name, without import lines, as the other features
  do (fewer hunks to rebase). A marked edit that gets too long may be wrapped over more marked
  lines; the line counts below are therefore "about".
- New files, package `org.schabi.newpipe.enhancements.crashfixes` (one small file per step, so
  that each step can be reverted alone):

| File | Content | Android imports |
|---|---|---|
| `ErrorMessages.kt` | `object ErrorMessages { @JvmStatic fun contains(error: Throwable?, text: String): Boolean }`: `error?.message?.contains(text) == true` | none |
| `SearchResume.kt` | `object SearchResume { enum class Action { NONE, RESTART, LOAD_MORE }; @JvmStatic fun decide(isLoading: Boolean, searchRunning: Boolean, hasItems: Boolean, hasNextPage: Boolean, queryEmpty: Boolean): Action }` (rules in C2) | none |
| `LatePage.kt` | `object LatePage { @JvmStatic fun usable(fragment: Fragment): Boolean; @JvmStatic fun <T> ifUsable(fragment: Fragment, action: java.util.function.Consumer<T>): Consumer<T> }` | glue |
| `LateDialog.kt` | `object LateDialog { @JvmStatic fun canShow(fm: FragmentManager?): Boolean; @JvmStatic fun <T> ifCanShow(fm: FragmentManager?, action: Consumer<T>): Consumer<T> }` | glue |
| `LateDelivery.kt` | `object LateDelivery { @JvmStatic fun <T> deliver(callback: Consumer<T>, value: T) }` | glue |

- No resources, no settings, no build file changes.

## C1 Long-press action after the page was left (AC-1): confirmed

**Failure path.** `util/SparseItemUtil.java:111-131` subscribes without keeping the disposable and
calls the callback on the main thread whenever the details arrive (`:127`). An exception thrown
by the callback is caught by RxJava and handed to `App.java:276-314`; `IllegalStateException` and
`NullPointerException` are "critical" (`:326-331`) and rethrown as uncaught: the app dies.
Two ways to get there, checked per action in `info_list/dialog/StreamDialogDefaultEntry.java`:

| Action | Page closed with Back (fragment destroyed) | App sent to background (state saved) |
|---|---|---|
| Open channel `:63-64` | `NavigationHelper.java:469` `requireActivity()` throws | `commit()` (`NavigationHelper.java:458-461`) throws |
| Enqueue `:72-73` | `fragment.getContext()` is null, `Toast.makeText(null, ..)` (`NavigationHelper.java:203`) NPE | no crash from the page; see 1c |
| Enqueue next, background, popup `:80-96` | no crash: the context is captured at click time | see 1c |
| Add to playlist `:112-122` | `getParentFragmentManager()` throws (`:117`); the wait is a database read (`local/dialog/PlaylistDialog.java:145-152`), not the video's details | `dialog.show` throws |
| Download `:129-135` | `requireContext()` `:133` throws | `show` on the child fragment manager `:134` throws |

Refinement of the finding: opening another page on top (fragment on the back stack) does not
crash today, the fragment keeps its host; it opens the channel or adds the dialog out of sight.
`SUBSCRIBE_TO_CHANNEL` (`:186-215`) uses the application context and is safe.

**Fix 1a** (`StreamDialogDefaultEntry.java`, about 13 marked lines, 9 replaced).
`LatePage.usable(fragment)` is the test upstream already applies at click time
(`info_list/dialog/InfoItemDialog.java:77-79`) plus the saved-state check:
`fragment.view != null && !fragment.isStateSaved && view lifecycle is at least STARTED`.
`ifUsable` returns a consumer that runs `action` only if `usable` is true at delivery time.
- `:64` callback becomes `LatePage.ifUsable(fragment, url -> openChannelFragment(fragment, item, url))`.
- `:71-74` Enqueue becomes the block form of its neighbours (`:80-84`):
  `final Context context = fragment.requireContext();` at click time, then
  `enqueueOnPlayer(context, singlePlayQueue)`. Not dropped when the page is left (spec).
- `:116` `dialog -> dialog.show(` becomes `LatePage.ifUsable(fragment, dialog -> dialog.show(`,
  `:121` `)` becomes `))`.
- `:131` `info -> {` becomes `LatePage.ifUsable(fragment, info -> {`, `:135` `})` becomes `}))`.
- Enqueue next, background, popup: unchanged, already carried out without the page.
- Side effect wanted by the spec: with another page opened on top, Open channel / Download /
  Add to playlist are now dropped instead of acting out of sight.

**Fix 1b, same defect elsewhere** (`QueueItemMenuUtil.java`, about 5 marked lines; Q2). The play
queue's item menu shows dialogs on a fragment manager captured earlier: Download `:83-87`
(`show` at `:86` throws when the state is saved or the activity is gone) and Add to playlist
`:61-68`. `:86` is wrapped in `if (LateDialog.canShow(fragmentManager)) { .. }`; `:64`/`:67` get
`LateDialog.ifCanShow(fragmentManager, dialog -> dialog.show(` .. `))`.
`canShow` = `fm != null && !fm.isDestroyed && !fm.isStateSaved`. Open channel there (`:72-76`)
starts an activity by intent and is safe.

**Fix 1c, the player refused in the background** (`SparseItemUtil.java:127`, 1 marked line; Q2).
`targetSdk` is 36 (`app/build.gradle:26`). From Android 12 `startForegroundService`
(`NavigationHelper.java:174,185,218`) may throw `ForegroundServiceStartNotAllowedException`, an
`IllegalStateException`, when the details arrive after the app has been in the background for
more than a few seconds and no player runs. This is derived from the platform rule, not seen on
a device. `:127` `callback.accept(result);` becomes `LateDelivery.deliver(callback, result);`,
which catches exactly that exception on API 31+ (logs, drops the action) and rethrows everything
else. **Rule:** this step is committed only if device step D1c reproduces the crash on the
build without it; otherwise it is left out and the pull request says so.

**Other callers.** `feature/queue-guard` reuses the same enum entries (FINDINGS F7) and is fixed
by 1a once both are merged. No other caller of the three `SparseItemUtil` methods exists.

**Regression risk.** An action is dropped although the page is usable: only if `usable` is wrong
for a visible page; it is the predicate the dialog itself uses to accept the click. The wrapper
changes nothing on the synchronous path (`SparseItemUtil.java:58,87`), where the page is in front.

## C2 Search stuck after pause (AC-2): confirmed

**Failure path.** `fragments/list/search/SearchFragment.java:360-362` disposes the running request
in `onPause`. `isLoading` is reset only in `doOnEvent` (`:1056`, `:1066`, `:1087`, `:1099`), which
does not run on dispose, so it stays true. `onResume` (`:371-395`) starts nothing: the search is
started only from `onViewCreated` (`:329`) and from user input. Result after any pause (Home,
another activity): the spinner stays, and every load-more path returns early on `isLoading`
(`fragments/list/BaseListFragment.java:127,190,632,668`); `onBackPressed` (`:884`) is affected too.
`wasLoading` is written (`:279`, `fragments/BaseStateFragment.java:59`) and never read here.

**Fix** (`SearchFragment.java`, 9 marked lines inserted after `super.onResume();` `:375`, before
anything is added to `disposables`):
```
final SearchResume.Action a = SearchResume.decide(isLoading.get(),
        searchDisposable != null && !searchDisposable.isDisposed(),
        !infoListAdapter.getItemsList().isEmpty(), Page.isValid(nextPage),
        TextUtils.isEmpty(searchString));
if (a == LOAD_MORE) { loadMoreItems(); } else if (a == RESTART) { startLoading(false); }
```
`decide`: `NONE` unless `isLoading && !searchRunning`; then `LOAD_MORE` if `hasItems &&
hasNextPage`; else `RESTART` if the query is not empty; else `NONE`.
- Why `isLoading` and not `wasLoading` as in `fragments/list/BaseListInfoFragment.java:91-101`:
  `getInstance` sets `wasLoading` (`:210`) before the first `onResume`, when `onViewCreated` has
  already started the search (must not be restarted: `searchRunning`), or has started a link
  lookup instead (`:997-1010`), which a restart would cancel through `disposables.clear()`
  (`:1039`) and replace by a text search. "Flag set, nothing running" is exactly the stuck state.
- `startLoading(false)`, not `search()`: `search` would write the history entry again and close
  the suggestion panel. `searchString` is the query of the interrupted search (`:1017`).
  A duplicate result cannot be added: both result handlers add only to an empty list.

**Same defect elsewhere.** None: the other list pages use `BaseListInfoFragment`.
**Regression risk.** A search restarted that should not be: excluded by the two conditions
above; the unit tests pin the first-`onResume` and link-lookup cases.

## C3 Channel page blank after a background result (AC-3): confirmed

**Failure path.** `fragments/list/channel/ChannelFragment.java` has no `onPause`; the request
(`:413-422`) keeps running in the background. Its result calls `handleResult` -> `updateTabs`
(`:317-364`). After `onStop` the child fragment manager's state is saved, so
`fragments/detail/TabAdapter.java:45-48`, `:65-68` and `:147` return silently: no tab is added,
`:357` hides the tab bar, the spinner is already hidden. Nothing runs `updateTabs` again on return
(`doInitialLoadLogic` runs only from `onViewCreated`): an empty page.

**Fix** (`ChannelFragment.java`, 9 marked lines, a new method directly after
`doInitialLoadLogic`, i.e. after `:398`; no blank line before it):
```
@Override
public void onResume() {
    super.onResume();
    // the tabs were dropped by TabAdapter if the result arrived after onStop
    if (currentInfo != null && tabAdapter != null && tabAdapter.getCount() == 0
            && !isContentUnsupported()) {
        updateTabs();
    }
}
```
A supported channel always has the Videos tab (`:325-326`), so "info present, no tab" means
dropped. In `onResume` the child fragment manager is no longer saved. `updateSearchButton` and
`monitorSubscription` already ran in `handleResult` and do not depend on the saved state.
`TabAdapter` is not changed (also used by the video page).

**Same defect elsewhere, not included.** A blocked channel whose result arrives in the
background calls `popBackStack()` (`:444`) with saved state, which throws; reaching a blocked
channel's page needs a direct link, and the spec does not cover it. Listed in section "Risks".
**Regression risk.** `updateTabs` twice: excluded by `getCount() == 0`.

## C4 Live stream after an audio-focus loss (AC-4): confirmed, wording corrected (Q4)

**Failure path.** `player/helper/AudioReactor.java:119` pauses the raw ExoPlayer; the state
becomes PAUSED and `player/PlayerPlaybackStateController.kt:170` calls `enterLiveIdle`
(`player/Player.java:528-548`): the source is released, the player is stopped. On focus gain
`AudioReactor.java:113` calls the raw `play()`: `playWhenReady` becomes true on a stopped player
without a source, nothing plays. The next tap goes to `player/PlayerTransportController.kt:77-81`,
sees `playWhenReady` and pauses; only the second tap rebuilds the source (`:36-41`). With the
setting off nothing sets `playWhenReady`, and one tap already resumes today.

**Fix 4a** (`AudioReactor.java` 7 marked lines, `Player.java` 5 marked lines).
- `AudioReactor`: field `private java.util.function.BooleanSupplier resumeOverride = () -> false;`
  after `:36`; setter `setResumeOverride(..)` (3 lines) after the constructor (`:50`); before
  `:113`: `if (resumeOverride.getAsBoolean()) { return; }` (3 lines). `:113` itself stays.
- `Player.java`, after `:352`:
  `audioReactor.setResumeOverride(() -> { final boolean idle = liveIdle; if (idle) { play(); } return idle; });`
- Only the live-idle case is rerouted to `Player.play()`. Everything else keeps the raw call on
  purpose: `Player.play()` restarts a completed video (`PlayerTransportController.kt:44-53`), and
  focus gain also arrives after a notification sound while a finished video is shown.

**Fix 4b, same bypass** (`player/PlayerBroadcastReceiver.kt:41`, 1 changed line; Q4):
`if (player.isLiveIdle()) player.play() else player.simpleExoPlayer.play()`. Reached when a
timestamp link is tapped while a live stream is idle and the flag left by the last seek-bar drag
(`player/PlayerProgressController.kt:303`) is true. Narrow, same stuck state.
`PlayerProgressController.kt:281` is not affected (its flag is read fresh; idle means false).

**Regression risk.** `Player.play()` requests audio focus again inside the focus callback; the
request is already held and granted, no new callback follows. Checked on the device (D4).

## C5 Download error without a message (AC-5): confirmed

**Failure path.** `us/shandian/giga/get/DownloadMission.java:373` and `:376` (the finding says
372-373) call `err.getMessage().contains(..)` for every `IOException` that reaches
`notifyError(int, Exception)`: from the download threads through `:355`, from post-processing
through `:792` and `us/shandian/giga/postprocessing/Postprocessing.java:170`. Exceptions such as
`EOFException` or a closed channel have no message: NPE on a worker thread, the app dies.
`us/shandian/giga/get/DownloadInitializer.java:187` does the same in its catch block.

**Fix** (3 changed lines): the three conditions become
`ErrorMessages.contains(err, "Permission denied")`, `ErrorMessages.contains(err, "ENOSPC")`
(`DownloadMission.java:373,376`) and `e instanceof IOException && ErrorMessages.contains(e,
"Permission denied")` (`DownloadInitializer.java:187`). Without a message the code falls through
to the existing branches: the mission fails with its error code and
`us/shandian/giga/ui/adapter/MissionAdapter.java:554` already handles a missing message.
**Same defect elsewhere.** None (searched `getMessage().` under `us/` and `org/`).
**Regression risk.** None for errors with a message: same result.

## C6 Local playlist without a thumbnail (AC-6): confirmed for the crash; blocking read not changed (Q3)

**Failure path.** `local/playlist/LocalPlaylistManager.java:145-147` returns the playlist's
thumbnail, which is null when the playlist was created from a video without one (`:49`) or after
`updateThumbnailUrl` stored one (`local/playlist/LocalPlaylistFragment.java:1093-1094`).
`LocalPlaylistFragment.java:1109-1110` calls `.equals` on it: NPE on delete (single delete from
the swipe dialog `:1123` and the long-press menu `:1364`). An item without a thumbnail alone
does not crash (`equals(null)` is false).

**Fix** (8 changed lines): `Objects.equals(playlistManager.getPlaylistThumbnail(playlistId),
<item thumbnail>)` at `:1109-1110`, and the same at `:811-812` (Remove duplicates; its subscriber
`:818-840` has no error handler, so the NPE is fatal there too), `:882-883` and `:902-903`
(Remove watched; today an error panel). This is what the multi-delete already does (`:1563`);
`java.util.*` is imported (`:72`).
**Not included.** The read blocks the calling thread (`blockingFirst`, main thread at `:1109` and
`:1561`). Making it asynchronous reorders it against `saveChanges()` and is not asked for by AC-6.
**Regression risk.** Playlist and item both without thumbnail now count as "the thumbnail was
removed": the playlist takes the first remaining item's thumbnail, as multi-delete does.

## Verification

**JVM unit tests** (`app/src/test/java/org/schabi/newpipe/enhancements/crashfixes/`, JUnit 4):

| Test class | Cases | AC |
|---|---|---|
| `SearchResumeTest` | `ac2_interruptedSearch_restarts` (loading, not running, no items); `ac2_interruptedLoadMore_loadsMore` (items and next page); `ac2_runningSearch_isLeftAlone` (first `onResume`); `ac2_notLoading_nothing` (link lookup, finished search); `ac2_emptyQuery_nothing` | AC-2 |
| `ErrorMessagesTest` | `ac5_noMessage_isFalse` (`IOException()`, `EOFException()`: the old crash input); `ac5_nullError_isFalse`; `ac5_messageContainsText_isTrue`; `ac5_otherMessage_isFalse` | AC-5 |

Nothing else is honestly testable on the JVM: C1, C3, C4 and C6 are single guards inside
fragments, the player and ExoPlayer, which do not run without Robolectric; `Objects.equals`
needs no test of ours. The unit tests do not prove that the fragment or the download engine
calls the helper; the device steps do. C5 has no reliable device provocation (it needs a
connection that fails with a message-less exception), so AC-5 rests on `ErrorMessagesTest`, the
three one-line call sites, and a regression step.

**Instrumented test** (by hand, not in CI; existing dependencies):
`ac6_deleteFromPlaylistWithoutThumbnail`: insert a playlist with thumbnail null and two streams
through the DAOs, host `LocalPlaylistFragment.getInstance(id, name)` in `MainActivity`, call the
private `deleteItem` by reflection on the main thread; expect no exception and one item left.
Fails today with the NPE.

**Device script** (debug build). Slow network for D1-D3: Developer options > Network download
rate limit (Android 13+) at the lowest value, or a throttled Wi-Fi. Each step is first run on
the build without the fix and must show the old failure.

- **D1a** Channel videos list, long-press > Download, then at once (1) Back, (2) Home. Old: crash
  dialog when the details arrive. New: nothing happens, no crash. Repeat with Open channel and
  Enqueue on a feed item loaded through the fast feed mode (items without duration), and with
  Add to playlist + Home. Then: long-press > Enqueue, Back at once: old crash, new "Enqueued".
  Control: the same actions while staying on the page behave as before.
- **D1b** Player queue, long-press an item > Download, Home at once. Old: crash. New: none.
- **D1c** Android 12+, nothing playing: long-press > Start playing in background on a fast-feed
  item, Home, wait until the details have arrived (over 10 s with the limit). Decides step 1c.
- **D2** Search a term, Home while the spinner shows, wait 10 s, return. Old: spinner forever.
  New: results appear. Then scroll to the end, Home while the footer spinner shows, return:
  more results load, and scrolling loads further pages. Control: paste a video link into
  search: it still opens the video. Rotate during a search: one result list, no duplicates.
- **D3** Open a channel, Home before it has loaded, wait 10 s, return. Old: empty page, no tabs.
  New: tabs and videos. Control: a channel that loaded in the foreground, Home and back: the
  selected tab and scroll position are unchanged (no rebuild).
- **D4** "Resume on focus gain" on: play a live stream, let a timer alarm ring and dismiss it
  (or take a call). Old: silent, first tap on play does nothing. New: the stream resumes at the
  live edge. Setting off: after the alarm one tap resumes (regression check, works today).
  Controls: a normal video still resumes after the alarm; a finished video does not restart
  after a notification sound. 4b: live stream paused, tap a timestamp in the description.
- **D5** Regression only: a normal download completes; a download into a folder whose
  permission was revoked still reports the permission error.
- **D6** Create a playlist from a video, export the database (Settings > Backup), set that
  playlist's `thumbnail_url` to NULL with sqlite on a PC, import. Swipe-delete an item and
  confirm. Old: crash. New: item removed. Same for Remove duplicates and Remove watched.

## AC coverage

| AC | Fix | Unit test | Device |
|---|---|---|---|
| AC-1 | 1a (five actions), 1b (queue menu), 1c (conditional) | none | D1a-c |
| AC-2 | C2 | `SearchResumeTest` | D2 |
| AC-3 | C3 | none | D3 |
| AC-4 | 4a, 4b | none | D4 |
| AC-5 | C5 | `ErrorMessagesTest` | D5 (regression) |
| AC-6 | C6 | none; instrumented `ac6_...` | D6 |
| AC-7 | no resources or settings; every guard is false or a no-op on the paths that work today | pinned "nothing" cases | controls in D1-D4 |

## Order of work

After the test commit, one `code(crash-fixes):` commit per step; each builds alone and can be
reverted alone (its helper file is added in the same commit).

1. C5: `ErrorMessages.kt`, `DownloadMission.java`, `DownloadInitializer.java`.
2. C6: `LocalPlaylistFragment.java`.
3. C2: `SearchResume.kt`, `SearchFragment.java`.
4. C3: `ChannelFragment.java`.
5. 4a: `AudioReactor.java`, `Player.java`. 6. 4b: `PlayerBroadcastReceiver.kt`.
7. 1a: `LatePage.kt`, `StreamDialogDefaultEntry.java`. 8. 1b: `LateDialog.kt`, `QueueItemMenuUtil.java`.
9. 1c: `LateDelivery.kt`, `SparseItemUtil.java`, only if D1c reproduces.
10. Device script complete; results in the pull request.

Upstream lines, all marked: `StreamDialogDefaultEntry` 13, `QueueItemMenuUtil` 5, `SparseItemUtil`
1, `SearchFragment` 9, `ChannelFragment` 9, `AudioReactor` 7, `Player` 5, `PlayerBroadcastReceiver`
1, `DownloadMission` 2, `DownloadInitializer` 1, `LocalPlaylistFragment` 8: about 61 in 11 files.

## Overlap with release-views

| File | release-views (its plan) | crash-fixes | Textual | Behaviour |
|---|---|---|---|---|
| `SearchFragment.java` | 1 line in `onDestroyView` before `:412` (4.5) | 9 lines after `:375` in `onResume` | 36 lines apart, no conflict | Its 4.2 relies on "`onResume` restarts what was loading"; for search that is false today and becomes true with C2. C2 runs in `onResume`, when the view and `itemsList` exist. |
| `ChannelFragment.java` | new `onDestroyView`; `:381` changed; 1 line in the subscriber `:417-420` (4.4) | new `onResume` after `:398` | 17 and 19 lines from its hunks, no conflict if `onDestroyView` is placed next to `onDestroy` (`:161-172`) | Complementary: its guard handles a result for a page without view (`binding == null`), C3 a result for a page with view but saved state. C3 checks `tabAdapter != null` because release-views sets it to null in `onDestroyView`. |

Rules to keep it that way: C3's method stays directly after `doInitialLoadLogic`, C2's lines
directly after `super.onResume()`; neither feature edits the other's lines; whichever merges
second into `enhancements/integration` re-runs D2, D3 and release-views script steps 4 and 6a.
Other branches: `feature/queue-guard` and `feature/default-open-action` change
`LocalPlaylistFragment.java:277,970-982` and `feature/keep-position` `Player.java:501,1228`; no
hunk is near ours. `download-fixes` and `player-memory` have no plan yet; they may touch
`DownloadMission.java` and the player.

## Risks

- **1a drops an action the user still expects** if a page is alive but briefly not started
  (for example under a system dialog). Accepted: the spec says actions that need the page are
  dropped; nothing is shown to the user in that case, as today's crash showed nothing useful.
- **1c is reasoned, not observed.** Guarded by the commit rule above; if it is left out, a crash
  on Android 12+ for "start in background, then leave the app for long" may remain.
- **C2 restarts a search from `onResume`**: a wrong condition would repeat requests on every
  return. The decision is the unit-tested part; D2 has the controls.
- **C4 touches the audio-focus path of every playback.** The override returns false unless a
  live stream is idle, so other playback takes the unchanged line.
- **C6 keeps a main-thread database read** (Q3) and changes which thumbnail a playlist without
  one gets after a delete.
- **Not fixed, seen while reading:** the blocked-channel `popBackStack` with saved state (C3);
  `LocalPlaylistManager.java:146` `get(0)` on a deleted playlist.
- **Rebase:** 11 upstream files; all lines marked, new names fully qualified, no import hunks.
