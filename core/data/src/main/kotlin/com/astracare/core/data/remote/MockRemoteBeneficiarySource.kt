package com.astracare.core.data.remote

import android.util.Log
import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.remote.PullOutcome
import com.astracare.core.domain.remote.PushOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.remote.SyncCursor
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A stand-in server, kept honest.
 *
 * A real backend was ruled out during planning: it is unbounded work that demonstrates nothing
 * about Android engineering, and the README states it as a scope boundary rather than leaving
 * it to be noticed. What a mock still has to do is **fail in the shapes a real server fails
 * in**, because those shapes are what the sync engine is built around. A stub that always
 * succeeds would let every error branch ship untested, which is worse than no mock — it looks
 * like coverage.
 *
 * So this one does five things a real server does:
 *
 *  - **Accepts idempotently.** Pushing a record the server already holds is a no-op, not a
 *    duplicate. That is only possible because IDs are minted on the device, and it is what
 *    makes retrying a lost response safe.
 *  - **Rejects permanently.** A record with no village is refused, and refused again. The
 *    rule is arbitrary; the *shape* — a 4xx that retrying cannot fix — is not.
 *  - **Fails transiently.** Every [TRANSIENT_FAILURE_EVERY]th call fails as if the connection
 *    dropped, so retry and exponential backoff are exercised on a real device rather than
 *    only in tests.
 *  - **Serves a delta.** A pull returns only what has changed since the caller's cursor, and
 *    the cursor is an opaque sequence number the client cannot compute for itself.
 *  - **Changes on its own.** Day 14. Every [SERVER_EDIT_EVERY]th pull edits a record
 *    server-side, as another health worker's handset would. Without this the pull would only
 *    ever hand back what this device pushed, and `SyncStatus.CONFLICTED` would be unreachable
 *    outside the unit tests.
 *
 * Both counters are deterministic rather than random. A random mock produces a demo that
 * sometimes misbehaves and a bug report nobody can reproduce; a counter produces the same
 * sequence every run.
 *
 * ## The server's clock is not the device's
 *
 * [serverSequence] is seeded from [TimeProvider] once and then advanced by this class alone.
 * It would have been simpler to stamp server-side edits with `timeProvider.now()`, and that
 * would have modelled a server whose clock agrees with the handset's exactly — hiding the one
 * condition the whole conflict design is built around. A mock that cannot reproduce the
 * problem is not a test of the solution.
 *
 * `delay` simulates a round trip, which is what makes the PENDING chip visible on screen long
 * enough to see it flip. Without it the local database and the "server" are the same
 * microsecond and the sync is invisible.
 */
