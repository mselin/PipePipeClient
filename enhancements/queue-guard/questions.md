# Questions: queue-guard

## Q1 spec -> user
When does a queue count as worth protecting? The spec says two or more items, ignoring an
auto-enqueued one (AC-1, AC-7). The alternative is to ask whenever anything is loaded in the
player, which would also interrupt plain "watch one video, tap the next" browsing.

## Q2 spec -> user
Should the Play all / Background / Popup buttons in playlist, channel and feed headers also
ask before replacing a built queue? The spec leaves them as they are ("Not covered").

## Q3 spec -> user
Should the dialog have a "remember my choice" option? The spec has none: the setting (AC-10)
is the one place that decides, and default-open-action adds the enqueue choices to it.

## A1 user
"Anything loaded in player, no matter if it is playing or paused."
Applied: the term "built queue" is gone; the guard applies while something is playing
(Terms, AC-1, AC-7).

## A2 user
"Yes, should ask. There should be an Enqueue button, and possibly Enqueue next button. The
asking should be configurable. Would configure to be on by default. But can be turned off."
Follow-up choice: "Ask, one shared switch".
Applied: the play buttons ask, with *Add all to queue* and *Play all next* in the dialog
(AC-10 to AC-14). One switch, on by default, covers taps and play buttons (AC-15, AC-16).

## A3 user
"Setting remembers choice." Follow-up choice: "Yes, checkbox".
Applied in default-open-action (its AC-6), because the checkbox stores the choice in the
default-action setting that feature adds. Queue-guard alone has no checkbox.
