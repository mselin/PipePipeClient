package org.schabi.newpipe.enhancements.downloadfixes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.schabi.newpipe.R
import org.schabi.newpipe.plugin.PluginManager
import us.shandian.giga.get.DownloadMission
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Android side of [PendingStateFiles]: where the old state directory was, the log, and
 * the one message on screen. Nothing here removes a state file; files that cannot be read
 * are moved to `set_aside/` by [PendingStateFiles].
 */
object PendingStateAndroid {
    private const val TAG = "PendingState"

    /** Name of the state directory, in the old (external) place as in the new one. */
    private const val STATE_DIR_NAME = "pending_downloads"

    /** State files that could not be read since the last message. */
    private val unreadable = AtomicInteger()

    /**
     * Removes unfinished writes from [newDir], then moves state that an older version saved in
     * the app's external files directory into it. Runs at every creation of the download
     * manager; when the external storage is not mounted the move is tried again next time.
     * Never throws: a failure here must not keep the pending downloads from being loaded.
     */
    @JvmStatic
    fun prepare(context: Context, newDir: File) {
        try {
            val temporaries = PendingStateFiles.deleteTemporaries(newDir)
            if (temporaries > 0) Log.i(TAG, "removed $temporaries unfinished state file(s) from $newDir")

            // Derived, not requested by name: asking for the sub-directory would create it again.
            val external = context.getExternalFilesDir(null)
            if (external == null) {
                Log.i(TAG, "external storage is not available, old pending state (if any) is moved at a later start")
                return
            }
            val oldDir = File(external, STATE_DIR_NAME)
            val result = PendingStateFiles.migrate(oldDir, newDir)
            if (result.moved + result.duplicatesRemoved + result.setAside + result.failed > 0) {
                Log.i(TAG, "pending state moved from $oldDir to $newDir: $result")
            }
            if (result.failed > 0) {
                Log.e(TAG, "${result.failed} old state file(s) are still in $oldDir, tried again at the next start")
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not prepare the pending state in $newDir", e)
        }
    }

    /**
     * The mission saved in [file], or null when the file cannot be read or holds something
     * else. Such a file is logged with its new place and the cause, and counted for
     * [reportSetAside]; it is kept, never removed.
     */
    @JvmStatic
    fun readMission(file: File): DownloadMission? {
        // A lambda, not a function reference: the plugin manager is only touched when a class
        // is unknown to the app itself, as in upstream's reader.
        return when (val result = PendingStateFiles.read(file) { name -> PluginManager.findPluginClass(name) }) {
            is PendingStateFiles.ReadResult.Ok -> {
                val value = result.value
                if (value is DownloadMission) {
                    value
                } else {
                    val keptAt = PendingStateFiles.setAside(file)
                    Log.e(TAG, "Not a download: $file holds a ${value.javaClass.name}; " + whereItIs(keptAt))
                    unreadable.incrementAndGet()
                    null
                }
            }
            is PendingStateFiles.ReadResult.Unreadable -> {
                Log.e(TAG, "Cannot read the saved state of a download: $file; " + whereItIs(result.keptAt), result.cause)
                unreadable.incrementAndGet()
                null
            }
        }
    }

    private fun whereItIs(keptAt: File?): String =
        if (keptAt != null) "kept at $keptAt" else "it could not be moved aside and stays where it is"

    /** If files were counted by [readMission]: one Toast on the main thread, then the count starts again. */
    @JvmStatic
    fun reportSetAside(context: Context) {
        val count = unreadable.getAndSet(0)
        if (count <= 0) return
        val appContext = context.applicationContext ?: context
        Handler(Looper.getMainLooper()).post {
            try {
                val text = appContext.resources.getQuantityString(R.plurals.enh_download_state_set_aside, count, count)
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "could not show that $count download state file(s) were kept aside", e)
            }
        }
    }
}
