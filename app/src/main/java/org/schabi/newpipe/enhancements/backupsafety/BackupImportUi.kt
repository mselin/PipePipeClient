package org.schabi.newpipe.enhancements.backupsafety

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.streams.io.SharpInputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import java.io.BufferedInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The running app's half of an import: the chosen backup is copied next to the live files and
 * checked there, off the main thread. No live file and no setting is touched; the exchange
 * happens at the restart ([BackupImportStartup]).
 */
object BackupImportUi {
    private const val TAG = "BackupImportUi"

    /**
     * One staging at a time: a second one would overwrite the files the first is checking. It
     * is not held while a question is shown, because a dialog that goes away with its activity
     * reports nothing; that dialog can then no longer decide anything either.
     */
    private val busy = AtomicBoolean(false)

    /**
     * @param onStaged the fragment's own `finishImport`: saves the path and restarts the app.
     * It runs only after the import was decided with [ImportJournal.commit].
     */
    @JvmStatic
    fun start(fragment: Fragment, file: StoredFileHelper, onStaged: Runnable) {
        val context = fragment.requireContext()
        val db = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val dir = db.parentFile!!
        val journal = ImportJournal(dir, db.name)
        if (journal.phase() != ImportJournal.Phase.NONE) {
            // an import is decided and only waits for the restart; its staged files must stay
            onStaged.run()
            return
        }
        if (!busy.compareAndSet(false, true)) {
            return
        }
        val staging = BackupStaging(journal) { DatabaseProbe.quickCheck(it) }
        val checking = AlertDialog.Builder(context)
            .setMessage(R.string.enh_backup_safety_checking)
            .setCancelable(false)
            .show()

        Single.fromCallable<StageResult> {
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Could not create the databases directory")
            }
            staging.stage(openZip = { BufferedInputStream(SharpInputStream(file.stream)) })
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                busy.set(false)
                dismiss(checking)
                if (!fragment.isAdded) {
                    // nobody is left to ask or to tell; the import is abandoned
                    abandon(journal)
                } else {
                    val shown = fragment.requireContext()
                    when (result) {
                        is StageResult.Refused -> {
                            abandon(journal)
                            showRefused(shown, result)
                        }
                        is StageResult.Staged ->
                            if (result.hasSettings) {
                                askForSettings(shown, journal, staging, onStaged)
                            } else {
                                commit(shown, journal, onStaged)
                            }
                    }
                }
            }, { error ->
                Log.e(TAG, "Staging the backup failed", error)
                busy.set(false)
                dismiss(checking)
                abandon(journal)
                if (fragment.isAdded) {
                    showRefused(fragment.requireContext(), StageResult.Refused(StageResult.Reason.DAMAGED))
                }
            })
    }

    /** Upstream's question. OK imports the settings too, Cancel the database only, Back nothing. */
    private fun askForSettings(context: Context, journal: ImportJournal, staging: BackupStaging, onStaged: Runnable) {
        AlertDialog.Builder(context)
            .setTitle(R.string.import_settings)
            .setPositiveButton(R.string.ok) { _, _ -> commit(context, journal, onStaged) }
            .setNegativeButton(R.string.cancel) { _, _ ->
                staging.dropSettings()
                commit(context, journal, onStaged)
            }
            .setOnCancelListener { abandon(journal) }
            .show()
    }

    /** Step 6: the decision. Only the marker is written; `onStaged` restarts the app. */
    private fun commit(context: Context, journal: ImportJournal, onStaged: Runnable) {
        try {
            journal.commit()
        } catch (e: IOException) {
            Log.e(TAG, "Writing the import marker failed", e)
            abandon(journal)
            showRefused(context, StageResult.Refused(StageResult.Reason.DAMAGED))
            return
        }
        onStaged.run()
    }

    private fun abandon(journal: ImportJournal) {
        journal.discardStaging()
    }

    private fun showRefused(context: Context, refused: StageResult.Refused) {
        val reason = when (refused.reason) {
            StageResult.Reason.NOT_A_BACKUP -> context.getString(R.string.enh_backup_safety_refused_not_a_backup)
            StageResult.Reason.DAMAGED -> context.getString(R.string.enh_backup_safety_refused_damaged)
            StageResult.Reason.NOT_A_DATABASE -> context.getString(R.string.enh_backup_safety_refused_not_a_database)
            StageResult.Reason.VERSION_UNSUPPORTED ->
                context.getString(R.string.enh_backup_safety_refused_version, refused.version ?: 0)
            StageResult.Reason.SETTINGS_UNREADABLE -> context.getString(R.string.enh_backup_safety_refused_settings)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.enh_backup_safety_refused_title)
            .setMessage(reason + "\n\n" + context.getString(R.string.enh_backup_safety_nothing_changed))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun dismiss(dialog: AlertDialog) {
        try {
            dialog.dismiss()
        } catch (e: IllegalArgumentException) {
            // its window is gone with the activity
        }
    }
}
