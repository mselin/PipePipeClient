# Sorted channel groups

The user can keep channel groups sorted by name instead of in manual order.

## Today

- Channel groups have one order: the manual order set in the reorder dialog (the menu button
  of "Channel groups" in the Subscriptions tab). A new group goes last.
- That order is used wherever groups are listed: the Channel groups section of the
  Subscriptions tab, the "Select groups" dialog on a channel page, and the group picker used
  when a channel group is chosen as a main-page tab.

## Terms

- **Group lists**: the three places above.
- **Manual order**: the order stored by the reorder dialog.

## Acceptance criteria

- **AC-1** A setting "Channel group order" in Settings > Appearance has three values:
  *Manual* (default), *Name (A-Z)*, *Name (Z-A)*. Its summary shows the selected value.
- **AC-2** With *Manual* every group list is exactly as it is without this feature.
- **AC-3** With *Name (A-Z)* every group list shows the groups in ascending name order.
- **AC-4** With *Name (Z-A)* every group list shows them in descending name order.
- **AC-5** Names are compared character by character by character code, after ignoring
  upper/lower case and surrounding whitespace. A name that is the beginning of another comes
  first. This gives: space and most punctuation, then digits, then letters A-Z, then letters
  outside A-Z (such as "Ä"). Numbers are not compared by value and no language-specific
  alphabet rules apply. Examples: "- Top" < "001 - First" < "Mix 10" < "Mix 2" < "music" <
  "ZZZ - Last" < "ZZZZ - Last of last" < "Äpfel".
- **AC-6** Groups with equal names keep their manual order relative to each other, in both
  directions.
- **AC-7** Entries that are not groups keep their place: "All" stays first and "New" stays
  last in the Subscriptions tab.
- **AC-8** The order follows changes without a restart: creating or renaming a group puts it
  at its sorted position; changing the setting reorders the lists the next time they are
  shown.
- **AC-9** The manual order is never modified by sorting. Switching back to *Manual* shows
  the order that was there before.
- **AC-10** With a name order selected, the reorder dialog still opens and still edits the
  manual order, and it says that groups are currently sorted by name so the manual order is
  not in effect.
- **AC-11** The setting is part of the app's settings export and import, like other settings.
- **AC-12** All new texts are translatable. English is delivered; other languages fall back
  to English.

## Not covered

- Sorting the channels inside a group, or the subscription list.
- Other sort keys (number of channels, creation date).
