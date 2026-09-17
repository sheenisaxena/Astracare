package com.astracare.feature.patients.capture

import com.astracare.core.common.Outcome
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The form-to-domain boundary.
 *
 * Separate from [CaptureReducerTest] because it tests a different claim: not "what does the
 * next state look like" but "where does validation live". The last test in this file is the
 * one that matters most — it pins the boundary in place, so that a future well-meaning change
 * that adds a range check to the UI layer fails here rather than in production, six months
 * later, when the UI's bound and the domain's bound have quietly diverged.
 */
class CaptureFormMappingTest {

    private val filledForm = CaptureUiState(
        name = "Asha Devi",
        ageYears = "3",
        village = "Kotri",
        weightKg = "12.4",
        heightCm = "91.0",
        muacMm = "134.0",
    )

    private val anyId = BeneficiaryId("record-1")

    @Test
    fun `a valid form maps to the exact record it describes`() {
        val now = Timestamp(1_700_000_000_000L)

        val beneficiary = filledForm.toBeneficiary(id = anyId, now = now).valueOrFail()

        assertEquals(anyId, beneficiary.id)
        assertEquals("Asha Devi", beneficiary.name)
        assertEquals(3, beneficiary.ageYears)
        assertEquals("Kotri", beneficiary.village)
        assertEquals(12.4, beneficiary.measurement.weightKg, TOLERANCE)
        assertEquals(91.0, beneficiary.measurement.heightCm, TOLERANCE)
        assertEquals(134.0, beneficiary.measurement.muacMm!!, TOLERANCE)
        assertEquals(now, beneficiary.recordedAt)
        assertEquals(now, beneficiary.updatedAt)
        assertEquals(SyncStatus.PENDING, beneficiary.syncStatus)
    }

    @Test
    fun `surrounding whitespace is trimmed from text and numbers alike`() {
        val form = filledForm.copy(name = "  Asha Devi  ", weightKg = " 12.4 ")

        val beneficiary = form.toBeneficiary(anyId, Timestamp(0L)).valueOrFail()

        assertEquals("Asha Devi", beneficiary.name)
        assertEquals(12.4, beneficiary.measurement.weightKg, TOLERANCE)
    }

    @Test
    fun `a blank MUAC is absent, not zero`() {
        val form = filledForm.copy(muacMm = "")

        assertNull(form.toBeneficiary(anyId, Timestamp(0L)).valueOrFail().measurement.muacMm)
    }

    @Test
    fun `every unparseable field is reported, not just the first`() {
        val form = filledForm.copy(ageYears = "three", weightKg = "heavy", muacMm = "x")

        val malformed = form.toBeneficiary(anyId, Timestamp(0L)) as Outcome.Failure

        assertEquals(
            setOf(CaptureField.AGE, CaptureField.WEIGHT, CaptureField.MUAC),
            malformed.error,
        )
    }

    @Test
    fun `an out-of-range value is not a parse error - the domain owns that rule`() {
        // 400 parses fine, so this layer accepts it. Rejecting it is BeneficiaryValidator's
        // job, reached through SaveBeneficiaryUseCase. Re-checking the bound here would give
        // the project two copies of every clinical range, free to disagree.
        val form = filledForm.copy(ageYears = "400")

        assertEquals(400, form.toBeneficiary(anyId, Timestamp(0L)).valueOrFail().ageYears)
    }

    private fun <T> Outcome<T, Set<CaptureField>>.valueOrFail(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure ->
            throw AssertionError("expected a valid record; malformed fields: ${this.error}")
    }

    private companion object {
        const val TOLERANCE = 0.0
    }
}
