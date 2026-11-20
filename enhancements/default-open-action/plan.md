# Default open action: plan

How `spec.md` (AC-1..AC-16) is built on queue-guard. Paths are relative to
`app/src/main/java/org/schabi/newpipe/` unless they start with `res/` (`app/src/main/res/`). Line
numbers are those of `dev` at `cd87fc7`. QG = queue-guard: "QG plan 3.2" is a section of
`enhancements/queue-guard/plan.md`, "QG AC-n" a criterion of its spec, and its types are used
exactly as QG plan 3 defines them. `DOA` abbreviates
`org.schabi.newpipe.enhancements.defaultopenaction.DefaultOpenAction`; in the file, G1 and G2 carry
the full name, so that no import line is added.

**Changed 2026-11-15, after A8 (spec commits `8d52865`, `f7270f4`) and queue-guard's A11 to A14
(QG plan: the note at its top, 3.1, 3.2 and section 6).** Queue-guard no longer asks for a single
video in the main player: its policy answers such a tap with `OPEN_AS_TODAY`, and a play button
with `PLAY_AS_TODAY`, whether its switch is on or off. `OpenActionPolicy` took "the wrapped policy
does not answer `ASK`" for "asking is off", so there it would have carried out the default action
and offered "Show stream details" although the switch is on. The spec now says: asking on ->
exactly queue-guard's behaviour, the default action is not used (AC-4); asking off -> the default
action, also on a single video in the main player (AC-3); AC-11 follows the same line. What
changed in this plan:

- 2: G1 passes queue-guard's switch on as a second argument. Still two marked lines, nothing else
  of QG changes.
- 4.2: `OpenActionPolicy` takes a `GuardSettings` and reads the switch itself. The rules of
  `onTap` and `offersStreamDetails` are restated on it; `onTapChoice`, `onListPlay`,
  `onListChoice` and *idle* are as they were. Row 6 is split into 6 (a queue worth guarding ->
  `ASK`) and 6b (a single video in the main player -> `OPEN_AS_TODAY`). Rows 7 to 12, 21 and 21b
  keep their results and now state that they cover a single video in the main player; a table
  spells that state out, and a paragraph shows AC-14 over the whole grid.
- 4.4: `DefaultOpenAction.policy` has a second parameter.
- 6: AC rows 3, 4, 8, 11, 12, 14, 15 and the wiring line; new 6.1, what the Tests layer changes.
- 7, 8, 9: the order of work for this amendment, three new risks, Q8.

Not changed: section 3 (S2, S3), 4.1, 4.3, section 5, rows 1 to 5 and 13 to 23 as results.

## 1. Shape

No tap handler gets a new hook: QG's T1-T4 and M1 already send every video tap and long-press menu
through `QueueGuardController`, which asks one `GuardPolicy`. This feature wraps QG's policy in
`OpenActionPolicy` (plain Kotlin: default action and "Start playback in", applied according to
QG's switch, which it reads through QG's `GuardSettings`; everything else goes to the wrapped
policy), gives QG's tap dialog a checkbox through `TapDialogExtension`, decided by
`RememberChoice` (plain Kotlin), and adds two settings. Every result is one of QG's `TapEffect`s,
carried out by QG's `FragmentTapActions`: no new action code.

## 2. Changes to queue-guard's files

Two single-line replacements in `enhancements/queueguard/QueueGuard.kt`, the two that QG plan 3.3
and 6 reserve. Each line keeps its place (`policy` stays above the controller that takes it).

| # | Line as QG plan 3.3 gives it | Becomes |
|---|---|---|
| G1 | `private val policy: GuardPolicy = QueueGuardPolicy(PrefsGuardSettings)` | `private val policy: GuardPolicy = DOA.policy(QueueGuardPolicy(PrefsGuardSettings), PrefsGuardSettings) // ENH:default-open-action` |
| G2 | `private val tapDialogExtension: TapDialogExtension? = null` | `private val tapDialogExtension: TapDialogExtension? = DOA.tapDialogExtension // ENH:default-open-action` |

Nothing else of QG changes: no other line of `QueueGuard.kt`, no other QG file, resource or hook.
`check-layers.sh` does not need the marker here (the file is not in `dev`); `git grep "ENH:"` does.

G1 names `PrefsGuardSettings` twice, on purpose: the wrapped policy and the wrapper read the one
switch through the one object, and the line shows it. `PrefsGuardSettings` (QG plan 3.3,
`QueueGuardGlue.kt`) is `internal` to the app module, which G1's file is in; `DOA.policy` receives
it as the public interface `GuardSettings` (QG plan 3.2), so this feature's code names no class of
QG's glue. It reads the preference on every call and touches nothing when it is merely passed, so
`QueueGuard`'s initialiser stays as harmless as before (section 8). Rejected: `DefaultOpenAction`
naming `PrefsGuardSettings` itself and G1 staying as it was (the wiring of the switch would be
split over two files, and nothing would show that both policies read the same one); a method
"is asking on" added to `GuardPolicy` (changes QG's interface and its tests for a fact QG already
exposes as `GuardSettings`).

## 3. Hook points in upstream files

One upstream file, 2 added lines, 0 changed: `res/xml/video_audio_settings.xml`, directly after
QG's S1 line (S1 follows `:62`, the end of "Preferred 'open' action"), S2 then S3, before the
blank line `:63`, and with no blank line added (`check-layers.sh` rejects an unmarked line). On
screen: Preferred 'open' action, Ask before replacing the queue, Default action when tapping a
video, Start playback in.

| # | Added line |
|---|---|
| S2 | `<ListPreference android:defaultValue="play_now" android:entries="@array/enh_default_open_action_entries" android:entryValues="@array/enh_default_open_action_values" android:key="@string/enh_default_open_action_key" android:summary="%s" android:title="@string/enh_default_open_action_title" app:singleLineTitle="false" app:iconSpaceReserved="false" /> <!-- ENH:default-open-action -->` |
| S3 | `<ListPreference android:defaultValue="video_page" android:entries="@array/enh_default_open_action_start_in_entries" android:entryValues="@array/enh_default_open_action_start_in_values" android:key="@string/enh_default_open_action_start_in_key" android:summary="%s" android:title="@string/enh_default_open_action_start_in_title" app:singleLineTitle="false" app:iconSpaceReserved="false" /> <!-- ENH:default-open-action -->` |

Why no other hook is needed, checked in the code. Taps (`fragments/list/BaseListFragment.java:660`,
`local/playlist/LocalPlaylistFragment.java:272`, `local/history/StatisticsPlaylistFragment.java:186`,
`local/feed/FeedFragment.kt:775`) reach the policy through T1-T4. Starting in the background or
popup player is QG's `FragmentTapActions`, i.e. the long-press actions at
`info_list/dialog/StreamDialogDefaultEntry.java:86-96`, popup permission prompt included
(`util/NavigationHelper.java:165-168`). "Show stream details" (AC-11) is QG's M1 at
`info_list/dialog/InfoItemDialog.java:346`. `settings/VideoAudioSettingsFragment.java:25` inflates
the XML, `settings/NewPipeSettings.java:76` writes the defaults at start-up, and
`android:summary="%s"` shows the selected entry as at `res/xml/video_audio_settings.xml:11`.

## 4. New code in `enhancements/defaultopenaction/`

### 4.1 `OpenActionModel.kt` (plain Kotlin)

```kotlin
enum class DefaultTapAction(val prefValue: String, val choice: GuardChoice) {
    PLAY_NOW("play_now", GuardChoice.PLAY_NOW),
    ADD_TO_QUEUE("add_to_queue", GuardChoice.ADD_TO_QUEUE),
    PLAY_NEXT("play_next", GuardChoice.PLAY_NEXT);
    companion object {
        val DEFAULT: DefaultTapAction = PLAY_NOW
        fun fromPrefValue(value: String?): DefaultTapAction      // null or unknown -> DEFAULT
        fun fromChoice(choice: GuardChoice): DefaultTapAction?   // CANCEL -> null
    }
}
enum class StartPlaybackIn(val prefValue: String) {
    VIDEO_PAGE("video_page"), BACKGROUND_PLAYER("background_player"), POPUP_PLAYER("popup_player");
    companion object {
        val DEFAULT: StartPlaybackIn = VIDEO_PAGE
        fun fromPrefValue(value: String?): StartPlaybackIn       // null or unknown -> DEFAULT
    }
}
/** Both are read on every call, never cached. */
interface OpenActionSettings { fun defaultAction(): DefaultTapAction; fun startPlaybackIn(): StartPlaybackIn }
```

### 4.2 `OpenActionPolicy.kt` (plain Kotlin)

