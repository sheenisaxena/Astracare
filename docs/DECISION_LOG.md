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

**Known debt:** this module currently lives in `:app`. It belongs in `:core:common` via the
`hilt-core` artifact (Dagger annotations without any Android dependency) so any module can
inject dispatchers without depending on `:app`. Tracked under Open items.

## 2.6 Room 2.x, not room3

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

## 4.5 Instrumented tests are excluded from CI

Emulators in CI are slow and flaky, and a red badge caused by infrastructure is worse than no
badge. Unit tests, ktlint and detekt run on every push; instrumented tests run locally.

---

## Open items

| Item | Status | Resolve by |
|---|---|---|
| Move `DispatchersModule` to `:core:common` via `hilt-core` | Known debt (2.5) | Before cross-module DI lands |
| ~~detekt 1.23.8 vs Kotlin 2.2.10 compatibility~~ | **Resolved** — detekt parses Kotlin 2.2.10 without error; its embedded compiler handles the newer syntax | Closed |
| `android.disallowKotlinSourceSets=false` required — KSP registers generated sources via `kotlin.sourceSets`, which AGP 9 rejects (3.5) | Third-party tooling gap | When KSP supports AGP 9 built-in Kotlin |
| Conflict-resolution strategy (last-write-wins vs vector clocks) | Not yet decided | With the sync engine |
| SQLCipher vs Jetpack Security for field-level encryption | Not yet decided | With PII encryption |
| Cold-start numbers before/after Baseline Profile | Not yet measured | With the benchmark module |
