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

# Part 10 — Encryption at rest, and what it does not buy

Day 16. The records this app holds are children's names, ages, villages and body measurements,
sitting on a handset that gets carried around a district and occasionally lost. Encrypting them
is the easy part. Being precise about what that does and does not achieve is the part worth
writing down.

## 10.1 Whole-database encryption, not the two columns named in PII_FIELDS

`Beneficiary.PII_FIELDS` has existed since Day 7 with a comment saying it was there so the
encryption work would have one definition to point at. Day 16 did not use it, and the reason is
worth recording rather than leaving as an unexplained loose end.

Field-level encryption of `name` and `village` was the plan. It is cheap — no native
dependency, no measurable cost — and it protects exactly two columns. What it leaves in
plaintext on disk is a child's age, weight, height, mid-upper-arm circumference, the village
*index*, the capture timestamp and the entire `capture_draft` row, which holds the same PII
mid-keystroke. For a malnutrition screening record, "everything except the name" is not
meaningfully de-identified: a row saying a three-year-old in a known catchment area has a MUAC
of 108mm is a medical record about an identifiable child.

SQLCipher encrypts the file: pages, indexes, the write-ahead log, temp storage and the header.
Three costs, all accepted:

- **~4MB of native library per ABI**, bundled in the AAR for `armeabi-v7a`, `arm64-v8a`, `x86`
  and `x86_64`. Real on a budget handset, and the reason ABI splits exist — noted as an open
  item, not solved today.
- **A few percent on reads.** Irrelevant at one health worker's data volume, and the paging
  work on Day 12 means the app reads thirty rows at a time rather than the table.
- **A native dependency in the deployment.** The honest version of the trade.

What tipped it was a fourth consideration that is not about security at all: field-level
encryption is a one-way door for queries. Encrypted columns cannot be sorted, matched with
`LIKE`, or indexed usefully. The history screen does not search by name *today*, so the cost
looks like zero — right up to the first "can I find a child by name?", at which point the
answer is a schema migration and a re-encryption of every row. SQLCipher leaves every future
query available.

`PII_FIELDS` stays, unused by the crypto, because it is still the single place that answers
"what in this model is sensitive?" — which the logging review below needed and any future
export or redaction feature will need again.

## 10.2 Jetpack Security is deprecated, and the version catalog knew about it first

The catalog carried `androidx-security-crypto = "1.1.0"` from the planning phase. Every API in
that library — `EncryptedSharedPreferences`, `EncryptedFile`, `MasterKey` — was **deprecated in
June 2025** (1.1.0-beta01), in favour of platform APIs and direct use of the Android Keystore.
The stable 1.1.0 released a month later, deprecated on arrival.

It is still the first answer to every "how do I encrypt data on Android" search, and it was
still sitting in this project's catalog waiting to be used. So the entry was removed rather
than left unused, with a comment saying why — an unused dependency is an invitation, and the
next person to open the catalog looking for the encryption story should not find a deprecated
library pre-approved.

`DatabasePassphrase` therefore talks to `AndroidKeyStore` and `javax.crypto` directly. That is
about forty lines instead of five, and the forty lines are the ones that were always doing the
work; the library was a wrapper.

**Concept — a dependency chosen during planning is a decision made with the information of the
planning date.** Re-checking it on the day it is first used cost one search.

## 10.3 A passphrase the Keystore wraps but cannot hold

SQLCipher needs a passphrase it can read. The Keystore's entire value is that its keys cannot
be read — they live in the TEE and only ever act on data handed to them. The requirements are
directly opposed, so the passphrase cannot *be* a Keystore key. One level of indirection
resolves it:

1. 32 random bytes from `SecureRandom`, once. That is the passphrase.
2. Wrapped with an AES-GCM key that is generated in, and never leaves, the Keystore.
3. The wrapped result stored in ordinary `SharedPreferences`.

Plain preferences, deliberately: what is written there is already ciphertext, and wrapping it
again with a key from the same Keystore adds a step without adding a secret.

Two details in that code are load-bearing and neither is obvious.

**`commit()`, not `apply()`.** `apply()` writes asynchronously. A process death in the window
between the database being created with a passphrase and that passphrase becoming durable would
leave a database encrypted with bytes nothing has recorded — permanently unreadable, on the
first ever launch. Blocking the caller once at first launch is the correct price.

**Key missing, wrapped passphrase present, is fatal and says so.** If the Keystore key is gone
while the stored blob remains, no code anywhere can decrypt that database. The tempting
behaviour is to mint a fresh passphrase; the result is an "unable to open database file" error
that sends the next person to look at the database. It throws instead, naming the actual cause.

## 10.4 setUnlockedDeviceRequired, and the worker it would have broken

The wrapping key is created with `setUnlockedDeviceRequired(true)` on API 28+, so the database
cannot be opened while the handset is locked. That is the protection being bought, and it is
not free: **WorkManager can start a sync pass on a locked device, in a process that was not
already running.**

The first version of that failure was bad. Resolving `PushPendingRecordsUseCase` pulls the
repository, which pulls the DAO, which opens the database — all of it during
`SyncBeneficiariesWorker`'s *construction*. A throw there surfaces as WorkManager's
"Could not instantiate SyncBeneficiariesWorker", which reads like a Hilt wiring bug, says
nothing about the phone being locked, and cannot become a retry because `doWork` was never
reached.

The fix is to inject `dagger.Lazy<...>` and resolve inside `doWork`, where the failure is a
catchable `LocalStoreUnavailableException` and a correct `Result.retry()`. The general rule:

**A worker whose dependencies can fail for transient, expected reasons must not resolve them in
its constructor.**

Below API 28 the flag does not exist. minSdk here is 24, so that is a real population, and the
honest statement is that those devices get Keystore-bound encryption without the locked-device
guarantee — not that they are unprotected.

`setIsStrongBoxBacked` was considered and rejected: some devices advertise StrongBox and then
throw at generation or first use, so it needs a fallback path that would have to be exercised
on hardware this project cannot reach. Untested fallback code in the one place that can make
every record unreadable is the wrong trade.

## 10.5 What this actually protects against

The part most write-ups skip. Android already encrypts app storage — file-based encryption has
been mandatory since Android 10, and the app's default storage is credential-encrypted, so it
is unreadable before the first unlock after boot. **SQLCipher does not add that; the platform
already had it.** Claiming otherwise would be the most comfortable sentence in this document
and it would be false.

What SQLCipher does add, on top of FBE:

- **Anything that reads the file while the platform considers it available.** A rooted device,
  an unlocked bootloader, a forensic image taken after first unlock, a backup that should not
  have run, another app exploiting a path-traversal or a wrongly-exported provider.
- **A second, independent control.** FBE is one key hierarchy managed by the platform. This is
  a second one, under the app's own control, and a break in one does not hand over the data.
- **Protection while the device is locked but running**, via `setUnlockedDeviceRequired` —
  which is a window FBE does not cover, because credential-encrypted storage stays unlocked
  after first unlock until reboot.

What it does not protect against, stated plainly:

- **An attacker with the unlock credential.** They are the user as far as every layer here is
  concerned.
- **Malware with root while the app is running.** The database is open and the passphrase is in
  heap memory.
- **The passphrase's lifetime in memory.** `SupportOpenHelperFactory` in
  `net.zetetic:sqlcipher-android` keeps the array it is given and offers no way to clear it —
  the `clearPassphrase` flag belonged to the older `android-database-sqlcipher` artifact.
  Zeroing it from outside is impossible too, because the factory opens the database lazily and
  may reopen it. So the passphrase is in heap for the life of the process. This was checked in
  the library source rather than assumed from its documentation.
- **Anything in transit.** There is no backend (4.2). The network security config below is
  written for a server that does not exist yet.
- **A device with no lock screen.** `setUnlockedDeviceRequired` on a device with no credential
  set means "always unlocked". It degrades silently, which is worth knowing.

**Concept — a security control is only assessable against a stated threat model.** "The
database is encrypted" is marketing. The list above is a claim someone can check.

## 10.6 Backup is off, and would not have worked anyway

`android:allowBackup="false"`, and both rules files filled in rather than left as the project
template's commented-out samples.

The primary reason is that Auto Backup would copy a health worker's beneficiary records —
including ones that have never reached a server — to a consumer cloud account, through a
transport outside this app's threat model. Encrypting the file on disk and then shipping it off
the device undoes most of the point.

The second reason is that it would not work. The passphrase is wrapped by a Keystore key that
cannot be exported or restored to another device, so a restored database would be an unopenable
blob and a restored `SharedPreferences` would hold ciphertext with no key. The failure would
present as a corrupt database on a new phone.

Both rules files matter because they are different channels: `allowBackup` governs cloud
backup, and Android 12+ **device-to-device transfer** has its own opt-out that `allowBackup`
does not cover. Turning off one and not the other is the easy half-measure.

## 10.7 FLAG_SECURE, and why debuggable builds are exempt

Encrypting the database does nothing about the screenshot the system takes when the app is
backgrounded. The recents thumbnail is a picture of whatever was on screen — on the capture
form, a child's name, age and village — stored outside the app's own storage.
`FLAG_SECURE` covers screenshots, screen recording, casting and that thumbnail in one flag.

Set on the activity rather than per screen: both screens show beneficiary records, so scoping
it to the capture form would protect the shorter exposure and leave the longer one. It also
means a third screen is protected by default rather than by remembering, which is the safe
direction for a flag whose absence is invisible.

Debuggable builds are exempt, because FLAG_SECURE blocks the developer's own screenshots too —
including the ones the README needs and any future screenshot test. The exemption keys off
`ApplicationInfo.FLAG_DEBUGGABLE` rather than a `BuildConfig.DEBUG` constant, so it is tied to
the property that actually matters: a debuggable build has already surrendered far more than
its screenshots, and a non-debuggable one is protected whichever build type produced it.

## 10.8 A test that reads the file, and the control that makes it mean something

