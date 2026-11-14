# Default open action

The user decides what a tap on a video does, and where playback starts.

Builds on queue-guard: same terms (something is playing, video tap, current item, Play now),
same dialog, same "Ask before replacing the queue" switch. Read
`enhancements/queue-guard/spec.md` first.

## Acceptance criteria

Settings

- **AC-1** A setting "Default action when tapping a video" sits with the queue-guard switch
  (Settings > Player). Values: *Play now* (default), *Add to queue*, *Play next*. Its
  summary shows the selected value.
- **AC-2** A setting "Start playback in" sits beside it. Values: *Video page* (default),
  *Background player*, *Popup player*. Its summary shows the selected value. It applies only
  while nothing is playing; while something is playing, playback stays in the player that is
  in use (queue-guard's Play now).

While something is playing

- **AC-3** With "Ask before replacing the queue" off, a video tap on anything but the current
  item carries out the default action directly, without a dialog. Each action gives the same
  result as the queue-guard dialog choice of the same name.
- **AC-4** With "Ask before replacing the queue" on, a video tap opens the queue-guard dialog
  as before. The default action is not used.

While nothing is playing

- **AC-5** A video tap starts the video according to "Start playback in", whatever the
  default action is:
  - *Video page*: what a video tap does today.
  - *Background player*: the tapped video alone starts in the background player, as the
    long-press action "Start playing in the background" does in search results. The list
    stays on screen.
  - *Popup player*: the tapped video alone starts in the popup player, as the long-press
    action "Start playing in a popup" does in search results, including its prompt when the
    popup permission is missing. The list stays on screen.
- **AC-6** In a local playlist with Music Player Mode on, a video tap while nothing is
  playing does what it does there today; "Start playback in" does not apply.

Remembering a choice

- **AC-7** The queue-guard dialog for a video tap has a checkbox "Remember my choice",
  unchecked every time the dialog opens.
- **AC-8** Choosing *Add to queue*, *Play next* or *Play now* with the checkbox checked
  carries out the choice, makes it the default action and switches "Ask before replacing the
  queue" off. Both settings show the new values in Settings.
- **AC-9** *Cancel* or any other dismissal saves nothing, checked or not.
- **AC-10** The dialog for play buttons has no checkbox.

Reaching the video page

- **AC-11** While nothing is playing and "Start playback in" is *Background player* or
  *Popup player*, the long-press menu of a video contains "Show stream details", which opens
  the video as a video tap does today. This adds to queue-guard's AC-17.

General

- **AC-12** A video tap on the current item behaves as today, whatever the settings.
- **AC-13** If the player was closed while the dialog was open, *Play now* follows AC-5.
- **AC-14** With both settings on their defaults the app behaves exactly as with queue-guard
  alone.
- **AC-15** The play buttons are not affected by either setting.
- **AC-16** All new texts are translatable. English is delivered; other languages fall back
  to English.

## Not covered

Everything queue-guard lists as not covered. In particular, links opened from other apps
keep using "Preferred 'open' action".
