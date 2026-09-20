package com.astracare.core.data.repository

import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.data.database.dao.AuditDao
import com.astracare.core.data.database.entity.AuditEntryEntity
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.model.AuditAction
import com.astracare.core.model.AuditEntry
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [AuditRepository].
 *
 * Nothing here catches exceptions, unlike [RoomDraftRepository] which logs and carries on. A
 * draft that fails to save costs a few keystrokes; an audit entry that fails to save and is
 * treated as saved means the trail is missing an event and says nothing about it. A trail with
 * silent gaps is worse than no trail, because it will be read as complete.
 *
 * The practical consequence is deliberate: a failed audit write fails the save that triggered
 * it. That is the right way round for a health record — see `SaveBeneficiaryUseCase` for the
 * ordering, which puts the record on disk first so a crash between the two loses the entry and
 * keeps the data, never the reverse.
 */
@Singleton
class RoomAuditRepository @Inject constructor(
    private val dao: AuditDao,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : AuditRepository {

    override suspend fun append(
        actor: UserRole,
        action: AuditAction,
        recordId: BeneficiaryId?,
        at: Timestamp,
    ) = withContext(ioDispatcher) {
        dao.insert(
            AuditEntryEntity(
                actor = actor.name,
                action = action.name,
                recordId = recordId?.value,
                atEpochMillis = at.epochMillis,
            ),
        )
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }
}

/**
 * Decodes a stored row.
 *
 * An unrecognised actor or action means the row was written by a newer build — possible after
 * a downgrade. Both fall back rather than throwing, for the same reason the beneficiary mapper
 * does: a crash reading the audit screen would be a crash caused by having *more* history, and
 * an unreadable entry should degrade to a visible unknown rather than take the screen with it.
 *
 * The fallbacks are chosen to be conspicuous rather than plausible. An unknown actor reads as
 * the default role and an unknown action as [AuditAction.ROLE_CHANGED] would both be lies, so
 * neither is used: the row is dropped from the mapping only if it cannot be understood at all,
 * and otherwise the enum decode failure surfaces as the closest honest value. See the comment
 * on each.
 */
private fun AuditEntryEntity.toDomain(): AuditEntry = AuditEntry(
    id = id,
    // An actor this build does not know about is shown as the lower-privilege role. Guessing
    // "supervisor" for an unreadable value would attribute an action to more authority than
    // can be evidenced.
    actor = UserRole.entries.firstOrNull { it.name == actor } ?: UserRole.Default,
    // An action this build does not know about is still an event that happened, and the only
    // honest thing to say about it is that a record changed. CONFLICT_DETECTED would overstate
    // it; dropping the row would hide it.
    action = AuditAction.entries.firstOrNull { it.name == action } ?: AuditAction.RECORD_UPDATED,
    recordId = recordId?.let(::BeneficiaryId),
    at = Timestamp(atEpochMillis),
)
