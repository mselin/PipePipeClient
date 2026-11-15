# Backlog

Reports as they came in. What to do about them is in `TODO.md`; what the code review found
is in `FINDINGS.md`.

## Out-of-memory crash with many subscriptions and groups

Reported by the user on 2026-11-15. Not high priority.

What happened:

- About 1600 subscriptions and many channel groups.
- While adding subscriptions to groups, one after another, playback of the queue became
  choppy and then the app crashed with an out-of-memory error.

What the user asked for: find the cause, and more generally analyse how the app handles
memory and whether it frees memory when it can.

What would make the cause findable (none of it captured yet):

- The crash report the app shows, or a logcat with the `OutOfMemoryError` stack trace.
- Device model, Android version, app version.
- A debug build of the fork on the device. Debug builds already include LeakCanary
  (`app/build.gradle`), which reports leaks and writes heap dumps on its own.

Places to look first. These are observations from reading the code for the group features,
not findings; whether they contribute to the crash is unknown:

- The "Select groups" dialog runs one database query per group when it opens and a
  read-then-rewrite of the member list per changed group when it saves
  (`local/subscription/dialog/FeedGroupSelectionDialog.java`).
- The Subscriptions tab builds a new list item for every subscription each time the
  subscription data changes (`local/subscription/SubscriptionViewModel.kt`).
- The app already requests a large heap (`android:largeHeap="true"` in the manifest), so the
  crash is not a matter of raising the limit.

Update, 2026-11-15: the code review points to the channel pages, not the group writes:
findings M1 and M2 in `FINDINGS.md`. Not yet confirmed on a device.

## App not responding while importing a backup

Reported by the owner on 2026-11-15, on the device-test debug build, with an Android "app not
responding" report. After a restart and a second import everything worked.

What the report shows (the backup itself is a 10 MB database with about 1600 subscriptions):

- The main thread was busy the whole time (94% of a core), not waiting.
- About ten background input/output threads and the database threads were busy at once; the
  process used four to five cores for at least twenty seconds.
- The process reached 4.1 GB of memory, 3.7 GB of it outside the Java heap, with 760 MB
  swapped out. The Java heap itself was nearly empty at the time of the dump.
- The app's own thread stacks are missing from the report (the dump failed), so which code was
  running is not shown.

What is known from the code, not confirmed as the cause:

- Import runs on the main thread and replaces the database file while the app keeps using
  it (finding D1 in `FINDINGS.md`). The backup-safety plan moves the work off the main thread
  and swaps the files at the next start instead.
- Every write to the subscription tables makes the Subscriptions tab reload and re-compare
  all subscriptions on the main thread, and open group feeds re-run their queries (findings
  J2 and J3; the subscription-scale plan).

Not explained: the 3.7 GB outside the Java heap. To find out, reproduce the import with the
debug build connected to a computer and take `adb shell dumpsys meminfo` for the app, or a
bug report, while it hangs.
