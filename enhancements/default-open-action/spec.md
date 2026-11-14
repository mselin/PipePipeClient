# Default open action

Let the user decide that tapping a video adds it to the queue instead of opening it.

Builds on queue-guard: same terms, same setting, same meaning of *Add to queue*, *Play next*
and *Open and replace queue*. Read `enhancements/queue-guard/spec.md` first.

## Terms

- **Queue exists**: a player is open and holds at least one item, playing or paused. This is
  wider than queue-guard's *built queue*, so a queue can be started by tapping.

## Acceptance criteria

- **AC-1** The queue-guard setting "Tapping a video while a queue is playing" offers four
  values: *Ask* (default), *Add to queue*, *Play next*, *Open and replace queue*.
- **AC-2** With *Add to queue*, a video tap while a queue exists, on anything but the current
  item, appends the video to the queue. The result equals queue-guard's *Add to queue*
  choice: toast, the list stays on screen, no dialog, no page opens.
- **AC-3** With *Play next*, the same tap inserts the video right after the current item. The
  result equals queue-guard's *Play next* choice.
- **AC-4** With *Add to queue* or *Play next* and no queue (no player open, or an empty
  queue), a video tap behaves as it does without this feature: the video opens.
- **AC-5** With *Add to queue* or *Play next*, a video tap on the current item behaves as it
  does without this feature.
- **AC-6** With *Add to queue* or *Play next*, the long-press menu of a video contains "Show
  stream details", which opens the video as a tap does without this feature. With *Ask* or
  *Open and replace queue* the long-press menu is unchanged.
- **AC-7** *Ask* and *Open and replace queue* behave exactly as queue-guard specifies.
- **AC-8** The summary line of the setting shows the selected value.
- **AC-9** All new texts are translatable. English is delivered; other languages fall back to
  English.

## Not covered

Everything queue-guard lists as not covered. In particular, links opened from other apps
keep using "Preferred 'open' action".
