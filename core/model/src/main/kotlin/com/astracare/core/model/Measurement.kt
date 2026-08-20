package com.astracare.core.model

/**
 * Anthropometric measurements taken during a visit.
 *
 * A separate value object rather than three loose fields on [Beneficiary], because they are
 * captured together, validated together, and only mean anything together. A weight with no
 * height cannot be interpreted.
 *
 * [muacMm] is nullable by design: mid-upper arm circumference is only recorded for children
 * under five, so absence is a legitimate state rather than missing data. Modelling it as
 * nullable forces every consumer to decide what to do when it is absent, instead of silently
 * treating 0.0 as a real reading.
 */
data class Measurement(
    val weightKg: Double,
    val heightCm: Double,
    val muacMm: Double?,
)
