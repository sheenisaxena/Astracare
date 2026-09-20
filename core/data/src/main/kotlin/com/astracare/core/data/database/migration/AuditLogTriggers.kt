package com.astracare.core.data.database.migration

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Makes `audit_log` append-only in the database itself.
 *
 * ## Why the Kotlin-side restrictions are not enough
 *
 * `AuditRepository` has no update method and `AuditDao` has no update method, which stops
 * every current caller and documents the intent clearly. Neither survives someone adding one,
 * and neither applies to code that does not go through them — a raw query, a future DAO, a
 * debugging session with a database inspector. "Append-only by convention" is a property of
 * the people who happen to be working on the code this month.
 *
 * These two triggers make it a property of the file. `RAISE(ABORT, ...)` rolls back the
 * statement and surfaces as a `SQLiteConstraintException`, so an attempt to rewrite history
 * fails loudly at the moment it is made rather than succeeding quietly.
 *
 * ## The trap: Room creates a fresh schema WITHOUT running migrations
 *
 * Room's schema comes from the compiled `@Entity` classes, and `@Entity` cannot declare a
 * trigger. So on a device that upgrades, [MIGRATION_3_4] creates the triggers — and on a
 * **fresh install** no migration runs at all, the table is created from the entity, and the
 * triggers would simply not exist. The append-only guarantee would hold for upgraders and
 * silently not hold for every new install, which is the worse half of the population and the
 * harder one to notice.
 *
 * Both paths therefore call this same function: the migration for upgrades, and a
 * `RoomDatabase.Callback.onCreate` in `DatabaseModule` for fresh databases. Room does not
 * validate triggers as part of its schema check, so nothing else would have caught the gap.
 *
 * `IF NOT EXISTS` so that calling it twice — which an interrupted upgrade can do — is safe.
 */
internal fun createAuditLogTriggers(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS audit_log_is_append_only_update
        BEFORE UPDATE ON audit_log
        BEGIN
            SELECT RAISE(ABORT, 'audit_log is append-only: rows cannot be updated');
        END
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS audit_log_is_append_only_delete
        BEFORE DELETE ON audit_log
        BEGIN
            SELECT RAISE(ABORT, 'audit_log is append-only: rows cannot be deleted');
        END
        """.trimIndent(),
    )
}
