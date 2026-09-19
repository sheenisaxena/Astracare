# AstraCare — Session Summary

Working notes from the build sessions. Private: git-excluded via `.git/info/exclude`, so it
never reaches the public repo.

**As of 17-Sep-2026** · repo `github.com/sheenisaxena/Astracare` (public) · 63 Kotlin files ·
Days 10–13 written to disk. **Nothing pushed yet** — the Day 12 build failed on a stale test
file that must be deleted first (section 8).

---

## 1. Where the plan stands

Master plan: `AstraCare_Master_Plan.xlsx` (5 sheets — Daily Plan, Progress Matrix, Flagship
Checklist, Reconciliation, Target Module Map).

| Day | Work | Status |
|---|---|---|
| 1–4 | Repo hygiene, catalog + Hilt, convention plugins, detekt + CI | Done |
| 5–6 | **Job search** — resume, referrals, applications | **Skipped** |
| 7 | Domain layer | Done |
| 8 | Cross-module Hilt graph + test seam | Done |
| 9 | Room as single source of truth | Done |
| 10 | MVI — both ViewModels, pure reducers, 19 unit tests | Done |
| 11 | Compose UI, design system, Room-backed autosave, nav shell | Done |
| 12 | Paging 3 over Room, ordering moved into SQL | Done |
| 13 | Sync push: :core:sync, WorkManager, mock remote | **Done** |
| 14 | **Sync pull + conflict resolution — next up** | Pending |
| 15 | **MOCK INTERVIEW + applications** — protect this one | Pending |
| 16–32 | Security, testing, perf, docs, interview prep | Pending |

**Honest status on schedule.** Eleven build days complete out of 32. Day 11 absorbed three
things the plan had scattered — the UI day, the DataStore integration the Reconciliation sheet
folded in, and the inline navigation it cut as a standalone day — so it ran well over its
2-hour box. Worth it: the app is now demonstrable end to end.

**The one real risk, unchanged and compounding:** Days 5, 6 and 15 are the entire job-search
allocation before Week 4 and all three are still unstarted, now six weeks past their dates.
Referral replies lag 1–2 weeks, so this is lead time being spent rather than banked. The
plan's own rule is *cut flagship features, never interview prep*.

Days 13–14 are the sync engine, which is the single most differentiating thing in this
project and the hardest to cut. Day 15 is a mock interview and an applications batch, and it
sits immediately after them. That is the one to protect.

---

## 2. What exists now

### Modules (7 + build-logic)

```
:app                  MainActivity, AstraCareApp (inline 2-destination nav)
:feature:patients     MVI + Compose: capture form, paged history list, effect observer
:core:sync            WorkManager push worker + scheduler (pull lands Day 14)
:core:data            Room DB v2, paged DAO, beneficiaries + capture_draft, migration, repos
:core:domain          repository + remote + scheduler INTERFACES, use cases, validation
:core:model           Beneficiary, Measurement, SyncStatus, Timestamp, CaptureDraft
:core:common          Outcome, TimeProvider, DI modules
:core:designsystem    theme, spacing/sizing tokens, StatusChip
```

`:core:model`, `:core:common`, `:core:domain` are **Kotlin/JVM, not Android** — the Android SDK
is not on their classpath, so `import android.*` in the domain layer does not compile.

### Paging

The history list is a `PagingSource` over Room, 30 rows a page, placeholders off, `cachedIn`
the ViewModel. Sort order moved out of Kotlin and into the query — the rule still lives in
`:core:domain` as `RecordAttentionOrder` and the DAO binds its values into an `ORDER BY CASE`.
The Day 10 sealed `Loading | Empty | Content` is gone; Paging's own `LoadState` replaced it.

### Sync

A saved record is pushed within seconds: the repository enqueues unique one-time work after
each successful local write, and an hourly periodic pass sweeps anything missed. The mock
server accepts idempotently, rejects permanently, and fails transiently every fifth call so
retry and backoff are exercisable on a real device.

