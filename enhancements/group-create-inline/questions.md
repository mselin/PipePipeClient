# Questions: group-create-inline

## Q1 spec -> user
When is the new group saved? The spec saves group and membership at the moment of *Create*
(AC-5), so cancelling "Select groups" afterwards keeps them. The alternative is to save only
when "Select groups" is confirmed with OK, so Cancel discards the new group too.

## Q2 spec -> user
Should a name that already exists be refused? The spec allows duplicates because the existing
group dialog does (AC-9).

## A1 user
"Created group is saved when Select groups is confirmed. So all groups have at least one
channel."
Applied: a named group is pending until OK (AC-5, AC-8, AC-10), and a pending group that is
unchecked is not created (AC-9).

## A2 user
"Shouldn't be able to create duplicate same named groups. They just cause confusion. Lets
leave this limit at to the selection dialog for now."
Applied: the name prompt refuses a name already used by an existing or pending group (AC-4,
Terms). The existing group dialog keeps accepting duplicates (AC-14).
