# AstraCare — Session Summary

Working notes from the build sessions. Private: git-excluded via `.git/info/exclude`, so it
never reaches the public repo.

**As of 17-Sep-2026** · repo `github.com/sheenisaxena/Astracare` (public) · 59 Kotlin files ·
Days 10, 11 and 12 written to disk, **not yet committed or pushed**.

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
| 12 | Paging 3 over Room, ordering moved into SQL | **Done** |
| 13 | **Sync push: :core:sync + WorkManager — next up** | Pending |
| 14–32 | Sync pull + conflicts, security, testing, perf, docs, interview prep | Pending |

**Honest status on schedule.** Ten build days complete out of 32. Day 11 absorbed three
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
:core:sync            (empty — Days 13-14)
:core:data            Room DB v2, paged DAO, beneficiaries + capture_draft, migration, repos
:core:domain          repository INTERFACES, use cases, validation
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

38 unit tests. `:core:domain` has its own tests for the first time since Day 7 — five of them,
all guarding the new Kotlin-declares / SQL-executes seam.

### Versions pinned

Kotlin 2.2.10 · AGP 9.3.1 · Gradle 9.5 · JDK 21 · compileSdk 37 / minSdk 24 ·
KSP 2.2.10-2.0.2 · Hilt 2.59.2 · Room 2.8.4 · detekt 1.23.8
New on Day 11: `lifecycle-runtime-compose`, `hilt-navigation-compose`.
New on Day 12: `paging-common` (in `:core:domain`), `room-paging`, `paging-compose` — all on
the `paging = 3.5.0` and `room` refs the catalog already carried.

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

**Verified:** all domain, model, MVI and reducer code compiles under Kotlin 2.2.10 against
real `paging-common` classes. All **38 unit tests pass**, including autosave debounce timing on
virtual time. Every Compose file type-checks clean against real Compose 1.12.1 / Material3
1.4.0 / paging-compose 3.5.0 artifacts and a real `android.jar` — zero unresolved references.
**detekt 1.23.8 with the project's own `config/detekt/detekt.yml` and the formatting plugin is
clean across all 57 files.**

**Not verified — run the build before trusting these:**

- **The Hilt graph.** `CaptureViewModel` gained three constructor dependencies and there are
  two new `@Binds`/`@Provides`. KSP has to resolve all of it; nothing here can check that.
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

## 7. Next session — Day 13 (sync push)

New `:core:sync` module, finally non-empty. A `@HiltWorker` that reads `pendingSync()` and
pushes to the mock remote, with a network constraint and exponential backoff. Idempotent
writes, because a worker that retries after a response was lost must not create a duplicate
record — device-generated UUIDs already make that possible.

Watch for: `hilt-work` and `androidx-hilt-compiler` are in the catalog but have never been
applied, so `:core:sync` needs its own Hilt/KSP wiring and `AstraCareApplication` needs a
`Configuration.Provider`. Also, Paging's retry branch and `SyncStatus.FAILED` both become
reachable for the first time — that is when the LoadState UI deferred on Day 12 earns its
place.

**Protect Day 15.** It is the mock interview plus an applications batch, immediately after the
sync work, and it is the first job-search day in six weeks that has not already slipped.

**Standing workflow per day:** build → `./gradlew detekt test assembleDebug` → commit with a
body that names the rejected alternative → push → append to `docs/DECISION_LOG.md`.

## Files to delete by hand

Neither the bridge nor I can delete on your disk. Outstanding:

- `app/src/main/kotlin/com/astracare/ui/theme/` — three files, superseded on Day 11 by
  `:core:designsystem` (skip if you already did this)
- `feature/patients/src/test/kotlin/com/astracare/feature/patients/BeneficiaryListUiStateTest.kt`
  — tests `toUiState()`, which Day 12 removed. **The build will not compile until this is
  gone.**
