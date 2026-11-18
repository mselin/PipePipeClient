# Plan: more crash fixes

Spec: `spec.md` (AC-1..AC-6). Paths are relative to `app/src/main/java/org/schabi/newpipe/`.
Line numbers are those of this branch at `3be65b5` (= `feature/crash-fixes` `fde3a11`).
Nothing here was run: the app cannot be started by this layer. Every statement about a library
is marked **[checked]** (read in the compiled classes of the versions in `app/build.gradle`:
fragment 1.8.9, viewpager 1.0.0, material 1.14.0, rxjava 3.1.12, exoplayer 2.18.7, with `javap`)
or **[assumed]**. Section "Checked and assumed" lists them all.
Open questions this plan depends on: Q1..Q6 in `questions.md`; each names the assumption used.

## 0. Shape of the change

- Marked direct edits in upstream code (`PROCESS.md`, defect-fix exception). **Every added or
  changed line ends with `// ENH:crash-fixes-more`, including lines that hold only a brace; no
  blank line and no import is added.** Helper classes are written with their full name.
- **No new helper class, no new file under `enhancements/crashfixesmore/` in `app/src/main`.**
  The parent's helpers are reused as they are:
  `org.schabi.newpipe.enhancements.crashfixes.LateDialog.canShow(fm)` where a fragment manager
  was captured or belongs to an activity, and
  `org.schabi.newpipe.enhancements.crashfixes.LatePage.ifUsable(fragment, action)` where a list
  page shows the dialog on its own fragment manager. `SearchResume` and `ErrorMessages` fit
  nowhere. The remaining edits are single conditions inside upstream code and have no decision
  logic of their own (`PROCESS.md`: instrumented tests and a device script take the place of
  unit tests).
- No resources, no settings, no build file changes.
- **Prerequisite P0 (Q5), not owned by this package.** The parent's wiring test
  (`app/src/test/.../crashfixes/CrashFixesWiringTest.kt`) counts the lines that *contain*
  `ENH:crash-fixes`; `ENH:crash-fixes-more` contains that text. As soon as this package marks a
  line in `ChannelFragment.java` (parent expects exactly 9) or `LocalPlaylistFragment.java`
  (exactly 8), the parent's test fails. Both files must be edited (C2, C5, C1f). The filter in
  that test has to become `it.contains(MARKER) && !it.contains("$MARKER-")` in a
  `test(crash-fixes):` commit on this branch **before** the first code commit. Until Q5 is
  answered, no code step that touches those two files is committed.

## 1. Result per candidate

| # | Candidate | Result | Edit |
|---|---|---|---|
| C1a | `player/PlayerClickController.kt:181-184` | confirmed | 1 line |
| C1b | `RouterActivity.java:611-628` (add to playlist) | confirmed | 4 lines |
| C1c | `RouterActivity.java:652-654` (download) | confirmed | 4 lines |
| C1d | `fragments/detail/DetailActions.kt:185-186` (add to playlist) | confirmed | 1 line |
| C1e | `fragments/detail/DetailActions.kt:140-141` (download) | **not confirmed** | none |
| C1f | `util/selection/StreamSelectionActions.java:51-53` | confirmed | 2 lines |
| C1g | `local/playlist/LocalPlaylistFragment.java:1013-1016` | confirmed | 1 line |
| C1h | `fragments/list/playlist/PlaylistFragment.java:265-272` | confirmed | 1 line |
| C2a | `ChannelFragment.handleResult`: `requireContext()` `:441`, `:449` | **not confirmed** | none |
| C2b | `ChannelFragment.handleResult`: `popBackStack()` `:453` | confirmed | 10 lines |
| C3a | `local/dialog/PlaylistAppendDialog.java:173` | confirmed | 1 line |
| C3b | `local/playlist/LocalPlaylistManager.java:146` (`get(0)`) | **not confirmed** (Q1) | none |
| C4 | `player/PlayerProgressController.kt:281` | confirmed, wording corrected (Q2) | 1 line |
| C5a | `ChannelFragment.updateTabs` `:353-355`, null tab | confirmed | 1 line |
| C5b | follow-up crash in the pager after C5a (found while checking) | confirmed by reading | 12 lines in `TabAdapter.java` |

How a thrown exception becomes a crash, used below: a callback of
`PlaylistDialog.createCorrespondingDialog` runs inside `Maybe.subscribe(Consumer)`
(`local/dialog/PlaylistDialog.java:145-152`); the observer catches what the callback throws and
hands it to `RxJavaPlugins.onError` **[checked]**; `App.java:326-331` treats
`IllegalStateException` and `NullPointerException` as critical and rethrows them as uncaught.
The same holds for `Single.subscribe(onSuccess, onError)` **[checked]**.

## C1 Dialogs shown after a wait (AC-1)

**Common failure path.** `PlaylistDialog.createCorrespondingDialog` asks the database whether
playlists exist (`LocalPlaylistManager.java:171-176`, io thread) and calls back on the main
thread, always at least one main-loop turn after the tap. `dialog.show(fm, tag)` is
`beginTransaction().add().commit()` **[checked]**; `commit` throws
`IllegalStateException: Can not perform this action after onSaveInstanceState` when
`fm.isStateSaved()`, which is `mStateSaved || mStopped`, so already true after `onStop`
**[checked]**, and `FragmentManager has been destroyed` on a destroyed manager **[checked]**.
Sequence: tap; the activity is stopped (Home, screen off, another activity) or destroyed
(rotation) before the database answers; callback on the main thread; `show` throws; the app dies.
For C1a, C1d, C1f, C1h the wait is one database read, so the window is a few milliseconds
(longer while the database is busy); for C1b, C1c it is a network request; for C1g it is reading
the whole playlist. None was observed on a device; all are derived.

**C1a `PlayerClickController.kt:184`** (1 changed line). The fragment manager is the activity's,
captured at the tap (`:59-62`, also `PlayQueueActivity.java:126`); the player outlives the
activity and the disposable is not kept.
```
old:         ) { dialog -> dialog.show(fragmentManager, Player.TAG) }
new:         ) { dialog -> if (org.schabi.newpipe.enhancements.crashfixes.LateDialog.canShow(fragmentManager)) dialog.show(fragmentManager, Player.TAG) } // ENH:crash-fixes-more
```

**C1b `RouterActivity.java`, add to playlist** (4 lines inserted after `:618`
`playlistDialog -> {`, before `:619`). The stream is loaded from the network first (`:611`); the
inner subscription is not in `disposables`, so it also survives `onDestroy`.
```
                                    if (!org.schabi.newpipe.enhancements.crashfixes.LateDialog.canShow(getSupportFragmentManager())) { // ENH:crash-fixes-more
                                        finish(); // ENH:crash-fixes-more
                                        return; // ENH:crash-fixes-more
                                    } // ENH:crash-fixes-more
```
**C1c `RouterActivity.java`, download** (4 lines inserted after `:650`
`final FragmentManager fm = getSupportFragmentManager();`). The outer disposable is cleared in
`onDestroy` (`:159`), so only the stopped case exists here.
```
                    if (!org.schabi.newpipe.enhancements.crashfixes.LateDialog.canShow(fm)) { // ENH:crash-fixes-more
                        finish(); // ENH:crash-fixes-more
                        return; // ENH:crash-fixes-more
                    } // ENH:crash-fixes-more
```
Why `finish()` and not only a skipped `show` (Q3): the activity is invisible and is finished only
by the dismissal of a dialog. `onStart` runs `handleUrl` again (`:149-153`), so without `finish`
a return shows the chooser a second time, and cancelling it leaves an invisible activity that
swallows touches, because `selectionIsDownload` / `selectionIsAddToPlaylist` are still true
(`:363-367`). Finishing is what "the dialog is dropped" means for a screen that has nothing else.

**C1d `DetailActions.kt:186`** (1 changed line). `fm` is captured at the tap (`:184`); the
disposable is cleared in `onDestroyView` (`VideoDetailFragment.kt:306`), so only the stopped case
exists.
```
old:             it.show(fm, TAG)
new:             if (org.schabi.newpipe.enhancements.crashfixes.LateDialog.canShow(fm)) it.show(fm, TAG) // ENH:crash-fixes-more
```

**C1e `DetailActions.kt:140-141`: not confirmed, no edit.** The download dialog of the video
page is shown synchronously in the click (`:104-109`) or from
`MainActivity.onRequestPermissionsResult` (`MainActivity.java:709-714`), where the activity has
already told its fragment manager that the state is no longer saved **[checked:
`FragmentActivity` calls `noteStateNotSaved`]**. There is no wait. The call is also inside
`try { } catch (e: Exception)` (`:139-145`), which catches the `IllegalStateException` and shows
a snackbar.

