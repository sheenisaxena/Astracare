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
import com.astracare.feature.patients.audit.AuditTrailRoute
import com.astracare.feature.patients.capture.CaptureRoute

/**
 * Where the app can be.
 *
 * Three destinations, no arguments, no deep links. Modelled as a sealed hierarchy from the
 * start so that adding one forces every `when` to acknowledge it — which is exactly what
 * happened when [AuditTrail] arrived on Day 17: the compiler listed the four places to update
 * rather than leaving one to be found by a blank screen.
 */
private sealed interface Destination {
    data object List : Destination
    data object Capture : Destination
    data object AuditTrail : Destination
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

    // System back from any screen other than the list returns to the list rather than
    // leaving the app. Enabled only where there is somewhere to go back to, so back from the
    // list behaves normally instead of becoming inescapable.
    //
    // Note what a two-entry stack lets this get away with: every destination returns to the
    // list, so "back" needs no history. The first screen reachable from two places is the one
    // that makes this wrong, and it is the same threshold that brings in Navigation Compose.
    BackHandler(enabled = destination !is Destination.List) {
        destination = Destination.List
    }

    when (destination) {
        Destination.List -> BeneficiaryListRoute(
            onAddRecord = { destination = Destination.Capture },
            // Record detail does not exist yet. Doing nothing is honest; routing to a
            // placeholder screen would be a dead end that looks like a feature.
            onOpenRecord = { _: BeneficiaryId -> },
            onOpenAuditTrail = { destination = Destination.AuditTrail },
        )

        Destination.Capture -> CaptureRoute(
            onRecordSaved = { destination = Destination.List },
            onDiscarded = { destination = Destination.List },
        )

        // Reachable only from an affordance the SUPERVISOR permission gates. The shell does
        // not re-check that, deliberately: a second copy of the rule here would be a second
        // thing to keep in step, and it would not add a guarantee — the role lives on this
        // device and the person holding it can change it. See DECISION_LOG 11.2.
        Destination.AuditTrail -> AuditTrailRoute(
            onBack = { destination = Destination.List },
        )
    }
}

/**
 * Persists the current destination across process death.
 *
 * A hand-written `Saver` because `rememberSaveable` can only store types the saved-instance
 * `Bundle` understands, and a Kotlin `data object` is not one of them. Each destination maps
 * to a string; the names are stable because they are the declarations themselves.
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
            Destination.AuditTrail -> AUDIT_KEY
        }
    },
    restore = { key ->
        when (key) {
            CAPTURE_KEY -> Destination.Capture
            AUDIT_KEY -> Destination.AuditTrail
            else -> Destination.List
        }
    },
)

private const val LIST_KEY = "list"
private const val CAPTURE_KEY = "capture"
private const val AUDIT_KEY = "audit"
