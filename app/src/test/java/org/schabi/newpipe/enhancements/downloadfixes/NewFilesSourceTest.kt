package org.schabi.newpipe.enhancements.downloadfixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The new files of the plan, read as text. `PendingStateAndroid` is glue around `Context`,
 * `Log` and `Toast` and cannot run on the JVM; these tests pin the few things in it that an
 * acceptance criterion stands on and that a refactoring could drop without any other test
 * failing. What it does on a device is steps 3 and 4 of the plan's device script.
 *
 * Until the Code layer writes the files, every test here fails with "does not exist".
 */
class NewFilesSourceTest {

    private val dir = "java/org/schabi/newpipe/enhancements/downloadfixes"
    private val glue = "PendingStateAndroid.kt"

    private fun files(ac: String): List<File> {
        val directory = MainFiles.file(dir)
        if (!directory.isDirectory) fail("$ac: app/src/main/$dir does not exist")
        return directory.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
    }

    private fun source(ac: String, name: String): String = MainFiles.lines(ac, "$dir/$name").joinToString("\n")

    /** The text of all plain files together: the plan names three files and leaves the others' names open. */
    private fun plainSource(ac: String): String {
        source(ac, "PendingStateFiles.kt") // "does not exist" until Code wrote the files
        return files(ac).filter { it.name != glue }.joinToString("\n") { it.readText() }
    }

    private fun assertMentions(ac: String, name: String, snippet: String, why: String) {
        assertTrue("$ac: app/src/main/$dir/$name must contain `$snippet`: $why", MainFiles.count(source(ac, name), snippet) > 0)
    }

    /**
     * PROCESS.md and plan ("no Android imports"): everything of the package except the one thin
     * Android shell is plain Kotlin, which is why the unit tests of this package can run it.
     */
    @Test
    fun logicFilesHaveNoAndroidImports() {
        source("plan", "ResumeCheck.kt")
        source("plan", "PendingStateFiles.kt")
        for (file in files("plan").filter { it.name != glue }) {
            assertEquals("the new package holds Kotlin files only", "kt", file.extension)
            val text = file.readText()
            val imports = text.lines().filter { it.trim().startsWith("import ") }
            assertEquals(
                "app/src/main/$dir/${file.name} must not import Android classes",
                emptyList<String>(),
                imports.filter { it.contains("import android.") || it.contains("import androidx.") || it.contains("org.schabi.newpipe.R") }
            )
            assertFalse("app/src/main/$dir/${file.name} must not use android.util.Log", text.contains("android.util.Log"))
        }
    }

    /** Plan sections 1 to 4: the three files the plan names exist, and the classes it declares are declared, once each. */
    @Test
    fun thePlannedFilesAndClassesExist() {
        source("plan section 1", "ResumeCheck.kt")
        source("plan section 2", "PendingStateFiles.kt")
        source("plan section 2", glue)
        val plain = plainSource("plan")
        for (declaration in listOf(
            "object ResumeCheck",
            "object PendingStateFiles",
            "object HlsBufferPolicy",
            "class SegmentOverBudgetException(",
            "class SegmentMemoryBudget(",
            "class SegmentCharge ",
            "class SegmentBuffer(",
            "class AllowListObjectInputStream(",
            "object PendingStateAllowList",
        )) {
            assertEquals("the new package declares `$declaration` once", 1, Regex("(?m)^\\s*(?:internal |public )?" + Regex.escape(declaration)).findAll(plain).count())
        }
        assertEquals(1, Regex("(?m)^object PendingStateAndroid\\b").findAll(source("plan section 2", glue)).count())
    }

    /** AC-6: nothing in the new package reads or writes a preference. */
    @Test
    fun ac6_theNewPackageUsesNoPreference() {
        val offenders = files("AC-6").filter { file ->
            val text = file.readText()
            text.contains("SharedPreferences") || text.contains("PreferenceManager") || text.contains("R.string.") || text.contains("getDefaultSharedPreferences")
        }.map { it.name }
        source("AC-6", glue)

        assertEquals("AC-6: no new setting", emptyList<String>(), offenders)
    }

    // ---- PendingStateFiles.kt (AC-2, AC-4) ----

    /**
     * AC-2: the save is "flushes, `FileDescriptor.sync()`, closes, then `File.renameTo(target)`".
     * A test cannot see whether the bytes were synced before the rename (after a process kill
     * the page cache survives; after a power loss it does not), so the call is pinned here.
     */
    @Test
    fun ac2_stateFiles_syncBeforeTheRename() {
        val name = "PendingStateFiles.kt"
        assertMentions("AC-2", name, ".sync()", "plan section 2: FileDescriptor.sync() before the rename")
        assertMentions("AC-2", name, ".renameTo(", "plan section 2: rename(2) replaces atomically")
        assertMentions("AC-2", name, "TEMP_SUFFIX", "the temporary name is the target's with the suffix")
    }

