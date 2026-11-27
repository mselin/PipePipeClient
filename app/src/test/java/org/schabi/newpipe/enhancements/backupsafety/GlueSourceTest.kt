package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new files of plan section 6, read as text. The Android glue cannot run on the JVM; these
 * tests pin only the few things in it that the fix stands on and that a refactoring could drop
 * without any other test failing. What the glue does on a device is the plan's device script.
 *
 * Until the Code layer writes the files, every test here fails with "does not exist".
 */
class GlueSourceTest {

    private val dir = "java/org/schabi/newpipe/enhancements/backupsafety"

    private val plain = listOf(
        "SqliteHeader.kt", "SchemaVersions.kt", "ZipStaging.kt", "ImportJournal.kt",
        "BackupStaging.kt", "LoginData.kt", "RepositoryAddress.kt"
    )

    private fun source(ac: String, name: String): String = MainFiles.text(ac, "$dir/$name")

    private fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    private fun assertMentions(ac: String, name: String, snippet: String, why: String) {
        assertTrue(
            "$ac: app/src/main/$dir/$name must contain `$snippet`: $why",
            squeeze(source(ac, name)).contains(squeeze(snippet))
        )
    }

    /**
     * PROCESS.md: decision logic is plain Kotlin with no Android imports. The seven logic files
     * import nothing from `android.` or `androidx.` (that is also why they can be tested here).
     */
    @Test
    fun logicFilesHaveNoAndroidImports() {
        for (name in plain) {
            val imports = source("plan section 6", name).lines().filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$dir/$name must not import Android classes",
                emptyList<String>(),
                imports.filter { it.contains("import android.") || it.contains("import androidx.") }
            )
        }
    }

    /**
     * AC-1 and the reason for the fix on the owner's device (about 1600 subscriptions, a 10 MB
     * database: the import blocked the main thread until the app stopped responding): the
     * staging runs on `Schedulers.io()` and comes back to the main thread for its dialogs.
     */
    @Test
    fun ac1_importUi_stagesOffTheMainThread() {
        assertMentions("AC-1", "BackupImportUi.kt", "Schedulers.io()", "plan section 2: work on Schedulers.io")
        assertMentions("AC-1", "BackupImportUi.kt", "AndroidSchedulers.mainThread()", "dialogs are shown on the main thread")
        assertMentions("AC-1", "BackupImportUi.kt", ".stage(", "steps 1 to 4 are BackupStaging.stage")
    }

    /**
     * AC-1, AC-2: the import UI decides with `commit()` only, after staging; it never uses
     * upstream's in-place extraction and never applies settings while the app runs.
     */
    @Test
    fun ac1_importUi_commitsTheMarkerAndNeverWritesLiveFiles() {
        val name = "BackupImportUi.kt"
        assertMentions("AC-1", name, ".commit()", "step 6: ImportJournal.commit()")
        assertMentions("AC-3", name, "onStaged.run()", "the fragment's finishImport runs after the commit")
        assertMentions("AC-3", name, "R.string.import_settings", "upstream's settings question is kept")
        assertMentions("AC-3", name, ".dropSettings()", "Cancel on the settings question imports the database only")
        assertMentions("AC-1", name, ".discardStaging()", "Back on the settings question aborts the import (answer A5a)")
        assertMentions("AC-2", name, "DatabaseProbe", "step 3: the staged database is probed")
        val text = squeeze(source("AC-1", name))
        for (unsafe in listOf("extractDb(", "extractFileFromZip(", "loadSharedPreferences(", "extractSettings(", "importDatabase(")) {
            assertFalse("AC-1: app/src/main/$dir/$name must not call upstream's in-place `$unsafe`", text.contains(unsafe))
        }
    }

    /** AC-2: every refusal reason is turned into its message, with "Nothing was changed". */
    @Test
    fun ac2_importUi_namesEveryReason() {
        val name = "BackupImportUi.kt"
        for (string in listOf(
            "enh_backup_safety_refused_not_a_backup", "enh_backup_safety_refused_damaged",
            "enh_backup_safety_refused_not_a_database", "enh_backup_safety_refused_version",
            "enh_backup_safety_refused_settings", "enh_backup_safety_nothing_changed"
        )) {
            assertMentions("AC-2", name, "R.string.$string", "the refusal dialog names the reason")
        }
    }

    /**
     * AC-1: at process start the journal is recovered with Room as the verifier and
     * `applyStagedSettings` as the settings writer, and a rollback is reported.
     */
    @Test
    fun ac1_startup_recoversTheJournal() {
        val name = "BackupImportStartup.kt"
        assertMentions("AC-1", name, ".recover(", "plan section 2: ImportJournal.recover acts on the marker")
        assertMentions("AC-1", name, "NewPipeDatabase.close()", "step c: close before opening the new database")
        assertMentions("AC-1", name, ".openHelper.writableDatabase", "step c: open through Room, which migrates and checks the schema")
        assertMentions("AC-1", name, ".applyStagedSettings(", "step d")
        assertMentions("AC-1", name, "R.string.enh_backup_safety_rolled_back", "answer A5c: the message after a rollback")
        assertMentions("AC-1", name, "ROLLED_BACK", "the message depends on the outcome")
    }

    /**
     * AC-2: the probe opens the staged file read-only with its own error handler (SQLite's
     * default handler deletes a file it finds corrupt), runs `quick_check` and looks for Room's
     * table.
     */
    @Test
    fun ac2_probe_checksWithoutRoomAndWithoutTheDefaultErrorHandler() {
        val name = "DatabaseProbe.kt"
        assertMentions("AC-2", name, "OPEN_READONLY", "the staged file is only read")
        assertMentions("AC-2", name, "DatabaseErrorHandler", "an error handler that does nothing")
        assertMentions("AC-2", name, "quick_check", "PRAGMA quick_check")
        assertMentions("AC-2", name, "room_master_table", "a Room database has this table")
    }

    /** AC-7: the preferences glue asks `LoginData.keptOnImport` and writes into the same editor. */
    @Test
    fun ac7_loginDataPrefs_usesTheRule() {
        assertMentions("AC-7", "LoginDataPrefs.kt", "LoginData.keptOnImport(", "the rule is LoginData's")
        assertMentions("AC-7", "LoginDataPrefs.kt", "@JvmStatic", "plan section 6 signature")
    }

    /** AC-6: the export dialog writes the tick into the manager; the box starts unticked. */
    @Test
    fun ac6_exportOptions_setTheFlagFromTheTick() {
        val name = "ExportOptionsUi.kt"
        assertMentions("AC-6", name, "manager.includeLoginData =", "Export sets the manager's flag to the box")
        assertMentions("AC-6", name, "R.string.enh_backup_safety_include_login", "the tick box")
        assertMentions("AC-6", name, "R.string.enh_backup_safety_include_login_warning", "the warning")
        assertFalse(
            "AC-6: the box is off by default; $name must not tick it",
            squeeze(source("AC-6", name)).contains("isChecked=true")
        )
    }

    /**
     * AC-4, AC-5: the confirmation uses `RepositoryAddress` with the build's debug flag, shows
     * the lines with the full addresses and the refused list.
     */
    @Test
    fun ac4_ac5_pluginConfirm_usesTheAddressRule() {
        val name = "PluginInstallConfirm.kt"
        assertMentions("AC-5", name, "RepositoryAddress.split(", "allowed and refused plugins")
        assertMentions("AC-5", name, "BuildConfig.DEBUG", "plain http only in debug builds, like the plugin settings")
        assertMentions("AC-4", name, "RepositoryAddress.lines(", "name plus full address")
        assertMentions("AC-4", name, "R.string.enh_backup_safety_plugin_confirm_title", "the confirmation")
        assertMentions("AC-5", name, "R.string.enh_backup_safety_plugin_refused", "the list of refused plugins")
        assertMentions("AC-4", name, "proceed(", "only the allowed plugins are passed on")
    }
}
