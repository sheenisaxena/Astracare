package com.astracare.feature.patients.capture

import com.astracare.core.common.Outcome
import com.astracare.core.domain.validation.ValidationError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.CaptureDraft
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp

/**
 * Every transition of [CaptureUiState] is a function in this file, and every one of them is
 * pure. The ViewModel performs no state arithmetic at all — it decides *when* a transition
 * happens and what asynchronous work surrounds it, never what the next state looks like.
 *
 * That separation is the entire return on MVI, and it is worth stating why rather than
 * treating it as a style rule. State bugs in a form are combinational: the report is "the
 * save button stayed disabled", and the cause is some pairing of a keystroke, a failed write
 * and a rotation. Reproducing that through the UI means recreating the sequence by hand.
 * Against a pure function it is three lines in a test, with no dispatcher, no Robolectric and
 * no ViewModel — which is why the tests for these transitions run in milliseconds and cannot
 * flake.
 *
 * There are two entry points rather than one, and the split is load-bearing. [reduce] taking
 * a [CaptureIntent] handles what the *user* did; [reduce] taking a [SaveOutcome] handles what
 * the *save* reported back. Merging them into one sealed hierarchy — the textbook single
 * reducer — would put `SaveSucceeded` in the same type the composable dispatches, so a
 * screen could announce that a record had been written when nothing had been written. The
 * type system prevents that here instead of a comment asking people not to.
 */

/** Applies a user action. */
internal fun CaptureUiState.reduce(intent: CaptureIntent): CaptureUiState = when (intent) {
    is CaptureIntent.FieldChanged ->
        withField(intent.field, intent.value)
            .copy(errors = errors.clearedFor(intent.field))

    // Re-entrancy is handled here rather than in the ViewModel so that "a save already in
    // flight absorbs further taps" is a property of the state machine, provable without one.
    CaptureIntent.SaveClicked -> if (isSaving) this else copy(isSaving = true)

    CaptureIntent.DiscardClicked -> this
}

/** Applies the result of a save. */
internal fun CaptureUiState.reduce(outcome: SaveOutcome): CaptureUiState = when (outcome) {
    // A blank form, not the saved one. Field work is record after record, so leaving the
    // previous beneficiary's details on screen is how the next child gets the last child's
    // village — a data-integrity bug wearing a convenience feature's clothes.
    SaveOutcome.Succeeded -> CaptureUiState()

    is SaveOutcome.Rejected -> copy(isSaving = false, errors = outcome.errors)

    // Input is deliberately preserved. A storage failure is transient and not the health
    // worker's fault; clearing the form would make them re-enter a visit they already
    // recorded, on a handset that just demonstrated it cannot be trusted to hold data.
    is SaveOutcome.Failed -> copy(isSaving = false)
}

private fun CaptureUiState.withField(field: CaptureField, value: String): CaptureUiState =
    when (field) {
        CaptureField.NAME -> copy(name = value)
        CaptureField.AGE -> copy(ageYears = value)
        CaptureField.VILLAGE -> copy(village = value)
        CaptureField.WEIGHT -> copy(weightKg = value)
        CaptureField.HEIGHT -> copy(heightCm = value)
        CaptureField.MUAC -> copy(muacMm = value)
    }

/**
 * Turns the form into a domain record, or names the fields that are not numbers.
 *
 * This is the whole of the UI layer's validation responsibility, and the boundary is worth
 * being precise about: this function answers "is this text a number?" and nothing else.
 * Whether that number is a plausible weight is [ValidationError]'s question, answered by
 * `BeneficiaryValidator` inside `SaveBeneficiaryUseCase`. Range checks are not duplicated
 * here, so they cannot drift from the domain's copy — the failure mode where a form accepts
 * a value the database then rejects.
 *
 * [id] and [now] are parameters rather than being generated inside, so a test can assert on
 * the exact record produced instead of on everything-except-two-fields.
 *
 * Returns `Outcome` — the project's own result type, already used across the domain — rather
 * than a nullable or an exception. A null would lose which fields were malformed, and there
 * is nothing exceptional about a typo.
 */
