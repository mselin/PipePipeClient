package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "wiring" tests: the lines plan section 5 puts into upstream files, read as text. The new
 * classes protect nothing unless upstream calls them, and a rebase can drop a marked line
 * without any other test noticing. In particular the unsafe import (`importDatabase`,
 * `extractDb`, `ZipHelper.extractFileFromZip` onto the live database) stays in upstream as
 * unused code; these tests pin that the dialog no longer leads to it.
 *
 * Per file:
 *
 * 1. **The number of lines containing `ENH:backup-safety`.** Plan section 5 lists every line
 *    ("16 marked lines in 5 files"), so the count is asserted exactly:
 *
 *    | File | Marked lines | Which |
 *    |---|---|---|
 *    | `App.java` | 1 | the start-up call |
 *    | `settings/BackupSettingsFragment.java` | 3 | two export intercepts, the import redirect |
 *    | `settings/ContentSettingsManager.kt` | 9 | 2 imports, property, 2 exports, 2 `keepCurrent`, commit check, `applyStagedSettings` |
 *    | `plugin/MissingPluginHelper.kt` | 2 | import, the Install redirect |
 *    | `AndroidManifest.xml` | 1 | the `<application>` tag's last line |
 *
 * 2. **What the marked lines say.** The marked lines of a file are joined, the marker and all
 *    whitespace are removed, and the plan's calls are looked up in the result.
 * 3. **Where they are**, when the place is what protects the data (before the database is
 *    opened, before the preferences are committed, first statement of the listener).
 * 4. **That the replaced expression is gone**, looked up in the whole file.
 *
 * Until the Code layer adds the lines, every `wiring` test of an edited file fails with "must
 * have N lines marked ENH:backup-safety". The two "not edited" tests pass from the start.
 */
class HookLinesTest {

    private companion object {
        const val MARKER = "ENH:backup-safety"
        const val PACKAGE = "org.schabi.newpipe.enhancements.backupsafety"

        const val APP = "java/org/schabi/newpipe/App.java"
        const val BACKUP_FRAGMENT = "java/org/schabi/newpipe/settings/BackupSettingsFragment.java"
        const val CONTENT_MANAGER = "java/org/schabi/newpipe/settings/ContentSettingsManager.kt"
        const val MISSING_PLUGIN = "java/org/schabi/newpipe/plugin/MissingPluginHelper.kt"
        const val MANIFEST = "AndroidManifest.xml"

        val EDITED = setOf(APP, BACKUP_FRAGMENT, CONTENT_MANAGER, MISSING_PLUGIN, MANIFEST)

        val NOT_EDITED = listOf(
            "java/org/schabi/newpipe/util/ZipHelper.java",
            "java/org/schabi/newpipe/NewPipeDatabase.java",
            "java/org/schabi/newpipe/database/Migrations.java",
            "java/org/schabi/newpipe/settings/ScheduledBackupWorker.kt",
            "java/org/schabi/newpipe/settings/PluginSettingsFragment.kt",
            "res/values/strings.xml",
            "res/values/settings_keys.xml"
        )
    }

    private fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    private fun count(haystack: String, needle: String): Int =
        if (needle.length > haystack.length) 0 else haystack.windowed(needle.length).count { it == needle }

    /** The marked lines of a file. */
    private inner class Marked(val ac: String, val path: String) {
        val all: List<String> = MainFiles.lines(ac, path)
        val lines: List<String> = all.filter { it.contains(MARKER) }

        /** Marked lines joined, without the marker comment and without whitespace. */
        val code: String = squeeze(
            lines.joinToString("") { it.replace("// $MARKER", "").replace("//$MARKER", "") }
        )

        fun expectCount(expected: Int, what: String): Marked {
            assertEquals(
                "$ac: app/src/main/$path must have $expected lines marked $MARKER ($what)",
                expected,
                lines.size
            )
            return this
        }

        /** [snippet] must be found [times] times in the marked lines (whitespace ignored). */
        fun expectCode(snippet: String, times: Int = 1): Marked {
            assertEquals(
                "$ac: the lines marked $MARKER in app/src/main/$path must contain `$snippet` " +
                    "$times time(s); marked lines: ${lines.map { it.trim() }}",
                times,
                count(code, squeeze(snippet))
            )
            return this
        }

        /** Index in the file of the one marked line that contains [snippet] (whitespace ignored). */
        fun lineOf(snippet: String): Int {
            val hits = all.indices.filter { all[it].contains(MARKER) && squeeze(all[it]).contains(squeeze(snippet)) }
            assertEquals("$ac: app/src/main/$path must have one marked line with `$snippet`", 1, hits.size)
            return hits[0]
        }

        /** Index of the first line at or after [from] containing [snippet] (whitespace ignored), or -1. */
        fun next(snippet: String, from: Int = 0): Int =
            (from until all.size).firstOrNull { squeeze(all[it]).contains(squeeze(snippet)) } ?: -1

        /** Index of the last line before [before] containing [snippet] (whitespace ignored), or -1. */
        fun previous(snippet: String, before: Int): Int =
            (before - 1 downTo 0).firstOrNull { squeeze(all[it]).contains(squeeze(snippet)) } ?: -1

        /** Index of the nearest line above [index] that is not blank. */
        fun lineAbove(index: Int): String = (index - 1 downTo 0).map { all[it] }.first { it.isNotBlank() }
    }

