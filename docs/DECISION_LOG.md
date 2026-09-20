# AstraCare — Architecture Decision Log

Every non-obvious choice in this repo, with the reasoning and the alternative that was
rejected. Written as the work happens, not reconstructed afterwards.

Read this alongside the module map in `settings.gradle.kts`.

---

## Why a decision log at all

Code shows *what* was built. It cannot show what was considered and discarded, and that is
the part that distinguishes a considered design from an accidental one. Most readers of this
repository will never open a source file — this document and the README are the deliverable.

A useful entry names the alternative and the cost of the choice. "Used Room" is not a
decision. "Used Room 2.x rather than room3 because a .0 major release is not where this
project should spend its debugging budget" is.

---

# Part 1 — Module structure

## 1.1 Container paths must not hold source code

**Changed:** deleted `:core` and `:feature` as modules, keeping them only as directory paths.

The generated project declared `include(":core")` *and* `include(":core:network")`. In Gradle
that makes `:core` the **parent path** of `:core:network`. But `:core` also had its own
`build.gradle.kts` applying `com.android.library` with `namespace = "com.core"` and a real
source set.

**Why this is wrong:** a parent path is an organisational device, not a compilation unit. A
module that is simultaneously a container and a library has no clear responsibility — nothing
should ever depend on "core in general". It also creates two Android libraries
(`com.core`, `com.feature`) that produce build output nobody consumes.

**Concept — module granularity has a cost.** Every module adds Gradle configuration time, a
manifest, a namespace, and dependency-wiring boilerplate. Modules earn their place by giving
you something: an enforced boundary, parallel compilation, or separate ownership. A module
that gives you none of those is overhead.

## 1.2 Sync belongs in `:core:sync`, not `:feature:sync`

**Changed:** moved the sync module out of the `:feature:` namespace.

**Concept — a feature module owns UI and at least one navigation destination.** That is what
the word means in a layered Android architecture. Background synchronisation has no UI, no
screen, and no route. Filing it under `:feature:` signals that the naming convention was
copied without being understood, which reads worse than having no convention.

**Rule applied:** module type follows responsibility, not filesystem convenience.

## 1.3 `:core:database` + `:core:network` merged into `:core:data`

**Rejected alternative:** keep them separate, as Now in Android does.

Separating persistence from networking pays off when different teams own them, or when the
build is slow enough that finer parallelism matters. Neither applies to a project of this
size. What it *would* cost is real: two more sets of build config, two more
namespaces, and cross-module plumbing between a DAO and the repository that consumes it.

`:core:data` holds the Room database, the mock remote source, and the repository
implementations — everything that knows *where* data physically lives.

## 1.4 `:core:model`, `:core:common` and `:core:domain` are Kotlin/JVM, not Android

**This is the most important structural decision in the project.**

These three modules apply `java-library` + `org.jetbrains.kotlin.jvm`. They are not Android
modules, so the Android SDK is *not on their compile classpath*.

**Why it matters:** "my domain layer is platform-independent" is normally a claim defended by
code review. Here it is enforced by the compiler — an `import android.net.Uri` in a use case
does not compile. You cannot accidentally leak a `Context` into business logic.

**Concept — prefer compiler-enforced architecture over documented convention.** Any rule that
depends on a human noticing a violation will eventually be violated. A rule the build enforces
cannot be.

**Secondary benefit:** this is also what makes a future Kotlin Multiplatform extraction
feasible rather than a rewrite. Pure-Kotlin domain code is already most of the way to a
`commonMain` source set. That path is deliberately *not* taken in this project (see 4.1).

## 1.5 Packages named `com.astracare.*`

**Changed:** `com.model` → `com.astracare.core.model`, and so on.

`com.model` is a module name, not a namespace. Reverse-domain naming exists to guarantee
global uniqueness, and in Android the `namespace` also determines where the generated `R` and
`BuildConfig` classes land — colliding namespaces across modules cause real build failures.

---

# Part 2 — Dependencies and dependency injection

## 2.1 Gradle version catalog as the single source of dependency truth

All versions live in `gradle/libs.versions.toml`. No module hard-codes a version string.

**Concept — single source of truth.** With seven modules, the failure mode without a catalog
is version drift: `:core:data` on Room 2.8.4 and `:core:sync` on 2.7.0, resolving to whichever
Gradle picks, with behaviour differing from what either file says. The catalog makes that
impossible and gives type-safe accessors (`libs.room.runtime`) that fail at *configuration*
time on a typo rather than at dependency-resolution time.

Versions are grouped by purpose, so the catalog also documents what each dependency is for.

## 2.2 KSP, not KAPT

**Concept:** KAPT works by generating Java stubs for all Kotlin code so that a Java annotation
processor can read them. That stub-generation pass is pure overhead and roughly doubles
annotation-processing time. KSP reads the Kotlin syntax tree directly.

KAPT is effectively legacy for new projects. Hilt, Room and all processors used here support
KSP.

## 2.3 The KSP version is locked to the Kotlin version

`ksp = "2.2.10-2.0.2"` — the prefix **must** equal `kotlin = "2.2.10"`.

**Why:** KSP plugs into compiler internals that are not a stable public API, so each KSP
release is built against one specific Kotlin version. A mismatch produces an error that reads
like a problem in your own code, which makes it an expensive afternoon. Worth knowing as a
class of problem: anything that hooks the compiler (KSP, Compose compiler, detekt) is
version-coupled to Kotlin.

## 2.4 Hilt rather than plain Dagger or manual DI

**Concept:** Hilt is opinionated Dagger. Its value is a fixed set of Android-lifecycle-aware
components — `SingletonComponent`, `ActivityRetainedComponent`, `ViewModelComponent` — with
the scoping and lifetimes already worked out, plus generated boilerplate for injecting into
framework types you don't construct yourself (Activity, Fragment, Worker).

Plain Dagger means hand-writing components and their lifetimes; a service-locator or manual
construction means no compile-time verification of the graph. Hilt fails at **compile time**
when a binding is missing, which is the property that matters.

- `@HiltAndroidApp` on `AstraCareApplication` generates the root component. Everything
  resolves against it.
- `@AndroidEntryPoint` on `MainActivity` marks an injection site — without it,
  `hiltViewModel()` fails at runtime.

## 2.5 Dispatchers are injected, never referenced directly

`DispatchersModule` provides `CoroutineDispatcher` behind a `@Dispatcher(IO)` /
`@Dispatcher(Default)` qualifier, rather than repositories calling `Dispatchers.IO`.

**Why — this is the single most common cause of flaky coroutine tests.** Code that hard-codes
`Dispatchers.IO` dispatches work onto a real thread pool the test cannot see or control. The
test has nothing to await, so it either races or needs arbitrary sleeps. An injected
dispatcher can be replaced with a `TestDispatcher`, whose scheduler gives the test *virtual
time* and deterministic completion.

**Concept — design for testability at the seam, not with a mock.** The fix is a constructor
parameter, not a mocking framework.

A qualifier is required because there are two bindings of the same type, `CoroutineDispatcher`;
Dagger disambiguates by annotation.

This module lives in `:core:common`, not `:app`. A binding declared in `:app` does work at
runtime — `SingletonComponent` is assembled there, so anything injected anywhere can reach it —
but it inverts the dependency direction conceptually: `:core:data` would rely on a binding
defined in a module it does not and must not depend on. Putting the contract at the bottom of
the graph means any module extracted from this app takes its dispatcher contract with it.

It carries Dagger annotations while remaining a pure Kotlin module: the `hilt-core` artifact
supplies them with no Android dependency. See the `astracare.jvm.hilt` convention plugin —
`hilt-android` here would have put `android.*` on the classpath of the very modules whose
purpose is not having it, silently destroying the boundary in 1.4.

## 2.6 Time is epoch millis behind a value class, not a date-time library

`Timestamp` in `:core:model` is a `@JvmInline value class` over `Long` epoch milliseconds.

**Rejected: `java.time`.** Requires core library desugaring below API 26, and `minSdk` here is
24. Workable, but it is build configuration bought for no benefit.

**Rejected: `kotlinx-datetime`.** From 0.7.0 its `Instant` and `Clock` are typealiases to
`kotlin.time.Instant`/`Clock`, which are `@ExperimentalTime` in Kotlin 2.2. That forces
`@OptIn` through every domain model, and Dagger's KSP processor cannot resolve a binding whose
type sits behind that alias — the graph fails to build with `'Clock' could not be resolved`.

**Applied:** epoch millis in a value class. This domain does exactly two things with time —
**compares** two instants (conflict resolution) and **serialises** them (epoch millis is the
wire format regardless). Time zones, calendar arithmetic and formatting are *presentation*
concerns and belong in the UI layer, where the device locale is known; a health worker should
see "2 hours ago" in their own language, and the domain has no business knowing that.

Consequences, all favourable: `:core:model` now has **zero dependencies**, Room stores the
column as `INTEGER` with no type converter, and `Comparable` makes conflict detection read as
`local.updatedAt > remote.updatedAt`.