@Singleton
class MockRemoteBeneficiarySource @Inject constructor(
    private val timeProvider: TimeProvider,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : RemoteBeneficiarySource {

    /**
     * Everything the fake server holds, keyed by the ID the device chose.
     *
     * A `Mutex` rather than a synchronized block: the periodic worker and a save-triggered
     * push can overlap, and this must not block the thread while it waits.
     */
    private val mutex = Mutex()
    private val accepted = linkedMapOf<BeneficiaryId, StoredRecord>()

    private var pushCount = 0
    private var pullCount = 0

    /**
     * The server's own monotonic counter, used both as the change-stream position and as the
     * timestamp for server-side edits.
     *
     * Null until the first write. Seeded lazily from the device clock purely so the `updatedAt`
     * values a pull hands back look plausible on a screen that renders them as dates — after
     * that it advances on its own and never reads the device clock again.
     */
    private var serverSequence: Long? = null

    override suspend fun push(beneficiary: Beneficiary): PushOutcome = withContext(ioDispatcher) {
        delay(ROUND_TRIP_MS)

        mutex.withLock {
            pushCount++

            when {
                pushCount % TRANSIENT_FAILURE_EVERY == 0 -> {
                    Log.d(TAG, "Simulated connection failure for ${beneficiary.id.value}")
                    PushOutcome.TransientFailure(IOException("simulated connection failure"))
                }

                beneficiary.village.isBlank() -> {
                    PushOutcome.Rejected("village is required")
                }

                else -> {
                    // Upsert, not insert. The second delivery of a record the server already
                    // has must be accepted silently — that is what makes it safe for the
                    // client to retry when a response goes missing.
                    //
                    // The client's `updatedAt` is stored as-is rather than restamped: it is
                    // when the record was *edited*, which the capturing device knows and the
                    // server does not. Restamping it would make every pull look like a change
                    // and rewrite the whole table on every pass.
                    val isRedelivery = accepted.put(
                        beneficiary.id,
                        StoredRecord(beneficiary.copy(syncStatus = SyncStatus.SYNCED), nextSequence()),
                    ) != null
                    if (isRedelivery) {
                        Log.d(TAG, "Idempotent re-accept of ${beneficiary.id.value}")
                    }
                    PushOutcome.Accepted
                }
            }
        }
    }

    override suspend fun pullChangedSince(cursor: SyncCursor?): PullOutcome =
        withContext(ioDispatcher) {
            delay(ROUND_TRIP_MS)

            mutex.withLock {
                pullCount++

                if (pullCount % TRANSIENT_FAILURE_EVERY == 0) {
                    Log.d(TAG, "Simulated connection failure on pull")
                    PullOutcome.TransientFailure(IOException("simulated connection failure"))
                } else {
                    simulateAnotherWorkersEdit()
                    changesSince(cursor)
                }
            }
        }

    /**
     * Everything stored at a position after [cursor].
     *
     * A null cursor means "never pulled" and returns everything — which is also what an
     * upgrading device gets, by design; see `MIGRATION_2_3`.
     *
     * The returned cursor is the stream head rather than the highest position actually sent.
     * Those differ only when nothing changed, and using the head means an idle device does not
     * re-examine the same empty window forever.
     */
    private fun changesSince(cursor: SyncCursor?): PullOutcome.Changes {
        val since = cursor?.value?.toLongOrNull() ?: BEFORE_EVERYTHING

        return PullOutcome.Changes(
            records = accepted.values.filter { it.sequence > since }.map { it.record },
            nextCursor = SyncCursor(sequenceHead().toString()),
        )
    }

    /**
     * Edits the oldest record the server holds, as another handset syncing would.
     *
     * The oldest rather than a random one, so the demo is reproducible, and the *village* field
     * rather than a measurement, because a supervisor correcting a place name while a health
     * worker corrects a weight is the concrete case the conflict design exists for.
     *
     * Note what this does NOT do: it does not know or care whether the local device has an
     * unsent edit for the same record. Whether the result is applied silently or flagged is
     * `ConflictResolver`'s decision, made from local state the server cannot see — which is
     * the property being demonstrated.
     */
    private fun simulateAnotherWorkersEdit() {
        if (pullCount % SERVER_EDIT_EVERY != 0) return

        val oldest = accepted.values.firstOrNull() ?: return
        val sequence = nextSequence()
        val edited = oldest.record.copy(
            village = "${oldest.record.village} (corrected)",
            // The server's sequence doubles as its clock, so the edit carries a timestamp the
            // handset did not produce. That is the point: two clocks, and no coordination.
            updatedAt = Timestamp(sequence),
        )
        accepted[edited.id] = StoredRecord(edited, sequence)
        Log.d(TAG, "Simulated a server-side edit to ${edited.id.value}")
    }

    /** The current head of the change stream, seeding it from the device clock on first use. */
    private fun sequenceHead(): Long =
        serverSequence ?: timeProvider.now().epochMillis.also { serverSequence = it }

    private fun nextSequence(): Long = (sequenceHead() + 1).also { serverSequence = it }

    /** A record plus the change-stream position it was written at. */
    private data class StoredRecord(val record: Beneficiary, val sequence: Long)

    private companion object {
        const val TAG = "MockRemote"

        /** Long enough that a sync is visible on screen, short enough not to be annoying. */
        const val ROUND_TRIP_MS = 300L

        /** Deterministic, so a failed call can be reproduced rather than waited for. */
        const val TRANSIENT_FAILURE_EVERY = 5

        /**
         * How often the "server" changes under the device's feet.
         *
         * Every third pull, so a conflict is reachable in a demo within a minute or two of
         * capturing records — but not so often that the list is permanently red.
         */
        const val SERVER_EDIT_EVERY = 3

        /** Lower than any sequence this server can mint, so a null cursor matches everything. */
        const val BEFORE_EVERYTHING = Long.MIN_VALUE
    }
}
