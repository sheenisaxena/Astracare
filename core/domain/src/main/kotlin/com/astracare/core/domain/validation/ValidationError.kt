package com.astracare.core.domain.validation

/**
 * Why a beneficiary record was rejected.
 *
 * A sealed hierarchy rather than error strings, for three reasons:
 *
 *  1. The UI layer decides the wording and the language. A domain module that returns
 *     "Weight must be between 0.5 and 150 kg" has quietly taken a localisation decision it
 *     has no business making — and this app is for field workers in rural India, where an
 *     English-only string is a real barrier.
 *  2. `when` over a sealed type is exhaustive, so adding a rule breaks compilation at every
 *     place that must handle it. A new string silently displays as nothing.
 *  3. Tests assert on `WeightOutOfRange` rather than on prose, so rewording a message does
 *     not break the test suite.
 */
sealed interface ValidationError {

    data object NameBlank : ValidationError

    data object VillageBlank : ValidationError

    data class AgeOutOfRange(val value: Int) : ValidationError

    data class WeightOutOfRange(val value: Double) : ValidationError

    data class HeightOutOfRange(val value: Double) : ValidationError

    data class MuacOutOfRange(val value: Double) : ValidationError
}