`EncryptedDatabaseTest` writes a record through the real stack, closes the database, and reads
the raw bytes off disk with no SQLite involved. It asserts the name is absent, the village is
absent, and the file does not begin with `SQLite format 3` — SQLCipher encrypts the header, so
an encrypted file does not even announce itself as a database.

Every cheaper test of this is a test of the configuration rather than the result: that
`openHelperFactory` was called, that the passphrase was non-empty, that the SQLCipher class is
on the classpath. All of them pass on a build where the factory is wired up and silently not
used — which is the one bug worth catching, because a quietly-plaintext database looks
identical from inside the app.

The second test is the half that makes the first one evidence. It writes the identical record
to an identical schema with no SQLCipher and asserts the name **is** found. Without it, the
encryption assertion would also pass if the mapper dropped the field, if the write never
happened, or if the search used the wrong encoding. A test that cannot fail for the right
reason is not proof of anything.

`close()` before reading is not incidental: without it the row can still be in the `-wal` file
and the assertion passes for the wrong reason.

This is instrumented — the Keystore has no JVM implementation, so it cannot run under
Robolectric either — which puts it outside CI (4.5). Recorded as an open item.

## 10.9 No plaintext-to-encrypted migration, and what it would be

An existing unencrypted `astracare.db` will not open after this change; SQLCipher reports it as
not a database. No build has ever been released, so the only affected databases are on
development machines and the answer is to uninstall the app.

Writing the migration anyway was rejected as speculative work on a path with no users, but the
shape is recorded so the decision is reversible rather than forgotten. SQLCipher's own
`sqlcipher_export` does it in one pass: open the plaintext file, `ATTACH` a new encrypted
database with the passphrase, `SELECT sqlcipher_export('encrypted')`, `DETACH`, then swap the
files and delete the original. The parts that need care are the ones a snippet omits — doing it
before Room opens the database, surviving a process death mid-swap without destroying either
copy, and carrying `user_version` across so Room does not then try to run every migration from
scratch.

## 10.10 A logging pass, which found nothing, which is the point

Encryption at rest is undone by a `Log.d` that prints what was encrypted. Every logging call in
the project was read against `Beneficiary.PII_FIELDS`:

- The sync worker logs `PushSummary` and `PullSummary` — counts only, no records.
- `MockRemoteBeneficiarySource` logs beneficiary **IDs**, which are device-minted UUIDs and
  carry no PII.
- `RoomDraftRepository` logs caught exceptions. SQLite exception messages carry statement text,
  not bound values, so a failing insert does not log a name — but this is the one place where a
  future change could leak without anyone noticing, and the absence of a logging abstraction
  (6.4) means there is nowhere central to enforce it.

Nothing needed changing. Recording that it was checked, and where the weak point is, is worth
more than the change would have been.

---

# Part 11 — Roles, and a trail that says only what it can prove

Day 17. The plan's Flagship Checklist calls this the project's strongest edge — "health data +
DPDP context". The risk in a day like this is building something that *looks* like access
control and an audit log, and letting the resemblance do the arguing. Most of what follows is
about the gap between what these mechanisms are and what they are usually taken to be.

## 11.1 The permission matrix is a declaration, not a scattering of `if`s

`RolePermissions.allows(role, capability)` is the only place that knows what a role may do. The
UI asks it; the use cases ask it; nothing contains a copy.

The alternative — `if (role == SUPERVISOR)` at each site — is shorter at every individual call
and puts a second copy of the model in the UI layer. The two drift the first time a role is
added, and they drift *quietly*, because a stale check still compiles and still does something.

Both levels of the `when` are exhaustive with no `else`, so adding a `UserRole` or a
`Capability` fails the build at the one file that must have an opinion. That is worth more here
than anywhere else it has been used in this project: a permission that falls through to a
default is a permission that defaults to *something*, and whichever default was picked is wrong
half the time.

`RolePermissionsTest` writes out all six cells, including the boring ones, plus a guard that
fails when the matrix stops covering every combination. Six assertions against a rule whose
failure mode is a supervisor quietly capturing records in a field worker's name.

**Concept — a rule the compiler enforces the shape of, and a test enforces the content of.**

## 11.2 Hiding a button and refusing an action are different guarantees

`SaveBeneficiaryUseCase` checks `CAPTURE_RECORD` even though the capture screen is never
offered to a role that lacks it. That looks redundant and is not, and the distinction is worth
being precise about because it is the one an interviewer will push on.

It is **not** defence in depth in the security sense. The role lives on the device, the device
belongs to the user, and the user can change it — 4.3 said so on Day 4, before any of this
existed. A check on the client cannot stop the client.

What it is, is **correctness**. The button is absent, but a save can still arrive: from a
restored back stack, from a process-death resurrection holding a stale screen, from a role
switched in the moment between the form opening and the save completing. Without the check the
app completes an action its own interface says is unavailable — a bug whatever the security
story is, and one that would present as a mysterious record nobody remembers making.

The line this draws is: **refuse actions below the UI; gate reads at the navigation.** The
audit screen's ViewModel therefore has no permission check at all. A read nobody can reach is
not something a client can secure anyway, and a ViewModel that refused would make the composable
handle an error state it can never be in. A determined user reads the database, not the screen.

`SaveError.NotPermitted` is a case rather than an exception for the same reason: "unreachable"
is a claim about today's navigation.

## 11.3 Append-only, in three layers, only one of which is real

`audit_log` is append-only, and the guarantee is built three times:

1. **`AuditRepository`** has no update and no delete. Code that goes through it cannot express
   a change.
2. **`AuditDao`** has no update and no delete. Same, one layer down.
3. **Two SQLite triggers** make `UPDATE` and `DELETE` on the table `RAISE(ABORT, ...)`.

The first two are documentation that happens to compile. Neither survives someone adding a
method, and neither applies to code that does not go through them — a raw query, a future DAO,
a session with a database inspector. Only the trigger makes it a property of the *file*.

### The trap that would have made it a property of half the devices

Room builds a fresh schema from the compiled `@Entity` classes and **runs no migration at all**
on a new install. `@Entity` cannot declare a trigger. So a migration that creates the triggers
gives them to every device that upgrades and to no device that installs clean.

Worse, nothing would have reported it: Room validates tables, columns, indices and views
against the compiled schema, and does not look at triggers. The app would have been append-only
for upgraders and silently mutable for everyone else — the larger population, and the one
nobody tests.

Both paths therefore call the same `createAuditLogTriggers`: `MIGRATION_3_4` for upgrades, a
`RoomDatabase.Callback.onCreate` in `DatabaseModule` for fresh databases.

**Concept — when a guarantee lives outside the schema, find every path that creates the
schema.**

## 11.4 Ordered by rowid, displayed by timestamp

`AuditDao.observeRecent` sorts by `id DESC`, not `at DESC`, and the entity's primary key is an
autoincrementing rowid rather than a UUID.

The timestamp comes from the device clock, which 2.6 has recorded as untrustworthy since Day 2
and which 9.1 already refused to let decide conflicts. It is fine for *showing* a reader when
something happened and wrong for establishing what happened first. Two entries written in the
same millisecond, or either side of someone changing the system clock, still come back in the
order they were written — because the rowid is the only monotonic thing in the app.

The screen shows relative time ("5 minutes ago") for the same reason. An absolute timestamp
looks authoritative; this one is not.

## 11.5 The honest limits of an audit trail with no authentication

This is the part that matters more than the code.

An `AuditEntry` records a **role**, not a person, because this app has no authentication. So an
entry says "this device, in supervisor mode, marked record X as conflicted" — useful for
reconstructing what the app did, and **not** an accountability record. The role is switchable
by whoever is holding the phone.

The switch is itself logged (`ROLE_CHANGED`), attributed to the role being **left** rather than
the one being taken — otherwise someone could become a supervisor and have the trail say a
supervisor authorised it. That is the only mitigation available without a server, and it is
partial: it makes tampering visible in the trail, it does not prevent it, and anyone who can
change the role can also read the database the trail lives in.

There is a fourth limit that follows from Day 16 rather than this day: the trail is inside the
encrypted database, so it is as readable as the records are, to exactly the same people.

The honest summary, which is what the README now says: **this is a local activity log, not an
accountability record.** A real audit requirement is met by the server recording what it
receives, from an authenticated principal, in storage the client cannot reach. Presenting this
as more than it is would be worse than not having it, because someone would rely on it.

**Concept — a control described accurately is worth more than a control described well.**

## 11.6 A role switcher that admits what it is

The UI carries a banner reading "Acting as Field worker" with a caption: *No sign-in yet — this
switch stands in for it*. Always visible, for both roles.

Three alternatives were weighed. A **hardcoded role** would have left the gating logic with one
path anyone ever exercises — the day's actual subject, untested. A **role set once on first
launch** models a real deployment more closely and makes the other role reachable only by
clearing app data, which is unusable in a demo and quietly implies an authority the client does
not have. The **visible switch** is the only one where the mechanism and its honesty are the
same object: anyone can flip it, and that is precisely the point 4.3 makes, rendered where a
health worker actually reads it rather than in a document they never will.

The banner shows for both roles deliberately. Showing it only to supervisors would make the
field-worker view look like the only view there is.

Two smaller decisions in the same area. `RoleSelected` carries the target role rather than being
a payload-free `ToggleRole`, because with a third role a toggle becomes a question with no
answer — and it would have to change shape at the moment the permission model is already
changing. And the UI's `permissions` `StateFlow` is seeded with the **least** privileged role,
not the real one: `stateIn` needs a value before the database answers, and a permissive seed
flashes a capture button at a supervisor for a frame, which on a slow first query is long
enough to tap.

## 11.7 What gets written down, and what deliberately does not

Four actions: `RECORD_CREATED`, `RECORD_UPDATED`, `CONFLICT_DETECTED`, `ROLE_CHANGED`.

Successful syncs are excluded. `SyncStatus` on the record already carries that, and duplicating
it here would bury the four events above under a line per record per sync pass. An audit log
that records everything is a log nobody reads, and a log nobody reads is not a control.