**C1f `StreamSelectionActions.java:52-53`** (2 changed lines). Called by `BaseListFragment:781`,
`BaseLocalListFragment:374`, `FeedFragment.kt:379`; their disposables end in `onDestroy`, so the
fragment is attached whenever the callback runs and `getParentFragmentManager()` cannot throw
**[checked: it throws only when the fragment has no manager]**; `show` throws when the state is
saved.
```
old:                     streams, dialog -> dialog.show(fragment.getParentFragmentManager(),
old:                             "StreamSelectionActions@playlist")));
new:                     streams, org.schabi.newpipe.enhancements.crashfixes.LatePage.ifUsable(fragment, dialog -> dialog.show(fragment.getParentFragmentManager(), // ENH:crash-fixes-more
new:                             "StreamSelectionActions@playlist")))); // ENH:crash-fixes-more
```
**C1g `LocalPlaylistFragment.java:1016`** (1 changed line). Outer wait `:1006-1012` (all streams
of the playlist), disposables cleared in `onDestroyView` (`:463-465`).
```
old:                             dialog -> dialog.show(getParentFragmentManager(), TAG)
new:                             org.schabi.newpipe.enhancements.crashfixes.LatePage.ifUsable(this, dialog -> dialog.show(getParentFragmentManager(), TAG)) // ENH:crash-fixes-more
```
**C1h `PlaylistFragment.java:272`** (1 changed line). Disposables cleared in `onDestroyView`
(`:201-203`).
```
old:                     dialog -> dialog.show(getFM(), TAG)
new:                     org.schabi.newpipe.enhancements.crashfixes.LatePage.ifUsable(this, dialog -> dialog.show(getFM(), TAG)) // ENH:crash-fixes-more
```
C1f-C1h have the shape of the parent's `StreamDialogDefaultEntry.java:118`, which compiles.
`LatePage.usable` reads the view lifecycle only after `fragment.getView() != null`.

**Named in AC-1 but nothing to fix.** "Download from the player": the player has no download
dialog of its own; the play queue's item menu was fixed by the parent (1b). "Download from a
selection": `SelectionDownloadHelper.java:22-38` shows a plain `AlertDialog` synchronously.
**Same defect elsewhere.** None: these are all callers of `createCorrespondingDialog` and
`DownloadDialog.newInstance` (searched), the others belong to the parent.
**Regression risk.** `LateDialog.canShow` is true for a started activity, so the foreground path
is unchanged. `LatePage.ifUsable` also drops the dialog when the list page's view is gone
(another page on top); today it would open over that other page. C1b/C1c finish the share
screen instead of crashing; a return to it finds nothing (Q3).

## C2 Late channel result (AC-2)

**C2a `requireContext()`: not confirmed, no edit.** `requireContext()` throws only when the
fragment is not attached **[checked]**. `handleResult` runs from three places: the worker
(`:426-429`), which `onDestroy` disposes (`:164-166`) on the main thread before the fragment is
detached; `doInitialLoadLogic` (`:396`), called in `onViewCreated`; and the new `onResume` lines
below. In all three the fragment is attached. A result for a page whose view is gone (another
page on top) does not crash either, without `release-views`: `updateTabs` runs on the old
binding, the child fragment manager is stopped, `TabAdapter` adds nothing (`TabAdapter.java:45`,
`:65`, `:147`), the loop at `:353` does not run, and `onViewCreated` handles `currentInfo` again
on return. With `release-views` that result does not reach `handleResult` at all.

**C2b `popBackStack()`: confirmed.** Sequence: a channel on the block list is opened; the app is
sent to the background before the request ends; the result arrives on the main thread with the
activity stopped; `:452-454` calls `getParentFragmentManager().popBackStack()`, which enqueues
without allowing state loss and throws `Can not perform this action after onSaveInstanceState`
**[checked]**; the exception leaves the `Single` subscriber and the app dies. Second finding,
no crash: with another page opened on top (not saved), today's code pops *that* page.

**Fix** (`ChannelFragment.java`, 10 lines in three places; Q4). A blocked result that arrives
while the page cannot go back is kept and handled when the page is resumed.
1. Field, 1 line inserted after `:74` `private int lastTab;`:
```
    private ChannelInfo lateBlockedResult; // ENH:crash-fixes-more
```
2. 5 lines inserted in the parent's `onResume`, after `:401` `super.onResume(); // ENH:crash-fixes`:
```
        if (lateBlockedResult != null) { // ENH:crash-fixes-more
            final ChannelInfo late = lateBlockedResult; // ENH:crash-fixes-more
            lateBlockedResult = null; // ENH:crash-fixes-more
            handleResult(late); // ENH:crash-fixes-more
        } // ENH:crash-fixes-more
```
3. 4 lines inserted before `:449` `Toast.makeText(requireContext(),` (inside the blocked branch):
```
                if (getView() == null || getParentFragmentManager().isStateSaved()) { // ENH:crash-fixes-more
                    lateBlockedResult = result; // ENH:crash-fixes-more
                    return; // ENH:crash-fixes-more
                } // ENH:crash-fixes-more
```
- The condition is deliberately **not** `LatePage.usable(this)`: `handleResult` is also called
  from `onViewCreated` (`BaseStateFragment.java:39`, cached or restored info), where the view
  exists **[checked: `mView` is set before `onViewCreated`]** but its lifecycle is not yet
  started; `usable` would be false there and a blocked channel opened from the cache would no
  longer go back.
- In `onResume` the state is not saved and the view exists, so `handleResult(late)` shows the
  toast and goes back, as for a result that arrived in time. If the channel was unblocked in
  between, the same call shows the channel.
- A result that is not blocked is untouched: it is shown by the parent's C3.

**With and without `release-views`.** That branch sets `binding` and `tabAdapter` to null in
`onDestroyView` and adds `if (binding == null) { currentInfo = result; return; }` in the worker's
subscriber before `handleResult(result)`.

| Late blocked result | Without `release-views` | With `release-views` |
|---|---|---|
| App in background, view alive | guard 3 keeps it; `onResume` shows toast and goes back | the same (`binding` is not null) |
| Another page on top, view gone | guard 3 (`getView() == null`) keeps it; on return `onViewCreated` starts a load (`currentInfo` is null) and `onResume` handles the kept result first; the page is popped and `onDestroy` disposes the load | the subscriber stores it in `currentInfo`; on return `onViewCreated` calls `handleResult(currentInfo)` with a view and unsaved state: toast and back. Guard 3 is not reached |

`binding` and `tabAdapter` are never touched by the new lines; `handleResult` from `onResume`
runs only with a view, when both exist in either variant.
**Regression risk.** Guard 3 is false for every result that arrives on a visible page and for
the `onViewCreated` path, so today's behaviour is kept there.

## C3 Playlists without a thumbnail (AC-3)

**C3a `PlaylistAppendDialog.java:173`: confirmed.** `playlists.thumbnail_url` is nullable
(`app/schemas/.../902.json`, `PlaylistEntity.java:30-31`); it is null for a playlist created
from a stream without thumbnail (`LocalPlaylistManager.java:49`). Tapping such a playlist in the
"Add to playlist" dialog runs `onPlaylistSelected` on the main thread (`:71-80`);
`playlist.thumbnailUrl.equals(..)` throws `NullPointerException`; nothing catches it.

**Fix** (1 changed line):
```
old:         if (playlist.thumbnailUrl.equals("drawable://" + R.drawable.dummy_thumbnail_playlist)) {
new:         if (("drawable://" + R.drawable.dummy_thumbnail_playlist).equals(playlist.thumbnailUrl)) { // ENH:crash-fixes-more
```
A playlist without thumbnail keeps none (Q6); the stream is appended by the unchanged
`:179-181`. **Regression risk.** None for a playlist with a thumbnail: same result.

**C3b `LocalPlaylistManager.java:146`: not confirmed, no edit (Q1).** `get(0)` throws only when
the playlist row is gone. Every caller that is not already inside `try/catch`
(`LocalPlaylistFragment.java:811`, `:882`, `:902`, `:1109`, `:1561`) runs per item of the
playlist, and a deleted playlist has no items: the join rows are removed by `ON DELETE CASCADE`
(`PlaylistStreamEntity.java:22-26`), the page's query is notified of that (tried with SQLite
3.53: a trigger on the join table fires for a cascade delete) and shows an empty list. A page
for a deleted playlist is reachable (home-screen shortcut), but it is empty; its only call is
`:390`, inside `try/catch`. A playlist cannot be deleted while its page is open: deleting
happens only in Bookmarks (`BookmarkFragment.java:677`), and the drawer that could open
Bookmarks on top is locked on every page but the main one (`MainActivity.java:831-832`).
Ready if the spec owner wants the guard anyway (1 changed line):
`return playlistTable.getPlaylist(playlistId).blockingFirst().stream().findFirst().map(PlaylistEntity::getThumbnailUrl).orElse(null); // ENH:crash-fixes-more`

## C4 Idle live stream after a seek (AC-4): confirmed, wording corrected (Q2)

**Failure path.** A paused live stream is idle: `PlayerPlaybackStateController.kt:170` calls
`enterLiveIdle` (`Player.java:535-553`), which stops the player; `stop()` keeps the timeline, so
`duration` stays what it was **[checked]**. The user drags the seek bar to its right end and
lets go: `onStopTrackingTouch` (`PlayerProgressController.kt:274-282`) finds `wasPlaying` false
but `duration == seekBar.progress`, and calls the raw `simpleExoPlayer.play()`, which only sets
`playWhenReady` **[checked]**. The player has no prepared source: nothing plays, the state
becomes BUFFERING (`:291-293`), and the next tap on play pauses
(`PlayerTransportController.kt:77-81`): the stuck state of the parent's C4.
The spec's "was playing before the seek" does not lead here: dragging while a live stream plays
pauses it under `PAUSED_SEEK` (`:256-262`), which never enters idle
(`PlayerPlaybackStateController.kt:59-63`, `:173-177`). The parent's plan said the same.

