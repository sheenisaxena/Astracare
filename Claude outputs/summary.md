# AstraCare — Session Summary

Working notes from the build sessions. Private: git-excluded via `.git/info/exclude`, so it
never reaches the public repo.

**As of 20-Sep-2026** · repo `github.com/sheenisaxena/Astracare` (public) · Days 14–23 written
to disk · Day 24 (system design drill 1) in progress.

> Replaces the 17-Sep version. Nothing lost that matters — the Day 10–13 detail it carried now
> lives in `docs/DECISION_LOG.md` Parts 7–9.

---

## 1. Where the plan stands

Master plan: `AstraCare_Master_Plan.xlsx` (Daily Plan, Progress Matrix, Flagship Checklist,
Reconciliation, Target Module Map).

| Day | Work | Status |
|---|---|---|
| 1–4 | Repo hygiene, catalog + Hilt, convention plugins, detekt + CI | Done |
| 5–6 | Job search | Running in parallel, off-plan |
| 7–13 | Domain, Hilt graph, Room, MVI, Compose UI, Paging, sync push | Done |
| 14 | Sync pull + conflict resolution | **Done** |
| 15 | Mock interview + applications | Running in parallel, off-plan |
| 16 | SQLCipher encryption + Keystore | **Done** |
| 17 | RBAC + append-only audit trail | **Done** |
| 18 | Unit tests — validator, mappers, defensive decoding | **Done** |
| 19 | Logging seam, mock-server tests, MockK where it earns its place | **Done** |
| 20 | End-to-end Compose test, PII log audit, migration + DAO tests | **Done** |
| 21 | `:macrobenchmark` module, cold start before any profile | **Done, unmeasured** |
| 22 | Baseline profile generator, `Partial(Require)` benchmark | **Done, unmeasured** |
| 23 | README build-out, architecture diagram, `docs/BUILDING.md` | **Done** |
| 24 | **System design drill 1 — offline-first sync engine** | **In progress** |
| 25–26 | System design drills 2 and 3 | Pending |
| 27–32 | Remaining interview prep and polish | Pending |

Ten build days completed in this session. The app is demonstrable end to end and the
documentation is in a state a reviewer can navigate.

---

## 2. What got built, day by day

### Day 14 — sync pull and conflict resolution (37 files)

`ConflictResolver` is the centrepiece and the thing to talk about in interviews. It keys
detection on **`SyncStatus`, never on comparing clocks** — client wall-clock time is not
monotonic, and a device whose clock is wrong silently wins or loses every conflict. Delta pulls
use an **opaque `SyncCursor`**, not a timestamp, so a server change stamped behind the device's
own clock cannot be skipped. `PushBeneficiariesWorker` became `SyncBeneficiariesWorker`.
DECISION_LOG Part 9.

### Day 16 — encryption at rest (13 files)

SQLCipher over the whole database, not the two PII columns — column encryption leaves indexes,
the WAL and the free list readable. The passphrase is wrapped by an Android Keystore AES-GCM key
with `setUnlockedDeviceRequired` on API 28+. No `fallbackToDestructiveMigration`, so a key that
is gone with the blob still present is fatal rather than silently destructive.
`allowBackup=false`, `FLAG_SECURE` on non-debuggable builds. Jetpack Security turned out to be
deprecated. DECISION_LOG Part 10, including the threat model.

### Day 17 — roles and audit trail (37 files)

`RolePermissions` is a nested exhaustive `when` with no `else`, so a new role or capability fails
the build at the one place that must have an opinion. Append-only enforced by **SQLite triggers
installed from both the migration and a `RoomDatabase.Callback.onCreate`** — Room runs no
migration on a fresh install, so a migration-only version of the guarantee holds on upgraded
devices and silently fails on new ones. DECISION_LOG Part 11.

### Days 18–19 — tests, and a logging seam that unblocked them

Boundary tests for the validator (including NaN and infinity), mapper and defensive-decoding
tests. Then Day 19 found that `MockRemoteBeneficiarySource` could not be unit-tested because it
called `android.util.Log` directly — so `Logger` went into `:core:common` with the Android
implementation in `:app`. Rejected Robolectric there: a large dependency to avoid writing a
thirty-line interface. Parts 12 and 13.

