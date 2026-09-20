# Performance

Measured numbers and the conditions that produced them. A figure with no device, no build type
and no iteration count next to it is not a measurement, so nothing goes in the tables below
without all three.

---

## Method

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

Requires a **physical device**, API 29 or higher, connected and unlocked.

### Why not an emulator

An emulator shares the host's CPU scheduler, thermal behaviour and page cache. Its startup
numbers move when the laptop compiles something in another window, which makes them numbers
about the laptop. They are not comparable between machines, between runs, or to anything a
health worker will experience on a low-end phone in the field.

### Before running

- Plug the device in and unlock it. The database key is created with
  `setUnlockedDeviceRequired` on API 28+, so a locked screen turns a benchmark into a crash
  (DECISION_LOG 10.4).
- Close other apps. Macrobenchmark warns about background load; it cannot eliminate it.
- Let the device cool if it has just been charging fast or running a build. Thermal throttling
  is the single largest source of drift between two runs of the same commit.
- Uninstall any previous `com.astracare` build. The `benchmark` variant shares the application
  id deliberately, and an older install will be replaced rather than measured.

### What is reported

`StartupTimingMetric` produces two figures per run:

| Metric | Ends at | What it means here |
|---|---|---|
| `timeToInitialDisplay` (TTID) | first frame | **A spinner.** The database is encrypted, so nothing can be shown until a Keystore key is unwrapped and Room has answered. |
| `timeToFullDisplay` (TTFD) | `reportFullyDrawn()` | The frame that carries records. `BeneficiaryListScreen` reports this once Paging's refresh settles. |

**TTFD is this app's startup number.** TTID is recorded because the gap between the two is the
isolated cost of opening an encrypted database — the figure to point at if the SQLCipher
decision is ever questioned on performance grounds.

### Reading `CompilationMode`

Two bounds rather than one number, so that Day 22's baseline profile can be judged as a
fraction of the gap available to it rather than against nothing:

- **`None`** — all AOT code wiped; every method starts interpreted. The floor.
- **`Full`** — everything compiled ahead of time. The ceiling, and more than a baseline profile
  will ever deliver, because a profile compiles only the startup path on purpose.
- **`Partial`** (Day 22) — the generated baseline profile. The question it answers is *what
  share of the `None` → `Full` gap did the profile recover*.

---

## Results

### Run 1 — before any baseline profile (Day 21)

Device: _(model, Android version, chipset)_
Build type: `benchmark` (release settings, **R8 disabled** — see caveats)
Iterations: 10
Date: _(YYYY-MM-DD)_ · Commit: _(sha)_

| Compilation | TTID median | TTID min–max | TTFD median | TTFD min–max |
|---|---|---|---|---|
| `None` | | | | |
| `Full` | | | | |

Derived:

| Figure | Value | How |
|---|---|---|
| Encryption + first query cost | | TTFD − TTID, `None` row |
| Headroom a profile could recover | | TTFD(`None`) − TTFD(`Full`) |

### Run 2 — with a baseline profile (Day 22)

_To be filled after Day 22._

| Compilation | TTFD median | Δ vs `None` | Share of available headroom |
|---|---|---|---|
| `None` | | — | — |
| `Partial` (baseline profile) | | | |
| `Full` | | | 100% |

---

## Caveats that apply to every number above

1. **The APK is not minified.** The `benchmark` build type inherits `release`'s disabled R8, so
   these are upper bounds on a shipped build. Enabling R8 is its own open item, and when it
   lands every figure here has to be re-measured rather than adjusted.
2. **The first iteration does more work than the rest.** `StartupMode.COLD` kills the process
   between iterations but does not clear app data, so iteration 1 creates the database and
   generates the Keystore key. The median absorbs this; the minimum does not, which is why the
   min is expected to sit noticeably below it.
3. **One device is one data point.** A mid-range phone from four years ago is the honest target
   for this app, and a flagship's numbers say very little about it.
4. **Nothing here is measured in CI.** Benchmarks need a device, take minutes, and produce a
   measurement rather than a pass or a fail. Re-run them by hand after any change that touches
   application startup, Hilt graph construction, or the database open path.
