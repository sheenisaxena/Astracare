package com.astracare

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.astracare.core.model.BeneficiaryId
import com.astracare.feature.patients.BeneficiaryListRoute
import com.astracare.feature.patients.capture.CaptureRoute

/**
 * Where the app can be.
 *
 * Two destinations, no arguments, no deep links. Modelled as a sealed hierarchy anyway so
 * that adding the record-detail screen forces every `when` to acknowledge it.
 */
private sealed interface Destination {
    data object List : Destination
    data object Capture : Destination
}

/**
 * The app shell.
 *
 * ## Why there is no Navigation Compose here
 *
 * This was decided during planning and is worth restating where the consequence lives: two
 * destinations do not justify the library. What `androidx.navigation` buys is a typed graph,
 * argument marshalling, deep links and a back stack that survives process death. This app
 * currently has no arguments to marshal, no deep links, and a back stack one entry deep.
 *
 * What it costs is not the dependency but the shape: routes become strings or serializable
 * types, every screen gains a `NavController`, and previewing a screen means faking one. That
 * is worth paying when there are eight destinations. At two it is ceremony, and ceremony
 * adopted early is ceremony nobody revisits.
 *
 * The line to watch for: the moment a destination takes an argument — which is the record
 * detail screen, and therefore probably Day 12 — this hand-rolled version starts having to
 * reimplement argument passing, and the library wins. See DECISION_LOG 6.5.
 *
 * ## Why rememberSaveable
 *
 * `remember` alone would drop the current destination on process death, so a health worker
 * whose phone reclaimed the app mid-form would return to the list. The draft would survive —
 * that is what Day 11's autosave is for — but they would have to find their way back to it,
 * having been told nothing. `rememberSaveable` puts the destination in the saved-instance
 * bundle, which is what `NavHost` would have done.
 */
@Composable
fun AstraCareApp() {
    var destination: Destination by rememberSaveable(
        stateSaver = DestinationSaver,
    ) { mutableStateOf(Destination.List) }

    // System back from the capture screen returns to the list rather than leaving the app.
    // Enabled only where there is somewhere to go back to, so back from the list behaves
    // normally instead of becoming inescapable.
    BackHandler(enabled = destination is Destination.Capture) {
        destination = Destination.List
    }

    when (destination) {
        Destination.List -> BeneficiaryListRoute(
            onAddRecord = { destination = Destination.Capture },
            // Record detail does not exist yet. Doing nothing is honest; routing to a
            // placeholder screen would be a dead end that looks like a feature.
            onOpenRecord = { _: BeneficiaryId -> },
        )

        Destination.Capture -> CaptureRoute(
            onRecordSaved = { destination = Destination.List },
            onDiscarded = { destination = Destination.List },
        )
    }
}

/**
 * Persists the current destination across process death.
 *
 * A hand-written `Saver` because `rememberSaveable` can only store types the saved-instance
 * `Bundle` understands, and a Kotlin `data object` is not one of them. Two destinations map
 * to two strings; the names are stable because they are the declarations themselves.
 *
 * An unrecognised value restores to [Destination.List] rather than throwing. A bundle written
 * by a previous build can outlive an app update, and crashing on launch because a destination
 * was renamed is a far worse outcome than starting on the list.
 */
private val DestinationSaver = Saver<Destination, String>(
    save = { destination ->
        when (destination) {
            Destination.List -> LIST_KEY
            Destination.Capture -> CAPTURE_KEY
        }
    },
    restore = { key -> if (key == CAPTURE_KEY) Destination.Capture else Destination.List },
)

private const val LIST_KEY = "list"
private const val CAPTURE_KEY = "capture"