Three exclusions are sharper than they look, and each has a test:

- **A refused save writes nothing.** Validation failed, or the role could not capture — either
  way no data changed, and the trail describes what happened to data.
- **A storage failure writes nothing.** The record did not land.
- **A conflict mark that the conditional write refused writes nothing.** The record moved
  underneath the pull, so no conflict was recorded — and an entry anyway would describe an event
  that never happened, which is worse than a missing one because it reads as true.

That last one is the whole reason the audit call sits inside `.also { if (marked) }` rather than
beside the write.

Created-versus-updated comes from reading the row before writing it, which is free: the one-shot
`findById` was added for the pull on Day 14.

## 11.8 The audit write is not transactional with the save, and that is a choice

The record lands first; the entry follows. A crash between them loses the entry and keeps the
record.

The reverse ordering would lose a health worker's data in order to keep a log of it, which is
the wrong way round for this app. Making both atomic is possible — Room can wrap them in one
transaction — and it would mean `SaveBeneficiaryUseCase` knowing the database exists, which is
the layering the entire project is built to avoid.

The deeper answer is that a client-side transaction would not buy what it appears to. A trail
that can be dropped by a crash is already not an accountability record (11.5); making it
atomic with the save would make it a *reliable* activity log and no more authoritative. Listed
as an open item rather than solved with a transaction reaching through three layers.

## 11.9 A third single-row table, and why not a column on an existing one

`session` holds one row: which role the device is operating as. That is now three
single-row tables — `capture_draft`, `sync_state`, `session` — and the obvious economy is to
put the role on `sync_state`.

`SyncStateEntity`'s own documentation rejects exactly that shortcut for exactly this reason, so
taking it here would be inconsistent as well as wrong. The lifetimes differ: clearing every
record and resetting the pull cursor is a coherent "start again" operation that must not change
who the device thinks it is, and switching role must not disturb the cursor. Sharing a row
couples them so that any future operation touching one has to reason about the other.

`SharedPreferences` was the other candidate, and Day 16 did use it for the wrapped passphrase.
That had a specific reason — it must be readable *before* the database can open — and nothing
about a role needs that, so 6.3's argument applies unchanged.

## 11.10 The trail has no foreign key to `beneficiaries`, deliberately

`audit_log.record_id` names a beneficiary and is not declared as a foreign key.

A trail that cascades away when the record it describes is deleted is not a trail — "record X
was deleted" is precisely the entry that must survive the deletion. `ON DELETE SET NULL` keeps
the row and destroys the only thing that made it meaningful.

The cost is that `record_id` can name a record that no longer exists, which is correct: the
history of a thing outlives the thing. The app cannot delete records today, so this is a
constraint chosen before it is needed rather than discovered after.

---

# Part 12 — Testing what was never tested, and checking the tests can fail

Day 18. The plan's entry reads: *"Unit tests: ViewModel with fake repository + TestDispatcher.
Use-case tests with boundary values (empty, max, malformed measurement)."*

## 12.1 Half the day was already done, which is worth saying rather than re-doing

Both ViewModels have been tested against hand-written fakes on a `TestDispatcher` since Day 10,
and the use cases have been tested as they were written — push, pull, conflict resolution,
audit, permissions. Eighty-three tests existed before this day started.

Arriving at a planned testing day and finding the work already absorbed into earlier days is a
good outcome and a slightly awkward one: the honest options are to write a second set of
ViewModel tests so the day has an artefact, or to find what is actually untested and test that.

What was actually untested:

- **`BeneficiaryValidator` had no test at all.** The densest concentration of clinical
  judgement in the codebase, on the path every record takes. `ValidationError`'s own
  documentation asserted that "tests assert on `WeightOutOfRange` rather than on prose" — a
  claim about tests that did not exist.
- **`:core:data` had no `test` source set.** Every test in that module was instrumented, so its
  pure functions — the mappers, the defensive enum decoding — were covered by nothing CI runs
  (4.5).
- **`Outcome` had no test.** Twelve lines that every failure in the app travels through.

So the day is the plan's second half, aimed at the gaps.

**Concept — a plan entry is a hypothesis about where the work will be.** Re-checking it on the
day is cheaper than executing it faithfully.

## 12.2 A range check has four interesting values

`in a..b` is inclusive at both ends, so for each bound there are exactly four values worth
asserting — just below the minimum, the minimum, the maximum, just above — and one that is
not, which is anything in the middle.

The bug this catches is `<` written where `<=` was meant. It passes every test using a
plausible 12.4 kg and fails only at 0.5. Every bound in the validator now has its four values,
and the property being pinned is not that the numbers are right — they are a clinical decision
the validator's own comments defend — but that the code implements the range it claims to.

Two assertions are not about any single field:

- **Every failure is reported, not just the first.** The documented behaviour is that a health
  worker sees everything wrong at once. A `return` added during a refactor removes that, and
  nothing else in the suite would notice.
- **Validation ignores what it does not own.** Sync status and timestamps are set by the sync
  engine, and a record arriving from a pull with `CONFLICTED` status and extreme timestamps
  must validate cleanly. Pinned so a well-meant "validate everything" change has to argue with
  a test rather than quietly reject records the app produced itself.

## 12.3 NaN is handled correctly by accident, which is why it needs a test

`Double.NaN !in 0.5..300.0` is `true`, because every comparison involving NaN is false. So the
validator rejects a NaN weight and does so without anyone having thought about it.

That makes it fragile in a specific way: rewriting the check as
`weight < MIN || weight > MAX` — which most people would call a clarification — silently starts
**accepting** NaN. And a NaN weight in the database can never again be compared, sorted or
summed; it poisons every aggregate it touches and the row looks fine.

The test asserts the current behaviour so the rewrite has to fail before it ships. The mutation
check below confirms it does.

## 12.4 Three defensive decoders, each choosing which way to be wrong

`:core:data` decodes an enum out of a TEXT column in three places, and all three fall back
rather than throw. The reason is the same each time: a row written by a *newer* build — after a
downgrade, or a partially applied migration — must not crash the app, because the app is the
only thing that can send a health worker's unsynced records anywhere.

What is interesting is that each fallback is a decision about the direction of the error, and
until Day 18 all three were comments:

| Unreadable value | Falls back to | Because |
|---|---|---|
| Sync status | `PENDING` | The record is re-offered to the server. `SYNCED` would mark data as safe when nothing had sent it. |
| Session role | `FIELD_WORKER` | The least privileged role, so an unreadable session grants less access, never more. |
| Audit actor | `FIELD_WORKER` | The trail must not attribute an action to more authority than it can evidence. |
| Audit action | `RECORD_UPDATED` | Something happened to a record and this build cannot say what. `CONFLICT_DETECTED` would raise an alarm nothing raised; dropping the row would hide an event that occurred. |

None of these is reachable without writing the raw value by hand, which is exactly why they
went unverified: they guard a case only production produces.

Each also has a companion test that walks the whole enum and asserts a clean round trip. Without
it, a genuinely new status would land in the fallback, decode as something plausible, and every
test would still pass.

## 12.5 The mutation check, and what it found about itself

A suite that passes proves the code does *something*. It does not prove the tests would notice
if the code did something else. So eight mutations were applied to the code this day covers,
one at a time, and the suite was run against each.

Seven were caught:

- `isBlank()` → `isEmpty()` (the whitespace-only name)
- the age range's maximum made exclusive
- the weight range's minimum made exclusive
- the range check rewritten as `< MIN || > MAX` (the NaN case in 12.3)
- the MUAC null check inverted, so absence became a validation failure
- the unknown-sync-status fallback changed from `PENDING` to `SYNCED`
- the unknown-role fallback changed from `FIELD_WORKER` to `SUPERVISOR`

One survived, and it should have. `Outcome.map` returns `this` on the failure branch; mutating
it to `Outcome.Failure(error)` allocates a new instance that compares equal to the old one,
because `Failure` is a data class. No caller can tell the difference, so no test can. That is an
**equivalent mutant** — a textbook category — and the honest response is to leave it
unkilled and say why, not to write an assertion on object identity.

### The harness lied to me first

The first run reported *two* survivors. The second was the inverted MUAC check, and it had not
survived at all — it had failed to compile, because removing the null guard breaks the smart
cast that `MuacOutOfRange(muac)` depends on. The harness looked for "N tests failed", found no
such line, and concluded the mutation was uncaught.

A verification tool that cannot distinguish *"no test caught this"* from *"this did not build"*
reports false coverage gaps, and a false coverage gap sends someone hunting for a test that
cannot exist. The harness now reports three outcomes, and the mutation was rewritten into a form
that compiles — at which point it was caught immediately.

**Concept — the thing checking your work needs checking too**, and its failure mode is the more
expensive one, because it is trusted by default.

## 12.6 What is still untested, stated rather than implied

This day closed the JVM-side gaps. It did not close these, and the open items table now says so:

- **The SQL.** The `CASE` ordering, the conditional updates, the `INSERT OR IGNORE`, the
  append-only triggers, the four migrations. All of it needs a real database, which means
  instrumentation, which CI does not run (4.5). The strongest claims in the project have the
  weakest automated coverage, and that is the honest shape of it.
- **The Worker's mapping of summaries to `WorkManager.Result`**, including "an interrupted push
  skips the pull" (9.8). Needs `work-testing`.
- **`MockRemoteBeneficiarySource`.** Its idempotent re-accept, deterministic failure cadence and
  delta pull are all asserted only through the use cases that consume it. Day 19's entry names
  the mock remote layer directly.
- **Mutation testing is a script in a scratch directory, not part of the build.** It was run
  by hand for this day's code and is not wired into CI; Kotlin support in the available
  mutation-testing plugins is limited enough that adopting one is its own decision.

---

# Part 13 — A logging seam, the mock server's own tests, and where MockK earns its place

Day 19. The plan: *"MockK for the mock remote layer. Turbine for Flow assertions. Explicit
tests for the sync conflict-resolution path."*

