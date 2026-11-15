# Plan: release views when a screen's view is destroyed

Spec: `spec.md` (AC-1..AC-8). Paths below are relative to
`app/src/main/java/org/schabi/newpipe/` unless they start with `app/` or `enhancements/`.
Open questions this plan depends on: Q2, Q3, Q4 in `questions.md`; each states the assumption
used here.

## 1. Findings checked against the code

| Finding | Holds? | What the code shows |
|---|---|---|
| M1 | yes | `fragments/list/channel/ChannelFragment.java:171` and `ChannelVideosFragment.java:223-225` null their bindings only in `onDestroy`. Pages are stacked with `replace` + `addToBackStack` and no de-duplication (`util/NavigationHelper.java:455-462`), so every left channel keeps its view. |
| M2 | yes, and wider | The adapter is created once (`fragments/list/BaseListFragment.java:219-226`) and `onDestroyView` (`:265-275`) neither detaches it nor clears `itemsList`. Each `setAdapter` (`:441`) registers the new list as observer of the old adapter and nothing unregisters it, so the adapter keeps every earlier list. The same mechanism exists in three places M2 does not name: the suggestion list of search, the Subscriptions tab, the main page's tab pager (section 2). |
| M3 | yes, with a correction | `util/PicassoHelper.java:204-206` has no size limit. But `loadBanner` is not only the channel banner: `util/ImageViewerActivity.kt:158` uses it as "full size" for comment pictures. `loadOrigin` (`:228`) is also unlimited. Limiting `loadBanner` itself would blur the zoomable picture viewer (against AC-7), so the limit goes to the one channel header call. |

One statement in the spec does not match the code: local playlists, history and bookmarks do
not release their views completely (Q3).

## 2. Confirmed inventory

"Kept alive as a left page" means: the object survives `onDestroyView`. Three ways exist:
**B** back stack of `R.id.fragment_holder` (every `NavigationHelper.open*Fragment`);
**T** tab of the main page: the tab fragments stay in the child fragment manager and are reused
when the main page's view is rebuilt (`fragments/MainFragment.java:193-200`, same adapter
instance returns the same fragments); **C** tab of a channel page: same, found again by tag
(`fragments/detail/TabAdapter.java:127-136`); a channel tab two swipes away is even removed and
re-added as the same instance (`TabAdapter.java:139-143`), with its adapter.

| Class | Left as | Retains after `onDestroyView` today |
|---|---|---|
| `fragments/BaseStateFragment` (base of all below except `MainFragment`, `ChannelInfoFragment`) | - | `emptyStateView` `:28`, `loadingProgressBar` `:30`, `errorPanelHelper` `:32`, which holds `rootView` (`error/ErrorPanelHelper.kt:42`). Any one of them keeps the whole view tree. |
| `fragments/list/BaseListFragment` (base of the next seven) | - | `itemsList` `:76`; `infoListAdapter` `:75` keeps every earlier list as observer and the header view through its header supplier (`:438`, suppliers are `headerBinding::getRoot`). Grows with every rebuild. |
| `list/channel/ChannelVideosFragment` | C, T, (B via parent) | `channelBinding`, `headerBinding`, `playlistControlBinding` `:104-106`, `addToGroupButton` `:107`, `subscribeButtonMonitor` `:90` (a click stream that holds the subscribe button). Three database monitors per `handleResult` (`:257-290`) stay subscribed and write to the header. |
| `list/channel/ChannelTabFragment` | C | only what the two bases retain |
| `list/kiosk/KioskFragment`, `DefaultKioskFragment` | B, T | only bases |
| `list/playlist/PlaylistFragment` | B, T | nulls its bindings (`:192-209`), but the adapter's header supplier still holds the header; bases |
| `list/videos/RelatedItemsFragment` | tab of the video page | same as playlist (`:63-66`) |
| `list/comments/CommentsFragment` | B inside `CommentsFragmentContainer` (`:103-105`, replies replace the list) | `emptyStateDesc` `:33`; bases |
| `list/search/SearchFragment` | B | nulls `searchBinding` (`:412`), but `suggestionListAdapter` (created once, `:294`) is never detached from `suggestionsList` (`:450`) and keeps every earlier suggestion list, hence the root. `searchEditText` etc. (`:471-475`) are the activity's toolbar views, not page views. |
| `list/channel/ChannelFragment` | B | `binding` `:83`, `tabAdapter` `:84` (new per view, `:137`; holds the old pager as observer); base |
| `list/channel/ChannelInfoFragment` | C | `binding` `:39`, never cleared |
| `fragments/MainFragment` | B (always the bottom page) | `binding` `:53`; `pagerAdapter` `:54` is reused and keeps the old pager (its single pager observer) and every earlier tab bar (one observer each, never unregistered). Grows with every rebuild. |
| `local/subscription/SubscriptionFragment.kt` | T, default tab | `_binding` `:44` never cleared, no `onDestroyView`; `groupAdapter` `:54` lives as long as the fragment and is never detached (`:255`). Grows with every rebuild; this is the list the owner returns to between channels. |
| `local/BaseLocalListFragment` (bookmarks, history, local playlist) | B, T | `headerRootBinding`, `footerRootBinding` `:70-71`; base. One tree, does not grow. |
| `local/feed/FeedFragment.kt`, `SubscriptionListFragment.kt`, `detail/VideoDetailFragment.kt` | B / T / player | clear their own references; only the `BaseStateFragment` fields remain |

