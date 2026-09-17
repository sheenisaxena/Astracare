package com.astracare.feature.patients.mvi

/**
 * The vocabulary every screen in this module speaks: state in, intents out, effects once.
 *
 * ## Why three marker interfaces and not an `MviViewModel<S, I, E>` base class
 *
 * A generic base class is the obvious move and it was rejected deliberately. It buys the
 * removal of roughly eight lines per screen — a `MutableStateFlow`, a `Channel`, and their
 * two read-only projections. It costs the ability for any screen to differ.
 *
 * The two screens in this module already differ in ways a single base class would have to
 * paper over. The list screen's state arrives from a `Flow` it does not control, so it is
 * built with `stateIn` and has no `MutableStateFlow` at all; the capture screen's state is
 * owned outright and mutated by a reducer. A base class holding a `MutableStateFlow` would
 * force the list screen to copy every upstream emission into a field it then has to keep in
 * sync — a worse design imposed by an abstraction that exists to save typing.
 *
 * Markers give what the abstraction was actually for: a name for each role, so a reader can
 * tell state from intent from effect at a glance, and so a future generic helper (a test
 * harness, a Compose collector) can be written against `UiState` rather than against each
 * screen. They constrain nothing.
 *
 * This is the same call Now in Android makes, and it is reversible: introducing a base class
 * later is additive, while removing one that screens have grown to depend on is not.
 *
 * ## Why they live here and not in a `:core:ui` module
 *
 * There is one feature module. A module whose entire content is three empty interfaces, for
 * one consumer, is scaffolding — it buys nothing today and costs a build file, a convention
 * plugin application, and an entry in the dependency graph. When a second feature module
 * appears these move to `:core:ui`; that is a package rename the IDE performs, not a
 * redesign. See DECISION_LOG 5.1.
 */

/**
 * The complete, renderable description of a screen at one instant.
 *
 * Complete is the operative word. If a composable needs something to render that is not in
 * the state, the state is wrong — and the bug that follows is the one where a screen renders
 * correctly the first time and incorrectly after a configuration change, because the missing
 * piece lived in a field that did not survive.
 */
interface UiState

/**
 * Something that happened which the screen may react to.
 *
 * Named for the event, not the handler: `SaveClicked`, never `save()`. The distinction is not
 * cosmetic — a screen that sends `save()` has already decided what saving means, which puts
 * the decision in the composable where it cannot be unit tested. A screen that reports
 * `SaveClicked` leaves that decision to the ViewModel, where it can.
 */
interface UiIntent

/**
 * A consequence that must happen exactly once, and which therefore cannot live in [UiState].
 *
 * Navigation, a snackbar, a vibration, closing the keyboard. The test for whether something
 * is an effect rather than state: would replaying it be wrong? State is replayed constantly —
 * every recomposition, every configuration change, every time the process is restored. A
 * `navigateBack = true` flag in state navigates back again after a screen rotation, and then
 * needs a `consumed` flag to stop it, and then the consumed flag needs resetting. Effects
 * exist so that sequence never starts.
 */
interface UiEffect
