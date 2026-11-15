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