Two of those three were already true. Turbine has been driving the effect-channel assertions in
both ViewModel tests since Day 10. The conflict path has twenty-three tests across
`ConflictResolverTest` and `PullRemoteChangesUseCaseTest`, written on Day 14 alongside the code.

What was left was the first clause, and it turned out to be two separate problems wearing one
sentence.

## 13.1 A logging call decided whether a class could be tested

`MockRemoteBeneficiarySource` is the stand-in server the entire sync engine is designed
against, and until today it had no direct test — it was verified only through the use cases
that consume it. That is backwards: a bug in it does not fail one test, it quietly changes what
a dozen others are actually asserting.

The reason it had none was two `Log.d` calls. `android.util.Log` is a stub on the JVM
unit-test classpath and every method on it throws, so **any class that calls it cannot have a
plain unit test.**

That is a terrible reason for a class to be untestable, and it had been sitting in the open
items as 6.4 since Day 11 with the trigger written as "the next diagnostic `adb logcat` cannot
give". The actual trigger was nothing to do with diagnostics.

The two escape hatches were both worse than fixing it:

- **Robolectric** — a large dependency and a much slower test, adopted to satisfy two log
  lines, and it leaves the next class with the same problem exactly where it started.
- **`testOptions.unitTests.isReturnDefaultValues = true`** — one line, and it silently stubs
  every Android method in the module to return a default. The *next* test to touch a real
  framework API gets a quiet zero instead of an error. Cheap now, expensive at the worst moment.

So: a `Logger` interface in `:core:common`, three methods, tags as parameters. The
implementation lives in `:app`, because `:core:common` is a Kotlin/JVM module and the compiler
will not let it reference `android.util.Log` at all. One file in the project now knows logcat
exists; everything else takes a `Logger` and is testable without a device.

Deliberately not a logging framework — no structured fields, no lazy message lambdas, no
appender configuration. It is a seam. Designing more would be designing for a crash-reporting
integration whose shape is not knowable yet.

There is no fallback binding in any core module, so forgetting to provide one is a Hilt compile
error rather than an app that silently logs nothing. And the interface is now the single place
the "no PII in logs" rule from 10.10 *could* be enforced mechanically — which is a real
argument for it over scattered `Log` calls, and is not enforced yet.

**Concept — when a cross-cutting utility makes code untestable, the utility is the bug.**

## 13.2 What is worth testing in a mock

Not "does it store things" — that is a `LinkedHashMap`. What matters are the properties the
sync engine is *built against*, because if any of them is wrong then the engine's own tests are
passing for the wrong reason:

- **Idempotent accept.** Day 13's retry story rests entirely on a second delivery being a
  no-op.
- **Deterministic failure.** Every fifth call, at positions 5 and 10 of ten, every run. A
  random mock produces a demo that sometimes misbehaves and a bug nobody can reproduce.
- **A correct delta.** Too few records and updates are lost; everything, and the cursor is
  decoration.
- **A clock the device does not control.** The conflict design exists for the case where the
  two disagree (9.1, 9.5). A mock that stamped server-side edits with `timeProvider.now()`
  would model a server whose clock agrees with the handset's, and Day 14's tests would pass
  without demonstrating anything.

Two smaller ones are worth their lines. A pull with an unreadable cursor falls back to a full
pull rather than throwing — the client stores the cursor verbatim and never interprets it, so
a corrupted one is possible, and "expensive" is the safe direction where "stranded" is not.
And the simulated round trip is asserted against virtual time, so it costs nothing to check and
still fails if someone deletes the `delay` as pointless — it is what makes the PENDING chip
visible long enough to watch it flip on a real device.

## 13.3 Fakes by default, MockK where the interaction *is* the behaviour

MockK has been on every test classpath since Day 2 and had never been used once. The plan kept
promising to justify it. The honest options were to use it somewhere it genuinely wins, or to
delete it and defend the absence — being undecided is the one answer that is not defensible.

The policy, now written down:

> **A fake asserts on state; a mock asserts on an interaction. Prefer the fake, because "the
> record reached storage marked PENDING" survives a refactor and "`upsert` was called once"
> does not. Reach for the mock only when the interaction is the entire observable behaviour.**

`SyncSchedulingTest` is the one place that applies. `SyncScheduler.requestSync()` returns
nothing, enqueues work in another system, and leaves no state to inspect. The only observable
fact is whether it was called — and the property that matters is a *negative*:

- A local save must request a sync, or a captured record waits for the hourly pass.
- `applyRemote` must **not**, or every pull enqueues a push of everything it just received —
  the app in a conversation with itself, on a metered connection, on a handset whose battery
  has to last a working day.

Negative interactions are exactly what a fake is bad at. A recording fake can only prove a list
is empty, which is the same assertion written longhand plus a class to maintain.
`verify(exactly = 0)` says it once.

That behaviour was defended by a comment in `OfflineFirstBeneficiaryRepository` and nothing
else, and it was the most plausible thing in the file for a future refactor to break — "both of
these write a record, why do they differ?" is a reasonable question to ask and a wrong one to
act on.

The DAO in that same file is still a fake. It has real state, the tests read it back, and its
conditional writes have to behave like the SQL they stand in for.

## 13.4 Mutation testing found the gap that reading did not

Six mutations against the day's claims. Five were caught immediately. One survived, and it is
the most instructive result in two days of testing work:

```
accepted.put(id, record)   ->   accepted.putIfAbsent(id, record)
```

The idempotence test pushed the same record twice and asserted the server held **one** record.
`putIfAbsent` also yields one record. The test passed against a server that silently keeps the
*first* version of a record forever — so every subsequent edit a health worker made would be
accepted, acknowledged, and discarded.

**Idempotent upsert and ignore-if-present are different guarantees**, and the test had been
asserting the weaker one without anyone noticing, including the person who wrote it. The fix is
one more test: push, edit, push again, and assert the stored village is the *new* one.

This is the second consecutive day where mutation testing found something reading the code did
not, and the pattern in both cases is the same — a test that asserts a *consequence* of the
property rather than the property itself. A record count is a consequence of idempotence. It is
also a consequence of several things that are not idempotence.

**Concept — "the test passes" and "the test would fail if this broke" are different claims**,
and only the second one is worth anything.

---
---

# Part 14 — The end-to-end test, the database tests, and a PII rule that is now mechanical

Day 20. The plan: *"E2E Compose UI test for capture → list. Audit that no PII reaches the
logs."*

Two items, and the second one took an hour. The first one turned into the day, because writing
it meant deciding where this project's UI tests are allowed to run — and answering that
question forced three tests that had been deferred since Day 12 to be written as well.

## 14.1 Robolectric, and why this is not a reversal of Day 19

`app/src/androidTest` has held the Hilt scaffolding for an end-to-end test since Day 8. The
test was never written. That is not an accident of scheduling: DECISION_LOG 4.5 keeps
instrumented tests out of CI, so anything written there runs when somebody remembers, and Day
18's write-up made exactly that complaint about every instrumented test in the project.

Writing the flagship UI test into a source set CI does not execute would have produced a
demonstration rather than a test.

Robolectric runs Android framework code on the JVM, which puts it in `./gradlew test`.

Day 19 **rejected** Robolectric, and the two decisions have to be read together or the second
one looks like drift. There, the problem was that `MockRemoteBeneficiarySource` called
`android.util.Log` and so could not be unit-tested; Robolectric would have solved it by bringing
in a large dependency to avoid writing a thirty-line interface. The seam was the better answer
and it is still the better answer — it also made 14.4 below possible, which Robolectric would
not have.

Here there is no thirty-line alternative. There is no way to drive a real Compose hierarchy on
the JVM without it. Same tool, different question, opposite answer.

**Concept — a rejected dependency is rejected for a reason, not forever.** "We decided against
Robolectric" is not a fact about Robolectric; it is a fact about one problem.

## 14.2 One journey, not a suite of screen tests

`CaptureToHistoryTest` has three tests. It could have had twenty.

The reason it does not: a UI test is the slowest, most fragile test in any Android project, and
its value is concentrated in the things no cheaper test can reach. By Day 20 this project has
152 JVM tests covering validation, reducers, mappers, conflict resolution, permissions, the
mock server and the effect channel. Each of them stops at a module boundary. Nothing so far
asserts that the boundaries *line up*: that Hilt can actually assemble the graph, that the nav
shell routes on an effect, that a ViewModel's state reaches a composable, that the list re-reads
what capture wrote.

So the three tests are chosen to cross as many seams as possible rather than to cover screens:

1. **Capture → save → it appears in the list.** The whole loop, including the effect that pops
   the back stack. This is the one that fails if the wiring is wrong anywhere.
2. **Invalid input is refused and what was typed survives.** Validation reaching the screen,
   and the property from 5.7 that a health worker standing in a village does not lose five
   fields because the age was wrong.
3. **A supervisor is not offered capture.** The permission matrix as behaviour rather than as
   `RolePermissionsTest`'s table.

Screen-level assertions — that a chip is the right colour, that a label reads correctly — are
cheaper and more reliable as Compose previews and as the unit tests that already exist.

## 14.3 The whole data layer is faked now, and that is a decision, not a convenience

Until today `TestDataModule` replaced `BeneficiaryRepository` and let the real Room-backed
implementations serve everything else. That worked on a device. It cannot work on the JVM: since
Day 16 the database is opened by SQLCipher, which is a **native** library, and Robolectric
cannot load a `.so`. The first screen to ask for a `DraftRepository` would take the run down
with an `UnsatisfiedLinkError` naming neither the database nor the test.

So the module now fakes six bindings, and a second module replaces `SyncModule` and
`WorkManagerModule` — both, because replacing only the scheduler leaves a `WorkManager` provider
that still has to resolve (the same trap as 9.11).

The division this forces is worth stating as a rule rather than treating as a workaround:

> **UI tests run against fakes. The real database is tested directly.**

That is only honest if the second half is true, and until this morning it was not. Which is how
a day about a UI test became a day about SQL.

