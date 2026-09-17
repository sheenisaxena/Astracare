package com.astracare.core.data.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for `AstraCareDatabase`.
 *
 * ## Why these are hand-written rather than auto-migrations
 *
 * Room's `@AutoMigration` would generate [MIGRATION_1_2] correctly — adding a table is the
 * case it handles best. It is written out anyway, because the point of this file is the *next*
 * migration, which will rename or backfill a column and which auto-migration cannot infer. A
 * codebase where the first migration is generated and the second is hand-written has two
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
val MIGRATION_1_2 = object : Migration(1, 2) {
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
val ALL_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)
