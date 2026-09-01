package com.astracare.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room representation of a beneficiary record.
 *
 * Deliberately separate from the `Beneficiary` domain model, at the cost of a mapper each way.
 * What that buys:
 *
 * - Column names can be renamed, indices added, or the table split, without a single change
 *   outside this module.
 * - The domain model stays free of Room annotations, which is what keeps `:core:model` a
 *   dependency-free Kotlin module.
 * - Room's constraints (no value classes as columns, enums need conversion, nullable columns
 *   for schema evolution) stay here instead of distorting the domain model to suit SQLite.
 *
 * Collapsing the two — annotating the domain model directly — is the single most common way a
 * layered Android codebase stops being layered. It works right up until the first migration.
 */
@Entity(
    tableName = "beneficiaries",
    indices = [
        // The sync engine repeatedly asks "what is not yet synced?". Without this index that
        // is a full table scan on every sync pass, on a low-end handset.
        Index(value = ["sync_status"]),
        // The history list orders by recency.
        Index(value = ["recorded_at"]),
    ],
)
data class BeneficiaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "age_years")
    val ageYears: Int,

    @ColumnInfo(name = "village")
    val village: String,

    /**
     * Flattened into this table rather than given its own, because a measurement has no
     * identity or lifetime of its own — it is captured with the record and never queried
     * independently. A separate table would mean a JOIN on every read for no benefit.
     */
    @Embedded(prefix = "measurement_")
    val measurement: MeasurementEmbedded,

    /**
     * Epoch milliseconds. Stored as INTEGER, which needs no type converter and sorts and
     * compares correctly in SQL — the reason the domain uses epoch millis rather than a
     * date-time type.
     */
    @ColumnInfo(name = "recorded_at")
    val recordedAtEpochMillis: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAtEpochMillis: Long,

    /**
     * Stored as the enum's NAME, not its ordinal.
     *
     * An ordinal silently changes meaning if anyone reorders the enum — every existing row
     * would then decode as a different status, with no error and no migration. That is a data
     * corruption bug that looks like a logic bug. The name costs a few bytes per row.
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String,
)

/**
 * Embedded measurement columns, prefixed `measurement_` in the table.
 *
 * `muacMm` is nullable in both the domain model and the schema: mid-upper arm circumference is
 * only recorded for children under five, so absence is a real state rather than missing data.
 * Storing 0.0 for "not taken" would make it indistinguishable from a genuine reading of zero.
 */
data class MeasurementEmbedded(
    @ColumnInfo(name = "weight_kg")
    val weightKg: Double,

    @ColumnInfo(name = "height_cm")
    val heightCm: Double,

    @ColumnInfo(name = "muac_mm")
    val muacMm: Double?,
)
