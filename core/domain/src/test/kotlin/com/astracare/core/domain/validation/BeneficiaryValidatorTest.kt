package com.astracare.core.domain.validation

import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validation rules, at their edges.
 *
 * `BeneficiaryValidator` had no test until Day 18, which is the gap worth naming rather than
 * quietly closing: it is the single densest concentration of clinical judgement in the
 * codebase, every record passes through it, and `ValidationError`'s own documentation claimed
 * "tests assert on `WeightOutOfRange` rather than on prose" while no such test existed.
 *
 * ## Why the boundaries are tested and the middle mostly is not
 *
 * A range check has exactly four interesting values — just below the minimum, the minimum,
 * the maximum, just above the maximum — and one uninteresting one, which is anything in
 * between. Off-by-one and wrong-comparison bugs live entirely at the edges: `<` written where
 * `<=` was meant passes every test that uses 12.4 kg and fails the one that uses 0.5.
 *
 * Kotlin's `in a..b` is inclusive at both ends, so every boundary value here must be
 * **accepted**. That is the property under test: not that the numbers are right — they are a
 * clinical decision — but that the code implements the range it says it does.
 *
 * ## The two properties that are not about any single field
 *
 * `every failure is reported, not just the first` pins the documented decision that a health
 * worker sees everything wrong at once. It is the kind of behaviour a `return` inserted during
 * a refactor silently removes, and nothing else would notice.
 *
 * `a NaN measurement is rejected` covers a value the range check handles correctly by accident
 * of how IEEE comparison works, which means a rewrite to `weight < MIN || weight > MAX` would
 * quietly start *accepting* it. A NaN weight reaching the database is a record that cannot be
 * compared, sorted or summed ever again.
 */
class BeneficiaryValidatorTest {

    // ---- the happy path, so every other assertion is about one thing ----------------------

    @Test
    fun `a complete, plausible record has no errors`() {
        assertTrue(validate(record()).isEmpty())
    }

    @Test
    fun `an absent MUAC is valid`() {
        // Not a missing value: mid-upper arm circumference is only recorded for children under
        // five, so null is a real state. Rejecting it would make every adult record invalid.
        assertTrue(validate(record(muacMm = null)).isEmpty())
    }

    // ---- name and village ------------------------------------------------------------------

    @Test
    fun `an empty name is rejected`() {
        assertEquals(listOf(ValidationError.NameBlank), validate(record(name = "")))
    }

    @Test
    fun `a whitespace-only name is rejected`() {
        // `isBlank`, not `isEmpty`. A space bar pressed once is indistinguishable from a real
        // name on screen and is not one.
        assertEquals(listOf(ValidationError.NameBlank), validate(record(name = "   ")))
    }

    @Test
    fun `a whitespace-only village is rejected`() {
        assertEquals(listOf(ValidationError.VillageBlank), validate(record(village = "\t\n ")))
    }

    @Test
    fun `a single character name is accepted`() {
        // There is no minimum length and there should not be one. Names this app will meet
        // include transliterations a Latin-alphabet intuition would reject.
        assertTrue(validate(record(name = "K")).isEmpty())
    }

    // ---- age ------------------------------------------------------------------------------

    @Test
    fun `age accepts both ends of its range`() {
        assertTrue(validate(record(ageYears = 0)).isEmpty())
        assertTrue(validate(record(ageYears = 120)).isEmpty())
    }

    @Test
    fun `age rejects just outside either end`() {
        assertEquals(listOf(ValidationError.AgeOutOfRange(-1)), validate(record(ageYears = -1)))
        assertEquals(listOf(ValidationError.AgeOutOfRange(121)), validate(record(ageYears = 121)))
    }

    @Test
    fun `a rejected age is reported with the value that was rejected`() {
        // The error carries the offending value rather than being a bare marker, so the UI can
        // say what was wrong without re-deriving it. Asserted because a refactor to a data
        // object would compile everywhere and lose it.
        val errors = validate(record(ageYears = 400))

        assertEquals(ValidationError.AgeOutOfRange(400), errors.single())
    }

    // ---- weight ---------------------------------------------------------------------------

    @Test
    fun `weight accepts both ends of its range`() {
        assertTrue(validate(record(weightKg = 0.5)).isEmpty())
        assertTrue(validate(record(weightKg = 300.0)).isEmpty())
    }

    @Test
    fun `weight rejects just outside either end`() {
        assertTrue(validate(record(weightKg = 0.49)).single() is ValidationError.WeightOutOfRange)
        assertTrue(validate(record(weightKg = 300.01)).single() is ValidationError.WeightOutOfRange)
    }

