package com.astracare.core.domain.usecase

import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.model.CaptureDraft
import javax.inject.Inject

/**
 * Persists the in-progress capture, or deletes it once there is nothing left to keep.
 *
 * One rule, and it is the reason this is a use case rather than a call straight to the
 * repository: **a blank draft is a deletion, not a row of empty strings.**
 *
 * Without it, clearing the form leaves a stored draft of six empty fields, the next launch
 * dutifully restores it, and the capture screen can never again start genuinely fresh. Worse,
 * the same path runs after a successful save — the form resets to blank, autosave fires, and
 * a record that was just committed leaves a ghost behind that looks like unfinished work.
 *
 * Folding the deletion into the save also means the caller has one operation to reason about
 * instead of a branch. Clearing after a successful capture is `invoke(CaptureDraft.Empty)`,
 * which reads as what it is: there is no longer a draft.
 */
class SaveDraftUseCase @Inject constructor(
    private val repository: DraftRepository,
) {

    suspend operator fun invoke(draft: CaptureDraft) {
        if (draft.isBlank) {
            repository.clear()
        } else {
            repository.save(draft)
        }
    }
}
