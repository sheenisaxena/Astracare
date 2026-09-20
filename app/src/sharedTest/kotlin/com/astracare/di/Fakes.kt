package com.astracare.di

import com.astracare.core.domain.remote.PullOutcome
import com.astracare.core.domain.remote.PushOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.remote.SyncCursor
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.domain.repository.SyncStateRepository
import com.astracare.core.domain.sync.SyncScheduler
import com.astracare.core.model.AuditAction
import com.astracare.core.model.AuditEntry
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.CaptureDraft
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rest of the data layer, as in-memory doubles.
 *
 * All `@Singleton`, because a UI test's whole point is that what one screen writes another
 * screen reads. A new instance per injection would make the capture screen and the history
 * screen talk to different stores, and the end-to-end test would fail for a reason that has
 * nothing to do with the app.
 *
 * Deliberately behavioural rather than empty. [FakeSessionRepository] really switches roles, so
 * the permission gating is exercised; [FakeAuditRepository] really appends, so a save that
 * should write a trail entry does. A stub that returned defaults would let the test pass
 * against an app that had stopped doing any of it.
 */

@Singleton
class FakeDraftRepository @Inject constructor() : DraftRepository {

    private var stored: CaptureDraft? = null

    override suspend fun load(): CaptureDraft? = stored

    override suspend fun save(draft: CaptureDraft) {
        stored = draft
    }

    override suspend fun clear() {
        stored = null
    }
}

@Singleton
class FakeSessionRepository @Inject constructor() : SessionRepository {

    private val role = MutableStateFlow(UserRole.Default)

    override fun observeActiveRole(): Flow<UserRole> = role

    override suspend fun activeRole(): UserRole = role.value

    override suspend fun setActiveRole(role: UserRole) {
        this.role.value = role
    }
}

@Singleton
class FakeAuditRepository @Inject constructor() : AuditRepository {

    private val entries = MutableStateFlow<List<AuditEntry>>(emptyList())

    override suspend fun append(
        actor: UserRole,
        action: AuditAction,
        recordId: BeneficiaryId?,
        at: Timestamp,
    ) {
        // Mirrors `autoGenerate`: the store assigns a monotonic id, which is what the audit
        // screen keys its list by.
        entries.value = entries.value + AuditEntry(
            id = entries.value.size + 1L,
            actor = actor,
            action = action,
            recordId = recordId,
            at = at,
        )
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> = entries
}

@Singleton
class FakeSyncStateRepository @Inject constructor() : SyncStateRepository {

    private var cursor: SyncCursor? = null

    override suspend fun lastPullCursor(): SyncCursor? = cursor

    override suspend fun recordPullCursor(cursor: SyncCursor) {
        this.cursor = cursor
    }
}

/**
 * A server that is never reached.
 *
 * Every call fails transiently, which is the honest shape for a UI test: the sync engine is not
 * under test here, and a fake that accepted records would have the history list quietly flip to
 * SYNCED mid-assertion. A transient failure leaves records PENDING, which is what a health
 * worker with no signal actually sees — and it is the state the screen most needs to render
 * correctly.
 */
@Singleton
class FakeRemoteBeneficiarySource @Inject constructor() : RemoteBeneficiarySource {

    override suspend fun push(beneficiary: Beneficiary): PushOutcome =
        PushOutcome.TransientFailure(cause = null)

    override suspend fun pullChangedSince(cursor: SyncCursor?): PullOutcome =
        PullOutcome.TransientFailure(cause = null)
}

/**
 * Records nothing and schedules nothing.
 *
 * `SyncScheduler` is fire-and-forget by design — neither method returns anything — so there is
 * nothing for a UI test to assert here, and the interaction that matters is covered by
 * `SyncSchedulingTest` with MockK.
 */
@Singleton
class NoOpSyncScheduler @Inject constructor() : SyncScheduler {
    override fun requestSync() = Unit
    override fun ensurePeriodicSync() = Unit
}
