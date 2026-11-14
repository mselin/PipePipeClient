# Default open action: plan

How `spec.md` (AC-1..AC-16) is built on queue-guard. Paths are relative to
`app/src/main/java/org/schabi/newpipe/` unless they start with `res/` (`app/src/main/res/`). Line
numbers are those of `dev` at `cd87fc7`. QG = queue-guard: "QG plan 3.2" is a section of
`enhancements/queue-guard/plan.md`, "QG AC-n" a criterion of its spec, and its types are used
exactly as QG plan 3 defines them. `DOA` abbreviates
`org.schabi.newpipe.enhancements.defaultopenaction.DefaultOpenAction`; in the file, G1 and G2 carry
the full name, so that no import line is added.

## 1. Shape

No tap handler gets a new hook: QG's T1-T4 and M1 already send every video tap and long-press menu
through `QueueGuardController`, which asks one `GuardPolicy`. This feature wraps QG's policy in
`OpenActionPolicy` (plain Kotlin: default action and "Start playback in"; everything else goes to
the wrapped policy), gives QG's tap dialog a checkbox through `TapDialogExtension`, decided by
`RememberChoice` (plain Kotlin), and adds two settings. Every result is one of QG's `TapEffect`s,
carried out by QG's `FragmentTapActions`: no new action code.

## 2. Changes to queue-guard's files

Two single-line replacements in `enhancements/queueguard/QueueGuard.kt`, the two that QG plan 3.3
and 6 reserve. Each line keeps its place (`policy` stays above the controller that takes it).

| # | Line as QG plan 3.3 gives it | Becomes |
|---|---|---|
| G1 | `private val policy: GuardPolicy = QueueGuardPolicy(PrefsGuardSettings)` | `private val policy: GuardPolicy = DOA.policy(QueueGuardPolicy(PrefsGuardSettings)) // ENH:default-open-action` |
| G2 | `private val tapDialogExtension: TapDialogExtension? = null` | `private val tapDialogExtension: TapDialogExtension? = DOA.tapDialogExtension // ENH:default-open-action` |

