package com.workout.autoeditor.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM-side smoke test using Robolectric. Catches the class of bug that froze
 * v0.1.0 / v0.1.1: invalid Compose modifiers, missing imports, mis-resolved
 * @Composable signatures, ANR-prone construction in the rendering path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SetupScreenSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupScreenRendersTitle() {
        composeRule.setContent {
            AppTheme {
                SetupScreen(onReady = {})
            }
        }
        composeRule.onNodeWithText("Workout Auto Editor").assertIsDisplayed()
    }

    @Test
    fun idleStateShowsAllThreeActions() {
        composeRule.setContent {
            AppTheme {
                SetupScreen(onReady = {})
            }
        }
        // After the model-presence check resolves false on a clean test env,
        // the IDLE state should render all three primary actions.
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Download Gemma 4 E2B (~1.9 GB)").assertIsDisplayed()
        composeRule.onNodeWithText("Configure URL / token").assertIsDisplayed()
        composeRule.onNodeWithText("Skip - use rule-based mode").assertIsDisplayed()
    }

    @Test
    fun skipBypassesDownloadAndCallsOnReady() {
        var readyCalled = false
        composeRule.setContent {
            AppTheme {
                SetupScreen(onReady = { readyCalled = true })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Skip - use rule-based mode").performClick()
        composeRule.waitForIdle()
        assert(readyCalled) { "onReady was not invoked after Skip" }
    }
}
