package org.schabi.newpipe.enhancements.groupsort

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.R

/**
 * Reads the "Channel group order" setting from the default shared preferences.
 */
object GroupSortPreference {

    /** The stored mode; a missing value or one of another type gives [GroupSortMode.MANUAL]. */
    fun current(context: Context): GroupSortMode {
        val stored = runCatching {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.enh_group_sort_key), null)
        }.getOrNull()
        return GroupSortMode.fromPreferenceValue(stored)
    }

    /**
     * The current mode on subscription, then the mode after every change of the setting
     * (or of all settings at once, which API 30+ reports with a `null` key).
     */
    fun modes(context: Context): Flowable<GroupSortMode> =
        Flowable.create({ source ->
            val emitter = source.serialize()
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val key = context.getString(R.string.enh_group_sort_key)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == null || changedKey == key) {
                    emitter.onNext(current(context))
                }
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            // SharedPreferences holds listeners weakly; this closure keeps a strong reference
            emitter.setCancellable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            emitter.onNext(current(context))
        }, BackpressureStrategy.LATEST)
}
