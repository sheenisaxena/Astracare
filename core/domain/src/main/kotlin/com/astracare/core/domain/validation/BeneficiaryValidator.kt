package com.astracare.core.domain.validation

import com.astracare.core.model.Beneficiary

/**
 * Validates a [Beneficiary] before it is persisted.
 *
 * Lives in the domain layer, not the UI, because these are rules about what constitutes a
 * valid record — not rules about what a form will accept. Put them in the composable and they
 * are bypassed the moment a second entry point exists (a sync pull, an import, a test), and
 * invalid data reaches the database.
 *
 * Returns ALL failures rather than the first. A health worker on a patchy handset should see
 * everything wrong with the form at once, not discover a second problem after fixing the
 * first.
 *
 * Bounds are deliberately generous. This is a data-capture app, and a validator that rejects
 * a genuine outlier is worse than one that admits an implausible value: the former loses real
 * clinical data permanently, the latter is visible and correctable later.
 */
object BeneficiaryValidator {

    // Named constants rather than inline literals — detekt's MagicNumber rule is active, and
    // more importantly these thresholds are clinical decisions that deserve to be findable.
    private const val MIN_AGE_YEARS = 0
    private const val MAX_AGE_YEARS = 120

    private const val MIN_WEIGHT_KG = 0.5
    private const val MAX_WEIGHT_KG = 300.0

    private const val MIN_HEIGHT_CM = 20.0
    private const val MAX_HEIGHT_CM = 260.0

    private const val MIN_MUAC_MM = 50.0
    private const val MAX_MUAC_MM = 500.0

    fun validate(beneficiary: Beneficiary): List<ValidationError> = buildList {
        if (beneficiary.name.isBlank()) {
            add(ValidationError.NameBlank)
        }
        if (beneficiary.village.isBlank()) {
            add(ValidationError.VillageBlank)
        }
        if (beneficiary.ageYears !in MIN_AGE_YEARS..MAX_AGE_YEARS) {
            add(ValidationError.AgeOutOfRange(beneficiary.ageYears))
        }

        val measurement = beneficiary.measurement
        if (measurement.weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) {
            add(ValidationError.WeightOutOfRange(measurement.weightKg))
        }
        if (measurement.heightCm !in MIN_HEIGHT_CM..MAX_HEIGHT_CM) {
            add(ValidationError.HeightOutOfRange(measurement.heightCm))
        }

        // Absent MUAC is valid — it is only recorded for children under five.
        val muac = measurement.muacMm
        if (muac != null && muac !in MIN_MUAC_MM..MAX_MUAC_MM) {
            add(ValidationError.MuacOutOfRange(muac))
        }
    }
}
