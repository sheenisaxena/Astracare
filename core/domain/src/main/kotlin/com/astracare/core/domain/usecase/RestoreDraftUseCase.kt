package com.astracare.core.domain.usecase

import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.model.CaptureDraft
import javax.inject.Inject

/**
 * Returns the unfinished capture, if there is one.
 *
 * Thin on purpose, and it still earns its place: it is the seam that lets the capture screen
 * be tested against a draft without a database, and it keeps `:feature:patients` depending on
 * use cases rather than reaching past them to a repository — which is the rule the rest of
 * this codebase follows, and rules that hold most of the time are not rules.
 *
 * A blank draft is treated as no draft. The repository should never contain one (see
 * [SaveDraftUseCase]) but a row written by an older build, or a partially-applied migration,
 * could produce one — and "restore" that fills a form with empty strings and then reports
 * success is worse than a fresh form.
 */
class RestoreDraftUseCase @Inject constructor(
    private val repository: DraftRepository,
) {

    suspend operator fun invoke(): CaptureDraft? = repository.load()?.takeUnless { it.isBlank }
}