    /** [snippet] (whitespace ignored) must occur [times] times in the whole file. */
    private fun assertInFile(ac: String, path: String, snippet: String, times: Int, why: String) {
        assertEquals(
            "$ac: app/src/main/$path must contain `$snippet` $times time(s): $why",
            times,
            count(squeeze(MainFiles.text(ac, path)), squeeze(snippet))
        )
    }

    // ---------------------------------------------------------------- App.java (AC-1)

    /**
     * AC-1: the swap of a committed import runs at process start, in `App.onCreate`, after the
     * phoenix check and before the settings are initialized and anything can open the database.
     */
    @Test
    fun ac1_wiring_appRunsTheImportJournalBeforeAnythingElse() {
        val marked = Marked("AC-1", APP)
            .expectCount(1, "plan section 5: +1 after :107")
            .expectCode("$PACKAGE.BackupImportStartup.run(this);")
        val hook = marked.lineOf("BackupImportStartup.run(this);")
        val onCreate = marked.next("public void onCreate()")
        val phoenix = marked.next("ProcessPhoenix.isPhoenixProcess(this)", onCreate)
        val acra = marked.next("ACRA.isACRASenderServiceProcess()", onCreate)
        val settings = marked.next("NewPipeSettings.initSettings(this);", onCreate)
        assertTrue("App.java: onCreate with its phoenix check and initSettings must exist", onCreate >= 0 && phoenix > onCreate && acra > onCreate && settings > phoenix)
        assertTrue(
            "AC-1: the call must come after the phoenix and ACRA process checks (those processes must not swap files)",
            hook > phoenix && hook > acra
        )
        assertTrue(
            "AC-1: the call must come before NewPipeSettings.initSettings (the settings may be replaced by the import)",
            hook < settings
        )
        for (later in listOf("NewPipeDatabase", "PluginManager.loadInstalledPlugins", "ServiceHelper.initServices", "StateSaver.init")) {
            val first = marked.next(later, onCreate)
            assertTrue("AC-1: the call must come before `$later` in onCreate", first < 0 || hook < first)
        }
    }

    // ---------------------------------------------------------------- BackupSettingsFragment.java (AC-1, AC-3, AC-6)

    /**
     * AC-1, AC-3: OK on "this will override your current data" starts `BackupImportUi.start`,
     * with the fragment's own `finishImport` as what happens after staging; the unsafe
     * `importDatabase` is no longer called.
     */
    @Test
    fun ac1_ac3_wiring_importGoesThroughBackupImportUi() {
        Marked("AC-1", BACKUP_FRAGMENT)
            .expectCount(3, "plan section 5: +2 export intercepts, 1 changed import line")
            .expectCode("BackupImportUi.start(this, file, () -> finishImport(lastImportDataUri)))")
        assertInFile(
            "AC-1", BACKUP_FRAGMENT, "importDatabase(file, lastImportDataUri)", 0,
            "the dialog must not call upstream's in-place import any more"
        )
        assertInFile(
            "AC-1", BACKUP_FRAGMENT, "importDatabase(", 1,
            "only the unused method's declaration may remain"
        )
        assertInFile("AC-1", BACKUP_FRAGMENT, "manager.extractDb(", 1, "only inside the unused importDatabase")
    }

