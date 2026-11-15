package org.schabi.newpipe.enhancements.groupdialog

/** A group named in the "Select groups" dialog that is not saved yet. [id] is a row id, not a database id. */
data class PendingGroup(val id: Long, val name: String, val checked: Boolean)

/** Everything one OK of the "Select groups" dialog changes for the channel [subscriptionId]. */
data class GroupChanges(
    val subscriptionId: Long,
    /** The checked pending groups, in the order added. */
    val newGroupNames: List<String>,
    /** Existing groups the channel joins, ascending. */
    val addToGroupIds: List<Long>,
    /** Existing groups the channel leaves, ascending. */
    val removeFromGroupIds: List<Long>
) {
    /** True if there is nothing to save. */
    val isEmpty: Boolean
        get() = newGroupNames.isEmpty() && addToGroupIds.isEmpty() && removeFromGroupIds.isEmpty()
}

/** Result of [ChangeSaver.save]: `null` = success. */
fun interface SaveCallback {
    fun onResult(error: Throwable?)
}

interface ChangeSaver {
    /** Saves all of [changes] or nothing. Calls [callback] at most once, now or later. */
    fun save(changes: GroupChanges, callback: SaveCallback)
}

interface SelectionUi {
    /** Render exactly these pending rows. */
    fun showPending(pending: List<PendingGroup>)

    /** The groups are loaded: "New group" becomes usable. */
    fun showLoaded()

    /** The groups could not be loaded: show an error instead of the list. */
    fun showLoadError()

    /** Lock or unlock the dialog while the save runs. */
    fun setBusy(busy: Boolean)

    /** Tell the user that nothing was saved. */
    fun showSaveError()

    fun close()
}

/**
 * The load state and the pending groups of one "Select groups" dialog, and what OK does with
 * them and with the checkboxes. Synchronous and confined to the main thread. Plain Kotlin, no
 * Android.
 */
class GroupDialogSession(
    private val subscriptionId: Long,
    private val saver: ChangeSaver,
    private val ui: SelectionUi
) {
    private enum class Load { LOADING, LOADED, FAILED }

    private var load = Load.LOADING
    private var existingNames: List<String> = emptyList()

    // Never mutated in place: every change puts a new list here, so a list that was handed out
    // stays as it was.
    private var items: List<PendingGroup> = emptyList()
    private var nextId = 1L

    /** Read-only snapshot, in the order added. */
    val pending: List<PendingGroup> get() = items

    /** True from the moment [confirm] hands changes to the saver until the saver has answered. */
    var isBusy: Boolean = false
        private set

    /** The dialog has loaded its groups; [existingNames] are their names. */
    fun onLoaded(existingNames: List<String>) {
        load = Load.LOADED
        this.existingNames = existingNames.toList()
        ui.showLoaded()
    }

    /** The dialog could not load its groups. */
    fun onLoadFailed() {
        load = Load.FAILED
        ui.showLoadError()
    }

    /**
     * Checks [raw] against the loaded and the pending names. `OK`: a checked pending group with
     * the cleaned name is appended and shown. Otherwise nothing changes.
     */
    fun submitName(raw: String): NameCheck {
        val result = GroupNameRules.check(raw, existingNames + items.map { it.name })
        if (result != NameCheck.OK) {
            return result
        }

        items = items + PendingGroup(nextId++, GroupNameRules.clean(raw), checked = true)
        ui.showPending(items)
        return NameCheck.OK
    }

    /** Sets the flag of the pending group [id]; an unknown id is ignored. */
    fun setChecked(id: Long, checked: Boolean) {
        items = items.map { if (it.id == id) it.copy(checked = checked) else it }
    }

    /**
     * OK. [tickedGroupIds] are the existing groups ticked now, [loadedGroupIds] those the channel
     * was a member of when the dialog loaded. Saves the differences and the checked pending
     * groups in one call of the saver, or closes if there is nothing to save.
     */
    fun confirm(tickedGroupIds: Set<Long>, loadedGroupIds: Set<Long>) {
        if (isBusy || load == Load.LOADING) {
            return
        }
        if (load == Load.FAILED) {
            ui.close()
            return
        }

        val changes = GroupChanges(
            subscriptionId,
            items.filter { it.checked }.map { it.name },
            (tickedGroupIds - loadedGroupIds).sorted(),
            (loadedGroupIds - tickedGroupIds).sorted()
        )
        if (changes.isEmpty) {
            ui.close()
            return
        }

        isBusy = true
        ui.setBusy(true)
        saver.save(changes) { error -> onSaverAnswered(error) }
    }

    private fun onSaverAnswered(error: Throwable?) {
        isBusy = false
        if (error == null) {
            ui.close()
        } else {
            ui.setBusy(false)
            ui.showSaveError()
        }
    }
}
