package org.schabi.newpipe.enhancements.groupcreateinline

/** A group named in the "Select groups" dialog that is not saved yet. [id] is a row id, not a database id. */
data class PendingGroup(val id: Long, val name: String, val checked: Boolean)

/** Result of [GroupSaver.createGroups]: `null` = success. */
fun interface SaveCallback {
    fun onResult(error: Throwable?)
}

interface GroupSaver {
    /**
     * Creates one group per name, in list order, each with [subscriptionId] as its only
     * member; all or nothing. Calls [callback] exactly once, now or later.
     */
    fun createGroups(names: List<String>, subscriptionId: Long, callback: SaveCallback)
}

interface SelectionUi {
    /** Render exactly these pending rows. */
    fun showPending(pending: List<PendingGroup>)

    /** Lock or unlock the dialog while the pending groups are being created. */
    fun setBusy(busy: Boolean)

    /** Tell the user that creating the pending groups failed. */
    fun showSaveError()

    /** Today's save of the checkboxes, followed by dismiss. */
    fun runUpstreamSave()
}

/**
 * The pending groups of one "Select groups" dialog and what OK does with them.
 * Synchronous and confined to the main thread. Plain Kotlin, no Android.
 */
class GroupCreateSession(
    private val subscriptionId: Long,
    /** Asked on every [submitName]. */
    private val existingNames: () -> List<String>,
    private val saver: GroupSaver,
    private val ui: SelectionUi
) {
    // Never mutated in place: every change puts a new list here, so a list that was handed out
    // stays as it was.
    private var items: List<PendingGroup> = emptyList()
    private var nextId = 1L

    /** Read-only snapshot, in the order added. */
    val pending: List<PendingGroup> get() = items

    /** True from the moment [confirm] hands names to the saver until the saver has answered. */
    var isBusy: Boolean = false
        private set

    /**
     * Checks [raw] against the existing and the pending names. `OK`: a checked pending group with
     * the cleaned name is appended and shown. Otherwise nothing changes.
     */
    fun submitName(raw: String): NameCheck {
        val result = GroupNameRules.check(raw, existingNames() + items.map { it.name })
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

    /** OK: creates the checked pending groups, then runs the upstream save. */
    fun confirm() {
        if (isBusy) {
            return
        }

        val names = items.filter { it.checked }.map { it.name }
        if (names.isEmpty()) {
            ui.runUpstreamSave()
            return
        }

        isBusy = true
        ui.setBusy(true)
        saver.createGroups(names, subscriptionId) { error -> onSaverAnswered(error) }
    }

    private fun onSaverAnswered(error: Throwable?) {
        isBusy = false
        if (error == null) {
            items = emptyList()
            ui.setBusy(false)
            ui.showPending(items)
            ui.runUpstreamSave()
        } else {
            ui.setBusy(false)
            ui.showSaveError()
        }
    }
}