## 3. Shape of the change

Direct, marked edits in upstream lifecycle methods. No new package, no new file under
`app/src/main`, no resources.

- A helper in `enhancements/releaseviews/` cannot do the work: the references are private fields
  of upstream classes; only code inside those classes can clear them. A helper would wrap single
  statements (`list.setAdapter(null)`) and add indirection without removing one upstream line.
- Every added or changed upstream line ends with `// ENH:release-views`. Changed lines are
  replaced one for one. `check-layers.sh` therefore passes unchanged (all added lines marked, no
  file loses more lines than it gains). What does not fit is the wording in `PROCESS.md` that a
  hook is a call into `enhancements/` (Q2).
- Size: about 55 added and 5 changed lines in 10 upstream files (9 Java, 1 Kotlin).

## 4. The change per class

Rule used throughout: data stays (adapters with their items, `currentInfo`, next page, saved
state); views go. Fragment saves the view hierarchy state before `onDestroyView` runs, so
detaching in `onDestroyView` does not lose the scroll position; `FeedFragment.kt:483` and
`BaseLocalListFragment.java:249` already rely on this.

### 4.1 `BaseStateFragment`
- `onDestroyView` (`:83-88`), after `dispose()`: `errorPanelHelper = null`, `emptyStateView = null`,
  `loadingProgressBar = null` (3 lines).
- Guards (4 changed lines): `:185` and `:199` call the helper only if it is not null; `:203` same;
  `:208` returns `errorPanelHelper != null && errorPanelHelper.isVisible()`. The two view fields
  are already null-checked everywhere (`:120-168`).
- Late paths now safe for every subclass: `showError`/`showTextError` from a failed load,
  `showLoading`/`hideLoading`/`showEmptyState` (all go through `hideErrorPanel`). `showError`
  still stores `lastPanelError` (`:186`), and `onResume` (`:65-67`) shows it on the new view,
  exactly as today. The retry click is debounced 300 ms but disposed with the helper.

### 4.2 `BaseListFragment`
- `onDestroyView` (`:265-275`), after the existing lines and before `super`:
  `itemsList.setAdapter(null)` (unregisters this list from the reused adapter, AC-3), then
  `infoListAdapter.setHeaderSupplier(null)` (its `notifyDataSetChanged` now has no observer),
  then `itemsList = null`. The adapter and its items stay: `writeTo` (`:327-334`) and
  `generateSuffix` (`:310`) read them for a left page when the activity saves state.
