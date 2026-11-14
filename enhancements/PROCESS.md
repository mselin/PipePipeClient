# PipePipe enhancements: how we develop them

This fork adds features on top of upstream PipePipe. Two rules shape everything:

1. **Stay rebasable.** Upstream files get one-line hooks; everything else is new files.
2. **Layers are independent.** Spec, Plan, Tests and Code are written in that order, each
   layer owns its own files, and a later layer never edits an earlier one. It asks instead.

## Repositories

| Repo | Role | Base branch |
|---|---|---|
| `mselin/PipePipeClient` | All four features live here | `dev` |
| `mselin/PipePipeExtractor` | No change expected; touched only if a spec proves it necessary | `main` |
| `mselin/PipePipe` | Umbrella (submodules, CI, release). Used only to build an integrated APK | `main` |

## Branches

```
dev (upstream, never committed to)
└─ enhancements/base                  process docs, layer check, unit-test scaffolding
   ├─ feature/queue-guard             F1: clicking a video must not silently replace the queue
   │  └─ feature/default-open-action  F2: setting for the default click action (nested on F1)
   ├─ feature/group-create-inline     F3: "create new group" when adding a channel to a group
   └─ feature/group-sort              F4: keep groups sorted A-Z / Z-A
enhancements/integration              merge of all feature branches, for building and device testing
```

F2 is nested on F1 because both decide what a click on a video does and share one decision
point. F3 and F4 are independent of each other and of F1/F2.

Updating from upstream: fetch `dev`, rebase `enhancements/base`, then rebase each feature
branch onto its parent. `enhancements/integration` is rebuilt, never rebased.

## Keeping upstream untouched

- New code goes in `app/src/main/java/org/schabi/newpipe/enhancements/<package>/`.
- Decision logic is plain Kotlin with no Android imports, so it is testable on the JVM.
  Android glue (dialogs, preferences) is a thin shell around it.
- New strings and preference keys go in `res/values/enhancements_*.xml`, never in upstream
  `strings.xml` / `settings_keys.xml` (those are the files that conflict on every rebase).
- An upstream file may receive a hook: a single call into `enhancements/`, marked
  `// ENH:<feature>`. `git grep "ENH:"` lists every touch point in the fork.
- With every new setting at its default, behaviour equals upstream, except where the spec
  explicitly says otherwise (F1's confirmation).

Known hook points (confirmed in the code, to be finalised by each Plan):

| Feature | Hook |
|---|---|
| F1, F2 | `NavigationHelper.openVideoDetailFragment(...)`, the path almost every video click takes |
| F3 | `FeedGroupSelectionDialog` (opened from `ChannelVideosFragment`) |
| F4 | `FeedDatabaseManager.groups()` consumers; `FeedGroupDAO` orders by `sort_order` today |

## Layers

Each feature has a directory `enhancements/<feature>/`. Its code and test package is the
feature name without hyphens (`queue-guard` -> `enhancements.queueguard`).

| Layer | Owns (may write) | Reads | Answers |
|---|---|---|---|
| Spec | `enhancements/<feature>/spec.md` | the request, the running app | What the user sees. Numbered acceptance criteria `AC-n`. No class names. |
| Plan | `enhancements/<feature>/plan.md` | spec, upstream code | How: hook points, new classes and their signatures, settings keys, which `AC-n` each piece covers. |
| Tests | `app/src/test/**/enhancements/<package>/`, `app/src/androidTest/**/enhancements/<package>/` | spec, plan | One or more tests per `AC-n`, named after it. They fail until Code exists. |
| Code | `app/src/main/**` (new files + `ENH:` hooks) | spec, plan, tests | Makes the tests pass. |

Rules:

- **One commit, one layer.** The subject starts with `spec(<feature>):`, `plan(...)`,
  `test(...)` or `code(...)`. Commits without such a prefix (process, tooling, CI) may not
  touch anything a layer owns.
- **A layer writes only what it owns.** `enhancements/tools/check-layers.sh` fails any commit
  whose prefix and changed paths disagree; CI runs it on every push.
- **Questions go up, never sideways.** If Code finds a test wrong, a plan unworkable or a
  spec ambiguous, it does not edit them. It appends a question to
  `enhancements/<feature>/questions.md` (the one file every layer of the feature may write)
  addressed to the owning layer, and stops work on the affected `AC-n`. A layer that cannot
  answer forwards the question to the layer above it; Spec forwards to the user.
- **Answers come from the owning layer.** It fixes its own file in a `spec(...)`, `plan(...)`
  or `test(...)` commit and appends the answer to `questions.md`; the layers below then
  re-run.
- **`questions.md` is a log.** Entries are appended, never edited or removed (the check
  enforces this). A question is open until an answer with the same number exists;
  `check-layers.sh --open-questions` lists the open ones.
- **Each layer is done separately.** A layer is worked on with only its "Reads" column as
  input and is reviewed before the next one starts.

`questions.md` format:

```
## Q3 code -> tests
AC-4 test expects the dialog when the queue has one item; plan section 2 says "more than one".

## A3 tests
Test was wrong. Fixed in the test commit that adds this answer.
```

A forwarded question is answered with the forward (`## A3 tests: forwarded as Q4`) and a new
`## Q4 tests -> plan`.

## Definition of done per feature

1. Spec approved by the user.
2. Plan approved; every `AC-n` mapped to a piece of the design.
3. Tests committed and failing only for the reason "not implemented".
4. Code committed; unit tests green; `git diff dev -- . ':!enhancements' ':!app/src/test'`
   shows only new files and `ENH:` hook lines in upstream files.
5. `check-layers.sh --open-questions` reports none.
6. Merged into `enhancements/integration`, debug APK built and tried on a device.

## Where things run

Upstream has no JVM unit tests (`app/src/test` does not exist; JUnit is already a
`testImplementation` dependency). `enhancements/base` adds that source set and a fork CI
workflow (`.github/workflows/enhancements.yml`) running `check-layers.sh` and
`./gradlew :app:testDebugUnitTest`. 

To run the same locally: JDK 25, the Android SDK (`ANDROID_HOME`), and the extractor checked
out next to the client as `../PipePipeExtractor`. Then:

```bash
enhancements/tools/check-layers.sh origin/dev
```

```bash
./gradlew :app:testDebugUnitTest -DskipFormatKtlint
```
