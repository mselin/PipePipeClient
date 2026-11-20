package org.schabi.newpipe.enhancements.groupdialog

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputLayout
import org.schabi.newpipe.R

/**
 * The name prompt of "New group": one text field, Add and Cancel. Add and the keyboard's done
 * action hand the text to [onSubmit]; its answer closes the prompt or becomes the error text of
 * the field. Every other way out only dismisses.
 */
class NamePrompt(
    private val context: Context,
    private val onSubmit: (String) -> NameCheck
) {
    private var dialog: AlertDialog? = null

    fun show() {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context)
            .inflate(R.layout.enhancements_group_dialog_name_prompt, null)
        val container: TextInputLayout = view.findViewById(R.id.enh_group_dialog_name_container)
        val input: EditText = view.findViewById(R.id.enh_group_dialog_name_input)

        val dialog = builder
            .setTitle(R.string.enh_group_dialog_new_group)
            .setView(view)
            .setPositiveButton(R.string.enh_group_dialog_add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        this.dialog = dialog

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
        dialog.show()

        fun submit() {
            when (onSubmit(input.text.toString())) {
                NameCheck.OK -> dialog.dismiss()
                NameCheck.EMPTY ->
                    container.error = context.getString(R.string.feed_group_dialog_empty_name)
                NameCheck.DUPLICATE ->
                    container.error = context.getString(R.string.enh_group_dialog_name_exists)
            }
        }

        // Set after show(), in place of the builder's listener, so that Add does not close the
        // prompt by itself.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        input.doOnTextChanged { _, _, _, _ -> container.error = null }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
