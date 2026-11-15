# To do

Work that is decided or waiting for a decision, in the order it should be done. Finding ids
refer to `FINDINGS.md`. Each item that changes the app goes through spec, plan, tests and
code as `PROCESS.md` describes, with its own pull request inside the fork.

## Now

1. **Release views when a screen's view is destroyed** (M1, M2, M3). The likely cause of the
   out-of-memory crash with many subscriptions. Branch `feature/release-views`. Asked for by
   the owner.

## Waiting for the owner

2. **Review and merge pull requests 1 to 5**, running each one's device checklist with a
   debug build. Number 4 is stacked on number 3.
3. **Switch on Issues for the fork** (repository Settings > General > Features), so the
   findings can be filed as issues. It cannot be done from a session.
4. **Redo group-create-inline as a direct edit of the dialog** (F10), and fix the dialog's
   save in the same change (D2). Recommended before pull request 1 is reviewed in depth.
5. **Restore certificate checking for downloads** (S1), with the exception scoped to the
   hosts that needed the original workaround.
6. **Narrow when queue-guard asks** (F4), if asking on every related-video tap is too much.
7. **Add a hook-count test to keep-position** (F13).

## Later

8. Confirm the memory findings on a device: a debug build includes LeakCanary. Browse
   channel to channel with a queue playing and read its reports (M1, M2, M4, M5).
9. Player memory that grows with what is played: seekbar preview sheets (M4), loaded queue
   items (M5), leaked media sessions (M8).
10. Costs that grow with the number of subscriptions: feed refresh memory (M6), full reloads
    of the subscription list (J3), group feed queries (J2), blocking reads per video row (J1).
11. Backup safety: write to a temporary file and validate before replacing the database
    (D1, D3); keep login cookies out of backups or warn (S3); the plugin install offered
    after an import (S2).
12. The remaining findings in `FINDINGS.md`: S4, S5, D4 to D6, M7, J4, C1 to C7.
