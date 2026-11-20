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

## Q3 plan -> spec
Does AC-12 also cover the checkbox changes of existing groups? Today, when saving those fails,
the dialog closes without a message (FeedGroupSelectionDialog.java:217-220), and AC-8 says they
are saved "as today". AC-12 says that if saving fails the dialog stays open and an error is
shown.
Planned with: AC-12 applies to creating the pending groups. They are committed first, in one
transaction; if that fails nothing is saved, the dialog stays open and the error is shown. If
it succeeds, today's save runs unchanged, including its silent close on failure; the new groups
then already contain the channel, so no group is left without it.
The other reading (any failure keeps the dialog open, all changes saved together or not at all)
is possible with the same single hook line, but the fork then replaces upstream's save instead
of calling it (plan section 2, S1). Affects AC-8 and AC-12.

## A3 spec
AC-12 is about creating the pending groups. They are saved first; if that fails nothing is
saved and the dialog stays open with an error. Saving the other checkbox changes keeps
today's behaviour, also when it fails. AC-12 now says so. The plan's assumption stands.

## Q4 tests -> plan
Is `isBusy` already true while `ui.setBusy(true)` runs? Plan section 4 says two things that
differ for that one moment. The comment on the property: "true only while a createGroups call is
unanswered". `confirm()` step 4: `isBusy = true`, then `ui.setBusy(true)`, then
`saver.createGroups(...)`; during `ui.setBusy(true)` no call has been made yet, but `isBusy` is
true.
Tested with: step 4 as written, because the numbered steps are named as the contract.
`GroupCreateSessionTest.ac8_sessionIsAlreadyBusyWhenTheUiIsToldBusy` expects `isBusy == true`
inside `ui.setBusy(true)`. It is the only test that depends on this; every other test holds for
both readings. If the comment is meant to the letter, that test is removed. The Android ui
cannot observe the difference. Affects AC-8.

## A4 plan
Yes. The numbered steps of `confirm()` are the contract: `isBusy` is true before
`ui.setBusy(true)` is called and stays true until the saver has answered. The comment on the
property was loose and now says so. The test stands.