    @Test
    fun `a zero weight is rejected`() {
        // The case a real form produces: the field was never filled in and a default of 0.0
        // reached the validator. It must not be mistaken for a measurement.
        assertEquals(
            listOf(ValidationError.WeightOutOfRange(0.0)),
            validate(record(weightKg = 0.0)),
        )
    }

    // ---- height ---------------------------------------------------------------------------

    @Test
    fun `height accepts both ends of its range`() {
        assertTrue(validate(record(heightCm = 20.0)).isEmpty())
        assertTrue(validate(record(heightCm = 260.0)).isEmpty())
    }

    @Test
    fun `height rejects just outside either end`() {
        assertTrue(validate(record(heightCm = 19.99)).single() is ValidationError.HeightOutOfRange)
        assertTrue(validate(record(heightCm = 260.01)).single() is ValidationError.HeightOutOfRange)
    }

    // ---- MUAC -----------------------------------------------------------------------------

    @Test
    fun `MUAC accepts both ends of its range when present`() {
        assertTrue(validate(record(muacMm = 50.0)).isEmpty())
        assertTrue(validate(record(muacMm = 500.0)).isEmpty())
    }

    @Test
    fun `MUAC rejects just outside either end when present`() {
        assertTrue(validate(record(muacMm = 49.99)).single() is ValidationError.MuacOutOfRange)
        assertTrue(validate(record(muacMm = 500.01)).single() is ValidationError.MuacOutOfRange)
    }

    // ---- properties that span fields --------------------------------------------------------

    @Test
    fun `every failure is reported, not just the first`() {
        // The documented decision: a health worker on a patchy handset sees everything wrong
        // at once rather than discovering a second problem after fixing the first. An early
        // `return` added during a refactor would break this and nothing else.
        val errors = validate(
            record(name = "", village = "", ageYears = 999, weightKg = 0.0, heightCm = 0.0, muacMm = 0.0),
        )

        assertEquals(
            setOf(
                ValidationError.NameBlank,
                ValidationError.VillageBlank,
                ValidationError.AgeOutOfRange(999),
                ValidationError.WeightOutOfRange(0.0),
                ValidationError.HeightOutOfRange(0.0),
                ValidationError.MuacOutOfRange(0.0),
            ),
            errors.toSet(),
        )
    }

    @Test
    fun `a NaN measurement is rejected`() {
        // `NaN !in 0.5..300.0` is true because every comparison involving NaN is false, so the
        // range check handles this correctly — by accident of IEEE semantics rather than by
        // design. Rewriting it as `weight < MIN || weight > MAX` would silently start accepting
        // NaN, and a NaN weight in the database can never be compared, sorted or summed again.
        assertTrue(validate(record(weightKg = Double.NaN)).single() is ValidationError.WeightOutOfRange)
        assertTrue(validate(record(heightCm = Double.NaN)).single() is ValidationError.HeightOutOfRange)
        assertTrue(validate(record(muacMm = Double.NaN)).single() is ValidationError.MuacOutOfRange)
    }

    @Test
    fun `an infinite measurement is rejected`() {
        val errors = validate(record(weightKg = Double.POSITIVE_INFINITY))

        assertTrue(errors.single() is ValidationError.WeightOutOfRange)
        assertTrue(validate(record(heightCm = Double.NEGATIVE_INFINITY)).isNotEmpty())
    }

    @Test
    fun `validation ignores the fields it is not responsible for`() {
        // Sync status, timestamps and the ID are set by the sync engine and the use case, not
        // by a health worker, and none of them can be invalid in a way this validator should
        // catch. Pinned so that a well-meant "validate everything" change has to argue with a
        // test rather than quietly rejecting records the sync engine produced.
        val fromServer = record().copy(
            syncStatus = SyncStatus.CONFLICTED,
            recordedAt = Timestamp(Long.MAX_VALUE),
            updatedAt = Timestamp(Long.MIN_VALUE),
        )

        assertTrue(validate(fromServer).isEmpty())
    }

    private fun validate(beneficiary: Beneficiary) = BeneficiaryValidator.validate(beneficiary)

    private fun record(
        name: String = "Asha Devi",
        village: String = "Kotri",
        ageYears: Int = 3,
        weightKg: Double = 12.4,
        heightCm: Double = 91.0,
        muacMm: Double? = 130.0,
    ) = Beneficiary(
        id = BeneficiaryId("1"),
        name = name,
        ageYears = ageYears,
        village = village,
        measurement = Measurement(weightKg = weightKg, heightCm = heightCm, muacMm = muacMm),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
        syncStatus = SyncStatus.PENDING,
    )
}
