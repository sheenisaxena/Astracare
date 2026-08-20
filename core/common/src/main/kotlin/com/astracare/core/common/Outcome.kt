package com.astracare.core.common

/**
 * Result of an operation that can fail in a way the caller is expected to handle.
 *
 * Named `Outcome` rather than `Result` to avoid shadowing Kotlin's built-in `kotlin.Result`,
 * which would force an import alias at every call site. `kotlin.Result` is also a poor fit
 * here: it carries a `Throwable`, and these failures are expected business states — an
 * out-of-range weight is not an exceptional condition, it is Tuesday.
 *
 * Errors are a typed [E] rather than exceptions so the compiler can check that every failure
 * mode is handled. An exception thrown from a use case is invisible to the type system, and
 * the caller finds out about it in production.
 */
sealed interface Outcome<out T, out E> {

    data class Success<T>(val value: T) : Outcome<T, Nothing>

    data class Failure<E>(val error: E) : Outcome<Nothing, E>

    val isSuccess: Boolean get() = this is Success

    companion object {
        fun success(): Outcome<Unit, Nothing> = Success(Unit)
    }
}

/** Returns the value on success, or null on failure. */
fun <T, E> Outcome<T, E>.getOrNull(): T? = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> null
}

/** Transforms the success value, leaving a failure untouched. */
inline fun <T, R, E> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}
