package com.astracare.core.data.database.mapper

import com.astracare.core.data.database.entity.BeneficiaryEntity
import com.astracare.core.data.database.entity.MeasurementEmbedded
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp

/**
 * Translation between the Room entity and the domain model.
 *
 * These functions are the entire cost of keeping the two representations separate, and they
 * are the right place for it: the mapping is explicit, testable, and reviewable in one file.
 * The alternative — one class annotated for both roles — spreads the same coupling invisibly
 * across every layer.
 */
internal fun BeneficiaryEntity.toDomain(): Beneficiary = Beneficiary(
    id = BeneficiaryId(id),
    name = name,
    ageYears = ageYears,
    village = village,
    measurement = Measurement(
        weightKg = measurement.weightKg,
        heightCm = measurement.heightCm,
        muacMm = measurement.muacMm,
    ),
    recordedAt = Timestamp(recordedAtEpochMillis),
    updatedAt = Timestamp(updatedAtEpochMillis),
    syncStatus = syncStatus.toSyncStatus(),
)

internal fun Beneficiary.toEntity(): BeneficiaryEntity = BeneficiaryEntity(
    id = id.value,
    name = name,
    ageYears = ageYears,
    village = village,
    measurement = MeasurementEmbedded(
        weightKg = measurement.weightKg,
        heightCm = measurement.heightCm,
        muacMm = measurement.muacMm,
    ),
    recordedAtEpochMillis = recordedAt.epochMillis,
    updatedAtEpochMillis = updatedAt.epochMillis,
    syncStatus = syncStatus.name,
)

/**
 * Decodes a stored status name.
 *
 * An unrecognised value means the row was written by a newer version of the app — possible
 * after a downgrade, or a partially applied migration. It is mapped to [SyncStatus.PENDING]
 * rather than throwing, because the alternative is a crash loop on launch with the user's
 * unsynced field data trapped inside the database.
 *
 * PENDING is the safe choice specifically because it is conservative: the record gets
 * re-offered to the sync engine. Defaulting to SYNCED would silently discard data.
 */
private fun String.toSyncStatus(): SyncStatus =
    SyncStatus.entries.firstOrNull { it.name == this } ?: SyncStatus.PENDING
