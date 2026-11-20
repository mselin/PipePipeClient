package org.schabi.newpipe.enhancements.backupsafety

import android.content.SharedPreferences

/** The preferences side of [LoginData]'s import rule. */
object LoginDataPrefs {
    /**
     * To be called on the [editor] of an import after `clear()` and the puts of the backup's
     * values, before the commit. When [imported] has no login data, the login entries end up
     * exactly as they are now: present ones are written back with their type, and the ones that
     * are absent now are removed again in case the backup held them (an editor applies `clear()`
     * first, then the last put or remove of each key).
     */
    @JvmStatic
    fun keepCurrent(preferences: SharedPreferences, editor: SharedPreferences.Editor, imported: Map<*, *>) {
        if (LoginData.hasLoginData(imported)) {
            return
        }
        val kept = LoginData.keptOnImport(imported, preferences.all)
        for (key in LoginData.ALL_KEYS) {
            when (val value = kept[key]) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> editor.remove(key)
            }
        }
    }
}
