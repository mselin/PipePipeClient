# Group dialog: create a group inline, and save reliably

Replaces group-create-inline. Same behaviour for the user, built as a direct edit of the
"Select groups" dialog, and the dialog's save is fixed.

Findings F10, F11, F12 and D2 in `enhancements/FINDINGS.md`.

## Today

- The "Select groups" dialog saves each toggled group in a separate step. Every step ends
  with an error even when its write succeeded, so the dialog always takes its error path.
  With two or more groups toggled, the first step to finish cancels the others: the channel
  can be missing from groups that were ticked. The dialog closes normally either way, and
  real errors are hidden the same way.
- group-create-inline adds *New group* by taking the dialog over at runtime from outside.

## Acceptance criteria

- **AC-1** Everything `enhancements/group-create-inline/spec.md` requires (its AC-1 to
  AC-16) holds, except where this spec says otherwise.
- **AC-2** OK saves all changes of the dialog together: the new groups with the channel as
  member, every group the channel was added to, every group it was removed from. Either all
  of it is saved or none of it.
- **AC-3** However many groups were toggled, after OK the channel is a member of exactly the
  groups that were ticked.
- **AC-4** If saving fails, nothing is saved, the dialog stays open with its checkboxes and
  pending groups as they were, and an error is shown. This replaces group-create-inline's
  AC-12, including its "behaves as today" part.
- **AC-5** While the save runs, OK, Cancel, *New group*, Back and the rows are disabled.
- **AC-6** If loading the groups fails, the dialog shows an error instead of an empty list,
  and *New group* stays disabled.
- **AC-7** A group's other members are never changed by this dialog: adding or removing this
  channel leaves the rest of the group's member list as it is in the database at that moment.
- **AC-8** The dialog is edited directly. The runtime take-over of group-create-inline
  (replaced OK listener, wrapped adapter, load inferred from adapter notifications) is gone.

## Not covered

- The existing group dialog of the Subscriptions tab.
