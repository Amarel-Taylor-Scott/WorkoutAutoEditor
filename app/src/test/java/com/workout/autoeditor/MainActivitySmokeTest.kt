package com.workout.autoeditor

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boots MainActivity in Robolectric. Catches: manifest issues, missing
 * R.string entries, theme resolution failures, and any uncaught throw
 * during onCreate / first composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivitySmokeTest {

    @Test
    fun mainActivityLaunchesWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assert(!activity.isFinishing) { "Activity finished immediately - likely a fatal init error" }
            }
        }
    }
}
