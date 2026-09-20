package com.astracare.core.domain.usecase

import com.astracare.core.common.Outcome
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.access.Capability
import com.astracare.core.domain.access.RolePermissions
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.domain.validation.BeneficiaryValidator
import com.astracare.core.domain.validation.ValidationError
import com.astracare.core.model.AuditAction
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.UserRole
import javax.inject.Inject

/**
 * Validates a record and saves it locally, marking it for sync.
 *
 * A use case earns its place when it does something the repository should not. Here that is
 * five things: checking the active role may capture at all, enforcing validation, stamping
 * [Beneficiary.updatedAt], resetting [SyncStatus] on edit, and writing the audit entry. A
 * repository that also did those would be making business decisions, and a ViewModel that did
 * them would have to repeat them at every call site.
 *
 * [TimeProvider] is injected rather than calling the system clock directly, so a test can fix
 * "now" and assert exact timestamps instead of sleeping.
 *
 * ## Why the permission is checked here as well as in the UI
 *
 * The capture screen is not offered to a supervisor, so in normal operation this check never
 * fires — and it is still worth having. Hiding a button and refusing an action are different
 * guarantees. The button is absent; the *capability* is what stops a save arriving from a
 * restored back stack, a process-death resurrection with stale UI, or a role switched in the
 * moment between the form opening and the save completing. Without the check the app would
 * complete an action its own interface says is unavailable, which is a correctness bug
 * whatever the security story is.
 *
 * It is emphatically **not** security. The role is on the device and the device belongs to the
 * user, who can change it. DECISION_LOG 4.3 said that before any of this was written and 11.2
 * says it again next to the code.
 *
 * ## Why the audit write is not transactional with the save
 *
 * The record lands first and the entry follows, so a crash between them loses the entry and
 * keeps the record. The reverse ordering would lose a health worker's data to keep a log of
 * it. Room could wrap both in one transaction and that would mean this use case knowing about
 * the database, which is the layering the whole project is built to avoid — a real audit
 * requirement is served by the server recording what it receives, not by a client's own
 * bookkeeping. Recorded as an open item rather than solved with a transaction that reaches
 * through three layers.
 */
class SaveBeneficiaryUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
    private val session: SessionRepository,
    private val audit: AuditRepository,
    private val timeProvider: TimeProvider,
) {

    suspend operator fun invoke(beneficiary: Beneficiary): Outcome<Unit, SaveError> {
        val role = session.activeRole()
        val violations = BeneficiaryValidator.validate(beneficiary)

        // The two refusals are evaluated together rather than as early returns, so this reads
        // as a list of the reasons a save does not happen. Order matters: a supervisor typing
        // an invalid age should be told they cannot capture, not asked to fix the age.
        return when {
            !RolePermissions.allows(role, Capability.CAPTURE_RECORD) ->
                Outcome.Failure(SaveError.NotPermitted(role))

            violations.isNotEmpty() -> Outcome.Failure(SaveError.Invalid(violations))

            else -> persist(beneficiary, role)
        }
    }

    private suspend fun persist(
        beneficiary: Beneficiary,
        role: UserRole,
    ): Outcome<Unit, SaveError> {
        // Read before writing, so the audit entry can say created or updated rather than the
        // uninformative "saved". Uses the one-shot read added for the pull on Day 14.
        val existed = repository.findById(beneficiary.id) != null
        val now = timeProvider.now()

        // Any edit invalidates a previous sync: the server's copy is now out of date, so the
        // record must be re-queued. Leaving it SYNCED would silently drop the change.
        val stamped = beneficiary.copy(updatedAt = now, syncStatus = SyncStatus.PENDING)

        return when (val result = repository.upsert(stamped)) {
            is Outcome.Success -> {
                audit.append(
                    actor = role,
                    action = if (existed) AuditAction.RECORD_UPDATED else AuditAction.RECORD_CREATED,
                    recordId = beneficiary.id,
                    at = now,
                )
                Outcome.success()
            }

            is Outcome.Failure -> Outcome.Failure(SaveError.Storage(result.error))
        }
    }
}

/**
 * Why a save failed.
 *
 * Validation and storage failures are separate cases because the caller responds differently:
 * validation errors belong on the form fields, a storage error is a message and a retry.
 */
sealed interface SaveError {

    data class Invalid(val violations: List<ValidationError>) : SaveError

    data class Storage(val error: RepositoryError) : SaveError

    /**
     * The active role may not capture records.
     *
     * Unreachable through the UI, which does not offer the capture screen to a role that
     * cannot use it. It is a case rather than an exception because "unreachable" is a claim
     * about today's navigation, and a screen that gets deep-linked or restored tomorrow should
     * meet a value the caller can render, not a crash.
     */
    data class NotPermitted(val role: UserRole) : SaveError
}
