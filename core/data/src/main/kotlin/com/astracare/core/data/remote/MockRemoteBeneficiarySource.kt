package com.astracare.core.data.remote

import android.util.Log
import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.domain.remote.PushOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
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
 * So this one does three things a real server does:
 *
 *  - **Accepts idempotently.** Pushing a record the server already holds is a no-op, not a
 *    duplicate. That is only possible because IDs are minted on the device, and it is what
 *    makes retrying a lost response safe.
 *  - **Rejects permanently.** A record with no village is refused, and refused again. The
 *    rule is arbitrary; the *shape* — a 4xx that retrying cannot fix — is not.
 *  - **Fails transiently.** Every [TRANSIENT_FAILURE_EVERY]th call fails as if the connection
 *    dropped, so retry and exponential backoff are exercised on a real device rather than
 *    only in tests.
 *
 * The transient failure is deterministic rather than random. A random mock produces a demo
 * that sometimes misbehaves and a bug report nobody can reproduce; a counter produces the same
 * sequence every run.
 *
 * `delay` simulates a round trip, which is what makes the PENDING chip visible on screen long
 * enough to see it flip. Without it the local database and the "server" are the same
 * microsecond and the sync is invisible.
 */
@Singleton
class MockRemoteBeneficiarySource @Inject constructor(
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : RemoteBeneficiarySource {

    /**
     * Everything the fake server holds, keyed by the ID the device chose.
     *
     * A `Mutex` rather than a synchronized block: the periodic worker and a save-triggered
     * push can overlap, and this must not block the thread while it waits.
     */
    private val mutex = Mutex()
    private val accepted = mutableMapOf<BeneficiaryId, Beneficiary>()
    private var callCount = 0

    override suspend fun push(beneficiary: Beneficiary): PushOutcome = withContext(ioDispatcher) {
        delay(ROUND_TRIP_MS)

        mutex.withLock {
            callCount++

            when {
                callCount % TRANSIENT_FAILURE_EVERY == 0 -> {
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
                    val isRedelivery = accepted.put(beneficiary.id, beneficiary) != null
                    if (isRedelivery) {
                        Log.d(TAG, "Idempotent re-accept of ${beneficiary.id.value}")
                    }
                    PushOutcome.Accepted
                }
            }
        }
    }

    private companion object {
        const val TAG = "MockRemote"

        /** Long enough that a sync is visible on screen, short enough not to be annoying. */
        const val ROUND_TRIP_MS = 300L

        /** Deterministic, so a failed push can be reproduced rather than waited for. */
        const val TRANSIENT_FAILURE_EVERY = 5
    }
}
