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
