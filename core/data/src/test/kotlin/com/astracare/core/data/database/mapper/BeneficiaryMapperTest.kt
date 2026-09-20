package com.astracare.core.data.database.mapper

import com.astracare.core.data.database.entity.BeneficiaryEntity
import com.astracare.core.data.database.entity.MeasurementEmbedded
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The entity-to-domain mapping, and the defensive decode nobody had exercised.
 *
 * `:core:data` had no `src/test` directory at all before Day 18 — every test in it was
 * instrumented, which meant the pure functions in it were covered by nothing that runs in CI.
 * This file is that source set's first inhabitant, and it exists for one branch in particular.
 *
 * ## The branch that matters
 *
 * `toSyncStatus` maps an unrecognised stored value to [SyncStatus.PENDING] rather than
 * throwing, and the mapper's own documentation explains why: the alternative is a crash loop
 * on launch with a health worker's unsynced records trapped inside the database. PENDING
 * specifically, because it is the conservative choice — the record gets re-offered to the sync
 * engine, where defaulting to SYNCED would silently discard it.
 *
 * That is a claim about behaviour under a condition nobody can produce on purpose: it needs a
 * row written by a *newer* build of the app, after a downgrade or a partially applied
 * migration. Untested, it is a comment. Tested, it is the reason the app does not lose data on
 * a rollback.
 *
 * ## Why the round trip is asserted as a whole object
 *
 * Field-by-field assertions pass when a field is added to the entity and forgotten in the
 * mapper — the new field simply is not asserted. Comparing the whole `Beneficiary` fails, and
 * the failure names the field. Same argument as `ConflictResolver.hasSameContentAs` on Day 14.
 */
class BeneficiaryMapperTest {

    @Test
    fun `a record survives the round trip unchanged`() {
        val original = beneficiary()

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `an absent MUAC survives the round trip as null`() {
        // Null is a real state — MUAC is only taken for children under five — so it must not
        // be coerced. A 0.0 here would be indistinguishable from a genuine reading of zero.
        val original = beneficiary(muacMm = null)

        assertEquals(null, original.toEntity().toDomain().measurement.muacMm)
    }

    @Test
    fun `sync status is stored as the enum name, not its ordinal`() {
        // An ordinal silently changes meaning if anyone reorders the enum: every existing row
        // would decode as a different status, with no error and no migration. The name costs a
        // few bytes per row and this test costs one line.
        assertEquals("CONFLICTED", beneficiary(syncStatus = SyncStatus.CONFLICTED).toEntity().syncStatus)
    }

    @Test
    fun `every sync status round trips`() {
        // The drift guard. A new SyncStatus that the mapper cannot decode would fall into the
        // PENDING fallback below and look like it worked, so the whole enum is walked rather
        // than a representative value.
        SyncStatus.entries.forEach { status ->
            val decoded = beneficiary(syncStatus = status).toEntity().toDomain().syncStatus

            assertEquals("$status did not survive the round trip", status, decoded)
        }
    }

    @Test
    fun `a status this build does not recognise decodes as pending`() {
        // Written by a newer build, read after a downgrade. PENDING is conservative: the
        // record is re-offered to the sync engine. Defaulting to SYNCED would mark data as
        // safely on the server when nothing had sent it.
        val fromTheFuture = entity(syncStatus = "QUARANTINED")

        assertEquals(SyncStatus.PENDING, fromTheFuture.toDomain().syncStatus)
    }

    @Test
    fun `an empty status decodes as pending rather than throwing`() {
        // The corrupt-row case rather than the newer-build case. Same answer, and worth its
        // own line because "" takes a different path through `firstOrNull` than a plausible
        // but unknown name does.
        assertEquals(SyncStatus.PENDING, entity(syncStatus = "").toDomain().syncStatus)
    }

    @Test
    fun `decoding is case sensitive, so a lowercased status is not silently accepted`() {
        // If this ever starts passing as SYNCED, something has begun normalising case — and a
        // mapper that accepts "synced" accepts a value nothing in this app writes, which means
        // it is reading rows some other process produced.
        assertEquals(SyncStatus.PENDING, entity(syncStatus = "synced").toDomain().syncStatus)
    }

    @Test
    fun `timestamps cross the boundary as raw epoch millis`() {
        // No timezone, no formatting, no rounding. The domain compares instants and the
        // database sorts integers; anything in between would be a place for the two to
        // disagree.
        val entity = beneficiary(recordedAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_001L).toEntity()

        assertEquals(1_700_000_000_000L, entity.recordedAtEpochMillis)
        assertEquals(1_700_000_000_001L, entity.updatedAtEpochMillis)
    }

    private fun beneficiary(
        muacMm: Double? = 130.0,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        recordedAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
    ) = Beneficiary(
        id = BeneficiaryId("a1b2"),
        name = "Asha Devi",
        ageYears = 3,
        village = "Kotri",
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = muacMm),
        recordedAt = Timestamp(recordedAt),
        updatedAt = Timestamp(updatedAt),
        syncStatus = syncStatus,
    )

    private fun entity(syncStatus: String) = BeneficiaryEntity(
        id = "a1b2",
        name = "Asha Devi",
        ageYears = 3,
        village = "Kotri",
        measurement = MeasurementEmbedded(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
        syncStatus = syncStatus,
    )
}
