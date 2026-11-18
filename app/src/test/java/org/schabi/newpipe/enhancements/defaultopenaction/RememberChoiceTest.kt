package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.enhancements.defaultopenaction.RecordingRememberStore.Saved
import org.schabi.newpipe.enhancements.queueguard.GuardChoice

/**
 * `RememberChoice` (plan 4.3) with a recording `RememberStore` ("M" in plan section 6): what the checkbox
 * "Remember my choice" saves, and when it saves nothing.
 *
 * `onChoice(choice, checked)` is what the checkbox add-on calls for a row click in the tap dialog,
 * `checked` being the checkbox at that moment (plan 4.4).
 *
 * Amendment 2 (spec AM-1, plan B2.3): `onChoice` returns whether the choice was saved, "which is when the
 * notice "asking is now off" is due". The tests `am1_...` are the table of B2.3; the eight tests of before
 * stay as they were.
 */
class RememberChoiceTest {

    private val store = RecordingRememberStore()
    private val remember = RememberChoice(store)

    private val nothing = emptyList<Saved>()

    // ---- AC-8: a choice with the checkbox checked ----

    /**
     * AC-8: the choice becomes "the default action" and "Ask before replacing the queue" is switched off.
     * Plan 4.3: "exactly one call, `store.save(DefaultTapAction.fromChoice(choice), false)`".
     */
    @Test
    fun ac8_checkedAddToQueueIsSavedOnceAsDefaultActionWithAskOff() {
        remember.onChoice(GuardChoice.ADD_TO_QUEUE, true)

        assertEquals(listOf(Saved(DefaultTapAction.ADD_TO_QUEUE, askBeforeReplacing = false)), store.saved)
    }

    @Test
    fun ac8_checkedPlayNextIsSavedOnceAsDefaultActionWithAskOff() {
        remember.onChoice(GuardChoice.PLAY_NEXT, true)

        assertEquals(listOf(Saved(DefaultTapAction.PLAY_NEXT, askBeforeReplacing = false)), store.saved)
    }

    /** *Play now* is the default action already on a fresh install; remembering it still switches ask off. */
    @Test
    fun ac8_checkedPlayNowIsSavedOnceAsDefaultActionWithAskOff() {
        remember.onChoice(GuardChoice.PLAY_NOW, true)

        assertEquals(listOf(Saved(DefaultTapAction.PLAY_NOW, askBeforeReplacing = false)), store.saved)
    }

    /**
     * AC-8 again later: the user switched ask back on in Settings and remembers another choice in a new
     * dialog. Every dialog has its own `RememberChoice` on the same store (plan 4.4); each saves its own.
     */
    @Test
    fun ac8_aChoiceRememberedInALaterDialogIsSavedToo() {
        remember.onChoice(GuardChoice.PLAY_NEXT, true)
        RememberChoice(store).onChoice(GuardChoice.ADD_TO_QUEUE, true)

        assertEquals(
            listOf(
                Saved(DefaultTapAction.PLAY_NEXT, askBeforeReplacing = false),
                Saved(DefaultTapAction.ADD_TO_QUEUE, askBeforeReplacing = false),
            ),
            store.saved,
        )
    }

    // ---- AC-9: nothing is saved ----

    /** AC-9: "*Cancel* ... saves nothing, checked or not." Plan 4.3: "`CANCEL`: none". */
    @Test
    fun ac9_checkedCancelSavesNothing() {
        remember.onChoice(GuardChoice.CANCEL, true)

        assertEquals(nothing, store.saved)
    }

    /** Plan section 6, AC-9: "every choice unchecked" saves nothing. Plan 4.3: "Not `checked` ...: none". */
    @Test
    fun ac9_uncheckedChoiceSavesNothingWhicheverItIs() {
        for (choice in ALL_CHOICES) {
            val freshStore = RecordingRememberStore()

            RememberChoice(freshStore).onChoice(choice, false)

            assertEquals("$choice with the checkbox unchecked", nothing, freshStore.saved)
        }
    }

    /**
     * AC-9: a dismissal saves nothing. Queue-guard's dialog calls the add-on for no dismissal (QG plan
     * 3.3), so for `RememberChoice` a dismissed dialog is one that never called it: creating it for the
     * dialog (plan 4.4) must not save anything by itself. AC-7 too: opening the dialog changes nothing.
     */
    @Test
    fun ac7_ac9_openingADialogSavesNothing() {
        RememberChoice(store)
        RememberChoice(store)

        assertEquals(nothing, store.saved)
    }

    /** AC-9 after AC-8 on one store: only the checked choice was saved. */
    @Test
    fun ac8_ac9_onlyTheChoicesMadeWithTheCheckboxCheckedAreSaved() {
        RememberChoice(store).onChoice(GuardChoice.ADD_TO_QUEUE, false)
        RememberChoice(store).onChoice(GuardChoice.CANCEL, true)
        RememberChoice(store).onChoice(GuardChoice.PLAY_NEXT, true)
        RememberChoice(store).onChoice(GuardChoice.PLAY_NOW, false)
        RememberChoice(store).onChoice(GuardChoice.CANCEL, false)

        assertEquals(listOf(Saved(DefaultTapAction.PLAY_NEXT, askBeforeReplacing = false)), store.saved)
    }

