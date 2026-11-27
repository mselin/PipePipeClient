# Handover

Working state of the fork's enhancement work in `mselin/PipePipeClient` (this repo, the lead),
`mselin/PipePipeExtractor` and `mselin/PipePipe`; the other two point here. Rules for this file:
`docs/HANDOVER-PROTOCOL.md`. Design doc: `enhancements/PROCESS.md`. Roadmap: `enhancements/TODO.md`
(stale since 2026-11-16, see Established), reports `enhancements/BACKLOG.md`, review `enhancements/FINDINGS.md`.

## Task

Bootstrap only: create the handover docs in pipepipeclient and pipepipe & pipepipeextractor should redirect there from the design doc, roadmap and git log. No code changes. Open a PR.

## Plan

1. Client branch `docs/handover` from `enhancements/base`: `docs/HANDOVER-PROTOCOL.md` (skill sections 2-5 verbatim) and this file. Check: `diff` of the protocol against the skill is empty; this file is under 150 lines. Done.
2. Client design doc and roadmap point here (one line at the top of `PROCESS.md` and `TODO.md`). Check: `grep -l docs/HANDOVER.md enhancements/*.md`. Done.
3. Extractor branch `docs/handover` from `enhancements/base`: `docs/HANDOVER.md` redirect, one line in `enhancements/README.md`, commit message naming this file (so `git log` redirects). Check: `git show --stat`. Done.
4. PipePipe branch `docs/handover` from `main`: `docs/HANDOVER.md` redirect, same commit message. Check: `git show --stat`. Done.
5. Push the three branches; open one PR per repo inside the forks (client and extractor into `enhancements/base`, PipePipe into `main`). Check: each PR's base repo is `mselin/...`. Status in Changes.
6. CI on the PRs. Check: check runs of the PR heads. Status in Verification.

## Established

Owner's rules (verbatim where quoted):
- Spec -> Plan -> Tests -> Code, one commit per layer (`spec(f):`, `plan(f):`, `test(f):`, `code(f):`); questions only through the append-only `enhancements/<f>/questions.md`; `enhancements/tools/check-layers.sh origin/dev` and `--open-questions` must pass.
- "One feature at time is good. One PR per feature too." "Do not make automatically pr to original project": PRs stay inside the forks and name the fork as target explicitly.
- "PR is like a free puppy. When you get one, you own it": follow up on your PRs; the owner merges.
- Private: the owner's e-mail, uploaded backups and logs are never committed or sent anywhere; the debug keystore lives only in the Actions secret.