`SyncStatus` gained **REJECTED** — a permanent refusal is neither FAILED (retryable) nor
CONFLICTED (server has a newer version). No migration needed: the column stores the enum name.

### The app runs a flow

Launch → record list (Loading / Empty / Content) → **Add record** → capture form → Save →
back to the list with the record visible and a sync chip on it. System back works. The form
autosaves and survives the app being killed.

`MainActivity` no longer renders `Greeting("Android")`.

### Database

Version **2**. `beneficiaries` plus `capture_draft` (single row, all-TEXT columns), added by a
hand-written `MIGRATION_1_2`. No `fallbackToDestructiveMigration`, so a missing migration
fails loudly rather than deleting unsynced field data.

### Quality gates

| Gate | When | Bypassable |
|---|---|---|
| `.githooks/pre-commit` → detekt | every commit | yes (`--no-verify`) |
| `.githooks/pre-push` → unit tests | every push | yes |
| GitHub Actions CI | every push / PR | no |
| Branch protection | — | **not enabled yet** |

46 unit tests. `:core:domain` now carries 13 of them — the ordering guard plus the push loop,
including the stale-write case that has no visible symptom.

### Versions pinned

Kotlin 2.2.10 · AGP 9.3.1 · Gradle 9.5 · JDK 21 · compileSdk 37 / minSdk 24 ·
KSP 2.2.10-2.0.2 · Hilt 2.59.2 · Room 2.8.4 · detekt 1.23.8
New on Day 11: `lifecycle-runtime-compose`, `hilt-navigation-compose`.
New on Day 12: `paging-common` (in `:core:domain`), `room-paging`, `paging-compose`.
New on Day 13: `work-runtime-ktx`, `androidx-hilt-work`, `androidx-hilt-compiler` — all already
in the catalog, applied for the first time.

---

## 3. Decisions worth being able to defend

Full reasoning with rejected alternatives: **`docs/DECISION_LOG.md`** (committed, public) —
796 lines, Parts 5 and 6 are Days 10 and 11.

### Carried forward

**Domain modules are Kotlin/JVM.** Compiler-enforced layering beats documented convention.

**Capability-scoped convention plugins.** Compose isn't applied to `:core:data`, so a
`@Composable` cannot leak into the data layer — it wouldn't compile.

**`Timestamp` = epoch millis in a value class.** `java.time` needs desugaring below API 26;
kotlinx-datetime 0.7+ aliases to experimental types KSP cannot resolve.

**`syncStatus` as enum NAME, not ordinal** · **`@Upsert`, not `@Insert(REPLACE)`** ·
**no `fallbackToDestructiveMigration`** · **injected dispatchers and `TimeProvider`** ·
**repository interface in domain, impl in data** (swapping in-memory for Room changed one
`@Binds` line) · **fake, not mock** · **client wall-clock time is not trustworthy** (Kronos is
the production remedy, deliberately not adopted).

### Day 10 — MVI

**No `MviViewModel<S, I, E>` base class; three marker interfaces.** The capture form *owns*
its state (`MutableStateFlow` + reducer); the list *derives* its state from a Room `Flow`
(`map` + `stateIn`, no mutable holder). A base class holding a `MutableStateFlow` would force
the list to keep a second copy of the truth.

**Sealed `UiState` for the list, data class for the form.** Loading/Empty/Content genuinely
exclude each other, and the old `StateFlow<List<Beneficiary>>` could not tell "no records"
from "not asked yet". A form has no exclusive modes. "Always sealed" is advice that's wrong
half the time.

**Effects via `Channel(BUFFERED)` + `receiveAsFlow()`.** `SharedFlow(replay = 0)` drops events
with no collector; `replay = 1` re-navigates on return; a flag in state fires again on
rotation; `consumeAsFlow()` dies with the first collector.

**Two reducer entry points, not one sealed hierarchy.** `reduce(CaptureIntent)` for what the
user did, `reduce(SaveOutcome)` for what the save reported, `SaveOutcome` internal. The
textbook single hierarchy would make `dispatch(SaveSucceeded)` legal for a composable.

