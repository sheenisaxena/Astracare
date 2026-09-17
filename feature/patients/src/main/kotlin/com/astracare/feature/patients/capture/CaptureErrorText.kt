package com.astracare.feature.patients.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.astracare.core.domain.validation.ValidationError
import com.astracare.feature.patients.R

/**
 * Turns a field's errors into the one line shown beneath it.
 *
 * This is the other half of the boundary drawn in DECISION_LOG 5.6. The domain says
 * [ValidationError.WeightOutOfRange] — a fact about the record, carrying the offending value
 * and no opinion about language. This function says "Weight should be between 0.5 and 300 kg",
 * in whichever language the device is set to. Neither could do the other's job: the domain
 * cannot know the locale, and the composable cannot know the clinical bounds.
 *
 * Returns only the first error. A field with two problems has one problem worth reporting
 * first, and stacking messages under a text input pushes the rest of the form off a small
 * screen — which is how a health worker ends up unable to see the field they are fixing.
 *
 * A malformed value outranks a range violation, because it is the more basic complaint: if
 * the text is not a number, the range check never ran and its verdict is not meaningful.
 */
@Composable
internal fun errorTextFor(field: CaptureField, errors: CaptureErrors): String? {
    val fieldErrors = errors.forField(field)

    return when {
        field in fieldErrors.malformed -> stringResource(R.string.error_not_a_number)
        else -> fieldErrors.violations.firstOrNull()?.let { stringResource(it.messageRes) }
    }
}

/**
 * The message for a domain violation.
 *
 * `when` over a sealed interface, so adding a [ValidationError] breaks compilation here rather
 * than displaying nothing at runtime. That exhaustiveness is the reason `ValidationError` is a
 * sealed hierarchy instead of error strings, and this is the call site that collects on it.
 *
 * The numbers in these messages duplicate `BeneficiaryValidator`'s bounds, which is a real
 * cost and the least-bad option: the alternative is interpolating the values out of the error
 * type, which produces "Weight should be between 0.5 and 300.0 kg" and an English sentence
 * assembled from fragments that no translator can reorder. Noted as a gap — the bounds and
 * their wording should eventually be generated from one source.
 */
private val ValidationError.messageRes: Int
    get() = when (this) {
        ValidationError.NameBlank -> R.string.error_name_blank
        ValidationError.VillageBlank -> R.string.error_village_blank
        is ValidationError.AgeOutOfRange -> R.string.error_age_range
        is ValidationError.WeightOutOfRange -> R.string.error_weight_range
        is ValidationError.HeightOutOfRange -> R.string.error_height_range
        is ValidationError.MuacOutOfRange -> R.string.error_muac_range
    }
