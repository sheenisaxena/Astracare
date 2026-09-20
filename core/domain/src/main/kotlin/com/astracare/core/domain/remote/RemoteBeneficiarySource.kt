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

    /**
     * Fetches everything that changed on the server since [cursor].
     *
     * A null [cursor] means "this device has never pulled" and asks for everything the server
     * holds for this worker. Added on Day 14.
     *
     * ## Why a cursor and not a timestamp
     *
     * The obvious signature is `pullChangedSince(since: Timestamp)`, and it has a failure mode
     * that is invisible until it loses data. The server would have to answer it by comparing
     * `since` against each record's `updatedAt` — and `updatedAt` is stamped by the *device*
     * that captured the record, from a wall clock the server does not control. A handset whose
     * clock is ten minutes slow pushes a record stamped ten minutes in the past; if another
     * device has already pulled past that point, the record falls behind the window and is
     * never delivered to anyone. Nothing errors. The record simply does not arrive.
     *
     * [SyncCursor] is opaque precisely so no client can be tempted to compare it against its
     * own clock, or to synthesise one. It is stored verbatim and handed back untouched — the
     * server decides what it means, which is the only party that can.
     *
     * **Must be repeatable.** Pulling twice with the same cursor must return the same records.
     * The client advances its stored cursor only after a whole pass has been applied, so a
     * crash mid-pass means the next pull re-delivers records it has already seen. That is safe
     * because resolution is idempotent — see `ConflictResolver` — but only if the server does
     * not treat a pull as consuming a queue.
     *
     * Records come back with [Beneficiary.syncStatus] set to
     * [com.astracare.core.model.SyncStatus.SYNCED]. That is not a placeholder: a record the
     * server is handing out is, by definition, a record the server has. What the *local* row
     * should end up marked as is the resolver's decision, not this one's.
     */
    suspend fun pullChangedSince(cursor: SyncCursor?): PullOutcome
}

/**
 * An opaque server-side position in the change stream.
 *
 * The client stores it, sends it back, and never interprets it. A sequence number, an etag, a
 * Postgres LSN, a page token — the client cannot tell and must not care. See
 * [RemoteBeneficiarySource.pullChangedSince] for why this is not a timestamp.
 *
 * A `value class` so it cannot be confused with the several other Strings in this area
 * (record IDs, status names) at no runtime cost.
 */
@JvmInline
value class SyncCursor(val value: String)

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

/**
 * What came back from a pull.
 *
 * Two cases, not three: a pull has nothing for the server to *reject*. It asks a question
 * rather than submitting data, so the only ways it can go are "here is the answer" and "ask
 * again later".
 */
sealed interface PullOutcome {

    /**
     * Everything that changed, plus where to resume from.
     *
     * [records] may be empty, and an empty pull is a perfectly successful one — it is what
     * almost every pull returns on a single-worker handset. [nextCursor] still advances, so the
     * next pull does not re-examine the same history.
     */
    data class Changes(
        val records: List<Beneficiary>,
        val nextCursor: SyncCursor,
    ) : PullOutcome

    /**
     * The pull did not happen — no connectivity, a timeout, a 5xx. The stored cursor is left
     * exactly where it was, so nothing is skipped; the pass is simply retried.
     */
    data class TransientFailure(val cause: Throwable?) : PullOutcome
}