```kotlin
/** Overrides the five functions of [GuardPolicy] (QG plan 3.2), signatures unchanged. */
class OpenActionPolicy(
    private val base: GuardPolicy,
    private val guardSettings: GuardSettings,   // QG's switch "Ask before replacing the queue" (QG plan 3.2)
    private val settings: OpenActionSettings,
) : GuardPolicy
```

`GuardSettings` is `org.schabi.newpipe.enhancements.queueguard.GuardSettings`,
`fun interface GuardSettings { fun askBeforeReplacing(): Boolean }`, unchanged. No parameter has a
default: a caller that forgot the switch must not compile.

How it composes with `base`. "Asking is on" is `guardSettings.askBeforeReplacing()`, read inside
every call that needs it and never cached (the user changes it in Settings, "Remember my choice"
switches it off, AC-8). It is no longer inferred from an answer of `base`: since QG's A11 `base`
answers a tap with `OPEN_AS_TODAY` and a play button with `PLAY_AS_TODAY` for a single video in
the main player with the switch on as well. Everything else is still `base`'s answer: this class
never reads `player.worthGuarding` and repeats none of QG's rules, so whether a queue is worth
guarding is decided in one place. `base` is pure; how often it is called is not part of the
contract.

- `onTap`: not `player.somethingPlaying` -> *idle* over `base.onTap(tap, player)`. Otherwise:
  `guardSettings.askBeforeReplacing()` or `player.isCurrent(tap)` -> `base.onTap(tap, player)`,
  whatever that answer is; otherwise
  `base.onTapChoice(settings.defaultAction().choice, tap, player)`.
- `onTapChoice`: `b = base.onTapChoice(choice, tap, player)`; `choice == PLAY_NOW` and not
  `player.somethingPlaying` -> *idle* over `b`; otherwise `b`. Does not read the switch.
- *idle* over `b`: `tap.startsListInBackground` or `startPlaybackIn() == VIDEO_PAGE` -> `b`;
  `BACKGROUND_PLAYER` -> `START_BACKGROUND`; `POPUP_PLAYER` -> `START_POPUP`.
- `onListPlay`, `onListChoice`: the base call, arguments and result unchanged.
- `offersStreamDetails`: `base.offersStreamDetails(player)`, or not `player.somethingPlaying`
  and `settings.startPlaybackIn() != VIDEO_PAGE`, or `player.somethingPlaying` and not
  `guardSettings.askBeforeReplacing()` and `settings.defaultAction() != PLAY_NOW`. It no longer
  calls `base.onListPlay`.

"Playing" stays `player.somethingPlaying` in all of this and must not become `worthGuarding`
(QG plan section 6): AC-3 has a tap carry out the default action on a single video in the main
player, which is how *Add to queue* starts a queue.

Against a `base` that is not QG's policy (the tests' recording fake) the contract is the list
above, literally: with the switch on, or for the current item, the result of `onTap` while
something is playing is `base.onTap`'s answer, `ASK` or not; with the switch off and another
video it is `base.onTapChoice`'s answer for the default action, and what `base.onTap` would
answer has no influence; `base.onListPlay`'s answer has no influence on `offersStreamDetails`.

Rule tables for `OpenActionPolicy(QueueGuardPolicy(g), g, settings)` with `g = GuardSettings { ask }`,
first match wins. playing = `player.somethingPlaying` (false for `PlayerState.IDLE` and for any
kind with `queueSize == 0`); worth guarding = `player.worthGuarding` (QG plan 3.1: playing, and
not the main player with `queueSize - autoQueuedCount <= 1`; it implies playing and is evaluated
by `base` only); current = `player.isCurrent(tap)`; MPM = `tap.startsListInBackground` (local
playlist with Music Player Mode on); kind = `player.kind`; "any" = no influence on the result.

`onTap`:

| # | playing | current | ask | worth guarding | default action | start playback in | MPM | kind | Result | AC |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | no | - | any | - | any | any | yes | any | `OPEN_AS_TODAY` | 6 |
| 2 | no | - | any | - | any | `VIDEO_PAGE` | no | any | `OPEN_AS_TODAY` | 5, 14 (QG 7) |
| 3 | no | - | any | - | any | `BACKGROUND_PLAYER` | no | any | `START_BACKGROUND` | 5 |
| 4 | no | - | any | - | any | `POPUP_PLAYER` | no | any | `START_POPUP` | 5 |
| 5 | yes | yes | any | any | any | any | any | any | `OPEN_AS_TODAY` | 12 (QG 8) |
| 6 | yes | no | on | yes | any | any | any | any | `ASK` | 4 (QG 1) |
| 6b | yes | no | on | no | any | any | any | `MAIN` | `OPEN_AS_TODAY` | 4 (QG 7) |
| 7 | yes | no | off | any | `ADD_TO_QUEUE` | any | any | any | `ENQUEUE` | 3 |
| 8 | yes | no | off | any | `PLAY_NEXT` | any | any | any | `ENQUEUE_NEXT` | 3 |
| 9 | yes | no | off | any | `PLAY_NOW` | any | yes | any | `OPEN_AS_TODAY` | 3 (QG 5) |
| 10 | yes | no | off | any | `PLAY_NOW` | any | no | `MAIN` | `OPEN_AS_TODAY` | 2, 3 (QG 16, QG 7) |
| 11 | yes | no | off | yes | `PLAY_NOW` | any | no | `BACKGROUND` | `START_BACKGROUND` | 2, 3 (QG 16) |
| 12 | yes | no | off | yes | `PLAY_NOW` | any | no | `POPUP` | `START_POPUP` | 2, 3 (QG 16) |

Row 6b is new and row 6 is narrowed by it: together they are the old row 6, "ask on -> what QG
does". In row 6b the kind can only be `MAIN` (in the background and the popup player every
playing queue is worth guarding), and so can "worth guarding: no" in rows 7 to 10; rows 11 and 12
say "yes" for that reason, not as a condition. Rows 7 to 12 keep the results they had: "ask off"
was "the wrapped policy does not ask" and is now the switch itself, which gives the same result
for a queue worth guarding and, as AC-3 now says in words, for a single video in the main player.

`onTapChoice` (ask, worth guarding, default action and current have no influence):

| # | choice | playing | start playback in | MPM | kind | Result | AC |
|---|---|---|---|---|---|---|---|
| 13 | `CANCEL` | any | any | any | any | `NONE` | 9 (QG 6) |
| 14 | `ADD_TO_QUEUE` | any | any | any | any | `ENQUEUE` | 4 (QG 3, QG 9) |
| 15 | `PLAY_NEXT` | yes | any | any | any | `ENQUEUE_NEXT` | 4 (QG 4) |
| 16 | `PLAY_NEXT` | no | any | any | any | `ENQUEUE` | 4 (QG 9) |
| 17 | `PLAY_NOW` | yes | any | yes | any | `OPEN_AS_TODAY` | 2 (QG 5) |
| 18 | `PLAY_NOW` | yes | any | no | `MAIN` / `BACKGROUND` / `POPUP` | `OPEN_AS_TODAY` / `START_BACKGROUND` / `START_POPUP` | 2 (QG 5) |
| 19 | `PLAY_NOW` | no | as rows 1-4 | as rows 1-4 | any | the result of rows 1-4 | 13 (through row 1: 6) |

Rows 13 to 19 are unchanged. A dialog only opens for a queue worth guarding (row 6); if the main
player is down to a single video when the user chooses, rows 14, 15, 17 and 18 still apply, on
"playing", as QG plan 3.2 has it: an explicit choice is never dropped or turned into another one.

`offersStreamDetails` (`onListPlay` and `onListChoice` are QG plan 3.2's rows whatever the two
settings are, AC-15; for a single video in the main player that is `PLAY_AS_TODAY` with ask on
and off):

| # | playing | kind | ask | default action | start playback in | Result | AC |
|---|---|---|---|---|---|---|---|
| 20 | yes | `BACKGROUND`, `POPUP` | any | any | any | `true` | QG 17 |
| 21 | yes | `MAIN`, a single video or a queue | off | `ADD_TO_QUEUE`, `PLAY_NEXT` | any | `true` | 11 |
| 21b | yes | `MAIN`, a single video or a queue | any other combination: ask on with any default action, or ask off with `PLAY_NOW` | | any | `false` | 11, QG 17, 14 |
| 22 | no | any | any | any | `BACKGROUND_PLAYER`, `POPUP_PLAYER` | `true` | 11 |
| 23 | no | any | any | any | `VIDEO_PAGE` | `false` | 14 |

Rows 20 to 23 are unchanged as results; "worth guarding" has no influence on any of them. What
changed is how row 21 is told from row 21b: by the switch. Row 21b with ask on is AC-11's "a tap
would neither open the video's page nor ask" answered with no in both of its halves: on a queue
worth guarding the tap asks (row 6), on a single video it opens the page (row 6b).

