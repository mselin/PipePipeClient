package org.schabi.newpipe.enhancements.backupsafety

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * SQLite's own check of a staged database file, without Room: Room would migrate the file, and
 * its open helper deletes a file it finds corrupt.
 */
object DatabaseProbe {
    private const val TAG = "DatabaseProbe"

    /** SQLite's default handler deletes the file; the staged file is deleted by its owner. */
    private val keepTheFile = DatabaseErrorHandler { }

    /**
     * True when [file] opens read-only, `PRAGMA quick_check` reports `ok` and Room's
     * `room_master_table` exists. Never throws. The side files SQLite creates next to [file]
     * are removed.
     */
    fun quickCheck(file: File): Boolean =
        try {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, keepTheFile)
            try {
                val intact = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    cursor.moveToFirst() && "ok".equals(cursor.getString(0), ignoreCase = true) && !cursor.moveToNext()
                }
                intact && db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'", null
                ).use { it.moveToFirst() }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "The staged database did not pass the check", e)
            false
        } finally {
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                File(file.path + suffix).delete()
            }
        }
}