### Known limitation: client wall-clock time is not trustworthy

`SystemTimeProvider` reads `System.currentTimeMillis()`, which can jump — the user changes the
device clock, or NTP corrects a drift. Any conflict resolution based on client timestamps is
therefore **best-effort**, and two handsets can disagree about which edit came first.

The production answer to this is an NTP-synced clock rather than the device clock. [Kronos]
(https://github.com/lyft/Kronos-Android) does exactly that: it maintains an offset against NTP
servers and exposes a corrected time, so timestamps stay comparable across devices whose
system clocks disagree. It is what the author has used in production for the same problem.

It is deliberately **not** used here — [TimeProvider] is a one-method interface precisely so
the implementation can be swapped without touching a single call site, and adding a networked
clock would broaden this project's scope past its stated boundary. Recording it because the
right answer to "how do you handle clock skew?" is naming the mechanism, not discovering the
problem in the interview.

## 2.7 Room 2.x, not room3

`androidx.room3:3.0.1` exists and is the Kotlin-Multiplatform-oriented major version.

**Decision:** stay on Room `2.8.4`. This is an Android-only application, so room3's headline
benefit does not apply, and a `.0` major release is not where this project should spend its
debugging budget.

Recorded because "why not the newest version" is a fair interview question, and "I didn't know
it existed" is a worse answer than a stated trade-off.

---

# Part 3 — Build system

## 3.1 Convention plugins in an included build

**Changed:** module build files collapsed from ~230 lines to 98; shared configuration moved to
`build-logic/convention/`.

**Concept.** Anyone can add modules. Managing them without duplicating configuration is the
distinguishing skill. Before this change, `compileSdk`, `minSdk`, `compileOptions` and the
test-instrumentation runner were repeated in eight build files. A `compileSdk` bump meant
eight edits, one of which you forget — and that is not hypothetical here, it is exactly what
happened when this project moved 36 → 37.

Now `AndroidSdk.kt` holds those values once.

**Rejected alternative — `allprojects { }` / `subprojects { }` in the root build file.** It
looks like less work and is actively discouraged: it forces every project to be configured
whenever any project is, which defeats configuration-on-demand and undermines the
configuration cache (enabled in this project's `gradle.properties`).

**Rejected alternative — `buildSrc`.** Any change to `buildSrc` invalidates the build cache
for the *entire* build. An included build (`includeBuild("build-logic")`) is more granular and
composable, and is the current recommendation.

`includeBuild` must sit inside `pluginManagement { }` in `settings.gradle.kts`, otherwise the
`astracare.*` plugin IDs are not resolvable from module build scripts.

## 3.2 Plugin artifacts are `compileOnly`

In `build-logic/convention/build.gradle.kts`, AGP/Kotlin/KSP are `compileOnly`.

**Why:** the convention plugin only needs those types to *compile* against. At execution time
they are already on the consuming build's classpath. Declaring them `implementation` puts a
second copy of AGP there and fails with duplicate-class errors.

## 3.3 No Kotlin Android plugin anywhere

AGP 9 ships **built-in Kotlin support**. Applying `org.jetbrains.kotlin.android` on top of it
breaks the build.

This is why no Android module here applies a Kotlin plugin, and why Kotlin's `jvmTarget` is
derived from `compileOptions` rather than set in a `kotlin { }` block. The JVM modules still
apply `org.jetbrains.kotlin.jvm` — that is a different plugin for non-Android modules, and
there it *does* need an explicit `jvmTarget`, because the Kotlin JVM plugin does not read the
`java { }` extension.

## 3.4 Each Android convention plugin configures its own extension type

**Rejected alternative:** a shared helper taking `CommonExtension`, as Now in Android uses.

`CommonExtension`'s generic signature has changed between AGP major versions. This project is
on a very new AGP 9.3.1. Accepting a few duplicated lines across the application and library
plugins buys immunity to that churn. Revisit if the duplication grows.

## 3.5 Kotlin sources live in `src/main/kotlin`, not `src/main/java`

Android Studio's project template puts Kotlin files in `src/main/java` — a historical artifact
from when Kotlin was bolted onto a Java-first build. Every Kotlin source was moved to
`src/main/kotlin` so the directory name matches its contents.

No `sourceSets { }` configuration is needed: both AGP and the Kotlin JVM plugin already treat
`src/main/kotlin` as a default source root. Test sources follow the same layout,
`src/test/kotlin` and `src/androidTest/kotlin`.

### The `android.disallowKotlinSourceSets` flag is a separate issue

This is worth stating precisely, because the obvious reading is wrong.

`gradle.properties` sets `android.disallowKotlinSourceSets=false`. It is tempting to assume
that exists because of the `src/main/java` layout above. It does not, and moving the sources
did **not** remove the need for it.

AGP 9 rejects all use of the `kotlin.sourceSets` DSL. KSP's Gradle plugin still registers its
*generated* output through that DSL:

```
Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.
Kotlin source set 'debug' contains:
  app/build/generated/ksp/debug/kotlin
  app/build/generated/ksp/debug/java
```

Those paths are under `build/`. They are KSP's own output, not project sources. The build
therefore fails the moment KSP is applied — Hilt's processor runs through it — regardless of
how this project lays out its own files.

**This is a third-party tooling gap, not a suppressed warning about our code**, and the flag
is the resolution AGP's own error message names. The distinction matters: a flag that
suppresses a legitimate complaint about your project is debt, while one that works around an
incompatibility between two build plugins is a version-pinning problem that resolves itself on
upgrade.

Tracked under Open items: remove once KSP registers generated sources via `android.sourceSets`.

**Concept — verify which layer an error is actually about.** Both symptoms surface as the same
AGP error mentioning `kotlin.sourceSets`, but one is about your source layout and the other is
about a plugin's generated output. The distinguishing detail is that the reported paths sit
under `build/`. Fixing the wrong layer produces a change that looks reasonable, passes review,
and does not work.

## 3.6 `project(":core:model")` rather than `projects.core.model`

Type-safe project accessors depend on a feature-preview flag whose stability varies by Gradle
version. The explicit form always works. Low-stakes, but not worth a build failure.

---

# Part 4 — Scope boundaries (stated, not discovered)

Declaring what a project deliberately excludes is a stronger signal than a longer feature
list. Everything below is a choice, not an omission.

## 4.1 Kotlin Multiplatform: studied, not shipped

The domain modules are pure Kotlin and therefore *could* be extracted to KMP. They are not,
and this is deliberate: a real KMP extraction is not a two-hour task, and a resume line that
cannot survive a follow-up question does more damage than an admitted gap.

## 4.2 No real backend

The remote source is a mock. A real server is unbounded work that demonstrates nothing about
Android engineering.

## 4.3 Client-side RBAC is a UX affordance, not a security boundary

Role-gated UI hides actions a role cannot perform. It does **not** secure anything —
the client is fully under the user's control. The server is the only real authorisation
boundary. Stated explicitly because claiming client-side RBAC as a security control is a
serious misunderstanding, and stating the limit correctly demonstrates the opposite.

## 4.4 One entity, one capture screen, one sync path

Depth over breadth. A second entity would add volume without demonstrating anything the first
does not.

## 3.7 CI detects; the pre-push hook and branch protection prevent

A CI workflow alone does not stop a broken commit reaching `main`. It reports *after* the push
has landed, at which point the history contains a bad commit and — on a public repository —
the badge is publicly red. CI is a detection mechanism.

Prevention needs two further layers, and they are not interchangeable:

**`.githooks/pre-push` — local, fast, bypassable.** Runs `detekt` and `test` before the push
leaves the machine, aborting on failure. Feedback in seconds rather than after a CI round
trip. It is convenience, not a control: `git push --no-verify` skips it, and it only exists on
machines that have run `git config core.hooksPath .githooks`. Git never version-controls
`.git/hooks`, which is why the hook is committed to `.githooks` and wired up via config.

**Branch protection — server-side, authoritative, not bypassable.** Requiring the CI check to
pass before a merge is the only layer that actually cannot be worked around, because it is
enforced by the remote rather than by the client.

**Concept — distinguish a control from a convention.** A check that runs on the developer's
machine improves the common case but cannot be relied upon, because the person it constrains
is also the person who can disable it. Only a server-side rule is a control. Both are worth
having; conflating them is how teams end up believing they have a gate when they have a
suggestion.

## 4.5 Instrumented tests are excluded from CI

Emulators in CI are slow and flaky, and a red badge caused by infrastructure is worse than no
badge. Unit tests, ktlint and detekt run on every push; instrumented tests run locally.

---

# Part 5 — UI state management (MVI)

Day 10. `:feature:patients` gains a state machine per screen: a sealed or data-class
`UiState`, an `Intent` type, a pure reducer, and a `Channel` for one-shot effects. Both
ViewModels were rewritten; neither exposes a raw domain list any more.

## 5.1 Marker interfaces, not an `MviViewModel<S, I, E>` base class

A generic base class holding the `MutableStateFlow` and the effect `Channel` is the obvious
move, and it was rejected. It saves roughly eight lines per screen. It costs the ability for
any screen to differ — and the two screens in this module already differ in a way that
matters.

`CaptureViewModel` owns its state: the form is created empty and mutated by a reducer, so a
`MutableStateFlow` is correct. `BeneficiaryListViewModel` does not own its state: the list
arrives from Room through a `Flow` it cannot write to, so its state is built with `map` and
`stateIn` and there is no mutable holder at all. A base class with a `MutableStateFlow` field
would force the list screen to copy every upstream emission into a field it then has to keep
consistent with the database — a second copy of the truth, created to satisfy an abstraction
whose purpose was to save typing.

What the abstraction was actually wanted for is naming, and three marker interfaces
(`UiState`, `UiIntent`, `UiEffect`) provide that while constraining nothing.

**Concept — the cost of an abstraction is paid by its least typical user.** Shared code that
suits four of five cases is not 80% useful; the fifth case either distorts itself to fit or
special-cases around it, and both are worse than the duplication avoided. Deduplication is
cheap to add later and expensive to remove once depended upon.

*Rejected:* base class in a new `:core:ui` module (a module for three empty interfaces and
one consumer); base class inside `:feature:patients` (right abstraction, wrong home the
moment a second feature exists). The markers live in `:feature:patients/mvi` and move to
`:core:ui` when there is a second consumer — an IDE package move, not a redesign.

## 5.2 The shape of `UiState` follows the screen

The history list uses a sealed interface; the capture form uses a data class. This is not an
inconsistency, and the reason is worth stating because "always use a sealed UiState" is
commonly repeated advice that is wrong half the time.

**Sealed, for the list.** `Loading`, `Empty` and `Content` genuinely exclude one another. As
a data class it would be `isLoading: Boolean` plus `records: List<Beneficiary>`, which admits
`isLoading = true` with forty records loaded, and cannot distinguish an empty database from
one that has not answered yet. That second ambiguity is the concrete bug: a composable given
`emptyList()` has to guess, and whichever way it guesses is wrong half the time — either
"no records yet" flashes for a frame before data arrives, or a spinner never stops for a
health worker who genuinely has no records. The information was never in the type.

**Data class, for the form.** Nothing about a form is mutually exclusive. Every field is
always present and always editable, even mid-save. A sealed hierarchy would have one subtype,
or one per field permutation.

*Not modelled:* an `Error` state on the list. Reads come from Room, which is local and is the
single source of truth — there is no network call behind that `Flow` that could fail. A state
nobody can reach is UI nobody can see and a test nobody can write. Paging brings its own
`LoadState` on Day 12, with the error case it actually needs.

## 5.3 Effects go through a `Channel`, not a `SharedFlow` and not state

Three candidates, one requirement: "navigate back after saving" must happen exactly once, and
must not happen again because the device was rotated.

**In state (`navigateBack: Boolean`) — rejected.** State is replayed on every recomposition
and every configuration change, so the flag fires again on rotation. The usual patch is a
`consumed` flag, which then needs resetting, which needs knowing when the UI has finished
with it. The bug is not that this is hard; it is that it is state modelling an event.

**`MutableSharedFlow(replay = 0)` — rejected.** It drops emissions made while nothing is
collecting, and there is a real window here: the save completes while the composable is
mid-recomposition or the app is briefly backgrounded, and `Saved` is discarded, leaving the
health worker on a form whose record has already been written with no indication either way.
`replay = 1` does not fix it, it inverts it — the event is re-delivered to the next collector,
so returning to the screen navigates away again.

**`Channel(BUFFERED)` — chosen.** Holds the event until someone collects it, delivers it to
exactly one collector, then it is gone. Exposed with `receiveAsFlow()`, not
`consumeAsFlow()`: the latter cancels the channel when its first collector is cancelled,
which happens on every configuration change, so the second collector would receive nothing
for the rest of the ViewModel's life.

`send` inside a coroutine rather than `trySend`, whose failure is a return value nobody
checks. A silently dropped navigation event is a screen that never closes.

**The test for state versus effect:** would replaying it be wrong? If yes, it is an effect.
This is why a rejected form is *not* an effect — validation errors must still be on the
fields after a rotation, so they are state.

## 5.4 The reducer is a pure function, outside the ViewModel

`CaptureReducer.kt` holds every transition of `CaptureUiState` as a top-level pure function.
`CaptureViewModel` decides *when* a transition happens and what asynchronous work surrounds
it; it never computes a next state itself.

The payoff is specific. State bugs in a form are combinational — the report is "the save
button stayed disabled", and the cause is some pairing of a keystroke, a failed write and a
rotation. Reproducing that through a UI means recreating the sequence by hand. Against a pure
function it is three lines, with no dispatcher, no Robolectric and no ViewModel, which is why
`CaptureReducerTest` runs in 27ms and cannot flake.

The list screen's equivalent is a projection, `List<Beneficiary> -> UiState`, rather than a
fold — there is no previous state to build on, because Room pushes the complete ordered list
on every change. The intents on that screen change no state at all; both are navigation, so
both produce only effects. Inventing a state change for them to justify the word "reducer"
would be architecture as costume.

## 5.5 Two reducer entry points, so the UI cannot claim a save succeeded

The textbook formulation is one sealed `Intent` hierarchy and one reducer. That hierarchy
must include the asynchronous results (`SaveSucceeded`, `SaveFailed`) so that every state
change goes through one function — and once it does, `dispatch(SaveSucceeded)` is a call a
composable can legally write.

Instead there are two pure functions: `reduce(CaptureIntent)` for what the user did, and
`reduce(SaveOutcome)` for what the save reported. `SaveOutcome` is `internal`, so the UI can
ask for a save but cannot announce one. Every transition still lives in one file; the
property that was wanted is kept, and the hole is closed by the type system rather than by a
comment asking people not to.

## 5.6 Parse errors belong to the UI; range errors belong to the domain

`CaptureErrors` carries two collections, and the split is a layering decision, not a
convenience.

Every numeric field on the form is a `String`, because a text box holds text: while someone
types a weight they pass through `"1"`, `"12"`, `"12."` and `"12.5"`, and only two of those
are a `Double`. State typed as `Double` has to decide what `"12."` means, and every available
answer breaks the keyboard.

So "is this text a number?" is a question only the UI layer can ask — `Beneficiary.ageYears`
is an `Int`, so `"abc"` cannot reach the validator. That check lives in
`CaptureUiState.toBeneficiary()`. "Is 400 a plausible age?" is a domain rule that must also
hold for records arriving from a sync pull or an import, so it stays in
`BeneficiaryValidator`, reached through `SaveBeneficiaryUseCase`, and is **not** duplicated
in the form.

`CaptureFormMappingTest` pins this boundary with a test asserting that `"400"` maps
*successfully* to a record. That test exists so a future well-meaning "let's validate earlier"
change fails in CI rather than in production, six months later, when the UI's bound and the
domain's bound have quietly diverged.

## 5.7 A successful save clears the form; a failed one does not

`SaveOutcome.Succeeded` resets to a blank `CaptureUiState`. Field work is record after
record, and leaving the previous beneficiary's details on screen is how the next child gets
the last child's village — a data-integrity bug wearing a convenience feature's clothes.

`SaveOutcome.Failed` preserves every keystroke. A storage failure is transient and not the
health worker's fault; clearing the form would make them re-enter a visit they already
recorded, on a handset that has just demonstrated it cannot be trusted to hold data.

Re-entrancy is handled in the reducer rather than the ViewModel — `SaveClicked` while
`isSaving` returns the same state — so "a save in flight absorbs further taps" is a property
of the state machine, provable without one. The ViewModel reads the value `getAndUpdate`
returns to decide whether to start work, because asking the *new* state would see
`isSaving` already true and never launch anything.

## 5.8 `SaveBeneficiaryUseCase` is injected into the capture screen, not the list

It was removed from `BeneficiaryListViewModel` on Day 8 when detekt flagged it as injected
but unused. That was correct and the fix was not to suppress the warning: a list screen does
not save. Keeping it would have meant holding a dependency whose only justification was that
it would be needed eventually, on the wrong class. It is injected here, where it is used.

---

# Part 6 — UI, the design system, and durable drafts

Day 11. The app stops rendering `Greeting("Android")` and starts being an app: two Compose
screens over the Day 10 state machines, a design system that finally contains the theme the
README always said it did, and autosave that survives the handset dying.

## 6.1 The theme moved to `:core:designsystem`, and what it grew on the way

Since Day 1 the README listed `:core:designsystem` as owning the Compose theme while the
theme sat in `:app` and the module was empty. That is a documented-but-false claim a reviewer
can check in about ten seconds, and it is a worse signal than an admitted gap.

Moving it was not a file move. Three things changed:

**Dynamic colour was removed, not carried over.** Material You derives a palette from the
user's wallpaper. That is a good default for a consumer app and the wrong one here, because
this app uses colour to carry clinical meaning — whether a record has reached the server —
and that signal must be legible on a low-end panel in direct sunlight. A palette generated
from an arbitrary photograph cannot be contrast-checked at build time, by a reviewer, or at
all. It is also Android 12+ only against a minSdk of 24, so supporting it would mean
validating two palettes for a purely aesthetic gain.

**Status colours were kept out of the Material scheme.** `colorScheme.error` is a *theming*
slot: it means "this looks like an error in this palette". A `StatusTone` means "this record
has not reached the server". Mapping one onto the other couples a clinical signal to a
styling decision, and they drift the first time anyone adjusts the brand colours. They travel
in a separate `CompositionLocal` instead — `staticCompositionLocalOf`, because the value
changes only when the whole theme does.

**The palette is teal rather than the template's purple.** A purple primary sits too close to
the amber-to-red attention range once a cheap screen washes out in daylight, which would make
the one distinction the list screen exists to communicate the hardest one to see.

**Concept — a false claim in documentation costs more than a missing feature.** An empty
module is a gap. An empty module the README describes as full is a reason to distrust the
rest of the README.

## 6.2 Spacing tokens, and making a lint rule work for the architecture

Every gap and inset comes from `Spacing`/`Sizing` in the design system. Beyond the usual
argument — a UI assembled from `16.dp`, `12.dp` and `18.dp` written on different afternoons
does not look designed, and the drift is invisible in review because each number looks
reasonable — there is a second effect worth naming.

detekt's `MagicNumber` rule is active project-wide and excludes only the design-system and
theme directories. So a literal `16.dp` in a feature module is a **build failure**. The
lint configuration and the design system therefore point the same way: the cheapest path
through CI is the one that uses a token.

**Concept — prefer a rule that makes the right thing easiest over a rule you suppress.** The
alternative was adding feature paths to `MagicNumber`'s excludes, which would have removed
the rule's value everywhere to solve a problem the design system was going to solve anyway. A
lint rule that fights the architecture should change the architecture or be deleted; leaving
it in place with a growing exclusion list gets the costs of both.

Tokens are named by role, not size. `Gutter` survives a decision to make it 20.dp;
`Space16` does not.

## 6.3 Drafts live in Room, not DataStore

"Offline autosave of in-progress form state" needed a store. Three candidates:

**`SavedStateHandle` — rejected.** Survives rotation and system-initiated process death. Does
not survive the user swiping the app away, a crash, or the battery going flat. For a health
worker on a cheap handset at the end of a long day, the battery is the case that matters, so
this does not actually deliver the feature.

**DataStore — rejected, and it was the planned choice.** The Reconciliation sheet had penned
it in as "a ~30-minute integration folded into whichever day needs session state". It offers
the same durability as Room. It costs a second persistence mechanism for one object: a second
thing to migrate, a second thing to encrypt when the security work lands on Day 16, a second
place to look when data goes missing. Choosing it would have bought a named technology for
the resume at the cost of a worse system — which is the trade this project's plan explicitly
warns against making.

**Room — chosen.** The database, its migration story and its backup policy already exist.

A single-row table with a fixed primary key, so "there is one draft" is enforced by SQLite
rather than by discipline. Every measurement column is `TEXT` while the same measurement in
`beneficiaries` is `REAL`, and that disagreement is correct: a record's weight is a number, a
draft's weight is whatever has been typed so far, and `"12."` is not a number. Coercing at the
storage layer would mean the draft that comes back is not the draft that was saved.

### The migration is hand-written, and the overload matters

Adding a table bumps the schema to 2, and there is no `fallbackToDestructiveMigration` to
absorb that — deliberately, since it resolves schema changes by deleting a database holding
unsynced field data.

`@AutoMigration` would have generated this one correctly; adding a table is the case it
handles best. It is written out anyway, because the *next* migration will rename or backfill a
column and auto-migration cannot infer that. One mechanism established early beats two.

The trap worth recording: Room 2.7 added `migrate(connection: SQLiteConnection)` alongside
`migrate(db: SupportSQLiteDatabase)`, both are open, and **both throw `NotImplementedError` by
default**. Overriding the wrong one compiles, looks complete, and crashes on the first real
upgrade. Which one runs depends on how the database was built: `Room.databaseBuilder` with no
`setDriver` uses the framework driver, and the connection overload unwraps it and delegates.
So `SupportSQLiteDatabase` is correct here — and would silently become wrong if this project
adopted a `SQLiteDriver`, for example when moving to `androidx.room3`.

### One rule, in a use case

`SaveDraftUseCase` deletes rather than stores a blank draft. Without it, clearing the form
leaves six empty strings on disk, the next launch dutifully restores them, and the capture
screen can never start genuinely fresh. The same path runs after a successful save, so the
rule also stops a committed record leaving a ghost that looks like unfinished work. Clearing
is therefore `invoke(CaptureDraft.Empty)` — one code path, reading as what it is.

## 6.4 Autosave is debounced, ordered, and allowed to fail quietly

Three decisions inside roughly fifteen lines, each of which is a bug if taken the other way.

**Debounced at 400ms, keyed on the draft rather than the state.** A write per keystroke is a
database transaction per keystroke, on a low-end handset, while someone types into it.
`distinctUntilChanged` runs on the mapped `CaptureDraft`, not on `CaptureUiState`, because
focus changes, save attempts and error clearing all produce a new state without changing a
character of typed text — comparing states would restart the timer for none of the reasons
that matter.

**Restore strictly before autosave, in one coroutine.** If the collector started first it
would observe the blank initial state, write it, and the restore would then be recovering a
draft it had already destroyed. Sequencing them in a single `launch` makes that impossible
rather than unlikely, because `collect` never returns.

**The draft is cleared explicitly on a successful save, not left to the debounce.** Otherwise
the draft outlives the record it produced for 400ms, and a process death inside that window
restores a form for a visit that is already saved — inviting the same child to be entered
twice.

**Failures are swallowed with a log, and only here.** `OfflineFirstBeneficiaryRepository`
returns an `Outcome` because a failed record write must reach the health worker. A failed
autosave must not: it retries on the next keystroke, and interrupting someone mid-form to
report it is worse than the failure. detekt's `SwallowedException` rule is active precisely
because silent catches in persistence code are how data loss becomes invisible — so the
exception is logged, not discarded, and this is the one place the exemption is taken.

## 6.5 No Navigation Compose for two destinations

Decided during planning; the consequence lives in `AstraCareApp`. What `androidx.navigation`
buys is a typed graph, argument marshalling, deep links, and a back stack that survives
process death. This app has no arguments, no deep links, and a back stack one entry deep.

What it costs is not the dependency but the shape: routes become strings or serializable
types, every screen acquires a `NavController`, and previewing a screen means faking one.
Worth paying at eight destinations; ceremony at two, and ceremony adopted early is ceremony
nobody revisits.

The hand-rolled version still does the two things a `NavHost` would have done: a `BackHandler`
enabled only where there is somewhere to go back to, and `rememberSaveable` with a hand-written
`Saver`, so the current destination survives process death. Without the latter, a phone that
reclaimed the app mid-form would return the health worker to the list — their draft intact,
which is what 6.3 is for, but with no indication of where it went.

**The line to watch:** the first destination that takes an argument — the record detail
screen, probably Day 12 — is the point where this starts reimplementing argument passing and
the library wins. `hiltViewModel()` is already in use, from `androidx.hilt:hilt-navigation-compose`,
which despite its name carries no dependency on Navigation Compose.

## 6.6 Route and Screen are separate composables

Every screen is two functions: `CaptureRoute` owns the ViewModel and turns effects into
navigation, `CaptureScreen` is a pure function of its arguments.

The split is what makes the second half previewable in the IDE, screenshot-testable, and
drivable from a Compose UI test with no Hilt graph and no database. Combined into one
composable, every preview would need dependency injection — which in practice means no
previews, which in practice means the UI is only ever seen by running the app.

Effects are collected through a shared `ObserveEffects` helper rather than inline, because
both screens need it and both would get it subtly wrong. `LaunchedEffect(Unit) { collect }` —
the version everyone writes first — keeps its coroutine alive while the screen sits in the
back stack, so a screen the user cannot see goes on receiving navigation events and acting on
them. `repeatOnLifecycle(STARTED)` cancels collection when the screen stops; the `Channel`
from Day 10 buffers the event meanwhile, so pausing defers delivery rather than dropping it.
The two choices only work together — `repeatOnLifecycle` over a `SharedFlow(replay = 0)` would
genuinely lose the event.

`rememberUpdatedState` keeps the long-lived collector pointed at the current lambda without
restarting it, which would re-subscribe to the channel.

## 6.7 The design system does not know what a beneficiary is

`StatusChip` takes a `String` and a `StatusTone`, not a `SyncStatus`. `:core:designsystem` has
no dependency on `:core:model`, and this is the decision that keeps it that way.

The obvious component takes a `SyncStatus` and decides internally how to colour and word it.
That would put three decisions in the wrong module: that CONFLICTED is critical while FAILED
is only a warning (a product judgement about this app); that FAILED reads "Retrying" rather
than "Failed" (telling a health worker something failed when the app has not given up invites
them back to paper); and what any of it is called in the user's language.

So `SyncStatusPresentation` in the feature module maps status to text-and-tone, and the design
system renders what it is handed — usable by a second feature that has nothing to do with
sync.

The chip also takes a required `contentDescription` separate from its visible text, because
the two should differ: "3 pending" on screen, "3 records waiting to sync" to a screen reader.
Required rather than optional makes the accessible wording a decision at every call site
instead of an omission at most of them.

## 6.8 Validation wording is a string resource, and the bounds are not duplicated

`ValidationError.WeightOutOfRange` carries the offending value and no opinion about language.
`CaptureErrorText` turns it into "Weight should be between 0.5 and 300 kg", from
`res/values/strings.xml`. Neither could do the other's job: the domain cannot know the locale,
and the composable cannot know the clinical bounds.

This is the other half of 5.6, and it is why the domain module needs no translation at all.
For an app whose users read Hindi, that is a requirement the project has already committed to
in `ValidationError`'s own KDoc, not a hypothetical.

One honest gap: the numbers inside those messages restate `BeneficiaryValidator`'s bounds. The
alternative — interpolating them out of the error type — produces "between 0.5 and 300.0 kg"
and an English sentence assembled from fragments no translator can reorder. Listed as an open
item rather than pretended away.

Only the first error per field is shown. A field with two problems has one worth reporting
first, and stacking messages under an input pushes the rest of the form off a small screen —
which is how someone ends up unable to see the field they are fixing. A malformed value
outranks a range violation, because if the text is not a number the range check never ran.

---

# Part 7 — Paging, and where a sort order lives

Day 12. The history list becomes a `PagingSource` over Room. The interesting part is not the
library; it is that paging forced two decisions this project had already made in the opposite
direction, and both had to be reopened honestly rather than quietly reversed.

## 7.1 The domain layer names `PagingData`

`BeneficiaryRepository` now returns `Flow<PagingData<Beneficiary>>`, so `:core:domain` depends
on `androidx.paging:paging-common`.

That looks like the layering violation this project has spent eleven days avoiding, so it is
worth being exact about what the rule actually is. The claim is that `:core:domain` is a
Kotlin/JVM module with no Android SDK on its classpath, enforced by the compiler rather than
by convention. `paging-common` is a Kotlin Multiplatform library publishing JVM, Android,
native and JS variants; a `java-library` module resolves its `standard-jvm` variant. The
module stays Kotlin/JVM and `import android.*` still does not compile. The rule holds.

What is genuinely true is that the domain's contract now speaks a pagination vocabulary, and
pagination is arguably presentation. Two alternatives were weighed:

**Keep Paging out of the domain.** The ViewModel would call `:core:data` directly, so
`:feature:patients` would depend on the data module. That destroys the property proved on
Day 9 — swapping the repository implementation was a one-line change to a single `@Binds`,
with nothing in domain, UI or app touched. Trading a structural guarantee for the absence of
one import is a bad trade.

**Define an in-house pagination abstraction** in the domain for `:core:data` to adapt onto.
Purest on paper. In practice a worse reimplementation of a contract that already exists,
maintained forever so that a type name never appears.

**Concept — know which rule you are actually enforcing.** "The domain has no Android
dependency" is checkable and valuable. "The domain names no library type" is a different,
much stronger rule that this project never claimed and could not keep — `kotlinx.coroutines`
`Flow` is all over the domain already, and nobody calls that a violation.

**Watch for:** the Android and JVM variants of a KMP library are different artifacts. The
domain module compiles against `paging-common-desktop` while everything downstream resolves
`paging-common-android`. Their APIs are generated from the same common source, so this is
routine — but it is the kind of thing that surfaces as a puzzling `NoSuchMethodError` rather
than a build failure, so it is written down here rather than discovered twice.

## 7.2 Sort order: declared in the domain, executed in SQL

Day 9's `BeneficiaryDao` carried an explicit comment saying ordering was deliberately NOT done
in SQL, because sort order is a domain rule and splitting one decision across two layers is
how the two halves come to disagree. `ObserveBeneficiariesUseCase` held a `sortedWith`
comparator.

Paging makes that impossible. Pages load a few dozen rows at a time, so a Kotlin comparator
sorts the rows currently in memory and nothing else — the fifth conflicted record sits below
the thirtieth synced one, because the two were never in the same list. The reasoning behind
the original decision was sound; its conclusion was invalidated by a fact that was not true
when it was made.

The resolution separates two things the original comment had treated as one:

- **The rule** stays in `:core:domain`, as `RecordAttentionOrder.byUrgency` — an ordered list
  of `SyncStatus`. CONFLICTED before FAILED before PENDING before SYNCED, with the reasoning
  attached. Reordering it is a one-line product decision, reviewable as such.
- **The execution** moves into `BeneficiaryDao.pagedByAttention`, whose `ORDER BY CASE` binds
  those values as query parameters. Nothing in the DAO decides that CONFLICTED beats FAILED;
  it knows only that there are four ranks and where they come from.

`ELSE 0` puts an unrecognised status ahead of everything. A status the query has not been
taught about is one the app cannot vouch for, and the safe failure is to show it rather than
bury it — the same reasoning as the mapper defaulting an unknown stored value to PENDING, and
as the sync count being "not SYNCED" rather than an enumeration of the other three.

**The seam this opens.** Nothing in the type system connects the Kotlin list to the four `WHEN`
arms. They can drift, and drift is silent: no crash, just a conflicted record quietly sorting
below synced ones on the screen that exists to surface it. `RecordAttentionOrderTest` is the
guard — it asserts every status has a rank, none is declared twice, the count matches the
DAO's arms, and the urgency order is what the product intends. They are the cheapest tests in
the project and they exist precisely because the failure is invisible.

**Not done: an indexed rank column.** A `CASE` expression cannot use an index, so this is a
scan plus a sort. The production answer at scale is an `attention_rank INTEGER` column written
at upsert time and indexed. That is a schema version and a data backfill, for a table holding
at most a few thousand rows on one health worker's handset. Deferred to the benchmark work,
where there will be a measurement instead of a guess — and where a backfill migration is
useful to have written, since Part 6 noted that auto-migration cannot generate one.

## 7.3 The sealed UiState dissolves rather than wrapping Paging

Day 10 gave this screen `Loading | Empty | Content` and argued the three genuinely exclude one
another. Paging answers the same question better: `LazyPagingItems.loadState` reports refresh,
append and prepend separately, which is strictly more information than one enum can carry.
Keeping both would mean two sources of truth about what the screen is doing, disagreeing
during a reload.

So it is deleted, not wrapped. What could not come from Paging is the count of unsynced
records — paged data holds only the rows near the viewport, so counting it would report a
number that changes as the user scrolls, which is worse than no number because it looks
authoritative. That is a separate `COUNT(*)` query and a small `SyncSummaryUiState`.

Day 10's decision that this screen needs no `Error` state still holds: the source is a local
database and cannot fail a load. Paging brings an error branch and a `retry()` with it, and
both stay unimplemented until `RemoteMediator` makes failure reachable. Building a retry
button now would mean shipping UI nobody can reach and no test can exercise, which is how a
codebase accumulates code that looks tested because it is never run.

## 7.4 `cachedIn` is not a performance tweak

`records = observeBeneficiaries().cachedIn(viewModelScope)`. Two things break without it, and
only one is visible:

- On a configuration change the `Pager` restarts from page zero, so the health worker who had
  scrolled forty records into their day is returned to the top by rotating the phone.
- Collecting the same `PagingData` flow more than once **throws**. A screen that renders the
  list and also reads its load state elsewhere would crash — in whichever build someone first
  writes that second collector, not in the one that introduced the bug.

It must be the last operator applied; anything after it runs per collector and defeats it.

Related, and easy to get wrong in the other direction: the `pagingSourceFactory` passed to
`Pager` is a factory rather than a value because Room invalidates a `PagingSource` on every
write and Paging then asks for a fresh one. Passing `dao.pagedByAttention(...)` directly hands
it the same invalidated instance forever, and the list silently stops updating after the first
save — with no error anywhere.

## 7.5 The Pager is built in the data layer

Most examples construct `Pager` in the ViewModel. `PagingConfig` is a statement about storage
— how many rows a read fetches, how far to prefetch, whether the source can report a total —
and a ViewModel choosing those numbers is a ViewModel making decisions about a database it is
not supposed to know exists. The moment two screens read the same table they will choose
differently.

`enablePlaceholders = false`. Placeholders let the list report a true total and render blank
rows for unloaded data, which keeps the scrollbar honest. They also require every row's height
to be known before its content exists, and these rows do not have that property — a long
village name wraps. Getting it wrong shows up as the list jumping under the user's thumb mid
scroll, which on a screen used one-handed outdoors is worse than a scrollbar that grows.

## 7.6 A fake that deliberately does not sort

`FakePagedBeneficiaryRepository` returns records in insertion order, and
`BeneficiaryListViewModelTest` asserts nothing about ordering.

That is the boundary, not an omission. Ordering now lives in SQL, so any unit-level fake
stands in for the query. A fake that sorted correctly would prove only that the fake was
written correctly, and would go on passing while the real `ORDER BY` was wrong — a test that
is worse than no test, because it converts an open question into false confidence.

So the contract is checked where each half can actually be checked: `RecordAttentionOrderTest`
pins the domain's declaration, and the SQL needs an instrumented test against a real database,
which is listed as a gap rather than faked.

**Concept — a fake must not be able to satisfy an assertion the real thing would fail.** When
it can, the honest move is to move the assertion, not to improve the fake.

---

# Part 8 — Pushing records to a server

Day 13. `:core:sync` stops being an empty directory. A record captured offline now reaches the
mock server on its own, and the chip on the history screen flips from Pending to Synced without
anyone asking it to.

## 8.1 The algorithm is a use case; the Worker is ten lines

`PushPendingRecordsUseCase` holds the read-push-mark loop. `PushBeneficiariesWorker` calls it
and maps the result to a WorkManager `Result`.

The reason is testability, and here it is not a general principle but a specific one: **this is
the code that can lose a health worker's field data.** A `CoroutineWorker` cannot be
constructed without a `Context` and `WorkerParameters`, so logic written inside one is testable
only under instrumentation or Robolectric — the slowest tests in any project, and therefore the
ones that quietly stop being written. Written as a plain class with two injected interfaces,
every branch runs in milliseconds.

`PushPendingRecordsUseCaseTest` covers seven paths including the one below. None of them needs
Android.

**Concept — put the logic where the tests can reach it.** "Testable" is not a quality of code
in the abstract; it is a question of what has to exist before a test can run. Framework base
classes are where that cost hides.

## 8.2 The stale write: a bug with no symptom

The dangerous moment in any push loop is between reading a record and marking it sent. The app
is open, the push is on a background thread, and the health worker can edit the record in that
window.

Mark it SYNCED afterwards and the app has declared the *new* version sent when only the old one
was. Nothing looks wrong: the row reads SYNCED, the chip is grey, the sync count is correct.
The edit is simply gone, and nobody finds out until someone compares the handset with the
server — which, for an app whose users are in villages, may be never.

So the mark is conditional:

```sql
UPDATE beneficiaries SET sync_status = :status
WHERE id = :id AND updated_at = :unchangedSince
```

`updateSyncStatus` returns whether a row actually changed. False is a normal outcome, not a
failure — it means a newer version exists locally and belongs in the next pass.

Every fake in the test suite implements the same condition. A fake that marked unconditionally
would let this bug pass a test, which is the one thing a fake must never do.

**Concept — the bugs worth engineering against are the ones with no symptom.** A crash gets
fixed. Silent data loss gets discovered by the user, months later, in the form of mistrust.

## 8.3 REJECTED is a fifth status, and the Day 12 guard caught it

A server can refuse a record permanently — a validation error, a schema it will not accept.
That is not FAILED, whose documented meaning is "retrying may fix this", because retrying never
will: the handset would re-offer the record on every pass until the battery died. It is not
CONFLICTED either, which means the server holds a *newer* version of a record it accepts.

Adding `SyncStatus.REJECTED` rippled exactly as far as Day 12 predicted it would:

- `RecordAttentionOrder.byUrgency` needed a fifth entry.
- `BeneficiaryDao.pagedByAttention` needed a fifth `WHEN` arm and a fifth parameter.
- `SyncStatusPresentation` needed a branch, and `strings.xml` two entries.

The first two are connected by nothing the compiler can see — a Kotlin list and a SQL `CASE`.
`RecordAttentionOrderTest` is what pointed at them, by failing on a constant that says how many
arms the query has. The third was caught by the compiler, because that `when` is exhaustive
over a sealed set with no `else`.

**Concept — a guard is worth what it catches the day you forget.** The ordering test was
written on Day 12 for a hypothetical. It earned itself back on Day 13.

No migration was needed: `sync_status` is TEXT storing the enum's *name*, so an added value is
just a value the column has not seen yet. Had it been the ordinal, this would have silently
reinterpreted existing rows — the reason that decision was made on Day 9.

## 8.4 `pendingSync` narrowed from "not synced" to "retryable"

The old query was `WHERE sync_status != 'SYNCED'`, which was right when SYNCED and PENDING were
effectively the only states. It now also matches REJECTED and CONFLICTED — records the server
has already answered for, and which need a person rather than another attempt. Left alone, the
handset would re-push them on every pass forever.

It now takes the retryable set explicitly: PENDING and FAILED. Those records still count toward
the sync banner, because they are still not on the server and the health worker needs to know
that. "What should we send" and "what is not safe yet" turn out to be different questions, and
one query cannot answer both.

## 8.5 A transient failure stops the whole pass

On `TransientFailure` the loop returns immediately rather than trying the next record.

A transient failure is almost always the connection, not the record. Continuing means forty
more attempts that will fail the same way, each waking the radio, on a handset that has to last
a working day in a place with no charger. Everything already accepted stays marked; the rest is
still PENDING, which is the database telling the truth about what needs sending.

A permanent rejection is the opposite and the loop keeps going, because the server is clearly
reachable and the next record may well be fine.

## 8.6 Two schedules, and neither is enough alone

`requestPush()` fires after every successful local write. `ensurePeriodicPush()` runs hourly.

Only the first, and a push that failed while the app was closed waits for the next capture —
which on a quiet day is tomorrow. Only the second, and a record captured with full signal and
the app open sits Pending for up to fifteen minutes, which teaches a health worker that sync
does not work.

Three WorkManager choices worth naming:

**`ExistingWorkPolicy.KEEP`, not `REPLACE`.** Capturing five records in a minute calls
`requestPush()` five times. KEEP lets the queued pass run, and since every pass sends
*everything* pending, the later records are included anyway. REPLACE would cancel and requeue
each time — so a worker two minutes into its backoff would be thrown away and restarted at the
beginning of the curve, which is the opposite of what backoff is for.

**`NetworkType.CONNECTED`, not `UNMETERED`.** A record is a few hundred bytes. Waiting for wifi
means a worker walking a village on mobile data syncs nothing all day, and wifi is not
something most of them see before going home. Metered data is the normal case here.

**Hourly, not the 15-minute minimum.** The periodic pass is a backstop for a failure, not the
primary path. Waking the radio four times an hour to find nothing to send is a real battery
cost on the handsets this targets.

The one-time pass also gives up after five attempts rather than retrying forever, and lets the
periodic one take over. Nothing is lost — the records are still PENDING, and the database is
the source of truth about what needs sending, not the work queue.

## 8.7 The mock server fails in the shapes a real one does

A real backend was ruled out during planning as unbounded work that proves nothing about
Android engineering. What a mock still owes is **realistic failure**, because the failure modes
are what the sync engine is built around. A stub that always succeeds ships every error branch
untested while looking like coverage.

So `MockRemoteBeneficiarySource` does three things a server does: accepts idempotently, rejects
permanently, and fails transiently every fifth call.

The idempotency is the one that matters most. A response can be lost after the server commits —
the connection drops between the write and the acknowledgement — and the only safe client
behaviour is to send again. That is only safe because **IDs are minted on the device**: the
server upserts by a key it did not choose. A server assigning its own IDs would create a
duplicate for every lost response, and the health worker would have no way to tell which record
was real. That was decided on Day 7 for offline-creation reasons; this is the second, larger
payoff.

The transient failure is deterministic — every fifth call — rather than random. A random mock
produces a demo that sometimes misbehaves and a bug nobody can reproduce.

## 8.8 Three pieces that only work together

`@HiltWorker` needs all of:

1. the annotation, from `androidx.hilt:hilt-work`;
2. `androidx.hilt:hilt-compiler` on KSP, which generates the factory entry;
3. `AstraCareApplication` implementing `Configuration.Provider` and supplying
   `HiltWorkerFactory`, **with the default `WorkManagerInitializer` removed from the manifest**.

Any one missing produces a **runtime** failure, not a build failure — "Could not instantiate
PushBeneficiariesWorker", a message that names the worker rather than the two lines of manifest
or the one missing KSP declaration that actually caused it.

Also worth recording: `Configuration.Provider` became a `val` property in WorkManager 2.9.
Overriding the old `getWorkManagerConfiguration()` method still compiles — as an unrelated
function that nothing calls — so the configuration is silently never read.

**Concept — wiring that fails at runtime deserves a comment where the wiring is.** All three
sites carry one, because the failure gives no hint which of them is missing.

---

# Part 9 — Pulling from the server, and conflicts

Day 14. The day the project is actually judged on: everything before this is competent
plumbing that many Android codebases have. What a server sends back, and what the app does when
it disagrees with what is on the handset, is the part that is usually got wrong — and got wrong
invisibly.

## 9.1 The conflict rule reads the sync status, not the clock

The one-line version of last-write-wins is:

```kotlin
if (remote.updatedAt > local.updatedAt) applyRemote() else keepLocal()
```

It is what most offline-first tutorials do. On this app it silently deletes field data, for two
independent reasons.

**It resolves cases that are not resolvable.** A supervisor corrects a village name on the
server while a health worker corrects the child's weight on the handset. Both edits are real,
neither is wrong, and one of them is about to be discarded with no record that it existed.

**The comparison is not sound.** `updatedAt` is stamped from the device's wall clock. That
clock drifts, jumps when NTP corrects it, and can be set by hand — which DECISION_LOG 2.6 has
said since Day 2. A handset ten minutes fast wins every race it enters, permanently, and
nothing about the resulting data looks wrong.

The way out was to notice that the question a conflict detector actually needs to answer is not
*"which version is newer?"* but ***"does this device hold an edit the server has never
seen?"*** — and the device knows that for certain, without consulting any clock. It is exactly
what `SyncStatus` records:

- `SYNCED` means the server acknowledged **this exact version**. Day 13's conditional mark
  (8.2) is what makes that trustworthy: a record edited during its own push is never marked
  SYNCED, so the status cannot overstate what the server has.
- Anything else means there is local work in flight.

So the rule is: **if the local row is SYNCED, the server's version supersedes it and applying
it loses nothing. If it is not SYNCED, both sides have moved and neither may be overwritten.**

```kotlin
when {
    local == null                        -> AcceptRemote(remote, replacing = null)
    local.syncStatus == SYNCED           -> acceptUnlessAlreadyCurrent(local, remote)
    local.hasSameContentAs(remote)       -> AcceptRemote(remote, replacing = local.updatedAt)
    local.syncStatus == CONFLICTED       -> KeepLocal
    else                                 -> FlagConflict(local.id, local.updatedAt)
}
```

`ConflictResolver.resolve` performs **no timestamp comparison at all**, and
`ConflictResolverTest` asserts that as a property rather than leaving it as a claim: the same
pair of records is resolved twice with the timestamps swapped, and both must give the same
answer. That test exists to fail when someone later "fixes" a conflict by reaching for
`updatedAt`.

The third branch is worth its own note. Two sides can hold an unsent edit and still agree —
two people made the same correction, or an earlier push was accepted and the acknowledgement
was lost. Nothing is in conflict when nothing differs, and flagging it anyway teaches the
health worker that the red chip means nothing.

**Concept — a correctness property that costs nothing to state is worth stating as a test.**

## 9.2 Content equality is "normalise and compare", not a field list

`hasSameContentAs` could have been five `&&`-joined field comparisons. It is this instead:

```kotlin
private fun Beneficiary.hasSameContentAs(other: Beneficiary): Boolean =
    copy(updatedAt = other.updatedAt, syncStatus = other.syncStatus) == other
```

The difference is a correctness one. A field added to `Beneficiary` and forgotten in a
hand-written list makes two genuinely different records compare equal — and *a conflict that
compares equal is a conflict that silently disappears*. This form fails the other way: a new
field participates automatically, and the worst case is a conflict flagged that need not have
been. One of those is recoverable by a person; the other is not.

`ConflictResolverTest` walks every field individually and asserts that changing it produces a
conflict, so the property is pinned rather than assumed.

**Concept — when two failure modes are available, pick the one a human can see.**

## 9.3 Detect, do not resolve

A conflicted record is marked `CONFLICTED`, keeps its local version, shows a red chip and sorts
to the top of the history list. And then it stops. There is no merge screen, no "keep mine /
keep theirs", no field-level picker.

That is a boundary, chosen, not a feature that ran out of time. Three options were weighed:

- **Auto-resolve everything.** Fast to build, and it is last-write-wins wearing a hat. Rejected
  for the reasons in 9.1.
- **Auto-resolve, with a merge UI for the rest.** The right end state, and it is a screen, a
  field-level diff, an interaction model and a set of tests — comfortably more than a day, on
  the day that also has to build the pull.
- **Detect, flag, and stop.** Nothing is lost: the local version is in the database, the
  server's is on the server, and the record is visibly waiting for a person.

The third is a *visible* dead end, and that is the whole argument. A wrong auto-resolution is
invisible — the record looks fine and the data is gone. A record a health worker cannot resolve
yet is annoying, and annoying is recoverable.

`SyncStatus.CONFLICTED` has existed since Day 7 and until today nothing could produce it, which
was noted at the time: a push cannot discover a conflict, only a pull can.

## 9.4 The pull is a use case, not a `RemoteMediator` — correcting 7.3

DECISION_LOG 7.3 predicted that Paging's error and retry branch would become reachable "via
RemoteMediator" on Day 14. That prediction was wrong, and it is worth correcting rather than
quietly leaving.

`RemoteMediator` is Paging's hook for *network-backed paging*: the list scrolls, Paging asks for
more, the mediator fetches a page and writes it to the database. It is the right tool when the
dataset is the server's and the local table is a window onto it.

This app is the other shape. The dataset belongs to the handset — a health worker's own
captures, which exist and must be listable with no connectivity at all — and the server is a
peer that occasionally has changes. Binding the pull to a `RemoteMediator` would make the sync
fire on *scroll*, on the UI's schedule, and not at all for a device sitting in a pocket. The
pull is therefore a plain use case beside `PushPendingRecordsUseCase`, run by the same Worker.

The consequence: Paging still never sees a network error, because nothing in the Paging path
ever touches the network. `LoadState.Error` remains unreachable, and it remains an open item —
now correctly described, rather than pencilled in against a day that was never going to fix it.

**Concept — a prediction in a decision log is a decision to revisit, not a promise to keep.**

## 9.5 The delta cursor is opaque, and that is a data-safety decision

The obvious signature for a delta pull is `pullChangedSince(since: Timestamp)`. It has a
failure mode that is invisible until it loses data.

The server would answer it by comparing `since` against each record's `updatedAt` — and
`updatedAt` is stamped by the *device that captured the record*, from a clock the server does
not control. A handset whose clock is ten minutes slow pushes a record stamped ten minutes in
the past. If another device has already pulled past that point, the record falls behind the
window and **is never delivered to anyone**. Nothing errors. The record simply does not arrive.

So the cursor is `SyncCursor`, an opaque value class holding a String the client stores and
hands back untouched. A sequence number, an etag, a Postgres LSN, a page token — the client
cannot tell and must not care. Making it opaque is not fastidiousness: it removes the *ability*
to compare it to a local clock, which is the only reliable way to stop someone doing so.

This is also why `MIGRATION_2_3` creates `sync_state` with no row. Seeding a cursor from
`MAX(updated_at)` over the existing records would spare an upgrading device a full re-download
and would skip exactly the records described above. A slow first pull is recoverable; a record
the server never sends again is not.

## 9.6 The cursor advances once, at the end of the pass

Not per record. The ordering is the whole safety property:

- **Advance late** (as now): a crash halfway through re-delivers records already applied on the
  next pull. Free, because resolution is idempotent — re-applying a record that is already
  there yields `KeepLocal`, and `PullRemoteChangesUseCaseTest` asserts the replay writes
  nothing.
- **Advance per record**: a pass that dies at record five leaves records six to forty behind
  the window, and the server never offers them again.

One costs a repeated request. The other loses data with no symptom. `RoomSyncStateRepository`
therefore also does *not* catch exceptions, which is the deliberate opposite of
`RoomDraftRepository` (6.4): a cursor that failed to save and was treated as saved is the same
bug arriving by a different route.

## 9.7 The stale write, from the other direction

Day 13 (8.2) guarded the window between reading a record and marking it sent. The pull has the
mirror image: between *deciding* what to do with a record and *writing* that decision, the
health worker can edit it. Applying the server's version then replaces an edit that has never
been sent anywhere, and the row looks perfectly healthy afterwards.

Every write on the pull path is therefore conditional on the local row not having moved, and
both statements refuse rather than overwrite:

- `replaceIfUnchanged` — a `@Transaction` over a read and an upsert, applied only if
  `updated_at` still matches what the decision was made against.
- `insertIfAbsent` — `@Insert(onConflict = IGNORE)`. `REPLACE` would delete a local capture
  that appeared since the decision and insert the server's row over it.

A refused write is a normal outcome, not an error: the decision was made against a version that
no longer exists, and the next pull decides again — this time seeing the local edit and
flagging a conflict. The fakes in every test enforce the same refusal, because a fake that
wrote unconditionally would let the test pass against production code that overwrites.

`replaceIfUnchanged` is a transaction over a read and an upsert rather than one nine-column
conditional `UPDATE`. Both are atomic; the transaction cannot drift. A hand-written column list
is a second place the schema is enumerated, and a column added to the entity and forgotten
there would be silently preserved from the stale local row while every other field came from
the server — producing a record that never existed on either side.

## 9.8 Push before pull, and an interrupted push skips the pull

The pass is push, then pull. Pushing first means a record the device has been holding reaches
the server *before* the pull asks what the server has, so it comes back as accepted rather than
as a competing version and resolves silently. The reverse ordering manufactures conflicts out
of records that were about to sync cleanly — and conflicts have to be rare enough that one
means something.

If the push is interrupted, the pull does not run. `PushSummary.Interrupted` means the
connection dropped or the server is unreachable, so the pull would almost certainly fail the
same way, on a handset whose battery has to last a working day. It is the same reasoning that
stops the push loop rather than trying the next forty records (8.5).

The honest cost: on the rare failure specific to one record rather than to the connection, the
pull waits for the retry. Bounded, and cheaper than the alternative.

## 9.9 The mock server now changes on its own

`MockRemoteBeneficiarySource` gained a delta pull and, more importantly, a deterministic
server-side edit every third pull — the oldest record it holds gets its village "corrected", as
another health worker's handset would do. Without that, a pull on a single device only ever
returns what that device pushed, `CONFLICTED` stays unreachable outside the unit tests, and the
whole feature is undemonstrable on a real handset.

Two details that would have made the mock useless:

**The server's clock is its own.** The mock's sequence counter is seeded once from
`TimeProvider` and advances by itself afterwards. Stamping server-side edits with
`timeProvider.now()` would model a server whose clock agrees with the handset's exactly —
hiding the one condition the entire design is built around.

**A push does not restamp `updatedAt`.** That field is when the record was *edited*, which the
capturing device knows and the server does not. Restamping it on accept would make every
subsequent pull look like a change and rewrite the whole table on every pass.

## 9.10 WorkManager's queue outlives the code that wrote it

`PushBeneficiariesWorker` became `SyncBeneficiariesWorker`, and `SyncScheduler`'s methods
became `requestSync`/`ensurePeriodicSync` — renamed rather than joined by a `requestPull`,
because two entry points would let a caller ask for half a sync and the ordering of the halves
is a correctness property (9.8), not a caller's choice.

The rename is not free. WorkManager's queue lives in its own database, and the periodic entry
written by an earlier build names a class that no longer exists. On upgrade it keeps firing and
keeps failing with a `ClassNotFoundException` naming a class nothing in the source tree
mentions. `ensurePeriodicSync` therefore cancels the Day 13 work names first — three lines, a
no-op on a fresh install, and the kind of thing that otherwise costs somebody a confusing hour.

## 9.11 Replacing a Hilt module replaces all of it

Adding `SyncStateRepository` to `DataModule` surfaced a latent break in the instrumented test
graph. `@TestInstallIn(replaces = [DataModule::class])` deletes **every** binding in that
module, not only the one being faked — and `TestDataModule` was restoring just
`BeneficiaryRepository`, so `DraftRepository` and `RemoteBeneficiarySource` had been missing
from the test graph since the days they were added. Nothing caught it because the instrumented
source set is excluded from CI (4.5).

Fixed by restating the other bindings, with a comment saying why they look redundant. The
narrower tool — `@UninstallModules` with `@BindValue` on the one test that needs the fake —
does not have this failure mode and is the right answer if that list grows again.

**Concept — a coarse test double has a blast radius, and it grows every time the real module
does.**

## 9.12 Schema v3, and the second migration earning its keep

`sync_state` is a third table: one row, one nullable TEXT column. It could have been DataStore,
and the argument against is sharper than it was for drafts (6.3). The cursor has to stay
consistent with the rows it describes. A cursor that survives a backup/restore while the
records do not tells the server this device is current on data it no longer holds, and the
server never resends it. One file means one backup, one restore, one encryption boundary when
that work lands, and no window in which the two disagree.

`Migrations.kt` was written by hand on Day 11 on the argument that the *next* migration would
be the one auto-migration could not infer. That turned out to be right in an unexpected way:
`MIGRATION_2_3`'s `CREATE TABLE` is trivially generatable, and the decision that matters is
what to put in the new table (9.5) — which no schema diff can reason about at all.

---

## Open items

| Item | Status | Resolve by |
|---|---|---|
| ~~Move `DispatchersModule` to `:core:common`~~ | **Resolved** — moved, via the `astracare.jvm.hilt` convention plugin and `hilt-core` (2.5) | Closed |
| Client wall-clock time is not monotonic (2.6) | Narrowed — conflict detection no longer depends on it (9.1) and the delta window uses an opaque cursor (9.5). Remaining exposure is display ordering by `recorded_at` | Kronos, or a server-stamped receipt time, if timestamps ever become user-visible evidence |
| ~~detekt 1.23.8 vs Kotlin 2.2.10 compatibility~~ | **Resolved** — detekt parses Kotlin 2.2.10 without error; its embedded compiler handles the newer syntax | Closed |
| `android.disallowKotlinSourceSets=false` required — KSP registers generated sources via `kotlin.sourceSets`, which AGP 9 rejects (3.5) | Third-party tooling gap | When KSP supports AGP 9 built-in Kotlin |
| ~~Conflict-resolution strategy (last-write-wins vs vector clocks)~~ | **Resolved** — neither: detection keyed on `SyncStatus`, no clock comparison (9.1) | Closed |
| SQLCipher vs Jetpack Security for field-level encryption | Not yet decided | With PII encryption |
| Cold-start numbers before/after Baseline Profile | Not yet measured | With the benchmark module |
| MVI marker interfaces live in `:feature:patients/mvi` (5.1) | Deliberate — one consumer | Move to `:core:ui` at the second feature module |
| ~~No Compose UI yet for either MVI screen~~ | **Resolved** — both screens built, `MainActivity` no longer renders the template (Part 6) | Closed |
| ~~Theme lives in `:app` while `:core:designsystem` is empty~~ | **Resolved** — moved, with spacing tokens and `StatusChip` (6.1, 6.2) | Closed |
| Validation bounds are stated twice: `BeneficiaryValidator` and `strings.xml` (6.8) | Accepted — the alternative is unlocalisable sentence fragments | When the ranges next change |
| ~~History list is a plain `LazyColumn`, not Paging~~ | **Resolved** — Paging 3 over Room (Part 7) | Closed |
| The SQL `ORDER BY` has no test; only the domain half of the contract is pinned (7.2, 7.6) | Needs an instrumented test against a real database | Days 18-20 |
| `CASE`-based ordering cannot use an index (7.2) | Correct at one health worker's scale | With the benchmark work, as an indexed rank column + backfill migration |
| Paging's error/retry branch is unimplemented (7.3) | Still true, and the Day 12 prediction was wrong: the pull is a use case, not a `RemoteMediator` (9.4), so nothing in the Paging path ever touches the network and `LoadState.Error` stays unreachable | Only if paging ever becomes network-backed — otherwise never |
| No test that the Worker maps the summaries to the right WorkManager Result (8.1, 9.8) | The algorithms are covered; the adapter — including "an interrupted push skips the pull" — is not | Days 18-20, with `work-testing` |
| A CONFLICTED record is a visible dead end — no merge UI (9.3) | Deliberate: detection without resolution loses nothing, and a wrong auto-merge is invisible | A field-level resolution screen, sized as its own day |
| `MockRemoteBeneficiarySource` holds accepted records in memory only (8.7, 9.9) | Fine for a mock; a restart forgets the 'server' while the device keeps its cursor, so the next pull returns nothing until new pushes land | Stays a mock — stated scope boundary |
| KMP variant split: domain compiles against `paging-common-desktop`, app ships `-android` (7.1) | Expected and routine | Watch for it if a NoSuchMethodError appears |
| No logging abstraction — `RoomDraftRepository`, both workers and the mock call `android.util.Log` directly (6.4) | The sync engine has landed and this did not, which was the trigger named on Day 11. Still a knowing shortcut, now with more call sites | Next time a diagnostic is needed that `adb logcat` on a developer's desk cannot give |
| Hand-rolled navigation (6.5) | Correct at two destinations | The first destination that takes an argument |
| Room schema JSON for v2 and v3 is not committed; only `1.json` is in `core/data/schemas/` | Blocks the migration test that the exported schemas exist for | Commit them on the next `./gradlew` run |
| No migration test for 1→2 or 2→3 | `MigrationTestHelper` needs instrumentation, which is out of CI (4.5) | Days 18-20, together with the `ORDER BY` test |
| A server that expires cursors has no full-resync path (9.5) | `PullOutcome` has no "cursor too old" case; the mock never expires one | When there is a real backend with log compaction |
| Deletes do not sync — no tombstones (9.1) | The app cannot delete a record, so the gap is not reachable today | With the first delete affordance |
| A pull's server-side change arrives up to an hour late on an idle handset | The periodic pass is the only trigger when nothing is being captured | A push notification, which needs a real backend (4.2) |
