package com.astracare.core.domain.repository

import com.astracare.core.domain.remote.SyncCursor

/**
 * Where the last pull got to.
 *
 * Small enough to be tempting to fold into [BeneficiaryRepository], and deliberately not:
 * that interface is about beneficiary records, and this is about the sync engine's own
 * bookkeeping. They have different lifetimes too — clearing every record would be a sensible
 * "log out" operation, and it must not reset this cursor to null, because doing so would ask
 * the server to re-send its entire history.
 *
 * Both methods suspend and neither returns a [kotlinx.coroutines.flow.Flow]. Nothing observes
 * the cursor: it is read once at the start of a pull and written once at the end, by the same
 * caller. A Flow here would be observability nothing consumes.
 */
interface SyncStateRepository {

    /**
     * The cursor from the last fully applied pull, or null if this device has never pulled.
     *
     * Null is the normal state on first launch and means "send me everything".
     */
    suspend fun lastPullCursor(): SyncCursor?

    /**
     * Advances the cursor.
     *
     * Called only after every record in a pull has been resolved and written. Advancing it
     * earlier — as each record is applied, say — would mean a crash mid-pass left the cursor
     * past records that were never applied, and the server would never send them again.
     * Advancing it late costs one repeated pull after a crash, which is free because
     * `ConflictResolver` is idempotent.
     */
    suspend fun recordPullCursor(cursor: SyncCursor)
}
