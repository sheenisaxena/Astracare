package com.astracare.feature.patients.capture

import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.validation.ValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture screen's state transitions, tested as pure functions.
 *
 * No dispatcher, no ViewModel, no Robolectric, no fakes. That is the payoff for keeping the
 * reducer separate: these assert on exactly the combinations that are painful to reproduce
 * through a UI — a failed write followed by an edit, a second tap while a save is in flight —
 * and they cannot flake, because there is nothing asynchronous to race.
 */
class CaptureReducerTest {

    private val filledForm = CaptureUiState(
        name = "Asha Devi",
        ageYears = "3",
        village = "Kotri",
        weightKg = "12.4",
        heightCm = "91.0",
        muacMm = "134.0",
    )

    @Test
    fun `field change updates only that field`() {
        val next = CaptureUiState().reduce(
            CaptureIntent.FieldChanged(CaptureField.VILLAGE, "Kotri"),
        )

        assertEquals("Kotri", next.village)
        assertEquals("", next.name)
    }

    @Test
    fun `editing a field clears that field's error but leaves the others`() {
        val rejected = CaptureUiState().reduce(
            SaveOutcome.Rejected(
                CaptureErrors(
                    violations = listOf(ValidationError.NameBlank, ValidationError.VillageBlank),
                    malformed = setOf(CaptureField.WEIGHT),
                ),
            ),
        )

        val next = rejected.reduce(CaptureIntent.FieldChanged(CaptureField.NAME, "Asha"))

        assertTrue(next.errorsFor(CaptureField.NAME).isEmpty)
        assertFalse(next.errorsFor(CaptureField.VILLAGE).isEmpty)
        assertFalse(next.errorsFor(CaptureField.WEIGHT).isEmpty)
    }

    @Test
    fun `save marks the form as saving and disables the button`() {
        val next = filledForm.reduce(CaptureIntent.SaveClicked)

        assertTrue(next.isSaving)
        assertFalse(next.isSaveEnabled)
    }

    @Test
    fun `a second save while one is in flight changes nothing`() {
        val saving = filledForm.reduce(CaptureIntent.SaveClicked)

        assertEquals(saving, saving.reduce(CaptureIntent.SaveClicked))
    }

    @Test
    fun `a successful save clears the form for the next record`() {
        val saving = filledForm.reduce(CaptureIntent.SaveClicked)

        assertEquals(CaptureUiState(), saving.reduce(SaveOutcome.Succeeded))
    }

    @Test
    fun `a rejected save surfaces the violations and re-enables the button`() {
        val violations = listOf(ValidationError.AgeOutOfRange(400))
        val saving = filledForm.reduce(CaptureIntent.SaveClicked)

        val next = saving.reduce(SaveOutcome.Rejected(CaptureErrors(violations = violations)))

        assertEquals(violations, next.errors.violations)
        assertTrue(next.isSaveEnabled)
    }

    @Test
    fun `a storage failure preserves everything the health worker typed`() {
        val saving = filledForm.reduce(CaptureIntent.SaveClicked)

        val next = saving.reduce(
            SaveOutcome.Failed(RepositoryError.StorageFailure(IllegalStateException("disk full"))),
        )

        assertEquals(filledForm.copy(isSaving = false), next)
        assertTrue(next.isSaveEnabled)
    }
}
