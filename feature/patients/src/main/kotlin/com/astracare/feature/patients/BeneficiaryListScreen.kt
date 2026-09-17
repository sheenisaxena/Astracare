package com.astracare.feature.patients

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astracare.core.designsystem.component.StatusChip
import com.astracare.core.designsystem.component.StatusTone
import com.astracare.core.designsystem.theme.AstraCareTheme
import com.astracare.core.designsystem.theme.Sizing
import com.astracare.core.designsystem.theme.Spacing
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import com.astracare.feature.patients.util.ObserveEffects

/**
 * Stateful entry point for the record history.
 *
 * ## Scope note
 *
 * This is a plain [LazyColumn] over the whole list, which is correct for the handful of
 * records a single health worker captures in a day and wrong at the scale the README claims
 * to care about. Day 12 replaces it with Paging 3 over Room. Written this way today so the
 * app is demonstrable end to end — capture a record, see it appear — rather than leaving the
 * list ViewModel built and unrendered for another day.
 *
 * Calling that out here rather than in a commit message: the next person to read this file
 * should know it is a deliberate staging post and not an oversight.
 */
@Composable
fun BeneficiaryListRoute(
    onAddRecord: () -> Unit,
    onOpenRecord: (BeneficiaryId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BeneficiaryListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            BeneficiaryListEffect.OpenCapture -> onAddRecord()
            is BeneficiaryListEffect.OpenRecord -> onOpenRecord(effect.id)
        }
    }

    BeneficiaryListScreen(
        state = uiState,
        onIntent = viewModel::dispatch,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BeneficiaryListScreen(
    state: BeneficiaryListUiState,
    onIntent: (BeneficiaryListIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = { SyncSummary(state) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onIntent(BeneficiaryListIntent.AddRecordClicked) },
                text = { Text(stringResource(R.string.list_action_add)) },
                icon = {},
            )
        },
    ) { innerPadding ->
        // Exhaustive over the sealed state. Adding a fourth case breaks compilation here,
        // which is the entire reason the state is sealed rather than a bag of booleans.
        when (state) {
            BeneficiaryListUiState.Loading -> CentredMessage(
                modifier = Modifier.padding(innerPadding),
                content = { CircularProgressIndicator() },
            )

            BeneficiaryListUiState.Empty -> CentredMessage(
                modifier = Modifier.padding(innerPadding),
                content = { EmptyState() },
            )

            is BeneficiaryListUiState.Content -> RecordList(
                records = state.records,
                onIntent = onIntent,
                contentPadding = innerPadding,
            )
        }
    }
}

/**
 * The count of records still on the handset, in the app bar.
 *
 * Shown on every state except Loading, including when everything is synced — a chip that
 * appears only when something is wrong trains people to ignore its absence, and "did it not
 * sync, or did the chip just not render?" is not a question a health worker should have to
 * ask at the end of a day.
 */
@Composable
private fun SyncSummary(state: BeneficiaryListUiState) {
    if (state !is BeneficiaryListUiState.Content) return

    val allSynced = state.awaitingSync == 0
    val text = if (allSynced) {
        stringResource(R.string.list_all_synced)
    } else {
        stringResource(R.string.list_awaiting_sync, state.awaitingSync)
    }

    StatusChip(
        text = text,
        tone = if (allSynced) StatusTone.Info else StatusTone.Warning,
        contentDescription = text,
        modifier = Modifier.padding(end = Spacing.Gutter),
    )
}

@Composable
private fun RecordList(
    records: List<Beneficiary>,
    onIntent: (BeneficiaryListIntent) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            // Clears the floating action button, which would otherwise sit on top of the
            // last record — the one just captured, and the one most likely to be tapped.
            bottom = Spacing.ScrollTail,
        ),
    ) {
        // Keyed by record id. Without a key, LazyColumn falls back to position, so inserting
        // a record at the top — which is exactly what capturing one does — makes Compose
        // treat every row as changed and re-compose the whole visible list.
        items(items = records, key = { it.id.value }) { record ->
            RecordRow(
                record = record,
                onClick = { onIntent(BeneficiaryListIntent.RecordClicked(record.id)) },
            )
            HorizontalDivider()
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
private fun CentredMessage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
internal fun BeneficiaryListEmptyPreview() {
    AstraCareTheme {
        BeneficiaryListScreen(state = BeneficiaryListUiState.Empty, onIntent = {})
    }
}

@Preview(showBackground = true)
@Composable
internal fun BeneficiaryListContentPreview() {
    val records = listOf(
        previewRecord("1", "Asha Devi", SyncStatus.PENDING),
        previewRecord("2", "Ramesh Kumar", SyncStatus.SYNCED),
        previewRecord("3", "Sunita Bai", SyncStatus.CONFLICTED),
    )
    AstraCareTheme {
        BeneficiaryListScreen(
            state = BeneficiaryListUiState.Content(records, awaitingSync = 2),
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
