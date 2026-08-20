package com.astracare.core.model

/**
 * Where a record stands with respect to the server.
 *
 * This lives on the domain model rather than only in the database because it is genuinely
 * domain state, not a storage detail: the UI shows it to the health worker, who needs to know
 * whether the record they just captured is safe on the server or still only on the handset.
 * In a low-connectivity setting that distinction is the difference between trusting the app
 * and writing on paper as well.
 */
enum class SyncStatus {
    /** Created or edited locally, not yet accepted by the server. */
    PENDING,

    /** Server has acknowledged this exact version. */
    SYNCED,

    /**
     * The server holds a newer version of this record than the local edit was based on.
     * Requires resolution rather than a retry — a retry would silently discard one side.
     */
    CONFLICTED,

    /** Sync was attempted and failed for a reason retrying may fix (network, 5xx). */
    FAILED,
}
