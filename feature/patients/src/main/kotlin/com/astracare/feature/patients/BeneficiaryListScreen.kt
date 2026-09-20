package com.astracare.feature.patients

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.astracare.core.designsystem.component.StatusChip
import com.astracare.core.designsystem.component.StatusTone
import com.astracare.core.designsystem.theme.AstraCareTheme
import com.astracare.core.designsystem.theme.Sizing
import com.astracare.core.designsystem.theme.Spacing
import com.astracare.core.domain.usecase.Permissions
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import com.astracare.feature.patients.util.ObserveEffects
import kotlinx.coroutines.flow.flowOf

/**
 * Stateful entry point for the record history.
 *
 * `collectAsLazyPagingItems()` belongs here rather than in the stateless half below: it is the
 * bridge between a `Flow<PagingData>` and something a `LazyColumn` can render, and it has to
 * live where the ViewModel does. The screen below takes the already-collected
 * [LazyPagingItems], which is what lets a preview hand it a static list.
 */
@Composable
fun BeneficiaryListRoute(
    onAddRecord: () -> Unit,
    onOpenRecord: (BeneficiaryId) -> Unit,
    onOpenAuditTrail: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BeneficiaryListViewModel = hiltViewModel(),
) {
    val records = viewModel.records.collectAsLazyPagingItems()
    val syncSummary by viewModel.syncSummary.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            BeneficiaryListEffect.OpenCapture -> onAddRecord()
            is BeneficiaryListEffect.OpenRecord -> onOpenRecord(effect.id)
            BeneficiaryListEffect.OpenAuditTrail -> onOpenAuditTrail()
        }
    }

    BeneficiaryListScreen(
        records = records,
        syncSummary = syncSummary,
        permissions = permissions,
        onIntent = viewModel::dispatch,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BeneficiaryListScreen(
    records: LazyPagingItems<Beneficiary>,
    syncSummary: SyncSummaryUiState,
    permissions: Permissions,
    onIntent: (BeneficiaryListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = {
                    // Both gates ask the permission, never the role. `if (role == SUPERVISOR)`
                    // would put a second copy of the permission matrix in the UI layer, and the
                    // two would drift the first time a role is added. See RolePermissions.
                    if (permissions.canViewAuditTrail) {
                        TextButton(
                            onClick = { onIntent(BeneficiaryListIntent.AuditTrailClicked) },
                        ) {
                            Text(stringResource(R.string.list_action_audit))
                        }
                    }
                    SyncSummaryChip(syncSummary)
                },
            )
        },
        floatingActionButton = {
            if (permissions.canCaptureRecord) {
                // The label has to be repeated as a contentDescription, and that is not
                // belt-and-braces. ExtendedFloatingActionButton wraps its `text` slot in
                // `clearAndSetSemantics {}` — Material's accessibility contract puts the
                // button's name on the `icon` slot, because normally the icon is the thing
                // that needs describing and the text would only duplicate it. This FAB has no
                // icon, so the label was cleared and nothing replaced it: the merged semantics
                // node carried Role=Button, an OnClick, and no name at all. TalkBack announced
                // "button".
                //
                // Found by `CaptureToHistoryTest`, not by reading the code — see DECISION_LOG
                // 14.9. `StatusChip` uses `clearAndSetSemantics` deliberately and sets a
                // description; here Material used it on our behalf and the slot we left empty
                // was the one holding the name.
                val addRecord = stringResource(R.string.list_action_add)
                ExtendedFloatingActionButton(
                    onClick = { onIntent(BeneficiaryListIntent.AddRecordClicked) },
                    text = { Text(addRecord) },
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = addRecord },
                )
            }
        },
    ) { innerPadding ->
        // Three cases, read off Paging's own load state rather than a hand-maintained enum.
        // `itemCount == 0` alone is ambiguous — it is also true before the first page has
        // arrived — so the empty state is only shown once refresh has finished.
        val isRefreshing = records.loadState.refresh is LoadState.Loading
        val isEmpty = !isRefreshing && records.itemCount == 0

        Column(Modifier.padding(innerPadding)) {
            RoleBanner(role = permissions.role, onIntent = onIntent)

            when {
                isRefreshing -> CentredBox(Modifier) { CircularProgressIndicator() }

                isEmpty -> CentredBox(Modifier) { EmptyState() }

                else -> RecordList(records = records, onIntent = onIntent)
            }
        }
    }
}

/**
 * Says which role the app is currently behaving as, and that this is not a sign-in.
 *
 * A control that looked like an account switcher would imply an authority the app does not
 * have. The caption is doing real work: `DECISION_LOG 4.3` says client-side RBAC is a UX
 * affordance rather than a security boundary, and this is that sentence in the one place a
 * health worker will actually read it.
 *
 * Always visible, for both roles. Showing it only to supervisors would make the field-worker
 * view look like the only view there is, which is exactly the impression a demo should not
 * leave.
 */
@Composable
private fun RoleBanner(
    role: UserRole,
    onIntent: (BeneficiaryListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // With two roles the "other" one is unambiguous. The intent still carries the target role
    // rather than being a toggle, so a third role changes this line and nothing downstream.
    val other = if (role == UserRole.FIELD_WORKER) UserRole.SUPERVISOR else UserRole.FIELD_WORKER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.list_role_acting_as, stringResource(role.labelRes())),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.list_role_caption),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TextButton(onClick = { onIntent(BeneficiaryListIntent.RoleSelected(other)) }) {
            Text(stringResource(R.string.list_role_switch))
        }
    }
    HorizontalDivider()
}