The fakes are also deliberately *behavioural* rather than empty. `FakeSessionRepository` really
switches roles, so test 3 exercises the gate. `FakeRemoteBeneficiarySource` fails every call
transiently, which leaves records PENDING — the state a health worker with no signal actually
sees, and the one the list most needs to render. A fake that accepted records would have the
list flip to SYNCED mid-assertion for reasons unrelated to anything under test.

All six are `@Singleton`, because the point of an end-to-end test is that what one screen writes
another screen reads.

## 14.4 The no-PII rule, made mechanical — including the cause chain

Day 16 audited every logging call site by hand and found nothing leaking (10.10). That audit was
true about a commit and expired the moment anyone added a line.

Day 19's `Logger` seam is what makes it checkable. `PiiLoggingTest` substitutes a recorder,
drives the real repository and mock-server code paths with a beneficiary named
`Zzyzx Qwertyuiop` from `Xylophonia`, and asserts neither string ever appears. It includes a
control test that deliberately logs the name, so a recorder that silently captured nothing would
fail rather than pass everything.

The part worth writing down is the cause chain. `Logger.warn` takes a `Throwable`, and a
throwable's message is written by whoever threw it. SQLite errors normally name the failing
*statement* and not the bound values — which is why the hand audit passed — but that is a
property of the current driver, not a guarantee, and nothing in this app controls it. Room could
change it in a point release.

So the recorder flattens tag, message and the entire cause chain into one string, and the fake
DAO throws exceptions whose messages imitate real driver output. If a driver or a wrapper ever
starts embedding values in an exception message, this test fails.

**Residual risk, named rather than assumed away:** this proves the *paths the test drives* do
not leak. It is not a static guarantee about every future call site. A `Logger` that rejected
PII structurally — typed log arguments, or a redacting implementation — would be, and the open
items table carries that. `SyncBeneficiariesWorker` also logs and is not covered here, because
it cannot be constructed without WorkManager; its messages are `PushSummary` and `PullSummary`,
which carry counts and no records.

## 14.5 The SQL, finally

`BeneficiaryDaoTest` is the test 12.6 said was missing and 7.2 said was missing before that.
Four claims this project makes in prose, none of which had any automated check:

- the `CASE` ordering that moved out of Kotlin on Day 12,
- `updateSyncStatusIfUnchanged` and `replaceIfUnchanged`, the two conditional writes behind the
  stale-write guard (9.7),
- `INSERT OR IGNORE` behind the pull (9.1), which must not overwrite a local capture,
- the append-only triggers (11.3).

A fake DAO cannot verify any of them, by construction. `FakeBeneficiaryRepository`'s own
documentation says so: a fake that sorted correctly would prove the fake sorts correctly and
would keep passing while the real `ORDER BY` was wrong.

One test is worth calling out. `pagedByAttention_matchesTheDomainDeclaration` inserts one record
per `SyncStatus` and asserts the query returns them in exactly `RecordAttentionOrder.byUrgency`.
`RecordAttentionOrderTest` pins the domain's list; this pins the SQL; **neither alone proves
they agree**, and nothing in the type system connects a Kotlin enum to a `CASE` arm. The join
between the two halves was the actual gap.

The triggers are tested on the **fresh-install** path — an in-memory database built by Room from
the compiled entities, with the same `RoomDatabase.Callback` the production builder uses. That
is the trap 11.3 identified: Room runs no migration on a new install, `@Entity` cannot declare a
trigger, and a migration-only version of the guarantee holds on upgraded devices and silently
fails on every fresh one. Room does not validate triggers, so nothing else would notice.

## 14.6 The migration chain, and a drift guard for the next one

`MigrationTest` is the other long-deferred one, and it matters more than anything else written
today. This app's premise is holding records that have not reached a server, and `DatabaseModule`
deliberately has no `fallbackToDestructiveMigration` (9.12). A wrong migration does not degrade
the app — it fails to open the database on the one upgrade where a health worker's unsent
records are inside it.

`runMigrationsAndValidate` checks more than "the SQL ran": it compares the resulting schema
against the compiled one from the exported JSON and fails on a missing index, a wrongly nullable
column, a column-order mismatch. Those are precisely the differences that produce Room's
"Migration didn't properly handle" crash at runtime and are invisible to a migration that merely
executes. This is what `core/data/schemas/` has been committed for since Day 9 — for a test that
did not exist for eleven days.

Each migration is tested alone, and then 1→4 is tested as a chain carrying a PENDING record
through every version. That is a different claim: an intermediate step that dropped and
recreated `beneficiaries` would pass all three individual tests.

The last test is not about migrations at all:

```kotlin
assertEquals(DATABASE_VERSION - 1, ALL_MIGRATIONS.size)
```

Bumping the version without writing a migration compiles, ships, and crashes on first upgrade.
This turns that into a failing test — and it is the test most likely to catch a mistake made six
months from now by someone who has never read this file.

These run **unencrypted** while the app runs encrypted, and that is deliberate isolation rather
than an oversight: a migration is SQL, SQLCipher swaps the implementation beneath SQL without
changing its semantics, and wiring the Keystore in would mean a failed key unwrap and a broken
migration produce the same red test. The cost is stated in the file: this does not prove an
*encrypted* v3 upgrades to v4. `EncryptedDatabaseTest` covers that path against a real device.

## 14.7 String literals in the UI test, and a grep that makes them safe

`CaptureToHistoryTest` asserts on `"Add record"` and `"No records yet"` rather than calling
`getString(R.string.list_action_add)`.

That is usually considered the wrong way round, so the reason: a test that resolves the same
resource the screen resolves passes when both are wrong. Rename a string's *value* and the test
follows it silently. The literals are what a person actually reads on the screen, and asserting
on them is the only version of this test that can fail for the right reason.

The obvious objection is that it breaks the moment a translation lands or a word is reworded,
with a "node not found" message that says nothing useful. So the verification harness greps
every literal the test declares against the project's `strings.xml` files and fails loudly if
one is absent. Cheap, and it converts the failure mode from a confusing test error into a
one-line diff.

## 14.8 What this day does not prove

Three tests were written today that cannot run in CI and have not run yet at all:
`BeneficiaryDaoTest`, `MigrationTest` and the trigger assertions in both. They are instrumented,
and 4.5 still keeps instrumented tests out of CI. Writing them was still worth it — they can be
run on demand now, and before today they could not — but the honest statement is that the
project's strongest claims moved from *untested* to *testable*, not to *continuously verified*.
An instrumented CI lane is the remaining half and it is its own day.

`CaptureToHistoryTest` does run in CI, and what it covers is narrower than "the UI works":

- **Not rendering.** Robolectric runs the composition and the semantics tree, not a GPU. Overlapping
  layout, clipped text, a touch target too small for a gloved thumb — none of it is visible.
- **Not Room, and not SQLCipher.** The data layer is faked, for the reason in 14.3.
- **Not WorkManager.** The scheduler is a no-op; whether a save requests a sync is asserted in
  `SyncSchedulingTest`.

What it does prove is the wiring, which is the one thing no unit test in this project can reach.

---
## 14.9 The end-to-end test found an unlabelled button on its first run

`CaptureToHistoryTest` failed the first time it ran, and not because the test was wrong.

The Compose semantics tree showed this:

```
Node #18  Role='Button'  Actions=[OnClick, RequestFocus]     ← merged: no name at all
 └ Node #22  ClearAndSetSemantics = 'true'
    └ Node #24  Text = '[Add record]'                        ← unmerged only
```

`ExtendedFloatingActionButton` wraps its `text` slot in `clearAndSetSemantics {}`. That is
correct of Material: the API's accessibility contract puts the button's name on the `icon`
slot, because normally the icon is the thing needing a description and the visible label would
only duplicate it. This FAB was written with `icon = {}`. The label was cleared and nothing
replaced it, so the primary action of the entire app announced itself to TalkBack as "button".

The fix is one modifier. What is worth recording is everything that failed to catch it:

- 152 unit tests, none of which render a composable.
- `RolePermissionsTest`, which asserts a field worker *may* capture a record — a claim about the
  matrix, not about whether the affordance is usable.
- A design system that gets this exact concern right elsewhere: `StatusChip` uses
  `clearAndSetSemantics` deliberately and supplies a description, because a coloured pill
  reading "Pending" needs the longer sentence. The team that wrote that line is the same one
  that left the FAB unnamed.
- Twelve days of reading this code, including mine.

**Concept — accessibility defects are invisible to every test that does not look at the
semantics tree**, and reviewing the code cannot substitute, because the bug was in a library's
behaviour rather than in the lines anyone wrote.

The test now finds the FAB by content description rather than by text. That is not a workaround
for the fix; it is the stronger assertion, because it checks what a screen-reader user is told
rather than what a sighted user sees.

## 14.10 The fake had reported "still loading" since Day 8

With the FAB fixed, all three tests still failed — now timing out waiting for the empty state.
The semantics tree showed why: a `ProgressBarRangeInfo` node, and no empty state anywhere. The
list was on its spinner and was never going to leave it.

```kotlin
PagingData.from(it.values.toList())   // FakeBeneficiaryRepository, Day 8 to Day 20
```

That overload leaves every `LoadState` as `Loading`, permanently. `BeneficiaryListScreen`
distinguishes spinner from empty state with `loadState.refresh is LoadState.Loading` —
deliberately, because `itemCount == 0` is also true before the first page arrives (7.4). So the
fake pinned the screen to the spinner branch and no record could ever appear.

Three things about this are worth more than the fix:

**The overload is deprecated, and the deprecation message says exactly this.** The build had
been printing the warning for twelve days. Nobody read it. Gradle's warning output is a place
things go to not be read, and the lesson is not "read the warnings" — it is that a warning is
not a control.

**No test could have caught it.** `FakeBeneficiaryRepository` had been the repository behind
every `:app` test since Day 8, and its documentation carefully explained why a fake must not
sort — the reasoning about not hiding failure modes was right there, in the same file, next to
a call that hid one completely. Nothing rendered the screen, so nothing consumed the load state.

