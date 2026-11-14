# Create a group while adding a channel

When adding a channel to groups, the user can create a new group on the spot.

## Today

- On the page of a subscribed channel, the add-to-group button opens the "Select groups"
  dialog: every channel group with a checkbox, then Cancel and OK. OK saves the checkboxes.
- The dialog cannot create a group. With no groups it is an empty list.
- A group can only be created from the Subscriptions tab ("New" in Channel groups), in a
  dialog that asks for name, icon and member channels.

## Acceptance criteria

- **AC-1** The "Select groups" dialog has a *New group* action. It is visible whether or not
  groups exist.
- **AC-2** *New group* opens a name prompt: one text field, *Create* and *Cancel*. The field
  has focus and the keyboard is shown; the keyboard's done action equals *Create*.
- **AC-3** *Create* with a name that is empty or only whitespace creates nothing, shows the
  existing error "Empty group name" on the field and keeps the prompt open.
- **AC-4** *Create* with any other name creates a channel group with that name (surrounding
  whitespace removed) and the same default icon the existing group dialog starts with. It is
  placed last in the manual order, like a group created the existing way.
- **AC-5** The channel is added to the new group as part of *Create*. Group and membership
  are saved at once; a later Cancel of the "Select groups" dialog does not undo them.
- **AC-6** After *Create* the prompt closes and the new group is in the "Select groups" list,
  checked. Checkbox changes the user made before *New group* and has not saved yet are kept.
- **AC-7** *Cancel*, Back or any other dismissal of the name prompt creates nothing and
  returns to the "Select groups" dialog as it was.
- **AC-8** Unchecking the new group and pressing OK removes the channel from it, like for any
  other group. The group itself remains.
- **AC-9** Names need not be unique, as in the existing group dialog.
- **AC-10** The new group is an ordinary group: it shows up in the Subscriptions tab and can
  be renamed, given another icon, filled or deleted there.
- **AC-11** If saving fails, nothing is half-created (no group without the channel), the
  prompt stays open with the name, and an error is shown.
- **AC-12** All new texts are translatable. English is delivered; other languages fall back
  to English.

## Not covered

- Choosing icon or further member channels while creating (use the existing group dialog).
- Creating groups from any other place.