/**
 * The display name for a role.
 *
 * A string resource, not the enum's `name`. `FIELD_WORKER` is an identifier; "Field worker" is
 * a word, and this app has already committed to a Hindi translation — see the header of
 * `strings.xml`. Exhaustive `when`, so a new role fails the build here rather than rendering
 * as a constant name on screen.
 */
@StringRes
internal fun UserRole.labelRes(): Int = when (this) {
    UserRole.FIELD_WORKER -> R.string.role_field_worker
    UserRole.SUPERVISOR -> R.string.role_supervisor
}

/**
 * The count of records still on the handset, in the app bar.
 *
 * Shown in every state including "all synced" — a chip that appears only when something is
 * wrong trains people to ignore its absence, and "did it not sync, or did the chip just not
 * render?" is not a question a health worker should have to ask at the end of a day.
 */
@Composable
private fun SyncSummaryChip(summary: SyncSummaryUiState) {
    val text = if (summary.isEverythingSynced) {
        stringResource(R.string.list_all_synced)
    } else {
        stringResource(R.string.list_awaiting_sync, summary.awaitingSync)
    }

    StatusChip(
        text = text,
        tone = if (summary.isEverythingSynced) StatusTone.Info else StatusTone.Warning,
        contentDescription = text,
        modifier = Modifier.padding(end = Spacing.Gutter),
    )
}

@Composable
private fun RecordList(
    records: LazyPagingItems<Beneficiary>,
    onIntent: (BeneficiaryListIntent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // The Scaffold's inset is applied by the enclosing Column, which also holds the role
        // banner, so only the tail padding is this list's own business now.
        contentPadding = PaddingValues(
            // Clears the floating action button, which would otherwise sit on top of the
            // last record — the one just captured, and the one most likely to be tapped.
            bottom = Spacing.ScrollTail,
        ),
    ) {
        // Keyed by record id. Without a key Paging falls back to position, so inserting a
        // record at the top — which is exactly what capturing one does — makes Compose treat
        // every row as changed and lose scroll position. `itemKey` is Paging's helper for
        // exactly this and handles the placeholder case, which a hand-written lambda would
        // have to remember to.
        items(
            count = records.itemCount,
            key = records.itemKey { it.id.value },
        ) { index ->
            // Null only when placeholders are enabled, which they are not here. Handled
            // rather than forced, because a `!!` in a list that Paging is free to change
            // underneath the composition is a crash waiting for a slow disk.
            records[index]?.let { record ->
                RecordRow(
                    record = record,
                    onClick = { onIntent(BeneficiaryListIntent.RecordClicked(record.id)) },
                )
                HorizontalDivider()
            }
        }

        // Appending the next page. No error branch and no retry button: this source is a local
        // database and cannot fail a load. That UI arrives with RemoteMediator on Days 13-14,
        // when there is a failure to retry and a way to test it.
        if (records.loadState.append is LoadState.Loading) {
            item {
                CentredBox(Modifier.padding(Spacing.Gutter)) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun RecordRow(record: Beneficiary, onClick: () -> Unit) {
    val status = record.syncStatus.presentation()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Snug),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Sizing.MinTouchTarget)
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Snug),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = record.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.list_record_summary,
                    record.name,
                    record.ageYears,
                    record.village,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(
            text = stringResource(status.labelRes),
            tone = status.tone,
            contentDescription = stringResource(status.descriptionRes),
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.padding(horizontal = Spacing.Loose),
    ) {
        Text(
            text = stringResource(R.string.list_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.list_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CentredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

/**
 * Previews feed a static `PagingData` through the same collector the real screen uses, so what
 * renders here is the actual code path rather than a parallel one kept in sync by hand.
 */
@Preview(showBackground = true)
@Composable
internal fun BeneficiaryListContentPreview() {
    val records = flowOf(
        PagingData.from(
            listOf(
                previewRecord("1", "Asha Devi", SyncStatus.CONFLICTED),
                previewRecord("2", "Ramesh Kumar", SyncStatus.PENDING),
                previewRecord("3", "Sunita Bai", SyncStatus.SYNCED),
            ),
        ),
    ).collectAsLazyPagingItems()

    AstraCareTheme {
        BeneficiaryListScreen(
            records = records,
            syncSummary = SyncSummaryUiState(awaitingSync = 2),
            // The two previews deliberately use different roles, so the gated affordances —
            // the capture button and the audit action — are both covered by a screenshot.
            permissions = Permissions(UserRole.FIELD_WORKER),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun BeneficiaryListEmptyPreview() {
    val records = flowOf(PagingData.empty<Beneficiary>()).collectAsLazyPagingItems()

    AstraCareTheme {
        BeneficiaryListScreen(
            records = records,
            syncSummary = SyncSummaryUiState.Unknown,
            permissions = Permissions(UserRole.SUPERVISOR),
            onIntent = {},
        )
    }
}

private fun previewRecord(id: String, name: String, syncStatus: SyncStatus) = Beneficiary(
    id = BeneficiaryId(id),
    name = name,
    ageYears = 3,
    village = "Kotri",
    measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
    recordedAt = Timestamp(0L),
    updatedAt = Timestamp(0L),
    syncStatus = syncStatus,
)
