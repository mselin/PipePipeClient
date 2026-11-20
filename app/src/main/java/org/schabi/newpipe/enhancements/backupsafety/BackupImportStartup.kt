package org.schabi.newpipe.enhancements.backupsafety

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.settings.ContentSettingsManager
import org.schabi.newpipe.settings.NewPipeFileLocator
import java.io.File
import java.io.FileInputStream

/**
 * The start-up half of an import: exchanges the database (and the settings) for the staged
 * backup, or undoes a half-done exchange. Runs in the main process before anything opens the
 * database or reads the settings.
 */
object BackupImportStartup {
    private const val TAG = "BackupImportStartup"

    @JvmStatic
    fun run(context: Context) {
        try {
            val db = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val dir = db.parentFile
            if (dir == null || !dir.isDirectory) {
                return // no database was ever created, so no import can be pending
            }
            val outcome = ImportJournal(dir, db.name).recover(
                verifyOpen = { verifyOpen(context, db) },
                applySettings = { file, legacy -> applySettings(context, file, legacy) }
            )
            if (outcome == ImportJournal.Outcome.ROLLED_BACK) {
                Toast.makeText(context, R.string.enh_backup_safety_rolled_back, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            // the journal is left as it is; the next start goes on from its marker
            Log.e(TAG, "Recovering the import journal failed", e)
        }
    }

    /**
     * Step c: opens the new database through Room, which runs the migrations and checks the
     * schema, and closes it again. The app opens it anew when it needs it.
     *
     * Room's open helper deletes a database file that SQLite reports as corrupt, and the next
     * open creates an empty database that would pass. The file is therefore held open while
     * Room works: if it was unlinked or exchanged, the open did not succeed. Room is closed
     * before the held descriptor is, because closing any descriptor of a file drops the
     * process's locks on it.
     */
    private fun verifyOpen(context: Context, db: File): Boolean {
        NewPipeDatabase.close() // something may have opened the previous database before this ran
        return try {
            FileInputStream(db).use { held ->
                try {
                    val opened = NewPipeDatabase.getInstance(context).openHelper.writableDatabase
                    val mine = Os.fstat(held.fd)
                    val live = Os.stat(db.path)
                    opened.isOpen && mine.st_nlink > 0 && mine.st_ino == live.st_ino && mine.st_dev == live.st_dev
                } finally {
                    NewPipeDatabase.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "The imported database cannot be opened", e)
            false
        }
    }

    /**
     * Step d. On any failure the previous settings are put back, in memory and, if the storage
     * takes it, on disk.
     */
    private fun applySettings(context: Context, file: File, legacy: Boolean): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = HashMap(preferences.all)
        return try {
            val home = ContextCompat.getDataDir(context) ?: context.filesDir.parentFile!!
            ContentSettingsManager(NewPipeFileLocator(home)).applyStagedSettings(preferences, file, legacy)
            keepImportPath(context, preferences, previous)
            true
        } catch (e: Exception) {
            Log.e(TAG, "The imported settings cannot be applied", e)
            putBack(preferences, previous)
            false
        }
    }

    /** Upstream saved the path of the imported file after the settings were replaced; it was saved before the restart here. */
    private fun keepImportPath(context: Context, preferences: SharedPreferences, previous: Map<String, *>) {
        val key = context.getString(R.string.import_export_data_path)
        val path = previous[key] as? String ?: return
        preferences.edit().putString(key, path).apply()
    }

    private fun putBack(preferences: SharedPreferences, previous: Map<String, *>) {
        try {
            val editor = preferences.edit().clear()
            for ((key, value) in previous) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            if (!editor.commit()) {
                Log.e(TAG, "The previous settings are back in memory but could not be written")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Putting the previous settings back failed", e)
        }
    }
}
