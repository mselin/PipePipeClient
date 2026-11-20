package org.schabi.newpipe.enhancements.backupsafety

import com.grack.nanojson.JsonParser
import org.schabi.newpipe.settings.PreferencesObjectInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

sealed class StageResult {
    data class Staged(val hasSettings: Boolean) : StageResult()
    data class Refused(val reason: Reason, val version: Int? = null) : StageResult()
    enum class Reason { NOT_A_BACKUP, DAMAGED, NOT_A_DATABASE, VERSION_UNSUPPORTED, SETTINGS_UNREADABLE }
}

/**
 * What the running app does with a chosen backup: copy it next to the live files and check the
 * copy. It writes only the journal's staged files and decides nothing; the decision is
 * [ImportJournal.commit].
 *
 * @param probe SQLite's own check of the staged database file (`DatabaseProbe.quickCheck`)
 */
class BackupStaging(private val journal: ImportJournal, private val probe: (File) -> Boolean) {
    private companion object {
        const val DB = "newpipe.db"
        const val JSON = "preferences.json"
        const val LEGACY = "newpipe.settings"
    }

    /** steps 1 to 4 of plan section 2; on refusal nothing staged remains */
    fun stage(openZip: () -> InputStream): StageResult {
        if (journal.phase() != ImportJournal.Phase.NONE) {
            // The staged files belong to an import that is already decided and waits for the
            // restart; they must not be overwritten. Callers check the phase before.
            return StageResult.Refused(StageResult.Reason.DAMAGED)
        }
        journal.discardStaging()
        val result = try {
            check(openZip)
        } catch (e: Exception) {
            StageResult.Refused(StageResult.Reason.DAMAGED)
        }
        if (result is StageResult.Refused) {
            journal.discardStaging()
        }
        return result
    }

    private fun check(openZip: () -> InputStream): StageResult {
        val targets = mapOf(
            DB to journal.stagedDb,
            JSON to journal.stagedSettingsJson,
            LEGACY to journal.stagedSettingsLegacy
        )
        // any error here, a full disk included, is reported as DAMAGED by stage()
        val found = openZip().use { ZipStaging.extract(it, targets) }
        if (DB !in found) {
            return StageResult.Refused(StageResult.Reason.NOT_A_BACKUP)
        }

        val header = SqliteHeader.read(journal.stagedDb)
            ?: return StageResult.Refused(StageResult.Reason.NOT_A_DATABASE)
        if (!SchemaVersions.canOpen(header.userVersion)) {
            return StageResult.Refused(StageResult.Reason.VERSION_UNSUPPORTED, header.userVersion)
        }
        val probed = try {
            probe(journal.stagedDb)
        } catch (e: Exception) {
            false
        }
        if (!probed) {
            return StageResult.Refused(StageResult.Reason.NOT_A_DATABASE)
        }

        // JSON settings are preferred, as in upstream's import; only the entry that will be used is judged
        if (JSON in found) {
            journal.stagedSettingsLegacy.delete()
            return if (isJsonObject(journal.stagedSettingsJson)) {
                StageResult.Staged(hasSettings = true)
            } else {
                StageResult.Refused(StageResult.Reason.SETTINGS_UNREADABLE)
            }
        }
        if (LEGACY in found) {
            return if (isLegacyMap(journal.stagedSettingsLegacy)) {
                StageResult.Staged(hasSettings = true)
            } else {
                StageResult.Refused(StageResult.Reason.SETTINGS_UNREADABLE)
            }
        }
        return StageResult.Staged(hasSettings = false)
    }

    private fun isJsonObject(file: File): Boolean =
        try {
            FileInputStream(file).use { JsonParser.`object`().from(it) }
            true
        } catch (e: Exception) {
            false
        }

    /** The same reader and the same cast as `ContentSettingsManager.applyLegacy`. */
    private fun isLegacyMap(file: File): Boolean =
        try {
            val entries = FileInputStream(file).use { PreferencesObjectInputStream(it).readObject() }
            entries is Map<*, *> && entries.keys.all { it is String }
        } catch (e: Exception) {
            false
        }

    /** "Cancel" on the settings question: a database-only import. */
    fun dropSettings() {
        journal.stagedSettingsJson.delete()
        journal.stagedSettingsLegacy.delete()
    }
}
