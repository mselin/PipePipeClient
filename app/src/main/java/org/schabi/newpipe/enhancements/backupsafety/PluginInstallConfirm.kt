package org.schabi.newpipe.enhancements.backupsafety

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.database.plugin.PluginServiceEntity

/**
 * The confirmation before plugins are installed from addresses stored in the database, which an
 * imported backup can have written. Nothing is fetched before the user saw the full addresses.
 */
object PluginInstallConfirm {
    /**
     * Shows every plugin with its full repository address. [proceed] is called with the
     * plugins whose address is allowed, and only after the user chose Install.
     */
    @JvmStatic
    fun show(activity: Activity, plugins: List<PluginServiceEntity>, proceed: (List<PluginServiceEntity>) -> Unit) {
        // plain http is allowed in debug builds only, as for an address typed in the plugin settings
        val allowPlainHttp = BuildConfig.DEBUG
        val split = RepositoryAddress.split(plugins.map { offer(it) }, allowPlainHttp)
        val allowed = plugins.filter { RepositoryAddress.isAllowed(it.repositoryUrl, allowPlainHttp) }

        val message = buildString {
            if (split.allowed.isNotEmpty()) {
                append(activity.getString(R.string.enh_backup_safety_plugin_confirm_intro))
                append("\n\n")
                append(RepositoryAddress.lines(split.allowed).joinToString("\n\n"))
            }
            if (split.refused.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("\n\n")
                }
                append(activity.getString(R.string.enh_backup_safety_plugin_refused))
                append("\n\n")
                // a refused address may hold line breaks or invisible characters: show them as spaces
                append(RepositoryAddress.lines(split.refused.map { it.copy(repositoryUrl = oneLine(it.repositoryUrl)) }).joinToString("\n\n"))
            }
        }
        val builder = AlertDialog.Builder(activity).setMessage(message)
        if (allowed.isEmpty()) {
            builder.setTitle(R.string.missing_plugin_title)
                .setPositiveButton(R.string.ok, null)
        } else {
            builder.setTitle(R.string.enh_backup_safety_plugin_confirm_title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.install) { _, _ -> proceed(allowed) }
        }
        builder.show()
    }

    /** The name comes from the database too; it must not be able to imitate an address line. */
    private fun offer(plugin: PluginServiceEntity) =
        RepositoryAddress.Offer(oneLine(plugin.name), plugin.repositoryUrl)

    private fun oneLine(text: String): String =
        text.map { if (it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
            .joinToString("")
}
