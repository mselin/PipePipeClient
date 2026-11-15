# Questions: player-memory

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "player-memory" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-3 and AC-7 pull against each other once AC-1 holds. Today all preview sheets of an item (up to
48, about 47 MiB at the default quality) are loaded when the item starts, so every preview is
there when the seekbar is touched. A fixed limit well below that leaves two choices: (a) load
fewer sheets, which makes previews of long videos coarser (the frame shown can be minutes away
from the thumb), or (b) keep today's frames but load sheets on demand while the user drags, which
shows the preview of a newly reached part one download later (until then the nearest loaded frame
is shown). Is (b) what AC-7 "once shown, look as today" allows, and is 8 MiB per item an
acceptable limit?
Planned with: (b), 8 MiB of decoded sheets per item (about 8 sheets at the default quality).

## Q3 plan -> spec
AC-4, three details the code forces a choice on. (1) At the end of the queue the player today
preloads the first item whatever the repeat mode; should "the next two" wrap around the end?
(2) When a queue edit (remove, move, shuffle) pushes a loaded item out of the window, may it stay
loaded until the player next synchronizes with the queue (the next item change or neighbour load,
normally seconds), instead of at once? Unloading at once is not safe: the queue index can be ahead
of what is actually playing. (3) AC-5 names next and previous only: picking an item further away
that was played earlier loads it again (today it starts at once until its source expires). Is
that the intended cost of AC-4?
Planned with: (1) yes, wraps; (2) yes, at the next synchronization; (3) yes, accepted.

## A2 spec
On-demand loading with a limit of 8 MiB of decoded sheets per item, as planned. A newly
reached part of a long video shows its preview one download later than today; AC-7 means the
previews look the same once shown.

## A3 spec
Yes to all three: "the next two" wrap at the end of the queue as the player's own order
does; an item pushed out of the window by a queue edit may stay loaded until the next
synchronization; picking a far item that was played earlier loads it again.
