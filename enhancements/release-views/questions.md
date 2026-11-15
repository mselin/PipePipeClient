# Questions: release-views

## Q1 spec -> user
The code review points to left pages keeping their views as the likely cause of the
out-of-memory crash. Fix it?

## A1 user
"I may be an edge case as an user, since have 1600 channels subscribed, so would like to have
that fixed."
Applied: this spec.

## Q2 plan -> spec
For the process owner. This change cannot be done with hooks as `PROCESS.md` defines them (one
line that calls into `enhancements/`): the references to release are private fields of upstream
classes, so the lines that clear and guard them must be inside those classes. The plan uses about
55 added and 5 changed lines in 10 upstream files, every one ending in `// ENH:release-views`,
changed lines replaced one for one, no new file under `app/src/main`. `check-layers.sh` passes
this unchanged (it checks the marker and that no file loses more lines than it gains). Only the
wording of `PROCESS.md` ("Keeping upstream untouched", definition of done item 4) does not allow
it. May a defect fix use marked lines that are the fix itself, not calls into `enhancements/`?
Planned with: yes, for this change; the script stays as it is.

## Q3 plan -> spec
Three things in the code differ from the spec's "Today" and decide whether AC-8 can be met.
(1) The main page (`MainFragment`) keeps its view while left, and its reused tab adapter keeps
one more old tab bar per return. It is under every other page, so the AC-8 walk always leaves it.
(2) The Subscriptions tab (`SubscriptionFragment`) never releases its view and its list adapter
keeps one more old list per return; it is the list the owner returns to between channels.
(3) Local playlists, history and bookmarks do not "already release their views correctly": the
shared base keeps the whole view through its error panel, and the local base keeps its header
and footer. Without these three LeakCanary still reports retained views of left pages in the
AC-8 walk. Are they in scope?
Planned with: yes, as the last, separate steps (plan 4.6, 13 + 5 + 2 lines); steps 1 to 5 do not
depend on them.

## Q4 plan -> spec
For the process owner. No acceptance criterion of this change can be tested honestly on the JVM:
there is no decision logic, and without Robolectric a fragment, a list or an image load cannot
run there. The plan gives instrumented tests (existing dependencies, a device or emulator, no
network) for AC-1 to AC-6 and a device script with LeakCanary for AC-8, AC-7 and the parts of
AC-5 that need a slow request. CI runs only JVM tests, so these run by hand. Is that accepted
for definition of done items 3 and 4 ("unit tests green"), for this change?
Planned with: yes; instrumented tests are the Tests layer, run locally before the code is merged.

## A2 spec
Yes. This is a defect fix inside upstream's own lifecycle methods, so its marked lines are
the fix itself, not calls into `enhancements/`. Every added or changed line still carries
`ENH:release-views`, and the layer check runs unchanged. `PROCESS.md` on the base branch now
says so. Decided by the session on the owner's behalf; the owner can overrule it.

## A3 spec
Yes, in scope. The spec's claim that local lists already release their views was wrong; the
plan showed that the common page base keeps the view for every page. "Today" is corrected and
AC-2a names the other pages. They stay the last, separable steps of the plan.

## A4 spec
Yes. There is no decision logic to unit-test, so this change has no JVM tests. The Tests
layer writes the plan's instrumented tests; here they can only be compiled, not run, because
there is no device or emulator. They and the device script are run by the owner. Decided by
the session on the owner's behalf; the owner can overrule it.

## Q5 tests -> plan
`detachedViewsHeldBy` as worded in plan 6 (instance fields up to `BaseFragment`; a `View` or
`ViewBinding` root that is not attached; a `RecyclerView.Adapter` with observers) cannot see
three things the plan's own table says the tests fail on. (1) `BaseStateFragment.errorPanelHelper`
is neither a view nor a binding, it is an `ErrorPanelHelper` that holds the root view. (2)
`ChannelFragment.tabAdapter` is a `PagerAdapter`, which has no `hasObservers()`. (3) The header
the list adapter keeps is behind its header supplier, and the tabs of a channel page are child
fragments. Is this extension of the walker accepted?
Written with: yes. The walker also follows fields that hold one of the app's own objects (class
name under `org.schabi.newpipe.`, not the extractor, not a fragment or context) two levels deep;
reports a `PagerAdapter` whose private androidx fields `mViewPagerObserver` / `mObservable` show
a registered pager or observer (not reported if those fields cannot be read); and walks the
page's child fragments. The plan's three checks are unchanged. Stated in the helper's KDoc.

## Q6 tests -> plan
`ac2_leftChannelPage_holdsNoDetachedView`: the plan hosts `ChannelFragment.getInstance(...)` and
lets its load run ("result of the load does not matter"). That starts a real channel request,
and the Tests layer was told to use no network. May the test avoid the request?
Written with: yes. The test writes a hand-built `ChannelInfo` into the private field
`ChannelFragment.currentInfo` by reflection before the page is shown, so `doInitialLoadLogic`
takes the `handleResult(currentInfo)` branch, the branch of a page restored from saved state.
The page then has a Videos and an Info tab, which the walker covers as child fragments. If Code
renames that field the test fails with a message naming this question. Stated in the test's KDoc.

## Q7 tests -> plan
`ac5_subscriptionChangeWhileLeft_isShownOnReturn`: the plan's steps are "insert the subscription
while left, idle (no crash), return: button reads subscribed" and its reason for failing today
is "the monitors write into the detached header". From the code, those steps also hold today:
writing to the detached header does not crash, and `handleResult` on return clears and
re-creates the monitors, which emit the current state. As written the test would pass on the
unfixed app. How is the plan's reason to be observed?
Written with: the test keeps the subscribe button of the header it is about to leave and
asserts that for five seconds after the insert its text still reads "Subscribe"; then the plan's
steps follow. Today the monitor is expected to set "Subscribed" on that old button, so the test
is expected to fail there; with plan 4.3 nothing writes to it. Stated in the test's KDoc.

## Q8 tests -> plan
Four smaller departures from plan 6, none of which changes what is proved. Accepted?
(1) Pages are stacked with `replace` + `addToBackStack` like `NavigationHelper`, but without its
fade animators, so a page's view is destroyed inside the transaction and the tests do not depend
on the device's animation setting. The second page is an empty test fragment. (2) The banner url
of `ac6` is `android.resource://<application id>/<numeric id of R.drawable.channel_banner>`
instead of `.../drawable/channel_banner`: same drawable, no lookup by name (the application id
differs from the code namespace). The header layout shows the same drawable as placeholder, so
the test waits for Picasso's own drawable class before it measures. (3) `ac3` is three methods,
one per page, each named `ac3_viewRebuiltFiveTimes_earlierViewsAreCollected_<page>`. (4) The fake
channel urls are on `releaseviews.invalid`; `ac5` removes its subscription row before and after
the test. The items have no upload date, so subscribing writes no stream rows.
Also for the owner: none of AC-2a's other pages (local playlists, history, bookmarks,
Subscriptions tab alone) has a walker test in plan 6; they are reached only through
`ac3_..._mainPage` and the device script.
Written with: yes to all four.