**Parse errors are the UI's, range errors are the domain's.** A test asserts `"400"` maps
*successfully*, pinning the boundary so it can't drift.

### Day 11 — UI, design system, drafts

**Drafts go in Room, not DataStore — and that overrides the plan.** The Reconciliation sheet
had pencilled in DataStore. Same durability, but it costs a second persistence stack for one
object: another thing to migrate, another thing to encrypt on Day 16, another place to look
when data goes missing. Choosing it would have bought a resume keyword at the cost of a worse
system. `SavedStateHandle` was rejected outright — it doesn't survive a flat battery, which
is the case that actually happens.

**The migration overload is a trap worth knowing.** Room 2.7 added
`migrate(SQLiteConnection)` alongside `migrate(SupportSQLiteDatabase)`; both are open and
**both throw `NotImplementedError` by default**. Override the wrong one and it compiles, looks
complete, and crashes on the first upgrade. `databaseBuilder` without `setDriver` uses the
framework driver, so `SupportSQLiteDatabase` is correct here.

**Autosave: debounced 400ms, restore strictly before the collector starts, cleared explicitly
on save.** If the collector started first it would write the blank initial state and the
restore would recover a draft it had already destroyed. `distinctUntilChanged` runs on the
mapped draft, not the state, so focus changes and error clearing don't restart the timer.

**Spacing tokens make a lint rule work for the architecture.** `MagicNumber` is active
project-wide and excludes only the design-system directories, so a literal `16.dp` in a
feature module is a build failure. The cheapest path through CI is the one that uses a token.
The alternative — adding feature paths to the exclude list — would have killed the rule to
solve a problem the design system solves anyway.

**The design system doesn't know what a beneficiary is.** `StatusChip` takes a `String` and a
`StatusTone`, not a `SyncStatus`. Three decisions would otherwise sit in the wrong module:
that CONFLICTED is critical while FAILED is only a warning, that FAILED reads "Retrying"
rather than "Failed", and what any of it is called in Hindi.

**Dynamic colour removed, not carried over.** This app uses colour semantically for sync
state; a wallpaper-derived palette can't be contrast-checked at build time, and it's
Android 12+ against minSdk 24.

**No Navigation Compose at two destinations.** `BackHandler` plus `rememberSaveable` with a
hand-written `Saver` do what a `NavHost` would. The line to watch: the first destination that
takes an argument — the record detail screen — is where the library wins.

**Route/Screen split on every screen.** The stateless half previews and UI-tests with no Hilt
graph. `ObserveEffects` uses `repeatOnLifecycle(STARTED)`, because a bare `LaunchedEffect`
keeps collecting from the back stack and fires navigation the user can't see.

### Day 13 — sync push

**The algorithm is a use case; the Worker is ten lines.** A `CoroutineWorker` can't be built
without a `Context`, so logic inside one is testable only under instrumentation. This is the
code that can lose field data, so it lives where plain JUnit reaches it — seven tests, no
Android.

**The stale write.** Between reading a record and marking it sent, the health worker can edit
it. An unconditional `SET sync_status = 'SYNCED'` then claims the *new* version reached the
server when only the old one did — the row reads SYNCED, the chip is grey, the edit is gone,
and nothing looks wrong. The mark is conditional on `updated_at` being unchanged, and every
fake in the suite enforces the same condition.

**REJECTED, and the Day 12 guard paying for itself.** `RecordAttentionOrderTest` failed on a
constant and pointed straight at the DAO's `CASE`, which nothing in the type system connects
to the Kotlin enum. The exhaustive `when` in `SyncStatusPresentation` caught the UI half at
compile time.

**`pendingSync` narrowed to retryable.** "Not SYNCED" also matches REJECTED and CONFLICTED, so
the old query had the handset re-pushing refused records forever. They still count toward the
sync banner — "what should we send" and "what isn't safe yet" are different questions.

**A transient failure stops the whole pass.** It's almost always the connection, not the
record, so the next forty attempts fail identically — battery and radio a field handset can't
spare. A permanent rejection does the opposite and the loop continues.