**It would have shipped as a demo failure, not a bug report.** The real repository uses `Pager`,
which supplies proper load states, so the app itself was fine. The defect lived entirely in the
double — which means the first person to hit it would have been someone running the test suite
on a clean checkout and concluding the project was broken.

**Concept — a test double is production code for the tests**, and the parts of it nothing
exercises are exactly as untrustworthy as any other unexercised code.

---

# Part 15 — Measuring cold start before there is anything to take credit for

Day 21. The plan: *"Add `:macrobenchmark` module. Measure cold start WITHOUT a baseline
profile. Record the number — do this before, or the delta is unprovable."*

The instruction in that sentence is the whole day. Adding a baseline profile and then
announcing an improvement is the most common unfalsifiable claim on an Android CV, and it is
unfalsifiable for a boring reason: nobody measured first. So Day 21 builds the instrument and
takes the "before" reading; Day 22 gets to make a claim.

## 15.1 A third module kind, and why the instrument lives outside the patient

`:macrobenchmark` is a `com.android.test` module — not a library, not an application. It
produces an APK containing only instrumentation, installed beside `:app` and driving it from a
separate process.

That separation is the point. An in-process measurement is part of what it measures: the
profiler's own class loading, its allocations and its JIT pressure land inside the sample. A
macrobenchmark launches the app the way the launcher does, kills it between iterations, and
reads the timing from the system rather than from inside the app.

The cost is that it cannot run anywhere except on a physical device, which puts it outside CI
permanently — a different exclusion from the instrumented tests of Day 20, and a more honest
one. Those *should* be continuous and are not. A benchmark should not be: its output is a
number, not a pass or a fail, and a number measured on shared CI hardware is a number about the
CI hardware.

## 15.2 A benchmark build type, because neither existing one can be measured

A macrobenchmark needs a build type present in both modules, and both existing ones are
disqualified:

- **`debug`** is debuggable, so it runs interpreted with JIT optimisations disabled. Its startup
  time is a fact about the debugger.
- **`release`** is unsigned, so it does not install.

Hence `benchmark`: release's compilation settings, debug's signing key, `isDebuggable = false`,
and `matchingFallbacks` so the library modules resolve their release variants for it.

It shares `:app`'s application id rather than taking a suffix. A suffix is the tidier-looking
choice and the wrong one — the benchmark drives `com.astracare` by package name, and a second
install means measuring whichever the system resolves.

## 15.3 `profileable` is confined to that build type, deliberately

Macrobenchmark needs the target process to be `profileable android:shell="true"`: readable by
the shell without being debuggable. The obvious place for the tag is the main manifest, which
merges into every variant — and that is precisely why it is in `app/src/benchmark/` instead.

`profileable` in a release build exposes method-level traces of a process whose screens show a
child's name, age and village. Day 16 spent a day making that data hard to read from outside
the app; a tag added for convenience would have quietly undone part of it. The flag is not a
catastrophe on its own, and that is exactly the kind of "not on its own" that accumulates.

## 15.4 The first frame of this app is a spinner, so TTID is the wrong number

`StartupTimingMetric` reports time-to-initial-display and time-to-full-display. TTID — the
first frame — is what "cold start" usually means, and for this app it means almost nothing.

The database is encrypted. Starting up means unwrapping a Keystore-wrapped passphrase, opening
SQLCipher, and letting Room answer a query, none of which has happened when the first frame
lands. That frame is a `CircularProgressIndicator`. Optimising TTID here would be optimising how
fast a health worker can be shown a loading indicator.

So `BeneficiaryListScreen` now calls `ReportDrawnWhen { !isRefreshing }`, and TTFD becomes the
metric. This puts an Activity API (`androidx.activity.compose`) into a feature module, which is
worth a second look before accepting. The alternative was hoisting a "ready" signal up to `:app`
so `MainActivity` could call `reportFullyDrawn()` — which would put a performance concern into
the navigation shell and couple it to one screen's load state. The screen is the only thing that
knows when it is ready; the dependency goes where the knowledge is.

TTID is still recorded. The **gap** between the two is the isolated cost of opening an encrypted
database, which is the figure to produce if the SQLCipher decision (10.1) is ever challenged on
performance grounds. Before today that cost was an assumption.

## 15.5 Two bounds instead of one number

`StartupBenchmark` runs twice: `CompilationMode.None()` and `CompilationMode.Full()`.

Neither is a user experience. `None` wipes all AOT code so everything starts interpreted; `Full`
compiles everything ahead of time, which is more than a baseline profile will ever produce,
because a profile compiles only the startup path on purpose to keep the APK small.

They bracket the answer, and the bracket is what makes Day 22 meaningful. The question then is
not "how much faster than `None`" — it is **what share of the `None` → `Full` gap the profile
recovered**. A profile closing 70% of a 300 ms gap is a good profile. A profile closing 70% of a
20 ms gap is a rounding error about to be written on a CV as a percentage. Measuring against
`None` alone cannot distinguish them, which is exactly how the unfalsifiable claim gets made in
good faith.

## 15.6 What these numbers will not be

Written down before the first run, so it cannot be quietly dropped afterwards:

- **Not release numbers.** The `benchmark` type inherits `release`'s disabled R8, so the APK is
  unminified. Every figure is an upper bound, and enabling R8 invalidates all of them rather
  than shifting them predictably.
- **Not the whole distribution.** `StartupMode.COLD` kills the process between iterations but
  does not clear app data, so iteration 1 creates the database and generates the Keystore key
  and the other nine do not. Ten iterations dilute it into the median; three would not, which
  is why the iteration count is recorded next to every figure in `docs/PERFORMANCE.md`.
- **Not portable.** One device is one data point, and this app's honest target is a mid-range
  phone several years old — a flagship's numbers say very little about it.
- **Not from an emulator.** An emulator shares the host's scheduler, thermals and page cache.
  Its startup times move when the laptop compiles something in another window.

`docs/PERFORMANCE.md` is the table these get recorded in, with the device, build type,
iteration count and commit beside them. A number without those four is not a measurement, and
the point of writing the file before the run is that the blanks are visible.

---
---

# Part 16 — A baseline profile, and refusing to let it report a number it did not earn

Day 22. The plan: *"Generate Baseline Profile via `BaselineProfileRule`. Re-measure cold start.
Record the before/after delta in a table."*

Day 21 built the instrument and wrote down what it would and would not prove. This day builds
the thing being measured. Almost every decision below is about the same risk — that a baseline
profile produces a number whether or not it did anything, and that the number is flattering.

## 16.1 What a baseline profile is, since the name oversells it

A text file of method signatures. `BaselineProfileRule` drives the app while ART records which
methods ran; the list is packaged into the APK, and at install time the runtime compiles those
methods ahead of time instead of interpreting them on first launch.

There is no measurement in it and nothing adaptive. It is a hint about which code matters, and
its quality is entirely a function of what the generating journey touched.

Saying so plainly is not pedantry — it is what makes the next decision obvious.

## 16.2 The journey goes past startup, because a startup-only profile makes the first tap slow

The obvious generator launches the app and stops. It produces a profile that makes launching
fast and leaves the first interaction exactly as slow as it was — which a user experiences as
"it opens instantly and then hangs". That is a *worse* impression than a uniformly slow app,
because the fast launch sets an expectation the next screen breaks.

So `BaselineProfileGenerator` does the whole first minute of a health worker's day: cold launch,
wait for the list to actually settle, open the capture form, come back. That pulls in the MVI
loop, the validator, the draft repository and a second screen of Compose — none of which a
launch-only profile would have named.

Two details inside it are worth recording:

**It waits for content, not for a duration.** Until Paging's refresh lands, the only thing
composed is a spinner, and a profile collected at that moment faithfully records the code path
for showing a loading indicator. This is the same race Day 20's UI test lost on its first run
(14.10), in a different harness, three days apart — which is a good argument that the race is a
property of this app's startup rather than of either test.

**It finds the button by content description.** Because `ExtendedFloatingActionButton` clears
its label's semantics, and the description only exists because the UI test found it missing
(14.9). An accessibility fix made two days ago is what makes the profile generator able to find
the primary action at all. That is not a coincidence worth marvelling at — UiAutomator and a
screen reader consume the same tree, so anything unreachable by one is unreachable by the other.

## 16.3 `BaselineProfileMode.Require`, because `UseIfAvailable` cannot fail

The measuring test uses `CompilationMode.Partial(BaselineProfileMode.Require)`.

`UseIfAvailable` is the friendlier-sounding option and it is the wrong one. When no profile is
installed it runs anyway, reports a number indistinguishable in shape from a real one, and the
before/after table then compares `None` against `None`. The result is a 0% improvement written
up as a measurement — or, worse, run-to-run noise written up as a win, in complete good faith,
by someone who had no way to know.

`Require` fails the test. That is the only behaviour of the two that cannot produce a false
result, and "fails loudly" beats "reports something" every time the something would be indistinguishable from the truth.

## 16.4 `profileinstaller` is not optional, and leaving it out fails silently

`androidx.profileinstaller` is now a dependency of `:app`.

It is easy to skip, because on a Play-installed app the store performs install-time compilation
from the packaged profile without it. Every device this project will ever touch is sideloaded —
a benchmark run, a reviewer's phone, a field pilot APK — and on those the profile sits in the
APK doing nothing unless this library writes it to ART.

Omitting it is the standard way to ship a baseline profile that has no effect and then measure
it as though it did. Combined with `UseIfAvailable` above, the two mistakes compose into a
convincing, entirely fictional improvement.

## 16.5 The profile is a committed text file, not a generated build artifact

The modern path is the `androidx.baselineprofile` Gradle plugin: it runs the generator, collects
the output, and wires it into the app automatically, creating `nonMinifiedRelease` and
`benchmarkRelease` variants to do it.

This project instead commits `app/src/main/baseline-prof.txt`, the mechanism AGP has packaged
since 7.x, and regenerates it by hand.

