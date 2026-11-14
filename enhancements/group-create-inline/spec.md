# Create a group while adding a channel

When adding a channel to groups, the user can create a new group on the spot.

## Today

- On the page of a subscribed channel, the add-to-group button opens the "Select groups"
  dialog: every channel group with a checkbox, then Cancel and OK. OK saves the checkboxes.
- The dialog cannot create a group. With no groups it is an empty list.
- A group can only be created from the Subscriptions tab ("New" in Channel groups), in a
  dialog that asks for name, icon and member channels. It accepts a name that is already in
  use.

## Terms

- **Pending group**: a group the user has named in the "Select groups" dialog that is not
  saved yet.
- **Same name**: equal after removing surrounding whitespace and ignoring upper/lower case
  ("Music", " music " and "MUSIC" are the same name).

## Acceptance criteria

Naming a new group

- **AC-1** The "Select groups" dialog has a *New group* action. It is visible whether or not
  groups exist.
- **AC-2** *New group* opens a name prompt: one text field, *Add* and *Cancel*. The field has
  focus and the keyboard is shown; the keyboard's done action equals *Add*.
- **AC-3** *Add* with a name that is empty or only whitespace adds nothing, shows the
  existing error "Empty group name" on the field and keeps the prompt open.
- **AC-4** *Add* with the same name as an existing group or as a pending group adds nothing,
  shows an error saying a group with this name already exists, and keeps the prompt open.
- **AC-5** *Add* with any other name closes the prompt and puts a pending group with that
  name (surrounding whitespace removed) into the "Select groups" list, checked. Nothing is
  saved yet. Checkbox changes made before are kept.
- **AC-6** *Cancel*, Back or any other dismissal of the name prompt adds nothing and returns
  to the "Select groups" dialog as it was.
- **AC-7** *New group* can be used several times before confirming.

Confirming

- **AC-8** OK creates every pending group that is still checked, with the channel as its
  member, and saves the other checkbox changes as today.
- **AC-9** A pending group that was unchecked before OK is not created. No group is ever
  created empty by this dialog.
- **AC-10** Cancel, Back or any other dismissal of "Select groups" creates no group.
- **AC-11** A created group gets the same default icon the existing group dialog starts with
  and is placed last in the manual order, like a group created the existing way. Several
  created at once keep the order in which they were added.
- **AC-12** If creating the pending groups fails, nothing at all is saved: no group is left
  behind without the channel, the other checkbox changes are not saved either, the dialog
  stays open and an error is shown. A failure while saving the other checkbox changes behaves
  as today.

Afterwards

- **AC-13** A created group is an ordinary group: it shows up in the Subscriptions tab and
  can be renamed, given another icon, filled, emptied or deleted there.
- **AC-14** The existing group dialog of the Subscriptions tab is unchanged. It still accepts
  a name that is already in use.

General

- **AC-15** A screen rotation may discard unsaved changes of the dialog, pending groups
  included, but never creates a group.
- **AC-16** All new texts are translatable. English is delivered; other languages fall back
  to English.

## Not covered

- Choosing icon or further member channels while creating (use the existing group dialog).
- Creating groups from any other place.
- Preventing duplicate names anywhere but in this name prompt.