**`ExistingWorkPolicy.KEEP`, not REPLACE.** REPLACE would throw away a worker two minutes into
its backoff and restart the curve — the opposite of what backoff is for.

**Device-minted IDs pay off twice.** They made offline creation possible on Day 7; they're also
what makes retrying a lost response safe, because the server upserts by a key it didn't choose.

**Three pieces that only work together.** `@HiltWorker` + the KSP processor + `Configuration.
Provider` with the manifest initializer removed. Any one missing fails at *runtime* with a
message naming the worker rather than the cause.

---

## 4. Problems hit, and what they taught

| Problem | Cause | Fix |
|---|---|---|
| `Can't find module entity for Astracare.app` | `rootProject.name` changed mid-project | Reverted. **Never bundle a cosmetic rename with a structural refactor** |
| `kotlin.sourceSets DSL is not allowed` | KSP registers generated sources via `kotlin.sourceSets`, which AGP 9 rejects | Kept `android.disallowKotlinSourceSets=false` |
| `'Clock' could not be resolved` (KSP) | kotlinx-datetime 0.7 typealiases to experimental `kotlin.time` | Dropped for `Timestamp` + `TimeProvider` |
| detekt `ImportOrdering` ×6 | ktlint layout is `*, java, javax, kotlin` — `javax` goes **after** `kotlinx` | Reordered |
| **D10:** detekt `ComplexCondition` on form mapping | A four-way null guard written to enable smart casting | Split into `parseMeasurement()` + `malformedFields()`. Better anyway: no `!!`, and every bad field is named |
| **D10:** detekt `MatchingDeclarationName` | `CaptureReducer.kt`'s only top-level class was `SaveOutcome` | Moved it into `CaptureContract.kt`, where the other message types live. The rule found a real misplacement |
| **D11:** `Expecting a top level declaration` in a KDoc | Wrote a detekt glob inside a comment — the `*/` in it **closed the comment early** | Rephrased. Caught only because the file was actually compiled; reading it would never have found it |
| **D11:** detekt `SpreadOperator` on `addMigrations(*ALL_MIGRATIONS)` | Spread copies the array per call | `ALL_MIGRATIONS` became a `List`, registered with `forEach`. Also the better type — arrays compare by identity |
| **D11:** detekt `MatchingDeclarationName` again | `StatusTone` alone in `StatusChip.kt` | Own file. Right anyway — consumers use the tone without the chip |

Also corrected along the way: Hilt is **2.59.2**, not the 2.57.1 the docs page reports;
`gradle/actions` is at **v6**; `actions/setup-java` is at **v5**.

---

## 5. What was verified, and what wasn't

Worth being precise about, because "it compiles" was assumed on earlier days and is not
assumed here.

**Verified:** all domain, model, MVI, reducer and sync code compiles under Kotlin 2.2.10
against real `paging-common` and WorkManager 2.11.2 classes. All **46 unit tests pass**, including autosave debounce timing on
virtual time. Every Compose file type-checks clean against real Compose 1.12.1 / Material3
1.4.0 / paging-compose 3.5.0 artifacts and a real `android.jar` — zero unresolved references.
**detekt 1.23.8 with the project's own `config/detekt/detekt.yml` and the formatting plugin is
clean across all 70 files.**

**Not verified — run the build before trusting these:**

- **The Hilt graph — now the biggest unknown.** Day 13 added `@HiltWorker` (assisted
  injection), a new KSP processor in `:core:sync`, `SyncModule`, `WorkManagerModule`, and two
  injected fields on the Application. None of it can be checked without a real build, and all
  of it fails at runtime rather than compile time.
- **Whether WorkManager actually uses `HiltWorkerFactory`.** If the manifest edit is wrong the
  build stays green and the first push crashes with "Could not instantiate
  PushBeneficiariesWorker".
- **Room codegen and the migration SQL.** The `CREATE TABLE` is hand-matched to what Room
  generates for `DraftEntity`. Room validates it at open time and aborts on any mismatch, so
  if it's wrong it will be obvious immediately — on a device that already holds a v1 database.
