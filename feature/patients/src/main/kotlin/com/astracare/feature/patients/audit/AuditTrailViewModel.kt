package com.astracare.feature.patients.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astracare.core.domain.usecase.ObserveAuditTrailUseCase
import com.astracare.core.model.AuditEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The audit trail screen's only job: hand the list to the UI.
 *
 * No intents and no effects, so no `dispatch` and no `Channel`. The MVI scaffolding on the
 * other two screens exists because they have user actions that change something; this one has
 * a back button that the shell owns. Adding a contract here for symmetry would be three files
 * describing a screen that does nothing.
 *
 * No paging either. `ObserveAuditTrailUseCase` caps the query — see its `RECENT_LIMIT` — and a
 * bounded list is one query on a table that is written to on every save, where a
 * `PagingSource` would be invalidated and reloaded each time.
 *
 * ## Why there is no permission check in here
 *
 * The screen is only reachable from an affordance that the permission gates, and a ViewModel
 * that also refused would make the composable handle an error state it can never be in. That
 * is the line DECISION_LOG 11.2 draws: refuse *actions* below the UI, because those can arrive
 * from a restored back stack or a stale screen; gate *reads* at the navigation, because a read
 * nobody can reach is not a vulnerability the client can fix anyway. The role is on the device
 * and the device belongs to the user — a determined one reads the database, not the screen.
 */
@HiltViewModel
class AuditTrailViewModel @Inject constructor(
    observeAuditTrail: ObserveAuditTrailUseCase,
) : ViewModel() {

    val entries: StateFlow<List<AuditEntry>> = observeAuditTrail()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            // Empty, not null. "Not loaded yet" and "nothing has happened" look the same for
            // the few milliseconds this is visible, and an empty list renders the same empty
            // state the real answer usually will.
            initialValue = emptyList(),
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
