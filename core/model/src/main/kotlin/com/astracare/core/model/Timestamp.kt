package com.astracare.core.model

/**
 * A point in time, as milliseconds since the Unix epoch (UTC).
 *
 * ## Why not a date-time library
 *
 * This domain only ever does two things with time: **compare** two instants (which of these
 * two versions of a record is newer?) and **serialise** them. Epoch milliseconds do both
 * exactly, and it is already the wire format the server would use.
 *
 * Everything a date-time library adds — time zones, calendar arithmetic, formatting — is a
 * *presentation* concern, and belongs in the UI layer where the device locale is known. A
 * health worker's screen should show "2 hours ago" in their language; the domain has no
 * business knowing that.
 *
 * Concretely this avoids: `java.time` (needs core library desugaring below API 26, and minSdk
 * here is 24), and `kotlinx-datetime` 0.7.x (its `Instant` is a typealias to the experimental
 * `kotlin.time.Instant`, which forces `@OptIn` through every model and cannot be resolved by
 * Dagger's annotation processor). `:core:model` now has zero dependencies.
 *
 * ## Why a value class rather than a bare Long
 *
 * `updatedAt > recordCount` compiles if both are `Long`. It does not compile here. The wrapper
 * is erased at runtime, so this costs nothing.
 *
 * [Comparable] is implemented because comparison is the entire point — the sync engine's
 * conflict detection is `local.updatedAt > remote.updatedAt`, and that should read plainly.
 */
@JvmInline
value class Timestamp(val epochMillis: Long) : Comparable<Timestamp> {

    override fun compareTo(other: Timestamp): Int =
        epochMillis.compareTo(other.epochMillis)

    override fun toString(): String = "Timestamp($epochMillis)"
}
