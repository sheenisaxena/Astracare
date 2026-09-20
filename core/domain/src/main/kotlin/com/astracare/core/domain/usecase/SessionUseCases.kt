package com.astracare.core.domain.usecase

import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.access.Capability
import com.astracare.core.domain.access.RolePermissions
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.model.AuditAction
import com.astracare.core.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * What the current role is allowed to do, as a value the UI can render.
 *
 * The screen asks for *permissions*, not for a role. That is the whole point of the
 * indirection: a composable written as `if (role == SUPERVISOR)` embeds a second copy of the
 * permission matrix in the UI layer, and the two drift the first time a role is added. A
 * composable written as `if (permissions.canCaptureRecord)` cannot.
 *
 * It re-emits on every role change, so switching roles re-gates the screen without anything
 * having to remember to refresh.
 */
class ObservePermissionsUseCase @Inject constructor(
    private val session: SessionRepository,
) {
    operator fun invoke(): Flow<Permissions> =
        session.observeActiveRole().map(::Permissions)
}

/**
 * The permission matrix resolved for one role.
 *
 * Computed properties rather than stored booleans, so this cannot fall out of step with
 * [RolePermissions] — there is only ever one answer and it comes from one place.
 */
data class Permissions(val role: UserRole) {

    val canCaptureRecord: Boolean get() = allows(Capability.CAPTURE_RECORD)

    val canViewAuditTrail: Boolean get() = allows(Capability.VIEW_AUDIT_TRAIL)

    private fun allows(capability: Capability) = RolePermissions.allows(role, capability)
}

/**
 * Changes the active role and writes the change down.
 *
 * The audit entry is the reason this is a use case and not a call straight to the repository.
 * A role that can be switched by whoever is holding the phone makes every other entry in the
 * trail ambiguous — "the supervisor did this" means nothing if the worker can become the
 * supervisor between one action and the next. Recording the switch is the only mitigation
 * available without a server, and it only works if it is impossible to switch without it.
 *
 * Logged against the role being **left**, not the one being taken. "FIELD_WORKER changed role"
 * is the true statement; attributing the switch to the new role would let someone become a
 * supervisor and have the trail say a supervisor authorised it.
 */
class SwitchRoleUseCase @Inject constructor(
    private val session: SessionRepository,
    private val audit: AuditRepository,
    private val timeProvider: TimeProvider,
) {
    suspend operator fun invoke(role: UserRole) {
        val previous = session.activeRole()
        if (previous == role) return

        session.setActiveRole(role)
        audit.append(
            actor = previous,
            action = AuditAction.ROLE_CHANGED,
            recordId = null,
            at = timeProvider.now(),
        )
    }
}

/**
 * The audit trail for the screen that displays it.
 *
 * No permission check here, and that is deliberate rather than an omission: a use case that
 * refused to read would make the ViewModel handle an error case for a screen that is only
 * reachable when the permission is held. The gate is on the navigation and the affordance —
 * see `BeneficiaryListScreen` — which is where a UX-level control belongs. DECISION_LOG 11.2
 * covers where the line between "hide it" and "refuse it" falls and why the two differ.
 */
class ObserveAuditTrailUseCase @Inject constructor(
    private val audit: AuditRepository,
) {
    operator fun invoke() = audit.observeRecent(RECENT_LIMIT)

    private companion object {
        /**
         * Enough to cover a working day's activity on one handset, few enough to render in one
         * list without paging. A number to revisit with a real deployment's volume, not by
         * guessing twice.
         */
        const val RECENT_LIMIT = 200
    }
}
