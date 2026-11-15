package org.schabi.newpipe.enhancements.groupdialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupDialogSession] (plan section 4): the load state and the pending groups of one "Select
 * groups" dialog, and what OK does with them and with the checkboxes.
 *
 * The contract under test, in the plan's words:
 * - Load state starts as loading. `onLoaded(names)`: state loaded, keeps a copy of `names`,
 *   `ui.showLoaded()` once. `onLoadFailed()`: state failed, `ui.showLoadError()` once;
 *   `showLoaded` is never called.
 * - `submitName(raw)` (group-create-inline's plan section 4, with the names of `onLoaded` as the
 *   existing names): checked against those names plus the names of all pending groups, checked
 *   or not. Not `OK`: return the result, no state change, no `ui` call. `OK`: append
 *   `PendingGroup(id, clean(raw), checked = true)`, one `ui.showPending(pending)`. Ids are
 *   1, 2, 3, ... in the order added, never reused. Never calls `saver`.
 * - `setChecked(id, checked)`: sets that flag, position unchanged, unknown id ignored. No `ui`
 *   or `saver` call.
 * - `confirm(ticked, loaded)`: (1) busy or still loading: nothing. (2) load failed: `ui.close()`
 *   only. (3) `changes = GroupChanges(subscriptionId, checked pending names in the order added,
 *   (ticked - loaded) sorted, (loaded - ticked) sorted)`. (4) `changes.isEmpty`: `ui.close()`
 *   only. (5) `isBusy = true`, `ui.setBusy(true)`, `saver.save(changes, cb)`. (6) `cb(null)`:
 *   `isBusy = false`, then `ui.close()` only. (7) `cb(error)`: `isBusy = false`, pending
 *   unchanged; then `ui.setBusy(false)`, `ui.showSaveError()`, no `close`.
 *
 * Test names: `ac<n>_` is an AC of `enhancements/group-dialog/spec.md`; `gci_ac<n>_` is an AC of
 * `enhancements/group-create-inline/spec.md`, which AC-1 of group-dialog keeps (Q4 in
 * `enhancements/group-dialog/questions.md`).
 *
 * [FakeUi] records every `ui` call, so a comparison with a complete list also proves that
 * nothing else was called. `submitName` and `setChecked` while loading, after a failed load or
 * while busy are unspecified by the plan and not tested. Assumption, see Q5 in questions.md: the
 * same holds for anything after a successful save (whether `pending` is emptied, what another
 * `confirm` does), because the dialog is closed then.
 */
class GroupDialogSessionTest {

    private val ui = FakeUi()
    private val saver = FakeSaver()
    private val session = GroupDialogSession(SUBSCRIPTION, saver, ui)

    // ---- a new session: gci AC-15, AC-10 ----

