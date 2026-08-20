package com.astracare.core.model

/**
 * Type-safe identifier for a [Beneficiary].
 *
 * A `value class` rather than a bare `String`: it costs nothing at runtime (the compiler
 * erases it to the underlying String) but makes `save(id, villageName)` a compile error
 * instead of a silent bug. In a codebase where records, villages and workers all have String
 * ids, that mistake is otherwise inevitable.
 *
 * IDs are generated on the device, not the server. An offline-first client must be able to
 * create a record with no connectivity, so it cannot wait for a server-assigned key.
 */
@JvmInline
value class BeneficiaryId(val value: String)

/**
 * A single beneficiary record captured in the field.
 *
 * This is a pure domain model. It is deliberately NOT the Room entity and NOT the UI state:
 *
 * - The Room entity (`:core:data`) has persistence concerns — column names, indices, type
 *   converters — that must not leak into business logic.
 * - The UI state (`:feature:patients`) has presentation concerns — formatted strings,
 *   validation messages, loading flags.
 *
 * Keeping the three separate costs a mapping function each way. It buys the ability to change
 * the database schema without touching the UI, and vice versa. Collapsing them into one class
 * is the most common reason a "layered" codebase turns out not to be layered.
 */
data class Beneficiary(
    val id: BeneficiaryId,
    val name: String,
    val ageYears: Int,
    val village: String,
    val measurement: Measurement,
    /** When the health worker captured this record on the device. Never changes. */
    val recordedAt: Timestamp,
    /**
     * Last local modification. This is the field the sync engine compares against the
     * server's copy to detect a conflict, so it must be updated on every edit.
     */
    val updatedAt: Timestamp,
    val syncStatus: SyncStatus,
) {
    /**
     * Fields carrying personally identifying information.
     *
     * Named here so the encryption work has a single definition to reference rather than a
     * judgement call at each call site, and so an audit can answer "what is sensitive?" by
     * reading one place.
     */
    companion object {
        val PII_FIELDS = setOf("name", "village")
    }
}
