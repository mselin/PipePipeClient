package org.schabi.newpipe.enhancements.groupcreateinline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupCreateSession] (plan section 4): the pending groups of one "Select groups" dialog and
 * what OK does with them.
 *
 * The contract under test, in the plan's words:
 * - `submitName(raw)`: checked against `existingNames()` plus the names of all pending groups,
 *   checked or not. Not `OK`: return the result, no state change, no `ui` call. `OK`: append
 *   `PendingGroup(id, clean(raw), checked = true)`, one `ui.showPending(pending)`. Ids are
 *   1, 2, 3, ... in the order added, never reused. Never calls `saver`.
 * - `setChecked(id, checked)`: sets that flag, position unchanged, unknown id ignored. No `ui`
 *   or `saver` call.
 * - `confirm()`: (1) busy: nothing. (2) names = checked pending names in the order added.
 *   (3) none: `ui.runUpstreamSave()` only. (4) else `isBusy = true`, `ui.setBusy(true)`, then
 *   `saver.createGroups(names, subscriptionId, cb)`. (5) success: pending empty, not busy, then
 *   `ui.setBusy(false)`, `ui.showPending(emptyList())`, `ui.runUpstreamSave()`. (6) error: not
 *   busy, pending unchanged, then `ui.setBusy(false)`, `ui.showSaveError()`.
 *
 * [FakeUi] records every `ui` call, so a comparison with a complete list also proves that
 * nothing else was called. `submitName` and `setChecked` while busy are unspecified by the plan
 * and not tested.
 */
class GroupCreateSessionTest {

    private val existing = mutableListOf<String>()
    private var existingNamesAsked = 0
    private val ui = FakeUi()
    private val saver = FakeSaver()
    private val session = GroupCreateSession(
        SUBSCRIPTION,
        {
            existingNamesAsked++
            existing.toList()
        },
        saver,
        ui
    )

    // ---- a new session: AC-15, AC-10 ----

