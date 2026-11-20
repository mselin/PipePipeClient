package org.schabi.newpipe.enhancements.refreshcooldown

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Localization

/**
 * Glue: the two preferences that hold the values, and the global setting's choices.
 *
 * Reading never throws: a preference of another type, or text that is not this feature's
 * format, gives no values (every group and subscription on "Use default"). Reading is safe on
 * any thread; writing happens only from the two UI places, on the main thread, so a
 * read-modify-write cannot lose an update.
 */
object CooldownStore {
    fun choices(context: Context): CooldownChoices = CooldownChoices.of(
        context.resources.getStringArray(R.array.feed_update_threshold_values),
        context.resources.getStringArray(R.array.feed_update_threshold_options)
    ) { Localization.localizeDuration(context, it) }

    fun subscriptions(context: Context): CooldownValues = read(context, R.string.enh_refresh_cooldown_subscriptions_key)

    fun groups(context: Context): CooldownValues = read(context, R.string.enh_refresh_cooldown_groups_key)

    /** `seconds == null`: "Use default", the entry is removed. */
    fun setSubscription(context: Context, key: String, seconds: Int?) =
        write(context, R.string.enh_refresh_cooldown_subscriptions_key, subscriptions(context).with(key, seconds))

    /** What the group dialog's OK stores: the entry of the old name goes, the one of the new name is written. */
    fun saveGroup(context: Context, groupId: Long, oldName: String, newName: String, seconds: Int?) =
        write(context, R.string.enh_refresh_cooldown_groups_key, CooldownValues.groupSaved(groups(context), groupId, oldName, newName, seconds))

    private fun read(context: Context, keyRes: Int): CooldownValues = runCatching {
        val text = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(keyRes), null)
        CooldownValues.parse(text, choices(context).allowed)
    }.getOrDefault(CooldownValues.EMPTY)

    private fun write(context: Context, keyRes: Int, values: CooldownValues) {
        val key = context.getString(keyRes)
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        if (values.size == 0) editor.remove(key) else editor.putString(key, values.format())
        editor.apply()
    }
}