    /**
     * A rotation builds a new session (plan S5), so "a new session has nothing" is the JVM part
     * of gci AC-15.
     */
    @Test
    fun gci_ac15_ac10_newSessionHasNoPendingGroupsAndHasCalledNothing() {
        assertEquals("AC-15: a new session has no pending groups", emptyList<PendingGroup>(), session.pending)
        assertFalse("a new session is not busy", session.isBusy)
        assertEquals("creating the session calls nothing on the ui", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-10: creating the session does not call the saver", noRequests, saver.requests)
    }

    @Test
    fun gci_ac15_sessionCreatedAfterAnotherStartsEmptyAndNumbersFromOne() {
        loaded()
        addPending("Music", "News")

        val laterUi = FakeUi()
        val laterSaver = FakeSaver()
        val later = GroupDialogSession(SUBSCRIPTION, laterSaver, laterUi)
        later.onLoaded(emptyList())

        assertEquals(
            "AC-15: pending groups of an earlier session are gone in a new one",
            emptyList<PendingGroup>(),
            later.pending
        )
        assertEquals(
            "AC-15: a name that was only pending in the earlier session is free again",
            NameCheck.OK,
            later.submitName("Music")
        )
        assertEquals("ids start at 1 in every session", listOf(group(1, "Music")), later.pending)
        assertEquals(
            "the earlier session keeps its own pending groups",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )
        assertEquals(
            "AC-15: no session has called its saver",
            noRequests,
            saver.requests + laterSaver.requests
        )
    }

    /** After a rotation the pending groups are gone (S5); an OK then must not create them after all. */
    @Test
    fun gci_ac15_confirmOnASessionCreatedAfterAnotherCreatesNoGroup() {
        loaded()
        addPending("Music", "News")

        val laterUi = FakeUi()
        val laterSaver = FakeSaver()
        val later = GroupDialogSession(SUBSCRIPTION, laterSaver, laterUi)
        later.onLoaded(emptyList())
        laterUi.forgetCalls()
        later.confirm(noIds, noIds)

        assertEquals(
            "AC-15: OK in the new session creates none of the discarded pending groups",
            noRequests,
            saver.requests + laterSaver.requests
        )
        assertEquals(
            "AC-15: OK in the new session has nothing to save and only closes",
            listOf<UiCall>(UiCall.Close),
            laterUi.calls
        )
    }

    // ---- the load: AC-6 ----

    @Test
    fun ac6_onLoadedTellsTheUiLoadedOnceAndNothingElse() {
        session.onLoaded(listOf("Music", "News"))

        assertEquals(
            "onLoaded: ui.showLoaded() once, no other ui call",
            listOf<UiCall>(UiCall.ShowLoaded),
            ui.calls
        )
        assertEquals("onLoaded does not call the saver", noRequests, saver.requests)
        assertFalse("onLoaded does not make the session busy", session.isBusy)
        assertEquals("onLoaded adds no pending group", emptyList<PendingGroup>(), session.pending)
    }

    /** gci AC-1: *New group* is there "whether or not groups exist", so no names is a normal load. */
    @Test
    fun ac6_onLoadedWithoutAnyGroupAlsoTellsTheUiLoaded() {
        session.onLoaded(emptyList())

        assertEquals(
            "onLoaded with no group: ui.showLoaded() once, as with groups",
            listOf<UiCall>(UiCall.ShowLoaded),
            ui.calls
        )
    }

    @Test
    fun ac6_onLoadFailedTellsTheUiOnlyTheLoadError() {
        session.onLoadFailed()

        assertEquals(
            "AC-6: ui.showLoadError() once; showLoaded is never called, so New group stays disabled",
            listOf<UiCall>(UiCall.ShowLoadError),
            ui.calls
        )
        assertEquals("AC-6: a failed load does not call the saver", noRequests, saver.requests)
        assertFalse("AC-6: a failed load does not make the session busy", session.isBusy)
    }

    /** Plan S2, answer A2 in questions.md: after a failed load OK closes without saving, like Cancel. */
    @Test
    fun ac6_afterAFailedLoadConfirmOnlyCloses() {
        session.onLoadFailed()
        ui.forgetCalls()

        session.confirm(noIds, noIds)

        assertEquals("AC-6, confirm step 2: only ui.close()", listOf<UiCall>(UiCall.Close), ui.calls)
        assertEquals("AC-6, confirm step 2: nothing is saved", noRequests, saver.requests)
        assertFalse("confirm step 2: the session does not become busy", session.isBusy)
    }

    /**
     * Step 2 comes before the changes are built (step 3): after a failed load nothing is known
     * about the groups, so nothing is saved whatever sets the dialog hands over.
     */
    @Test
    fun ac6_afterAFailedLoadConfirmSavesNothingWhateverSetsItIsGiven() {
        session.onLoadFailed()
        ui.forgetCalls()

        session.confirm(setOf(3L, 7L), setOf(4L))

        assertEquals("AC-6, confirm step 2: nothing is saved", noRequests, saver.requests)
        assertEquals(
            "AC-6, confirm step 2: only ui.close(), no setBusy",
            listOf<UiCall>(UiCall.Close),
            ui.calls
        )
    }

    /**
     * Step 1: "busy or still loading: return, nothing happens". Until the load has ended the
     * dialog does not know which groups the channel is in, so an OK must neither save nor close.
     *
     * Assumption, see Q6 in questions.md: the plan names no AC for this; AC-3 is the one a save
     * from an unknown state would break.
     */
    @Test
    fun ac3_confirmWhileTheGroupsAreStillLoadingDoesNothing() {
        session.confirm(noIds, noIds)
        session.confirm(setOf(3L), setOf(4L))

        assertEquals("confirm step 1: while loading no ui call, no close", emptyList<UiCall>(), ui.calls)
        assertEquals("confirm step 1: while loading the saver is not called", noRequests, saver.requests)
        assertFalse("confirm step 1: while loading the session does not become busy", session.isBusy)
    }

    /** Step 1 swallows an OK only while loading: the same OK after the load is saved. */
    @Test
    fun ac3_confirmAfterTheLoadSavesWhatAConfirmWhileLoadingIgnored() {
        session.confirm(setOf(3L), setOf(4L))
        session.onLoaded(listOf("Music"))

        session.confirm(setOf(3L), setOf(4L))

        assertEquals(
            "once loaded, confirm saves the differences; the ignored OK left nothing behind",
            listOf(changes(add = listOf(3L), remove = listOf(4L))),
            saver.requests
        )
    }

    // ---- submitName refuses: gci AC-3, AC-4 ----

    @Test
    fun gci_ac3_emptyOrWhitespaceOnlyNameIsRefusedAsEmpty() {
        loaded()

        for (raw in listOf("", " ", "   ", "\t", "\n", " \t\r\n ")) {
            assertEquals("AC-3: submitName(${show(raw)})", NameCheck.EMPTY, session.submitName(raw))
        }
        assertEquals("AC-3: a refused name adds nothing", emptyList<PendingGroup>(), session.pending)
        assertEquals("AC-3: a refused name causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-3: a refused name does not reach the saver", noRequests, saver.requests)
    }

    @Test
    fun gci_ac3_refusedEmptyNameLeavesEarlierPendingGroupsAsTheyWere() {
        loaded()
        addPending("Music", "News")
        session.setChecked(2, false)
        ui.forgetCalls()
        val before = session.pending.toList()

        assertEquals("AC-3: submitName(\"  \")", NameCheck.EMPTY, session.submitName("  "))

        assertEquals("AC-3: a refused name changes no pending group", before, session.pending)
        assertEquals("AC-3: a refused name causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-3: a refused name does not reach the saver", noRequests, saver.requests)
    }

    @Test
    fun gci_ac4_nameOfAnExistingGroupIsRefused() {
        loaded("Music", "News")

        for (raw in listOf("Music", " music ", "MUSIC", "news")) {
            assertEquals(
                "AC-4: submitName(${show(raw)}) with existing groups Music and News",
                NameCheck.DUPLICATE,
                session.submitName(raw)
            )
        }
        assertEquals("AC-4: a refused name adds nothing", emptyList<PendingGroup>(), session.pending)
        assertEquals("AC-4: a refused name causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-4: a refused name does not reach the saver", noRequests, saver.requests)
    }

    /** "Same name" cleans both sides: an existing group stored as " LATE night " still counts. */
    @Test
    fun gci_ac4_existingNameWithOtherSpacingOrCaseIsTheSameName() {
        loaded(" LATE night ")

        assertEquals(
            "AC-4: submitName(\"Late Night\") with an existing group \" LATE night \"",
            NameCheck.DUPLICATE,
            session.submitName("Late Night")
        )
        assertEquals("AC-4: a refused name adds nothing", emptyList<PendingGroup>(), session.pending)
    }

    /**
     * The existing names are those handed to `onLoaded`, and the session "keeps a copy" of them
     * (plan section 4): the dialog's own list may change afterwards without changing which names
     * are taken.
     */
    @Test
    fun gci_ac4_theNamesGivenWhenLoadedAreKeptAsACopy() {
        val namesOfTheDialog = mutableListOf("Music")
        session.onLoaded(namesOfTheDialog)

        namesOfTheDialog.clear()
        namesOfTheDialog += "News"

        assertEquals(
            "AC-4: a name that existed when the groups were loaded is taken",
            NameCheck.DUPLICATE,
            session.submitName("music")
        )
        assertEquals(
            "the session keeps a copy: a name put into the caller's list later is not taken",
            NameCheck.OK,
            session.submitName("News")
        )
    }

    @Test
    fun gci_ac4_duplicateOfPendingGroupIsRefused() {
        loaded()
        addPending("Music")
        ui.forgetCalls()

        for (raw in listOf("Music", "music", " MUSIC ")) {
            assertEquals(
                "AC-4: submitName(${show(raw)}) while \"Music\" is pending",
                NameCheck.DUPLICATE,
                session.submitName(raw)
            )
        }
        assertEquals(
            "AC-4: a refused name leaves the pending groups unchanged",
            listOf(group(1, "Music")),
            session.pending
        )
        assertEquals("AC-4: a refused name causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-4: a refused name does not reach the saver", noRequests, saver.requests)
    }

    /** The plan: "names of all pending groups, checked or not". */
    @Test
    fun gci_ac4_duplicateOfUncheckedPendingGroupIsRefused() {
        loaded()
        addPending("Music", "News")
        session.setChecked(1, false)
        ui.forgetCalls()

        assertEquals(
            "AC-4: submitName(\" music \") while \"Music\" is pending but unchecked",
            NameCheck.DUPLICATE,
            session.submitName(" music ")
        )
        assertEquals(
            "AC-4: a refused name leaves the pending groups unchanged, flags included",
            listOf(group(1, "Music", checked = false), group(2, "News")),
            session.pending
        )
        assertEquals("AC-4: a refused name causes no ui call", emptyList<UiCall>(), ui.calls)
    }

    @Test
    fun gci_ac4_existingAndPendingNamesAreBothTaken() {
        loaded("Music")
        addPending("News")

        assertEquals(
            "AC-4: the existing name is taken while a group is pending",
            NameCheck.DUPLICATE,
            session.submitName("MUSIC")
        )
        assertEquals(
            "AC-4: the pending name is taken while a group exists",
            NameCheck.DUPLICATE,
            session.submitName("NEWS")
        )
        assertEquals("AC-5: a third name is still accepted", NameCheck.OK, session.submitName("Podcasts"))
    }

    /** "Not OK: no state change" includes the id counter: ids follow the groups added. */
    @Test
    fun gci_ac3_ac4_refusedNamesDoNotUseUpAnId() {
        loaded()
        addPending("Music")
        assertEquals("AC-3: submitName(\"\")", NameCheck.EMPTY, session.submitName(""))
        assertEquals("AC-4: submitName(\"music\")", NameCheck.DUPLICATE, session.submitName("music"))
        addPending("News")

        assertEquals(
            "ids are 1, 2, 3, ... in the order added; refused names are not added",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )
    }

    // ---- submitName accepts: gci AC-5, AC-7 ----

    @Test
    fun gci_ac5_acceptedNameBecomesACheckedPendingGroupWithTheCleanedName() {
        loaded()

        assertEquals("AC-5: submitName(\" Music \")", NameCheck.OK, session.submitName(" Music "))

        assertEquals(
            "AC-5: the pending group has the name without surrounding whitespace and is checked",
            listOf(PendingGroup(1, "Music", true)),
            session.pending
        )
    }

    @Test
    fun gci_ac5_acceptedNameIsShownOnceWithAllPendingGroups() {
        loaded()

        session.submitName(" Music ")
        assertEquals(
            "AC-5: one showPending with the pending groups, nothing else on the ui",
            listOf<UiCall>(UiCall.ShowPending(listOf(group(1, "Music")))),
            ui.calls
        )

        session.submitName("News")
        assertEquals(
            "AC-5: the second accepted name gives one more showPending, with both groups",
            listOf<UiCall>(
                UiCall.ShowPending(listOf(group(1, "Music"))),
                UiCall.ShowPending(listOf(group(1, "Music"), group(2, "News")))
            ),
            ui.calls
        )
    }

    @Test
    fun gci_ac5_acceptedNameIsNotSavedYet() {
        loaded()

        assertEquals(NameCheck.OK, session.submitName("Music"))

        assertEquals("AC-5: nothing is saved yet, the saver is not called", noRequests, saver.requests)
        assertFalse("AC-5: adding a pending group does not make the session busy", session.isBusy)
    }

    @Test
    fun gci_ac5_earlierPendingGroupsKeepTheirCheckboxesWhenANameIsAdded() {
        loaded()
        addPending("Music", "News")
        session.setChecked(1, false)
        ui.forgetCalls()

        assertEquals(NameCheck.OK, session.submitName("Podcasts"))

        val expected = listOf(group(1, "Music", checked = false), group(2, "News"), group(3, "Podcasts"))
        assertEquals("AC-5: checkbox changes made before are kept", expected, session.pending)
        assertEquals(
            "AC-5: the ui is told to show the groups with those flags",
            listOf<UiCall>(UiCall.ShowPending(expected)),
            ui.calls
        )
    }

    /** Names that sort differently from the order typed: the order added is what counts (S3). */
    @Test
    fun gci_ac7_threeNamesGiveThreePendingGroupsInOrderWithIds123() {
        loaded()

        assertEquals(NameCheck.OK, session.submitName("Zebra"))
        assertEquals(NameCheck.OK, session.submitName("apple"))
        assertEquals(NameCheck.OK, session.submitName("Mango"))

        assertEquals(
            "AC-7: three pending groups in the order added, ids 1, 2, 3, all checked",
            listOf(group(1, "Zebra"), group(2, "apple"), group(3, "Mango")),
            session.pending
        )
        assertEquals("AC-7: one showPending per accepted name and no other ui call", 3, ui.calls.size)
        assertEquals(
            "AC-7: the last showPending shows all three",
            UiCall.ShowPending(listOf(group(1, "Zebra"), group(2, "apple"), group(3, "Mango"))),
            ui.calls.last()
        )
    }

    /**
     * "Ids are ... never reused": also not after a save was tried. After a failed save the
     * dialog stays open and *New group* is usable again (AC-4), so the next group gets the next
     * id. (A successful save closes the dialog; nothing can be added after it. Q5 in questions.md.)
     */
    @Test
    fun gci_ac7_idsAreNotReusedAfterAFailedSave() {
        loaded()
        addPending("Music", "News")
        session.confirm(noIds, noIds)
        saver.fail(RuntimeException("disk full"))

        assertEquals(NameCheck.OK, session.submitName("Podcasts"))

        assertEquals(
            "ids are never reused: the group added after the failed save gets 3",
            listOf(group(1, "Music"), group(2, "News"), group(3, "Podcasts")),
            session.pending
        )
    }

    /** `pending` is a "read-only snapshot" (plan section 4): a list read earlier does not change. */
    @Test
    fun gci_ac5_pendingReadEarlierIsNotChangedByLaterActions() {
        loaded()
        addPending("Music")
        val snapshot = session.pending
        val asRead = snapshot.toList()

        session.submitName("News")
        session.setChecked(1, false)
        session.confirm(noIds, noIds)
        saver.succeed()

        assertEquals("pending is a snapshot: a list read earlier stays as it was read", asRead, snapshot)
    }

    /** `ui.showPending(pending)` hands over that snapshot, not a list the session keeps changing. */
    @Test
    fun gci_ac5_listGivenToTheUiIsNotChangedByLaterActions() {
        loaded()
        addPending("Music")
        val givenToUi = ui.listsHandedOver.single()

        session.submitName("News")
        session.setChecked(1, false)
        session.confirm(noIds, noIds)
        saver.succeed()

        assertEquals(
            "the list given to showPending stays as it was when the ui got it",
            listOf(group(1, "Music")),
            givenToUi
        )
    }

    // ---- setChecked: gci AC-9 ----

    @Test
    fun gci_ac9_setCheckedChangesOnlyTheFlagOfThatGroup() {
        loaded()
        addPending("Music", "News", "Podcasts")
        ui.forgetCalls()

        session.setChecked(2, false)
        assertEquals(
            "AC-9: only group 2 is unchecked; ids, names and positions stay",
            listOf(group(1, "Music"), group(2, "News", checked = false), group(3, "Podcasts")),
            session.pending
        )

        session.setChecked(1, true)
        assertEquals(
            "checking a group that is checked changes nothing",
            listOf(group(1, "Music"), group(2, "News", checked = false), group(3, "Podcasts")),
            session.pending
        )

        session.setChecked(2, true)
        assertEquals(
            "AC-9: a group can be checked again and keeps its position",
            listOf(group(1, "Music"), group(2, "News"), group(3, "Podcasts")),
            session.pending
        )
        assertEquals("setChecked causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-10: setChecked does not call the saver", noRequests, saver.requests)
    }

    @Test
    fun gci_ac9_ac10_uncheckingEveryPendingGroupOnlyChangesTheFlags() {
        loaded()
        addPending("Music", "News")
        ui.forgetCalls()

        session.setChecked(1, false)
        session.setChecked(2, false)

        assertEquals(
            "AC-9: both groups stay in the list, unchecked",
            listOf(group(1, "Music", checked = false), group(2, "News", checked = false)),
            session.pending
        )
        assertEquals(
            "setChecked causes no ui call, also when nothing is left checked",
            emptyList<UiCall>(),
            ui.calls
        )
        assertEquals("AC-10: setChecked does not call the saver", noRequests, saver.requests)
        assertFalse("setChecked does not make the session busy", session.isBusy)
    }

    @Test
    fun gci_ac9_setCheckedWithUnknownIdIsIgnored() {
        loaded()
        session.setChecked(1, false) // nothing is pending yet
        assertEquals("unknown id on an empty session", emptyList<PendingGroup>(), session.pending)

        addPending("Music", "News")
        ui.forgetCalls()
        for (unknown in listOf(0L, -1L, 3L, 99L)) {
            session.setChecked(unknown, false)
        }

        assertEquals(
            "an unknown id changes no pending group",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )
        assertEquals("an unknown id causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("an unknown id does not call the saver", noRequests, saver.requests)
    }

    // ---- without OK nothing is created: gci AC-10 ----

    @Test
    fun gci_ac10_namingAndTickingWithoutConfirmNeverCallsTheSaver() {
        loaded()
        addPending("Music", "News")
        session.setChecked(1, false)
        session.setChecked(1, true)
        session.submitName("")
        session.submitName("music")
        addPending("Podcasts")
        session.setChecked(3, false)

        assertEquals("AC-10: without confirm() the saver has no call", noRequests, saver.requests)
        assertFalse("AC-10: without confirm() the session is never busy", session.isBusy)
        assertEquals(
            "AC-10: without confirm() the ui is only told to show pending rows",
            emptyList<UiCall>(),
            ui.calls.filterNot { it is UiCall.ShowPending }
        )
    }

    // ---- confirm, which groups are added and removed (step 3): AC-3 ----

    @Test
    fun ac3_tickedGroupsTheChannelWasNotInAreAddedInAscendingOrder() {
        loaded()

        session.confirm(linkedSetOf(9L, 3L, 7L), noIds)

        assertEquals(
            "AC-3: ticked - loaded, sorted ascending; nothing to remove, no new group",
            listOf(changes(add = listOf(3L, 7L, 9L))),
            saver.requests
        )
    }

    @Test
    fun ac3_untickedGroupsTheChannelWasInAreRemovedInAscendingOrder() {
        loaded()

        session.confirm(noIds, linkedSetOf(9L, 4L, 6L))

        assertEquals(
            "AC-3: loaded - ticked, sorted ascending; nothing to add, no new group",
            listOf(changes(remove = listOf(4L, 6L, 9L))),
            saver.requests
        )
    }

    /** Groups 2 and 3 are ticked as they were loaded; any other group is in neither set. */
    @Test
    fun ac3_groupsThatWereNotToggledAreInNeitherList() {
        loaded()

        session.confirm(
            tickedGroupIds = linkedSetOf(20L, 2L, 15L, 3L, 5L),
            loadedGroupIds = linkedSetOf(10L, 3L, 1L, 2L)
        )

        assertEquals(
            "AC-3: only the toggled groups are written: 5, 15, 20 added, 1, 10 removed; 2 and 3 untouched",
            listOf(changes(add = listOf(5L, 15L, 20L), remove = listOf(1L, 10L))),
            saver.requests
        )
    }

    /** "Ascending" is by number: database ids are Longs and need not fit an Int. */
    @Test
    fun ac3_idsAreSortedAsNumbers() {
        loaded()

        session.confirm(
            tickedGroupIds = linkedSetOf(10L, 5_000_000_000L, 9L, 100L),
            loadedGroupIds = linkedSetOf(4_999_999_999L, 30L, 200L)
        )

        assertEquals(
            "AC-3: both lists ascending by value",
            listOf(
                changes(
                    add = listOf(9L, 10L, 100L, 5_000_000_000L),
                    remove = listOf(30L, 200L, 4_999_999_999L)
                )
            ),
            saver.requests
        )
    }

    /**
     * The defect this feature fixes (plan section 1, D2): with two or more toggled groups some
     * were lost. Fifty toggles, 25 ticks and 25 unticks, must all arrive, in one `save` call.
     */
    @Test
    fun ac3_fiftyToggledGroupsAreAllInTheOneSave() {
        loaded()
        val stillTicked = listOf(51L, 52L, 53L, 54L, 55L)
        val newlyTicked = (2L..50L step 2).toList()
        val unticked = (1L..49L step 2).toList()

        session.confirm(
            tickedGroupIds = (newlyTicked.reversed() + stillTicked).toSet(),
            loadedGroupIds = (stillTicked + unticked.reversed()).toSet()
        )

        assertEquals("AC-3: all fifty toggles go into one save call", 1, saver.requests.size)
        val saved = saver.requests.single()
        assertEquals("AC-3: every one of the 25 ticked groups is added", newlyTicked, saved.addToGroupIds)
        assertEquals("AC-3: every one of the 25 unticked groups is removed", unticked, saved.removeFromGroupIds)
        assertEquals("AC-3: no group is created", emptyList<String>(), saved.newGroupNames)
        assertEquals("the save is for the channel of the dialog", SUBSCRIPTION, saved.subscriptionId)
    }

    // ---- confirm, everything in one save (steps 3 and 5): AC-2, gci AC-8, AC-9, AC-11 ----

    @Test
    fun ac2_newGroupsAddsAndRemovesArriveInOneSaveCall() {
        loaded("Old")
        addPending("Music", "News")

        session.confirm(tickedGroupIds = setOf(7L, 3L, 5L), loadedGroupIds = setOf(5L, 9L, 4L))

        assertEquals(
            "AC-2: one save call carries the new groups, the adds and the removes",
            listOf(
                GroupChanges(
                    subscriptionId = SUBSCRIPTION,
                    newGroupNames = listOf("Music", "News"),
                    addToGroupIds = listOf(3L, 7L),
                    removeFromGroupIds = listOf(4L, 9L)
                )
            ),
            saver.requests
        )
    }

    /** Toggles alone, the case of the old dialog, now go through the same saver (plan E6). */
    @Test
    fun ac2_togglesWithoutPendingGroupsAlsoGoToTheSaver() {
        loaded("Music")
        ui.forgetCalls()

        session.confirm(tickedGroupIds = setOf(3L), loadedGroupIds = setOf(4L))

        assertEquals(
            "AC-2: checkbox changes without a new group are saved by the saver too",
            listOf(changes(add = listOf(3L), remove = listOf(4L))),
            saver.requests
        )
        assertEquals(
            "the session is busy until the saver answers; nothing is closed yet",
            listOf<UiCall>(UiCall.SetBusy(true)),
            ui.calls
        )
    }

    @Test
    fun gci_ac8_confirmGivesTheSaverTheCheckedNamesAndTheSubscriptionOnce() {
        loaded()
        addPending(" Music ", "News")

        session.confirm(noIds, noIds)

        assertEquals(
            "AC-8: one save call with the cleaned names and the channel's subscription id",
            listOf(changes(names = listOf("Music", "News"))),
            saver.requests
        )
    }

    @Test
    fun gci_ac9_uncheckedPendingGroupIsNotInTheChanges() {
        loaded()
        addPending("Music", "News", "Podcasts")
        session.setChecked(2, false)

        session.confirm(tickedGroupIds = setOf(3L), loadedGroupIds = noIds)

        assertEquals(
            "AC-9: the unchecked group \"News\" is not created; the rest is saved",
            listOf(changes(names = listOf("Music", "Podcasts"), add = listOf(3L))),
            saver.requests
        )
    }

    @Test
    fun gci_ac9_ac11_reCheckedPendingGroupIsSavedAgainAtItsOldPosition() {
        loaded()
        addPending("Music", "News")
        session.setChecked(1, false)
        session.setChecked(1, true)

        session.confirm(noIds, noIds)

        assertEquals(
            "AC-9: a re-checked group is created again; AC-11: in the order added, not the order checked",
            listOf(changes(names = listOf("Music", "News"))),
            saver.requests
        )
    }

    @Test
    fun gci_ac11_saverGetsTheNamesInTheOrderAdded() {
        loaded()
        addPending("Zebra", "apple", "Mango", "banana")

        session.confirm(noIds, noIds)

        assertEquals(
            "AC-11: several groups keep the order in which they were added (not sorted)",
            listOf(changes(names = listOf("Zebra", "apple", "Mango", "banana"))),
            saver.requests
        )
    }

    // ---- confirm without any difference (step 4): AC-2, gci AC-9 ----

    @Test
    fun ac2_withoutAnyDifferenceConfirmOnlyCloses() {
        loaded("Music", "News")
        ui.forgetCalls()

        session.confirm(tickedGroupIds = linkedSetOf(4L, 2L), loadedGroupIds = linkedSetOf(2L, 4L))

        assertEquals(
            "confirm step 4: nothing to save: only ui.close(), no setBusy",
            listOf<UiCall>(UiCall.Close),
            ui.calls
        )
        assertEquals("confirm step 4: nothing to save: the saver is not called", noRequests, saver.requests)
        assertFalse("confirm step 4: the session does not become busy", session.isBusy)
    }

    /** No group at all, nothing ticked, nothing named: the empty dialog closes on OK. */
    @Test
    fun ac2_withNoGroupsAndNothingPendingConfirmOnlyCloses() {
        loaded()

        session.confirm(noIds, noIds)

        assertEquals("confirm step 4: only ui.close()", listOf<UiCall>(UiCall.Close), ui.calls)
        assertEquals("confirm step 4: the saver is not called", noRequests, saver.requests)
    }

    /** Step 1 swallows an OK only while busy or loading. Without a difference every OK closes. */
    @Test
    fun ac2_withoutAnyDifferenceEveryConfirmCloses() {
        loaded()

        session.confirm(setOf(2L), setOf(2L))
        session.confirm(setOf(2L), setOf(2L))

        assertEquals(
            "confirm step 4: each confirm() without a difference closes",
            listOf<UiCall>(UiCall.Close, UiCall.Close),
            ui.calls
        )
        assertEquals("the saver is not called", noRequests, saver.requests)
    }

    @Test
    fun gci_ac9_allPendingGroupsUncheckedAndNoToggleMeansNothingIsSaved() {
        loaded()
        addPending("Music", "News")
        ui.forgetCalls()
        session.setChecked(1, false)
        session.setChecked(2, false)

        session.confirm(setOf(2L), setOf(2L))

        assertEquals("AC-9: all unchecked: the saver is never called", noRequests, saver.requests)
        assertEquals(
            "AC-9: all unchecked and no toggle: close once and nothing else",
            listOf<UiCall>(UiCall.Close),
            ui.calls
        )
        assertFalse("AC-9: all unchecked: the session does not become busy", session.isBusy)
        assertEquals(
            "confirm step 4 changes no pending group",
            listOf(group(1, "Music", checked = false), group(2, "News", checked = false)),
            session.pending
        )
    }

    // ---- while the save runs (steps 1 and 5): AC-5 ----

    /** Step 5: `ui.setBusy(true)`, then `saver.save`; the session is busy during that call. */
    @Test
    fun ac5_confirmTellsTheUiBusyBeforeItCallsTheSaver() {
        loaded()
        addPending("Music")
        ui.forgetCalls()
        var uiCallsBeforeSaver: List<UiCall>? = null
        var busyWhenSaverCalled: Boolean? = null
        saver.onSave = {
            uiCallsBeforeSaver = ui.calls
            busyWhenSaverCalled = session.isBusy
        }

        session.confirm(setOf(3L), noIds)

        assertEquals(
            "AC-5, confirm step 5: ui.setBusy(true) is the only ui call before saver.save",
            listOf<UiCall>(UiCall.SetBusy(true)),
            uiCallsBeforeSaver
        )
        assertEquals("AC-5, confirm step 5: isBusy is true inside saver.save", true, busyWhenSaverCalled)
    }

    /**
     * Step 5 lists `isBusy = true` before `ui.setBusy(true)`, so the session already reports
     * busy while the ui is being told (group-create-inline's questions.md, A4, for the same
     * wording).
     */
    @Test
    fun ac5_sessionIsAlreadyBusyWhenTheUiIsToldBusy() {
        loaded()
        addPending("Music")
        ui.forgetCalls()
        val seenByUi = watchUi()

        session.confirm(noIds, noIds)

        assertEquals(
            "confirm step 5: isBusy = true comes before ui.setBusy(true)",
            listOf(Seen(UiCall.SetBusy(true), busy = true, pending = listOf(group(1, "Music")))),
            seenByUi
        )
    }

    @Test
    fun ac5_untilTheSaverAnswersTheSessionStaysBusyAndTheUiHearsNothingMore() {
        loaded()
        addPending("Music", "News")
        ui.forgetCalls()

        session.confirm(setOf(3L), setOf(4L))

        assertTrue("AC-5: isBusy is true while the save call is unanswered", session.isBusy)
        assertEquals(
            "AC-5: while unanswered: only setBusy(true); no setBusy(false), no close yet",
            listOf<UiCall>(UiCall.SetBusy(true)),
            ui.calls
        )
        assertEquals(
            "while unanswered: the pending groups are still there",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )
    }

    /** Step 1: a second OK while the save runs is swallowed, whatever sets it brings. */
    @Test
    fun ac5_secondConfirmWhileBusyDoesNothing() {
        loaded()
        addPending("Music", "News")
        session.confirm(setOf(3L), setOf(4L))
        ui.forgetCalls()

        session.confirm(setOf(3L), setOf(4L))
        session.confirm(setOf(8L), setOf(6L))

        assertEquals(
            "AC-5, confirm step 1: while busy the saver is not called again",
            listOf(changes(names = listOf("Music", "News"), add = listOf(3L), remove = listOf(4L))),
            saver.requests
        )
        assertEquals("AC-5, confirm step 1: while busy no ui call", emptyList<UiCall>(), ui.calls)
        assertTrue("confirm step 1: the session stays busy", session.isBusy)
        assertEquals(
            "confirm step 1: the pending groups are unchanged",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )

        saver.succeed()

        assertEquals(
            "the one save call is completed once: one close",
            listOf<UiCall>(UiCall.Close),
            ui.calls
        )
        assertEquals("no save call is left unanswered", 0, saver.unanswered)
    }

    // ---- the saver reports success (step 6): AC-2 ----

    /**
     * Step 6: "`ui.close()` only". The dialog goes away, so its controls are not enabled again
     * (no `setBusy(false)`) and the pending rows are not redrawn (no `showPending`).
     */
    @Test
    fun ac2_successOnlyClosesTheDialog() {
        loaded()
        addPending("Music", "News")
        session.confirm(setOf(3L), setOf(4L))
        ui.forgetCalls()

        saver.succeed()

        assertEquals(
            "confirm step 6: ui.close() and no other ui call",
            listOf<UiCall>(UiCall.Close),
            ui.calls
        )
        assertFalse("confirm step 6: after the answer the session is not busy", session.isBusy)
        assertEquals("AC-2: everything was saved by one save call", 1, saver.requests.size)
    }

    /** Step 6: "`isBusy = false`, then `ui.close()`". */
    @Test
    fun ac2_onSuccessTheSessionIsNoLongerBusyWhenTheUiIsToldToClose() {
        loaded()
        addPending("Music")
        session.confirm(noIds, noIds)
        var busyWhenClosed: Boolean? = null
        ui.onCall = { call -> if (call == UiCall.Close) busyWhenClosed = session.isBusy }

        saver.succeed()

        assertEquals("confirm step 6: isBusy is false already when close() is called", false, busyWhenClosed)
    }

    /** "The callback may arrive inside that call": the result must be the same as when it arrives later. */
    @Test
    fun ac2_successReportedInsideTheSaverCallGivesTheSameUiCalls() {
        saver.answer = SaverAnswer.SuccessAtOnce
        loaded()
        addPending("Music")
        ui.forgetCalls()

        session.confirm(setOf(3L), noIds)

        assertEquals(
            "setBusy(true) of step 5, then close() of step 6",
            listOf<UiCall>(UiCall.SetBusy(true), UiCall.Close),
            ui.calls
        )
        assertFalse("an answer inside the saver call must not leave the session busy", session.isBusy)
    }

    /** Toggles alone end the same way as a save with new groups. */
    @Test
    fun ac2_successOfASaveWithoutPendingGroupsAlsoOnlyCloses() {
        loaded("Music")
        session.confirm(setOf(3L, 7L), setOf(4L))
        ui.forgetCalls()

        saver.succeed()

        assertEquals("confirm step 6: ui.close() only", listOf<UiCall>(UiCall.Close), ui.calls)
        assertFalse("after the answer the session is not busy", session.isBusy)
    }

    // ---- the saver reports an error (step 7): AC-4 ----

    @Test
    fun ac4_errorClearsBusyAndShowsTheErrorWithoutClosing() {
        loaded()
        addPending("Music", "News")
        session.confirm(setOf(3L), setOf(4L))
        ui.forgetCalls()

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "AC-4, confirm step 7: setBusy(false), showSaveError; the dialog stays open: no close",
            listOf<UiCall>(UiCall.SetBusy(false), UiCall.ShowSaveError),
            ui.calls
        )
        assertFalse("AC-4: after the error the session is not busy, OK can be pressed again", session.isBusy)
    }

    /** AC-4 replaces gci AC-12's "behaves as today": a failed save of checkboxes alone is shown too. */
    @Test
    fun ac4_errorOfASaveWithoutPendingGroupsIsShownAndKeepsTheDialogOpen() {
        loaded("Music")
        session.confirm(setOf(3L), setOf(4L))
        ui.forgetCalls()

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "AC-4: checkbox changes alone: setBusy(false), showSaveError, no close",
            listOf<UiCall>(UiCall.SetBusy(false), UiCall.ShowSaveError),
            ui.calls
        )
        assertFalse("AC-4: after the error the session is not busy", session.isBusy)
    }

