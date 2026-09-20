package com.astracare.feature.patients.audit

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astracare.core.designsystem.theme.AstraCareTheme
import com.astracare.core.designsystem.theme.Spacing
import com.astracare.core.model.AuditAction
import com.astracare.core.model.AuditEntry
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import com.astracare.feature.patients.R
import com.astracare.feature.patients.labelRes

/** Stateful entry point for the audit trail. */
@Composable
fun AuditTrailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuditTrailViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    AuditTrailScreen(entries = entries, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditTrailScreen(
    entries: List<AuditEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_title)) },
                navigationIcon = {
                    // A text button rather than an icon, matching the rest of the app: this
                    // project has taken no icon dependency, and a labelled control is better
                    // for a screen reader than an unlabelled glyph would have been anyway.
                    TextButton(onClick = onBack) { Text(stringResource(R.string.audit_back)) }
                },
            )
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            EmptyTrail(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = Spacing.ScrollTail,
                ),
            ) {
                // Keyed by the row id, which is monotonic and never reused — the trail is
                // append-only, so unlike the record list there is no case where a key changes
                // meaning underneath a composition.
                items(items = entries, key = { it.id }) { entry ->
                    AuditRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter, vertical = Spacing.Snug),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            text = stringResource(entry.action.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = entry.subjectText(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            // "by Field worker", not "by Asha" — this app has no authentication, so the trail
            // records a role and not a person. Writing it as a role in the UI as well keeps
            // the screen from implying an accountability it cannot deliver. DECISION_LOG 11.5.
            text = stringResource(R.string.audit_by_actor, stringResource(entry.actor.labelRes())) +
                " · " + entry.at.relativeToNow(),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AuditEntry.subjectText(): String =
    recordId?.let { stringResource(R.string.audit_subject_record, it.value) }
        ?: stringResource(R.string.audit_subject_device)

/**
 * "5 minutes ago", in the device's language.
 *
 * `DateUtils` rather than `java.time`, which needs core library desugaring below API 26 while
 * minSdk here is 24 — the same constraint that made `Timestamp` epoch millis in the first
 * place. It is also already localised, which a hand-rolled formatter would not be.
 *
 * Relative rather than absolute, because the question this screen answers is "what just
 * happened", and because a device clock that has moved makes an absolute timestamp look
 * authoritative when it is not.
 */
@Composable
private fun Timestamp.relativeToNow(): String =
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/**
 * Exhaustive `when`, so a new [AuditAction] fails the build here rather than rendering blank.
 *
 * The same guard `SyncStatusPresentation` uses, for the same reason: a fallback branch would
 * let a new kind of event show up as whatever the default was, and on this screen that means
 * telling someone an event was one thing when it was another.
 */
@StringRes
private fun AuditAction.labelRes(): Int = when (this) {
    AuditAction.RECORD_CREATED -> R.string.audit_action_record_created
    AuditAction.RECORD_UPDATED -> R.string.audit_action_record_updated
    AuditAction.CONFLICT_DETECTED -> R.string.audit_action_conflict_detected
    AuditAction.ROLE_CHANGED -> R.string.audit_action_role_changed
}

@Composable
private fun EmptyTrail(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.Gutter),
    ) {
        Text(
            text = stringResource(R.string.audit_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.audit_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun AuditTrailPreview() {
    AstraCareTheme {
        AuditTrailScreen(
            entries = listOf(
                AuditEntry(
                    id = 3,
                    actor = UserRole.FIELD_WORKER,
                    action = AuditAction.ROLE_CHANGED,
                    recordId = null,
                    at = Timestamp(System.currentTimeMillis()),
                ),
                AuditEntry(
                    id = 2,
                    actor = UserRole.FIELD_WORKER,
                    action = AuditAction.CONFLICT_DETECTED,
                    recordId = BeneficiaryId("a1b2"),
                    at = Timestamp(System.currentTimeMillis()),
                ),
                AuditEntry(
                    id = 1,
                    actor = UserRole.FIELD_WORKER,
                    action = AuditAction.RECORD_CREATED,
                    recordId = BeneficiaryId("a1b2"),
                    at = Timestamp(System.currentTimeMillis()),
                ),
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun AuditTrailEmptyPreview() {
    AstraCareTheme {
        AuditTrailScreen(entries = emptyList(), onBack = {})
    }
}
