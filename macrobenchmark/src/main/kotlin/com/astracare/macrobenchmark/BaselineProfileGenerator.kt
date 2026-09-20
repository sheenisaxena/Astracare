package com.astracare.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces the baseline profile: the list of classes and methods ART should compile ahead of
 * time instead of interpreting on first run.
 *
 * ## What a profile actually is, since the name suggests something cleverer
 *
 * A text file of method signatures. Running this generator drives the app while ART records
 * which methods were executed, and the result is packaged into the APK; on install,
 * `profileinstaller` hands it to the runtime, which compiles those methods ahead of time. There
 * is no measurement in it and nothing adaptive — it is a hint about which code matters, and its
 * quality is entirely a function of what this journey touches.
 *
 * That is worth stating plainly because it explains the failure mode: a generator that only
 * launches the app produces a profile that makes launching fast and the first tap exactly as
 * slow as before. The user experiences that as "the app opens quickly and then hangs", which is
 * a worse impression than a uniformly slow app.
 *
 * ## The journey, and why it goes past startup
 *
 * [generate] does three things: cold launch to the settled list, open the capture form, and
 * come back. The first is the startup path. The second is the first thing a health worker does
 * — the reason the app exists is that somebody taps "Add record" — and it pulls in the entire
 * MVI loop, the validator, the draft repository and a second screen's worth of Compose.
 *
 * `includeInStartupProfile = true` marks the whole collection as startup-relevant. A startup
 * profile is a smaller subset ART treats with higher priority; including the capture screen in
 * it is deliberate, because on this app the two are one continuous action.
 *
 * ## Generating on an emulator is fine. Measuring on one is not.
 *
 * `docs/PERFORMANCE.md` refuses emulator numbers, and this generator happily runs on one. Not
 * a contradiction: a profile is a *list of method names*, and which methods execute does not
 * depend on the host's thermals or scheduler. A timing does. The output of this file is
 * reproducible on any machine; the output of `StartupBenchmark` is not.
 *
 * ## Requires API 33+, or a rooted device below that
 *
 * ART only exposes the profile it recorded from API 33 onwards without root. On an older
 * device this fails with a permissions error that reads like a setup mistake. See
 * `docs/PERFORMANCE.md` for the Gradle Managed Device alternative, which sidesteps it entirely.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfile = BaselineProfileRule()

    @Test
    fun generate() = baselineProfile.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Wait for the list to settle rather than for a fixed time. Until Paging's refresh
        // lands, the only thing composed is a spinner, and a profile collected at that moment
        // would faithfully record the code path for showing a loading indicator.
        //
        // This is the same race Day 20's UI test lost on its first run, in a different harness.
        device.wait(Until.hasObject(By.desc(ADD_RECORD)), UI_TIMEOUT_MS)

        // The first interaction, not just the launch. Found by description because
        // ExtendedFloatingActionButton clears its label's semantics — the same reason the UI
        // test looks it up this way, and the reason that label exists at all (DECISION_LOG 14.9).
        device.findObject(By.desc(ADD_RECORD))?.click()
        device.wait(Until.hasObject(By.text(CAPTURE_TITLE)), UI_TIMEOUT_MS)

        // Back to the list, so the profile covers the return path too. A one-way journey
        // records only half of what a health worker repeats all day.
        device.pressBack()
        device.wait(Until.hasObject(By.desc(ADD_RECORD)), UI_TIMEOUT_MS)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.astracare"
        const val ADD_RECORD = "Add record"
        const val CAPTURE_TITLE = "New record"

        /**
         * Generous on purpose. This runs on whatever device is attached, with no AOT code at
         * all, and a timeout that fires early produces a *partial profile* rather than a
         * failure — the worst outcome available, because the build then succeeds and ships
         * something that looks like a baseline profile and covers half the journey.
         */
        const val UI_TIMEOUT_MS = 10_000L
    }
}
