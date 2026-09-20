package com.astracare.core.domain.repository

import com.astracare.core.model.UserRole
import kotlinx.coroutines.flow.Flow

/**
 * Which role this device is currently operating as.
 *
 * Its own repository rather than a field on [SyncStateRepository] or [BeneficiaryRepository],
 * because it is neither sync bookkeeping nor a beneficiary record, and because the lifetimes
 * differ: clearing every record is a sensible operation that must not change who the device
 * thinks it is, and switching role must not disturb the pull cursor.
 *
 * [observeActiveRole] is a `Flow` and [setActiveRole] is `suspend`, following the same rule as
 * everywhere else: the role is observed by the UI, which must re-gate itself the moment it
 * changes, and a write completes.
 */
interface SessionRepository {

    /**
     * The active role, starting from [UserRole.Default] on a device that has never set one.
     *
     * Never emits null. "No role yet" is not a state the UI can usefully render, and making it
     * one would mean every consumer handling a case that resolves itself in milliseconds.
     */
    fun observeActiveRole(): Flow<UserRole>

    /** Reads the role once, for callers that are making a decision rather than rendering. */
    suspend fun activeRole(): UserRole

    /**
     * Switches roles.
     *
     * The caller is responsible for writing the corresponding
     * [com.astracare.core.model.AuditAction.ROLE_CHANGED] entry — see `SwitchRoleUseCase`. It
     * is not done here, because a repository that also wrote an audit entry would be making
     * the decision that role changes are audit-worthy, and that is a domain rule.
     */
    suspend fun setActiveRole(role: UserRole)
}