    @Test
    fun ac4_errorLeavesThePendingGroupsWithNamesFlagsAndOrder() {
        loaded()
        addPending("Zebra", "apple", "Mango")
        session.setChecked(2, false)
        session.confirm(setOf(3L), noIds)

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "AC-4: pending groups are as they were after a failed save",
            listOf(group(1, "Zebra"), group(2, "apple", checked = false), group(3, "Mango")),
            session.pending
        )
    }

    /** Step 7: "`isBusy = false`, pending unchanged; then ..." the ui calls. */
    @Test
    fun ac4_onErrorTheSessionIsSettledBeforeTheUiIsCalled() {
        loaded()
        addPending("Music", "News")
        session.setChecked(2, false)
        session.confirm(noIds, noIds)
        ui.forgetCalls()
        val seenByUi = watchUi()
        val intact = listOf(group(1, "Music"), group(2, "News", checked = false))

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "confirm step 7: isBusy is false and pending intact already at the first ui call",
            listOf(
                Seen(UiCall.SetBusy(false), busy = false, pending = intact),
                Seen(UiCall.ShowSaveError, busy = false, pending = intact)
            ),
            seenByUi
        )
    }

    @Test
    fun ac4_errorReportedInsideTheSaverCallGivesTheSameUiCalls() {
        saver.answer = SaverAnswer.ErrorAtOnce(RuntimeException("disk full"))
        loaded()
        addPending("Music")
        ui.forgetCalls()

        session.confirm(setOf(3L), noIds)

        assertEquals(
            "AC-4: setBusy(true), then the two calls of step 7; no close",
            listOf<UiCall>(UiCall.SetBusy(true), UiCall.SetBusy(false), UiCall.ShowSaveError),
            ui.calls
        )
        assertFalse("an answer inside the saver call must not leave the session busy", session.isBusy)
        assertEquals("AC-4: the pending group is still there", listOf(group(1, "Music")), session.pending)
    }

    @Test
    fun ac4_confirmAfterAnErrorTriesAgainAndCanSucceed() {
        loaded()
        addPending("Music", "News")
        session.confirm(setOf(3L), setOf(4L))
        saver.fail(RuntimeException("disk full"))
        ui.forgetCalls()

        session.confirm(setOf(3L), setOf(4L))

        val request = changes(names = listOf("Music", "News"), add = listOf(3L), remove = listOf(4L))
        assertEquals(
            "AC-4: nothing was saved, so a later confirm() sends all of it again",
            listOf(request, request),
            saver.requests
        )
        assertTrue("the retry makes the session busy again", session.isBusy)

        saver.succeed()

        assertEquals(
            "the retry runs like a first try: steps 5 and 6",
            listOf<UiCall>(UiCall.SetBusy(true), UiCall.Close),
            ui.calls
        )
        assertFalse("after the successful retry the session is not busy", session.isBusy)
    }

    /**
     * After an error the dialog is usable again before the retry. Step 7: "a later `confirm`
     * builds new changes from its arguments and the pending groups of that moment".
     */
    @Test
    fun ac4_retryUsesTheSetsAndPendingGroupsOfThatMoment() {
        loaded()
        addPending("Music", "News")
        session.confirm(tickedGroupIds = setOf(3L), loadedGroupIds = setOf(4L, 6L, 8L))
        saver.fail(RuntimeException("disk full"))

        session.setChecked(1, false)
        assertEquals(NameCheck.OK, session.submitName("Podcasts"))
        session.confirm(tickedGroupIds = setOf(8L, 9L), loadedGroupIds = setOf(4L, 6L, 8L))

        assertEquals(
            "AC-4: the retry saves what is ticked and checked when OK is pressed again",
            listOf(
                changes(names = listOf("Music", "News"), add = listOf(3L), remove = listOf(4L, 6L, 8L)),
                changes(names = listOf("News", "Podcasts"), add = listOf(9L), remove = listOf(4L, 6L))
            ),
            saver.requests
        )
    }

    /** After an error and with every difference taken back, OK has nothing to save and closes. */
    @Test
    fun ac4_retryWithoutAnyDifferenceLeftOnlyCloses() {
        loaded()
        addPending("Music")
        session.confirm(setOf(3L), noIds)
        saver.fail(RuntimeException("disk full"))
        ui.forgetCalls()

        session.setChecked(1, false)
        session.confirm(noIds, noIds)

        assertEquals("the saver was called by the first OK only", 1, saver.requests.size)
        assertEquals("confirm step 4 on the retry: only ui.close()", listOf<UiCall>(UiCall.Close), ui.calls)
    }

    // ---- helpers ----

    /** Set-up: the groups were loaded with these names; the `showLoaded` call is forgotten. */
    private fun loaded(vararg existingNames: String) {
        session.onLoaded(existingNames.toList())
        ui.forgetCalls()
    }

    /** Set-up: names the groups and fails at once if a name is not accepted. */
    private fun addPending(vararg names: String) {
        for (name in names) {
            assertEquals(
                "set-up: submitName(${show(name)}) must be accepted",
                NameCheck.OK,
                session.submitName(name)
            )
        }
    }

    /** From now on notes, for every ui call, what the session said about itself at that moment. */
    private fun watchUi(): List<Seen> {
        val seen = mutableListOf<Seen>()
        ui.onCall = { call -> seen += Seen(call, session.isBusy, session.pending.toList()) }
        return seen
    }

    /** A ui call together with the session's state while the call was running. */
    private data class Seen(val call: UiCall, val busy: Boolean, val pending: List<PendingGroup>)

    private companion object {
        /** Not 1 and beyond the Int range, so that no other number can pass for it. */
        const val SUBSCRIPTION = 5_000_000_042L

        val noRequests = emptyList<GroupChanges>()
        val noIds = emptySet<Long>()

        fun group(id: Long, name: String, checked: Boolean = true) = PendingGroup(id, name, checked)

        /** The changes of one save for [SUBSCRIPTION]; whatever is not named is empty. */
        fun changes(
            names: List<String> = emptyList(),
            add: List<Long> = emptyList(),
            remove: List<Long> = emptyList()
        ) = GroupChanges(SUBSCRIPTION, names, add, remove)
    }
}

/** Makes whitespace visible in a failure message. */
private fun show(raw: String): String =
    "\"" + raw.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") + "\""
