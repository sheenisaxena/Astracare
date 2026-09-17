package com.astracare.feature.patients.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.astracare.feature.patients.mvi.UiEffect
import kotlinx.coroutines.flow.Flow

/**
 * Collects a ViewModel's one-shot effects for as long as the screen is actually on screen.
 *
 * Both screens in this module need this and both would get it subtly wrong written inline,
 * which is the whole justification for a shared helper in a module that otherwise avoids them.
 *
 * ## Why `repeatOnLifecycle(STARTED)` and not a bare `LaunchedEffect`
 *
 * `LaunchedEffect(Unit) { flow.collect { ... } }` is the version everyone writes first. Its
 * coroutine lives as long as the composable is in the composition, which includes the whole
 * time the screen sits in a back stack behind another one. So a screen the user navigated away
 * from goes on receiving effects and goes on acting on them: the classic symptom is a
 * navigation event firing from a screen the user cannot see, dumping them somewhere they did
 * not ask to be.
 *
 * `repeatOnLifecycle(STARTED)` cancels the collection when the screen stops and restarts it
 * when it resumes. Combined with the `Channel` these effects come from, an event sent while
 * the screen is stopped is buffered rather than lost — so pausing collection defers delivery
 * instead of dropping it. The two choices only work together: `repeatOnLifecycle` over a
 * `SharedFlow(replay = 0)` would genuinely lose the event.
 *
 * ## Why `rememberUpdatedState`
 *
 * [onEffect] is a lambda written at the call site, so a new instance is created on every
 * recomposition. Capturing it directly would mean the long-lived collector holds the first
 * one forever, calling yesterday's navigation callbacks. `rememberUpdatedState` keeps the
 * coroutine pointed at the current lambda without restarting it — which is exactly what must
 * not happen, since restarting would re-subscribe to the channel.
 *
 * Keyed on the flow and the lifecycle owner only. Deliberately not on [onEffect]: that is the
 * thing `rememberUpdatedState` exists to keep out of the key.
 */
@Composable
internal fun <T : UiEffect> ObserveEffects(
    effects: Flow<T>,
    onEffect: suspend (T) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEffect by rememberUpdatedState(onEffect)

    LaunchedEffect(effects, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            effects.collect { currentOnEffect(it) }
        }
    }
}