    /**
     * A rotation builds a new session (plan S5), so "a new session has nothing" is the JVM part
     * of AC-15. The existing names are "asked on every submitName" (plan section 4) and must not
     * be read earlier: the session is created before upstream has loaded them (plan section 10).
     */
    @Test
    fun ac15_ac10_newSessionHasNoPendingGroupsAndHasCalledNothing() {
        assertEquals("AC-15: a new session has no pending groups", emptyList<PendingGroup>(), session.pending)
        assertFalse("a new session is not busy", session.isBusy)
        assertEquals("creating the session calls nothing on the ui", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-10: creating the session does not call the saver", noRequests, saver.requests)
        assertEquals(
            "existing names are asked on submitName, not when the session is created",
            0,
            existingNamesAsked
        )
    }

    @Test
    fun ac15_sessionCreatedAfterAnotherStartsEmptyAndNumbersFromOne() {
        addPending("Music", "News")

        val laterUi = FakeUi()
        val laterSaver = FakeSaver()
        val later = GroupCreateSession(SUBSCRIPTION, { emptyList() }, laterSaver, laterUi)

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
    fun ac15_confirmOnASessionCreatedAfterAnotherCreatesNoGroup() {
        addPending("Music", "News")

        val laterUi = FakeUi()
        val laterSaver = FakeSaver()
        val later = GroupCreateSession(SUBSCRIPTION, { emptyList() }, laterSaver, laterUi)
        later.confirm()

        assertEquals(
            "AC-15: OK in the new session creates none of the discarded pending groups",
            noRequests,
            saver.requests + laterSaver.requests
        )
        assertEquals(
            "AC-15: OK in the new session only runs the upstream save",
            listOf<UiCall>(UiCall.RunUpstreamSave),
            laterUi.calls
        )
    }

    // ---- submitName refuses: AC-3, AC-4 ----

    @Test
    fun ac3_emptyOrWhitespaceOnlyNameIsRefusedAsEmpty() {
        for (raw in listOf("", " ", "   ", "\t", "\n", " \t\r\n ")) {
            assertEquals("AC-3: submitName(${show(raw)})", NameCheck.EMPTY, session.submitName(raw))
        }
        assertEquals("AC-3: a refused name adds nothing", emptyList<PendingGroup>(), session.pending)
        assertEquals("AC-3: a refused name causes no ui call", emptyList<UiCall>(), ui.calls)
        assertEquals("AC-3: a refused name does not reach the saver", noRequests, saver.requests)
    }

    @Test
    fun ac3_refusedEmptyNameLeavesEarlierPendingGroupsAsTheyWere() {
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
    fun ac4_nameOfAnExistingGroupIsRefused() {
        existing += listOf("Music", "News")

        for (raw in listOf("Music", " music ", "MUSIC", "news")) {
            assertEquals(
                "AC-4: submitName(${show(raw)}) with existing groups $existing",
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
    fun ac4_existingNameWithOtherSpacingOrCaseIsTheSameName() {
        existing += " LATE night "

        assertEquals(
            "AC-4: submitName(\"Late Night\") with an existing group \" LATE night \"",
            NameCheck.DUPLICATE,
            session.submitName("Late Night")
        )
        assertEquals("AC-4: a refused name adds nothing", emptyList<PendingGroup>(), session.pending)
    }

    /**
     * The session is built before upstream has loaded the groups (plan S2), so the names must be
     * read at the moment of each submit ("asked on every submitName"), not kept from an earlier
     * moment.
     */
    @Test
    fun ac4_existingNamesAreReadAtEverySubmit() {
        existing += "Music" // loaded after the session was created
        assertEquals(
            "AC-4: a group that appeared after the session was created is taken",
            NameCheck.DUPLICATE,
            session.submitName("music")
        )

        existing.clear()
        existing += "News"
        assertEquals(
            "AC-4: the second submit sees the names as they are then",
            NameCheck.DUPLICATE,
            session.submitName("NEWS")
        )
        assertEquals(
            "AC-4: a name that is no longer among the existing ones is free",
            NameCheck.OK,
            session.submitName("Music")
        )
    }

    @Test
    fun ac4_duplicateOfPendingGroupIsRefused() {
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
    fun ac4_duplicateOfUncheckedPendingGroupIsRefused() {
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
    fun ac4_existingAndPendingNamesAreBothTaken() {
        existing += "Music"
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
    fun ac3_ac4_refusedNamesDoNotUseUpAnId() {
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

    // ---- submitName accepts: AC-5, AC-7 ----

    @Test
    fun ac5_acceptedNameBecomesACheckedPendingGroupWithTheCleanedName() {
        assertEquals("AC-5: submitName(\" Music \")", NameCheck.OK, session.submitName(" Music "))

        assertEquals(
            "AC-5: the pending group has the name without surrounding whitespace and is checked",
            listOf(PendingGroup(1, "Music", true)),
            session.pending
        )
    }

    @Test
    fun ac5_acceptedNameIsShownOnceWithAllPendingGroups() {
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
    fun ac5_acceptedNameIsNotSavedYet() {
        assertEquals(NameCheck.OK, session.submitName("Music"))

        assertEquals("AC-5: nothing is saved yet, the saver is not called", noRequests, saver.requests)
        assertFalse("AC-5: adding a pending group does not make the session busy", session.isBusy)
    }

    @Test
    fun ac5_earlierPendingGroupsKeepTheirCheckboxesWhenANameIsAdded() {
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
    fun ac7_threeNamesGiveThreePendingGroupsInOrderWithIds123() {
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

    /** "Ids are ... never reused": also not after the pending groups were saved and cleared. */
    @Test
    fun ac7_idsAreNotReusedAfterTheSavedGroupsWereCleared() {
        addPending("Music", "News")
        session.confirm()
        saver.succeed()
        assertEquals("set-up: success empties the pending groups", emptyList<PendingGroup>(), session.pending)

        assertEquals(NameCheck.OK, session.submitName("Podcasts"))

        assertEquals(
            "ids are never reused: the next group gets 3",
            listOf(group(3, "Podcasts")),
            session.pending
        )
    }

    /** `pending` is a "read-only snapshot" (plan section 4): a list read earlier does not change. */
    @Test
    fun ac5_pendingReadEarlierIsNotChangedByLaterActions() {
        addPending("Music")
        val snapshot = session.pending
        val asRead = snapshot.toList()

        session.submitName("News")
        session.setChecked(1, false)
        session.confirm()
        saver.succeed()

        assertEquals("pending is a snapshot: a list read earlier stays as it was read", asRead, snapshot)
    }

    /** `ui.showPending(pending)` hands over that snapshot, not a list the session keeps changing. */
    @Test
    fun ac5_listGivenToTheUiIsNotChangedByLaterActions() {
        addPending("Music")
        val givenToUi = ui.listsHandedOver.single()

        session.submitName("News")
        session.setChecked(1, false)
        session.confirm()
        saver.succeed()

        assertEquals(
            "the list given to showPending stays as it was when the ui got it",
            listOf(group(1, "Music")),
            givenToUi
        )
    }

    // ---- setChecked: AC-9 ----

    @Test
    fun ac9_setCheckedChangesOnlyTheFlagOfThatGroup() {
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
    fun ac9_ac10_uncheckingEveryPendingGroupOnlyChangesTheFlags() {
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
    fun ac9_setCheckedWithUnknownIdIsIgnored() {
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

    // ---- without OK nothing is created: AC-10 ----

    @Test
    fun ac10_namingAndTickingWithoutConfirmNeverCallsTheSaver() {
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

    // ---- confirm without a checked pending group (step 3): AC-8, AC-9 ----

    @Test
    fun ac8_withoutPendingGroupsConfirmOnlyRunsTheUpstreamSave() {
        session.confirm()

        assertEquals(
            "AC-8: no pending group: only runUpstreamSave, no setBusy",
            listOf<UiCall>(UiCall.RunUpstreamSave),
            ui.calls
        )
        assertEquals("AC-8: no pending group: the saver is not called", noRequests, saver.requests)
        assertFalse("no pending group: the session does not become busy", session.isBusy)
    }

    /** Step 1 swallows a second OK only while busy. Without names every OK goes on to upstream, as today. */
    @Test
    fun ac8_withoutPendingGroupsEveryConfirmRunsTheUpstreamSave() {
        session.confirm()
        session.confirm()

        assertEquals(
            "AC-8: each confirm() without names runs the upstream save",
            listOf<UiCall>(UiCall.RunUpstreamSave, UiCall.RunUpstreamSave),
            ui.calls
        )
        assertEquals("the saver is not called", noRequests, saver.requests)
    }

    @Test
    fun ac9_allPendingGroupsUncheckedMeansNoGroupIsCreated() {
        addPending("Music", "News")
        ui.forgetCalls()
        session.setChecked(1, false)
        session.setChecked(2, false)

        session.confirm()

        assertEquals("AC-9: all unchecked: the saver is never called", noRequests, saver.requests)
        assertEquals(
            "AC-9: all unchecked: runUpstreamSave once and nothing else",
            listOf<UiCall>(UiCall.RunUpstreamSave),
            ui.calls
        )
        assertFalse("AC-9: all unchecked: the session does not become busy", session.isBusy)
        assertEquals(
            "confirm step 3 changes no pending group",
            listOf(group(1, "Music", checked = false), group(2, "News", checked = false)),
            session.pending
        )
    }

    // ---- confirm with checked pending groups (step 4): AC-8, AC-9, AC-11 ----

    @Test
    fun ac8_confirmGivesTheSaverTheCheckedNamesAndTheSubscriptionOnce() {
        addPending(" Music ", "News")

        session.confirm()

        assertEquals(
            "AC-8: one createGroups call with the cleaned names and the channel's subscription id",
            listOf(FakeSaver.Request(listOf("Music", "News"), SUBSCRIPTION)),
            saver.requests
        )
    }

    /** Step 4: `ui.setBusy(true)`, then `saver.createGroups`; the session is busy during that call. */
    @Test
    fun ac8_confirmTellsTheUiBusyBeforeItCallsTheSaver() {
        addPending("Music")
        ui.forgetCalls()
        var uiCallsBeforeSaver: List<UiCall>? = null
        var busyWhenSaverCalled: Boolean? = null
        saver.onCreateGroups = {
            uiCallsBeforeSaver = ui.calls
            busyWhenSaverCalled = session.isBusy
        }

        session.confirm()

        assertEquals(
            "confirm step 4: ui.setBusy(true) is the only ui call before saver.createGroups",
            listOf<UiCall>(UiCall.SetBusy(true)),
            uiCallsBeforeSaver
        )
        assertEquals("confirm step 4: isBusy is true when the saver is called", true, busyWhenSaverCalled)
    }

    /**
     * Step 4 lists `isBusy = true` before `ui.setBusy(true)`, so the session already reports busy
     * while the ui is being told.
     *
     * Assumption, see Q4 in questions.md: the numbered contract decides. The comment on `isBusy`
     * ("true only while a createGroups call is unanswered") read to the letter would make it
     * false at this moment, because `createGroups` has not been called yet.
     */
    @Test
    fun ac8_sessionIsAlreadyBusyWhenTheUiIsToldBusy() {
        addPending("Music")
        ui.forgetCalls()
        val seenByUi = watchUi()

        session.confirm()

        assertEquals(
            "confirm step 4: isBusy = true comes before ui.setBusy(true)",
            listOf(Seen(UiCall.SetBusy(true), busy = true, pending = listOf(group(1, "Music")))),
            seenByUi
        )
    }

    @Test
    fun ac8_untilTheSaverAnswersTheSessionStaysBusyAndTheUiHearsNothingMore() {
        addPending("Music", "News")
        ui.forgetCalls()

        session.confirm()

        assertTrue("isBusy is true while the createGroups call is unanswered", session.isBusy)
        assertEquals(
            "while unanswered: only setBusy(true), no runUpstreamSave yet",
            listOf<UiCall>(UiCall.SetBusy(true)),
            ui.calls
        )
        assertEquals(
            "while unanswered: the pending groups are still there",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )
    }

    @Test
    fun ac9_uncheckedPendingGroupIsNotGivenToTheSaver() {
        addPending("Music", "News", "Podcasts")
        session.setChecked(2, false)

        session.confirm()

        assertEquals(
            "AC-9: the unchecked group \"News\" is not created",
            listOf(FakeSaver.Request(listOf("Music", "Podcasts"), SUBSCRIPTION)),
            saver.requests
        )
    }

    @Test
    fun ac9_ac11_reCheckedPendingGroupIsSavedAgainAtItsOldPosition() {
        addPending("Music", "News")
        session.setChecked(1, false)
        session.setChecked(1, true)

        session.confirm()

        assertEquals(
            "AC-9: a re-checked group is created again; AC-11: in the order added, not the order checked",
            listOf(FakeSaver.Request(listOf("Music", "News"), SUBSCRIPTION)),
            saver.requests
        )
    }

    @Test
    fun ac11_saverGetsTheNamesInTheOrderAdded() {
        addPending("Zebra", "apple", "Mango", "banana")

        session.confirm()

        assertEquals(
            "AC-11: several groups keep the order in which they were added (not sorted)",
            listOf(FakeSaver.Request(listOf("Zebra", "apple", "Mango", "banana"), SUBSCRIPTION)),
            saver.requests
        )
    }

    // ---- the saver reports success (step 5): AC-8 ----

    @Test
    fun ac8_successClearsBusyThenThePendingRowsThenRunsTheUpstreamSave() {
        addPending("Music", "News")
        session.confirm()
        ui.forgetCalls()

        saver.succeed()

        assertEquals(
            "AC-8, confirm step 5: setBusy(false), showPending(empty), runUpstreamSave, in this order",
            listOf<UiCall>(UiCall.SetBusy(false), UiCall.ShowPending(emptyList()), UiCall.RunUpstreamSave),
            ui.calls
        )
        assertEquals(
            "AC-8: after success no group is pending any more",
            emptyList<PendingGroup>(),
            session.pending
        )
        assertFalse("after the answer the session is not busy", session.isBusy)
        assertEquals("AC-8: the saver was called once", 1, saver.requests.size)
    }

    /** Step 5: "pending becomes empty", not only the groups that were created. */
    @Test
    fun ac8_ac9_successAlsoRemovesTheUncheckedPendingGroups() {
        addPending("Music", "News", "Podcasts")
        session.setChecked(2, false)
        session.confirm()
        ui.forgetCalls()

        saver.succeed()

        assertEquals(
            "AC-9: the unchecked group was not created",
            listOf(FakeSaver.Request(listOf("Music", "Podcasts"), SUBSCRIPTION)),
            saver.requests
        )
        assertEquals(
            "confirm step 5: pending becomes empty, the unchecked group does not stay behind",
            emptyList<PendingGroup>(),
            session.pending
        )
        assertEquals(
            "confirm step 5: the ui is told to show no pending rows",
            listOf<UiCall>(UiCall.SetBusy(false), UiCall.ShowPending(emptyList()), UiCall.RunUpstreamSave),
            ui.calls
        )
    }

    /** Step 5: "pending becomes empty, isBusy = false; then ..." the ui calls. */
    @Test
    fun ac8_onSuccessTheSessionIsSettledBeforeTheUiIsCalled() {
        addPending("Music", "News")
        session.confirm()
        ui.forgetCalls()
        val seenByUi = watchUi()

        saver.succeed()

        assertEquals(
            "confirm step 5: pending is empty and isBusy false already at the first ui call",
            listOf(
                Seen(UiCall.SetBusy(false), busy = false, pending = emptyList()),
                Seen(UiCall.ShowPending(emptyList()), busy = false, pending = emptyList()),
                Seen(UiCall.RunUpstreamSave, busy = false, pending = emptyList())
            ),
            seenByUi
        )
    }

    /** "The callback may arrive inside that call": the result must be the same as when it arrives later. */
    @Test
    fun ac8_successReportedInsideTheSaverCallGivesTheSameUiCalls() {
        saver.answer = SaverAnswer.SuccessAtOnce
        addPending("Music")
        ui.forgetCalls()

        session.confirm()

        assertEquals(
            "AC-8: setBusy(true), then the three calls of step 5",
            listOf<UiCall>(
                UiCall.SetBusy(true),
                UiCall.SetBusy(false),
                UiCall.ShowPending(emptyList()),
                UiCall.RunUpstreamSave
            ),
            ui.calls
        )
        assertFalse("an answer inside the saver call must not leave the session busy", session.isBusy)
        assertEquals(
            "AC-8: after success no group is pending any more",
            emptyList<PendingGroup>(),
            session.pending
        )
    }

    @Test
    fun ac8_createdGroupsAreNotCreatedAgainByAnotherConfirm() {
        addPending("Music")
        session.confirm()
        saver.succeed()
        ui.forgetCalls()

        session.confirm()

        assertEquals(
            "AC-8: a group is created once, a later OK does not create it again",
            1,
            saver.requests.size
        )
        assertEquals(
            "a confirm() after success has no names left: only runUpstreamSave",
            listOf<UiCall>(UiCall.RunUpstreamSave),
            ui.calls
        )
    }

    // ---- the saver reports an error (step 6), double tap (step 1): AC-12 ----

    @Test
    fun ac12_errorClearsBusyAndShowsTheErrorWithoutTheUpstreamSave() {
        addPending("Music", "News")
        session.confirm()
        ui.forgetCalls()

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "AC-12, confirm step 6: setBusy(false), showSaveError; no runUpstreamSave, no showPending",
            listOf<UiCall>(UiCall.SetBusy(false), UiCall.ShowSaveError),
            ui.calls
        )
        assertFalse("AC-12: after the error the session is not busy, OK can be pressed again", session.isBusy)
    }

    @Test
    fun ac12_errorLeavesThePendingGroupsWithNamesFlagsAndOrder() {
        addPending("Zebra", "apple", "Mango")
        session.setChecked(2, false)
        session.confirm()

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "AC-12: pending groups are unchanged after a failed save",
            listOf(group(1, "Zebra"), group(2, "apple", checked = false), group(3, "Mango")),
            session.pending
        )
    }

    /** Step 6: "isBusy = false, pending unchanged; then ..." the ui calls. */
    @Test
    fun ac12_onErrorTheSessionIsSettledBeforeTheUiIsCalled() {
        addPending("Music", "News")
        session.setChecked(2, false)
        session.confirm()
        ui.forgetCalls()
        val seenByUi = watchUi()
        val intact = listOf(group(1, "Music"), group(2, "News", checked = false))

        saver.fail(RuntimeException("disk full"))

        assertEquals(
            "confirm step 6: isBusy is false and pending intact already at the first ui call",
            listOf(
                Seen(UiCall.SetBusy(false), busy = false, pending = intact),
                Seen(UiCall.ShowSaveError, busy = false, pending = intact)
            ),
            seenByUi
        )
    }

    @Test
    fun ac12_errorReportedInsideTheSaverCallGivesTheSameUiCalls() {
        saver.answer = SaverAnswer.ErrorAtOnce(RuntimeException("disk full"))
        addPending("Music")
        ui.forgetCalls()

        session.confirm()

        assertEquals(
            "AC-12: setBusy(true), then the two calls of step 6; no runUpstreamSave",
            listOf<UiCall>(UiCall.SetBusy(true), UiCall.SetBusy(false), UiCall.ShowSaveError),
            ui.calls
        )
        assertFalse("an answer inside the saver call must not leave the session busy", session.isBusy)
        assertEquals("AC-12: the pending group is still there", listOf(group(1, "Music")), session.pending)
    }

    @Test
    fun ac12_confirmAfterAnErrorTriesAgainAndCanSucceed() {
        addPending("Music", "News")
        session.confirm()
        saver.fail(RuntimeException("disk full"))
        ui.forgetCalls()

        session.confirm()

        val request = FakeSaver.Request(listOf("Music", "News"), SUBSCRIPTION)
        assertEquals(
            "AC-12: a later confirm() calls the saver again with the same names",
            listOf(request, request),
            saver.requests
        )
        assertTrue("the retry makes the session busy again", session.isBusy)

        saver.succeed()

        assertEquals(
            "AC-12: the retry runs like a first try: steps 4 and 5",
            listOf<UiCall>(
                UiCall.SetBusy(true),
                UiCall.SetBusy(false),
                UiCall.ShowPending(emptyList()),
                UiCall.RunUpstreamSave
            ),
            ui.calls
        )
        assertEquals(
            "after the successful retry no group is pending",
            emptyList<PendingGroup>(),
            session.pending
        )
        assertFalse("after the successful retry the session is not busy", session.isBusy)
    }

    /** After an error the session is not busy, so the dialog is usable again before the retry. */
    @Test
    fun ac12_retryUsesTheNamesAndCheckboxesOfThatMoment() {
        addPending("Music", "News")
        session.confirm()
        saver.fail(RuntimeException("disk full"))

        session.setChecked(1, false)
        assertEquals(NameCheck.OK, session.submitName("Podcasts"))
        session.confirm()

        assertEquals(
            "AC-12: the retry creates what is checked when OK is pressed again",
            listOf(
                FakeSaver.Request(listOf("Music", "News"), SUBSCRIPTION),
                FakeSaver.Request(listOf("News", "Podcasts"), SUBSCRIPTION)
            ),
            saver.requests
        )
    }

    @Test
    fun ac12_secondConfirmWhileBusyDoesNothing() {
        addPending("Music", "News")
        session.confirm()
        ui.forgetCalls()

        session.confirm()
        session.confirm()

        assertEquals("confirm step 1: while busy the saver is not called again", 1, saver.requests.size)
        assertEquals("confirm step 1: while busy no ui call", emptyList<UiCall>(), ui.calls)
        assertTrue("confirm step 1: the session stays busy", session.isBusy)
        assertEquals(
            "confirm step 1: the pending groups are unchanged",
            listOf(group(1, "Music"), group(2, "News")),
            session.pending
        )

        saver.succeed()

        assertEquals(
            "the one createGroups call is completed once: one runUpstreamSave",
            listOf<UiCall>(UiCall.SetBusy(false), UiCall.ShowPending(emptyList()), UiCall.RunUpstreamSave),
            ui.calls
        )
        assertEquals("no createGroups call is left unanswered", 0, saver.unanswered)
    }

    // ---- helpers ----

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

        val noRequests = emptyList<FakeSaver.Request>()

        fun group(id: Long, name: String, checked: Boolean = true) = PendingGroup(id, name, checked)
    }
}

/** Makes whitespace visible in a failure message. */
private fun show(raw: String): String =
    "\"" + raw.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") + "\""