Two reasons, one honest about its limits:

**It is reviewable.** The whole thesis of this repository is that decisions are inspectable —
that is what `DECISION_LOG.md` is for. A profile that lives in git as a readable list of the
methods this app compiles ahead of time fits that; one that materialises during a build the
reader cannot run does not.

**The plugin would have churned Day 21's work on Day 22.** It auto-creates its own build types
derived from `release`, which overlaps the hand-written `benchmark` type built yesterday for
exactly this purpose, and I could not verify the interaction — AGP 9.3.1 is new enough that its
managed-variant behaviour is not something to adopt blind on the day the measurement is taken.

The cost is real and goes in the open items: the file can go stale. A profile generated against
a UI that has since changed is worse than no profile, because it compiles methods that are no
longer on the startup path while missing the ones that are — and nothing in the build will say
so. Adopting the plugin is the follow-up, on a day where a broken build is affordable.

## 16.6 What "faster" is allowed to mean here

The table in `docs/PERFORMANCE.md` records **share of available headroom**:

```
share = (TTFD_None − TTFD_Partial) / (TTFD_None − TTFD_Full)
```

This is why Day 21 measured two bounds instead of one. "30% faster than no compilation at all"
is technically true of almost any profile and says nothing about whether the profile is good;
the same 30% is excellent against a large gap and meaningless against a small one.

And the case that has to be written down *before* the run, or it will not survive contact with
a disappointing result: **if the measured delta falls inside the run-to-run spread of the `None`
row, the entry in the table is "no measurable improvement on this device".** An eight-module app
with a Hilt graph and Compose has real startup work to compile, so an improvement is expected —
but an expectation is not evidence, and a measurement that can only come out one way was never
a measurement.

**Concept — the integrity of a benchmark is decided before it runs**, by what the harness is
allowed to report. Every choice on this day (`Require` over `UseIfAvailable`, two bounds over
one, the pre-committed null result) is the same move: removing the ways a number could look
like a win without being one.

---
---

# Part 17 — The README, and the diagram that had been wrong for eleven days

Day 23. The plan: *"README build-out: one-line framing, architecture diagram, DECISION LOG, perf
table, explicit scope boundaries, screen GIF."*

Most of that list already existed. A day spent on documentation that is already written is
either wasted or it is an audit, and this one turned into an audit almost immediately.

## 17.1 The architecture diagram was wrong, and it had been wrong since Day 12

The ASCII diagram in the README showed `:feature:patients` and `:core:sync` depending on
`:core:data`. Neither does. Checked against the build files:

```
:feature:patients  →  :core:model, :core:domain, :core:designsystem
:core:sync         →  :core:model, :core:domain
:core:data         →  :core:model, :core:common, :core:domain
```

The feature layer has never touched `:core:data`. It talks to repository *interfaces* in
`:core:domain`, and the implementations sit beside it rather than beneath it — which is the
single most important claim this project makes about its own structure, and the diagram
contradicted it.

Worth being precise about how this happened, because the mechanism is more interesting than the
error. The diagram was drawn on Day 3 from the *intended* architecture, before most of those
modules had dependencies at all. It was accurate as a plan. It was never re-checked against a
build file, because it renders fine and reads plausibly, and nothing in a build can fail because
a picture is out of date.

**Concept — documentation that cannot be verified drifts silently**, and an architecture diagram
is the worst offender in any repository because it is the artefact reviewers trust most and the
one nothing validates. Prose at least gets re-read when someone edits the paragraph around it.

The replacement is `docs/architecture.svg`, generated by hand from the actual `project(":...")`
declarations. It is still not verified by anything, which is now an open item rather than an
assumption: a Gradle task that fails when the diagram and the dependency graph disagree is the
real fix, and it is a day of work rather than an afternoon.

## 17.2 What the diagram shows that the ASCII one could not

Three things, all of which had been prose-only:

- **The compiler-enforced line.** A dashed rule across the graph with `:core:domain`,
  `:core:model` and `:core:common` below it. The Android SDK is not on their classpath, so the
  layering is a compile error rather than a review comment — the project's signature decision
  (1.2), and until now a paragraph.
- **Two columns, not a stack.** UI on the left, infrastructure on the right, both pointing down
  into `:core:domain`. That shape *is* the argument: nothing in the UI column can reach an
  implementation, because there is no arrow that way.
- **`:app`'s dashed edges.** `:app` depends on `:core:data` and `:core:sync` without naming a
  single type from either. Both are there purely so their Hilt `@Binds` reach the runtime
  classpath when the singleton component is assembled. A solid arrow would have overstated it; a
  missing arrow would have made the build file look wrong.

## 17.3 Environment troubleshooting moved out of the reading path

Thirty lines on `JAVA_HOME` — genuinely useful, and the reason the git hooks fail while the IDE
build succeeds — sat between "Stack" and "Current state".

A README has one reader worth optimising for: someone deciding in ninety seconds whether to keep
reading. Putting a `SetEnvironmentVariable` incantation in front of them answers a question they
have not asked yet, and it answers it at the exact point where they were about to find out what
the project does.

It now lives in `docs/BUILDING.md`, with the known tooling gaps collected alongside it —
including the two failures a fresh clone will currently hit (the missing schema JSONs, and
`coldStartBaselineProfile` failing by design until a profile is committed). Both were previously
recorded only in a KDoc and a decision-log table, which is to say: not where someone hitting
them would look.

## 17.4 A ten-minute reading path, because nobody reads eight modules

New section, and the one addition that is not on the plan's list.

The honest problem with a portfolio repository is that its value is distributed across thousands
of lines and a reviewer has minutes. Left to themselves they will open `MainActivity`, find
fourteen lines and a comment, and form an impression from the least representative file in the
project.

So the README now names five things and says why each is worth the click: the decision log, the
conflict resolver, the passphrase wrapper, the end-to-end test, and `build-logic`. That is a
curated path rather than a table of contents — it is an argument about which parts of this work
are actually load-bearing, which is itself information about the person who chose them.

## 17.5 The screen recording is not there, and says so

The one item on the plan's list that could not be done: it needs a device.

The section exists, states plainly that recordings are not in the repository yet, and carries
the capture commands in a source comment along with commented-out markup ready to uncomment. A
broken image tag would have been worse than nothing — the one thing a portfolio README cannot
afford is a visible defect on the page whose job is to demonstrate care.

---

## Open items

