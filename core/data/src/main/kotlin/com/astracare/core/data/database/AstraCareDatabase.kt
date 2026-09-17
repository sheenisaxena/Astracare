package com.astracare.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.astracare.core.data.database.dao.BeneficiaryDao
import com.astracare.core.data.database.dao.DraftDao
import com.astracare.core.data.database.entity.BeneficiaryEntity
import com.astracare.core.data.database.entity.DraftEntity

/**
 * The app's local database — and, for offline-first purposes, the source of truth.
 *
 * `exportSchema = true` writes a JSON description of the schema to `core/data/schemas/` on
 * every build. Those files are committed deliberately: they are what makes it possible to
 * write a migration test that asserts a real v1 database opens correctly at v2. Without the
 * exported schema there is nothing to migrate *from* in a test, and migrations get verified by
 * shipping them — which on this app would mean losing field data that was never synced.
 *
 * ## Two tables, two very different jobs
 *
 * [BeneficiaryEntity] holds committed records that the sync engine will push. [DraftEntity]
 * holds one unfinished capture that must never be pushed anywhere. They share a database
 * because they share a lifetime and a backup policy — not because they are the same kind of
 * thing. Nothing joins them, and the drafts table is deliberately invisible to
 * `BeneficiaryDao`.
 */
@Database(
    entities = [BeneficiaryEntity::class, DraftEntity::class],
    version = DATABASE_VERSION,
    exportSchema = true,
)
abstract class AstraCareDatabase : RoomDatabase() {
    abstract fun beneficiaryDao(): BeneficiaryDao
    abstract fun draftDao(): DraftDao
}

/**
 * Named constant rather than a literal in the annotation: it is referenced by migration tests,
 * and a bare `version = 2` gives nothing to point at.
 *
 * Bumped to 2 on Day 11, adding `capture_draft`. Every bump needs a matching entry in
 * `ALL_MIGRATIONS` — there is no `fallbackToDestructiveMigration` to catch a miss, which is
 * the intent: the app would rather fail loudly on a developer's device than silently delete a
 * health worker's unsynced records.
 */
const val DATABASE_VERSION = 2

internal const val DATABASE_NAME = "astracare.db"