Nothing else of QG changes: no other line of `QueueGuard.kt`, no other QG file, resource or hook.
`check-layers.sh` does not need the marker here (the file is not in `dev`); `git grep "ENH:"` does.

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
class OpenActionPolicy(private val base: GuardPolicy, private val settings: OpenActionSettings) : GuardPolicy
```

How it composes with `base`. It never reads the ask switch: "ask is on" is `base.onTap` answering
`ASK`. `base` is pure; how often it is called is not part of the contract.

- `onTap`: not `player.somethingPlaying` -> *idle* over `base.onTap(tap, player)`. Otherwise
  `b = base.onTap(tap, player)`; `b == ASK` or `player.isCurrent(tap)` -> `b`; otherwise
  `base.onTapChoice(settings.defaultAction().choice, tap, player)`.
- `onTapChoice`: `b = base.onTapChoice(choice, tap, player)`; `choice == PLAY_NOW` and not
  `player.somethingPlaying` -> *idle* over `b`; otherwise `b`.
- *idle* over `b`: `tap.startsListInBackground` or `startPlaybackIn() == VIDEO_PAGE` -> `b`;
  `BACKGROUND_PLAYER` -> `START_BACKGROUND`; `POPUP_PLAYER` -> `START_POPUP`.
- `onListPlay`, `onListChoice`: the base call, arguments and result unchanged.
- `offersStreamDetails`: `base.offersStreamDetails(player)`, or not `player.somethingPlaying`
  and `settings.startPlaybackIn() != VIDEO_PAGE`, or `player.somethingPlaying` and
  `base.onListPlay(player) != ListEffect.ASK` (while something is playing that is exactly "ask
  is off", QG plan 3.2) and `settings.defaultAction() != PLAY_NOW`.

Rule tables for `OpenActionPolicy(QueueGuardPolicy(GuardSettings { ask }), settings)`, first match
wins. playing = `player.somethingPlaying` (false for `PlayerState.IDLE` and for any kind with
`queueSize == 0`); current = `player.isCurrent(tap)`; MPM = `tap.startsListInBackground` (local
playlist with Music Player Mode on); kind = `player.kind`; "any" = no influence on the result.

`onTap`:

| # | playing | current | ask | default action | start playback in | MPM | kind | Result | AC |
|---|---|---|---|---|---|---|---|---|---|
| 1 | no | - | any | any | any | yes | any | `OPEN_AS_TODAY` | 6 |
| 2 | no | - | any | any | `VIDEO_PAGE` | no | any | `OPEN_AS_TODAY` | 5, 14 (QG 7) |
| 3 | no | - | any | any | `BACKGROUND_PLAYER` | no | any | `START_BACKGROUND` | 5 |
| 4 | no | - | any | any | `POPUP_PLAYER` | no | any | `START_POPUP` | 5 |
| 5 | yes | yes | any | any | any | any | any | `OPEN_AS_TODAY` | 12 (QG 8) |
| 6 | yes | no | on | any | any | any | any | `ASK` | 4 (QG 1) |
| 7 | yes | no | off | `ADD_TO_QUEUE` | any | any | any | `ENQUEUE` | 3 |
| 8 | yes | no | off | `PLAY_NEXT` | any | any | any | `ENQUEUE_NEXT` | 3 |
| 9 | yes | no | off | `PLAY_NOW` | any | yes | any | `OPEN_AS_TODAY` | 3 (QG 5) |
| 10 | yes | no | off | `PLAY_NOW` | any | no | `MAIN` | `OPEN_AS_TODAY` | 2, 3 (QG 16) |
| 11 | yes | no | off | `PLAY_NOW` | any | no | `BACKGROUND` | `START_BACKGROUND` | 2, 3 (QG 16) |
| 12 | yes | no | off | `PLAY_NOW` | any | no | `POPUP` | `START_POPUP` | 2, 3 (QG 16) |

`onTapChoice` (ask, default action and current have no influence):

| # | choice | playing | start playback in | MPM | kind | Result | AC |
|---|---|---|---|---|---|---|---|
| 13 | `CANCEL` | any | any | any | any | `NONE` | 9 (QG 6) |
| 14 | `ADD_TO_QUEUE` | any | any | any | any | `ENQUEUE` | QG 3, QG 9 |
| 15 | `PLAY_NEXT` | yes | any | any | any | `ENQUEUE_NEXT` | QG 4 |
| 16 | `PLAY_NEXT` | no | any | any | any | `ENQUEUE` | QG 9 |
| 17 | `PLAY_NOW` | yes | any | yes | any | `OPEN_AS_TODAY` | 2 (QG 5) |
| 18 | `PLAY_NOW` | yes | any | no | `MAIN` / `BACKGROUND` / `POPUP` | `OPEN_AS_TODAY` / `START_BACKGROUND` / `START_POPUP` | 2 (QG 5) |
| 19 | `PLAY_NOW` | no | as rows 1-4 | as rows 1-4 | any | the result of rows 1-4 | 13 (through row 1: 6) |

`offersStreamDetails` (`onListPlay` and `onListChoice` are QG plan 3.2's rows whatever the two
settings are, AC-15):

| # | playing | kind | ask | default action | start playback in | Result | AC |
|---|---|---|---|---|---|---|---|
| 20 | yes | `BACKGROUND`, `POPUP` | any | any | any | `true` | QG 17 |
| 21 | yes | `MAIN` | off | `ADD_TO_QUEUE`, `PLAY_NEXT` | any | `true` | 11 |
| 21b | yes | `MAIN` | any other combination | | any | `false` | QG 17, 14 |
| 22 | no | any | any | any | `BACKGROUND_PLAYER`, `POPUP_PLAYER` | `true` | 11 |
| 23 | no | any | any | any | `VIDEO_PAGE` | `false` | 14 |

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
| `object DefaultOpenAction { fun policy(base: GuardPolicy): GuardPolicy }` | `OpenActionPolicy(base, PrefsOpenActionSettings)` |
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
written in that package. P = the combined policy of 4.2's tables with a mutable fake
`OpenActionSettings`. D = `OpenActionPolicy` over a recording fake `GuardPolicy`.
C = `QueueGuardController(P, mutable fake PlayerStateSource)` with recording `TapActions`,
`ListActions`, `GuardUi`. M = `RememberChoice` with a recording `RememberStore`. R = the JDK XML
parser over this feature's two resource files, QG's `res/values/enhancements_queue_guard.xml` and
`res/xml/video_audio_settings.xml` (path fallback as in QG plan 5). "Manual" = on a device.

| AC | Implemented by | Verified by |
|---|---|---|
| 1 | S2, section 5, `DefaultTapAction` | JVM R: S2 is the element right after the one with `@string/enh_queue_guard_ask_key`; `defaultValue` `play_now` = `DefaultTapAction.DEFAULT.prefValue`; `summary` `%s`; the values array = `DefaultTapAction.values().map { it.prefValue }`; entries resolve to "Play now", "Add to queue", "Play next"; title text. `fromPrefValue`: each value, and `null` and unknown give `PLAY_NOW`. Manual: Settings > Player, summary after a change (preference screen) |
| 2 | S3, section 5, `StartPlaybackIn`, rows 5-12, 17-18 | JVM R: S3 is the element right after S2; default `video_page` = `StartPlaybackIn.DEFAULT.prefValue`; values array = the enum's `prefValue`s; three texts, title; `fromPrefValue` as for AC-1. P: while playing, `onTap` and `onTapChoice` give the same result for all three values. Manual: Settings |
| 3 | rows 7-12 | JVM P: each row for every kind, MPM and start-in value. D: the result is `base.onTapChoice(defaultAction.choice, tap, player)`. C: only `enqueue()` / `enqueueNext()` / `startInBackground()` / `startInPopup()` is called, `true` is returned, no UI; `PLAY_NOW` on `MAIN` returns `false`. Manual: one tap per action (toast and queue equal the dialog choice) |
| 4 | row 6 | JVM P: `ASK` for all 9 combinations of the two settings. C: only `askForTap`, no action |
| 5 | rows 2-4 | JVM P for every default action, ask on and off. C: `VIDEO_PAGE` returns `false` and calls nothing; `BACKGROUND_PLAYER` calls only `startInBackground()`, `POPUP_PLAYER` only `startInPopup()`, both return `true` without UI. Manual: playback starts, list stays, popup permission prompt (player service and system permission) |
| 6 | row 1 | JVM P, C (returns `false`) for every start-in value |
| 7 | `RememberChoiceAddOn`, layout, string | JVM R: the layout has exactly one `CheckBox`, its text is `@string/enh_default_open_action_remember` = "Remember my choice", no `android:checked`. Manual: shown in the tap dialog, unchecked on every opening (view) |
| 8 | `RememberChoice`, `PrefsOpenActionSettings.save` | JVM M: 4.3. Scenario with one in-memory fake that is `GuardSettings`, `OpenActionSettings` and `RememberStore`: ask on, playing; tap -> prompt; `RememberChoice.onChoice(PLAY_NEXT, true)`, then the prompt's callback -> only `enqueueNext()`; the fake holds `PLAY_NEXT` and ask off; a second tap -> `enqueueNext()` without UI. Same for the other two choices. Manual: both values shown in Settings afterwards (preference glue) |
| 9 | 4.3; QG's dialog calls the add-on for no dismissal | JVM M: `CANCEL` checked, and every choice unchecked, save nothing. Manual: Cancel, Back, outside tap, rotation with the box ticked (dialog lifecycle) |
| 10 | G2 feeds only `onVideoTap`; QG builds the list dialog with no extension (QG plan 3.3) | Manual: the list dialog has no checkbox (the box is attached by QG's Android dialog code; nothing to call on the JVM) |
| 11 | rows 20-23, QG's M1 | JVM P: the five rows, row 21 for both enqueue actions and row 21b for ask on and for `PLAY_NOW`; C: `offersStreamDetails()`. Manual: the entry appears and opens the page (QG's menu glue) |
| 12 | row 5 | JVM P: `OPEN_AS_TODAY` for every combination of the settings, ask and kind; C returns `false` |
| 13 | row 19 | JVM P. C: the fake player is set to `PlayerState.IDLE` between prompt and choice, for each start-in value and MPM |
| 14 | `PLAY_NOW` + `VIDEO_PAGE` delegate everything | JVM: over the whole grid (kind incl. `null`, queue size 0 and 3, current or not, ask, MPM, every `GuardChoice`), P with default settings equals `QueueGuardPolicy` alone in all five functions; `DEFAULT` of both enums |
| 15 | `onListPlay`, `onListChoice` | JVM P: equal to `QueueGuardPolicy` alone for all 9 setting combinations. D: arguments and result unchanged. C: `ListActions` as with QG alone |
| 16 | section 5 | JVM R: every text is a `<string>` in `values/`; only the two keys and the two value arrays are `translatable="false"` |

Wiring on the JVM (recommended): lines containing `ENH:default-open-action`: `QueueGuard.kt` 2 (one
with `DefaultOpenAction.policy(QueueGuardPolicy(PrefsGuardSettings))`, one with
`DefaultOpenAction.tapDialogExtension`), `res/xml/video_audio_settings.xml` 2. QG's own counts and
its S1 position test (QG plan 5) stay as they are.

## 7. Order of work

(0) QG's code is on the branch. (1) `res/values/enhancements_default_open_action.xml`, the layout,
then S2 and S3. (2) `OpenActionModel.kt`, `OpenActionPolicy.kt`, `RememberChoice.kt`, until the unit
tests pass. (3) `DefaultOpenAction.kt`. (4) G1 and G2; `git grep -c "ENH:default-open-action"` gives
the counts of section 6 and QG's unit tests still pass. (5) Device pass over the "Manual" cells
with no player, the main, the background and the popup player.

## 8. Risks and edge cases

- "Remember my choice" switches off the one switch that also covers the play buttons (QG AC-15,
  QG AC-16): afterwards Play All, Background and Popup replace the queue without asking.
- The default action is unused while the switch is on (AC-4), which is the default, and the
  setting is not greyed out then: that dependency would have to be declared on QG's S1 line.
- QG AC-7 and QG AC-16 describe the app only while this feature's settings are on their defaults
  (AC-14). QG's unit tests build `QueueGuardPolicy` themselves and stay green.
- `QueueGuard`'s initialiser runs G1 and G2: `DefaultOpenAction` and `PrefsOpenActionSettings` must
  not touch `App.getApp()` or resources in an initialiser and must not refer to `QueueGuard`.
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
