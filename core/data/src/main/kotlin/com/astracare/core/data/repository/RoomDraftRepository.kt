package com.astracare.core.data.repository

import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.common.log.Logger
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.data.database.dao.DraftDao
import com.astracare.core.data.database.entity.DraftEntity
import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.model.CaptureDraft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Room-backed [DraftRepository].
 *
 * ## Why failures are swallowed here and nowhere else
 *
 * `OfflineFirstBeneficiaryRepository` returns an `Outcome` and lets the caller decide, because
 * a record that failed to save is something the health worker must be told about immediately.
 * Autosave is the opposite: it is a best-effort convenience that runs every few hundred
 * milliseconds, and surfacing a failure would interrupt someone mid-form to report a problem
 * the next keystroke will retry anyway.
 *
 * "Swallowed" is doing real work in that sentence, so it is worth being precise: the exception
 * is caught and **logged**, not discarded. detekt's `SwallowedException` rule is active on
 * this project precisely because a silent catch in persistence code is how data loss becomes
 * invisible, and this is the one place the trade-off is worth making — with a log line so it
 * is still diagnosable.
 *
 * The `Logger` seam arrived on Day 19 and replaced the direct `android.util.Log` calls this
 * class shipped with. It was not the diagnostic need that forced it — it was that a class
 * calling `android.util.Log` cannot have a plain JVM unit test.
 *
 * ## Why the dispatcher is injected
 *
 * Room's suspend DAOs already move off the main thread, so `withContext` is not what makes
 * this safe — the mapping either side of the call is what would otherwise run on the caller's
 * thread. Injecting the dispatcher rather than naming `Dispatchers.IO` is what lets a test
 * drive this on a `TestDispatcher` with virtual time instead of a real thread pool it cannot
 * await.
 */
class RoomDraftRepository @Inject constructor(
    private val dao: DraftDao,
    private val logger: Logger,
    private val timeProvider: TimeProvider,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DraftRepository {

    override suspend fun load(): CaptureDraft? = withContext(ioDispatcher) {
        runCatching { dao.load()?.toDraft() }
            .onFailure { logger.warn(TAG, "Could not read the saved draft; starting from an empty form", it) }
            .getOrNull()
    }

    override suspend fun save(draft: CaptureDraft) {
        withContext(ioDispatcher) {
            runCatching { dao.upsert(draft.toEntity(timeProvider.now().epochMillis)) }
                .onFailure { logger.warn(TAG, "Could not autosave the draft; will retry on the next edit", it) }
        }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            runCatching { dao.clear() }
                .onFailure { logger.warn(TAG, "Could not clear the saved draft", it) }
        }
    }

    private companion object {
        const val TAG = "DraftRepository"
    }
}

/**
 * Entity-to-domain mapping, kept here rather than in the shared mapper file because these two
 * types exist only for drafts and nothing else needs the translation.
 */
private fun DraftEntity.toDraft(): CaptureDraft = CaptureDraft(
    name = name,
    ageYears = ageYears,
    village = village,
    weightKg = weightKg,
    heightCm = heightCm,
    muacMm = muacMm,
)

private fun CaptureDraft.toEntity(updatedAtEpochMillis: Long): DraftEntity = DraftEntity(
    name = name,
    ageYears = ageYears,
    village = village,
    weightKg = weightKg,
    heightCm = heightCm,
    muacMm = muacMm,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
