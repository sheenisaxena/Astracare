# AstraCare

[![CI](https://github.com/sheenisaxena/Astracare/actions/workflows/ci.yml/badge.svg)](https://github.com/sheenisaxena/Astracare/actions/workflows/ci.yml)

An offline-first Android field data capture app for community health workers operating in
low-bandwidth and intermittently connected environments.

> **Status: in active development.** The build system, module architecture and dependency
> graph are in place. Feature work is underway — see [Current state](#current-state) for an
> honest breakdown of what does and does not exist yet.

---

## The problem

A community health worker records patient measurements in a village with no usable mobile
data. The app has to accept that data, keep it safe on the device, and reconcile it with the
server whenever connectivity returns — possibly hours later, possibly after the same record
was edited elsewhere.

That makes three things non-negotiable:

- **The local database is the source of truth**, not a cache of a remote response. The UI reads
  from disk and never waits on a network call.
- **Sync is a background queue with conflict resolution**, not a request/response cycle. Writes
  are idempotent and retried with exponential backoff.
- **Data at rest is sensitive.** Records contain personally identifying health information, so
  field-level encryption and an audit trail are requirements rather than enhancements.

## Architecture

Multi-module, layered, with unidirectional data flow.

```
                         :app
                          │
        ┌─────────────────┼──────────────────┐
        ▼                 ▼                  ▼
:feature:patients    :core:sync      :core:designsystem
        │                 │
        └────────┬────────┘
                 ▼
            :core:data          ← Room + remote source + repository impls
                 │
                 ▼
           :core:domain         ← use cases + repository interfaces
                 │
        ┌────────┴────────┐
        ▼                 ▼
   :core:model       :core:common
```

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android application | Entry point, Hilt root component, navigation host |
| `:feature:patients` | Android library | Capture screen, record history, MVI ViewModel |
| `:core:sync` | Android library | WorkManager sync engine, backoff, conflict resolution |
| `:core:data` | Android library | Room database, remote source, repository implementations |
| `:core:domain` | **Kotlin/JVM** | Use cases and repository *interfaces* |
| `:core:model` | **Kotlin/JVM** | Pure domain models |
| `:core:common` | **Kotlin/JVM** | Dispatchers, result types, shared extensions |
| `:core:designsystem` | Android library | Compose Material 3 theme and shared components |

`:core:domain`, `:core:model` and `:core:common` are **Kotlin/JVM modules, not Android
modules**. The Android SDK is not on their compile classpath, so an `import android.*` in the
domain layer does not compile. Layering is enforced by the build rather than by code review.

The same principle drives the Gradle setup: convention plugins are split by capability, so
Compose and Hilt are simply absent from modules that should not use them.

## Stack

Kotlin · Jetpack Compose · Material 3 · Hilt · Room · WorkManager · Paging 3 · Coroutines &
Flow · KSP · Turbine · MockK · Gradle convention plugins

**Build requirements:** JDK 21 · Gradle 9.5 · AGP 9.3.1 · `compileSdk` 37 · `minSdk` 24

```bash
./gradlew assembleDebug
./gradlew test
./gradlew detekt                 # static analysis + ktlint rules
./gradlew detekt --auto-correct  # fix what can be fixed automatically
```

Git hooks (detekt on commit, tests on push) install themselves on the first Gradle sync — no
setup command needed. Git never clones `.git/config`, so `core.hooksPath` cannot survive a
clone on its own; `settings.gradle.kts` sets it during configuration instead.

To verify, or to set it by hand:

```bash
git config core.hooksPath   # should print .githooks
```

## Current state

**In place**

- Seven-module structure with compiler-enforced layer boundaries
- `build-logic` included build with five capability-scoped convention plugins; SDK and Java
  levels defined once
- Version catalog covering the full dependency set
- Hilt graph bootstrapped, with injected coroutine dispatchers for testability

**Next**

- Domain model and use cases; Room database as single source of truth
- Compose capture screen with MVI state management; paged record history
- WorkManager sync with conflict resolution
- Field-level PII encryption, role gating, append-only audit log
- Unit and UI test suites; macrobenchmark with baseline profiles

## Design decisions

**[docs/DECISION_LOG.md](docs/DECISION_LOG.md)** records the reasoning behind each structural
choice and the alternative rejected — including why the domain modules are Kotlin/JVM, why
convention plugins live in an included build rather than `buildSrc`, and why Room 2.x was
chosen over the newer `room3`.

## Deliberate scope boundaries

Stated because they are choices, not omissions:

- **No real backend.** The remote source is a mock; a production server would demonstrate
  nothing about Android engineering.
- **Client-side role gating is a UX affordance, not a security boundary.** The client is under
  the user's control; the server is the only real authorisation point.
- **One entity, one capture screen, one sync path.** Depth over breadth — a second entity adds
  volume without demonstrating anything new.
- **Kotlin Multiplatform is not used.** The pure-Kotlin domain modules would make extraction
  feasible, but it is not claimed as shipped.

## Licence

Not yet licensed. All rights reserved.
