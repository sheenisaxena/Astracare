package com.astracare.feature.patients.capture

import com.astracare.core.model.CaptureDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The form-to-draft round trip.
 *
 * Worth its own tests because the failure mode is silent and delayed: a field omitted from
 * [CaptureUiState.toDraft] does not break anything until a health worker's phone dies
 * mid-visit and the village they typed is missing when the app comes back. Nothing in the
 * type system catches that — both sides compile fine with a field left out — so it is caught
 * here instead.
 */
class CaptureDraftMappingTest {

    private val filledForm = CaptureUiState(
        name = "Asha Devi",
        ageYears = "3",
        village = "Kotri",
        weightKg = "12.4",
        heightCm = "91.0",
        muacMm = "134.0",
    )

    @Test
    fun `every typed field survives the round trip`() {
        assertEquals(filledForm, filledForm.toDraft().toUiState())
    }

    @Test
    fun `half-typed text is preserved exactly, not normalised`() {
        // "12." is not a Double and must come back as "12." — the entire reason a draft
        // stores text rather than numbers. Coercing it would move the user's cursor.
        val partial = CaptureUiState(weightKg = "12.", ageYears = "")

        assertEquals("12.", partial.toDraft().toUiState().weightKg)
    }

    @Test
    fun `transient state is deliberately dropped`() {
        val midSave = filledForm.copy(
            isSaving = true,
            errors = CaptureErrors(malformed = setOf(CaptureField.AGE)),
        )

        val restored = midSave.toDraft().toUiState()

        // A restored form must not open already flagged red, and must not believe a save it
        // has no record of is still running.
        assertFalse(restored.isSaving)
        assertTrue(restored.errors.isEmpty)
        assertTrue(restored.isSaveEnabled)
    }

    @Test
    fun `an untouched form produces a blank draft`() {
        assertTrue(CaptureUiState().toDraft().isBlank)
    }

    @Test
    fun `whitespace alone still counts as blank`() {
        // Otherwise an accidental space bar press creates a draft that is restored forever
        // and can never be cleared by emptying the form.
        assertTrue(CaptureUiState(name = "   ").toDraft().isBlank)
    }

    @Test
    fun `a single typed character makes the draft worth keeping`() {
        assertFalse(CaptureUiState(muacMm = "1").toDraft().isBlank)
    }

    @Test
    fun `the empty draft restores to the same form a fresh screen starts with`() {
        assertEquals(CaptureUiState(), CaptureDraft.Empty.toUiState())
    }
}
