package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AC-8: "Android's own backup and device transfer do not include login cookies, the proxy token
 * or installed plugins." Answer A2: they carry the database only.
 *
 * The two rule files of plan section 4 and the manifest are parsed as XML where they lie in
 * `app/src/main`. Backup rules are an allow-list here: one `<include>` of anything but the
 * database files would bring a preferences file (the cookies), `files/plugins` or the staging
 * files of an import into the backup. What Android does with the rules is device step 11.
 *
 * Until the Code layer writes the files and the manifest line, every test here fails.
 */
class BackupRulesTest {

    private val legacyRules = "res/xml/enhancements_backup_rules.xml"
    private val extractionRules = "res/xml/enhancements_data_extraction_rules.xml"
    private val manifest = "AndroidManifest.xml"
    private val android = "http://schemas.android.com/apk/res/android"

    private val databaseFiles = setOf("newpipe.db", "newpipe.db-wal", "newpipe.db-shm", "newpipe.db-journal")

    private fun root(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(MainFiles.existing("AC-8", path)).documentElement
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** Every child of [section] is `<include domain="database" path="newpipe.db*"/>`; together they are the four files. */
    private fun assertOnlyTheDatabase(file: String, section: Element) {
        val children = section.children()
        for (child in children) {
            assertEquals(
                "AC-8: app/src/main/$file <${section.tagName}> may hold only <include> (an allow-list)",
                "include",
                child.tagName
            )
            assertEquals(
                "AC-8: app/src/main/$file: only the domain \"database\" may be included (a preferences file " +
                    "holds the cookies and the token, \"file\" holds the plugins)",
                "database",
                child.getAttribute("domain")
            )
            assertEquals("AC-8: app/src/main/$file: an <include> has domain and path, nothing else", 2, child.attributes.length)
        }
        assertEquals(
            "AC-8: app/src/main/$file <${section.tagName}> must include exactly the database and its companion " +
                "files (not the staging files newpipe.db.import / .old, not the whole directory)",
            databaseFiles,
            children.map { it.getAttribute("path") }.toSet()
        )
        assertEquals("AC-8: app/src/main/$file: no <include> twice", databaseFiles.size, children.size)
    }

    /** AC-8, Android 6 to 11: `fullBackupContent` rules include only the database files. */
    @Test
    fun ac8_fullBackupContent_includesOnlyTheDatabase() {
        val root = root(legacyRules)
        assertEquals("full-backup-content", root.tagName)
        assertOnlyTheDatabase(legacyRules, root)
    }

    /** AC-8, Android 12+: cloud backup and device transfer each include only the database files. */
    @Test
    fun ac8_dataExtractionRules_includeOnlyTheDatabaseForCloudAndTransfer() {
        val root = root(extractionRules)
        assertEquals("data-extraction-rules", root.tagName)
        val sections = root.children()
        assertEquals(
            "AC-8: app/src/main/$extractionRules must have exactly <cloud-backup> and <device-transfer> " +
                "(a missing section means: everything is transferred)",
            listOf("cloud-backup", "device-transfer"),
            sections.map { it.tagName }.sorted()
        )
        sections.forEach { assertOnlyTheDatabase(extractionRules, it) }
    }

    /** AC-8: the manifest's `<application>` names both rule files; without that they have no effect. */
    @Test
    fun ac8_manifestNamesBothRuleFiles() {
        val application = root(manifest).children().single { it.tagName == "application" }
        assertEquals(
            "AC-8: <application android:fullBackupContent>",
            "@xml/enhancements_backup_rules",
            application.getAttributeNS(android, "fullBackupContent")
        )
        assertEquals(
            "AC-8: <application android:dataExtractionRules>",
            "@xml/enhancements_data_extraction_rules",
            application.getAttributeNS(android, "dataExtractionRules")
        )
        assertEquals(
            "plan section 4: allowBackup stays true (the database is still backed up)",
            "true",
            application.getAttributeNS(android, "allowBackup")
        )
    }

    /** AC-8: both rule files are where `@xml/...` looks for them. */
    @Test
    fun ac8_ruleFilesExist() {
        assertTrue(MainFiles.existing("AC-8", legacyRules).isFile)
        assertTrue(MainFiles.existing("AC-8", extractionRules).isFile)
    }
}