- AC-4 with the reused adapter: `initViews` (`:430-441`) already sets layout manager, header
  supplier and adapter on every new view, in that order, before the saved view state is
  applied; the adapter has its items, so the list restores its position on first layout.
  `doInitialLoadLogic` finds `currentInfo` and does not load
  (`BaseListInfoFragment.java:136-145`); `handleResult` does not add items twice (`:258`).
  The footer supplier captures only the fragment and is kept, so the filter footer returns.
- Guard: `restoreFocus` (`:353`) posts a runnable that reads `itemsList`; first line of the
  runnable becomes a null check (1 line). The other two posted runnables already check
  (`:126`, `:832`).
- Access paths while left, and why they cannot run:
  - load results: `BaseListInfoFragment.onPause` (`:83-88`) and `SearchFragment.onPause`
    (`:355-368`) dispose every worker; `onResume` restarts what was loading. So
    `handleResult`, `handleNextItems`, `showListFooter`, `hideLoading` (all touch `itemsList`)
    are never called between `onPause` and the next `onResume`.
  - `onStop` and `writeTo` read the focused row through `getFocusedPosition`, which catches the
    `NullPointerException` (`:315-324`).
  - `onSharedPreferenceChanged` only sets a flag (`:853-866`); `RelatedItemsFragment` checks its
    binding (`:158`). Selection listener and toolbar are already released (`:270`, `:261`).
  - row clicks need an attached view; their handlers touch no view.
  - images: Picasso holds its target views weakly; a late bitmap for a detached row is dropped.
- Covers without own edits: `ChannelTabFragment`, `KioskFragment`, `DefaultKioskFragment`,
  `PlaylistFragment`, `RelatedItemsFragment`.

### 4.3 `ChannelVideosFragment`
- New `onDestroyView`, before `super`: `disposables.clear()`; dispose `subscribeButtonMonitor`;
  null `channelBinding`, `headerBinding`, `playlistControlBinding`, `addToGroupButton` (10 lines).
- Why clearing instead of guarding: the monitors are the only late path into the header
  (`:259`, `:370`, `:377`, `:388-417`, `:424`). `handleResult` already clears and re-creates them on
  every view build (`:698-703`), so a left page loses nothing: on return the new monitor emits the
  current state and the button shows it (AC-5, "shown when the page is returned to"). The
  "you subscribed" snackbar for a left page is dropped; today it targets a detached list and
  is not shown either.
- Other header accesses (`setTitle` `:746`, `showLoading` `:645`, `handleResult` `:652-713`) run
  only from `initViews` or a load result, see 4.2. `showContentNotSupportedIfNeeded` checks
  (`:719`). `menuSortButton`/`menuFilterButton` are toolbar menu items, not page views; kept.

### 4.4 `ChannelFragment`
- New `onDestroyView`: `lastTab = binding.tabLayout.getSelectedTabPosition()`; `binding = null`;
  `tabAdapter = null`; `super` (7 lines). The pager's adapter is **not** set to null: that would
  call `TabAdapter.destroyItem` and remove the tab fragments with their data.
- `writeTo` `:381`: `objectsToSave.add(0)` becomes `add(lastTab)` (1 changed line), so a channel
  left and then saved (rotation, process death) keeps its tab as today, where the stale binding
  answered. `onSaveInstanceState` `:145` already falls back to `lastTab`.
- Late load result: `currentWorker` is not disposed on pause here, so a result can arrive for a
  left page and `updateTabs` (`:317-364`) would touch `binding` and `tabAdapter`. In the
  subscriber (`:417-420`), after `isLoading.set(false)`: if `binding == null`, store
  `currentInfo = result` and return (1 line). On return `doInitialLoadLogic` (`:392-398`) calls
  `handleResult(currentInfo)`: shown without a second request, as today. A late error goes
  through 4.1.
- `subscriptionMonitor` (`:258-260`) touches only the toolbar menu item; unchanged.
- Selected tab on return: restored by the pager's saved view state after `updateTabs` selected
  `lastTab`; both now name the same tab.

