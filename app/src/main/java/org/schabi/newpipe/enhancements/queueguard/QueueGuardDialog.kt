package org.schabi.newpipe.enhancements.queueguard

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.schabi.newpipe.R

/** Adds a view of its own to the tap dialog (default-open-action: its checkbox). */
fun interface TapDialogExtension {
    fun create(context: Context): TapDialogAddOn
}

/**
 * A view in the tap dialog. [onChoice] is called only for a row click that is then carried out, right
 * before it; never for CANCEL, never for a dropped click.
 */
interface TapDialogAddOn {
    val view: View

    fun onChoice(choice: GuardChoice)
}

/**
 * The queue-guard dialog: a stock AlertDialog built like upstream's long-press menu (`InfoItemDialog`),
 * three rows and a Cancel button. Dismissed without a choice when [owner] is destroyed (rotation), and
 * with [dismissOnStop] also when it is stopped, as the router does with its own chooser.
 *
 * [context] is an activity, or a themed wrapper around one (the router's screen has a transparent theme).
 * [onDismiss] is called once for every dismissal, after the choice was carried out if there was one;
 * `chosen` is `false` for Cancel, Back, a tap outside, a dropped click and a dismissal by [owner].
 */
internal class DialogGuardUi(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val extension: TapDialogExtension?,
    private val dismissOnStop: Boolean = false,
    private val onDismiss: ((chosen: Boolean) -> Unit)? = null,
) : GuardUi {

    override fun askForTap(title: String, queueSize: Int, onChoice: (GuardChoice) -> Unit) {
        show(
            heading = title,
            queueSize = queueSize,
            rows = TAP_ROWS,
            addOn = extension?.create(context),
            onChoice = onChoice,
        )
    }

    override fun askForList(videoCount: Int, queueSize: Int, onChoice: (GuardChoice) -> Unit) {
        show(
            heading = context.resources.getQuantityString(
                R.plurals.enh_queue_guard_list_count,
                videoCount,
                videoCount,
            ),
            queueSize = queueSize,
            rows = LIST_ROWS,
            addOn = null,
            onChoice = onChoice,
        )
    }

    private fun show(
        heading: String,
        queueSize: Int,
        rows: List<Int>,
        addOn: TapDialogAddOn?,
        onChoice: (GuardChoice) -> Unit,
    ) {
        val bannerView = View.inflate(context, R.layout.dialog_title, null)
        bannerView.isSelected = true
        bannerView.findViewById<TextView>(R.id.itemTitleView).text = heading
        val detailsView = bannerView.findViewById<TextView>(R.id.itemAdditionalDetails)
        detailsView.text = context.resources.getQuantityString(
            R.plurals.enh_queue_guard_queue_count,
            queueSize,
            queueSize,
        )
        detailsView.visibility = View.VISIBLE

        val items = rows.map { context.getString(it) }.toTypedArray()
        var chosen = false
        val builder = AlertDialog.Builder(context)
            .setCustomTitle(bannerView)
            .setItems(items) { _, index ->
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@setItems
                }
                val choice = ROW_CHOICES[index]
                chosen = true
                addOn?.onChoice(choice)
                onChoice(choice)
            }
            .setNegativeButton(R.string.cancel, null)
        if (addOn != null) {
            builder.setView(addOn.view)
        }
        val dialog = builder.create()

        // As InfoItemDialog: a rotation destroys the owner, the dialog goes without calling onChoice.
        val lifecycle = owner.lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (dismissOnStop) {
                    dialog.dismiss()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                dialog.dismiss()
                lifecycle.removeObserver(this)
            }
        }
        lifecycle.addObserver(observer)
        // The owner can be the activity: do not keep a dismissed dialog until it is destroyed.
        dialog.setOnDismissListener {
            lifecycle.removeObserver(observer)
            onDismiss?.invoke(chosen)
        }
        dialog.show()
    }

    private companion object {
        /** The rows of both dialogs, in this order. */
        val ROW_CHOICES = listOf(GuardChoice.ADD_TO_QUEUE, GuardChoice.PLAY_NEXT, GuardChoice.PLAY_NOW)

        val TAP_ROWS = listOf(
            R.string.enh_queue_guard_add_to_queue,
            R.string.enh_queue_guard_play_next,
            R.string.enh_queue_guard_play_now,
        )

        val LIST_ROWS = listOf(
            R.string.enh_queue_guard_add_all,
            R.string.enh_queue_guard_play_all_next,
            R.string.enh_queue_guard_replace_queue,
        )
    }
}
