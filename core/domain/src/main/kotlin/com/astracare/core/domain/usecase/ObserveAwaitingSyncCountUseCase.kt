package com.astracare.core.domain.usecase

import com.astracare.core.domain.repository.BeneficiaryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * How many records are still only on this handset.
 *
 * Separate from [ObserveBeneficiariesUseCase] because it answers a question about the whole
 * table, and that question cannot be answered from paged data. Paging deliberately holds only
 * the rows near the viewport; counting those would report a number that changes as the user
 * scrolls — and a sync count that moves while you scroll is worse than none, because it looks
 * like it means something.
 *
 * The count is the one number this screen exists to communicate. At the end of a day in a
 * village with no signal, "eleven records have not reached the server" is the difference
 * between trusting the app and writing on paper as well.
 */
class ObserveAwaitingSyncCountUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
) {

    operator fun invoke(): Flow<Int> = repository.observeAwaitingSyncCount()
}
