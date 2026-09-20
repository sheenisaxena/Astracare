package com.astracare

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.astracare.core.designsystem.theme.AstraCareTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's only activity.
 *
 * [AndroidEntryPoint] makes it a member of the Hilt graph, which is what lets
 * `hiltViewModel()` resolve a `@HiltViewModel` in the composables it hosts. Without it,
 * injection into any ViewModel scoped here fails at runtime rather than at compile time —
 * the one place Hilt cannot check for you.
 *
 * Everything else lives in [AstraCareApp]. The activity's whole job is to be an entry point:
 * a composable shell can be previewed, screenshot-tested and reasoned about, while an
 * activity cannot.
 *
 * As of Day 11 this no longer renders the `Greeting("Android")` that the project template
 * shipped with — the first thing any reviewer opening this repository would have seen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenCaptureProtection()
        enableEdgeToEdge()
        setContent {
            AstraCareTheme {
                AstraCareApp()
            }
        }
    }

    /**
     * Blocks screenshots, screen recording and the recents-screen thumbnail. Day 16.
     *
     * Encrypting the database protects the records on disk and does nothing about the copy the
     * system writes when the app is backgrounded: the recents thumbnail is a picture of
     * whatever was on screen, stored outside the app's own storage. On the capture screen that
     * is a child's name, age and village. [WindowManager.LayoutParams.FLAG_SECURE] is the one
     * flag that covers screenshots, recording, casting and that thumbnail together.
     *
     * ## Why it is set on the whole activity
     *
     * Both screens show beneficiary records, so scoping it to the capture form would protect
     * the shorter exposure and leave the longer one. Setting it once here also means a third
     * screen is protected by default rather than by remembering — the safe direction for a
     * flag whose absence is invisible.
     *
     * ## Why debuggable builds are exempt
     *
     * FLAG_SECURE blocks the developer's own screenshots too, including the ones the README
     * and any future screenshot test need. Keying the exemption off `FLAG_DEBUGGABLE` rather
     * than a `BuildConfig.DEBUG` constant ties it to the property that actually matters — a
     * build that is debuggable has already surrendered far more than its screenshots, and one
     * that is not is protected no matter which build type produced it.
     */
    private fun applyScreenCaptureProtection() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
    }
}
