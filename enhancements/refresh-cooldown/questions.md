## Q1 plan -> spec
"Today" places the setting under Settings > Notifications. The settings list shows that
screen as "Feed" (`res/xml/main_settings.xml:49-53`, title `settings_category_feed_title`);
only its file is called `notifications_settings.xml`. The setting is the screen's first
entry. Working assumption: AC-1 means this setting where it is, Settings > Feed > "Feed
update threshold"; nothing of it is edited.

## Q2 plan -> spec
AC-3 says a subscription's cooldown "starts as Use default". The long-press menu of the
Subscriptions tab knows a channel by service and address, not by a subscription id, so the
plan stores a subscription's value under service and address (plan 3.1). While the channel is
not subscribed to the value has no effect (AC-4), but when the same channel is subscribed to
again it has its old cooldown, not *Use default*. Working assumption: accepted. The
alternative is to remove the value at every place that unsubscribes (five today, in five
files), each a marked line.

## Q3 plan -> spec
AC-2: "set in the dialog that edits the group. It starts as Use default." A new group's id
exists only after the insert, inside `FeedGroupDialogViewModel.createGroup` (`:71-74`).
Working assumption: the dialog that creates a group has no cooldown row; the group starts as
*Use default* and the value is set by editing it. Offering the row there too would need one
more marked line in the view model (`.doOnSuccess { id -> … }` after `:71`) and a holder for
the choice between the dialog and its view model.

## Q4 plan -> spec
AC-8 and the end of a refresh that also had failing channels: upstream shows "Sorry,
something went wrong. / Report" as a snackbar at that moment, and a second snackbar replaces
the one on screen. Working assumption: the error message comes first and the skipped message
3.5 seconds later (plan 6.2). Without failing channels the skipped message comes at once.

## Q5 plan -> spec
AC-8, AC-9: how long and where "Refresh anyway" is offered is not said. Working assumption
(plan 6.1, 6.3): the message appears once, on the page of the feed the refresh was started
for ("All" for "All", the group's page for a group), at the end of the refresh or when that
page is returned to within 60 seconds after it; it stays 8 seconds. A later refresh replaces
it. "Exactly the channels that refresh skipped" excludes a channel that was unsubscribed or
left the group in between. If another refresh is running when the action is tapped, nothing
happens.

## Q6 plan -> spec
Terms: "last successful refresh". The stored time is also set when a channel is subscribed
to and by the fetch after a subscription import (`SubscriptionManager.kt:144-149`, `:74-86`),
not only by a feed refresh; and a refresh that stored videos but reported an error for the
channel counts as failed (`FeedLoadManager.kt:338-355`). Both are today's behaviour and AC-11
keeps them. Working assumption: the cooldown counts from that stored time, whatever wrote it,
so a channel just subscribed to is in cooldown; AC-6 holds as written.

## Q7 plan -> spec
AC-3 names the long-press menu "on the Subscriptions tab" (`SubscriptionFragment`). The
channel list page of a group (`SubscriptionListFragment.kt:238-290`) has a similar menu with
a variable number of entries. Working assumption: only the Subscriptions tab's menu gets the
entry.
