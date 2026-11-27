package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The texts of plan section 6 in `res/values/enhancements_backup_safety.xml`, read as XML.
 *
 * - AC-2: the import "is refused with a message that names the reason": one text per
 *   [StageResult.Reason], the version one with a place for the number, and "Nothing was changed".
 * - AC-6: the tick box "says that anyone with the file can use the accounts".
 * - AC-4, AC-5: the confirmation and the "not https" list of the plugin dialog.
 * - AC-1 (answer A5c): the message after an import that was rolled back at the restart.
 *
 * Until the Code layer writes the file, every test here fails.
 */
class BackupSafetyStringsTest {

    private val path = "res/values/enhancements_backup_safety.xml"

    private fun strings(): Map<String, String> {
        val file = MainFiles.existing("strings", path)
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }
    }

    private fun text(ac: String, name: String): String {
        val value = strings()[name]
        assertTrue("$ac: app/src/main/$path must define the string $name", !value.isNullOrBlank())
        return value!!
    }

    /** AC-2: every reason of a refusal has its own text. */
    @Test
    fun ac2_everyRefusalReasonHasAText() {
        val names = mapOf(
            StageResult.Reason.NOT_A_BACKUP to "enh_backup_safety_refused_not_a_backup",
            StageResult.Reason.DAMAGED to "enh_backup_safety_refused_damaged",
            StageResult.Reason.NOT_A_DATABASE to "enh_backup_safety_refused_not_a_database",
            StageResult.Reason.VERSION_UNSUPPORTED to "enh_backup_safety_refused_version",
            StageResult.Reason.SETTINGS_UNREADABLE to "enh_backup_safety_refused_settings"
        )
        assertEquals("a reason was added or removed; it needs a text", StageResult.Reason.values().toSet(), names.keys)
        val texts = names.values.map { text("AC-2", it) }
        assertEquals("AC-2: the reasons must be told apart: five different texts", 5, texts.toSet().size)
        text("AC-2", "enh_backup_safety_refused_title")
    }

    /** AC-2: the version text has exactly one place for the version number, `%1$d`. */
    @Test
    fun ac2_versionTextNamesTheVersion() {
        val text = text("AC-2", "enh_backup_safety_refused_version")
        assertTrue("AC-2: enh_backup_safety_refused_version must contain %1\$d, is: $text", text.contains("%1\$d"))
        assertEquals("only one format argument", 1, text.count { it == '%' })
        assertEquals(
            "The database in this backup has version 950. This version of the app cannot open it.",
            String.format(text, 950)
        )
    }

    /** AC-2: the damaged text also covers a full disk, which is reported with the same reason. */
    @Test
    fun ac2_damagedTextMentionsStorage() {
        assertTrue(text("AC-2", "enh_backup_safety_refused_damaged").contains("storage"))
    }

    /** AC-1, AC-2: a refusal says that nothing was changed; a rollback says the previous data is back. */
    @Test
    fun ac1_textsSayThatTheOldDataIsIntact() {
        assertEquals("Nothing was changed.", text("AC-1", "enh_backup_safety_nothing_changed"))
        assertTrue(text("AC-1", "enh_backup_safety_rolled_back").contains("previous data was restored"))
        text("AC-1", "enh_backup_safety_checking")
    }

    /** AC-6: the tick box is "Include login data", and its warning says who can use the accounts. */
    @Test
    fun ac6_tickBoxAndWarning() {
        assertEquals("Include login data", text("AC-6", "enh_backup_safety_include_login"))
        val warning = text("AC-6", "enh_backup_safety_include_login_warning")
        assertTrue("AC-6: the warning must say: $warning", warning.contains("Anyone with the file can use your accounts"))
        assertTrue(warning.contains("clear text"))
        text("AC-6", "enh_backup_safety_export_title")
        text("AC-6", "enh_backup_safety_export_button")
    }

    /** AC-4, AC-5: the plugin dialog asks about addresses and names the reason for a refusal. */
    @Test
    fun ac4_ac5_pluginDialogTexts() {
        assertTrue(text("AC-4", "enh_backup_safety_plugin_confirm_title").contains("addresses"))
        text("AC-4", "enh_backup_safety_plugin_confirm_intro")
        assertTrue(text("AC-5", "enh_backup_safety_plugin_refused").contains("not https"))
    }

    /** New strings go in the feature's own file, with the feature's prefix (PROCESS.md). */
    @Test
    fun strings_haveTheFeaturesPrefix() {
        val names = strings().keys
        assertTrue("the plan lists 16 strings", names.size >= 16)
        assertFalse(names.any { !it.startsWith("enh_backup_safety_") })
    }
}