Branches (heads on 2026-11-27):
- Client: `dev` = upstream 5.6.0 (`aa31606`), never committed to. `enhancements/base` `57dba3f` = dev + CI, `enhancements/` docs and tools; `enhancements/integration` = base.
- Client PRs (branch -> base): #2 group-sort, #3 queue-guard, #4 default-open-action (on #3), #5 keep-position, #6 release-views, #7 group-dialog, #8 tls-checks, #9 crash-fixes, #10 backup-safety, #11 subscription-scale, #12 player-memory, #13 download-fixes, #14 crash-fixes-more (on #9), #15 feed-live-update (on #11), #16 refresh-cooldown (on #15). All others target `enhancements/integration`.
- `build/device-test` `449193e`: reset to group-sort, then merge in order default-open-action, keep-position, release-views, group-dialog, backup-safety, tls-checks, crash-fixes-more, player-memory, download-fixes, refresh-cooldown. Rebuilt, never rebased. A push runs `.github/workflows/device-build.yml`: APK as a draft pre-release (newest 5 kept), signed with the Actions secret keystore, certificate SHA-256 `da396bcc...b136c8a`.
- Extractor: `main` = upstream `375a425`; `enhancements/base` `d78078e`; integration = base; `fix/keep-tls-checks` `65b1091` (PR #1 into integration); `build/device-test` = that fix. CI job `extractor-tests`.
- PipePipe: `main` = upstream v5.6.0 (`5c06ea7`); Issues on (findings filed 2026-11-16 as 1-23).
- Rulesets (owner-made): client "Default" (dev), "dev PR", "CI" (integration and `feature/**` require `layers` and `unit-tests`, admin bypass); extractor "Default", "main PR", "CI" (`extractor-tests`).

Build and test:
- `flock /tmp/pipepipe-gradle.lock ./gradlew :app:testDebugUnitTest --rerun :app:compileDebugAndroidTestKotlin :app:assembleDebug -DskipFormatKtlint --console=plain`, with the extractor at `../PipePipeExtractor` on the matching branch (`fix/keep-tls-checks`).
- Rebasing after a sync: base onto `dev`, then each branch onto its parent's new head using the parent's old head as upstream (`git rebase --onto <parent> <parent-old-head> <branch>`).

Device diagnostics (owner's GrapheneOS phone, package `InfinityLoop1309.NewPipeEnhanced.debug`):
- ACRA catches app crashes, so they are not in `adb logcat -b crash`. Use `adb shell dumpsys activity exit-info <package>`; ANR stacks: `adb shell dumpsys dropbox --print data_app_anr`.
- Logs: `adb logcat -G 64M` first, then `adb logcat -b events,system,main -d > kill.txt`.

Findings of 2026-11-27 (from the owner's logs):
- ANRs: Groupie 2.10.1 `Section.update` is quadratic (`DiffCallback` -> `GroupUtils.getItem` walks the list); 1600 subscriptions took ~3 s per update in the debug build (ANR stack 24.11 15:03). Fixed by subscription-scale AC-8 (Subscriptions tab) and AC-9 (group editor `FeedGroupDialog.kt:367`, group page `SubscriptionListFragment.kt:216`) with `enhancements/subscriptionscale/SectionUpdate.kt`.
- Background exits "FREEZER / Sync transaction while frozen" (importance 400, nothing playing) are the OS freezer; the log shows the system Calendar provider killed the same way. Cost: a cold start.
- 27.11 07:52 jump to 0:00 was a new queue item. Upstream side bug: `Player.setRecovery()` wrote the old item's position (77 s) onto the new item; keep-position ignored it (H4 seek=false).
- Keep-position does not cover: a play after the player was closed (error, Android Auto, notification); Android Auto's `MediaBrowserPlaybackPreparer`; a page reopened with a non-equal queue. The owner has "Always start from beginning" on, so every fresh start is 0:00.
- Upstream 5.6.0 (`aa31606`) loads seek previews at the first touch; D-pad seeking shows none until then. player-memory keeps its bounded loader on top (`LazySeekbarPreviews.clear()`).
- RSS 1.3-1.9 GB with a ~70 MB Java heap (native memory); no out-of-memory exit seen.

## Changes

- Client `docs/handover`: `docs/HANDOVER-PROTOCOL.md`, `docs/HANDOVER.md`, pointer lines in `enhancements/PROCESS.md` and `enhancements/TODO.md`. PR: see Verification.
- Extractor `docs/handover`: `docs/HANDOVER.md` (redirect), pointer line in `enhancements/README.md`.
- PipePipe `docs/handover`: `docs/HANDOVER.md` (redirect).
- No code, test, spec or plan file changed by this task.

## Verification

- Last full run before this task: `build/device-test` `449193e`, 1984 unit tests, 2 skipped, 0 failed; APK built; CI green on every branch; draft `device-test-20261127-1225-449193e`, certificate checked.
- This task: protocol diff empty; PR checks: pending (filled in after the push).
- Unverified on a device: AC-8 and AC-9; the device checklists of all features; keep-position with the KeepPosition log.

## Dead ends

- `--refresh-dependencies`: Maven Central throttles this machine. Never use it.
- GraphQL (`gh pr list`) is refused here: use `gh api repos/...` (REST). Job logs: `gh api repos/<o>/<r>/actions/jobs/<id>/logs`; failed jobs can be re-run.
- Repository settings, releases and remote branch deletion are refused from a session; the owner does them in the UI.
- The device-build secret must be an Actions secret, not an Agents secret.
- A 16 MB log buffer covers ~15 minutes of the debug build. Dropbox ANR entries often lack Java stacks ("tombstoned reported failure").
- Rebasing a child onto a recomputed merge-base after the parent moved caused add/add conflicts; use the parent's old head.

## Next

Owner: install draft `device-test-20261127-1225-449193e` and run the AC-8/AC-9 device check (during a feed refresh: switch tabs, open a channel and return, search the Subscriptions tab, the group editor and "All" channels; `adb logcat -s Choreographer` must show no "Skipped" above 30 frames).

Candidate next tasks: keep-position for starts after a close and for Android Auto (needs the owner's spec answer and a KeepPosition log); the upstream `setRecovery` wrong-item position; refresh `enhancements/TODO.md` and `PROCESS.md` (branch list, PR targets); the native memory use.

## Subagent files

None.
