# To do

Work that is decided or waiting for a decision, in the order it should be done. Finding ids
refer to `FINDINGS.md`. Each item that changes the app goes through spec, plan, tests and
code as `PROCESS.md` describes, with its own pull request inside the fork.

## Planned, waiting for the owner's word to go on to tests and code

Each has a spec and a plan on its own branch, with no open questions. The specs are the
session's proposals from the findings; the owner has not reviewed them yet.

| Branch | Fixes | Upstream lines |
|---|---|---|
| `feature/release-views` | M1, M2, M3: left pages release their views | about 60 in 10 files |
| `feature/group-dialog` | F10, F11, F12, D2: direct edit of the dialog, one-transaction save. Replaces pull request 1 | about 90 in 3 files |
| `feature/tls-checks` | S1: certificate checks restored, also for playback | 6 in 3 files |
| `feature/backup-safety` | D1, D3, S2, S3: staged import, login data left out of backups, plugin address shown | 16 in 5 files |
| `feature/crash-fixes` | C1 to C6 | about 61 in 11 files |
| `feature/player-memory` | M4, M5, M8 | 6 in 4 files |
| `feature/subscription-scale` | M6, J1, J2, J3 | 36 in 11 files |
| `feature/download-fixes` | D5, D6, M7, S4 | about 62 in 4 files |
| `feature/keep-position` | F13: hook-count test added to its plan | none |

## Waiting for the owner

1. **Review and merge pull requests 2 to 5**, running each one's device checklist with a
   debug build. Number 4 is stacked on number 3. Pull request 1 is superseded by
   `feature/group-dialog`.
2. **Switch on Issues for the fork** (repository Settings > General > Features), so the
   findings can be filed as issues. It cannot be done from a session.
3. **Decide how queue-guard should ask less** (F4), if asking on every related-video tap is
   too much.
4. **Decide whether the extractor fork should stop switching certificate checks off**; the
   tls-checks plan only guards the app side.
5. **Try BiliBili downloads and playback** with tls-checks before merging it.

## Later

6. Confirm the memory findings on a device: a debug build includes LeakCanary.
7. The remaining findings in `FINDINGS.md`: S5, D4, J4, C7, and the two the crash-fixes plan
   noticed (a blocked channel's result arriving in the background; a thumbnail read on a
   deleted playlist).

## Issues

Filed on 2026-11-16 in the `mselin/PipePipe` fork (the only one of the three with Issues
switched on), numbers 1 to 19: one per fix package, one per finding without a fix, one for
device verification and one for the unexplained memory use during import. The security findings S1 to
S4 were filed as numbers 20 to 23 at the owner's word. Not filed: S5, which has no fix and
is better reported to the upstream maintainer privately first.

