package com.astracare

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEMPORARY — delete once `CaptureToHistoryTest` is green.
 *
 * Dumps the semantics tree of the first screen, merged and unmerged, with every node's bounds.
 * That distinguishes the three explanations for "the node exists but is not displayed" —
 * zero-sized root, node laid out off-screen, or a merge boundary the finder is not crossing —
 * which the failure messages alone cannot.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class SemanticsDumpTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun dumpTheFirstScreen() {
        compose.waitForIdle()

        val activity = compose.activity
        val root = activity.window.decorView
        println("### DISPLAY " + activity.resources.displayMetrics)
        println("### DECOR ${root.width}x${root.height} attached=${root.isAttachedToWindow} shown=${root.isShown}")

        println("### MERGED ###")
        println(compose.onRoot().printToString(maxDepth = 100))

        println("### UNMERGED ###")
        println(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 100))
    }
}
