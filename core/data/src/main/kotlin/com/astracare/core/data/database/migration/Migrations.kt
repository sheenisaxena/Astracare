package com.astracare.core.data.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.astracare.core.data.database.entity.SessionEntity
import com.astracare.core.data.database.entity.SyncStateEntity

/**
 * Schema migrations for `AstraCareDatabase`.
 *
 * ## Why these are hand-written rather than auto-migrations
 *
 * Room's `@AutoMigration` would generate [MIGRATION_1_2] correctly — adding a table is the
 * case it handles best. It was written out anyway, on the argument that the point of this file
 * would be the *next* migration, which auto-migration could not infer. [MIGRATION_2_3] is that
 * migration, and it bears the argument out: its `CREATE TABLE` is trivially generatable and the
 * decision that matters is what to put in the new table, which no schema diff can reason about.
 *
 * A codebase where the first migration is generated and the second is hand-written has two
 * mechanisms and no single place to look; one mechanism, established early, has one.
 *
 * ## Which `migrate` overload to override
 *
 * Room 2.7 added `migrate(connection: SQLiteConnection)` for the driver-based API and kept
 * `migrate(db: SupportSQLiteDatabase)` for the framework one. Both are open, and **both throw
 * `NotImplementedError` by default** — so overriding the wrong one produces a migration that
 * compiles, looks complete, and crashes on the first real upgrade.
 *
 * Which one runs depends on how the database was built. `Room.databaseBuilder` with no
 * `setDriver` call — as in `DatabaseModule` — uses the framework driver, and the connection
 * overload's default implementation unwraps it and delegates to this one. So
 * [SupportSQLiteDatabase] is the correct override here, and it would silently become the
 * wrong one if this project ever adopted a `SQLiteDriver` (for example when moving to
 * `androidx.room3` for multiplatform).
 */
val MIGRATION_1_2 = object : Migration(V1, V2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Written to match exactly what Room generates for DraftEntity, because Room
        // validates the resulting schema against the compiled one at open time and aborts
        // with IllegalStateException on any difference. `IF NOT EXISTS` guards the case
        // where a previous upgrade attempt was interrupted after this statement.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `capture_draft` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `age_years` TEXT NOT NULL,
                `village` TEXT NOT NULL,
                `weight_kg` TEXT NOT NULL,
                `height_cm` TEXT NOT NULL,
                `muac_mm` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        // No data migration. A draft is unfinished work belonging to the session that was
        // interrupted; there is nothing in v1 to carry forward, and `beneficiaries` is
        // untouched — which is the property that matters, because those rows may not have
        // reached the server yet.
    }
}

/**
 * Adds `sync_state`, which holds the pull cursor. Day 14.
 *
 * ## The backfill decision, which is the whole reason this is hand-written
 *
 * The table is created with **no row**, so [SyncStateEntity.pullCursor] reads as null and the
 * first pull after upgrade asks the server for everything it holds. That is the expensive
 * option and it is the only correct one. The tempting alternative is to seed a cursor — from
 * `MAX(updated_at)` over `beneficiaries`, say — so an upgrading device does not re-download its
 * own history. It would work on most devices and lose data on the rest: those timestamps come
 * from device clocks, and any server-side change stamped behind a fast handset's clock would
 * fall behind the seeded window and never be delivered. A slow first pull is recoverable; a
 * record the server never sends again is not.
 *
 * This is the case the file's introduction was written for. Room's `@AutoMigration` would have
 * generated the `CREATE TABLE` correctly and had no opinion at all about the backfill, because
 * inferring one is not something a schema diff can do.
 */
val MIGRATION_2_3 = object : Migration(V2, V3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Matches what Room generates for SyncStateEntity exactly; Room validates the result
        // against the compiled schema at open time and aborts on any difference — including
        // a nullable column declared NOT NULL.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_state` (
                `id` INTEGER NOT NULL,
                `pull_cursor` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Adds `audit_log` and `session`, and makes the audit table append-only. Day 17.
 *
 * ## Two tables in one migration, and why not two migrations
 *
 * They arrive together because they are one feature: a role that nothing records is an
 * unaudited role, and an audit trail with no actor is a list of anonymous events. Splitting
 * them into 3→4 and 4→5 would create a version in which half the feature exists, which is a
 * state no device would ever be in and a migration test would have to cover anyway.
 *
 * ## The triggers, and the half of the problem a migration cannot solve
 *
 * [createAuditLogTriggers] is called here for devices that upgrade — and Room creates a fresh
 * schema from the compiled entities WITHOUT running any migration, so a new install would get
 * the table and no triggers. `DatabaseModule` installs a `RoomDatabase.Callback` that calls the
 * same function on create. Room does not validate triggers, so nothing would have complained
 * about the gap; it would simply not have been append-only on most devices.
 *
 * ## No backfill, and this time that is not a decision
 *
 * There is nothing to backfill. An audit trail that begins by inventing entries for events
 * nobody observed would be worse than one that starts empty, and [SessionEntity] is left
 * unwritten so the role resolves to its default rather than being asserted.
 */
val MIGRATION_3_4 = object : Migration(V3, V4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Written to match exactly what Room generates for `AuditEntryEntity`, including the two
        // indices — Room validates the result against the compiled schema at open time and
        // aborts on any difference, and a missing index is a difference.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `audit_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `actor` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `record_id` TEXT,
                `at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_at` ON `audit_log` (`at`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_audit_log_record_id` ON `audit_log` (`record_id`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `session` (
                `id` INTEGER NOT NULL,
                `active_role` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )

        createAuditLogTriggers(db)
    }
}

/**
 * Every migration, in the order Room should consider them.
 *
 * A single list rather than a `vararg` at the call site, so that adding a migration is one
 * edit here and the DI module never changes. Forgetting to register a migration is the
 * classic way a correct migration still causes a crash on upgrade.
 *
 * A `List`, not an `Array`. Room's `addMigrations` takes a vararg, so an array would invite
 * `addMigrations(*ALL_MIGRATIONS)` — which copies the whole array on every call and which
 * detekt's `SpreadOperator` rule rejects. A list is also the better type for a public
 * constant: arrays compare by identity and are mutable in place.
 */
val ALL_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

/**
 * Schema versions, named so a migration's endpoints read as a direction rather than as two
 * bare integers — `Migration(V2, V3)` inside `MIGRATION_2_3` is checkable at a glance, and a
 * transposed pair is the kind of mistake that only shows up on a real upgrade.
 *
 * Deliberately not derived from `DATABASE_VERSION`. A migration's target is a fixed point in
 * history; tying the newest one to whatever the current version happens to be would make it
 * follow the next bump silently, which is precisely the mistake the list above exists to
 * prevent.
 */
private const val V1 = 1
private const val V2 = 2
private const val V3 = 3
private const val V4 = 4
