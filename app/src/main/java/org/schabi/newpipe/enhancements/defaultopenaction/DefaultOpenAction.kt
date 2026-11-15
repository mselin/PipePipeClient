package org.schabi.newpipe.enhancements.defaultopenaction

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import androidx.preference.PreferenceManager
import org.schabi.newpipe.App
import org.schabi.newpipe.R
import org.schabi.newpipe.enhancements.queueguard.GuardChoice
import org.schabi.newpipe.enhancements.queueguard.GuardPolicy
import org.schabi.newpipe.enhancements.queueguard.TapDialogAddOn
import org.schabi.newpipe.enhancements.queueguard.TapDialogExtension

// Android glue of default-open-action (plan 4.4). Queue-guard's `QueueGuard` reads both members of
// [DefaultOpenAction] in its initialiser (hooks G1, G2), so nothing here touches the app, its
// preferences or its resources before a call, and nothing here refers to `QueueGuard`.

/** What queue-guard's glue takes from this feature: the policy wrapper (G1) and the tap dialog's checkbox (G2). */
object DefaultOpenAction {

    /** Queue-guard's policy [base] with the default action and "Start playback in" on top. */
    fun policy(base: GuardPolicy): GuardPolicy = OpenActionPolicy(base, PrefsOpenActionSettings)

    /** A new checkbox, so a new unchecked one, for every tap dialog (AC-7). */
    val tapDialogExtension: TapDialogExtension = TapDialogExtension { context ->
        RememberChoiceAddOn(context, RememberChoice(PrefsOpenActionSettings))
    }
}

/** This feature's two settings, and queue-guard's switch for saving, in the default shared preferences. */
internal object PrefsOpenActionSettings : OpenActionSettings, RememberStore {

    override fun defaultAction(): DefaultTapAction {
        val ctx = App.getApp()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return DefaultTapAction.fromPrefValue(
            prefs.getString(ctx.getString(R.string.enh_default_open_action_key), null),
        )
    }

    override fun startPlaybackIn(): StartPlaybackIn {
        val ctx = App.getApp()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return StartPlaybackIn.fromPrefValue(
            prefs.getString(ctx.getString(R.string.enh_default_open_action_start_in_key), null),
        )
    }

    /** AC-8: the default action, and queue-guard's switch "Ask before replacing the queue". */
    override fun save(defaultAction: DefaultTapAction, askBeforeReplacing: Boolean) {
        val ctx = App.getApp()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.edit()
            .putString(ctx.getString(R.string.enh_default_open_action_key), defaultAction.prefValue)
            .putBoolean(ctx.getString(R.string.enh_queue_guard_ask_key), askBeforeReplacing)
            .apply()
    }
}

/** The checkbox "Remember my choice" in queue-guard's tap dialog. Read at the moment of the choice. */
internal class RememberChoiceAddOn(context: Context, private val remember: RememberChoice) : TapDialogAddOn {

    override val view: View =
        LayoutInflater.from(context).inflate(R.layout.enhancements_default_open_action_remember, null)

    private val checkBox: CheckBox = view.findViewById(R.id.enh_default_open_action_remember)

    override fun onChoice(choice: GuardChoice) {
        remember.onChoice(choice, checkBox.isChecked)
    }
}
