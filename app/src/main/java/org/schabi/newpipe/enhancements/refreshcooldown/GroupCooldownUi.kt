package org.schabi.newpipe.enhancements.refreshcooldown

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import kotlin.math.roundToInt

/**
 * Glue: the cooldown row of the dialog that edits a group.
 *
 * [attach] adds the row when the group arrives (never for a new group); a choice is kept in
 * the row and stored by [save] only, which the dialog calls when it is confirmed. Cancel and
 * back never reach [save], so they store nothing.
 */
object GroupCooldownUi {
    /** [seconds]: the value the row shows; [storedName]: the group's name the stored value belongs to. */
    private class RowState(var seconds: Int?, val storedName: String)

    fun attach(optionsRoot: ViewGroup, group: FeedGroupEntity?) {
        if (group == null || optionsRoot.findViewById<View>(R.id.enh_refresh_cooldown_group_button) != null) return
        val context = optionsRoot.context
        val row = LayoutInflater.from(context).inflate(R.layout.enhancements_refresh_cooldown_group_row, optionsRoot, false) as Button
        optionsRoot.addView(row)
        // "Select subscriptions" moves below the row, and the options grow by the row's height
        optionsRoot.findViewById<View>(R.id.select_channel_button)?.let { select ->
            (select.layoutParams as? ConstraintLayout.LayoutParams)?.let { params ->
                params.topToBottom = row.id
                select.layoutParams = params
            }
        }
        val rootParams = optionsRoot.layoutParams
        if (rootParams != null && rootParams.height > 0) {
            rootParams.height += (48 * context.resources.displayMetrics.density).roundToInt()
            optionsRoot.layoutParams = rootParams
        }

        val state = RowState(CooldownStore.groups(context)[CooldownValues.groupKey(group.uid, group.name)], group.name)
        row.tag = state
        val choices = CooldownStore.choices(context)
        fun showValue() {
            row.text = context.getString(
                R.string.enh_refresh_cooldown_entry,
                choices.label(state.seconds, context.getString(R.string.enh_refresh_cooldown_use_default))
            )
        }
        showValue()

        var chooser: AlertDialog? = null
        row.setOnClickListener {
            chooser?.dismiss()
            chooser = CooldownChooser.show(context, state.seconds) {
                state.seconds = it
                showValue()
            }
        }
        // the chooser goes with the dialog (rotation, dismissal)
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                chooser?.dismiss()
                chooser = null
            }
        })
    }

    fun save(optionsRoot: ViewGroup, groupId: Long, newName: String) {
        val row = optionsRoot.findViewById<View>(R.id.enh_refresh_cooldown_group_button) ?: return
        val state = row.tag as? RowState ?: return
        CooldownStore.saveGroup(optionsRoot.context, groupId, state.storedName, newName, state.seconds)
    }
}
