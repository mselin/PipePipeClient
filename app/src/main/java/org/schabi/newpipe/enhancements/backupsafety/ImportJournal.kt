package org.schabi.newpipe.enhancements.backupsafety

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The exchange of the live database (and the settings) for a staged backup, as a journal that
 * a process start can finish or undo from any point.
 *
 * Files, all in [dbDir]:
 *
 * - `newpipe.db.import`, `preferences.json.import`, `newpipe.settings.import`: the staged backup;
 * - `import.commit` / `import.rollback` / `import.restore` / `import.done`: the marker. Its
 *   name is the phase;
 * - `newpipe.db.old`, `-wal.old`, `-shm.old`, `-journal.old`: the previous database.
 *
 * What keeps the previous data safe:
 *
 * 1. A previous file leaves its live name only by a rename to its `.old` name.
 * 2. An `.old` file is deleted in two places only: after the marker is `import.done`, and when
 *    there is no marker at all. [commit] refuses to write a marker while an `.old` file exists,
 *    so an `.old` file next to a marker is always previous data, never a leftover.
 * 3. A live file is deleted only in phase `import.rollback`, which is entered only after the
 *    staged database took the live name, when every live file belongs to the new database.
 * 4. A marker is removed only when its work is complete: `import.restore` only when no `.old`
 *    file is left to rename back.
 * 5. No step relies on an earlier step of the same run: each tests the files it acts on, and
 *    a rename or delete that fails stops the run with the marker unchanged, for the next start.
 */
class ImportJournal(private val dbDir: File, private val dbName: String = "newpipe.db") {
    enum class Phase { NONE, COMMIT, ROLLBACK, RESTORE, DONE }
    enum class Outcome { NOTHING_PENDING, COMPLETED, ROLLED_BACK }
    enum class Step { MOVE_OLD, PROMOTE, VERIFY, APPLY_SETTINGS, MARK, DELETE, RESTORE_OLD }

    val stagedDb: File = File(dbDir, "$dbName.import")
    val stagedSettingsJson: File = File(dbDir, "preferences.json.import")
    val stagedSettingsLegacy: File = File(dbDir, "newpipe.settings.import")

    /** called before every single file operation; tests throw from it to imitate a kill */
    var beforeStep: (Step) -> Unit = {}

    /** The database and its companion files, the database first. */
    private val live: List<File> = listOf("", "-wal", "-shm", "-journal").map { File(dbDir, dbName + it) }

    /** Everything staging can leave behind, including the side files SQLite creates when the staged database is probed. */
    private val staged: List<File> = listOf(stagedDb, stagedSettingsJson, stagedSettingsLegacy) +
        listOf("-wal", "-shm", "-journal").map { File(dbDir, stagedDb.name + it) }

    private fun old(file: File) = File(dbDir, file.name + ".old")

    private fun marker(phase: Phase) = File(dbDir, "import." + phase.name.lowercase())

    /**
     * The latest phase a marker exists for. Two markers exist only for a moment, when a rename
     * of the marker was replaced by "create the next, delete the previous"; the later one counts.
     */
    fun phase(): Phase =
        listOf(Phase.DONE, Phase.RESTORE, Phase.ROLLBACK, Phase.COMMIT).firstOrNull { marker(it).exists() }
            ?: Phase.NONE

    /** Deletes the staged files. No-op unless phase() == NONE: after [commit] they are the import. */
    fun discardStaging() {
        if (phase() == Phase.NONE) {
            staged.forEach { it.delete() }
        }
    }

    /**
     * The decision to import: creates `import.commit`. Touches no live file. Throws, and leaves
     * no marker, when there is no staged database, when an import is already pending or when a
     * leftover `.old` file cannot be removed.
     */
    @Throws(IOException::class)
    fun commit() {
        if (phase() != Phase.NONE) {
            throw IOException("An import is already pending")
        }
        if (!stagedDb.isFile) {
            throw IOException("No staged database")
        }
        // see rule 2 of the class comment; a start without marker has normally removed them already
        for (leftover in live.map { old(it) }) {
            if (leftover.exists() && !leftover.delete()) {
                throw IOException("Unable to remove ${leftover.name}")
            }
        }
        val marker = marker(Phase.COMMIT)
        try {
            FileOutputStream(marker).use { it.fd.sync() }
        } catch (e: IOException) {
            marker.delete()
            throw e
        }
    }

