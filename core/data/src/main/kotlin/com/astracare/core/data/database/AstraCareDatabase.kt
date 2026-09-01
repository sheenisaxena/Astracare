package com.astracare.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.astracare.core.data.database.dao.BeneficiaryDao
import com.astracare.core.data.database.entity.BeneficiaryEntity

/**
 * The app's local database — and, for offline-first purposes, the source of truth.
 *
 * `exportSchema = true` writes a JSON description of the schema to `core/data/schemas/` on
 * every build. Those files are committed deliberately: they are what makes it possible to
 * write a migration test that asserts a real v1 database opens correctly at v2. Without the
 * exported schema there is nothing to migrate *from* in a test, and migrations get verified by
 * shipping them — which on this app would mean losing field data that was never synced.
 */
@Database(
    entities = [BeneficiaryEntity::class],
    version = DATABASE_VERSION,
    exportSchema = true,
)
abstract class AstraCareDatabase : RoomDatabase() {
    abstract fun beneficiaryDao(): BeneficiaryDao
}

/**
 * Named constant rather than a literal in the annotation: it is referenced by migration tests,
 * and a bare `version = 1` gives nothing to point at.
 */
const val DATABASE_VERSION = 1

internal const val DATABASE_NAME = "astracare.db"
