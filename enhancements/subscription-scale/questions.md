# Questions: subscription-scale

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "subscription-scale" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
AC-4 contradicts itself for the default fetch method. The Subscriptions tab shows each
channel's subscriber count (list and grid rows), and a feed refresh that does not use the
dedicated fetch method rewrites that count for almost every channel. So "does not rebuild for
a change that does not alter what it shows" would rebuild on nearly every batch, while "during
a feed refresh it is not rebuilt at all unless a channel's name or picture changed" forbids it.
Planned with: the second sentence wins. While the tab stays on screen, only a change of the
set of channels, a name or a picture rebuilds the list; subscriber counts are brought up to
date when the tab is returned to, or with the next such change (plan 5.3). If counts must
follow a refresh live, `SubscriptionRows.sameNamesAndPictures` also compares the count and the
tab is rebuilt per batch again (cheaper than today, but not "not at all").

## Q3 plan -> spec
AC-3 and AC-4 say "not on screen" without defining it. Planned with: on screen = the screen's
fragment is resumed. That makes a hidden main tab, a page on the back stack and a stopped app
"not on screen". A feed or the Subscriptions tab that is only covered by the expanded player
stays resumed and keeps updating as today; detecting that would need a hook into the player
sheet. Also planned: the first load of a screen is allowed while it is not on screen yet, so
a tab that has never been shown has its list ready when first opened, as today.

## Q4 plan -> spec
AC-1 says the memory held "does not grow with the number of channels refreshed" and also
"new-video notifications work as today". The notification check shows its notifications after
the whole check, so it must keep something until the end. Planned with: a manual refresh
keeps nothing per channel; the notification check keeps, per notification-enabled channel
that has new videos, the channel's name, address, picture address and its new items only
(plan section 3). That still grows with the number of such channels, but not with the number
of channels refreshed or the size of their video lists. Showing each notification as soon as
its batch is stored would remove the remainder and change when notifications appear.