### 4.5 `ChannelInfoFragment`, `CommentsFragment`, `SearchFragment`
- `ChannelInfoFragment`: new `onDestroyView` with `binding = null` (5 lines). `binding` is used
  only during `onCreateView` (`:70-75`); the linkify result holds the text view itself.
- `CommentsFragment`: new `onDestroyView` with `emptyStateDesc = null` (5 lines). Used only in
  `handleResult` (`:148`), see 4.2.
- `SearchFragment.onDestroyView`, before `searchBinding = null` (`:412`):
  `searchBinding.suggestionsList.setAdapter(null)` (1 line). Suggestion results are disposed in
  `onPause` (`:363-365`); `initViews` attaches the adapter again (`:450`).

### 4.6 `MainFragment`, `SubscriptionFragment`, `BaseLocalListFragment` (scope: Q3)
- `MainFragment`: the reused `pagerAdapter` must let go of the old pager and tab bar without
  destroying the tab fragments. `SelectedTabsPagerAdapter` gets a field `keepItems` and a
  `destroyItem` override that calls `super` only when it is false (5 lines). New
  `onDestroyView`: set `keepItems`, `binding.pager.setAdapter(null)` (the pager drops its
  observer and tells the tab bar, which unregisters its own), unset `keepItems`,
  `binding = null`, `super` (8 lines). On return `setupTabs` (`:193-200`) attaches the same
  adapter and `instantiateItem` hands back the same fragments
  (`app/src/main/java/androidx/fragment/app/FragmentStatePagerAdapterMenuWorkaround.java:157-161`);
  the selected tab comes from the pager's saved state. `onDestroy` (`:143-146`) then finds
  `binding == null`; the child fragment manager destroys the tabs. The tabs listener checks
  `isResumed()` (`:80`).
- `SubscriptionFragment.kt`: new `onDestroyView`: `binding.itemsList.adapter = null`,
  `_binding = null`, `super` (5 lines). All observers use `viewLifecycleOwner` (`:258-260`);
  `onPause` reads `binding` before the view is destroyed (`:105`); the broadcast receiver uses
  `_binding?` (`:156`). Scroll position is saved by the fragment itself (`:105`, `:386`).
- `BaseLocalListFragment.onDestroyView` (`:243-251`): `headerRootBinding = null`,
  `footerRootBinding = null` (2 lines). The header is null-checked at `:400`, `:411`, `:459`; the
  footer is used only in `initViews`.

## 5. Banner size (AC-6)

`ChannelVideosFragment.java:655-656`, one added line between `tag(...)` and `into(...)`:
`.fit().centerCrop().onlyScaleDown()`. `PicassoHelper` is not changed (section 1, M3).

- `fit()` makes Picasso decode and scale to the size of the banner view (full width, 70 dp,
  `app/src/main/res/layout/channel_header.xml:14-22`), which is never larger than the screen. A
  2560 x 424 banner becomes about 1080 x 193 on a 1080 px wide phone.
- Same look: the view already shows the image with `centerCrop`. Picasso's `centerCrop` picks the
  same scale and the same centred region for the same target size, so the view then draws the
  result one to one. `onlyScaleDown` leaves a banner smaller than the view unscaled, and the view
  enlarges it as today. The view cannot change size without being rebuilt (`MainActivity` has no
  `configChanges`, `app/src/main/AndroidManifest.xml:65-67`), and a rebuild loads again.
- Placeholder and error drawable are set as before. With images disabled the request has no
  image and Picasso returns before the deferred path.

## 6. Verification

**JVM unit tests: none.** The change contains no decision logic; it clears references in Android
lifecycle methods. Without Robolectric a JVM test cannot run a fragment, a `RecyclerView` or
Picasso, and a test that greps the source for `= null` proves nothing. `ScaffoldingTest` stays the
only JVM test (Q4).

