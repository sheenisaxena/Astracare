package com.astracare.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start, measured before there is a baseline profile to take credit for it.
 *
 * Day 21 of the plan says it plainly: *record the number first, or the delta is unprovable*.
 * A "40% faster startup" claim with no pre-measurement is not a result, it is a press release,
 * and it is the single most common unfalsifiable line on an Android CV.
 *
 * ## Two bounds, not one number
 *
 * [coldStartNoCompilation] runs with [CompilationMode.None], which wipes any AOT-compiled code
 * so every method starts interpreted. [coldStartFullCompilation] runs with
 * [CompilationMode.Full], which compiles everything ahead of time.
 *
 * Neither is what a user experiences. That is the point. They bracket it:
 *
 * - `None` is the floor — a first launch on a device that has learned nothing about this app.
 * - `Full` is the ceiling — more AOT code than a baseline profile will ever produce, because a
 *   profile deliberately compiles only the startup path to keep the APK small.
 *
 * Day 22 adds `Partial` with the generated profile, and the number that matters then is not
 * "how much faster than None" but **what fraction of the None→Full gap the profile recovered**.
 * A profile that closes 70% of a 300ms gap is a good profile; one that closes 70% of a 20ms gap
 * is a rounding error someone is about to put on a CV. Measuring only against `None` cannot
 * tell those two apart, and that is why there are two tests here instead of one.
 *
 * ## What is being timed
 *
 * [StartupTimingMetric] reports two values. `timeToInitialDisplay` is the first frame;
 * `timeToFullDisplay` ends at `reportFullyDrawn()`, which `BeneficiaryListScreen` calls once
 * Paging's refresh settles. For this app **TTFD is the real metric** — the first frame is a
 * spinner, because the database is encrypted and opening it means unwrapping a Keystore key
 * first. Quoting TTID would be measuring how fast the app can show a loading indicator.
 *
 * Both are recorded anyway. The gap between them is itself the interesting figure: it is the
 * cost of SQLCipher plus the first Room query, isolated from everything else in startup, and
 * it is the number to point at if the encryption decision (DECISION_LOG 10.1) is ever
 * challenged on performance grounds.
 *
 * ## Known measurement caveats, stated rather than discovered later
 *
 * - **The first iteration does more work.** `StartupMode.COLD` kills the process between
 *   iterations but does not clear app data, so iteration 1 creates the database and generates
 *   the Keystore key and the rest do not. The library reports the median, which makes this a
 *   distortion of one sample in [ITERATIONS] rather than of the result — but it is why the
 *   min is always noticeably below the median here, and why a run with `iterations = 3` is
 *   not worth recording.
 * - **The APK is not minified.** The `benchmark` build type inherits release's disabled R8
 *   (the standing open item), so these are upper bounds on a shipped build's numbers.
 * - **Emulator numbers are numbers about the host machine.** Run this on a physical device or
 *   do not record it; see `docs/PERFORMANCE.md`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStartNoCompilation() = measureColdStart(CompilationMode.None())

    @Test
    fun coldStartFullCompilation() = measureColdStart(CompilationMode.Full())

    /**
     * Day 22: the same launch, with the baseline profile applied and nothing else.
     *
     * [BaselineProfileMode.Require] rather than `UseIfAvailable`, and the difference is the
     * whole integrity of the result. `UseIfAvailable` runs happily when no profile is
     * installed, reports a number indistinguishable in shape from a real one, and the
     * before/after table then compares `None` against `None` — a 0% improvement written up as
     * a measurement, or worse, noise written up as a win. `Require` fails the test instead,
     * which is the only behaviour that cannot produce a false result.
     */
    @Test
    fun coldStartBaselineProfile() =
        measureColdStart(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measureColdStart(compilationMode: CompilationMode) = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
        setupBlock = {
            // Home first, so the launch being timed is a launch and not a return to a task
            // that is already in the recents stack.
            pressHome()
        },
    ) {
        // startActivityAndWait returns at the first frame, not at reportFullyDrawn — the
        // library reads TTFD from the system afterwards rather than from this block. Waiting
        // for the list here would time the test's own polling loop instead.
        startActivityAndWait()
    }

    private companion object {
        /**
         * Hardcoded rather than read from BuildConfig.
         *
         * The instrumentation APK has its own application id, so `BuildConfig.APPLICATION_ID`
         * here is the benchmark's, not the app's — a mistake that produces "package not found"
         * on a device where the app is plainly installed.
         */
        const val TARGET_PACKAGE = "com.astracare"

        /**
         * Enough that the median is stable and the first-iteration effect described above is
         * diluted. Ten cold starts at roughly a second each, twice over, is about a minute of
         * device time — cheap enough to re-run after any change that touches startup.
         */
        const val ITERATIONS = 10
    }
}
