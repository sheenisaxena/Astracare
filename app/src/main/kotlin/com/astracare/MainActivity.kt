package com.astracare

import android.os.Bundle
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
        enableEdgeToEdge()
        setContent {
            AstraCareTheme {
                AstraCareApp()
            }
        }
    }
}
