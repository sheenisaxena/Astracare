package com.astracare.core.domain.usecase

import com.astracare.core.common.Outcome
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.validation.BeneficiaryValidator
import com.astracare.core.domain.validation.ValidationError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.SyncStatus
import javax.inject.Inject

/**
 * Validates a record and saves it locally, marking it for sync.
 *
 * A use case earns its place when it does something the repository should not. Here that is
 * three things: enforcing validation, stamping [Beneficiary.updatedAt], and resetting
 * [SyncStatus] on edit. A repository that also did those would be making business decisions,
 * and a ViewModel that did them would have to repeat them at every call site.
 *
 * [TimeProvider] is injected rather than calling the system clock directly, so a test can fix
 * "now" and assert exact timestamps instead of sleeping.
 */
class SaveBeneficiaryUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
    private val timeProvider: TimeProvider,
) {

    suspend operator fun invoke(beneficiary: Beneficiary): Outcome<Unit, SaveError> {
        val violations = BeneficiaryValidator.validate(beneficiary)
        if (violations.isNotEmpty()) {
            return Outcome.Failure(SaveError.Invalid(violations))
        }

        // Any edit invalidates a previous sync: the server's copy is now out of date, so the
        // record must be re-queued. Leaving it SYNCED would silently drop the change.
        val stamped = beneficiary.copy(
            updatedAt = timeProvider.now(),
            syncStatus = SyncStatus.PENDING,
        )

        return when (val result = repository.upsert(stamped)) {
            is Outcome.Success -> Outcome.success()
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
}
