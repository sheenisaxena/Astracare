package com.astracare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
 * The one end-to-end journey: capture a record, save it, see it in the history list.
 *
 * ## Why this runs on the JVM
 *
 * `app/src/androidTest` has held the Hilt scaffolding for this test since Day 8 and the test
 * was never written. DECISION_LOG 4.5 excludes instrumented tests from CI, so writing it there
 * would have produced a test that exists and runs when somebody remembers — which is the exact
 * complaint Day 18's write-up made about every other instrumented test in this project.
 *
 * Robolectric puts it in CI. Day 19 *rejected* Robolectric for the logging problem, and this is
 * not a reversal: there it would have been a large dependency brought in to avoid writing a
 * thirty-line seam, and here there is no alternative that gets a UI test running automatically
 * at all. The same tool, two different questions.
 *
 * ## What it therefore does not cover, stated plainly
 *
 * - **Real rendering.** Robolectric's Compose support runs the composition and semantics tree,
 *   not a GPU. Layout that overlaps, text that clips, a touch target too small for a gloved
 *   thumb — none of it is visible here.
 * - **Real Room, and real SQLCipher.** The data layer is faked (see `TestDataModule`), because
 *   SQLCipher is a native library and Robolectric cannot load a `.so`. The database is covered
 *   directly by `MigrationTest`, `BeneficiaryDaoTest` and `EncryptedDatabaseTest`, where a fake
 *   could not mislead.
 *
 * So what this *does* prove is the wiring: the Hilt graph assembles, the nav shell routes
 * between destinations, the MVI loop turns typed input into a saved record, the effect channel
 * delivers, and the history list re-reads it. That is the thing no unit test can check, because
 * every unit test in this project stops at a module boundary.
 *
 * ## Why `MainActivity` rather than a `HiltTestActivity`
 *
 * The usual pattern declares a bare `@AndroidEntryPoint` activity in a debug manifest so the
 * test can host a composable. That is right for testing *a screen*. This is testing the app, so
 * it launches the real entry point and gets the real `AstraCareApp` shell, the real
 * `rememberSaveable` navigation and the real back handling for free.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [ROBOLECTRIC_SDK])
class CaptureToHistoryTest {

    // order matters: Hilt must inject before the Activity is created, or the graph is not
    // ready when MainActivity's onCreate resolves its ViewModels.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun `a captured record is saved and appears in the history list`() {
        // Starts on the history list, empty.
        compose.onNodeWithText(EMPTY_TITLE).assertIsDisplayed()

        compose.onNodeWithText(ADD_RECORD).performClick()

        compose.onNodeWithText(FIELD_NAME).performTextInput(NAME)
        compose.onNodeWithText(FIELD_AGE).performTextInput("3")
        compose.onNodeWithText(FIELD_VILLAGE).performTextInput(VILLAGE)
        compose.onNodeWithText(FIELD_WEIGHT).performTextInput("12.4")
        compose.onNodeWithText(FIELD_HEIGHT).performTextInput("91.0")

        compose.onNodeWithText(SAVE).performScrollTo().performClick()

        // Back on the list without anything navigating explicitly: the save emits an effect,
        // the shell reacts. That round trip is the part with no unit-test equivalent.
        compose.onNodeWithText(LIST_TITLE).assertIsDisplayed()
        compose.onNodeWithText(NAME).assertIsDisplayed()
    }

    @Test
    fun `an invalid record is refused and the typed input survives`() {
        compose.onNodeWithText(ADD_RECORD).performClick()

        compose.onNodeWithText(FIELD_NAME).performTextInput(NAME)
        compose.onNodeWithText(FIELD_AGE).performTextInput("400")
        compose.onNodeWithText(FIELD_VILLAGE).performTextInput(VILLAGE)
        compose.onNodeWithText(FIELD_WEIGHT).performTextInput("12.4")
        compose.onNodeWithText(FIELD_HEIGHT).performTextInput("91.0")

        compose.onNodeWithText(SAVE).performScrollTo().performClick()

        // Still on the capture screen, and — the part that matters to a health worker standing
        // in a village — every character they typed is still there. DECISION_LOG 5.7.
        compose.onNodeWithText(CAPTURE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(NAME).assertIsDisplayed()
    }

    @Test
    fun `a supervisor is not offered the capture screen`() {
        // The role gate, end to end rather than as a permission-matrix unit test. Switching
        // role is the stand-in for signing in as someone else (DECISION_LOG 11.6), and the
        // capture affordance must disappear when it happens.
        compose.onNodeWithText(ADD_RECORD).assertIsDisplayed()

        compose.onNodeWithText(SWITCH_ROLE).performClick()

        compose.onNodeWithText(AUDIT).assertIsDisplayed()
        compose.onNodeWithText(ADD_RECORD).assertDoesNotExist()
    }

    private companion object {
        const val NAME = "Asha Devi"
        const val VILLAGE = "Kotri"

        // String literals rather than `getString`, deliberately. A test that resolves the same
        // resource the screen does passes when both are wrong — most obviously when a resource
        // is renamed and both sides follow. These are what a person reads on the screen.
        const val LIST_TITLE = "Records"
        const val EMPTY_TITLE = "No records yet"
        const val CAPTURE_TITLE = "New record"
        const val ADD_RECORD = "Add record"
        const val SAVE = "Save record"
        const val SWITCH_ROLE = "Switch"
        const val AUDIT = "Audit"
        const val FIELD_NAME = "Name"
        const val FIELD_AGE = "Age (years)"
        const val FIELD_VILLAGE = "Village"
        const val FIELD_WEIGHT = "Weight (kg)"
        const val FIELD_HEIGHT = "Height (cm)"
    }
}

/**
 * Robolectric needs a concrete SDK level and does not read `targetSdk`.
 *
 * Pinned below `compileSdk` because Robolectric ships a prebuilt Android runtime per API level
 * and the newest ones lag the SDK by months. A version it has no runtime for fails with a
 * download error rather than a test failure, which is a confusing first impression for whoever
 * runs this next.
 */
private const val ROBOLECTRIC_SDK = 34
