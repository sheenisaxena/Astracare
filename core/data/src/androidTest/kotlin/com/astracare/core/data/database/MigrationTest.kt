package com.astracare.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astracare.core.data.database.migration.ALL_MIGRATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every migration, run against a real database, with real data in it.
 *
 * Deferred since Day 12 and written on Day 20, which is the last scheduled day that would have
 * come back to it. The reason it matters more than any other test in the project: this app's
 * entire premise is holding records that have not reached a server yet, and `DatabaseModule`
 * deliberately has no `fallbackToDestructiveMigration`. A migration that is wrong does not
 * degrade the app — it fails to open the database on the one upgrade where a health worker's
 * unsent records are inside it.
 *
 * ## What `runMigrationsAndValidate` actually checks
 *
 * More than "the SQL ran". It compares the resulting schema against the compiled one from the
 * exported JSON and fails on any difference — a missing index, a nullable column declared NOT
 * NULL, a column order mismatch. Those are exactly the differences that produce Room's
 * "Migration didn't properly handle" crash at runtime, and they are invisible to a migration
 * that simply executes without error.
 *
 * That validation is why `core/data/schemas/` is committed rather than gitignored, a decision
 * made on Day 9 for a test that did not exist for eleven days.
 *
 * ## Prerequisite: 2.json and 3.json are missing
 *
 * `MigrationTestHelper` builds a database at version N by executing the `createSql` in
 * `schemas/…/N.json`, so a version with no exported schema cannot be created or validated. Only
 * `1.json` was ever committed — every later build overwrote the working copy with the current
 * version and the intermediate ones were never added. Until they exist, every test in this file
 * fails on a missing schema file rather than on anything it asserts.
 *
 * A build only ever exports the *current* version, so they are recovered from history, once:
 *
 * ```
 * git stash
 * git checkout <the commit that introduced schema v2>   # Day 9, capture_draft
 * ./gradlew :core:data:kspDebugKotlin
 * cp core/data/schemas/com.astracare.core.data.database.AstraCareDatabase/2.json /tmp/
 * git checkout <the commit that introduced schema v3>   # Day 14, sync_state
 * ./gradlew :core:data:kspDebugKotlin && cp …/3.json /tmp/
 * git checkout -            # back to HEAD, which exports 4.json on the next build
 * ```
 *
 * then copy `2.json` and `3.json` back into `schemas/` and commit all four together. Doing it
 * from history rather than by hand is not fussiness: the JSON carries an identity hash Room
 * computes from the entity definitions, and a hand-written one is a schema that never existed.
 *
 * ## Why these run unencrypted while the app runs encrypted
 *
 * No SQLCipher factory is passed, so this uses the framework SQLite implementation. That is
 * deliberate isolation rather than an oversight: a migration is SQL, SQLCipher swaps the
 * implementation beneath SQL without changing its semantics, and wiring the Keystore into these
 * tests would mean a failed key unwrap and a broken migration produce the same red test.
 *
 * The cost is stated: this does not prove an *encrypted* v3 database upgrades to v4. That path
 * is exercised by `EncryptedDatabaseTest` opening the real app database, and by every manual
 * run. A combined test is worth writing the first time an upgrade actually misbehaves.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AstraCareDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsBeneficiariesAndAddsTheDraftTable() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insertBeneficiary(id = "a1", name = "Asha Devi")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, migration(1, 2))

        // The property that matters on every migration in this file: the row is still there.
        // `capture_draft` arriving is the easy half — Room's validation would have caught a
        // malformed CREATE TABLE. A dropped beneficiary would not fail validation at all.
        assertEquals("Asha Devi", migrated.nameOf("a1"))
        assertTrue(migrated.hasTable("capture_draft"))
    }

    @Test
    fun migrate2To3_addsSyncStateWithNoRow() {
        helper.createDatabase(TEST_DB, 2).use { db -> db.insertBeneficiary(id = "a1") }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, migration(2, 3))

        assertTrue(migrated.hasTable("sync_state"))
        // Empty on purpose. DECISION_LOG 9.5: seeding a cursor from local timestamps would let
        // an upgrading device skip every server change stamped behind its own clock. A slow
        // first pull is recoverable; a record the server never resends is not.
        assertEquals(0, migrated.countOf("sync_state"))
        assertEquals("a1", migrated.idOf("a1"))
    }

    @Test
    fun migrate3To4_addsTheAuditAndSessionTables() {
        helper.createDatabase(TEST_DB, 3).use { db -> db.insertBeneficiary(id = "a1") }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, migration(3, 4))

        assertTrue(migrated.hasTable("audit_log"))
        assertTrue(migrated.hasTable("session"))
        // Nothing to backfill: an audit trail that begins by inventing entries for events
        // nobody observed is worse than one that starts empty.
        assertEquals(0, migrated.countOf("audit_log"))
        assertEquals(0, migrated.countOf("session"))
        assertEquals("a1", migrated.idOf("a1"))
    }

    @Test
    fun migrate3To4_makesTheAuditLogAppendOnly() {
        helper.createDatabase(TEST_DB, 3).use { db -> db.insertBeneficiary(id = "a1") }
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, migration(3, 4))

        migrated.execSQL(
            "INSERT INTO audit_log (actor, action, record_id, at) VALUES ('FIELD_WORKER', 'RECORD_CREATED', 'a1', 1)",
        )

        // Room does not validate triggers, so `runMigrationsAndValidate` passing says nothing
        // about them. Asserted directly: the append-only guarantee is a property of the file,
        // not of the Kotlin interfaces that happen to lack an update method.
        assertTrue(migrated.rejects("UPDATE audit_log SET action = 'ROLE_CHANGED' WHERE id = 1"))
        assertTrue(migrated.rejects("DELETE FROM audit_log WHERE id = 1"))
        assertEquals(1, migrated.countOf("audit_log"))
    }

    @Test
    fun migrate1To4_carriesAnUnsyncedRecordThroughEveryVersion() {
        // The one that matters most. A handset that has not been updated since v1 still holds
        // records that were never sent, and this is the path they take. Each migration is
        // tested alone above; this asserts they compose, which is a different claim — an
        // intermediate step that drops and recreates a table would pass every test above.
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insertBeneficiary(id = "unsent", name = "Asha Devi", syncStatus = "PENDING")
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, *ALL_MIGRATIONS.toTypedArray())

        assertEquals("Asha Devi", migrated.nameOf("unsent"))
        assertEquals("PENDING", migrated.syncStatusOf("unsent"))
        // Still queued for the server after three schema versions, which is the entire point.
        assertEquals(1, migrated.countOf("beneficiaries"))
    }

    @Test
    fun everyVersionFromOneToTheCurrentIsReachable() {
        // The drift guard. Bumping DATABASE_VERSION without adding a migration compiles, ships,
        // and crashes on the first upgrade — `runMigrationsAndValidate` below is what turns
        // that into a failing test instead.
        assertEquals(
            "a schema version was added without a migration, or one was added without a bump",
            DATABASE_VERSION - 1,
            ALL_MIGRATIONS.size,
        )
    }

    private fun migration(from: Int, to: Int) =
        ALL_MIGRATIONS.single { it.startVersion == from && it.endVersion == to }

    // ---- helpers, kept small so the tests above read as assertions rather than SQL ----------

    private fun SupportSQLiteDatabase.insertBeneficiary(
        id: String,
        name: String = "Asha Devi",
        syncStatus: String = "PENDING",
    ) = execSQL(
        """
        INSERT INTO beneficiaries
            (id, name, age_years, village, measurement_weight_kg, measurement_height_cm,
             measurement_muac_mm, recorded_at, updated_at, sync_status)
        VALUES ('$id', '$name', 3, 'Kotri', 12.4, 91.0, NULL, 0, 0, '$syncStatus')
        """.trimIndent(),
    )

    private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use { it.count == 1 }

    private fun SupportSQLiteDatabase.countOf(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.nameOf(id: String): String? = columnOf(id, "name")

    private fun SupportSQLiteDatabase.idOf(id: String): String? = columnOf(id, "id")

    private fun SupportSQLiteDatabase.syncStatusOf(id: String): String? = columnOf(id, "sync_status")

    private fun SupportSQLiteDatabase.columnOf(id: String, column: String): String? =
        query("SELECT $column FROM beneficiaries WHERE id = '$id'").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** True when the statement is refused. Used for the append-only triggers. */
    private fun SupportSQLiteDatabase.rejects(sql: String): Boolean =
        runCatching { execSQL(sql) }.isFailure

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
