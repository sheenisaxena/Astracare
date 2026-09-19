package com.astracare.core.domain.remote

import com.astracare.core.model.Beneficiary

/**
 * The server, as the sync engine needs to see it.
 *
 * ## Why this is a separate interface from BeneficiaryRepository
 *
 * `BeneficiaryRepository`'s documentation says plainly that nothing in it touches the network,
 * and that is still true — it is the local store, and its `upsert` returns the moment the
 * database has the record. This is the other half, and keeping them apart is what makes the
 * offline-first claim checkable rather than asserted: one interface cannot reach the network,
 * the other is the only thing that can.
 *
 * ## Why the domain owns it
 *
 * Pushing pending records is the sync algorithm, and the algorithm is a domain decision — what
 * to retry, what to give up on, what must never be marked as sent. `PushPendingRecordsUseCase`
 * therefore needs this type, so it lives here and `:core:data` implements it. The domain still
 * knows nothing about *how* the call is made; `Beneficiary` in, [PushOutcome] out, no HTTP
 * status codes, no headers, no serialisation.
 *
 * ## Scope boundary
 *
 * The implementation is a mock. A real backend is unbounded work that would demonstrate
 * nothing about Android engineering, and that was decided during planning rather than
 * discovered when time ran out. What matters is that the mock fails in the shapes a real
 * server fails in — see `MockRemoteBeneficiarySource`.
 */
interface RemoteBeneficiarySource {

    /**
     * Sends one record.
     *
     * **Must be idempotent.** The response to a successful write can be lost — the connection
     * drops after the server commits but before the client hears back — and the only safe
     * behaviour then is to send it again. That is only safe if the second send is a no-op,
     * which it is because record IDs are minted on the device: the server upserts by a key it
     * did not choose. A server assigning its own IDs would create a duplicate record for
     * every lost response, and the health worker would have no way to tell which was real.
     */
    suspend fun push(beneficiary: Beneficiary): PushOutcome
}

/**
 * What the server said.
 *
 * Three cases, and the distinction between the last two is the one that matters: it decides
 * whether the record is tried again. Getting it wrong in one direction retries a record
 * forever; in the other, it silently abandons field data.
 */
sealed interface PushOutcome {

    /** The server has the record. Safe to mark synced — subject to the check in the use case. */
    data object Accepted : PushOutcome

    /**
     * The server refused, and would refuse again — a validation error, a rejected schema, an
     * identifier it will not accept. Retrying is pointless, so the record is marked
     * [com.astracare.core.model.SyncStatus.REJECTED] and left for a person.
     *
     * [reason] is diagnostic. It is the server's words, in whatever language the server
     * speaks, so it belongs in a log and not on a screen — the same rule that keeps
     * `ValidationError` free of message strings.
     */
    data class Rejected(val reason: String) : PushOutcome

    /**
     * The attempt failed for a reason that may not recur — no connectivity, a timeout, a 5xx.
     * The record stays pending and the whole pass is retried later with backoff.
     */
    data class TransientFailure(val cause: Throwable?) : PushOutcome
}