    /**
     * AC-4: every read of pending state in the new code goes through the allow-list. No plain
     * `ObjectInputStream` and no `PluginObjectInputStream` is constructed anywhere in the package.
     */
    @Test
    fun ac4_everyReadGoesThroughTheAllowList() {
        val all = files("AC-4").joinToString("\n") { it.readText() }
        source("AC-4", "PendingStateFiles.kt")
        val filtered = MainFiles.count(all, "AllowListObjectInputStream(")
        // "ObjectInputStream(" is also the end of "AllowListObjectInputStream(", of its declaration
        // and of its call to the superclass constructor ") : ObjectInputStream(input)".
        val superCalls = Regex("""\)\s*:\s*ObjectInputStream\(""").findAll(all).count()

        assertEquals("AC-4: one class extends ObjectInputStream: the allow-list stream", 1, superCalls)
        assertEquals(
            "AC-4: every ObjectInputStream( in the package is the allow-list stream (its uses, its declaration, its super call)",
            filtered + superCalls,
            MainFiles.count(all, "ObjectInputStream(")
        )
        assertEquals("AC-4: the unfiltered plugin stream is not used", 0, MainFiles.count(all, "PluginObjectInputStream"))
        assertMentions("AC-4", "PendingStateFiles.kt", "AllowListObjectInputStream(", "plan section 2: read() uses the filtered stream")
        assertTrue("AC-4: resolveClass is overridden", MainFiles.count(all, "override fun resolveClass(") == 1)
        assertTrue("AC-4: resolveProxyClass is overridden", MainFiles.count(all, "override fun resolveProxyClass(") == 1)
    }

    /** AC-2: `read` survives what a damaged file can cause: the plan names the two errors it catches. */
    @Test
    fun ac2_stateFiles_readCatchesTheTwoErrors() {
        val name = "PendingStateFiles.kt"
        assertMentions("AC-2", name, "OutOfMemoryError", "plan section 2: a huge declared length")
        assertMentions("AC-2", name, "StackOverflowError", "plan section 2: a deeply nested graph")
    }

    // ---- PendingStateAndroid.kt (AC-2, AC-4) ----

    /** Plan section 2: the signatures the hooks in `DownloadManager.java` compile against. */
    @Test
    fun glue_hasThePlannedSignatures() {
        assertMentions("plan section 2", glue, "fun prepare(context: Context, newDir: File)", "called from the constructor of DownloadManager")
        assertMentions("plan section 2", glue, "fun readMission(file: File): DownloadMission?", "called at DownloadManager.java:150")
        assertMentions("plan section 2", glue, "fun reportSetAside(context: Context)", "called after the loop")
        assertEquals("the three are static for Java", 3, MainFiles.count(source("plan section 2", glue), "@JvmStatic"))
    }

    /**
     * AC-2/AC-4: `prepare` removes unfinished writes, then moves old state. The old directory is
     * `File(context.getExternalFilesDir(null), "pending_downloads")`; asking for
     * `getExternalFilesDir("pending_downloads")` would create the directory again at every start.
     */
    @Test
    fun ac4_glue_prepareCleansThenMoves_fromTheOldDirectory() {
        val text = source("AC-4", glue)
        assertMentions("AC-2", glue, "PendingStateFiles.deleteTemporaries(newDir)", "a `.new` file is by construction an unfinished write")
        assertMentions("AC-4", glue, "PendingStateFiles.migrate(", "plan section 4: the move")
        assertMentions("AC-4", glue, "getExternalFilesDir(null)", "plan section 4: the old directory is derived, not requested")
        assertEquals("AC-4: the external directory is asked for in one way only", 1, MainFiles.count(text, "getExternalFilesDir("))
        assertMentions("AC-4", glue, "\"pending_downloads\"", "plan section 4: the old directory's name")
        val squeezed = MainFiles.squeeze(text)
        assertTrue(
            "AC-2/AC-4: temporaries are deleted before the move (a killed copy's `.new` must not be in the way)",
            squeezed.indexOf("PendingStateFiles.deleteTemporaries(") < squeezed.indexOf("PendingStateFiles.migrate(")
        )
        assertEquals("the move is called in one place", 1, MainFiles.count(text, "PendingStateFiles.migrate("))
    }

    /**
     * AC-2/AC-4: `readMission` reads through `PendingStateFiles.read` with the plugins' classes,
     * logs an unreadable file as an error, and never deletes: "kept aside, not deleted silently".
     */
    @Test
    fun ac2_glue_readMissionReadsFiltered_logsAndNeverDeletes() {
        val text = source("AC-2", glue)
        assertMentions("AC-4", glue, "PendingStateFiles.read(", "plan section 2")
        assertEquals("AC-4: one place reads", 1, MainFiles.count(text, "PendingStateFiles.read("))
        assertMentions("AC-4", glue, "findPluginClass", "plan section 2: PluginManager::findPluginClass resolves plugin classes")
        assertMentions("AC-2", glue, "Log.e(", "A2: a log entry for a file that cannot be read")
        assertMentions("AC-2", glue, "DownloadMission", "something readable that is not a mission is handled like an unreadable file")
        for (forbidden in listOf(".delete()", "deleteRecursively", "deleteOnExit", "ObjectInputStream(", "FileInputStream(", "readFromFile(")) {
            assertEquals("AC-2/AC-4: app/src/main/$dir/$glue must not contain `$forbidden`", 0, MainFiles.count(text, forbidden))
        }
    }

    /** AC-2, A2: one on-screen message per start with the number of files, from the plural resource. */
    @Test
    fun ac2_glue_reportsWithTheMessageResource() {
        assertMentions("AC-2", glue, "Toast.makeText(", "A2: one message per app start")
        assertMentions("AC-2", glue, "R.plurals.enh_download_state_set_aside", "plan section 2: the text")
        assertMentions("AC-2", glue, "getQuantityString(", "the number is part of the text")
        assertEquals("AC-2: one message, in one place", 1, MainFiles.count(source("AC-2", glue), "Toast.makeText("))
    }
}
