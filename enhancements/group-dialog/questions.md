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

## A2 spec
As planned: after a failed load, OK closes the dialog without saving, like Cancel. There is
nothing it could save.

## A3 spec
As planned: a running save is not cancelled by a rotation. It saves everything or nothing;
only its answer to the old dialog is dropped. That removes the partial save of finding F12.

## Q4 tests -> plan
How are the carried-over tests named? Section 7 says `GroupNameRulesTest` moves with "package
line only" and the 20 `submitName` / `setChecked` tests keep everything but their set-up, and
that new tests start with `ac<n>_` for the ACs of this spec. The old names also start with
`ac<n>_`, for the ACs of group-create-inline's spec, so `ac4_...` would mean two different
criteria in one test class.
Tests written with: every test that proves an AC of group-create-inline's spec (kept by AC-1) is
named `gci_ac<n>_...`; bodies and assertions of the carried-over tests are unchanged. Affects
AC-1.

## Q5 tests -> plan
What is the session's state after a successful save? Step 6 says `isBusy = false`, then
`ui.close()` only; it does not say whether `pending` is emptied or what a later `confirm` does.
The old contract emptied `pending`, and three old tests relied on it.
Tests written with: nothing after a successful save is asserted except `isBusy == false` and the
`ui` calls. `ac8_ac9_successAlsoRemovesTheUncheckedPendingGroups` and
`ac8_createdGroupsAreNotCreatedAgainByAnotherConfirm` have no counterpart;
`ac7_idsAreNotReusedAfterTheSavedGroupsWereCleared` became
`gci_ac7_idsAreNotReusedAfterAFailedSave`. Affects AC-2.

## Q6 tests -> plan
Which AC does "`confirm` while loading does nothing" (step 1) belong to? Section 7 lists the test
without an AC and section 8 maps step 1 to AC-5, which is about the running save.
Tests written with: `ac3_confirmWhileTheGroupsAreStillLoadingDoesNothing` (a save built from
sets that are not loaded yet would break AC-3). Only the name depends on the answer.

## Q7 tests -> plan
Two points of `GroupDialogSourceTest`.
(a) Of the five texts the dialog's source must not contain, `ConcatAdapter` and
`registerAdapterDataObserver` are not in `FeedGroupSelectionDialog.java` today either (they are
in `groupcreateinline/GroupCreateInline.kt`), so a test for each alone passes before any code
exists. Tests written with: one test for `groupcreateinline`, `ConcatAdapter` and
`registerAdapterDataObserver` together (AC-8), one for `toSingle(` (AC-3), one for
`updateSubscriptionsForGroup` (AC-7).
(b) Section 3 replaces, renames or deletes the three resource files of group-create-inline;
section 7 does not list a test for that. Tests written with: one more test,
`ac8_theResourceFilesOfGroupCreateInlineAreGone`. That the `<include>` comes after the
RecyclerView is not tested.
