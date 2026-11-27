package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.R

/** Glue: the single-choice dialog both places use. */
object CooldownChooser {
    fun show(context: Context, current: Int?, onChosen: (Int?) -> Unit): AlertDialog {
        val choices = CooldownStore.choices(context)
        return AlertDialog.Builder(context)
            .setTitle(R.string.enh_refresh_cooldown_title)
            .setSingleChoiceItems(
                choices.items(context.getString(R.string.enh_refresh_cooldown_use_default)).toTypedArray(),
                choices.indexOf(current)
            ) { dialog, which ->
                onChosen(choices.valueAt(which))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
