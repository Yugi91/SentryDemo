package io.pula.sentrydemo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test that launches the real [MainActivity]
 * (`@AndroidEntryPoint`) backed by `HiltTestApplication`, asserting that the
 * five demo cards render and that tapping the delay button progresses the
 * activity log to the "finished" state.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DemoScreenInstrumentedTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun all_five_demo_buttons_render_with_expected_titles() {
        compose.onNodeWithText("1. Delay action (2 s)").assertIsDisplayed()
        compose.onNodeWithText("2. Crash action").assertIsDisplayed()
        compose.onNodeWithText("3. ANR action (block 8 s)").assertIsDisplayed()
        compose.onNodeWithText("4. Photo workflow (ok path)").assertIsDisplayed()
        compose.onNodeWithText("4b. Photo workflow (force fail)").assertIsDisplayed()

        // Five "Run" buttons, one per card.
        compose.onAllNodesWithText("Run").assertCountEquals(5)
    }

    @Test
    fun tapping_delay_button_eventually_logs_finished() {
        compose.onNodeWithText("1. Delay action (2 s)").assertIsDisplayed()
        compose.onAllNodesWithText("Run").onFirst().assertIsEnabled().performClick()

        // The delay use case awaits 2 s of real time; allow 5 s for jitter.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("delay_action: finished")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
