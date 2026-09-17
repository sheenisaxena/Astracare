package com.astracare.feature.patients

import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The history screen's stream-to-state projection.
 *
 * The distinction these tests pin down — that an empty database projects to `Empty` and never
 * to a `Content` with no rows — is the whole reason the screen stopped exposing a raw
 * `List<Beneficiary>`. Without it the composable has to infer the difference between "no
 * records" and "not asked yet", and it cannot.
 */
class BeneficiaryListUiStateTest {

    @Test
    fun `no records projects to Empty, not an empty Content`() {
        assertEquals(BeneficiaryListUiState.Empty, emptyList<Beneficiary>().toUiState())
    }

    @Test
    fun `records project to Content in the order the domain supplied`() {
        val records = listOf(
            record("a", SyncStatus.PENDING),
            record("b", SyncStatus.SYNCED),
        )

        val state = records.toUiState() as BeneficiaryListUiState.Content

        assertEquals(records, state.records)
    }

    @Test
    fun `awaitingSync counts every record the server has not acknowledged`() {
        val records = listOf(
            record("a", SyncStatus.PENDING),
            record("b", SyncStatus.SYNCED),
            record("c", SyncStatus.FAILED),
            record("d", SyncStatus.CONFLICTED),
            record("e", SyncStatus.SYNCED),
        )

        val state = records.toUiState() as BeneficiaryListUiState.Content

        // Counted as "not SYNCED" rather than by listing the three other cases, so a new
        // SyncStatus added later is counted as unsafe by default. Erring the other way would
        // quietly tell a health worker their work was on the server when it was not.
        assertEquals(3, state.awaitingSync)
    }

    private fun record(id: String, syncStatus: SyncStatus) = Beneficiary(
        id = BeneficiaryId(id),
        name = "Asha Devi",
        ageYears = 3,
        village = "Kotri",
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
        syncStatus = syncStatus,
    )
}
