package org.schabi.newpipe.enhancements.crashfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.function.Consumer

/**
 * AC-1 (C1, step 1b): the one branch of `LateDialog` that runs without Android: no fragment
 * manager at all (`canShow` = `fm != null && !fm.isDestroyed && !fm.isStateSaved`).
 *
 * The plan lists no JVM test for `LateDialog`; this is the part that needs no Android object.
 * The two branches that matter on a device (destroyed, state saved) need a real
 * `FragmentManager`, which does not exist on the JVM without Robolectric; device step D1b
 * covers them. Assumption: both `Consumer`s of the signature are `java.util.function.Consumer`,
 * the type `PlaylistDialog.createCorrespondingDialog` takes.
 */
class LateDialogTest {

    @Test
    fun ac1_noFragmentManager_cannotShow() {
        assertFalse(LateDialog.canShow(null))
    }

    @Test
    fun ac1_noFragmentManager_actionIsDropped() {
        val delivered = ArrayList<String>()
        val guarded: Consumer<String> = LateDialog.ifCanShow(null, Consumer { delivered.add(it) })
        assertEquals("wrapping must not run the action", emptyList<String>(), delivered)

        guarded.accept("dialog")
        assertEquals("the action must be dropped, not run", emptyList<String>(), delivered)
    }
}
