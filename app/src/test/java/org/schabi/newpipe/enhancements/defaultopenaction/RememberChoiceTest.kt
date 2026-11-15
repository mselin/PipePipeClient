package org.schabi.newpipe.enhancements.defaultopenaction

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.enhancements.defaultopenaction.RecordingRememberStore.Saved
import org.schabi.newpipe.enhancements.queueguard.GuardChoice

/**
 * `RememberChoice` (plan 4.3) with a recording `RememberStore` ("M" in plan section 6): what the checkbox
 * "Remember my choice" saves, and when it saves nothing.
 *
 * `onChoice(choice, checked)` is what the checkbox add-on calls for a row click in the tap dialog,
 * `checked` being the checkbox at that moment (plan 4.4).
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
}