**Mutation testing found two real gaps** that reading the code did not, on two consecutive days.
The better one: `accepted.put` → `putIfAbsent` survived, because the idempotence test asserted
record *count*, which `putIfAbsent` also satisfies while silently discarding every later edit.
Idempotent upsert and ignore-if-present are different guarantees.

### Day 20 — the end-to-end test, and the two bugs it found

`CaptureToHistoryTest` runs on **Robolectric**, deliberately, so it is in `./gradlew test` rather
than an instrumented lane CI does not run. Day 19 rejected Robolectric and this is not a
reversal — different problem, no alternative here.

It failed on its first two runs, and both failures were real:

1. **The FAB had no accessible name.** `ExtendedFloatingActionButton` wraps its `text` slot in
   `clearAndSetSemantics {}` — Material's contract puts the button's name on the `icon` slot,
   and we passed `icon = {}`. TalkBack announced "button". 152 unit tests, a design system that
   gets this right in `StatusChip`, and twelve days of reading all missed it.
2. **`FakeBeneficiaryRepository` had reported "still loading" since Day 8.**
   `PagingData.from(list)` — the single-argument, *deprecated* overload — leaves every
   `LoadState` as `Loading` permanently, so the list rendered a spinner forever. The build had
   been printing the deprecation warning for twelve days.

Also written: `MigrationTest` (every migration, the 1→4 chain carrying a PENDING record, and a
`DATABASE_VERSION - 1 == ALL_MIGRATIONS.size` drift guard), `BeneficiaryDaoTest` (the `CASE`
ordering, both conditional writes, `INSERT OR IGNORE`, triggers on the fresh-install path) and
`PiiLoggingTest` (drives real paths with unmistakable PII, inspects the whole cause chain).
Parts 14, 14.9 and 14.10.

### Days 21–22 — the instrument, then the thing it measures

`:macrobenchmark` is a `com.android.test` module driving `:app` from a separate process, with a
`benchmark` build type in `:app` (release settings, debug signing, not debuggable). `profileable`
lives in `app/src/benchmark/AndroidManifest.xml`, **not** the main manifest — main merges into
every variant, and a release build exposing method traces of a process showing a child's name
would have undone part of Day 16.

**The first frame of this app is a spinner**, so TTID measures how fast a loading indicator
appears. `BeneficiaryListScreen` now calls `ReportDrawnWhen { !isRefreshing }` and TTFD is the
metric. TTID is still recorded because TTFD − TTID isolates the cost of the encryption decision.

Day 22 added `BaselineProfileGenerator` (launch **and** the first interaction — a startup-only
profile makes the app open fast and hang on the first tap), `profileinstaller`, and
`CompilationMode.Partial(BaselineProfileMode.Require)`. `Require` over `UseIfAvailable` because
the latter reports a number with no profile installed, which turns noise into a CV bullet.

Three compilation modes, not one: `None` is the floor, `Full` the ceiling, and the honest figure
is **share of available headroom**, not "Δ vs None". Parts 15 and 16.

### Day 23 — documentation audit

The ASCII architecture diagram had been **wrong since Day 3**: it showed `:feature:patients` and
`:core:sync` depending on `:core:data`. Neither does. Replaced with `docs/architecture.svg`,
drawn from the actual `project(":...")` declarations, showing the compiler-enforced Kotlin/JVM
line, UI and infrastructure as two columns, and `:app`'s dashed edges (Hilt bindings for types it
never names).

Thirty lines of `JAVA_HOME` troubleshooting moved to `docs/BUILDING.md`. Added a "Read this repo
in ten minutes" section naming five files and why each matters. Part 17.

---

## 3. Verification state

- **152 JVM tests pass** in the offline harness; 155 once the three Robolectric tests run.
- **detekt clean** at `maxIssues: 0` against the project's real `config/detekt/detekt.yml`.
- **All eight harness stages green** — including type checks for the instrumented tests, the
  shared test doubles, the Robolectric test and `:macrobenchmark`.