**Fix** (1 changed line, the form of the parent's 4b):
```
old:             player.simpleExoPlayer.play()
new:             if (player.isLiveIdle()) player.play() else player.simpleExoPlayer.play() // ENH:crash-fixes-more
```
`Player.play()` on an idle live stream rebuilds the source and plays
(`PlayerTransportController.kt:36-41`): what a tap on play does today in the same state.
**Regression risk.** `isLiveIdle()` is false for every video that is not a stopped live stream;
those take the unchanged call.

## C5 Swallowed tab error (AC-5)

**Failure path of the crash on the device, C5a.** `ChannelFragment` gives the same `TabAdapter`
to the pager and, through `setupWithViewPager`, to the tab bar (`:137-139`).
`PagerAdapter.notifyDataSetChanged` first calls the pager's observer and only then the other
observers **[checked]**; the tab bar is one of the others **[checked]**. `updateTabs`
(`:317-364`) runs on the main thread when the channel has loaded and the pager is laid out:
1. `addFragment(Videos)` -> `notifyDataSetChanged` -> the pager populates ->
   `FragmentPagerAdapter.finishUpdate` -> `commitNowAllowingStateLoss` **[checked]** -> the tab
   fragment is created synchronously and throws `IllegalStateException` (on the device: Picasso,
   in `ChannelVideosFragment.handleResult`).
2. `TabAdapter.notifyDataSetChanged` (`:148-152`) catches and prints it. The tab bar was not
   told: it still has no tabs.
3. `finishUpdate` did not reach the line that forgets its transaction **[checked]**. Every
   later `finishUpdate` executes that transaction again and fails with
   `Fragment already added` **[checked: `commitNowAllowingStateLoss` does not test "already
   committed"; `FragmentStore.addFragment` throws]**. So every later `addFragment` and, when the
   channel has three or more tabs, the final `notifyDataSetUpdate` (`:351`) fail the same way
   and are swallowed; the tab bar is never told.
4. `:354` `binding.tabLayout.getTabAt(0)` is null for an index outside the tab bar
   **[checked]**; `.setText` throws `NullPointerException`: the crash.

**Fix C5a** (`ChannelFragment.java:353`, 1 changed line): titles are set only for tabs the tab
bar has.
```
old:         for (int i = 0; i < tabAdapter.getCount(); i++) {
new:         for (int i = 0; i < Math.min(tabAdapter.getCount(), binding.tabLayout.getTabCount()); i++) { // ENH:crash-fixes-more
```
`:360-363` already tests its tab for null.

**C5b, the crash that follows once C5a is in: confirmed by reading, not observed.** The failed
tab's view was added to the pager before the fragment threw **[checked]**, so a layout pass is
pending. `ViewPager.onMeasure` calls `populate()`, which always ends in `finishUpdate`
**[checked]**; by step 3 that throws `IllegalStateException: Fragment already added`, this time
outside `TabAdapter.notifyDataSetChanged`, with nothing to catch it. C5a alone would move the
crash from `updateTabs` to the next frame.

**Fix C5b** (`fragments/detail/TabAdapter.java`, 12 lines inserted after `:143`, the closing
brace of `destroyItem`; no blank line before them):
```
    private boolean pagerFailed; // ENH:crash-fixes-more
    @Override // ENH:crash-fixes-more
    public void finishUpdate(@NonNull final ViewGroup container) { // ENH:crash-fixes-more
        try { // ENH:crash-fixes-more
            super.finishUpdate(container); // ENH:crash-fixes-more
        } catch (final IllegalStateException e) { // ENH:crash-fixes-more
            if (!pagerFailed) { // ENH:crash-fixes-more
                Log.e("TabAdapter", "The pager failed; this adapter adds no more tabs", e); // ENH:crash-fixes-more
            } // ENH:crash-fixes-more
            pagerFailed = true; // ENH:crash-fixes-more
        } // ENH:crash-fixes-more
    } // ENH:crash-fixes-more
```
- `Log`, `NonNull` and `ViewGroup` are imported already (`:3-6`). `TabAdapter` is used only by
  `ChannelFragment` (searched); the video page does not use it, so the spec's "unless the plan
  finds the same crash there" does not apply.
- The failure is logged once with its stack trace; the repeats of step 3 (one per layout pass)
  are not, or the log would be flooded. The existing `printStackTrace` at `:151` stays.
- With C5b the error no longer leaves `notifyDataSetChanged`, so the tab bar *is* told and shows
  every tab with its title. C5a stays as the guard at the line that crashed, for an error
  swallowed at `:150` that does not come from `finishUpdate` (for example from `destroyItem`).
- **What the user gets after such a failure:** the tab bar with all tabs; the failed tab as far
  as it got; the other tabs empty, because their fragments are queued in the transaction that
  can no longer run. Reloading does not repair it; leaving and reopening the page builds a new
  adapter. This is "does not crash, shows the tabs that exist", not a working page.
- **Not known:** whether the half-created tab fragment survives its further lifecycle (start,
  stop, destroy). That is the tab's own code, the "cause" the spec excludes; test I2 below
  exercises it with a fragment that fails before its own code runs.

**Regression risk.** C5a: both counts are equal whenever the pager works. C5b: an
`IllegalStateException` from a channel tab's creation no longer reaches the crash reporter; it
is in the log only. `IllegalStateException: FragmentManager is already executing transactions`
from a nested update is now swallowed in a layout pass too; there the transaction was not run,
so the next `finishUpdate` runs it (self-healing).

## Checked and assumed

| Statement | Status |
|---|---|
| `FragmentManager.isStateSaved()` is `mStateSaved \|\| mStopped`; `isDestroyed()` is `mDestroyed` | checked (fragment 1.8.9) |
| `DialogFragment.show(fm, tag)` ends in `commit()`; `commit`/`popBackStack()` enqueue with `allowStateLoss = false`; that throws when the state is saved or the manager is destroyed | checked |
| `Fragment.requireContext()` / `getParentFragmentManager()` throw only without context / manager; `Fragment.isStateSaved()` is false without manager | checked |
| `mView` is set and added to its container before `onViewCreated` | checked |
| `FragmentActivity.onRequestPermissionsResult` calls `noteStateNotSaved` | checked |
| `Maybe.subscribe(Consumer)` and `Single.subscribe(c, c)` pass an exception of the callback to `RxJavaPlugins.onError` | checked (rxjava 3.1.12) |
| `PagerAdapter.notifyDataSetChanged`: pager observer first, then the registered observers; `TabLayout` registers with `registerDataSetObserver`, `ViewPager` with `setViewPagerObserver` | checked (viewpager 1.0.0, material 1.14.0) |
| `TabLayout.getTabAt(i)` returns null outside `0..getTabCount()-1` | checked |
| `FragmentPagerAdapter.finishUpdate` keeps `mCurTransaction` when the commit throws; `commitNowAllowingStateLoss` -> `execSingleAction` without a committed check; ops run before the fragments move; `FragmentStore.addFragment` throws `Fragment already added` | checked |
| `ViewPager.onMeasure` -> `populate()` -> `finishUpdate` whenever the pager is attached; `setCurrentItemInternal` populates at once only after the first layout | checked |
| `ExoPlayer.stop()` keeps the timeline; `play()` is `setPlayWhenReady(true)` without `prepare` | checked (exoplayer 2.18.7) |
| A cascade delete fires the triggers Room's change tracking uses | tried with desktop SQLite 3.53; assumed equal on Android |
| `Activity.finish()` on an already destroyed activity does nothing (C1b, a window of milliseconds) | assumed (platform, no body in the SDK stubs) |
| The seek bar's maximum equals `duration` on an idle live stream, so "dragged to the end" satisfies `:280` | assumed (both come from the same value; a manifest refresh between the last tick and the pause can make them differ, then today's code and the fix both do nothing) |
| The page survives the further lifecycle of a half-created tab fragment (C5b) | assumed; test I2 |
| The Kotlin and Java lines above compile | not compiled; C1f-C1h and C4 copy shapes of the parent that compile |

## Verification

**JVM unit tests** (`app/src/test/java/org/schabi/newpipe/enhancements/crashfixesmore/`, JUnit 4).
There is no new logic class, so there is one class, the source scan
`CrashFixesMoreWiringTest`, built like the parent's but with the marker `ENH:crash-fixes-more`.
Per file: the exact number of lines containing the marker, the listed text in the marked lines
(marker and whitespace removed), and that the faulty expression is gone.

| Test | File | Marked lines | Must contain | Must be gone (whole file) |
|---|---|---|---|---|
| `ac1_wiring_playerDialog` | `player/PlayerClickController.kt` | 1 | `if (org.schabi.newpipe.enhancements.crashfixes.LateDialog.canShow(fragmentManager)) dialog.show(fragmentManager, Player.TAG)` | |
| `ac1_wiring_shareMenu` | `RouterActivity.java` | 8 | `LateDialog.canShow(getSupportFragmentManager())) { finish(); return; }`, `LateDialog.canShow(fm)) { finish(); return; }` | |
| `ac1_wiring_detailPage` | `fragments/detail/DetailActions.kt` | 1 | `LateDialog.canShow(fm)) it.show(fm, TAG)` | |
| `ac1_wiring_selection` | `util/selection/StreamSelectionActions.java` | 2 | `LatePage.ifUsable(fragment, dialog -> dialog.show(fragment.getParentFragmentManager(), "StreamSelectionActions@playlist"))` | `streams, dialog -> dialog.show(` |
| `ac1_wiring_localPlaylist` | `local/playlist/LocalPlaylistFragment.java` | 1 | `LatePage.ifUsable(this, dialog -> dialog.show(getParentFragmentManager(), TAG))` | |
| `ac1_wiring_remotePlaylist` | `fragments/list/playlist/PlaylistFragment.java` | 1 | `LatePage.ifUsable(this, dialog -> dialog.show(getFM(), TAG))` | |
| `ac2_ac5_wiring_channelPage` | `fragments/list/channel/ChannelFragment.java` | 11 | `private ChannelInfo lateBlockedResult;`; `if (lateBlockedResult != null) { final ChannelInfo late = lateBlockedResult; lateBlockedResult = null; handleResult(late); }`; `if (getView() == null \|\| getParentFragmentManager().isStateSaved()) { lateBlockedResult = result; return; }`; `Math.min(tabAdapter.getCount(), binding.tabLayout.getTabCount())` | `i < tabAdapter.getCount(); i++` |
| `ac5_wiring_tabAdapter` | `fragments/detail/TabAdapter.java` | 12 | `public void finishUpdate(@NonNull final ViewGroup container) { try { super.finishUpdate(container); } catch (final IllegalStateException e) {`; `pagerFailed = true;` | |
| `ac3_wiring_appendDialog` | `local/dialog/PlaylistAppendDialog.java` | 1 | `("drawable://" + R.drawable.dummy_thumbnail_playlist).equals(playlist.thumbnailUrl)` | `playlist.thumbnailUrl.equals(` |
| `ac3_wiring_playlistManagerUntouched` | `local/playlist/LocalPlaylistManager.java` | 0 | (C3b is not confirmed; changes with the answer to Q1) | |
| `ac4_wiring_seekOnIdleLive` | `player/PlayerProgressController.kt` | 1 | `if (player.isLiveIdle()) player.play() else player.simpleExoPlayer.play()` | |
| `ac6_wiring_noResources` | `app/src/main/res` | | no file name and no line contains `crash_fixes_more` or `crash-fixes-more` | |

Total: **39 marked lines in 10 upstream files.** The parent's wiring test must still pass
(prerequisite P0).

**Instrumented tests** (by hand on a device or emulator, not in CI; package
`...enhancements.crashfixesmore`; existing dependencies; modelled on the parent's
`LocalPlaylistDeleteTest`):
- **I1 `ac3_addToPlaylistWithoutThumbnail`**: insert a playlist with thumbnail null and one
  stream through the DAOs; show `PlaylistAppendDialog.newInstance(listOf(stream))` on
  `MainActivity`; when the list has loaded, click its row (or call the private
  `onPlaylistSelected` by reflection with the entry of the adapter). Expect no exception and one
  more join row for the playlist. Fails today with the `NullPointerException`.
- **I2 `ac5_failingTab_channelPageSurvives`**: show `ChannelFragment.getInstance(0, url, name)`
  on `MainActivity` and wait until it is laid out; by reflection set `currentInfo` to a
  `ChannelInfo(0, id, url, url, name)` whose tabs and description give **at least three** tabs
  (with two, the final notification succeeds and the old code does not crash); register on the
  page's child fragment manager a `FragmentLifecycleCallbacks` that throws
  `IllegalStateException` in `onFragmentPreCreated` once, for the first tab fragment; call the
  private `updateTabs()` on the main thread; wait for the idle sync and one second of frames.
  Expect: no exception from `updateTabs`, the process alive, the tab bar's tab count equal to
  the adapter's count. On the parent's code this fails with the `NullPointerException` of the
  device crash; with C5a alone it is expected to die in the next layout pass with
  `Fragment already added`. **This test is the only evidence for C5b and must be run before the
  pull request is merged**; if the process dies with C5b in place, the Tests layer asks the plan.

**Device script** (debug build; slow network as in the parent's script). Steps marked *old* are
first run on the parent's build.
- **D1** Share a video link to the app, choose Download, press Home at once, wait until the
  details have loaded. *Old:* crash dialog. New: nothing; the share screen is gone. The same
  with Add to playlist. Control: without leaving, both dialogs appear and closing them ends the
  share screen.
- **D2** Regression (the window is too short to hit by hand): Add to playlist from the player's
  button, from the play queue's menu, from the video page (tap and long-press), from a selection
  in a channel, in the feed and in a local playlist, from a remote playlist's menu and from a
  local playlist's "append to another playlist": the dialog appears each time. Once each: tap
  and press Home in the same moment; no crash.
- **D3** Put a channel on the block list (by name), open its link through the share menu with
  "show info", press Home before it has loaded, wait, return. *Old:* crash dialog. New: on
  return the "blocked" toast and the previous page. Control: the same without leaving: toast
  and back, as before. Control: the parent's D3 (channel loaded in the background shows its
  tabs).
- **D4** With the database of the parent's D6 (a playlist whose `thumbnail_url` is NULL): open
  a video, Add to playlist, choose that playlist. *Old:* crash. New: "added", the video is in
  the playlist.
- **D5** Play a live stream that can be rewound, pause, wait ten seconds, drag the seek bar to
  its right end. *Old:* nothing plays and the first tap on play does nothing. New: the stream
  plays. Controls: paused live stream, drag to the middle: stays paused, one tap plays; playing
  live stream, drag back: keeps playing from there; a paused normal video dragged to its end
  behaves as before.
- **D6** Regression for C5 (a pager failure cannot be provoked by hand): three channels with
  several tabs open with all tab titles; swipe through the tabs; rotate; leave and return: the
  selected tab is kept. `adb logcat -s TabAdapter` shows no "The pager failed".

## AC coverage

| AC | Fix | Unit test | Instrumented / device |
|---|---|---|---|
| AC-1 | C1a-C1d, C1f-C1h | `ac1_wiring_*` | D1, D2 |
| AC-2 | C2b (C2a not confirmed) | `ac2_ac5_wiring_channelPage` | D3 |
| AC-3 | C3a; second sentence: no edit, Q1 | `ac3_wiring_*` | I1, D4 |
| AC-4 | C4 (criterion as in Q2) | `ac4_wiring_seekOnIdleLive` | D5 |
| AC-5 | C5a, C5b | `ac2_ac5_wiring_channelPage`, `ac5_wiring_tabAdapter` | I2, D6 |
| AC-6 | no resources, no settings; every guard is false or equal on the paths that work today | `ac6_wiring_noResources` | controls in D1-D6 |

## Order of work

P0 first (Q5). Then the test commit, then one `code(crash-fixes-more):` commit per step; each
builds alone and can be reverted alone.

1. C3a: `PlaylistAppendDialog.java`.
2. C4: `PlayerProgressController.kt`.
3. C1a, C1d: `PlayerClickController.kt`, `DetailActions.kt`.
4. C1b, C1c: `RouterActivity.java`.
5. C1f, C1g, C1h: `StreamSelectionActions.java`, `LocalPlaylistFragment.java`,
   `PlaylistFragment.java`.
6. C2b: `ChannelFragment.java` (10 lines).
7. C5a and C5b together: `ChannelFragment.java` (1 line), `TabAdapter.java` (12 lines). Not
   apart: C5a alone moves the crash.
8. I1, I2 and the device script; results in the pull request.

## Merging with the other branches

Checked with `git diff origin/dev...origin/feature/<name> -- <file>` for `release-views`,
`player-memory`, `keep-position`, `queue-guard`, `default-open-action`, `subscription-scale`
(and `download-fixes`, `group-dialog`, `group-sort`, `group-create-inline`, `backup-safety`,
`tls-checks`: none touches a file of this plan).

| File | Other branch and its hunks | Ours | Textual | Behaviour |
|---|---|---|---|---|
| `ChannelFragment.java` | `release-views`: new `onDestroyView` after `:140`; `:381` changed; 4 lines in the subscriber between `:427` and `:428` | field after `:74`; `:353`; in `onResume` after `:401`; in `handleResult` before `:449` | no conflict: at least 20 unchanged lines lie between each of our hunks and the nearest of theirs (`:353`/`:381`, `:401`/`:427`, `:428`/`:449`). Rule: nothing of ours goes into the subscriber `:426-430` or next to `:140` | table in C2; C5a reads `binding` only inside `updateTabs`, which both variants reach only with a view |
| `PlayerProgressController.kt` | `player-memory`: `:18`, `:36`, 1 line after `:260` | `:281` | no conflict (20 unchanged lines between) | independent |
| `PlayerClickController.kt` | `keep-position`: 1 line after `:124` | `:184` | no conflict | independent |
| `LocalPlaylistFragment.java` | `queue-guard`, `default-open-action`: after `:276`, `:969-982` | `:1016` | no conflict (33 lines) | independent |
| `PlaylistFragment.java` | `queue-guard`, `default-open-action`: `:369-374` | `:272` | no conflict | independent |
| `RouterActivity.java`, `DetailActions.kt`, `StreamSelectionActions.java`, `PlaylistAppendDialog.java`, `TabAdapter.java` | none | | | `Player.java`, `BaseListFragment.java`, `SearchFragment.java`, `ChannelVideosFragment.java`, `InfoItemDialog.java` are not edited by this plan |

`StreamSelectionActions` is reached through `BaseListFragment`, which `release-views` and
`queue-guard` edit; C1f changes only what the callback does when it runs. Whichever of
`release-views` and this branch is merged second re-runs D3, D6 and I2.

## Risks

- **C5b is derived from the compiled library, not observed**, and what the page looks like
  after a pager failure is partly unknown (half-created tab). I2 is the check; without a run of
  I2 the claim "AC-5 is met" rests on reading only.
- **C5b hides errors.** A tab that fails with `IllegalStateException` becomes an empty tab and
  a log line instead of a crash report. That is what AC-5 asks for; it also makes the next such
  defect harder to notice. D6 has the log check.
- **The parent's wiring test (P0).** If it is not changed first, the unit tests of the parent
  fail on this branch although nothing of the parent changed.
- **C1b/C1c end the share screen** when the dialog cannot be shown. A user who leaves while the
  details load has to share again (Q3).
- **C1f-C1h drop the dialog when another page was opened on top**, where today it appears over
  that page. Same choice as the parent's 1a.
- **C2b keeps one result in a field.** If the channel is unblocked while its page is hidden, the
  kept result is shown as a normal result; without `release-views` a load started on return may
  deliver the same channel a second time, which rebuilds the tabs once more.
- **C4 depends on the seek bar's maximum being the player's duration** for an idle live stream;
  where it is not, nothing changes and the defect remains for that stream.
- **Not fixed, seen while reading:** upstream's same-queue intent path
  (`PlayerStartController.kt:107-134`) can start a live stream again while the idle flag stays
  set; then the play button, and with C4 a seek to the end, rebuild the source although it
  plays. `LocalPlaylistManager.java:146` stays as it is (Q1).
- **Rebase:** 10 upstream files, 39 lines, all marked, names fully qualified, no import hunks.

## Amendment

Spec: `spec.md`, "Amendment, 2026-11-18" (AC-7, AC-8). Line numbers are those of this branch at
`fa3699d`; `local/feed/FeedFragment.kt` and `App.java` are identical to `origin/dev` here. Nothing
was run or built. **[checked]** means read with `javap` in rxjava 3.1.12, rxandroid 3.0.2 or
fragment 1.8.9. Open questions this section depends on: Q9, Q10.

This section replaces three statements above: section 0, second bullet ("no new file under
`enhancements/crashfixesmore/`"); the total "39 marked lines in 10 upstream files"; and A8(c) in
`questions.md` where it says the wiring test fails on any file in that package. Everything else
above stands.

### A0. Result

| # | Candidate | Result | Edit |
|---|---|---|---|
| C7 | late "channel not available" lookup, `local/feed/FeedFragment.kt:879-910`, `:960-998` | confirmed by reading | 2 inserted lines |
| C8 | undeliverable error with a "bug" cause, `App.java:276-339` | mechanism confirmed by reading; narrower than AC-8 reads (Q10) | 4 inserted lines + 1 new Kotlin file |

How RxJava hands errors to the app's handler, used by both **[checked: `RxJavaPlugins.onError`,
`isBug`]**: `RxJavaPlugins.onError(e)` wraps `e` in `UndeliverableException` **unless** `e` itself
is an `OnErrorNotImplementedException`, `MissingBackpressureException`, `QueueOverflowException`,
`IllegalStateException`, `NullPointerException`, `IllegalArgumentException` or
`CompositeException`; those reach the handler as they are. Only `RxJavaPlugins` ever builds an
`UndeliverableException` **[checked: no other class in the jar refers to it]**, and it extends
`IllegalStateException` **[checked]**. So the wrapper says "this error is not of a bug type", it
does **not** say "nobody waits": see C8 and Q10.

### C7 Late "channel not available" lookup (AC-7): confirmed

**Sequence.** A refresh ends with a `RequestException` whose cause is a
`ContentNotAvailableException` (deleted or terminated channel). `handleLoadedState` (`:858`, main
thread, through the view's `LiveData` observer) calls `handleItemsErrors` (`:879`). It starts
`Single.fromCallable { .. }.subscribeOn(io).observeOn(mainThread).subscribe(onSuccess, onError)`
(`:884-902`); the disposable is thrown away. The user presses Back before the database answered:
`onDestroy`, then detach, both on the main thread. Then one of:
- **Success branch.** The callable had already read the row. `onSuccess` (main thread) calls
  `handleFeedNotAvailable`; its first line `:965` `requireContext()` throws
  `IllegalStateException: Fragment FeedFragment{..} not attached to a context` **[checked]**.
  `ConsumerSingleObserver.onSuccess` catches it and calls `RxJavaPlugins.onError` **[checked]**;
  an `IllegalStateException` is not wrapped; `App.java:302-304` finds it critical and hands it to
  the uncaught-exception handler: the app closes.
- **Error branch.** The callable runs after the detach: `:885` `requireContext()` throws on the io
  thread, or the row is gone (`:887`). `onError` (main thread) logs and calls
  `handleItemsErrors(rest)` (`:898-900`). If `rest` holds a further unavailable channel, the same
  repeats; if what is left is not empty and has none, `:908` `ErrorUtil.showSnackbar(this, ..)`
  runs `fragment.requireContext()` (`error/ErrorUtil.kt:79`; view and activity are null) and
  throws. `ConsumerSingleObserver.onError` wraps both in a `CompositeException` **[checked]**,
  which is not wrapped further; both members are `IllegalStateException`: the app closes. If
  `rest` is empty, this branch does not crash.

The window is one database read. Not observed; derived. AC-8's change does **not** cover it:
neither error is an `UndeliverableException`.

**Every `requireContext()` / `requireActivity()` reachable from the two callbacks.**

| Line | Runs | With the page detached | After the fix |
|---|---|---|---|
| `:885` `requireContext()` in the callable | io thread, later | throws; delivered to `onError` because the lookup is not disposed | unchanged: `onError` logs "Unable to process" and calls `handleItemsErrors`, which returns at E7a |
| `:908` -> `ErrorUtil.kt:77`, `:79` | main, same turn as the entry of `handleItemsErrors` | `:79` throws | not reached (E7a) |
| `:965`, `:970` `requireContext()`, `:967`, `:982` ff. `getString` | main, same turn as the entry of `handleFeedNotAvailable` | throw | not reached (E7b) |
| `:975` `requireContext()`, `:978`, `:990` in the dialog's buttons | main, at a later tap | would throw | **not changed** (see "Not fixed") |

**Fix** (`local/feed/FeedFragment.kt`, 2 inserted lines, no import; `context` is
`Fragment.getContext()`, null exactly when `requireContext()` throws **[checked]**; no local or
field named `context` is in scope in either function).

E7a, 1 line inserted after `:879` `    private fun handleItemsErrors(errors: List<Throwable>) {`,
directly before `:880` `        errors.forEachIndexed { i, t ->`:
```
        if (context == null) return // ENH:crash-fixes-more
```
E7b, 1 line inserted after `:964` `    ) {` (end of the parameter list of
`handleFeedNotAvailable`), directly before `:965`
`        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())`:
```
        if (context == null) return // ENH:crash-fixes-more
```
Both guards and everything they protect run in one main-thread turn, and a detach happens only on
the main thread, so nothing can detach between guard and use.

**The lookup is deliberately NOT added to `disposables`.** That was the proposal of the
investigation; reading shows it would open a crash path that does not exist today, and with the
two guards it adds nothing.
- `disposables` is disposed in `onDestroy` (`:463`), never cleared in `onDestroyView`. So a
  lookup started just before another page is opened on top would not be disposed anyway; one
  started just before Back would be.
- Once it is disposed while the callable has not finished, whatever the callable throws goes to
  `RxJavaPlugins.onError` instead of `onError` **[checked: `SingleFromCallable.subscribeActual`
  tests `d.isDisposed()`; `SubscribeOnObserver.dispose` disposes `d`]**. The callable throws
  `IllegalStateException` twice: `requireContext()` at `:885` on a page detached in the meantime,
  and `:887` "Subscription not found". An `IllegalStateException` is not wrapped and is critical:
  the app would close, where today the same error ends in the `Log.e` of `:899`. AC-8 does not
  catch it either (not undeliverable).
- Joining would therefore need two more changed lines (`:885` to the application context, `:887`
  to another exception type) to undo its own side effect: 5 lines instead of 2.
- If the join is wanted all the same, ask the plan; the three extra lines are:
  `:885` `NewPipeDatabase.getInstance(org.schabi.newpipe.App.getApp()).subscriptionDAO() // ENH:crash-fixes-more`,
  `:887` `?: throw java.util.NoSuchElementException("Subscription not found: ${t.subscriptionId}") // ENH:crash-fixes-more`,
  `:902` `).let { disposables.add(it) } // ENH:crash-fixes-more`.

**Why `context == null` and not `!isAdded` (Q9).** `isAdded` is `mHost != null && mAdded`, and
`mAdded` is false for a page that lies under another page on the back stack **[checked:
`FragmentStore.removeFragment`]**, while a feed that is a tab of the main page keeps `mAdded`.
Today a result that arrives for a feed page under another page shows its dialog or snackbar on the
activity and does not crash. `context == null` changes nothing there and drops the result only
for a page that is gone, which is where the crash is.

**Regression risk.** For an attached page both guards are false: no change. For a destroyed page
the "channel not available" dialog and the snackbar for the remaining errors are not shown; they
would have crashed.

**Not fixed, seen while reading.** `:975` and `:990`: the buttons of the dialog use
`requireContext()` / `getString` when tapped. The dialog belongs to the activity and is modal, so
the page cannot be left by Back while it is open; a navigation started from outside (a link, a
notification) that pops the feed page under the open dialog would make "Unsubscribe" crash. This
is after the lookup has returned, so outside AC-7 as written (Q9).

### C8 Errors nobody waits for (AC-8): mechanism confirmed, narrower than the criterion (Q10)

**Today's handler, read line by line** (`App.java:276-339`):
1. `:279-280` logs the class name of what it got.
2. `:282-289` `actual` = the cause if the argument is an `UndeliverableException`, else the
   argument.
3. `:291-296` `errors` = the members if `actual` is a `CompositeException`, else `[actual]`.
4. `:298-306` for each member in order: if its cause chain (itself included, subclasses included,
   `ktx/Throwable.kt:50`) holds `IOException` or `InterruptedException` (`SocketException` and
   `InterruptedIOException` are subclasses), **return**. Else if it holds `NullPointerException`,
   `IllegalArgumentException`, `OnErrorNotImplementedException`, `MissingBackpressureException`
   or `IllegalStateException`: hand **that member** to the thread's uncaught-exception handler
   (`reportException`, `:334-338`) and return.
5. `:310-314` nothing matched: with `isDisposedRxExceptionsReported()` (false in `App`, the debug
   setting `allow_disposed_exceptions_key` in `DebugApp.kt:62`) hand `actual` to the
   uncaught-exception handler; else do nothing (the log line there is commented out).

**Sequence.** A page is left while it loads; `BaseListInfoFragment.java:83-88` (and `:107`,
`:165`, `:194`) disposes the worker. The extraction then fails with an exception that is not of a
bug type itself but has one in its cause chain, for example
`ParsingException("Could not parse .. as a Long", NumberFormatException)`
(`YoutubeStreamExtractor.java:455`; the extractor has 261 `catch (Exception e)` sites, most of
which rethrow like this). `SingleFromCallable` finds itself disposed and calls
`RxJavaPlugins.onError` **[checked]**, which wraps it. Step 4 finds the
`IllegalArgumentException` in the chain: the app closes. The mechanism is certain; that such a
response occurred on the device is not known.

**What the change can and cannot tell apart (Q10).**
- An error that is itself `NullPointerException`, `IllegalStateException`,
  `IllegalArgumentException` (or one of the other four types above) reaches the handler
  **unwrapped whether or not anybody waits**. The handler cannot tell "the page was left" from "a
  callback has a bug". These keep closing the app. AC-8's "whatever caused it" cannot be met for
  them.
- The other way round: an exception of any other type thrown **inside a callback of a live
  subscriber** (`onSuccess`, `onNext`) is also wrapped as undeliverable **[checked:
  `ConsumerSingleObserver.onSuccess`, `LambdaObserver`]**. Today it closes the app if its chain
  holds a bug type and is dropped without a trace otherwise; after the change both are a log line.
- `OnErrorNotImplementedException` (a live subscriber without error handler) is never wrapped
  **[checked: `Functions.OnErrorMissingConsumer`]**: unchanged.

**New file** `app/src/main/java/org/schabi/newpipe/enhancements/crashfixesmore/UndeliveredErrors.kt`
(plain Kotlin, no Android import; uses the RxJava exception classes and the same
`hasAssignableCause` the handler uses, so the chain walk is identical). It must not contain the
text `ENH:crash-fixes-more` anywhere, comments included (the wiring test counts marked lines in
all of `app/src/main`).
```kotlin
package org.schabi.newpipe.enhancements.crashfixesmore

import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import org.schabi.newpipe.ktx.hasAssignableCause
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException

object UndeliveredErrors {
    enum class Action { IGNORE, LOG, REPORT, CRASH }

    const val LOG_TAG = "UndeliveredErrors"
    const val LOG_MESSAGE = "Undelivered error, logged and not rethrown"

    @JvmStatic
    fun decide(throwable: Throwable, reportDisposed: Boolean): Action {
        val undeliverable = throwable is UndeliverableException
        val actual: Throwable? = if (undeliverable) throwable.cause else throwable
        val errors: List<Throwable?> = if (actual is CompositeException) actual.exceptions else listOf(actual)
        for (error in errors) {
            if (error.hasAssignableCause(
                    IOException::class.java, SocketException::class.java,
                    InterruptedException::class.java, InterruptedIOException::class.java
                )
            ) {
                return Action.IGNORE
            }
            if (error.hasAssignableCause(
                    NullPointerException::class.java, IllegalArgumentException::class.java,
                    OnErrorNotImplementedException::class.java, MissingBackpressureException::class.java,
                    IllegalStateException::class.java
                )
            ) {
                return if (undeliverable && !reportDisposed) Action.LOG else Action.CRASH
            }
        }
        return when {
            reportDisposed -> Action.REPORT
            undeliverable -> Action.LOG
            else -> Action.IGNORE
        }
    }
}
```
Meaning of the actions, in terms of today's handler: `IGNORE` = it returns and does nothing more
(step 4 "return", and step 5 with the setting off); `CRASH` = step 4, the member goes to the
uncaught-exception handler; `REPORT` = step 5 with the setting on, `actual` goes to the
uncaught-exception handler; `LOG` = new.

**Decision table.** `U(x)` = `UndeliverableException(x)`; "chain" = the throwable and its causes;
io = `IOException` or `InterruptedException`; bug = the five types of step 4. For a
`CompositeException` the rows are applied to its members in order and the first member that is
"io" or "bug" decides; if none is, the last row group applies.

| Argument | Unwrapped error's chain | setting off: today | setting off: new | setting on: today = new |
|---|---|---|---|---|
| `U(x)` | holds io (even if it also holds a bug type) | IGNORE | IGNORE | IGNORE |
| `U(x)` | holds a bug type, no io | CRASH | **LOG** | CRASH |
| `U(x)` | neither | IGNORE (silent) | **LOG** | REPORT |
| not `U` | holds io | IGNORE | IGNORE | IGNORE |
| not `U` | holds a bug type, no io | CRASH | CRASH | CRASH |
| not `U` | neither | IGNORE (silent) | IGNORE | REPORT |

`decide` returns `LOG` only for an `UndeliverableException` with the setting off, by construction;
for every other input it equals today's handler. Rows the tests must hold, with the setting
off / on:

| # | Argument | off | on |
|---|---|---|---|
| 1 | `U(IOException())`, `U(SocketException())`, `U(InterruptedIOException())`, `U(InterruptedException())` | IGNORE | IGNORE |
| 2 | `U(RuntimeException(InterruptedException()))` | IGNORE | IGNORE |
| 3 | `U(Exception("parse", NullPointerException()))` | LOG (today CRASH) | CRASH |
| 4 | `U(Exception("parse", NumberFormatException()))` | LOG (today CRASH) | CRASH |
| 5 | `U(Exception("parse", IllegalStateException()))` | LOG (today CRASH) | CRASH |
| 6 | `U(Exception(Exception(Exception(NullPointerException()))))` | LOG (today CRASH) | CRASH |
| 7 | `U(RuntimeException("plain"))`, `U(IndexOutOfBoundsException())` | LOG (today silent) | REPORT |
| 8 | `U(Exception(IOException(NullPointerException())))`, `U(Exception(NullPointerException().initCause(IOException())))` | IGNORE | IGNORE |
| 9 | `NullPointerException()`, `IllegalStateException()`, `IllegalArgumentException()`, `MissingBackpressureException()` | CRASH | CRASH |
| 10 | `OnErrorNotImplementedException(RuntimeException())`, `OnErrorNotImplementedException(NullPointerException())` | CRASH | CRASH |
| 11 | `OnErrorNotImplementedException(IOException())` | IGNORE | IGNORE |
| 12 | `QueueOverflowException()`, `RuntimeException("plain")` | IGNORE | REPORT |
| 13 | `IOException()`, `RuntimeException(InterruptedException())` | IGNORE | IGNORE |
| 14 | `CompositeException(RuntimeException(), RuntimeException())` | IGNORE | REPORT |
| 15 | `CompositeException(NullPointerException(), IOException())` | CRASH | CRASH |
| 16 | `CompositeException(IOException(), NullPointerException())` | IGNORE | IGNORE |
| 17 | `CompositeException(RuntimeException(), IOException())` | IGNORE | IGNORE |
| 18 | `CompositeException(RuntimeException(), IllegalStateException())` (the shape of C7's error branch) | CRASH | CRASH |
| 19 | `RuntimeException(U(RuntimeException()))` (a wrapper inside: it is an `IllegalStateException`) | CRASH | CRASH |
| 20 | built by hand, RxJava never does: `U(NullPointerException())`, `U(U(RuntimeException()))`, `U(CompositeException(RuntimeException(), NullPointerException()))` | LOG | CRASH |
| 21 | built by hand: `U(CompositeException(IOException(), NullPointerException()))` | IGNORE | IGNORE |
| 22 | built by hand: `U(CompositeException(RuntimeException(), RuntimeException()))` | LOG | REPORT |

**Edit** (`App.java`, 4 lines inserted after `:280`
`                        + "throwable = [" + throwable.getClass().getName() + "]");`, directly
before the blank line `:281`; no import, no blank line; `Log` is imported at `:10`):
```
                if (org.schabi.newpipe.enhancements.crashfixesmore.UndeliveredErrors.decide(throwable, isDisposedRxExceptionsReported()) == org.schabi.newpipe.enhancements.crashfixesmore.UndeliveredErrors.Action.LOG) { // ENH:crash-fixes-more
                    Log.e(org.schabi.newpipe.enhancements.crashfixesmore.UndeliveredErrors.LOG_TAG, org.schabi.newpipe.enhancements.crashfixesmore.UndeliveredErrors.LOG_MESSAGE, throwable); // ENH:crash-fixes-more
                    return; // ENH:crash-fixes-more
                } // ENH:crash-fixes-more
```
- **Only `LOG` is acted on.** For every other answer the unchanged upstream lines `:282-314` run
  and decide as today. The other three values exist so that the table can be tested; a mistake in
  them cannot change what the app does, and `LOG` cannot be returned for an error that is not
  undeliverable. That is the whole safety argument of this edit.
- The whole wrapper is logged, so the line carries the stack trace of the cause.
  `adb logcat -s UndeliveredErrors:E` finds it; the existing line `:279` still precedes it.
- `isDisposedRxExceptionsReported()` is now read for every error instead of only in step 5. In
  `App` it is `false`; in `DebugApp` it reads a cached preference and may run on any thread.
- The two fully qualified lines are longer than 100 columns, like the parent's in
  `RouterActivity.java`; no check in the build enforces a width.

**Regression risk.** With the debug setting on: none, `decide` never answers `LOG`. With it off:
exactly rows 3-7 change. A live subscriber without error handler, a bug-type error thrown by a
callback, a missing-backpressure error: unchanged.

### Marked lines after the amendment

| File | Marked lines | |
|---|---|---|
| the 10 files of "Verification" | 39 | unchanged, line for line |
| `local/feed/FeedFragment.kt` | 2 | E7a, E7b |
| `App.java` | 4 | C8 |
| **Total** | **45 marked lines in 12 upstream files** | |
| `enhancements/crashfixesmore/UndeliveredErrors.kt` | 0 | new file, the only one in that package in `app/src/main` |

No resources, no settings, no build file changes: unchanged.

### What changes in `CrashFixesMoreWiringTest`

1. Constants: add `FEED_FRAGMENT = "$NEWPIPE/local/feed/FeedFragment.kt"` and
   `APP = "$NEWPIPE/App.java"`. `PLANNED` gains `FEED_FRAGMENT to 2` and `APP to 4` (12 entries).
   `PLANNED_TOTAL` 39 -> **45**. The class comment's "39 lines in 10 upstream files" -> "45 lines
   in 12".
2. `ac6_wiring_onlyThePlannedFilesAreMarked`: no change of logic; it passes with the new map. It
   also proves that `UndeliveredErrors.kt` holds no marked line.
3. `ac6_wiring_noResourcesAndNoOwnClasses`: the resource half is unchanged. The last assertion
   (`assertFalse(.. ownPackage.exists())`) becomes: the directory
   `app/src/main/java/org/schabi/newpipe/enhancements/crashfixesmore` exists and its file list is
   exactly `[UndeliveredErrors.kt]`. The test's name may become
   `ac6_wiring_noResourcesAndOneOwnClass`.
4. `ac6_wiring_filesThePlanLeavesAlone`: add `$NEWPIPE/error/ErrorUtil.kt` and
   `$NEWPIPE/fragments/list/BaseListInfoFragment.java` (0 marked lines each).
5. New `ac7_wiring_feedPage` (`Source("AC-7", FEED_FRAGMENT)`):
   `expectEdit("E7a", listOf("if (context == null) return"), after = "private fun handleItemsErrors(errors: List<Throwable>) {", before = "errors.forEachIndexed { i, t ->")`;
   `expectEdit("E7b", listOf("if (context == null) return"), ..)` cannot be told from E7a by its
   text, and `expectEdit` wants exactly one start. So: `expectCount(2, ..)`, then for the two
   marked indices in order assert the neighbours: first as above; second
   `after = ") {"`, `before = "val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())"`.
   And, for "the lookup is not joined": `expectUntouched` for the three lines the join would
   have changed: `NewPipeDatabase.getInstance(requireContext()).subscriptionDAO()`,
   `?: throw IllegalStateException("Subscription not found: ${t.subscriptionId}")` and
   `return // this will be called on the remaining errors by handleFeedNotAvailable()`.
6. New `ac8_wiring_errorHandler` (`Source("AC-8", APP)`): `expectEdit("C8", listOf(` the four
   lines above, the first two compared in full `), after = "+ \"throwable = [\" + throwable.getClass().getName() + \"]\");")`,
   `expectCount(4, ..)`, and `expectUntouched` for the upstream lines the function mirrors, so
   that a rebase which changes them fails here and the function is revisited:
   `IOException.class, SocketException.class,`;
   `InterruptedException.class, InterruptedIOException.class);`;
   `NullPointerException.class, IllegalArgumentException.class, // bug in app`;
   `OnErrorNotImplementedException.class, MissingBackpressureException.class,`;
   `IllegalStateException.class); // bug in operator`;
   `if (throwable instanceof UndeliverableException) {`;
   `if (actualThrowable instanceof CompositeException) {`;
   `if (isDisposedRxExceptionsReported()) {`; `reportException(error);`;
   `reportException(actualThrowable);`.
7. Every other test of the class, `TabAdapterFinishUpdateTest` and the three instrumented tests:
   unchanged.

### Tests to write

**JVM** (`app/src/test/java/org/schabi/newpipe/enhancements/crashfixesmore/`, JUnit 4, no new
dependency; `android.util.Log` is not touched by `decide`):
- **T1 `UndeliveredErrorsTest`**, one test per row group, named `ac8_..`: rows 1-22 with the
  setting off and on. Rows 3-7 are "the one change"; all others are "as today".
- **T2, in the same class: today's handler written out a second time in the test** (steps 2-5
  above, returning IGNORE / CRASH / REPORT), and a generated matrix (every exception type of the
  table, plain, as cause at depth 1 and 2, as first and second member of a `CompositeException`,
  wrapped in `U` and not, setting off and on) with the assertion: `decide == today`, except
  `decide == LOG` exactly where the argument is an `UndeliverableException`, the setting is off
  and `today != IGNORE-by-io`, that is where today's step 4 did not return for an io cause.
- **T3 `UndeliveredErrorsRxTest`: what RxJava really hands over** (real RxJava on the JVM;
  `RxJavaPlugins.setErrorHandler` with a recorder in `@Before`, `RxJavaPlugins.reset()` in
  `@After`; no scheduler, everything synchronous):
  - `RxJavaPlugins.onError(Exception("x", NullPointerException()))` -> the recorder gets an
    `UndeliverableException` whose cause is that exception; `decide(.., false) == LOG`.
  - for each of `NullPointerException`, `IllegalStateException`, `IllegalArgumentException`,
    `CompositeException`, `OnErrorNotImplementedException`, `MissingBackpressureException`,
    `QueueOverflowException`: `RxJavaPlugins.onError(e)` -> the recorder gets the same instance.
  - nobody waits: `Single.create<Int> { saved = it }.subscribe({}, {})`, dispose, then
    `saved.onError(Exception("late", NumberFormatException()))` -> wrapped; `LOG` / `CRASH`.
    The same with `saved.onError(NullPointerException())` -> unwrapped; `CRASH` (the limit of Q10).
  - a receiver without handler: `Single.error<Int>(RuntimeException("x")).subscribe { }` ->
    `OnErrorNotImplementedException`, unwrapped; `CRASH` with the setting off and on.
  - a live callback that throws: `Single.just(1).subscribe({ throw IndexOutOfBoundsException() }, {})`
    -> wrapped; `LOG` (the risk named below, pinned so that it is not a surprise);
    `{ throw NullPointerException() }` -> unwrapped; `CRASH`.
- **Wiring**: as listed above.
- Optional, if the Tests layer finds it worth the machinery: run the real compiled handler (the
  anonymous `Consumer` in `App`) under a class loader with a recording `android.util.Log`, as
  `TabAdapterFinishUpdateTest` does, and compare it with `decide` over the matrix of T2. Without
  it, "equal to today's handler" rests on T2's copy and on the wiring test's untouched lines.

**Instrumented** (by hand, package `...enhancements.crashfixesmore`, existing dependencies and
`MainActivityPages.kt`). Both use the fact that the app's handler calls
`Thread.currentThread().getUncaughtExceptionHandler()`: a test sets a recording handler on the
thread concerned (`Thread.setUncaughtExceptionHandler`) and restores the old one, so a failure is
an assertion with a stack trace and not a dead process.
- **I3 `FeedPageLateLookupTest.ac7_pageLeftBeforeLookupReturns_nothingThrown`**: on the test
  thread insert a `SubscriptionEntity` through `subscriptionDAO()` and keep its uid;
  `showPage(FeedFragment.newInstance())`; set the recorder on the main thread; then **in one
  main-thread turn**: by reflection call the private `handleItemsErrors(java.util.List)` with
  `listOf(FeedLoadService.RequestException(uid, "0:url", ContentNotAvailableException("gone")), RuntimeException("other"))`,
  then `supportFragmentManager.popBackStackImmediate()` and assert `page.context == null`. Then
  `letFramesRun(2000)`. Expect: the recorder is empty. Today the recorder gets
  `IllegalStateException .. not attached to a context` on the success branch, or a
  `CompositeException` member from `ErrorUtil.showSnackbar` on the error branch; the second list
  entry is there so that **both** branches fail today, whichever the io thread takes. Second
  test, control, `ac7_pageStillThere_dialogIsShown`: the same without the pop; expect the
  recorder empty and, as far as it can be read without a UI library, no exception; the dialog
  itself is checked in D7.
- **I4 `UndeliveredErrorHandlerTest`** (the real handler of the debug app): set the preference
  `allow_disposed_exceptions_key` to false (restore afterwards), recorder on the test's own
  thread, then from that thread:
  `RxJavaPlugins.onError(Exception("x", NullPointerException()))` -> recorder empty
  (`ac8_undeliverable_isNotRethrown`; today: one entry);
  `RxJavaPlugins.onError(OnErrorNotImplementedException(RuntimeException("x")))` -> one entry,
  that exception (`ac8_receiverWithoutHandler_closesAsToday`);
  `RxJavaPlugins.onError(NullPointerException())` -> one entry;
  with the preference true: the first call -> one entry
  (`ac8_debugSettingOn_asToday`).

**Device script.**
- **D7** (AC-7) Subscribe to a channel that has been deleted or terminated, put it in a group,
  refresh the group. Control: staying on the page, the "could not load feed" dialog for that
  channel appears, as before. Then: refresh again and press Back in the moment the progress ends;
  repeat ten times. No crash; when the page was left in time, no dialog appears on the page
  behind. The window cannot be hit reliably by hand: I3 is the check.
- **D8** (AC-8) Release-like behaviour, debug setting "report out-of-lifecycle errors" off: open
  twenty channels, playlists and searches on a slow network and press Back while each loads;
  refresh a large group meanwhile. No crash. Afterwards `adb logcat -d -s UndeliveredErrors:E`:
  every entry is a load that was left; note each cause in the pull request. Then switch the
  setting on and repeat five times: an undeliverable error may now close the app with the report
  screen, as before the change.

### AC coverage (amendment)

| AC | Fix | Unit test | Instrumented / device |
|---|---|---|---|
| AC-7 | E7a, E7b (criterion as in Q9) | `ac7_wiring_feedPage` | I3, D7 |
| AC-8 | `UndeliveredErrors.decide`, 4 lines in `App.java` (criterion as in Q10) | T1, T2, T3, `ac8_wiring_errorHandler` | I4, D8 |
| AC-6 | no resource, no setting; with the debug setting on, AC-8 changes nothing | `ac6_wiring_*` as changed above | controls in D7, D8 |

### Order of work (amendment)

After the test commit: 9. `UndeliveredErrors.kt` and the 4 lines in `App.java`, one commit (the
lines do not compile without the file). 10. The 2 lines in `FeedFragment.kt`. Each can be
reverted alone. 11. I3, I4, D7, D8; results in the pull request.

### Merging with the other branches (amendment)

Checked with `git diff origin/dev...origin/feature/<name> -- <file>` for all 13 feature branches.

| File | Other branch and its hunks | Ours | Textual | Behaviour |
|---|---|---|---|---|
| `local/feed/FeedFragment.kt` | `subscription-scale`: 1 line after `:245`, 1 after `:253`. `queue-guard` / `default-open-action`: `:331`, `:338`, `:347`, `:349` changed, 1 line after `:781`. `release-views`: none | after `:879`, after `:964` | no conflict: 98 unchanged lines between `:781` and `:879` | `subscription-scale` delivers a refresh that ended off screen at the next return to a feed page: more moments in which a lookup starts, same window; the guards do not depend on it |
| `App.java` | `backup-safety`: 1 line after `:107`. `tls-checks`: 1 line after `:131`, 1 after `:134` | after `:280` | no conflict: 145 unchanged lines between | independent |
| `error/ErrorUtil.kt`, `fragments/list/BaseListInfoFragment.java` | not edited by this plan | | | |

In the merged build the lines move (the investigation's `:882`, `:968` are these); the wiring test
finds them by their neighbours, not by number.

### Checked and assumed (amendment)

| Statement | Status |
|---|---|
| `RxJavaPlugins.onError` wraps in `UndeliverableException` unless `isBug`; `isBug` = the seven types named in A0 | checked (rxjava 3.1.12) |
| Only `RxJavaPlugins` constructs `UndeliverableException`; it extends `IllegalStateException` | checked |
| If the handler throws, RxJava hands that to the thread's uncaught-exception handler | checked |
| `SingleFromCallable`: a throwing callable goes to the observer if not disposed, else to `RxJavaPlugins.onError` | checked |
| `ConsumerSingleObserver.onSuccess`: a throwing callback -> `RxJavaPlugins.onError(e)`; `onError`: -> `RxJavaPlugins.onError(CompositeException(original, e))`; the same in `LambdaObserver.onError` | checked |
| Disposing the outer disposable reaches the callable's disposable through `ObserveOnSingleObserver` and `SubscribeOnObserver` | checked |
| `Single.subscribe(Consumer)` uses `Functions.ON_ERROR_MISSING`, which calls `RxJavaPlugins.onError(OnErrorNotImplementedException(e))` | checked |
| `SingleCreate.Emitter.onError` after dispose -> `RxJavaPlugins.onError` (test T3) | checked |
| A disposed main-thread delivery is removed from the handler queue | checked (rxandroid 3.0.2, `ScheduledRunnable.dispose`); not relied on by the fix |
| `Fragment.getContext()` is null iff `mHost` is null; `requireContext()` throws exactly then; `isAdded` also needs `mAdded`, cleared for a page under another page | checked (fragment 1.8.9) |
| `onDestroy` and detach run on the main thread, in that order | assumed (as in C2a above) |
| An `AlertDialog` built on the activity can be shown while the fragment's view is gone | assumed; this is today's behaviour and is not changed |
| A `const val` and a `@JvmStatic fun` of a Kotlin `object`, and its nested enum, are reachable from Java as written in the 4 lines | not compiled; `LateDialog.canShow` of the parent is called the same way |
| The extractor produces non-bug exceptions with a bug-type cause | read (`YoutubeStreamExtractor.java:455`, `:679`); that it happened on the device: not known |

### Risks (amendment)

- **What a swallowed undeliverable error can hide.** (1) A callback of a live subscriber that
  throws an exception of a non-bug type whose chain holds a bug type (for example a
  `RuntimeException` wrapping a `NullPointerException`, thrown inside `onSuccess`): today a crash
  with a report, afterwards a log line and a page that stays as it was, typically still
  "loading". RxJava gives the handler no way to tell this from a load that was left (T3 pins the
  case). (2) A failure inside a pipeline after its page was left that points at a real defect of
  the extractor or the database: no crash report any more. Errors of that kind that are a plain
  `NullPointerException` and the like still close the app (Q10), so the worst offenders stay
  visible.
- **How it is found.** One `Log.e` per swallowed error with the fixed tag `UndeliveredErrors`,
  the fixed text `Undelivered error, logged and not rethrown` and the full stack trace including
  the cause chain: `adb logcat -s UndeliveredErrors:E`. The debug setting "report out-of-lifecycle
  errors" turns the old behaviour back on without a new build; D8 has both runs. The log is lost
  with the log buffer: a user who reports "the page stayed empty" has no report to attach.
- **More log lines than before.** Row 7 was silent; a cancelled load that fails with, say, a
  `ParsingException` now writes a stack trace. Cancelled network calls (io) stay silent.
- **AC-8 is met only for errors RxJava marks undeliverable** (Q10). An extractor
  `NullPointerException` thrown directly after its page was left still closes the app.
- **AC-7 without the join** leaves the lookup running after the page is gone, for one database
  read; it holds the page that long. Its result ends in a guard.
- **AC-7 is derived, not observed**, and it may not be the crash of issue #26: that one has no
  log. I3 shows the defect and the fix; it does not show what happened on the device.
- **The copy in `decide` can age.** If upstream changes the handler's lists, `decide` no longer
  mirrors it. Because only `LOG` is acted on, the effect is limited to which undeliverable errors
  are logged instead of handled by upstream's code; the wiring test's untouched lines fail on
  such a rebase.
- **Rebase:** 12 upstream files, 45 lines, all marked, names fully qualified, no import hunks; one
  new file.
