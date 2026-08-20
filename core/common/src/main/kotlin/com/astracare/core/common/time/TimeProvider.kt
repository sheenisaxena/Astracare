package com.astracare.core.common.time

import com.astracare.core.model.Timestamp

/**
 * Supplies the current time.
 *
 * A one-method interface rather than calling `System.currentTimeMillis()` at the point of use.
 * The reason is testability, and here it is unusually important: this app's core feature is
 * conflict resolution by timestamp comparison. Questions like "does editing a synced record
 * re-queue it?" and "is the newer version kept?" are *entirely* about what the clock returned,
 * and a hard-coded system call makes them untestable without sleeping.
 *
 * A `fun interface` so a test can supply one with a lambda:
 * ```
 * val fixed = TimeProvider { Timestamp(1_000L) }
 * ```
 */
fun interface TimeProvider {
    fun now(): Timestamp
}

/**
 * Real implementation, backed by the system wall clock.
 *
 * Note the caveat that matters for sync: wall-clock time can jump — the user changes the
 * device clock, or NTP corrects it. Conflict resolution based on client timestamps is
 * therefore best-effort, and that limitation belongs in the decision log rather than being
 * quietly ignored.
 */
class SystemTimeProvider : TimeProvider {
    override fun now(): Timestamp = Timestamp(System.currentTimeMillis())
}
