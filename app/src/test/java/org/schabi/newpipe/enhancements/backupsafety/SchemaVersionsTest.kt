package org.schabi.newpipe.enhancements.backupsafety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-2: "its version is one the app can open". [SchemaVersions] (plan section 2, "A version the
 * app can open") repeats Room's search for a migration path, so that a backup the app cannot
 * upgrade is refused before anything is replaced (finding D3: such a backup made the app crash
 * at every start).
 *
 * The drift tests read `NewPipeDatabase.java`, `database/AppDatabase.java` and
 * `database/Migrations.java` as text: when upstream adds a migration or raises the version and
 * `SchemaVersions` is not updated, they fail.
 */
class SchemaVersionsTest {

    private val openable = (1..6).toSet() + setOf(9, 900, 901, 902)

    /** AC-2: the current version is 902. */
    @Test
    fun ac2_currentIs902() {
        assertEquals(902, SchemaVersions.CURRENT)
    }

    /** AC-2: the registered migrations are the twelve of the plan. */
    @Test
    fun ac2_migrationsAreTheTwelveRegisteredOnes() {
        assertEquals(
            setOf(
                1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 7 to 6, 8 to 6, 9 to 6,
                6 to 900, 9 to 900, 900 to 901, 901 to 902
            ),
            SchemaVersions.MIGRATIONS.toSet()
        )
        assertEquals("no duplicates", 12, SchemaVersions.MIGRATIONS.size)
    }

    /**
     * AC-2: for every version from -5 to 1100, `canOpen` is true exactly for 1 to 6, 9, 900,
     * 901 and 902. Refused are 0, 7, 8 (their only migrations go down to 6), 10 to 899 and
     * everything above 902 (a backup of a newer app).
     */
    @Test
    fun ac2_canOpen_exactlyTheVersionsWithAPath() {
        for (version in -5..1100) {
            assertEquals("version $version", version in openable, SchemaVersions.canOpen(version))
        }
        for (version in listOf(Int.MIN_VALUE, -902, 10_000, 1_000_000, Int.MAX_VALUE)) {
            assertFalse("version $version", SchemaVersions.canOpen(version))
        }
    }

    /** AC-2: the path from 1 runs through every version up to 6, then 900, 901, 902. */
    @Test
    fun ac2_path_fromOne() {
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 900, 900 to 901, 901 to 902),
            SchemaVersions.path(1)
        )
    }

    /** AC-2: from 9 the search takes the edge with the highest target (9-900), not 9-6. */
    @Test
    fun ac2_path_fromNineTakesTheHighestTarget() {
        assertEquals(listOf(9 to 900, 900 to 901, 901 to 902), SchemaVersions.path(9))
    }

    /** AC-2: 7 and 8 have only an edge downwards; an upgrade never follows it. */
    @Test
    fun ac2_path_neverGoesDown() {
        assertNull(SchemaVersions.path(7))
        assertNull(SchemaVersions.path(8))
        assertNull(SchemaVersions.path(10))
        assertNull(SchemaVersions.path(0))
    }

    /** AC-2: the path ends at `to`, and no edge beyond `to` is taken. */
    @Test
    fun ac2_path_respectsTheTarget() {
        assertEquals(listOf(5 to 6, 6 to 900), SchemaVersions.path(5, 900))
        assertEquals(listOf(1 to 2, 2 to 3), SchemaVersions.path(1, 3))
        assertEquals(listOf(1 to 2, 2 to 3), SchemaVersions.path(1, 3, listOf(1 to 5, 1 to 2, 2 to 3)))
    }

    /**
     * AC-2: the search does not backtrack, as Room's does not: with 1-3, 1-2 and 2-4 it takes
     * 1-3 (the highest target), finds nothing from 3 and gives up, although 1-2-4 exists. The
     * check must not accept a version that Room would refuse.
     */
    @Test
    fun ac2_path_doesNotBacktrack() {
        assertNull(SchemaVersions.path(1, 4, listOf(1 to 3, 1 to 2, 2 to 4)))
        assertEquals(listOf(1 to 2, 2 to 4), SchemaVersions.path(1, 4, listOf(1 to 2, 2 to 4)))
    }

    /** AC-2: without migrations there is no path. */
    @Test
    fun ac2_path_withoutMigrations() {
        assertNull(SchemaVersions.path(1, 2, emptyList()))
    }

    // ---------------------------------------------------------------- drift against upstream

    private val migrationName = Regex("""MIGRATION_(\d+)_(\d+)""")

    /** AC-2: `MIGRATIONS` equals the arguments of `addMigrations(...)` in `NewPipeDatabase.java`. */
    @Test
    fun ac2_drift_migrationsEqualTheOnesRegisteredWithRoom() {
        val source = MainFiles.text("AC-2", "java/org/schabi/newpipe/NewPipeDatabase.java")
        val call = Regex("""\.addMigrations\(([^)]*)\)""").findAll(source).toList()
        assertEquals("NewPipeDatabase.java must call addMigrations(...) once", 1, call.size)
        val registered = migrationName.findAll(call[0].groupValues[1])
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        assertTrue("no migrations found in addMigrations(...)", registered.isNotEmpty())
        assertEquals(
            "SchemaVersions.MIGRATIONS must equal the migrations of NewPipeDatabase.java",
            registered.toSet(),
            SchemaVersions.MIGRATIONS.toSet()
        )
        assertFalse(
            "NewPipeDatabase.java must not get a destructive fallback unnoticed: the version check assumes none",
            source.contains("fallbackToDestructiveMigration")
        )
    }

    /** AC-2: each `MIGRATION_a_b` in `Migrations.java` really is `new Migration(DB_VER_a, DB_VER_b)`. */
    @Test
    fun ac2_drift_migrationNamesTellTheirVersions() {
        val source = MainFiles.text("AC-2", "java/org/schabi/newpipe/database/Migrations.java")
        val constants = Regex("""DB_VER_(\d+)\s*=\s*(\d+)\s*;""").findAll(source).toList()
        assertTrue(constants.isNotEmpty())
        for (constant in constants) {
            assertEquals("DB_VER_${constant.groupValues[1]}", constant.groupValues[1], constant.groupValues[2])
        }
        val declared = Regex("""MIGRATION_(\d+)_(\d+)\s*=\s*new\s+Migration\(\s*DB_VER_(\d+)\s*,\s*DB_VER_(\d+)\s*\)""")
            .findAll(source).toList()
        assertTrue(declared.isNotEmpty())
        for (migration in declared) {
            val (nameFrom, nameTo, from, to) = migration.destructured
            assertEquals("MIGRATION_${nameFrom}_$nameTo", nameFrom to nameTo, from to to)
        }
        assertTrue(
            "every migration of SchemaVersions must be declared in Migrations.java",
            declared.map { it.groupValues[3].toInt() to it.groupValues[4].toInt() }.toSet()
                .containsAll(SchemaVersions.MIGRATIONS)
        )
    }

    /** AC-2: `CURRENT` equals `version = DB_VER_n` in `AppDatabase.java`. */
    @Test
    fun ac2_drift_currentEqualsTheDatabaseVersion() {
        val source = MainFiles.text("AC-2", "java/org/schabi/newpipe/database/AppDatabase.java")
        val versions = Regex("""version\s*=\s*DB_VER_(\d+)""").findAll(source).toList()
        assertEquals("AppDatabase.java must declare version = DB_VER_n once", 1, versions.size)
        assertEquals(versions[0].groupValues[1].toInt(), SchemaVersions.CURRENT)
    }
}
