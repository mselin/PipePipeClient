# Findings

Code review findings, kept as the reference for the issues and for `TODO.md`. Each finding has
an id (S = security, D = data loss, M = memory, J = jank, C = crash or correctness, F = the
fork's own features).

## Whole application, 2026-11-15

Read-only review of upstream code at `dev` `cd87fc7` (the `enhancements/` code excluded), by
four reviewers, one per area. Everything is from reading code; nothing was run on a device or
measured. "checked" means the finding was re-read in the code a second time, independently of
the area reviewer; "reviewer" means it is as the area reviewer reported it. Paths are under
`app/src/main/java/org/schabi/newpipe/` unless they start with `us/` or `app/`.

### Security

| # | Finding | Where | Severity | Status |
|---|---|---|---|---|
| S1 | TLS certificate and hostname checks are disabled process-wide for `HttpsURLConnection` ("Fix random certificate issue for BiliBili"). Affects the download engine, HLS downloads and the opt-in sync client; download requests can carry the session cookie. The OkHttp client used for browsing is not affected. | `MainActivity.java:143`, `:946-962` | high | checked |
| S2 | An imported backup can lead the app to offer installing a plugin from a repository URL stored in the imported database. The URL is not shown and skips the https check; plugins run in-process and can read the stored login cookies. Needs the user to import a hostile backup and tap Install. | `plugin/MissingPluginHelper.kt:196-213`, `settings/ContentSettingsManager.kt:85-94` | high | reviewer |
| S3 | Every backup and settings export contains the YouTube, BiliBili and NicoNico cookies and the proxy token in clear text. `allowBackup="true"` with no backup rules also hands them to Android backup and device transfer. | `settings/ContentSettingsManager.kt:38-50`, `app/src/main/AndroidManifest.xml:53` | medium | checked (manifest) |
| S4 | Pending-download state is Java-serialized into the external files directory and read back without a class filter. On Android 7-10 another app with storage permission can read it (URLs with cookies) or plant a crafted file. | `us/shandian/giga/service/DownloadManager.java:80`, `:150` | medium | reviewer |
| S5 | The cast receiver can be crashed by a LAN peer before authentication, and a cast "speed" command of 0 is saved and then crashes every later playback start until app data is cleared. | `cast/CastReceiver.kt:102-123`, `:165`, `player/Player.java:724-733` | medium | reviewer |

### Data loss and corruption

| # | Finding | Where | Severity | Status |
|---|---|---|---|---|
| D1 | Backup import deletes the live database, then writes the new one in place. A truncated zip or full disk leaves a corrupt database and stale WAL files. | `util/ZipHelper.java:83-98`, `settings/BackupSettingsFragment.java:377-419` | medium | checked |
| D2 | The "Select groups" dialog's save always ends in its error path (`toSingle(() -> null)` is rejected by RxJava). With two or more groups toggled, the first finished write cancels the others, so a channel can be missing from groups that were ticked. Real errors are swallowed the same way. | `local/subscription/dialog/FeedGroupSelectionDialog.java:187-221` | medium | checked |
| D3 | Importing a backup whose database version has no upgrade path (7, 8, or a newer NewPipe schema) crashes the app at start, after the old database is already overwritten. | `database/Migrations.java:251-333` | medium | reviewer |
| D4 | The clean-up meant for old livestreams deletes all of a channel's feed rows, so after each refresh a channel's feed holds only what that fetch returned. May be intended. | `database/feed/dao/FeedDAO.kt:160-174` | low-medium | reviewer |
| D5 | Single-connection download resume appends the full response after the already downloaded prefix when the server ignores the range request; the file is reported as finished. | `us/shandian/giga/get/DownloadRunnableFallback.java:55-133` | medium | reviewer |
| D6 | Download metadata is rewritten in place; a process kill mid-write makes the pending download vanish and orphans its partial file. | `us/shandian/giga/util/Utility.java:87-95` | low-medium | reviewer |

### Memory

The owner reported choppy queue playback followed by an out-of-memory crash while assigning
about 1600 subscriptions to groups, channel by channel. The most likely cause is M1 and M2
together: browsing from channel to channel with a queue playing. The group writes themselves
do not retain memory.

| # | Finding | Where | Severity | Status |
|---|---|---|---|---|
| M1 | Fragments on the back stack keep their whole view tree. Channel pages null their bindings only in `onDestroy`, and each uploader tap pushes a new channel page with no de-duplication. Every visited channel keeps its banner, lists and thumbnails. | `fragments/list/channel/ChannelFragment.java:162-172`, `ChannelVideosFragment.java:216-226`, `util/NavigationHelper.java:455-462` | high | checked |
| M2 | `BaseListFragment` creates its adapter once and never detaches it or nulls the list in `onDestroyView`, so each view recreation leaves another RecyclerView with its rows and bitmaps alive. `BaseLocalListFragment` shows the correct pattern. | `fragments/list/BaseListFragment.java:221-223`, `:265-275` | high | checked |
| M3 | Channel banners are decoded at full size; every other image is capped. | `util/PicassoHelper.java:204-206` | medium | reviewer |
| M4 | Up to 48 seekbar-preview storyboard sheets are downloaded and kept decoded for every item played, also in background playback. | `player/seekbarpreview/SeekbarPreviewThumbnailHolder.java:33`, `:109-162` | medium | reviewer |
| M5 | Every queue item ever loaded stays fully loaded (stream info, media source) until the queue manager is rebuilt, so memory grows with items played. | `player/playback/MediaSourceManager.java:469-541` | medium | reviewer |
| M6 | A feed refresh keeps every fetched stream of every channel in memory until the whole refresh ends. Large with 1600 subscriptions, but only during a refresh. | `local/feed/service/FeedLoadManager.kt:265-268` | high during refresh | reviewer |
| M7 | The HLS downloader buffers out-of-order segments in memory without a bound. | `us/shandian/giga/hls/transfer/HlsSegmentFileTransferEngine.kt:257-310` | medium | reviewer |
| M8 | Each playback start leaks one active media session. | `player/PlayerStartController.kt:203-217` | medium | reviewer |

### Jank (choppy playback and slow lists)

| # | Finding | Where | Status |
|---|---|---|---|
| J1 | Every video row does a blocking database read on the main thread when bound. | `local/history/HistoryRecordManager.java:331-345`, `info_list/holder/StreamInfoItemHolder.java:92-160` | reviewer |
| J2 | Each open group feed re-runs a 500-row, five-table query on every group or subscription write, also when off screen. | `local/feed/FeedViewModel.kt:43-67` | reviewer |
| J3 | The Subscriptions tab reloads and re-diffs all subscriptions on every write to the table (about 80 full reloads per "All" refresh). | `local/subscription/SubscriptionViewModel.kt:81-88` | reviewer |
| J4 | "Mark as watched" fetches video details inside a database transaction, blocking every other writer on a slow connection. | `local/history/HistoryRecordManager.java:105-122` | reviewer |

### Crashes and correctness

| # | Finding | Where | Status |
|---|---|---|---|
| C1 | Long-press actions (Download, Enqueue, Open channel) crash the app if the page is left before the video's details arrive. Queue guard reuses these actions. | `info_list/dialog/StreamDialogDefaultEntry.java:63-134`, `util/SparseItemUtil.java:105-131` | reviewer |
| C2 | Search stays stuck loading after the app is paused mid-search; load-more stops for that search. | `fragments/list/search/SearchFragment.java:355-395` | reviewer |
| C3 | A channel page stays blank if its data arrives while the app is in the background. | `fragments/list/channel/ChannelFragment.java:413-422` | reviewer |
| C4 | A live stream does not resume after a call or alarm, and the first tap on play does nothing. | `player/helper/AudioReactor.java:113-119` | reviewer |
| C5 | A download error without a message (truncated WebM/Opus post-processing) crashes the app from the download thread. | `us/shandian/giga/get/DownloadMission.java:372-373` | reviewer |
| C6 | Deleting an item from a local playlist whose thumbnail is null crashes. | `local/playlist/LocalPlaylistFragment.java:1109` | reviewer |
| C7 | The player thumbnail can show the previous video's image (Picasso target not held, older requests not cancelled). | `player/PlayerThumbnailController.kt:35-72` | reviewer |

### What is solid

- The rewritten Kotlin detail page releases its controllers correctly.
- The explicit caches (info cache, serialized cache, Picasso memory cache) are bounded.
- The player's main teardown path releases ExoPlayer, listeners and Rx loops.
- Plugin packages have id validation, size limits and key pinning; the headless WebView has a
  narrow bridge; no exported component acts on an untrusted intent.
- Local playlists, history and bookmarks cancel their database subscriptions properly.

## The fork's own features, 2026-11-15

Review of the five features in pull requests 1 to 5, read against the upstream code they hook
into. No confirmed crash or data-loss bug. Ordered by how much each should worry the owner.

| # | Feature | Finding |
|---|---|---|
| F1 | all | None of the Android wiring (dialogs, adapters, preference reads, player glue; about half of the feature code) has run on a device. The unit tests prove the decision logic only. |
| F2 | all | The tests are heavy to own: about 7 lines of test per line of code, many pinning call order, exact strings and hook-line text. Refactors will break many of them. |
| F3 | queue-guard | *Add all to queue* on a remote playlist or channel adds only the videos loaded so far, not the whole list. The dialog shows that count. |
| F4 | queue-guard | It asks often: tapping a related video on the page being watched asks every time, and it still asks after a queue has finished while the player stays open. |
| F5 | queue-guard | *Play all next* moves videos one at a time on the live queue; several hundred videos mean several hundred queue events. Not measured. |
| F6 | queue-guard | The dialog offers *Play next* and popup playback also where upstream's long-press menu hides them (one-item queue, audio-only items). |
| F7 | queue-guard | Its actions reuse upstream's long-press actions and inherit C1. |
| F8 | default-open-action | *Remember my choice* also stops the play buttons from asking, because there is one switch for both. |
| F9 | default-open-action | The default-action setting looks active while asking is on, although it is unused then. |
| F10 | group-create-inline | The feature takes over upstream's dialog at runtime (replaces the OK listener, wraps the adapter, infers "loaded" from adapter notifications). The upstream file changed once in 410 commits, so a direct edit would be simpler and less fragile. It also hands off to the save with bug D2. |
| F11 | group-create-inline | If loading the groups fails, the duplicate-name check has nothing to compare against. |
| F12 | group-create-inline | Rotating while OK is saving creates the new groups but drops the other checkbox changes; rows can still be toggled while the save runs. |
| F13 | keep-position | No test counts its hook lines, so a hook lost in a rebase is not caught. |
| F14 | keep-position | It relies on the player clearing its current item at the start of every reload (true today, `player/PlayerPlaybackStateController.kt:117-130`). |

### Is "do not touch upstream files" the right rule?

Feature code is 1,710 lines with 39 hook lines; tests are 11,690 lines. The rule pays off
where upstream changes the hooked files often. Over the last 410 upstream commits:
`Player.java` 32, the list fragments 6 to 14 each, the group files 1 to 2. So the rule is right
for keep-position, queue-guard, default-open-action and group-sort, and wrong for
group-create-inline (F10).