    /**
     * AC-6: "Export database" and "Export settings" each ask `ExportOptionsUi.intercept` as the
     * first statement of their click listener, before a file picker is opened.
     */
    @Test
    fun ac6_wiring_bothExportsAskForTheLoginDataTick() {
        val marked = Marked("AC-6", BACKUP_FRAGMENT)
            .expectCount(3, "plan section 5: +2 export intercepts, 1 changed import line")
            .expectCode("if ($PACKAGE.ExportOptionsUi.intercept(p, manager)) { return true; }", times = 2)
        val hooks = marked.all.indices.filter {
            marked.all[it].contains(MARKER) && marked.all[it].contains("ExportOptionsUi.intercept")
        }
        assertEquals(2, hooks.size)
        assertTrue(
            "AC-6: the first intercept must be the first statement of the \"Export database\" listener",
            squeeze(marked.lineAbove(hooks[0]))
                .contains(squeeze("exportDataPreference.setOnPreferenceClickListener((final Preference p) -> {"))
        )
        assertTrue(
            "AC-6: the second intercept must be the first statement of the \"Export settings\" listener",
            squeeze(marked.lineAbove(hooks[1]))
                .contains(squeeze("requirePreference(R.string.export_settings_only_key).setOnPreferenceClickListener(p -> {"))
        )
    }

    // ---------------------------------------------------------------- ContentSettingsManager.kt (AC-6, AC-7, AC-9, AC-1)

    /** The nine marked lines of `ContentSettingsManager.kt`. */
    private fun contentManager(ac: String) = Marked(ac, CONTENT_MANAGER).expectCount(
        9,
        "plan section 5: +6 (2 imports, property, 2 keepCurrent, applyStagedSettings), 3 changed (:40, :121, :169)"
    )

    /**
     * AC-6, AC-9: both places that wrote all preferences write `LoginData.forExport(..)` now,
     * and `includeLoginData` is `false` unless the export dialog sets it.
     */
    @Test
    fun ac6_ac9_wiring_exportsWriteTheFilteredPreferences() {
        contentManager("AC-6")
            .expectCode("import $PACKAGE.LoginData")
            .expectCode("var includeLoginData = false")
            .expectCode("output.writeObject(LoginData.forExport(preferences.all, includeLoginData))")
            .expectCode("`object`(LoginData.forExport(preferences.all, includeLoginData))")
        assertInFile(
            "AC-6", CONTENT_MANAGER, "preferences.all", 2,
            "the preferences may be read as a whole only inside LoginData.forExport(preferences.all, ..)"
        )
        assertInFile("AC-6", CONTENT_MANAGER, "LoginData.forExport(preferences.all, includeLoginData)", 2, "legacy entry and JSON")
        assertInFile("AC-9", CONTENT_MANAGER, "includeLoginData = true", 0, "the default must stay false")
    }

    /**
     * AC-7: both ways of applying imported settings call `LoginDataPrefs.keepCurrent` after
     * their loop of puts and before the commit.
     */
    @Test
    fun ac7_wiring_importsKeepTheCurrentLogins() {
        val marked = contentManager("AC-7")
            .expectCode("import $PACKAGE.LoginDataPrefs")
            .expectCode("LoginDataPrefs.keepCurrent(preferences, editor, json)")
            .expectCode("LoginDataPrefs.keepCurrent(preferences, editor, entries)")
        for ((imported, function) in listOf("json" to "fun applyJson(", "entries" to "fun applyLegacy(")) {
            val hook = marked.lineOf("LoginDataPrefs.keepCurrent(preferences, editor, $imported)")
            val start = marked.previous(function, hook)
            val clear = marked.previous("editor.clear()", hook)
            val lastPut = marked.previous("editor.put", hook)
            val commit = marked.next("editor.commit()", hook)
            assertTrue("AC-7: keepCurrent(.., $imported) must be inside `$function`", start >= 0)
            assertTrue(
                "AC-7: in `$function`, keepCurrent must come after editor.clear() and after the puts of the backup's values",
                clear > start && lastPut > clear && lastPut < hook
            )
            assertTrue("AC-7: in `$function`, keepCurrent must come before editor.commit()", commit > hook)
            val nextFunction = (hook + 1 until marked.all.size)
                .firstOrNull { Regex("""\bfun\s+\w+\(""").containsMatchIn(marked.all[it]) } ?: -1
            assertTrue("AC-7: the commit must be in the same function", nextFunction < 0 || commit < nextFunction)
        }
    }

