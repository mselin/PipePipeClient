# Backlog

Things to look at later. Nothing here has a spec yet.

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
