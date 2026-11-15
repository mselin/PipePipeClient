# Questions: group-dialog

## Q1 spec -> user
Should this fix get a spec and plan now?

## A1 user
Selected "group-dialog" when asked which fixes from FINDINGS.md to plan.
Applied: this spec. Its acceptance criteria are the session's proposal from the findings and
have not been reviewed by the owner.

## Q2 plan -> spec
What does OK do after the groups failed to load? AC-6 says an error is shown and *New group*
stays disabled; it does not mention OK. Today OK closes the dialog in that case
(FeedGroupSelectionDialog.java:153-156 sets the loaded flag on failure).
Planned with: OK closes the dialog without saving anything, like Cancel (plan section 2, S2).
The other reading is to disable OK as well. Affects AC-6.

## Q3 plan -> spec
What happens to a save that is running when the screen rotates? AC-5 lists what is disabled
during the save; rotation cannot be disabled, and finding F12 is about exactly this.
Planned with: the running save is not cancelled. It is one transaction, so everything of that OK
is saved or nothing; the old dialog's result (close, or the error message) is dropped, and the
dialog that Android rebuilds loads the groups again (plan section 2, S3). Affects AC-2 and AC-5.