- **Never executed here:** the three Robolectric tests since the last fix, anything
  instrumented, and both benchmark runs.

The harness lives in the cloud session, not the repo: `verify.sh` (8 stages), `sync-detekt.sh`
plus a `detektcheck` Gradle project, and `mutate.sh`. It will not survive indefinitely.

---

## 4. Outstanding — things only you can do

**Blocking a clean build:**

1. **Delete** `app/src/androidTest/kotlin/com/astracare/di/TestDataModule.kt` and
   `FakeBeneficiaryRepository.kt`. They moved to `src/sharedTest/` and the duplicates break the
   `androidTest` compile.
2. **Delete** `core/sync/.../PushBeneficiariesWorker.kt`. Not a compile error — it is dead code —
   but WorkManager stores worker class *names*, so stale periodic work enqueued before Day 14
   keeps instantiating it and never pulls.
3. **Delete** `app/src/test/kotlin/com/astracare/SemanticsDumpTest.kt` once
   `CaptureToHistoryTest` is green. It was diagnostic scaffolding.

**Blocking tests from running:**

4. **Recover `2.json` and `3.json`** into `core/data/schemas/`. Only `1.json` was ever committed
   and a build exports only the current version, so they come from git history — the recipe is in
   `MigrationTest`'s KDoc. Every test in that file fails until they exist, and hand-writing them
   will not work: the JSON carries an identity hash Room computes from the entities.

**Pending measurement:**

5. Run `.\gradlew.bat :app:testDebugUnitTest` and confirm the three Robolectric tests pass.
6. Generate the baseline profile, commit it to `app/src/main/baseline-prof.txt`, then run all
   three compilation modes and fill in `docs/PERFORMANCE.md`. Both tables are empty.
   `coldStartBaselineProfile` fails by design until that profile exists.
7. Record screens — the `adb screenrecord` and `ffmpeg` commands are in a comment in the README's
   Screens section.

**Environment note:** PowerShell needs `.\gradlew.bat`, not `./gradlew`.

---

## 5. Conventions in force

- Build → `./gradlew detekt test assembleDebug` → commit with a body **naming the rejected
  alternative** → push → append to `docs/DECISION_LOG.md`.
- Commit trailers: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` and `Claude-Session:`.
- Git hooks self-install on Gradle sync: detekt on commit, tests on push.
- Every decision-log part states what the day does **not** prove, written before the numbers
  exist so a disappointing result cannot quietly drop it.

---

## 6. Stack facts worth having to hand

Kotlin 2.2.10 · AGP 9.3.1 · JDK 21 · Gradle 9.5 · compileSdk 37 / minSdk 24 · Room 2.8.4 ·
Paging 3.5.0 · Work 2.11.2 · Hilt 2.59.2 · SQLCipher 4.19.0 · Robolectric 4.17 · benchmark 1.4.1
· detekt 1.23.8.

Eight modules; `:core:model`, `:core:common` and `:core:domain` are Kotlin/JVM (`java-library`),
so `import android.*` in the domain layer is a compile error. Eight convention plugins in
`build-logic`. R8 is **off** — a standing open item, and the reason every benchmark figure will
be an upper bound.

---

## 7. Day 24 — in progress

System design drill 1: *design an offline-first sync engine*. Running as a **mock interview** — I
play a staff engineer at a health-tech company. Brief: 200k devices growing to a million, 30–80
records a day at ~2 KB, sync gaps of hours to days, records edited after creation, supervisors
correcting them on a web dashboard, health data about children.

The trap to avoid is answering by narrating AstraCare. Several things an interviewer will push on
— tombstones and deletes, multi-writer conflicts on one record, cursor expiry and full resync —
are *open items* in the decision log precisely because the small version did not need them. The
senior answer is "here is what I built, here is what changes at that scale, and here is why the
small version was correct for its constraints."

The strongest material is the reasoning, not the architecture: sync status over clocks, opaque
cursors over timestamps, refusing destructive migration fallback. Each is a failure mode that can
be named out loud.
