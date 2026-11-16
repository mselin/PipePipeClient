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