**Instrumented tests** (`app/src/androidTest/java/org/schabi/newpipe/enhancements/releaseviews/`,
existing dependencies only, on a device or emulator, no network needed). They host fragments in
`MainActivity` (`ActivityScenario`) with `replace(R.id.fragment_holder, ...)` + `addToBackStack`,
like `NavigationHelper`. Shared helper `detachedViewsHeldBy(fragment)`: walks the instance fields
from the fragment's class up to `BaseFragment`; reports a `View` or `ViewBinding` root that is not
attached to a window, and a `RecyclerView.Adapter` with `hasObservers()`.

| Test | AC | What it does. Fails today because |
|---|---|---|
| `ac1_leftSearchPage_holdsNoDetachedView` | 1 | `SearchFragment.getInstance(0, "")`, push a second page. `itemsList`, both adapters, base fields |
| `ac2_leftChannelVideosPage_holdsNoDetachedView` | 2 | `ChannelVideosFragment.getInstance(info)` with a hand-built `ChannelInfo` and 40 `StreamInfoItem`s (no request is made). Bindings |
| `ac2_leftChannelPage_holdsNoDetachedView` | 2 | `ChannelFragment.getInstance(...)`, leave at once. `binding`, `tabAdapter`; result of the load does not matter |
| `ac3_viewRebuiltFiveTimes_earlierViewsAreCollected` | 3 | leave and return five times, weak reference to each root view, GC with retries, all cleared. One page each for search, channel videos, and (Q3) the main page |
| `ac4_itemsAndPositionSurviveReturn` | 4 | channel videos page as above, scroll to item 20, leave, return: same item count, same first visible item. Passes today; guards the fix |
| `ac5_subscriptionChangeWhileLeft_isShownOnReturn` | 5 | same page left, insert its subscription with `SubscriptionManager`, idle (no crash), return: button reads "subscribed". Today the monitors write into the detached header |
| `ac6_bannerBitmapIsNotLargerThanItsView` | 6 | banner url `android.resource://<package>/drawable/channel_banner` (2550 x 427, `res/drawable-nodpi`), images enabled; loaded bitmap is at most the view's width and height. Today 2550 x 427 |

Not covered by them and why: AC-5 late load result of `ChannelFragment` and late images (need a
real slow request), AC-8 (whole app), the look of AC-6, AC-7. CI runs only JVM tests, so these
run by hand: `./gradlew :app:connectedDebugAndroidTest -DskipFormatKtlint`.

**Device script** (debug build; Settings > Debug: allow heap dumping, LeakCanary watches 10 s,
`app/src/debug/java/org/schabi/newpipe/DebugApp.kt:22`).

1. Baseline on the build before the change, steps 2-3: LeakCanary must report traces through
   `BaseListFragment.itemsList`, `InfoListAdapter`, `ChannelFragment.binding`. This proves the
   script detects the defect.
2. AC-8: Subscriptions tab > open a channel > tap a video > in the video page tap the uploader
   > Back. Repeat 20 times, with different channels. Start a queue playing once and leave it.
3. Wait 30 s, open "Leaks", "Dump heap now", wait for the analysis. Expected: no leak whose
   leaking object is a view of a fragment, and none through a class of section 2.
4. AC-4, per page type: search results; main tab with trending; Subscriptions tab; channel on
   tab Videos, on a second tab, on Info; remote playlist; comments > replies; bookmarks. For
   each: load a second page of items, scroll so a known item is first, note the selected tab,
   open a channel from it (for comments: open replies), **switch on airplane mode**, Back.
   Expected: same items, same first item, same tab, no spinner and no error panel.
5. AC-4 across state saving: with a channel on tab 2 left under another page, rotate, Back: tab 2.
   Repeat step 4 once for a channel with "Don't keep activities" on (airplane mode off).
6. AC-5: (a) open a channel and, before it has loaded, open search from the toolbar; wait 10 s;
   Back: channel shown, no crash. (b) channel A > video of A > uploader (A again) > Subscribe >
   Back twice: the first page of A reads "Subscribed". (c) fling a list and at once open a
   channel; repeat 10 times. (d) with five channels left, subscribe and add to a group on the top
   one.