**The main player with a single video, complete.** The states that are playing and not worth
guarding: kind `MAIN`, `queueSize >= 1`, `queueSize - autoQueuedCount <= 1`. Written
`queueSize`/`autoQueuedCount`: 1/0 (one video), 2/1 and 3/2 (one video and what "Auto-enqueue next
stream" put around it, played or not), 1/1 and 2/2 (only auto-enqueued videos left). The results
are the same for all of them: `autoQueuedCount` reaches this feature only through `base`.

| ask | default action | tap on another video, MPM or not | tap on the current item | `offersStreamDetails` | play button |
|---|---|---|---|---|---|
| on | `PLAY_NOW` | `OPEN_AS_TODAY` (row 6b, AC-4) | `OPEN_AS_TODAY` (row 5, AC-12) | `false` (row 21b, AC-11) | `PLAY_AS_TODAY` (AC-15, QG 14) |
| on | `ADD_TO_QUEUE` | `OPEN_AS_TODAY` (row 6b, AC-4) | `OPEN_AS_TODAY` (row 5, AC-12) | `false` (row 21b, AC-11) | `PLAY_AS_TODAY` (AC-15, QG 14) |
| on | `PLAY_NEXT` | `OPEN_AS_TODAY` (row 6b, AC-4) | `OPEN_AS_TODAY` (row 5, AC-12) | `false` (row 21b, AC-11) | `PLAY_AS_TODAY` (AC-15, QG 14) |
| off | `PLAY_NOW` | `OPEN_AS_TODAY` (rows 9, 10, AC-3) | `OPEN_AS_TODAY` (row 5, AC-12) | `false` (row 21b, AC-11, AC-14) | `PLAY_AS_TODAY` (AC-15, QG 14, QG 16) |
| off | `ADD_TO_QUEUE` | `ENQUEUE` (row 7, AC-3) | `OPEN_AS_TODAY` (row 5, AC-12) | `true` (row 21, AC-11) | `PLAY_AS_TODAY` (AC-15, QG 14, QG 16) |
| off | `PLAY_NEXT` | `ENQUEUE_NEXT` (row 8, AC-3) | `OPEN_AS_TODAY` (row 5, AC-12) | `true` (row 21, AC-11) | `PLAY_AS_TODAY` (AC-15, QG 14, QG 16) |

"Start playback in" has no influence on any cell (AC-2). The same six lines for the main player
with a queue worth guarding (2/0, 3/0, 3/1, 5/3) differ in two cells per line with ask on: the
tap on another video is `ASK` (row 6) and the play button is `ASK` (QG 10). With ask off the two
tables are equal, cell by cell.

**AC-14 over the whole grid.** It holds, also in the new states. With `PLAY_NOW` and `VIDEO_PAGE`:

- `onTap`, nothing playing: *idle* over `base.onTap` with `VIDEO_PAGE` is `base.onTap`.
- `onTap`, playing, ask on or current item: `base.onTap`, by the rule itself.
- `onTap`, playing, ask off, another video: `base.onTapChoice(PLAY_NOW, tap, player)`, which is
  QG's *playNow*. For a queue worth guarding `base.onTap` is *playNow* too (QG plan 3.2, last row
  of `onTap`). For a single video in the main player `base.onTap` is `OPEN_AS_TODAY` by its first
  row, and *playNow* there is `OPEN_AS_TODAY` as well, with Music Player Mode (its second row) and
  without (kind `MAIN`). Equal in both.
- `onTapChoice`: `base.onTapChoice`, except `PLAY_NOW` while nothing is playing, which is *idle*
  with `VIDEO_PAGE`, again `base`'s answer.
- `onListPlay`, `onListChoice`: `base`.
- `offersStreamDetails`: the second clause needs a value other than `VIDEO_PAGE`, the third one
  other than `PLAY_NOW`; `base.offersStreamDetails` remains.

No step looks at `queueSize` or `autoQueuedCount`, so the grid can be widened by any state. The
old rule passed the same check only because `PLAY_NOW` hides the difference; AC-14 could not see
the error this amendment removes, and it cannot guard against its return. Rows 6b and 21b can
(6.1).

### 4.3 `RememberChoice.kt` (plain Kotlin)

```kotlin
fun interface RememberStore { fun save(defaultAction: DefaultTapAction, askBeforeReplacing: Boolean) }
class RememberChoice(private val store: RememberStore) {
    /** A choice made in the tap dialog, before it is carried out. [checked]: the checkbox at that moment. */
    fun onChoice(choice: GuardChoice, checked: Boolean)
}
```

`checked` and `choice` one of `ADD_TO_QUEUE`, `PLAY_NEXT`, `PLAY_NOW`: exactly one call,
`store.save(DefaultTapAction.fromChoice(choice), false)` (AC-8). Not `checked`, or `CANCEL`: none (AC-9).

### 4.4 `DefaultOpenAction.kt` (Android glue, no unit tests)

