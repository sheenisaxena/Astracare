package com.astracare.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result type every failure in this app travels through.
 *
 * Twelve lines of production code and it was untested, which is the usual reason a type like
 * this goes untested: it looks too simple to break. What makes it worth a file is that it is
 * *load-bearing* — `SaveBeneficiaryUseCase`, the repository and every ViewModel branch on it,
 * so a variance annotation or a `map` that transformed the wrong side would be a bug in five
 * places at once and obvious in none of them.
 *
 * ## The test that is really about the type system
 *
 * `map leaves a failure untouched` is the one worth reading. `map` returns `this` on the
 * failure branch, which compiles only because `Failure<E>` is an `Outcome<Nothing, E>` and
 * `Nothing` is a subtype of the new `R`. If someone "simplifies" that to
 * `Outcome.Failure(error)` the behaviour is identical and the allocation is not — and if
 * someone widens the variance, the branch silently stops type-checking in a way that reads as
 * a caller's problem. Asserting that the failure survives with its error intact pins the
 * behaviour the variance exists to allow.
 */
class OutcomeTest {

    @Test
    fun `a success reports success and carries its value`() {
        val outcome: Outcome<Int, String> = Outcome.Success(42)

        assertTrue(outcome.isSuccess)
        assertEquals(42, outcome.getOrNull())
    }

    @Test
    fun `a failure reports failure and yields no value`() {
        val outcome: Outcome<Int, String> = Outcome.Failure("disk full")

        assertFalse(outcome.isSuccess)
        assertNull(outcome.getOrNull())
    }

    @Test
    fun `the unit success helper is a success`() {
        // `Outcome.success()` is what every void-returning use case returns, so "did it
        // actually construct a Success" is worth one line rather than an assumption.
        assertTrue(Outcome.success().isSuccess)
        assertEquals(Unit, Outcome.success().getOrNull())
    }

    @Test
    fun `map transforms the success value`() {
        val mapped: Outcome<String, Nothing> = Outcome.Success(2).map { "value $it" }

        assertEquals("value 2", mapped.getOrNull())
    }

    @Test
    fun `map leaves a failure untouched`() {
        val failure: Outcome<Int, String> = Outcome.Failure("disk full")

        val mapped: Outcome<String, String> = failure.map { error("the transform must not run") }

        assertFalse(mapped.isSuccess)
        assertEquals(Outcome.Failure("disk full"), mapped)
    }

    @Test
    fun `a null success value is a success, not a failure`() {
        // The trap in every result type that models absence with null. A repository returning
        // `Success(null)` for "found nothing" must not be mistaken for a failure, and
        // `getOrNull` cannot distinguish the two — which is why `isSuccess` exists separately
        // and why callers branch on the type rather than on the value.
        val outcome: Outcome<String?, String> = Outcome.Success(null)

        assertTrue(outcome.isSuccess)
        assertNull(outcome.getOrNull())
    }

    @Test
    fun `outcomes compare by value, so tests can assert on them directly`() {
        // Data classes, deliberately. Every assertEquals on an Outcome across this test suite
        // depends on it, and a refactor to a plain class would make all of them compare by
        // identity and fail in a way that looks like a logic bug.
        assertEquals(Outcome.Success(1), Outcome.Success(1))
        assertEquals(Outcome.Failure("e"), Outcome.Failure("e"))
        assertFalse(Outcome.Success(1) == Outcome.Success(2))
    }
}
