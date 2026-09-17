package com.astracare.core.domain.repository

import com.astracare.core.model.CaptureDraft

/**
 * Durable storage for the in-progress capture.
 *
 * ## Why this is not part of [BeneficiaryRepository]
 *
 * They look similar and they are not. [BeneficiaryRepository] holds records that the sync
 * engine will push to a server; this holds work that must never be pushed anywhere, because
 * it has not been validated and the health worker has not finished with it. A single
 * repository would mean one `observeAll()` that has to remember to exclude drafts, and the
 * first time someone forgot, a half-typed record would sync.
 *
 * ## Why read is suspend, not Flow
 *
 * The opposite of the choice made in [BeneficiaryRepository], and for a reason. Records are a
 * `Flow` because the background sync worker changes them behind the UI's back, so the screen
 * must be pushed updates it did not ask for. A draft has exactly one writer — the capture
 * screen that is currently open — so there is nothing to observe. It is read once, when the
 * screen opens.
 *
 * Observing it would be actively wrong: the screen would receive its own autosaves back and
 * have to filter them out, and any bug in that filtering shows up as the cursor jumping while
 * someone types.
 *
 * ## Why no error type
 *
 * [BeneficiaryRepository.upsert] returns an `Outcome` because a failed record write is
 * something the health worker must be told about. A failed autosave is not: it is a
 * best-effort convenience, retried a few hundred milliseconds later on the next keystroke,
 * and interrupting someone mid-form to report it would be worse than the failure. The
 * implementation swallows and logs; see DECISION_LOG 6.4.
 */
interface DraftRepository {

    /** The stored draft, or null if there is none. */
    suspend fun load(): CaptureDraft?

    /** Stores [draft], replacing any previous one. */
    suspend fun save(draft: CaptureDraft)

    /** Removes the stored draft. Safe to call when there is none. */
    suspend fun clear()
}
