package com.finora.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.finora.app.FinoraApp
import com.finora.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real app (real database, real navigation, real screens) on the JVM and walks
 * every primary section, so a screen that crashes on open fails CI rather than a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Robolectric's main looper is paused: delays on the main dispatcher (the search debounce)
     * only elapse when its clock is advanced, and Room answers from real background threads.
     * So advance the looper clock and give real time to the database between checks.
     */
    private fun waitFor(text: String) {
        repeat(100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            compose.waitForIdle()
            if (exists(text)) return
            Thread.sleep(20)
        }
        throw AssertionError("“$text” never appeared")
    }

    @Test fun everySectionOpensWithDataAndWithout() {
        waitFor("Your money")

        // Data arrives through the real SMS pipeline.
        val c = (ApplicationProvider.getApplicationContext<FinoraApp>()).container
        runBlocking {
            c.transactions.importSms("AX-HDFCBK", "Rs.450.00 debited from A/c XX1234 to VPA swiggy@icici (UPI Ref No 407312345678). Avl Bal Rs 12,345.67", System.currentTimeMillis())
            c.transactions.importSms("AX-HDFCBK", "Rs 500.00 sent to RAHUL SHARMA via UPI from A/c XX1234. UPI Ref 401299999999", System.currentTimeMillis())
        }

        compose.onNodeWithText("Activity").performClick()
        waitFor("Merchant, person, note")
        waitFor("swiggy")

        compose.onNodeWithText("Review 2").performClick()
        waitFor("New merchant")
        // The payment to a person is asked about separately, as a purpose question.
        waitFor("What was this for?")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Split").performClick()
        waitFor("Bills & splitting")
        compose.onAllNodesWithText("Split a bill")[0].performClick()
        waitFor("Scan a receipt")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Invest").performClick()
        waitFor("No investments yet")
        compose.onNodeWithText("Update prices").performClick()
        waitFor("Prices are as of")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("More").performClick()
        waitFor("Loans & lending")
        compose.onNodeWithText("Loans & lending").performClick()
        waitFor("Others owe you")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Analytics").performClick()
        waitFor("Cash flow, 12 months")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Documents").performClick()
        waitFor("No documents")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Settings").performClick()
        waitFor("Automatic SMS capture")

        compose.onNodeWithText("Home").performClick()
        waitFor("Your money")
        assertTrue(exists("Spent"))
    }
}