| Piece | Does |
|---|---|
| `object DefaultOpenAction { fun policy(base: GuardPolicy, guardSettings: GuardSettings): GuardPolicy }` | `OpenActionPolicy(base, guardSettings, PrefsOpenActionSettings)`. G1 is the only caller; `guardSettings` is the object `base` was built with |
| `DefaultOpenAction.tapDialogExtension: TapDialogExtension` (a `val`) | `TapDialogExtension { context -> RememberChoiceAddOn(context, RememberChoice(PrefsOpenActionSettings)) }`: a new add-on, so a new unchecked box, for every dialog (AC-7) |
| `internal object PrefsOpenActionSettings : OpenActionSettings, RememberStore` | `ctx` = `App.getApp()`, `prefs` = its default shared preferences, both fetched inside each call. `defaultAction()` = `DefaultTapAction.fromPrefValue(prefs.getString(ctx.getString(R.string.enh_default_open_action_key), null))`; `startPlaybackIn()` = `StartPlaybackIn.fromPrefValue(prefs.getString(ctx.getString(R.string.enh_default_open_action_start_in_key), null))`; `save(action, ask)` = `prefs.edit().putString(ctx.getString(R.string.enh_default_open_action_key), action.prefValue).putBoolean(ctx.getString(R.string.enh_queue_guard_ask_key), ask).apply()` (the last key is QG's switch) |
| `internal class RememberChoiceAddOn(context: Context, remember: RememberChoice) : TapDialogAddOn` | `view` = `LayoutInflater.from(context).inflate(R.layout.enhancements_default_open_action_remember, null)`; `onChoice(choice)` = `remember.onChoice(choice, checkBox.isChecked)` with `checkBox` = `view.findViewById(R.id.enh_default_open_action_remember)` |

## 5. Resources

`res/values/enhancements_default_open_action.xml`, root element as in QG's file
(`<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">`).
No `values-xx` copies, nothing in `strings.xml` or `settings_keys.xml`. Both preferences hold a
string. Arrays are in the order shown, which is the order of the enums in 4.1.

| Name | English text / value |
|---|---|
| `enh_default_open_action_key` (`translatable="false"`) | `enh_default_open_action` (default `play_now`) |
| `enh_default_open_action_title` | Default action when tapping a video |
| `enh_default_open_action_play_now` | Play now |
| string-array `enh_default_open_action_entries` | `@string/enh_default_open_action_play_now`, `@string/enh_queue_guard_add_to_queue`, `@string/enh_queue_guard_play_next` (QG's "Add to queue" and "Play next": the dialog choices AC-3 names) |
| string-array `enh_default_open_action_values` (`translatable="false"`) | `play_now`, `add_to_queue`, `play_next` |
| `enh_default_open_action_start_in_key` (`translatable="false"`) | `enh_default_open_action_start_in` (default `video_page`) |
| `enh_default_open_action_start_in_title` | Start playback in |
| `enh_default_open_action_start_in_video_page` | Video page |
| `enh_default_open_action_start_in_background` | Background player |
| `enh_default_open_action_start_in_popup` | Popup player |
| string-array `enh_default_open_action_start_in_entries` | the three `enh_default_open_action_start_in_…` texts above, in that order |
| string-array `enh_default_open_action_start_in_values` (`translatable="false"`) | `video_page`, `background_player`, `popup_player` |
| `enh_default_open_action_remember` | Remember my choice |

`res/layout/enhancements_default_open_action_remember.xml`: a `FrameLayout` (`match_parent` x
`wrap_content`, left and right padding `?dialogPreferredPadding` as in
`res/layout/dialog_feed_group_create.xml:164-166`) with one `CheckBox` (`match_parent` x
`wrap_content`, `@+id/enh_default_open_action_remember`, `android:saveEnabled="false"`,
`android:text="@string/enh_default_open_action_remember"`, no `android:checked`).

## 6. AC coverage

JVM tests: `app/src/test/java/org/schabi/newpipe/enhancements/defaultopenaction/`, JUnit 4, fakes
written in that package. P = the combined policy of 4.2's tables,
`OpenActionPolicy(QueueGuardPolicy(g), g, settings)`, with a mutable fake `GuardSettings` `g` and a
mutable fake `OpenActionSettings`. D = `OpenActionPolicy` over a recording fake `GuardPolicy`, with
a mutable fake `GuardSettings` of its own. Queue fixtures, written `queueSize`/`autoQueuedCount`:
"worth guarding" = `BACKGROUND` and `POPUP` with 1/0 and 3/0, `BACKGROUND` with 3/2, `MAIN` with
2/0, 3/0, 3/1 and 5/3; "single in main" = `MAIN` with 1/0, 2/1, 3/2, 2/2 and 1/1 (6.1).
C = `QueueGuardController(P, mutable fake PlayerStateSource)` with recording `TapActions`,
`ListActions`, `GuardUi`. M = `RememberChoice` with a recording `RememberStore`. R = the JDK XML
parser over this feature's two resource files, QG's `res/values/enhancements_queue_guard.xml` and
`res/xml/video_audio_settings.xml` (path fallback as in QG plan 5). "Manual" = on a device.

| AC | Implemented by | Verified by |
|---|---|---|
| 1 | S2, section 5, `DefaultTapAction` | JVM R: S2 is the element right after the one with `@string/enh_queue_guard_ask_key`; `defaultValue` `play_now` = `DefaultTapAction.DEFAULT.prefValue`; `summary` `%s`; the values array = `DefaultTapAction.values().map { it.prefValue }`; entries resolve to "Play now", "Add to queue", "Play next"; title text. `fromPrefValue`: each value, and `null` and unknown give `PLAY_NOW`. Manual: Settings > Player, summary after a change (preference screen) |
| 2 | S3, section 5, `StartPlaybackIn`, rows 5-12, 17-18 | JVM R: S3 is the element right after S2; default `video_page` = `StartPlaybackIn.DEFAULT.prefValue`; values array = the enum's `prefValue`s; three texts, title; `fromPrefValue` as for AC-1. P: while playing, `onTap` and `onTapChoice` give the same result for all three values. Manual: Settings |
| 3 | rows 7-12, the switch read through `guardSettings` | JVM P: each row for every kind, MPM and start-in value, over the queues worth guarding and over single in main (rows 7 to 10 there). D: with the switch off the result is `base.onTapChoice(defaultAction.choice, tap, player)`, whatever `base.onTap` would answer (`OPEN_AS_TODAY`, `START_BACKGROUND`, `START_POPUP`). C: only `enqueue()` / `enqueueNext()` / `startInBackground()` / `startInPopup()` is called, `true` is returned, no UI; `PLAY_NOW` on `MAIN` returns `false`; a queue is started by tapping (`MAIN` 1/0, *Add to queue*: `enqueue()`, then with 2/0 again `enqueue()`). Manual: one tap per action (toast and queue equal the dialog choice); with ask off and *Add to queue*, watch one video in the main player and tap another: it is enqueued |
| 4 | rows 6, 6b, 14-16 | JVM P: for all 9 combinations of the two settings, `ASK` over the queues worth guarding (row 6) and `OPEN_AS_TODAY` over single in main, with and without MPM (row 6b); equal to `QueueGuardPolicy` alone in both. D: with the switch on the result is `base.onTap`'s answer whatever it is (`ASK`, `OPEN_AS_TODAY`, `START_BACKGROUND`, `START_POPUP`), never the default action's; the switch is read on every call. C: row 6 only `askForTap`, no action; row 6b returns `false`, no UI, no action. Manual: ask on, *Add to queue*, one video in the main player, tap another: it opens and plays as today |
| 5 | rows 2-4 | JVM P for every default action, ask on and off. C: `VIDEO_PAGE` returns `false` and calls nothing; `BACKGROUND_PLAYER` calls only `startInBackground()`, `POPUP_PLAYER` only `startInPopup()`, both return `true` without UI. Manual: playback starts, list stays, popup permission prompt (player service and system permission) |
| 6 | row 1 | JVM P, C (returns `false`) for every start-in value |
| 7 | `RememberChoiceAddOn`, layout, string | JVM R: the layout has exactly one `CheckBox`, its text is `@string/enh_default_open_action_remember` = "Remember my choice", no `android:checked`. Manual: shown in the tap dialog, unchecked on every opening (view) |
| 8 | `RememberChoice`, `PrefsOpenActionSettings.save` | JVM M: 4.3. Scenario with one in-memory fake that is `GuardSettings` (for both policies), `OpenActionSettings` and `RememberStore`: ask on, a queue worth guarding; tap -> prompt; `RememberChoice.onChoice(PLAY_NEXT, true)`, then the prompt's callback -> only `enqueueNext()`; the fake holds `PLAY_NEXT` and ask off; a second tap -> `enqueueNext()` without UI. Same for the other two choices. The remembered action also applies once the main player is down to a single video (AC-3). Manual: both values shown in Settings afterwards (preference glue) |
| 9 | 4.3; QG's dialog calls the add-on for no dismissal | JVM M: `CANCEL` checked, and every choice unchecked, save nothing. Manual: Cancel, Back, outside tap, rotation with the box ticked (dialog lifecycle) |
| 10 | G2 feeds only `onVideoTap`; QG builds the list dialog with no extension (QG plan 3.3) | Manual: the list dialog has no checkbox (the box is attached by QG's Android dialog code; nothing to call on the JVM) |
| 11 | rows 20-23, the switch read through `guardSettings`, QG's M1 | JVM P: the five rows, row 21 for both enqueue actions and row 21b for ask on and for `PLAY_NOW`, rows 21 and 21b over `MAIN` with a queue and over single in main, row 20 for `BACKGROUND` with auto-enqueued videos. D: while something is playing and `base` does not offer the entry, the result is "switch off and not `PLAY_NOW`", whatever `base.onListPlay` answers. C: `offersStreamDetails()`. Manual: the entry appears and opens the page (QG's menu glue); ask on, *Add to queue*, one video in the main player: no entry |
| 12 | row 5 | JVM P: `OPEN_AS_TODAY` for every combination of the settings, ask and kind, single in main included; D: the current item gets `base.onTap`'s answer with the switch on and off; C returns `false` |
| 13 | row 19 | JVM P. C: the fake player is set to `PlayerState.IDLE` between prompt and choice, for each start-in value and MPM |
| 14 | `PLAY_NOW` + `VIDEO_PAGE` delegate everything (4.2, "AC-14 over the whole grid") | JVM: over the whole grid (kind incl. `null`, queue size 0, 1 and 3, the queues with auto-enqueued videos of both fixtures, current or not, ask, MPM, every `GuardChoice`), P with default settings equals `QueueGuardPolicy` alone in all five functions; `DEFAULT` of both enums |
| 15 | `onListPlay`, `onListChoice` | JVM P: equal to `QueueGuardPolicy` alone for all 9 setting combinations; `ASK` with ask on only over the queues worth guarding, `PLAY_AS_TODAY` over single in main. D: arguments and result unchanged. C: `ListActions` as with QG alone |
| 16 | section 5 | JVM R: every text is a `<string>` in `values/`; only the two keys and the two value arrays are `translatable="false"` |

Wiring on the JVM (recommended): lines containing `ENH:default-open-action`: `QueueGuard.kt` 2 (one
with `DefaultOpenAction.policy(QueueGuardPolicy(PrefsGuardSettings), PrefsGuardSettings)`, one with
`DefaultOpenAction.tapDialogExtension`), `res/xml/video_audio_settings.xml` 2. QG's own counts and
its S1 position test (QG plan 5) stay as they are.

### 6.1 For the Tests layer: what A8 changes

The 144 existing tests, by class. "Change" = the test's expectation, its scripted setup or the
states it runs over change. "Stay" = the body of the test is untouched; where the column says so,
the shared fixture it is built on changes. Counted by reading the tests against the rules of 4.2,
not from a run.

| Class | Tests | Stay | Change | New |
|---|---|---|---|---|
| `OpenActionPolicyTest` | 41 | 38 | 3 | 11 |
| `OpenActionControllerTest` | 25 | 22 | 3 | 6 |
| `OpenActionPolicyDelegationTest` | 11 | 6 (field changes) | 5 | 1 |
| `DefaultSettingsEqualQueueGuardTest` | 5 | 0 | 5 | 0 |
| `RememberChoiceScenarioTest` | 9 | 9 (`Run` changes) | 0 | 1 |
| `DefaultOpenActionWiringTest` | 6 | 5 | 1 | 0 |
| `DefaultOpenActionResourcesTest` | 23 | 23 | 0 | 0 |
| `OpenActionModelTest` | 16 | 16 | 0 | 0 |
| `RememberChoiceTest` | 8 | 8 | 0 | 0 |
| Total | 144 | 127 | 17 | 19 |

The names of new tests below are proposals; the AC and the row in them are not.

`Fakes.kt`:

- `CombinedPolicy.policy` becomes `OpenActionPolicy(QueueGuardPolicy(askSwitch), askSwitch, settings)`:
  one `FakeAskSwitch` for both, as G1 wires it. `queueGuardAlone` stays.
- `playing(...)` gets a last parameter `autoQueued: Int = 0`, passed on as `autoQueuedCount`.
  `playingIn`, `SOMETHING_PLAYING` (each player with 1/0 and 3/0) and `NOTHING_PLAYING` stay as
  they are; `SOMETHING_PLAYING` keeps `MAIN` with one video, which the ask-off tests need.
- Two fixtures are added, this package's own (nothing comes from QG's test package), with the
  content of QG's (QG plan 5.1), every state on `CURRENT_URL`. `WORTH_GUARDING`: `BACKGROUND` and
  `POPUP` with 1/0 and 3/0, `BACKGROUND` with 3/2, `MAIN` with 3/0, 2/0, 3/1 and 5/3.
  `SINGLE_IN_MAIN`: `MAIN` with 1/0, 2/1, 3/2, 2/2 and 1/1.
- `InMemoryPreferences`, `RecordingGuardPolicy`, `Scene` and the other fakes stay.

`OpenActionPolicyTest` (P):

- Change, three tests that expect `ASK` with ask on in every state of `SOMETHING_PLAYING`, which
  contains `MAIN` with 1/0:
  - `ac4_row6_askOn_tapAsksForAllNineCombinationsOfTheTwoSettings`: over `WORTH_GUARDING`.
  - `ac12_ac3_rows6to8and11_sameUrlOnAnotherServiceIsNotTheCurrentItem`: its row 6 line over
    `WORTH_GUARDING`; its rows 7, 8 and 11 lines stay.
  - `ac15_playButtonsAskOrPlayAsTodayWhateverTheTwoSettingsAre`: with ask on, `ASK` over
    `WORTH_GUARDING` and `PLAY_AS_TODAY` over `SINGLE_IN_MAIN`; with ask off `PLAY_AS_TODAY` over
    both; `NOTHING_PLAYING` as before.
- Stay, 38. Among them, and they must keep running over `SOMETHING_PLAYING` or
  `playingIn(MAIN)` with its single video: rows 7 to 10 (`ac3_row7_…`, `ac3_row8_…`,
  `ac3_row9_…`, `ac2_ac3_row10_…`), which now are AC-3's "also on a single video in the main
  player"; `ac12_row5_…`; rows 13 to 19; rows 20 to 23, of which
  `ac11_row21b_mainPlayerInUse_askOn_streamDetailsIsNotOfferedWhateverTheDefaultAction` fails
  against the code as it is and passes once the code follows this plan; the three
  `ac2_…whileSomethingIsPlaying…`; `ac1_ac2_aChangedSettingAppliesToTheNextCallOfTheSamePolicy`;
  the other three `ac15_…`.
- New, 11:
  - `ac4_row6b_askOn_singleVideoInTheMainPlayerOpensAsTodayForAllNineCombinationsOfTheTwoSettings`:
    `SINGLE_IN_MAIN`, a tap on another video with and without MPM -> `OPEN_AS_TODAY`.
  - `ac4_rows6and6b_askOn_tapGetsQueueGuardsAnswerInEveryPlayingState`: ask on, 9 combinations,
    `SOMETHING_PLAYING + WORTH_GUARDING + SINGLE_IN_MAIN`, every tap: equal to
    `combined.queueGuardAlone.onTap` (AC-4, "exactly as queue-guard specifies").
  - `ac3_row7_askOff_addToQueueEnqueuesOnASingleVideoInTheMainPlayer`: `SINGLE_IN_MAIN`, with and
    without MPM, every start-in value -> `ENQUEUE`.
  - `ac3_row8_askOff_playNextEnqueuesNextOnASingleVideoInTheMainPlayer`: likewise ->
    `ENQUEUE_NEXT`.
  - `ac3_rows9and10_askOff_playNowOnASingleVideoInTheMainPlayerOpensAsToday`: likewise ->
    `OPEN_AS_TODAY`.
  - `ac3_rows7to12_askOff_queuesWithAutoEnqueuedVideosGetTheDefaultAction`: ask off,
    `WORTH_GUARDING`: `ENQUEUE`, `ENQUEUE_NEXT`, and for `PLAY_NOW` without MPM `OPEN_AS_TODAY` /
    `START_BACKGROUND` / `START_POPUP` by kind.
  - `ac12_row5_tapOnTheCurrentItemOpensAsTodayOnASingleVideoInTheMainPlayerWhateverTheSettings`:
    `SINGLE_IN_MAIN`, all 18 combinations, with and without MPM.
  - `ac11_row21_singleVideoInTheMainPlayer_askOff_addToQueueOrPlayNextOffersStreamDetails`:
    `SINGLE_IN_MAIN` -> `true`.
  - `ac11_row21b_singleVideoInTheMainPlayer_streamDetailsIsNotOfferedWithAskOnOrPlayNow`:
    `SINGLE_IN_MAIN`; ask on with each default action, and ask off with `PLAY_NOW` -> `false`.
  - `ac4_rows13to18_aDialogChoiceOnASingleVideoInTheMainPlayerIsQueueGuards`: `onTapChoice` over
    `SINGLE_IN_MAIN`, all 18 combinations: `NONE`, `ENQUEUE`, `ENQUEUE_NEXT`, `OPEN_AS_TODAY`
    (the dialog was opened for a queue that has shrunk since).
  - `ac3_ac4_theAskSwitchIsReadAtEveryTap`: one policy object, `MAIN` 1/0, *Add to queue*: ask on
    -> `OPEN_AS_TODAY`, switched off -> `ENQUEUE`, on again -> `OPEN_AS_TODAY`; the same three
    steps for `offersStreamDetails`: `false`, `true`, `false`.

`OpenActionControllerTest` (C):

- Change, three tests that expect a dialog in every state of `SOMETHING_PLAYING` with ask on:
  `ac4_row6_askOn_tapOnlyOpensTheDialogForAllNineCombinationsOfTheTwoSettings`,
  `ac9_row13_cancelInTheDialogCallsNoAction`,
  `ac15_askOn_playButtonOpensTheListDialogAndItsChoicesAreQueueGuardsWhateverTheTwoSettingsAre`:
  over `WORTH_GUARDING`.
- Stay, 22: the six `ac3_…` (ask off, `MAIN` with one video included),
  `ac4_askOn_theChoiceMadeInTheDialogIsCarriedOutNotTheDefaultAction` and the `ac13_…`
  (`playing(kind)` is three videos), `ac5_…`, `ac6_…`, both `ac11_…` (the row 21b part with ask
  on fails against the code as it is, as in P), `ac12_…`, `ac14_…`, the other three `ac15_…`.
- New, 6:
  - `ac4_row6b_askOn_tapOnASingleVideoInTheMainPlayerIsLeftToTodaysTapHandler`: `SINGLE_IN_MAIN`,
    9 combinations, with and without MPM: `tap` returns `false`, the log is empty.
  - `ac3_rows7and8_askOff_theDefaultActionIsCarriedOutOnASingleVideoInTheMainPlayer`:
    `SINGLE_IN_MAIN`: only `enqueue()` / only `enqueueNext()`, `true`, no dialog.
  - `ac3_ac4_aQueueIsStartedByTappingAndGuardedOnceItExists`: one scene. Ask off, *Add to
    queue*, `MAIN` 1/0: tap -> `enqueue`; player set to `MAIN` 2/0: tap -> `enqueue`; ask on:
    tap -> only `askForTap` with `queueSize` 2; player set to `MAIN` 1/0: tap returns `false`
    and adds nothing to the log.
  - `ac11_rows21and21b_singleVideoInTheMainPlayer_controllerOffersStreamDetailsOnlyWithAskOffAndAnEnqueueAction`:
    `SINGLE_IN_MAIN`, all 18 combinations.
  - `ac14_defaultSettings_tapsRunAsWithQueueGuardAloneOnQueuesWithAutoEnqueuedVideos`: the
    transcript comparison of `ac14_defaultSettings_tapsAndDialogChoicesRunAsWithQueueGuardAlone`
    over `WORTH_GUARDING + SINGLE_IN_MAIN`.
  - `ac15_playButtonsRunAsWithQueueGuardAloneOnQueuesWithAutoEnqueuedVideos`: the same for
    `playButtonTranscript`, all 18 combinations.

`OpenActionPolicyDelegationTest` (D):

- The field becomes `OpenActionPolicy(base, askSwitch, settings)` with a `FakeAskSwitch` of the
  class. All 11 tests go through it.
- Change, 5:
  - `ac3_delegation_theDefaultActionGetsTheWrappedPolicysAnswerToTheDialogChoiceOfTheSameName`
    and `ac3_delegation_theWrappedPolicyGetsTheChoiceOfTheDefaultActionWithTheTapAndThePlayerStateUnchanged`:
    the switch is off (with it on they would get the tap's answer); the scripted answers to the
    tap stay `OPEN_AS_TODAY`, `START_BACKGROUND`, `START_POPUP`. `ASK` is not scripted there:
    QG's policy never asks with the switch off, and 4.2 leaves no room for another result anyway.
  - `ac4_delegation_whetherToAskIsTheWrappedPolicysAnswerToTheTap`: the switch is on, and the
    wrapped policy answers the tap, in turn, `ASK`, `OPEN_AS_TODAY`, `START_BACKGROUND` and
    `START_POPUP`: each time that answer is the result, under all 9 settings, and the wrapped
    policy was asked `onTap` with tap and state unchanged. None of the four is among
    `unusualChoiceAnswers`' values for the three actions, so the default action was not used.
    The quoted plan sentence ("It never reads the ask switch") is gone.
  - `ac12_delegation_theCurrentItemGetsTheWrappedPolicysAnswerToTheTapNotTheDefaultAction`: with
    the switch on and off (off is the case that proves it).
  - `ac11_delegation_askOffIsTheWrappedPolicyNotAskingForAPlayButton`: turned round. For
    `playingIn(MAIN)`, `base.streamDetailsAnswer = false` and `base.listAnswer` both `ASK` and
    `PLAY_AS_TODAY`: the result is `false` with the switch on and
    `settings.action != PLAY_NOW` with it off, whatever `listAnswer` is. Proposed name:
    `ac11_delegation_askOffIsTheSwitchNotTheWrappedPolicysAnswerToAPlayButton`.
- Stay, 6: both `ac4_delegation_…DialogChoice…`, both `ac15_delegation_…`,
  `ac11_delegation_whereTheWrappedPolicyOffersStreamDetailsTheEntryStays`,
  `ac11_delegation_whileNothingIsPlayingOnlyStartPlaybackInDecides`. They hold with the switch on
  and off; running them under both is welcome, not required.
- New, 1: `ac3_ac4_ac11_delegation_theWrapperDoesNotJudgeTheQueueItself`: the scripted wrapped
  policy and the expectations of the three tests above (default action with the switch off, the
  tap's answer with it on, the menu entry), over `WORTH_GUARDING + SINGLE_IN_MAIN`: a state that
  is or is not worth guarding gets the same result, because only the wrapped policy looks at
  that.

`DefaultSettingsEqualQueueGuardTest`:

- The field becomes `OpenActionPolicy(QueueGuardPolicy(askSwitch), askSwitch, FakeOpenActionSettings(…))`.
- Change, all 5, in the same way: the grid grows and with it the asserted number of cases.
  `queueSizes` becomes 0, 1 and 3 (12 states with the four kinds), plus `PlayerState.IDLE`, plus
  the eight states with auto-enqueued videos or two videos that the two fixtures add (`BACKGROUND`
  3/2; `MAIN` 2/0, 3/1, 5/3, 2/1, 3/2, 2/2, 1/1): 21 states, something playing in 14, of which 5
  are not worth guarding. Cases: `onTap` 2 x 21 x 4 = 168; `onTapChoice` 672; `onListPlay` 42;
  `onListChoice` 168; `offersStreamDetails` 42. The comparisons themselves stay.

`RememberChoiceScenarioTest`:

- `Run.policy` becomes `OpenActionPolicy(QueueGuardPolicy(preferences), preferences, preferences)`.
  The 9 tests stay: `playing(kind)` is three videos, a queue worth guarding in every player.
- New, 1: `ac3_ac8_aRememberedActionAlsoAppliesToASingleVideoInTheMainPlayer`: `MAIN`, three
  videos, ask on; tap -> dialog; *Add to queue* with the box checked -> `enqueue`, ask off
  saved; player set to `MAIN` 1/0; tap -> `enqueue` without dialog.

`DefaultOpenActionWiringTest`:

- Change, 1: `ac3_ac5_ac11_hookG1_queueGuardWrapsItsPolicyInDefaultOpenAction` expects the call
  `DefaultOpenAction.policy(QueueGuardPolicy(PrefsGuardSettings), PrefsGuardSettings)`, class
  named in full. Its name may gain `ac4`. The other 5 stay: still two marked lines and no import
  in `QueueGuard.kt`, S2 and S3 untouched.

`DefaultOpenActionResourcesTest`, `OpenActionModelTest`, `RememberChoiceTest`: no change. No
resource, enum or `RememberChoice` rule changed.

Until the code follows, the test source set does not compile (everything that constructs
`OpenActionPolicy` passes three arguments). Against the rules as the code has them now, the
expectations that fail are those of row 6b and of row 21b with ask on, and nothing else should.

## 7. Order of work

(0) QG's code is on the branch. (1) `res/values/enhancements_default_open_action.xml`, the layout,
then S2 and S3. (2) `OpenActionModel.kt`, `OpenActionPolicy.kt`, `RememberChoice.kt`, until the unit
tests pass. (3) `DefaultOpenAction.kt`. (4) G1 and G2; `git grep -c "ENH:default-open-action"` gives
the counts of section 6 and QG's unit tests still pass. (5) Device pass over the "Manual" cells
with no player, the main, the background and the popup player.

For A8, on top of the built feature: (a) `OpenActionPolicy`: the third constructor parameter, the
two rules of 4.2, the class comment; (b) `DefaultOpenAction.policy` and G1, in one commit, since
neither compiles without the other; (c) the unit tests of 6.1 pass and QG's own stay green;
(d) device pass over the new "Manual" lines of AC-3, AC-4 and AC-11, with "Auto-enqueue next
stream" on and off.

## 8. Risks and edge cases

- "Remember my choice" switches off the one switch that also covers the play buttons (QG AC-15,
  QG AC-16): afterwards Play All, Background and Popup replace the queue without asking.
- The default action is unused while the switch is on (AC-4), which is the default, and the
  setting is not greyed out then: that dependency would have to be declared on QG's S1 line.
- With the switch on and *Add to queue* or *Play next* selected, a tap on a single video in the
  main player replaces it, as upstream does (row 6b, A8). A queue is then started with the
  long-press "Enqueue"; from the second video on, taps ask.
- With the switch off and *Add to queue* or *Play next*, no tap in the main player plays a video
  at once, from the first video on (rows 7 and 8). The way there is "Show stream details"
  (row 21), which replaces the queue without asking; the user has switched asking off (A3).
- Row 7 on one video with auto-enqueued ones behind it: upstream's append drops an auto-enqueued
  tail when the user enqueues (`player/playqueue/PlayQueue.java:331-337`), as for the long-press
  "Enqueue". The queue is worth guarding from then on, which matters only once asking is on again.
- G1 hands one `GuardSettings` to both policies. Two different objects would compile and could
  disagree; the wiring test pins the line (6.1).
- QG AC-7 and QG AC-16 describe the app only while this feature's settings are on their defaults
  (AC-14). QG's unit tests build `QueueGuardPolicy` themselves and stay green.
- `QueueGuard`'s initialiser runs G1 and G2: `DefaultOpenAction` and `PrefsOpenActionSettings` must
  not touch `App.getApp()` or resources in an initialiser and must not refer to `QueueGuard`.
  `OpenActionPolicy`'s constructor only stores its three arguments; the switch is read inside the
  calls. QG's Q1 hook loads `QueueGuard` off the main thread (QG plan 8), which makes this firm.
- *Popup player* without the overlay permission: every tap opens the system permission screen with
  a toast and plays nothing (`util/NavigationHelper.java:165-168`). Choosing the value in Settings
  does not ask for it (that would be a hook at `settings/VideoAudioSettingsFragment.java:27`).
- After a first tap with *Background player* or *Popup player*, taps count as "nothing is playing"
  until the player has registered (`player/helper/PlayerHolder.java:168-185`): each starts its
  video anew, as repeated long-press starts do.
- The checkbox is below the three rows and a row acts at once, so it has to be ticked first.
- Stored values outside the lists (an imported backup) fall back to the defaults (`fromPrefValue`).
- QG plan 8 applies unchanged to the effects reused here (sparse feed items are loaded first,
  *Play next* on a one-item queue, url forms of the current item).

## 9. Assumptions

Not a question, since "sits with" and "beside it" leave it to the plan: S2 and S3 go directly
below QG's switch, default action first (AC-1, AC-2). The questions this plan raised are answered:

| Q | Outcome | Affects |
|---|---|---|
| Q3 | A3 changed the spec: "Show stream details" is also offered while the main player is in use, the switch is off and the default action is *Add to queue* or *Play next* (row 21, the `offersStreamDetails` rule in 4.2). QG's M1 entry already opens the page as a tap does today | AC-11 |
| Q4 | A4 confirmed: *Play now* after the player was closed follows AC-6 in a local playlist with Music Player Mode on (row 19 through row 1) | AC-6, AC-13 |
| Q5 | A5 confirmed: *Popup player* starts audio-only items in the popup player too | AC-5 |
| Q6 | A6 confirmed, and QG plan 3.3 now states it: the add-on is called only for a row click that is then carried out | AC-8, AC-9 |
| Q7 | A7 (this plan): rows 14 to 16 belong to AC-4 | AC-4 |
| Q8 | A8 (the user, through the spec): with asking on, a single video in the main player opens as today and the default action is not used; with asking off the default action applies there too. Rows 6 and 6b, the switch read through `GuardSettings` (2, 4.2, 6.1) | AC-3, AC-4, AC-11 |

The amendment for A8 raised no question. Decided by this plan, within the spec's words: "a single
video in the main player" (AC-3, AC-4) is QG's "not a queue worth guarding" while something is
playing, auto-enqueued videos not counted, and is judged by QG's policy alone; while the switch is
off, what the wrapped policy would answer to the tap itself is not consulted.

## Amendment 2

For spec section "Amendment 2" (AM-1 to AM-3, spec commit `66b32fa`). It follows queue-guard's
amendment 2: "QG A2.n" is a part of the section "Amendment 2" of
`enhancements/queue-guard/plan.md` on `feature/queue-guard`. This branch does not hold that
section, nor queue-guard's amended spec, until it is rebased onto `feature/queue-guard`; read
them there.

**What this section overrides.** The note at the top of this plan ("Changed 2026-11-15"); in 4.2
the rule of `onTap` and of `onTapChoice` (each gets a first clause), row 6b, the paragraph and
table "The main player with a single video, complete", and every mention of `worthGuarding` and
`autoQueuedCount`; 4.3 (the result of `onChoice`); in 4.4 the row `RememberChoiceAddOn`; in
section 5 one new string; in section 6 the rows AC-4, AC-8, AC-14; 6.1; in section 8 the risk
"With the switch on and *Add to queue* or *Play next* selected, a tap on a single video in the
main player replaces it"; in section 9 the row Q8. Everything else stands: G1, G2, S2, S3, 4.1,
rows 1 to 5 and 7 to 23, the layout.

Summary: no hook is added, changed or removed. Lines marked `ENH:default-open-action` stay at
`enhancements/queueguard/QueueGuard.kt` 2 and `res/xml/video_audio_settings.xml` 2. Changed:
`OpenActionPolicy.kt` (two clauses), `RememberChoice.kt` (a result), `DefaultOpenAction.kt` (the
message), one string.

### B2.1 What queue-guard's amendment changes underneath (QG A2.1, A2.2, A2.4)

- `PlayerState` has no `autoQueuedCount` and no `worthGuarding`: "a queue worth guarding" is
  `somethingPlaying`. `QueueGuardPolicy.onTap` asks for one video in the main player again.
- `TapRequest` has two more fields with defaults: `way: OpenWay` (`LIST_TAP` for a video tap in a
  list; `PAGE_CONTROL`, `VIDEO_PAGE`, `BACKGROUND_PLAYER`, `POPUP_PLAYER` for the paths the
  amendment adds: the video page's controls, links, an address typed in search, a video from
  another app) and `relatedTo` (the video whose related list was tapped).
- The tap dialog, and with it the checkbox of G2, now also opens from those paths.
  `DialogGuardUi` builds the add-on with a `Context` that may be a themed wrapper instead of the
  activity (QG A2.4, the router); `TapDialogExtension.create(context)` is unchanged.

### B2.2 `OpenActionPolicy.kt` (AM-2, AM-3)

Signature unchanged. Two clauses come first; the rest of both functions is as 4.2 has it.

- `onTap`: `tap.way != OpenWay.LIST_TAP` -> `base.onTap(tap, player)`, whatever is playing and
  whatever the three settings are. Otherwise as before.
- `onTapChoice`: `tap.way != OpenWay.LIST_TAP` -> `base.onTapChoice(choice, tap, player)`.
  Otherwise as before.
- `onListPlay`, `onListChoice`, `offersStreamDetails`, *idle*: unchanged.

So neither the default action nor "Start playback in" ever applies to a page control, a link or
a video from another app, also while nothing is playing and also when the player was closed
while their dialog was open: those requests get queue-guard's answer (QG A2.2 rows 1, 2, 4, 6, 7
and 8 to 11). The class still reads neither `relatedTo` nor the queue size.

Rule table for `OpenActionPolicy(QueueGuardPolicy(g), g, settings)`, `onTap`, list taps
(`way == LIST_TAP`). Rows 1 to 5 and 7 to 12 are the rows of 4.2, unchanged; the column "worth
guarding" of 4.2 is gone.

| # | playing | current | ask | tap in the related list of the current item's page | Result | Criterion |
|---|---|---|---|---|---|---|
| 1-4 | no | - | any | any | as 4.2 ("Start playback in") | AC-5, AC-6 |
| 5 | yes | yes | any | any | `OPEN_AS_TODAY` | AC-12 |
| 6 | yes | no | on | no | `ASK`, in every player and for a single video too | AM-2, AC-4 (QG AM-1) |
| 6c | yes | no | on | yes | `OPEN_AS_TODAY` | AM-2 (QG AM-2) |
| 7-12 | yes | no | off | any | as 4.2 (the default action) | AC-3 |

Row 6b of 4.2 (a single video in the main player opens as today) no longer exists: that state is
row 6. Row 6c is new and is queue-guard's answer, not a rule of this class. With asking off a
tap in the related list of the current item's page gets the default action like any other tap
(rows 7 to 12; Q9).

Requests of another `way`, for every state and all 18 combinations of the settings:

| Function | Result |
|---|---|
| `onTap` | `base.onTap`: `OPEN_AS_TODAY` while nothing is playing, for the current item, with asking off, and where the path would not replace the queue; else `ASK` (AM-3) |
| `onTapChoice` | `base.onTapChoice`: `NONE`, `ENQUEUE`, `ENQUEUE_NEXT` (`ENQUEUE` once nothing is playing), and `OPEN_AS_TODAY` for *Play now*, never `START_BACKGROUND` or `START_POPUP` |

`offersStreamDetails`, rows 20 to 23: results unchanged. Row 21b with asking on is now `false`
because every tap on another video asks; there is no state left in which it opens the page.

AC-14 still holds over the whole grid, by the steps of 4.2: for a list tap nothing in them used
`worthGuarding`, and with asking off `base.onTap` is *playNow* (QG A2.2 row 3), which is what
`base.onTapChoice(PLAY_NOW, ...)` gives; every other `way` is `base` by the two new clauses.

### B2.3 `RememberChoice.kt` (AM-1, plain Kotlin)

```kotlin
class RememberChoice(private val store: RememberStore) {
    /**
     * A choice made in the tap dialog, before it is carried out. [checked]: the checkbox at that moment.
     * Returns whether the choice was saved, which is when the notice "asking is now off" is due (AM-1).
     */
    fun onChoice(choice: GuardChoice, checked: Boolean): Boolean
}
```

| `checked` | `choice` | `store.save` | Result |
|---|---|---|---|
| yes | `ADD_TO_QUEUE`, `PLAY_NEXT`, `PLAY_NOW` | once, `(DefaultTapAction.fromChoice(choice), false)` | `true` |
| yes | `CANCEL` | not called | `false` |
| no | any | not called | `false` |

The notice is due for every saved choice, *Play now* included, and also when asking was off
already or the saved action equals the old one: the result does not look at what was stored
before. `RememberStore` is unchanged.

### B2.4 `DefaultOpenAction.kt` (AM-1, glue)

`RememberChoiceAddOn(context, remember)` keeps `context` and shows the message:

```kotlin
override fun onChoice(choice: GuardChoice) {
    if (remember.onChoice(choice, checkBox.isChecked)) {
        Toast.makeText(
            context,
            context.getString(
                R.string.enh_default_open_action_asking_off,
                context.getString(R.string.settings),
                context.getString(R.string.settings_category_player_title),
                context.getString(R.string.enh_queue_guard_ask_title),
            ),
            Toast.LENGTH_LONG,
        ).show()
    }
}
```

- Shown from the add-on, which queue-guard's dialog calls for a row click that is then carried
  out, right before it (QG plan 3.3): the message comes with every remembered choice and with no
  other click. `context` is the dialog's, so the text is in the app's language.
- A toast, not a dialog: the choice is carried out at once, and three of its results are toasts
  themselves ("Enqueued", ...). Android shows toasts one after the other; this one is first.
- The two names in it are the screen's and the switch's own strings (`res/xml/main_settings.xml:10`,
  QG plan 4), so the message stays right in every language and after a rename.
- `PrefsOpenActionSettings`, `DefaultOpenAction.policy`, `tapDialogExtension`: unchanged. Nothing
  new runs in an initialiser.

### B2.5 Resource (adds to section 5)

In `res/values/enhancements_default_open_action.xml`:

| Name | English text |
|---|---|
| `enh_default_open_action_asking_off` | `Asking is now off. To switch it on again: %1$s > %2$s > %3$s` |

Written in the file with `&gt;` for `>`. Translatable. With the English strings it reads:
"Asking is now off. To switch it on again: Settings > Player > Ask before replacing the queue".

### B2.6 Criteria

| AM | Implemented by | Verified by |
|---|---|---|
| 1 | B2.3, B2.4, B2.5 | JVM: the table of B2.3; the string's text and its three placeholders; `DefaultOpenAction.kt` names `R.string.enh_default_open_action_asking_off` (read as text). Manual: B2.8 step 1 |
| 2 | queue-guard's row 1 and row 5 through `base.onTap`; rows 6 and 6c | JVM: with asking on, equal to `QueueGuardPolicy` alone in every playing state, a single video in the main player included, for all 9 combinations. Manual: step 2 |
| 3 | the two clauses of B2.2 | JVM: over a recording wrapped policy and over `QueueGuardPolicy`: a request of another `way` gets the wrapped answer for all 18 combinations, playing or not. Manual: step 3 |

### B2.7 Tests (replaces 6.1)

- `Fakes.kt`: `playing(...)` loses `autoQueued`; the fixtures `WORTH_GUARDING` and
  `SINGLE_IN_MAIN` go, `SOMETHING_PLAYING` (each player with one item and with three) covers
  both. New helpers: a tap of each other `OpenWay`, a tap in the related list of the current item.
- `OpenActionPolicyTest`. Now the opposite of what it pinned:
  `ac4_row6b_askOn_singleVideoInTheMainPlayerOpensAsTodayForAllNineCombinationsOfTheTwoSettings`
  (-> `ASK`; it merges into `ac4_row6_...`); `ac3_ac4_theAskSwitchIsReadAtEveryTap` (main player,
  one video, *Add to queue*: asking on gives `ASK`, off `ENQUEUE`; its `offersStreamDetails` half
  stays `false`, `true`, `false`); the part of
  `ac15_playButtonsAskOrPlayAsTodayWhateverTheTwoSettingsAre` that expects `PLAY_AS_TODAY` for a
  single video with asking on (-> `ASK`). Back on `SOMETHING_PLAYING`: `ac4_row6_...`,
  `ac4_rows6and6b_...` (still "equal to queue-guard alone"), the row 6 line of
  `ac12_ac3_rows6to8and11_...`. Removed: `ac3_rows7to12_askOff_queuesWithAutoEnqueuedVideosGetTheDefaultAction`.
  Same result on a plain state (`MAIN`, one item): every other `...SingleVideoInTheMainPlayer...`
  test (rows 5, 7, 8, 9/10, 13 to 18, 21, 21b). New: row 6c with asking on and rows 7, 8 for the
  same tap with asking off; B2.2's second table for all 18 combinations, nothing playing with
  *Background player* and asking off with *Add to queue* included.
- `OpenActionControllerTest`. Now the opposite:
  `ac4_row6b_askOn_tapOnASingleVideoInTheMainPlayerIsLeftToTodaysTapHandler` (-> only the dialog,
  with "1"); the last step of `ac3_ac4_aQueueIsStartedByTappingAndGuardedOnceItExists` (asking
  on, one video: the dialog). Removed:
  `ac14_defaultSettings_tapsRunAsWithQueueGuardAloneOnQueuesWithAutoEnqueuedVideos`,
  `ac15_playButtonsRunAsWithQueueGuardAloneOnQueuesWithAutoEnqueuedVideos`. Back on
  `SOMETHING_PLAYING`: `ac4_row6_...`, `ac9_row13_...`, `ac15_askOn_playButtonOpensTheListDialog...`.
  `ac11_rows21and21b_singleVideoInTheMainPlayer_...` keeps its results. New: a page-control
  request with asking off and *Add to queue*: `false`, nothing called; with asking on: the
  dialog, *Play now* calls `openAsToday()`; nothing playing with *Popup player*: `false`.
- `OpenActionPolicyDelegationTest`: `ac3_ac4_ac11_delegation_theWrapperDoesNotJudgeTheQueueItself`
  runs over `SOMETHING_PLAYING`. New: for a request of another `way` the wrapped policy gets
  `onTap` / `onTapChoice` with tap and state unchanged and its answer is the result, with the
  switch on and off, every default action and start-in value, playing or not; `onTapChoice` is
  never asked in place of `onTap`.
- `DefaultSettingsEqualQueueGuardTest`: the grid loses the states with auto-enqueued videos
  (13 states: four kinds with 0, 1 and 3 items, and `IDLE`) and gains, per state, the taps of
  every `OpenWay` and the related tap; the asserted case counts follow from that. The five
  comparisons stay.
- `RememberChoiceTest`: the 8 stay. New: `onChoice` returns `true` for each of the three choices
  with the box checked, `false` unchecked and for `CANCEL`; it returns `true` exactly when one
  save was recorded; `true` again for a second remembered choice on the same store.
- `RememberChoiceScenarioTest`: the 10 stay (`Run` ignores the result or asserts it). New: main
  player with one video, asking on: tap -> dialog; *Play now* with the box checked -> notice due,
  asking off saved; the next tap opens as today without a dialog (AM-1 "*Play now* included").
- `DefaultOpenActionResourcesTest`: new, the string of B2.5 (parsed text, not
  `translatable="false"`). `DefaultOpenActionWiringTest`: the 6 stay; new, the text check of
  AM-1 in B2.6. `OpenActionModelTest`: unchanged.

Until queue-guard's amended code is below this branch, this test source set does not compile.

### B2.8 Device script

1. AM-1. Asking on. Play a video in the background, tap another in a list, tick "Remember my
   choice", choose *Play now*: it plays, and a message says asking is now off and names
   Settings > Player > Ask before replacing the queue. Open that screen: the switch is off.
   Switch it on and repeat with *Add to queue*: the message, then "Enqueued".
2. AM-2. Asking on, default action *Add to queue*. Play one video on its page, go back, tap
   another video: the dialog opens ("1 item in the queue"), nothing is enqueued by itself. On
   the page of the playing video tap a related video: it opens and plays, no dialog.
3. AM-3. Asking off, default action *Add to queue*, "Start playback in" *Popup player*.
   Background player with a queue; open another video's page through "Show stream details" and
   press its Background button: it plays there at once, nothing is enqueued. Tap a timed link in
   a description while the main player holds a queue: the video opens as before this feature.
   Close the player, share a link from another app and choose "Show info": the page opens, no
   popup starts. A tap in a list still enqueues.

### B2.9 Merging, risks, order

- Merging: the only upstream file of this feature is `res/xml/video_audio_settings.xml`, not
  touched here. `feature/keep-position`, `release-views`, `crash-fixes`, `crash-fixes-more`,
  `player-memory` and `subscription-scale` change none of this feature's files.
- Order: (1) queue-guard's amendment (code) is on `feature/queue-guard`; rebase this branch onto
  it. G1 and G2 sit on two lines of `QueueGuard.kt` that the amendment does not change. (2)
  `OpenActionPolicy.kt`, `RememberChoice.kt`. (3) the string, then `DefaultOpenAction.kt`. (4)
  tests of B2.7; queue-guard's own stay green. (5) device script.
- "Remember my choice" can now be ticked in a dialog opened from a video page control, a link or
  the router. It saves the same two settings; the saved default action then applies to list taps
  only, and with asking off those paths behave as today (Q9).
- With asking on the default action is never used any more (row 6 covers every playing state);
  it matters only after asking is switched off.
- Assumed, not run: a toast shown right before another toast is displayed first and in full
  (Android queues them); a toast created with a themed wrapper of the router shows after the
  router has finished. Checked in source: the add-on is called only for a row click that is then
  carried out (`QueueGuardDialog.kt:83-90`); `R.string.settings` and
  `R.string.settings_category_player_title` are the titles of `res/xml/main_settings.xml:5,10`.
- Q9 and Q10 are raised by this amendment and planned with the stated assumption.