- **Compose codegen.** Type-checking passed; the Compose compiler plugin itself couldn't run
  in the verification harness, so `@Composable` call-context rules are unchecked.
- **Nothing has been rendered on a screen.** Layout, spacing and contrast are unreviewed.
- **The `ORDER BY CASE` query.** Room compiles and validates it, but nothing checks it returns
  rows in the intended order. `RecordAttentionOrderTest` pins only the domain half.
- **The KMP variant split.** `:core:domain` compiles against `paging-common-desktop` while the
  app ships `-android`. Routine, but it surfaces as a `NoSuchMethodError` rather than a build
  failure if it does go wrong.

```
./gradlew detekt test assembleDebug
```

---

## 6. Known gaps

- **Days 5–6 job search not started** — six weeks overdue, and still the highest-priority item
- **Branch protection off** — CI detects, doesn't prevent
- **No instrumented or Compose UI tests** (Days 18–20). Two now compete for highest value: a
  `MigrationTestHelper` test for `MIGRATION_1_2`, and a DAO test asserting the paged
  `ORDER BY` actually returns attention-order. The migration can destroy data; the ordering
  can be wrong in a way nothing notices
- **No tests in `:core:data`.** The mapper round-trip
  (`assertEquals(entity, entity.toDomain().toEntity())`) is still the cheapest first one
- Paging's error/retry branch is unimplemented — a local database cannot fail a load, so it
  waits for RemoteMediator on Days 13–14
- Validation bounds are stated twice: `BeneficiaryValidator` and `strings.xml`. Accepted; the
  alternative is unlocalisable sentence fragments
- No logging abstraction — `RoomDraftRepository` calls `android.util.Log` directly
- Record detail screen doesn't exist; tapping a row does nothing
- MVI markers live in `:feature:patients/mvi`; they move to `:core:ui` at the second feature
- GitHub repo has no **description or topics** set
- `misc file` commit message is public; cleaning it needs a force-push (not worth it)

---

## 7. Next session — Day 14 (sync pull + conflict resolution)

The other direction: fetch server changes, detect conflicts, resolve them. This is the day the
project's hardest claim gets made, so the decision log entry matters as much as the code —
"why last-write-wins over vector clocks" is a question that gets asked.

Three things Day 13 left deliberately for Day 14:

- **`SyncStatus.CONFLICTED` is never set by anything.** Push cannot produce a conflict; only a
  pull can discover one.
- **Sync failures are invisible.** A record just sits PENDING. Day 14 has the UI surface.
- **Paging's error/retry branch** becomes reachable once a pull can fail — that's when the
  LoadState UI deferred on Day 12 earns its place.

Watch for: `Timestamp` comparison is the conflict rule, and DECISION_LOG 2.6 already records
that client wall-clock time is not trustworthy (Kronos is the noted remedy, deliberately not
adopted). Restate that limit honestly rather than presenting timestamp comparison as sound.

**Then protect Day 15.** Mock interview plus an applications batch. It is the first job-search
day in six weeks that has not already slipped, and it sits directly after the hardest build day
in the plan — which is exactly the position from which days get skipped.

**Standing workflow per day:** build → `./gradlew detekt test assembleDebug` → commit with a
body that names the rejected alternative → push → append to `docs/DECISION_LOG.md`.

## 8. Files to delete by hand — BLOCKING THE BUILD

Neither the bridge nor I can delete on your disk. Both are still present:

- `feature/patients/src/test/kotlin/com/astracare/feature/patients/BeneficiaryListUiStateTest.kt`
  — tests `toUiState()`, removed on Day 12. **This is what failed your last push.**
- `app/src/main/kotlin/com/astracare/ui/theme/` — three files, dead since Day 11.

```bash
git rm feature/patients/src/test/kotlin/com/astracare/feature/patients/BeneficiaryListUiStateTest.kt
git rm -r app/src/main/kotlin/com/astracare/ui/theme
```
