package org.schabi.newpipe.enhancements.backupsafety

/**
 * Which database versions the app can open: the current one and every older one that Room
 * finds a chain of registered migrations for. Kept in step with `NewPipeDatabase.java` and
 * `database/AppDatabase.java` by `SchemaVersionsTest`.
 */
object SchemaVersions {
    const val CURRENT: Int = 902

    /** The migrations registered in `NewPipeDatabase.getDatabase`, as (from, to). */
    val MIGRATIONS: List<Pair<Int, Int>> = listOf(
        1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 7 to 6, 8 to 6, 9 to 6,
        6 to 900, 9 to 900, 900 to 901, 901 to 902
    )

    /**
     * Room's upward search (`androidx.room.util.MigrationUtil.findUpMigrationPath`, 2.8.4):
     * from the current version take the registered migration with the highest target that is
     * above the current version and not above [to]; give up when there is none. It does not
     * backtrack.
     *
     * @return the migrations in order, empty when [from] equals [to], null when there is no path
     */
    fun path(from: Int, to: Int = CURRENT, migrations: List<Pair<Int, Int>> = MIGRATIONS): List<Pair<Int, Int>>? {
        if (from > to) {
            return null
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var at = from
        while (at < to) {
            val next = migrations
                .filter { it.first == at && it.second > at && it.second <= to }
                .maxByOrNull { it.second }
                ?: return null
            result += next
            at = next.second
        }
        return result
    }

    fun canOpen(version: Int): Boolean =
        version == CURRENT || (version in 1 until CURRENT && path(version) != null)
}
