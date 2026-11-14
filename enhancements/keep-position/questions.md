# Questions: keep-position

## Q1 spec -> user
The app has a switch "Always start play from beginning" (Settings > Advanced). In the code it
also discards the position when the playing video moves between players. Is it on in your
app?

## A1 user
"Yes, it is on."
Applied: that switch is the cause; the feature keeps the position while it is on (Today,
AC-2).

## Q2 spec -> user
What should the new setting be by default?

## A2 user
"On."
Applied: AC-1.

## Q3 spec -> user
The same cause seems to restart the video on a quality change and on error recovery. Should
the setting cover those too, or only player switches?

## A3 user
"All reloads of the playing video."
Applied: one switch, "Keep position of the playing video", for player switches, quality
changes and error recovery (Terms, AC-2 to AC-4).
