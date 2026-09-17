package com.astracare.feature.patients.capture

import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.validation.ValidationError
import com.astracare.feature.patients.mvi.UiEffect
import com.astracare.feature.patients.mvi.UiIntent
import com.astracare.feature.patients.mvi.UiState

/**
 * The fields of the capture form, as an addressable enum.
 *
 * Exists so that one [CaptureIntent.FieldChanged] can serve six inputs instead of six
 * near-identical intent types and six near-identical reducer branches. The enum is also what
 * lets an error be attached to the field that caused it, which is what makes selective error
 * clearing possible below.
 */
enum class CaptureField {
    NAME,
    AGE,
    VILLAGE,
    WEIGHT,
    HEIGHT,
    MUAC,
}

/**
 * Everything currently wrong with the form.
 *
 * Two collections, because there are genuinely two kinds of wrong and they belong to
 * different layers:
 *
 *  - [violations] are domain rules — an age of 400, a weight of 0. They are produced by
 *    `BeneficiaryValidator` in `:core:domain`, because they are facts about what a valid
 *    record is, and they must hold for records arriving from a sync pull or an import too.
 *  - [malformed] are fields whose text is not a number at all. The domain has no concept of
 *    this: `Beneficiary.ageYears` is an `Int`, so "abc" cannot reach the validator. Parsing
 *    is a property of having a text box, which only the UI layer has.
 *
 * Collapsing them would mean either teaching the domain about strings, or reimplementing the
 * range rules here so both kinds could share a type. Both are worse.
 */
data class CaptureErrors(
    val violations: List<ValidationError> = emptyList(),
    val malformed: Set<CaptureField> = emptySet(),
) {

    val isEmpty: Boolean get() = violations.isEmpty() && malformed.isEmpty()

    /** Drops every error attributable to [field]. Used when the user starts fixing it. */
    fun clearedFor(field: CaptureField): CaptureErrors =
        if (isEmpty) {
            this
        } else {
            CaptureErrors(
                violations = violations.filterNot { it.field == field },
                malformed = malformed - field,
            )
        }

    /** Just the errors attributable to [field], for rendering beneath that one input. */
    fun forField(field: CaptureField): CaptureErrors = CaptureErrors(
        violations = violations.filter { it.field == field },
        malformed = if (field in malformed) setOf(field) else emptySet(),
    )

    companion object {
        val None = CaptureErrors()
    }
}

/**
 * Which input a domain violation belongs under.
 *
 * Declared here rather than in `:core:domain` on purpose. "Weight is out of range" is a
 * domain fact; "it should be shown under the third text box" is not, and a domain module that
 * knew about text boxes would have stopped being a domain module.
 */
internal val ValidationError.field: CaptureField
    get() = when (this) {
        ValidationError.NameBlank -> CaptureField.NAME
        ValidationError.VillageBlank -> CaptureField.VILLAGE
        is ValidationError.AgeOutOfRange -> CaptureField.AGE
        is ValidationError.WeightOutOfRange -> CaptureField.WEIGHT
        is ValidationError.HeightOutOfRange -> CaptureField.HEIGHT
        is ValidationError.MuacOutOfRange -> CaptureField.MUAC
    }

/**
 * The capture form.
 *
 * ## Why every numeric field is a String
 *
 * A text box holds text. While someone is typing a weight they will pass through "1", "12",
 * "12." and "12.5", and only two of those are a `Double`. State typed as `Double` has to
 * decide what "12." means, and every available answer is wrong: reject it and the decimal
 * point cannot be typed; coerce it to 12.0 and the cursor jumps. Holding the raw text and
 * parsing once, at save, is the only version where the keyboard behaves.
 *
 * It also keeps this state a faithful record of what the health worker actually entered,
 * which is what has to survive process death — not a lossy interpretation of it.
 *
 * ## Why a data class here, when the list screen uses a sealed interface
 *
 * A form has no mutually exclusive modes. Every field is always present and always editable,
 * even mid-save, so there is no illegal combination for a sealed hierarchy to rule out —
 * modelling it as one would produce a single subtype, or a subtype per field permutation.
 *
 * The list screen is the opposite: Loading, Empty and Content genuinely exclude one another,
 * and a data class there would admit `isLoading = true` alongside forty records. The shape
 * follows the screen. See DECISION_LOG 5.2.
 */
data class CaptureUiState(
    val name: String = "",
    val ageYears: String = "",
    val village: String = "",
    val weightKg: String = "",
    val heightCm: String = "",
    val muacMm: String = "",
    val errors: CaptureErrors = CaptureErrors.None,
    val isSaving: Boolean = false,
) : UiState {

    /**
     * Guards against the double-tap that produces two records.
     *
     * Derived rather than stored: a second boolean that must be kept consistent with
     * [isSaving] is a second boolean that will eventually disagree with it.
     */
    val isSaveEnabled: Boolean get() = !isSaving

    fun errorsFor(field: CaptureField): CaptureErrors = errors.forField(field)
}

/** What the capture screen reports upward. */
sealed interface CaptureIntent : UiIntent {

    data class FieldChanged(val field: CaptureField, val value: String) : CaptureIntent

    data object SaveClicked : CaptureIntent

    data object DiscardClicked : CaptureIntent
}

/**
 * One-shot consequences of a capture attempt.
 *
 * Note what is absent: there is no `ShowValidationErrors` effect. Validation results are
 * state — they stay on screen while the health worker fixes the form, and they must still be
 * there after a rotation. Sending them as an effect would clear the field highlights the
 * moment the device turned.
 */
sealed interface CaptureEffect : UiEffect {

    /** The record is committed to the local database. Safe to navigate away. */
    data object Saved : CaptureEffect

    /**
     * The local write failed. Carries the domain error rather than a message, for the same
     * reason [ValidationError] does: the wording and the language are the UI's to choose,
     * and this app's users are not reading English.
     */
    data class SaveFailed(val error: RepositoryError) : CaptureEffect

    data object Discarded : CaptureEffect
}

/**
 * The result of a save attempt, and the third message type in this contract.
 *
 * [CaptureIntent] travels up from the UI, [CaptureEffect] travels back down, and this
 * travels from the ViewModel's coroutine into the reducer. It is `internal` on purpose: the
 * UI can ask for a save, but it must not be able to announce that one succeeded. Folding
 * these cases into [CaptureIntent] — the usual single-hierarchy formulation — would make
 * `dispatch(SaveSucceeded)` a call a composable could legally write.
 */
internal sealed interface SaveOutcome {

    data object Succeeded : SaveOutcome

    /** The form is not a valid record. Errors belong on the fields, so they go into state. */
    data class Rejected(val errors: CaptureErrors) : SaveOutcome

    /** The record was valid but the local write failed. */
    data class Failed(val error: RepositoryError) : SaveOutcome
}
