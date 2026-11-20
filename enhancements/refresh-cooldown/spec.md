# Refresh cooldown per group and per subscription

Asked for by the owner on 2026-11-19. Built on `feature/feed-live-update` (and through it on
`feature/subscription-scale`), because it changes the same feed loader and feed page.

## Today

- A refresh of a feed skips every channel that was refreshed recently. "Recently" is one
  app-wide setting: Settings > Feed > "Feed update threshold", default 5 minutes,
  with the choices Always update, 5 minutes, 15 minutes, 1 hour, 6 hours, 12 hours, 1 day.
- The setting is the same for every channel and group.
- A refresh does not say that it skipped channels, and there is no way to refresh a skipped
  channel short of changing the setting.

## Terms

- **Cooldown**: the time after a channel's last successful refresh during which a refresh
  skips that channel. The existing "Feed update threshold" is the **global cooldown**.
- **Choices**: the choices of the global cooldown. A group or a subscription can also be set
  to **Use default**, which means it has no cooldown of its own.
- **Effective cooldown of a channel**: its own cooldown if it has one; otherwise the shortest
  cooldown among the groups it is in that have one; otherwise the global cooldown. "Always
  update" is the shortest possible.

## Acceptance criteria

### Values

- **AC-1** The global cooldown is the existing setting. Its place, choices and default stay
  as they are.
- **AC-2** A channel group has a cooldown, set in the dialog that edits an existing group.
  It starts as *Use default*. The dialog shows the current value. The dialog that creates a
  group has no such row; the value is set by editing the group afterwards.
- **AC-3** A subscription has a cooldown, set from the long-press menu of the channel on the
  Subscriptions tab. It starts as *Use default*. The menu entry shows the current value.
- **AC-4** The values survive a restart of the app, and an export and import of a backup
  that contains both the database and the settings. A value that belongs to a group or a
  subscription that no longer exists has no effect. A subscription's value follows the
  channel's address: a channel that is unsubscribed and subscribed to again has its old value.

### Refreshing

- **AC-5** A refresh, from "All" or from a group's feed, skips a channel when its last
  successful refresh is more recent than its effective cooldown, and refreshes it otherwise.
  The result for a channel is the same whichever feed the refresh is started from.
- **AC-6** A channel that was never refreshed, and a channel whose last refresh failed or
  ended with reported errors, are not in cooldown. As today, subscribing to a channel and the
  fetch after an import of subscriptions count as a refresh of that channel.
- **AC-7** The background check for new videos that the notifications use behaves as today.

### Refresh anyway

- **AC-8** When a refresh has skipped one or more channels because of their cooldown, the
  feed says how many were skipped and offers *Refresh anyway*. The message does not block
  the feed and goes away by itself. It is shown once, on the page of the feed that was
  refreshed, at the end of the refresh; when the refresh also has messages about channels
  that could not be loaded, those come first.
- **AC-9** *Refresh anyway* refreshes exactly the channels that refresh skipped, ignoring
  their cooldown, and nothing else. Afterwards those channels are in cooldown like any other
  refreshed channel.
- **AC-10** When a refresh skipped nothing, no message appears.

### General

- **AC-11** With every group and subscription on *Use default*, refreshing skips and
  refreshes the same channels as today. The only difference is the message of AC-8.
- **AC-12** All new texts are translatable. English is delivered; other languages fall back
  to English.

## Not covered

- Changing the default or the choices of the global cooldown.
- A cooldown per service.
- Showing in the lists which channels are in cooldown, or when a channel was last refreshed.
