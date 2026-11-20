package org.schabi.newpipe.enhancements.backupsafety

import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatTextView
import androidx.preference.Preference
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.ContentSettingsManager

/** The question every manual export asks first: with or without login data. */
object ExportOptionsUi {
    /** The preference whose listener is being called again after "Export"; that call passes. */
    private var passing: Preference? = null

    /**
     * First statement of an export preference's click listener.
     *
     * @return true when the dialog was shown and the listener must stop; false when the listener
     * was called again from the dialog's Export button and goes on to the file picker
     */
    @JvmStatic
    fun intercept(preference: Preference, manager: ContentSettingsManager): Boolean {
        if (passing === preference) {
            passing = null
            return false
        }
        passing = null
        val context = preference.context
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val warning = AppCompatTextView(context)
        warning.setText(R.string.enh_backup_safety_include_login_warning)
        val box = AppCompatCheckBox(context)
        box.setText(R.string.enh_backup_safety_include_login)
        val content = LinearLayout(context)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(padding, padding / 2, padding, 0)
        content.addView(box)
        content.addView(warning)
        AlertDialog.Builder(context)
            .setTitle(R.string.enh_backup_safety_export_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.enh_backup_safety_export_button) { _, _ ->
                manager.includeLoginData = box.isChecked
                passing = preference
                try {
                    preference.onPreferenceClickListener?.onPreferenceClick(preference)
                } finally {
                    passing = null
                }
            }
            .show()
        return true
    }
}