internal fun CaptureUiState.toBeneficiary(
    id: BeneficiaryId,
    now: Timestamp,
): Outcome<Beneficiary, Set<CaptureField>> {
    val age = ageYears.trim().toIntOrNull()
    val measurement = parseMeasurement()

    // The happy path is inside the null check rather than after an early return, so the
    // values are smart-cast and there is no `!!` anywhere — nothing to assert, nothing that
    // could throw if the guard were ever wrong.
    return if (age != null && measurement != null) {
        Outcome.Success(
            Beneficiary(
                id = id,
                name = name.trim(),
                ageYears = age,
                village = village.trim(),
                measurement = measurement,
                recordedAt = now,
                // Equal to recordedAt for a new record; SaveBeneficiaryUseCase restamps it
                // on every write, so this is the value only until the use case runs.
                updatedAt = now,
                // Set explicitly rather than defaulted, because it is the claim the sync
                // engine acts on: a record that has never left the handset is PENDING, and
                // anything else here would make the worker skip it.
                syncStatus = SyncStatus.PENDING,
            ),
        )
    } else {
        Outcome.Failure(malformedFields())
    }
}

/**
 * The three measurements, or null if any of them is not a number.
 *
 * Absent MUAC is legitimate — it is only recorded for children under five — so blank text
 * parses to a null measurement rather than a failure. Text that is present but unparseable
 * is still an error, which is why [muacIsWellFormed] distinguishes the two.
 */
private fun CaptureUiState.parseMeasurement(): Measurement? {
    val weight = weightKg.trim().toDoubleOrNull()
    val height = heightCm.trim().toDoubleOrNull()
    val muac = muacMm.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    return if (weight != null && height != null && muacIsWellFormed()) {
        Measurement(weightKg = weight, heightCm = height, muacMm = muac)
    } else {
        null
    }
}

private fun CaptureUiState.muacIsWellFormed(): Boolean {
    val text = muacMm.trim()
    return text.isEmpty() || text.toDoubleOrNull() != null
}

/**
 * Names every field that is not a number, so the form can mark all of them at once.
 *
 * Reached only on the failure path, which is why re-parsing here rather than threading
 * partial results out of [parseMeasurement] is the right trade: it costs four string parses
 * on a keystroke-free code path, and it keeps the success path free of nullable plumbing.
 */
private fun CaptureUiState.malformedFields(): Set<CaptureField> = buildSet {
    if (ageYears.trim().toIntOrNull() == null) add(CaptureField.AGE)
    if (weightKg.trim().toDoubleOrNull() == null) add(CaptureField.WEIGHT)
    if (heightCm.trim().toDoubleOrNull() == null) add(CaptureField.HEIGHT)
    if (!muacIsWellFormed()) add(CaptureField.MUAC)
}

/**
 * The form as durable, unvalidated work.
 *
 * Only the typed text crosses this boundary. [CaptureUiState.errors] and
 * [CaptureUiState.isSaving] are deliberately dropped: they describe a save attempt that no
 * longer exists once the process has died, and restoring a form that opens already flagged
 * red — or already believing a save is in flight — would be worse than restoring nothing.
 */
internal fun CaptureUiState.toDraft(): CaptureDraft = CaptureDraft(
    name = name,
    ageYears = ageYears,
    village = village,
    weightKg = weightKg,
    heightCm = heightCm,
    muacMm = muacMm,
)

/**
 * Seeds a fresh form from stored work.
 *
 * Restoration is not a transition — there is no previous state to reduce from, because this
 * runs before the screen has accepted a single keystroke. So it is a plain pure function
 * rather than a third `reduce` overload, and the ViewModel seeds its state with the result.
 * Round-trips with [toDraft] exactly, which is what `CaptureDraftMappingTest` pins.
 */
internal fun CaptureDraft.toUiState(): CaptureUiState = CaptureUiState(
    name = name,
    ageYears = ageYears,
    village = village,
    weightKg = weightKg,
    heightCm = heightCm,
    muacMm = muacMm,
)
