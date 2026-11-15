package org.schabi.newpipe.enhancements.groupcreateinline

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R

/**
 * The pending rows of the "Select groups" dialog, shown after upstream's rows and with upstream's
 * row layout.
 */
class PendingGroupsAdapter(
    private val onCheckedChange: (id: Long, checked: Boolean) -> Unit
) : RecyclerView.Adapter<PendingGroupsAdapter.Holder>() {

    private val items = mutableListOf<PendingGroup>()

    fun submit(items: List<PendingGroup>) {
        this.items.clear()
        this.items.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_feed_group_selection, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    // Keeps the row's own state in step with the checkbox, so that a row that is bound again
    // (scrolled away and back) shows what the user chose.
    private fun onRowChecked(id: Long, checked: Boolean) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            items[index] = items[index].copy(checked = checked)
        }
        onCheckedChange(id, checked)
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkBox: CheckBox = itemView.findViewById(R.id.group_checkbox)
        private val groupName: TextView = itemView.findViewById(R.id.group_name)

        fun bind(item: PendingGroup) {
            groupName.text = item.name

            // Set checkbox state without triggering listener
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.checked
            checkBox.setOnCheckedChangeListener { _, isChecked -> onRowChecked(item.id, isChecked) }

            itemView.setOnClickListener { checkBox.toggle() }
        }
    }
}
