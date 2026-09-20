package com.astracare.feature.patients.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astracare.core.designsystem.theme.AstraCareTheme
import com.astracare.core.designsystem.theme.Sizing
import com.astracare.core.designsystem.theme.Spacing
import com.astracare.feature.patients.R
import com.astracare.feature.patients.util.ObserveEffects

/**
 * Stateful entry point: owns the ViewModel, translates effects into navigation and snackbars.
 *
 * Split from [CaptureScreen] on purpose. This half cannot be previewed or tested without a
 * Hilt graph; the half below is a pure function of its arguments and can be rendered in the
 * IDE, screenshot-tested, and driven from a Compose UI test with no dependency injection at
 * all. Keeping them in one composable would mean every preview needed a database.
 *
 * `hiltViewModel()` as a default argument rather than a required one: production passes
 * nothing, a test passes a ViewModel built over fakes.
 */
@Composable
fun CaptureRoute(
    onRecordSaved: () -> Unit,
    onDiscarded: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.capture_save_failed)
    val notPermittedMessage = stringResource(R.string.capture_save_not_permitted)

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            CaptureEffect.Saved -> onRecordSaved()
            CaptureEffect.Discarded -> onDiscarded()
            // Shown rather than navigated away from: the form still holds everything the
            // health worker typed, and the reducer deliberately kept it there.
            is CaptureEffect.SaveFailed -> snackbarHostState.showSnackbar(saveFailedMessage)

            // Distinct wording from a storage failure, because the two ask for different
            // things: one says try again, this one says you are in the wrong role. Telling
            // someone to retry an action that will always be refused is the worse message.
            CaptureEffect.SaveNotPermitted ->
                snackbarHostState.showSnackbar(notPermittedMessage)
        }
    }

    CaptureScreen(
        state = uiState,
        onIntent = viewModel::dispatch,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * The capture form. A pure function of [state], reporting upward through [onIntent].
 *
 * `collectAsStateWithLifecycle` in the route above rather than `collectAsState`: the latter
 * keeps collecting while the app is in the background, so a screen nobody can see goes on
 * recomposing and holding the upstream Flow open. On this screen that would keep a database
 * query alive behind a locked phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureScreen(
    state: CaptureUiState,
    onIntent: (CaptureIntent) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.capture_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Snug),
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                // The form is taller than a small handset in landscape, and the soft keyboard
                // takes roughly half of what is left. Without this the MUAC field is
                // unreachable on exactly the devices this app targets.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.Tight),
        ) {
            CaptureFields(state = state, onIntent = onIntent)

            CaptureActions(
                isSaveEnabled = state.isSaveEnabled,
                onIntent = onIntent,
                modifier = Modifier.padding(top = Spacing.Tight),
            )
        }
    }
}

@Composable
private fun CaptureFields(
    state: CaptureUiState,
    onIntent: (CaptureIntent) -> Unit,
) {
    CaptureTextField(
        field = CaptureField.NAME,
        value = state.name,
        labelRes = R.string.capture_field_name,
        keyboardType = KeyboardType.Text,
        errors = state.errors,
        onIntent = onIntent,
    )
    CaptureTextField(
        field = CaptureField.AGE,
        value = state.ageYears,
        labelRes = R.string.capture_field_age,
        // Number, not Decimal: age is whole years, and offering a decimal point invites
        // "2.5" into a field the domain models as an Int.
        keyboardType = KeyboardType.Number,
        errors = state.errors,
        onIntent = onIntent,
    )
    CaptureTextField(
        field = CaptureField.VILLAGE,
        value = state.village,
        labelRes = R.string.capture_field_village,
        keyboardType = KeyboardType.Text,
        errors = state.errors,
        onIntent = onIntent,
    )
    CaptureTextField(
        field = CaptureField.WEIGHT,
        value = state.weightKg,
        labelRes = R.string.capture_field_weight,
        keyboardType = KeyboardType.Decimal,
        errors = state.errors,
        onIntent = onIntent,
    )
    CaptureTextField(
        field = CaptureField.HEIGHT,
        value = state.heightCm,
        labelRes = R.string.capture_field_height,
        keyboardType = KeyboardType.Decimal,
        errors = state.errors,
        onIntent = onIntent,
    )
    CaptureTextField(
        field = CaptureField.MUAC,
        value = state.muacMm,
        labelRes = R.string.capture_field_muac,
        keyboardType = KeyboardType.Decimal,
        errors = state.errors,
        onIntent = onIntent,
        // The last field submits rather than advancing to nothing.
        imeAction = ImeAction.Done,
        supportingTextRes = R.string.capture_field_muac_hint,
    )
}

/**
 * One labelled input.
 *
 * The keyboard type is a hint, not a guarantee — a physical keyboard, a clipboard paste or a
 * third-party IME can all put letters into a `KeyboardType.Decimal` field. That is precisely
 * why `toBeneficiary()` still parses defensively instead of trusting the input type: an app
 * that relies on the keyboard for validation is an app that breaks for anyone using Gboard's
 * handwriting mode.
 */
@Composable
private fun CaptureTextField(
    field: CaptureField,
    value: String,
    labelRes: Int,
    keyboardType: KeyboardType,
    errors: CaptureErrors,
    onIntent: (CaptureIntent) -> Unit,
    imeAction: ImeAction = ImeAction.Next,
    supportingTextRes: Int? = null,
) {
    val errorText = errorTextFor(field, errors)
    val supportingText = errorText ?: supportingTextRes?.let { stringResource(it) }

    OutlinedTextField(
        value = value,
        onValueChange = { onIntent(CaptureIntent.FieldChanged(field, it)) },
        label = { Text(stringResource(labelRes)) },
        isError = errorText != null,
        singleLine = true,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        // Left editable during a save. The save is a local database write measured in
        // milliseconds; disabling every field for it would make the form flicker, and
        // double-submission is already prevented by the reducer.
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CaptureActions(
    isSaveEnabled: Boolean,
    onIntent: (CaptureIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.End),
        modifier = modifier.fillMaxWidth(),
    ) {
        TextButton(
            onClick = { onIntent(CaptureIntent.DiscardClicked) },
            modifier = Modifier.heightIn(min = Sizing.MinTouchTarget),
        ) {
            Text(stringResource(R.string.capture_action_discard))
        }
        Button(
            onClick = { onIntent(CaptureIntent.SaveClicked) },
            enabled = isSaveEnabled,
            modifier = Modifier.heightIn(min = Sizing.MinTouchTarget),
        ) {
            Text(stringResource(R.string.capture_action_save))
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun CaptureScreenPreview() {
    AstraCareTheme {
        CaptureScreen(
            state = CaptureUiState(name = "Asha Devi", ageYears = "3", village = "Kotri"),
            onIntent = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

@Preview(showBackground = true)
@Composable
internal fun CaptureScreenErrorPreview() {
    AstraCareTheme {
        CaptureScreen(
            state = CaptureUiState(
                name = "Asha Devi",
                ageYears = "three",
                weightKg = "12.4",
                errors = CaptureErrors(malformed = setOf(CaptureField.AGE)),
            ),
            onIntent = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}