    /**
     * AC-1: settings that cannot be written must be noticed, or the import cannot be rolled
     * back: the legacy path throws on a failed commit like the JSON path already does, and
     * `applyStagedSettings` lets the start-up code apply a staged file in either format.
     */
    @Test
    fun ac1_wiring_stagedSettingsAreAppliedWithACheckedCommit() {
        contentManager("AC-1")
            .expectCode("if (!editor.commit()) throw IOException(\"Unable to commit preferences\")")
            .expectCode("fun applyStagedSettings(preferences: SharedPreferences, file: java.io.File, legacy: Boolean)")
            .expectCode("if (legacy) applyLegacy(preferences, it) else applyJson(preferences, it)")
        assertInFile("AC-1", CONTENT_MANAGER, "editor.commit()", 2, "one per format")
        assertInFile("AC-1", CONTENT_MANAGER, "if (!editor.commit())", 2, "both commits must be checked")
    }

    // ---------------------------------------------------------------- MissingPluginHelper.kt (AC-4, AC-5)

    /**
     * AC-4, AC-5: Install on the "missing plugin" dialog opens `PluginInstallConfirm.show`;
     * upstream's `ensureEnabled` and `install` run only from its callback, with the plugins it
     * passes on. Nothing installs the unchecked list directly any more.
     */
    @Test
    fun ac4_ac5_wiring_installGoesThroughTheConfirmation() {
        Marked("AC-4", MISSING_PLUGIN)
            .expectCount(2, "plan section 5: import, :158 changed")
            .expectCode("import $PACKAGE.PluginInstallConfirm")
            .expectCode(
                "PluginInstallConfirm.show(activity, installable.map { it.plugin!! }) " +
                    "{ ok -> ensureEnabled(activity) { install(activity, ok) } }"
            )
        assertInFile(
            "AC-4", MISSING_PLUGIN, "install(activity, installable", 0,
            "the list from the database must not be installed without the confirmation"
        )
        assertInFile("AC-4", MISSING_PLUGIN, "install(activity,", 1, "the only call is the one in the confirmation's callback")
        assertInFile("AC-4", MISSING_PLUGIN, "ensureEnabled(activity)", 1, "the only call is the one in the confirmation's callback")
    }

    // ---------------------------------------------------------------- AndroidManifest.xml (AC-8)

    /**
     * AC-8: the last line of the `<application>` start tag names both rule files; the marker
     * rides in `tools:ignore` because a start tag cannot hold a comment.
     */
    @Test
    fun ac8_wiring_manifestNamesTheBackupRules() {
        val marked = Marked("AC-8", MANIFEST)
            .expectCount(1, "plan section 5: :63 changed")
            .expectCode("android:fullBackupContent=\"@xml/enhancements_backup_rules\"")
            .expectCode("android:dataExtractionRules=\"@xml/enhancements_data_extraction_rules\"")
            .expectCode("tools:ignore=\"AllowBackup,$MARKER\">")
        val hook = marked.lineOf("android:fullBackupContent")
        assertTrue(
            "AC-8: the marked line must belong to the <application> tag",
            marked.previous("<application", hook) > marked.previous("</queries>", hook) &&
                marked.previous("<activity", hook) < marked.previous("<application", hook)
        )
    }

    // ---------------------------------------------------------------- nothing else

    /** AC-9 and plan section 5, "Not edited": these upstream files carry no line of this fix. */
    @Test
    fun wiring_filesThePlanLeavesAloneHaveNoMarkedLine() {
        for (path in NOT_EDITED) {
            Marked("plan section 5", path).expectCount(0, "listed as not edited")
        }
    }

    /**
     * Plan section 5: five upstream files are edited, no others. Every file under
     * `app/src/main` that carries the marker is one of the five, a file of the fix's own
     * package, or one of its own resource files.
     */
    @Test
    fun wiring_onlyTheFiveFilesOfThePlanCarryTheMarker() {
        val main = MainFiles.existing("plan section 5", MANIFEST).parentFile!!
        val own = { path: String ->
            path.startsWith("java/org/schabi/newpipe/enhancements/backupsafety/") ||
                Regex("""res/[^/]+/enhancements_[^/]+\.xml""").matches(path)
        }
        val textFiles = setOf("java", "kt", "xml")
        val marked = main.walkTopDown()
            .filter { it.isFile && it.extension in textFiles }
            .filter { it.readText().contains(MARKER) }
            .map { it.relativeTo(main).path.replace(File.separatorChar, '/') }
            .filterNot(own)
            .toSet()
        assertEquals(
            "upstream files with lines marked $MARKER (before the Code layer: none; afterwards: the plan's five)",
            if (marked.isEmpty()) emptySet() else EDITED,
            marked
        )
    }
}