    // ---- AM-1: the result says whether the notice "asking is now off" is due ----

    private val rememberable = listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW)

    /**
     * AM-1: "When a choice is saved with "Remember my choice", a short message says that asking is now
     * off ... It is shown for every remembered choice, *Play now* included." Plan B2.3, first row: checked
     * and one of the three choices -> saved once, result `true`.
     */
    @Test
    fun am1_checkedChoiceReturnsTrueForEachOfTheThreeChoicesPlayNowIncluded() {
        for (choice in rememberable) {
            val freshStore = RecordingRememberStore()

            val noticeDue = RememberChoice(freshStore).onChoice(choice, true)

            assertTrue("$choice with the checkbox checked: the notice is due", noticeDue)
            assertEquals(
                "$choice with the checkbox checked: saved once",
                listOf(Saved(DefaultTapAction.fromChoice(choice)!!, askBeforeReplacing = false)),
                freshStore.saved,
            )
        }
    }

    /** AM-1 with AC-9. Plan B2.3, second row: checked and `CANCEL` -> not saved, result `false`: no notice. */
    @Test
    fun am1_ac9_checkedCancelReturnsFalse() {
        val noticeDue = remember.onChoice(GuardChoice.CANCEL, true)

        assertFalse("Cancel with the checkbox checked: no notice", noticeDue)
        assertEquals(nothing, store.saved)
    }

    /**
     * AM-1 with AC-9. Plan B2.3, third row: not checked, any choice -> not saved, result `false`. A choice
     * that is only carried out leaves asking on, so a notice "asking is now off" would be wrong.
     */
    @Test
    fun am1_ac9_uncheckedChoiceReturnsFalseWhicheverItIs() {
        for (choice in ALL_CHOICES) {
            val freshStore = RecordingRememberStore()

            val noticeDue = RememberChoice(freshStore).onChoice(choice, false)

            assertFalse("$choice with the checkbox unchecked: no notice", noticeDue)
            assertEquals("$choice with the checkbox unchecked", nothing, freshStore.saved)
        }
    }

    /**
     * AM-1, plan B2.3: "Returns whether the choice was saved". Over all eight cases (four choices, checked
     * or not): the result is `true` exactly when one `save` was recorded, and `false` exactly when none was.
     * A result of `true` without a save would announce "asking is now off" while asking is still on; a save
     * with `false` would switch asking off without telling.
     */
    @Test
    fun am1_returnsTrueExactlyWhenItSaved() {
        var cases = 0
        var saved = 0
        for (choice in ALL_CHOICES) {
            for (checked in listOf(true, false)) {
                val freshStore = RecordingRememberStore()

                val noticeDue = RememberChoice(freshStore).onChoice(choice, checked)

                assertEquals(
                    "$choice, checked=$checked: result $noticeDue, saved ${freshStore.saved}",
                    noticeDue,
                    freshStore.saved.size == 1,
                )
                assertTrue("$choice, checked=$checked: at most one save", freshStore.saved.size <= 1)
                cases++
                if (noticeDue) saved++
            }
        }
        assertEquals("cases", 8, cases)
        assertEquals("cases in which the choice is saved and the notice is due", 3, saved)
    }

    /**
     * AM-1, plan B2.3: "The notice is due for every saved choice, ... also when asking was off already or
     * the saved action equals the old one: the result does not look at what was stored before" (questions.md
     * A10: "for every remembered choice"). A second remembered choice on the same store, the same action
     * again included, returns `true` again.
     */
    @Test
    fun am1_aSecondRememberedChoiceOnTheSameStoreReturnsTrueAgainAlsoForTheSameAction() {
        assertTrue("first: Play next", RememberChoice(store).onChoice(GuardChoice.PLAY_NEXT, true))
        assertTrue("second: Play next again", RememberChoice(store).onChoice(GuardChoice.PLAY_NEXT, true))
        assertTrue("third: Play now", RememberChoice(store).onChoice(GuardChoice.PLAY_NOW, true))
        assertTrue("fourth: by the same object", remember.onChoice(GuardChoice.PLAY_NOW, true))
        assertFalse("then unchecked", remember.onChoice(GuardChoice.PLAY_NOW, false))

        assertEquals(
            listOf(
                Saved(DefaultTapAction.PLAY_NEXT, askBeforeReplacing = false),
                Saved(DefaultTapAction.PLAY_NEXT, askBeforeReplacing = false),
                Saved(DefaultTapAction.PLAY_NOW, askBeforeReplacing = false),
                Saved(DefaultTapAction.PLAY_NOW, askBeforeReplacing = false),
            ),
            store.saved,
        )
    }
}
