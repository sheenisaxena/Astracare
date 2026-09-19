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

    /**
     * The server refused this record and will refuse it again.
     *
     * Added on Day 13, and it is worth saying why [FAILED] would not do. FAILED means "try
     * again later", so a record marked FAILED is re-pushed on every sync pass — forever, if
     * the server's objection is permanent. A 4xx is the server saying the record is wrong,
     * not that the moment was. Conflating the two produces a handset that retries a rejected
     * record until the battery dies.
     *
     * Distinct from [CONFLICTED] too: a conflict means the server holds a *newer* version of
     * a record it accepts. A rejection means it will not accept this record at all. Both need
     * a human, but they need different humans doing different things.
     */
    REJECTED,
}