    /**
     * Acts on the marker; to be called at process start, before anything opens the database.
     *
     * @param verifyOpen opens the live database the way the app does; false when it cannot be opened.
     * It must leave the database closed when it returns false.
     * @param applySettings replaces the settings by the staged file; false when that failed and
     * the previous settings are still (or again) in place
     */
    fun recover(verifyOpen: () -> Boolean, applySettings: (file: File, legacy: Boolean) -> Boolean): Outcome =
        when (phase()) {
            Phase.NONE -> {
                // an import that was never decided, or leftovers: nothing here is the user's data
                (staged + live.map { old(it) }).forEach { delete(it) }
                Outcome.NOTHING_PENDING
            }
            Phase.COMMIT -> rollForward(verifyOpen, applySettings)
            Phase.ROLLBACK -> rollback()
            Phase.RESTORE -> restore()
            Phase.DONE -> finish()
        }

    private fun rollForward(verifyOpen: () -> Boolean, applySettings: (File, Boolean) -> Boolean): Outcome {
        // The staged database exists exactly as long as it has not taken the live name: until
        // then every live file is previous data, afterwards every live file is new.
        if (stagedDb.exists()) {
            if (!moveOldAside()) {
                // previous files are partly live, partly .old, and nothing new is live
                return if (mark(Phase.COMMIT, Phase.RESTORE)) restore() else Outcome.ROLLED_BACK
            }
            beforeStep(Step.PROMOTE)
            if (live.any { it.exists() } || !stagedDb.renameTo(live[0])) {
                return if (mark(Phase.COMMIT, Phase.RESTORE)) restore() else Outcome.ROLLED_BACK
            }
        }

        var ok = live[0].exists()
        if (ok) {
            beforeStep(Step.VERIFY)
            ok = try {
                verifyOpen()
            } catch (e: Exception) {
                false
            }
        }
        if (ok) {
            val legacy = !stagedSettingsJson.exists()
            val settings = if (legacy) stagedSettingsLegacy else stagedSettingsJson
            if (settings.exists()) {
                beforeStep(Step.APPLY_SETTINGS)
                ok = try {
                    applySettings(settings, legacy)
                } catch (e: Exception) {
                    false
                }
            }
        }

        if (ok) {
            // from here on the previous data may be deleted, and only from here on
            return if (mark(Phase.COMMIT, Phase.DONE)) finish() else Outcome.COMPLETED
        }
        return if (mark(Phase.COMMIT, Phase.ROLLBACK)) rollback() else Outcome.ROLLED_BACK
    }

    /** Step a. False when a previous file could not be moved; nothing new may become live then. */
    private fun moveOldAside(): Boolean {
        for (file in live) {
            if (!file.exists()) {
                continue
            }
            if (old(file).exists()) {
                // The previous file was already moved (rule 2), so this one appeared afterwards:
                // something opened a database here between two starts. It is not the user's data.
                delete(file)
            } else {
                beforeStep(Step.MOVE_OLD)
                file.renameTo(old(file))
            }
            if (file.exists()) {
                return false
            }
        }
        return true
    }

    /** Phase DONE: the new database is verified and the settings are applied; only deletions are left. */
    private fun finish(): Outcome {
        (live.map { old(it) } + staged).forEach { delete(it) }
        delete(marker(Phase.COMMIT))
        delete(marker(Phase.DONE))
        return Outcome.COMPLETED
    }

    /** Phase ROLLBACK: every live file belongs to the new database. */
    private fun rollback(): Outcome {
        live.forEach { delete(it) }
        if (live.any { it.exists() && !old(it).exists() }) {
            // A new file without a previous counterpart cannot be removed. Renaming the previous
            // database back next to it could let SQLite replay a foreign WAL; try at the next start.
            return Outcome.ROLLED_BACK
        }
        return if (mark(Phase.ROLLBACK, Phase.RESTORE)) restore() else Outcome.ROLLED_BACK
    }

    /** Phase RESTORE: nothing new is live (or it has a previous counterpart that replaces it). */
    private fun restore(): Outcome {
        for (file in live) {
            if (old(file).exists()) {
                beforeStep(Step.RESTORE_OLD)
                old(file).renameTo(file)
            }
        }
        if (live.any { old(it).exists() }) {
            // previous data is still under an .old name: keep the marker, or the next start would delete it
            return Outcome.ROLLED_BACK
        }
        staged.forEach { delete(it) }
        delete(marker(Phase.COMMIT))
        delete(marker(Phase.ROLLBACK))
        delete(marker(Phase.RESTORE))
        return Outcome.ROLLED_BACK
    }

    private fun delete(file: File) {
        if (file.exists()) {
            beforeStep(Step.DELETE)
            file.delete()
        }
    }

    /**
     * Moves the marker on. True when the marker of [to] exists afterwards; the caller must not
     * go on with the work of [to] otherwise.
     */
    private fun mark(from: Phase, to: Phase): Boolean {
        beforeStep(Step.MARK)
        if (marker(from).renameTo(marker(to))) {
            return true
        }
        return try {
            FileOutputStream(marker(to)).use { it.fd.sync() }
            marker(from).delete()
            true
        } catch (e: IOException) {
            marker(to).exists()
        }
    }
}