7. AC-6: screenshot of the same three channel headers before and after, portrait and landscape,
   compared side by side; Android Studio heap dump: the banner view's bitmap has the view's size.
8. AC-7: the comment picture viewer still zooms sharply; no new setting or text.

## 7. AC coverage

| AC | Covered by |
|---|---|
| AC-1 | 4.1, 4.2, 4.5 (comments, search), 4.6 (local lists) |
| AC-2 | 4.1, 4.2, 4.3, 4.4, 4.5 (info tab) |
| AC-3 | 4.2 and 4.5 (list unregistered from the reused adapter), 4.4 (`tabAdapter`), 4.6 (pager, Subscriptions) |
| AC-4 | data kept; order in `initViews` unchanged; `lastTab` (4.4); `keepItems` (4.6); script 4-5 |
| AC-5 | guards 4.1, 4.2, 4.4; monitors cleared 4.3; Picasso's weak targets; script 6 |
| AC-6 | section 5; test `ac6_...`; script 7 |
| AC-7 | no resources, no settings; `PicassoHelper` untouched; script 8 |
| AC-8 | everything above together; script 1-3 |

## 8. Order of work

Each step is one `code(release-views):` commit that builds and is safe alone.

1. `BaseStateFragment` (4.1). Releases nothing visible alone, but makes the base null-safe.
2. `ChannelVideosFragment`, `ChannelInfoFragment`, `CommentsFragment`, `SearchFragment` (4.3, 4.5).
   Before step 3 on purpose: the channel monitors use `itemsList` (`:424`) and must be gone
   before the base clears it.
3. `BaseListFragment` (4.2). From here the instrumented tests for AC-1, AC-3, AC-5 pass.
4. `ChannelFragment` (4.4).
5. Banner (section 5).
6. `SubscriptionFragment.kt`, `BaseLocalListFragment`, then `MainFragment` (4.6), each its own
   commit; skipped if Q3 is answered "out of scope".
7. Device script, complete; LeakCanary result noted in the pull request.

## 9. Risks

- **A path that reaches a cleared reference was missed.** The argument in 4.2 rests on "no load
  result between `onPause` and `onResume`". A subclass that subscribes elsewhere breaks it; the
  ones found are handled (4.3, 4.4). Mitigation: script 6 and "Don't keep activities".
- **`MainFragment` (4.6) is the most delicate step**: if `keepItems` were wrong, tabs would be
  destroyed and reloaded. It is last and separate so it can be dropped or reverted alone.
- **Blocked channel, late result** (4.4): today a left page whose late result is a blocked
  channel pops the page on top of it; after the change the check runs when the user returns.
- **Banner**: `fit()` waits for the header to be laid out; a header never laid out never loads,
  which is harmless. A different resampling path could look minimally softer; script 7 decides.
- **GC-based tests** can fail spuriously; they retry and are backed by LeakCanary.
- **Rebase**: ten upstream files touched; all lines are marked and sit in or next to lifecycle
  methods upstream rarely edits.
- **Residual, accepted**: the Subscriptions tab's group carousel item keeps its small inner list
  (`local/subscription/item/FeedGroupCarouselItem.kt:33`); it is detached from the page's root.

Not touched, and why: `PicassoHelper` and `ImageViewerActivity` (section 1); `InfoListAdapter`,
`TabAdapter`, `ErrorPanelHelper` (their owners let go of them); `KioskFragment`,
`DefaultKioskFragment`, `ChannelTabFragment`, `PlaylistFragment`, `RelatedItemsFragment`,
`FeedFragment`, `SubscriptionListFragment`, `VideoDetailFragment`, `BookmarkFragment`,
`LocalPlaylistFragment`, `StatisticsPlaylistFragment` (fixed through their bases; their own
references are already cleared); `CommentsFragmentContainer` (holds no view); `NavigationHelper`
(how many pages pile up is "not covered"); toolbar views and menu items held by `SearchFragment`,
`ChannelFragment`, `ChannelVideosFragment`, `PlaylistFragment` (they belong to the activity).
