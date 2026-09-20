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

`JAVA_HOME` must point at that JDK as an **OS-level environment variable**, not only inside the
IDE. Android Studio's Gradle JDK setting (Settings → Build Tools → Gradle → Gradle JDK) is
internal to Studio, so a build started from the IDE succeeds while the git hooks below fail with
`JAVA_HOME is not set and no 'java' command could be found in your PATH` — hooks run `./gradlew`
in a plain shell that never sees that setting. `org.gradle.java.home` does not cover this either:
it selects the JDK for the Gradle daemon, but the wrapper needs a JVM before it can read it.

```bash
# macOS / Linux — in ~/.zshenv (not ~/.zshrc), so non-interactive shells inherit it too
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # Linux: e.g. /usr/lib/jvm/temurin-21-jdk

"$JAVA_HOME/bin/java" -version                       # verify — must report 21
```

```powershell
# Windows — user-level; restart Android Studio and any open terminals afterwards
[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Program Files\Eclipse Adoptium\jdk-21','User')
```

Android Studio's bundled runtime (`<studio>/jbr`) works as a fallback if its `java -version`
reports 21, but it moves on Studio updates — a standalone JDK is the more stable target.

```bash
./gradlew assembleDebug
./gradlew test                   # includes the Robolectric end-to-end UI test
./gradlew connectedDebugAndroidTest   # migration, DAO and encryption tests; needs a device
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
- Domain model and use cases; Room as the single source of truth, schema v4 with hand-written
  migrations and no destructive fallback
- Compose capture screen with MVI state management; paged record history ordered in SQL
- WorkManager sync: push, pull, and conflict detection keyed on sync status rather than clocks
- Database encrypted at rest with SQLCipher, passphrase wrapped by an Android Keystore key
- Two roles with a declarative permission matrix, and an append-only audit trail
- 155 JVM tests, including an end-to-end Compose journey on Robolectric and a test asserting
  that no personally identifying information reaches the log
- Instrumented tests for every migration, the `CASE` ordering, the conditional writes and the
  append-only triggers — written, and runnable on demand rather than in CI

**Next**

- An instrumented CI lane, so the database tests run on every push rather than on request
- Macrobenchmark module with baseline profiles
- R8 enabled for release builds

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
  the user's control; the server is the only real authorisation point. The app has no sign-in
  at all — the role switch in the UI says so on screen. Permissions are still enforced below
  the UI, because hiding a button and refusing an action are different guarantees, but that is
  correctness rather than security. See DECISION_LOG 11.2.
- **The audit trail is a local activity log, not an accountability record.** It records a
  *role*, not a person, because there is nobody to authenticate against. Anyone holding the
  handset can change the role; the change is itself logged, which makes tampering visible and
  not impossible. A real audit requirement is met by a server recording what it receives from
  an authenticated principal, in storage the client cannot reach. See DECISION_LOG 11.5.
- **One entity, one capture screen, one sync path.** Depth over breadth — a second entity adds
  volume without demonstrating anything new.
- **Kotlin Multiplatform is not used.** The pure-Kotlin domain modules would make extraction
  feasible, but it is not claimed as shipped.

## Data protection

The records are children's names, ages, villages and body measurements, held on a handset in
the field. India's Digital Personal Data Protection Act, 2023 and the DPDP Rules notified in
November 2025 are the relevant regime, and they are phased in over the period following
notification.

**This project does not claim DPDP compliance**, and none of the following is legal advice —
a real deployment needs counsel and a Data Protection Officer's review, not a README. What the
build does do is follow the principles the Act is built on, where an Android client is the
right place to follow them:

- **Data minimisation.** The schema holds only fields a malnutrition screening actually uses.
  There is no phone number, no address beyond a village name, no caregiver identifier, and no
  device or advertising identifier. Record IDs are device-minted UUIDs carrying no meaning.
- **Purpose limitation.** One entity, one purpose. Nothing here is collected for analytics, and
  the app has no analytics SDK.
- **Storage limitation, partially.** Drafts are cleared on save or discard. Records are not yet
  subject to a retention window, which is an open item rather than a decision — retention
  belongs with the server that would enforce it.
- **Security safeguards.** Encryption at rest (SQLCipher, Keystore-wrapped key), backup and
  device-transfer both disabled so the database cannot be extracted through those channels,
  `FLAG_SECURE` on release builds so the recents thumbnail is not a screenshot of a child's
  name, and no cleartext traffic permitted. DECISION_LOG Part 10 states what this protects
  against and, more importantly, what it does not.
- **Accountability.** An append-only audit trail, with its limits stated above and in
  DECISION_LOG 11.5 rather than overstated.

**The largest stated gap:** the data subjects are children under five, and DPDP places
additional obligations on processing children's personal data — including verifiable parental
consent. This app implements no consent capture, no notice, and no grievance mechanism. Those
are not client-side features, and pretending otherwise would be the wrong kind of completeness.

## Licence

Not yet licensed. All rights reserved.
