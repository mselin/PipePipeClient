package org.schabi.newpipe.enhancements.groupcreateinline

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity

/**
 * Adds "New group" to upstream's "Select groups" dialog (FeedGroupSelectionDialog): a button
 * below the list, the pending rows after upstream's rows, the name prompt, and an OK that first
 * creates the checked pending groups and then runs upstream's own save. Wiring only; the
 * decisions are in [GroupCreateSession], [GroupNameRules] and [GroupWriter].
 */
object GroupCreateInline {

    /**
     * Called from the dialog's `onCreateDialog`, after it has set the list's adapter and the
     * button listeners. [existingGroups] is upstream's own list and is only read; [upstreamSave]
     * is upstream's save of the checkboxes, which also dismisses the dialog.
     */
    @JvmStatic
    fun attach(
        dialog: DialogFragment,
        root: View,
        subscriptionId: Long,
        existingGroups: List<FeedGroupEntity>,
        upstreamSave: Runnable
    ) {
        // Anything missing: change nothing, the dialog stays as upstream built it.
        val recycler = root.findViewById<RecyclerView>(R.id.groups_recycler_view) ?: return
        val upstreamAdapter = recycler.adapter ?: return
        val saveButton = root.findViewById<View>(R.id.save_button) ?: return
        val cancelButton = root.findViewById<View>(R.id.cancel_button) ?: return
        val listParent = recycler.parent as? ViewGroup ?: return
        val context = dialog.requireContext()

        lateinit var session: GroupCreateSession
        val pendingAdapter = PendingGroupsAdapter { id, checked -> session.setChecked(id, checked) }
        recycler.adapter = ConcatAdapter(upstreamAdapter, pendingAdapter)

        // Between the list and the Cancel/OK row. Disabled until upstream has loaded the groups:
        // it notifies its adapter when the load ends, on success and on failure.
        val newGroupButton = LayoutInflater.from(context)
            .inflate(R.layout.enhancements_group_create_inline_new_group, listParent, false)
        newGroupButton.isEnabled = false
        listParent.addView(newGroupButton, listParent.indexOfChild(recycler) + 1)
        upstreamAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = onFirstChange()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onFirstChange()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onFirstChange()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onFirstChange()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                onFirstChange()

            private fun onFirstChange() {
                upstreamAdapter.unregisterAdapterDataObserver(this)
                newGroupButton.isEnabled = true
            }
        })

        val saver = RxGroupSaver(RoomGroupTable(NewPipeDatabase.getInstance(context)))
        session = GroupCreateSession(
            subscriptionId,
            { existingGroups.map { it.name } },
            saver,
            DialogUi(
                dialog, context, recycler, pendingAdapter,
                listOf(saveButton, cancelButton, newGroupButton), upstreamSave
            )
        )

        var prompt: NamePrompt? = null
        newGroupButton.setOnClickListener {
            prompt?.dismiss()
            prompt = NamePrompt(context, session::submitName).also { it.show() }
        }
        saveButton.setOnClickListener { session.confirm() }

        dialog.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                prompt?.dismiss()
                saver.dispose()
            }
        })
    }

    private class DialogUi(
        private val dialog: DialogFragment,
        private val context: Context,
        private val recycler: RecyclerView,
        private val pendingAdapter: PendingGroupsAdapter,
        private val controls: List<View>,
        private val upstreamSave: Runnable
    ) : SelectionUi {

        override fun showPending(pending: List<PendingGroup>) {
            pendingAdapter.submit(pending)
            if (pending.isNotEmpty()) {
                val rowCount = recycler.adapter?.itemCount ?: 0
                recycler.scrollToPosition(rowCount - 1)
            }
        }

        override fun setBusy(busy: Boolean) {
            controls.forEach { it.isEnabled = !busy }
            dialog.isCancelable = !busy
        }

        override fun showSaveError() {
            Toast.makeText(context, R.string.enh_group_create_inline_save_failed, Toast.LENGTH_LONG)
                .show()
        }

        override fun runUpstreamSave() {
            upstreamSave.run()
        }
    }
}