| Item | Status | Resolve by |
|---|---|---|
| ~~Move `DispatchersModule` to `:core:common`~~ | **Resolved** — moved, via the `astracare.jvm.hilt` convention plugin and `hilt-core` (2.5) | Closed |
| Client wall-clock time is not monotonic (2.6) | Narrowed — conflict detection no longer depends on it (9.1) and the delta window uses an opaque cursor (9.5). Remaining exposure is display ordering by `recorded_at` | Kronos, or a server-stamped receipt time, if timestamps ever become user-visible evidence |
| ~~detekt 1.23.8 vs Kotlin 2.2.10 compatibility~~ | **Resolved** — detekt parses Kotlin 2.2.10 without error; its embedded compiler handles the newer syntax | Closed |
| `android.disallowKotlinSourceSets=false` required — KSP registers generated sources via `kotlin.sourceSets`, which AGP 9 rejects (3.5) | Third-party tooling gap | When KSP supports AGP 9 built-in Kotlin |
| ~~Conflict-resolution strategy (last-write-wins vs vector clocks)~~ | **Resolved** — neither: detection keyed on `SyncStatus`, no clock comparison (9.1) | Closed |
| ~~SQLCipher vs Jetpack Security for field-level encryption~~ | **Resolved** — SQLCipher over the whole database (10.1); Jetpack Security turned out to be deprecated (10.2) | Closed |
| Cold-start numbers before/after Baseline Profile | Both halves built — instrument (Part 15) and profile (Part 16). Neither reading taken: it needs a phone, and `Partial(Require)` will fail until a generated profile is committed | One device session: generate, commit the profile, run all three modes |
| MVI marker interfaces live in `:feature:patients/mvi` (5.1) | Deliberate — one consumer | Move to `:core:ui` at the second feature module |
| ~~No Compose UI yet for either MVI screen~~ | **Resolved** — both screens built, `MainActivity` no longer renders the template (Part 6) | Closed |
| ~~No end-to-end test: nothing asserts the module boundaries line up~~ | **Resolved** — `CaptureToHistoryTest` on Robolectric, so it runs in `./gradlew test` (14.1, 14.2). Covers the Hilt graph, nav on an effect, and the capture → list round trip | Closed |
| ~~Theme lives in `:app` while `:core:designsystem` is empty~~ | **Resolved** — moved, with spacing tokens and `StatusChip` (6.1, 6.2) | Closed |
| Validation bounds are stated twice: `BeneficiaryValidator` and `strings.xml` (6.8) | Accepted — the alternative is unlocalisable sentence fragments. Day 18 pinned the domain half with boundary tests, so a bound that moves now breaks a test; the string still has to be updated by hand | When the ranges next change |
| ~~History list is a plain `LazyColumn`, not Paging~~ | **Resolved** — Paging 3 over Room (Part 7) | Closed |
| ~~No SQL is tested: the `CASE` ordering, the conditional updates, `INSERT OR IGNORE`, the append-only triggers and all four migrations~~ | **Resolved as written, not as running** — `BeneficiaryDaoTest` and `MigrationTest` cover all of it (14.5, 14.6), including the fresh-install trigger path and a 1→4 chain carrying a PENDING record. Both are instrumented, so 4.5 still keeps them out of CI | An instrumented CI lane — the remaining half |
| `CASE`-based ordering cannot use an index (7.2) | Correct at one health worker's scale, and now measurable — the query sits inside TTFD (15.4) | An indexed rank column + backfill migration, if TTFD ever says it matters |
| Paging's error/retry branch is unimplemented (7.3) | Still true, and the Day 12 prediction was wrong: the pull is a use case, not a `RemoteMediator` (9.4), so nothing in the Paging path ever touches the network and `LoadState.Error` stays unreachable | Only if paging ever becomes network-backed — otherwise never |
| No test that the Worker maps the summaries to the right WorkManager Result (8.1, 9.8) | The algorithms are covered; the adapter — including "an interrupted push skips the pull" — is not. Named for Days 18-20 and not done in any of them | With `work-testing`, as its own piece of work |
| A CONFLICTED record is a visible dead end — no merge UI (9.3) | Deliberate: detection without resolution loses nothing, and a wrong auto-merge is invisible | A field-level resolution screen, sized as its own day |
| `MockRemoteBeneficiarySource` holds accepted records in memory only (8.7, 9.9) | Fine for a mock; a restart forgets the 'server' while the device keeps its cursor, so the next pull returns nothing until new pushes land | Stays a mock — stated scope boundary |
| KMP variant split: domain compiles against `paging-common-desktop`, app ships `-android` (7.1) | Expected and routine | Watch for it if a NoSuchMethodError appears |
| ~~No logging abstraction — classes call `android.util.Log` directly (6.4)~~ | **Resolved** — `Logger` in `:core:common`, Android implementation in `:app` (13.1). Forced by testability, not diagnostics: a class calling `android.util.Log` cannot have a JVM unit test | Closed |
| Hand-rolled navigation (6.5) | Correct at two destinations | The first destination that takes an argument |
| Room schema JSON for v2 and v3 is not committed; only `1.json` is in `core/data/schemas/` | **Now blocking.** `MigrationTest` exists and every test in it fails on a missing schema file until they do. A build exports only the *current* version, so they have to be recovered from git history — the recipe is in the test's KDoc. Hand-writing them is not an option: the JSON carries an identity hash Room computes from the entities | Before `MigrationTest` can run at all |
| ~~No migration test for 1→2 or 2→3~~ | **Resolved** — every migration individually, the full chain, and a `DATABASE_VERSION - 1 == ALL_MIGRATIONS.size` drift guard (14.6) | Closed |
| A server that expires cursors has no full-resync path (9.5) | `PullOutcome` has no "cursor too old" case; the mock never expires one | When there is a real backend with log compaction |
| Deletes do not sync — no tombstones (9.1) | The app cannot delete a record, so the gap is not reachable today | With the first delete affordance |
| A pull's server-side change arrives up to an hour late on an idle handset | The periodic pass is the only trigger when nothing is being captured | A push notification, which needs a real backend (4.2) |
| An existing plaintext `astracare.db` will not open after Day 16 (10.9) | No released build, so only development devices are affected — uninstall and reinstall. The `sqlcipher_export` recipe is written down but not implemented | If a build is ever released before the next schema change |
| The SQLCipher passphrase stays in heap for the life of the process (10.5) | `SupportOpenHelperFactory` keeps the array and offers no way to clear it; verified in the library source, not assumed | Nothing to do without a change upstream — it bounds the threat model rather than being a bug |
| API 24-27 get no `setUnlockedDeviceRequired` (10.4) | The flag is API 28+. Those devices still get Keystore-bound encryption, just not the locked-device guarantee | Whenever minSdk rises to 28 |
| `EncryptedDatabaseTest` is instrumented, so it is outside CI (4.5, 10.8) | Unchanged, and it now has company: the migration and DAO tests written on Day 20 are instrumented too (14.8). The Keystore has no JVM implementation, so there is no Robolectric path for this one | An instrumented CI lane |
| SQLCipher adds ~4MB of native library per ABI, unmeasured (10.1) | Size still unmeasured; its *startup* cost is now isolated as TTFD − TTID (15.4), which was an assumption until Day 21 | APK size with an ABI split or app bundle, as its own piece |
| R8 is disabled — `optimization { enable = false }` in `app/build.gradle.kts` | Now also a measurement problem: the `benchmark` build type inherits it, so every figure in `docs/PERFORMANCE.md` is an upper bound and enabling R8 invalidates rather than shifts them (15.6) | Its own day; enabling R8 blind on a Hilt + Room + Paging graph is not a ten-minute change |
| The audit trail records a role, not a person (11.5) | There is no authentication, and the role is switchable by whoever holds the phone. `ROLE_CHANGED` makes tampering visible, not impossible | Server-side audit from an authenticated principal — needs a real backend (4.2) |
| The audit write is not atomic with the save it describes (11.8) | A crash between them loses the entry and keeps the record, which is the right way round. A client-side transaction would make it reliable, not authoritative | With a server that records what it receives |
| ~~No migration test for 3→4, and the append-only triggers are untested (11.3)~~ | **Resolved** — both paths: the migration one in `MigrationTest`, the fresh-install one in `BeneficiaryDaoTest` against a Room-built schema with the production callback (14.5) | Closed |
| Client RBAC hides affordances and refuses actions; it secures nothing (4.3, 11.2) | Stated in the README and next to the code. The role is on the device and the device belongs to the user | Server-side authorisation on every request |
| `audit_log` grows without bound and is never pruned | One line per save on a table nobody deletes from — and deletion is impossible by design (11.3), so pruning needs a deliberate mechanism rather than a `DELETE` | When a real deployment's volume is known; likely a server-side archive plus a local retention window |
| DPDP: children's data attracts enhanced protections this app does not implement | Beneficiaries are children under five. Verifiable parental consent, and the consent notice and retention machinery around it, are absent | Out of scope for a portfolio build; named in the README rather than implied to be handled |
| `CaptureToHistoryTest` asserts on UI string literals, not resources (14.7) | Deliberate: a test resolving the same resource as the screen passes when both are wrong. The harness greps every literal against `strings.xml`, so the failure is a one-line diff rather than "node not found" | Revisit when a `values-hi/` translation lands and the default locale stops being the only one |
| Robolectric renders no pixels (14.8) | Composition and semantics only. Layout overlap, clipped text and undersized touch targets are invisible to `CaptureToHistoryTest` | Screenshot testing (Paparazzi or Roborazzi), as its own decision |
| `app/src/sharedTest` is wired into both test source sets by hand | Not an AGP convention — two `kotlin.srcDir` lines in `app/build.gradle.kts`. A new module needing shared doubles repeats them | Move into a convention plugin at the second module that needs it |
| Robolectric downloads an `android-all` runtime on first run | An offline or firewalled CI agent fails with a download error rather than a test failure. The SDK level is pinned below `compileSdk` for the same reason | Prefetch the runtime in the CI cache if it ever bites |
| `app/src/main/baseline-prof.txt` is regenerated by hand (16.5) | Deliberate over the `androidx.baselineprofile` plugin: the profile stays a reviewable file in git, and the plugin's auto-created variants overlap the `benchmark` build type written the day before. The cost is that the file can go stale silently — a profile against a changed UI compiles the wrong methods and nothing in the build says so | Adopt the plugin on a day where a broken build is affordable |
| Profile generation needs API 33+, or root below it | ART only exposes its recorded profile without root from 33 onwards. `docs/PERFORMANCE.md` carries the Gradle Managed Device fallback, which is legitimate here because a profile is a list of method names rather than a timing | Nothing to do; the fallback is written down |
| No baseline profile committed yet, so `coldStartBaselineProfile` fails | Correct behaviour, not a defect: `BaselineProfileMode.Require` refuses to report a number when there is no profile, which is the entire point of choosing it over `UseIfAvailable` (16.3) | Generate and commit the profile |
| The architecture diagram is not verified by anything (17.1) | It was wrong for eleven days and nothing could have caught it — a picture cannot fail a build. Redrawn by hand from the `project(":...")` declarations; still hand-maintained | A Gradle task that fails when the diagram and the dependency graph disagree — a day, not an afternoon |
| No screen recordings in the repository (17.5) | Needs a device. The section and the capture commands are in place; the journey itself is covered by `CaptureToHistoryTest` | One device session with `adb screenrecord` |
| Benchmarks are outside CI and always will be (15.1) | Deliberate, unlike the Day 20 instrumented tests. A benchmark's output is a number, and a number from shared CI hardware is a number about that hardware | Never — re-run by hand after any change to startup, the Hilt graph or the database open path |
| The `benchmark` build type shares `:app`'s application id (15.2) | Required: the benchmark drives `com.astracare` by name, and a suffix would mean two installs and an ambiguous target. The cost is that it replaces a development install | Nothing to do; noted so the replaced install is not a surprise |
| `ReportDrawnWhen` puts an Activity API in a feature module (15.4) | Accepted — the screen is the only thing that knows when it is ready, and the alternative couples the navigation shell to one screen's load state | Revisit if a second screen ever needs to report readiness |
| No frame-timing benchmark for the history list | Cold start was the day's scope. Scroll jank on a long list — with the `CASE` ordering and Paging underneath — is a separate measurement and a separate metric | Its own day, with `FrameTimingMetric` |
| Mutation testing is a scratch script, not part of the build (12.5, 13.4) | Run by hand two days running, and it found a real gap both times — most recently a test asserting a *consequence* of idempotence rather than idempotence itself. Nothing in CI stops the next test from being one that cannot fail | Kotlin support in the available plugins is thin enough to be its own decision |
| ~~`MockRemoteBeneficiarySource` is asserted only through its consumers (12.6)~~ | **Resolved** — 16 direct tests (13.2), unblocked by the logging seam | Closed |
| The `Logger` seam could enforce the no-PII-in-logs rule *structurally* and does not (10.10, 13.1, 14.4) | Narrowed — `PiiLoggingTest` now drives the real paths with unmistakable PII and inspects the whole cause chain, so this is mechanical rather than a hand audit. It still proves the paths it drives, not every future call site; a typed or redacting `Logger` would | When there is a redaction requirement, or a crash reporter to route through |
| `SyncBeneficiariesWorker` is the only `Logger` consumer with no test at all (9.8, 14.4, 14.8) | Still true after Day 20 — the UI test replaces its scheduler with a no-op, and `PiiLoggingTest` cannot construct it without WorkManager. Its summary-to-`Result` mapping needs `work-testing` | Whenever `work-testing` lands |
