package com.mirage.spike

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Executes the current Activity on an API 34 Google APIs emulator.
 * Missing composition actions are expected acceptance failures, not skipped tests.
 */
class WorkflowUiAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)
    private fun snap(id: String, name: String, point: LatLng) = SavedScenario(
        id, name, "SNAP", 1L, false, null, "", point, name,
        TravelMode.DRIVE, emptyMap(), Realism.REALISTIC, null, emptyList())

    @Before fun launch() {
        context.stopService(Intent(context, MockLocationService::class.java))
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.paused = false; PlaybackSource.signal = Signal.GOOD
        val home = snap("fixture-home", "Fixture Home", LatLng(33.50, -112.0))
        val office = snap("fixture-office", "Fixture Office", LatLng(33.60, -112.1))
        val route = home.copy(id = "fixture-route", name = "Fixture commute", kind = "ROUTE",
            start = home.dest, startName = home.name, dest = office.dest, destName = office.name)
        PrefsScenarioStore(context).save(listOf(home, office, route))
        device.executeShellCommand("pm grant com.mirage.app android.permission.ACCESS_FINE_LOCATION")
        device.executeShellCommand("pm grant com.mirage.app android.permission.ACCESS_COARSE_LOCATION")
        device.executeShellCommand("pm grant com.mirage.app android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set com.mirage.app android:mock_location allow")
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Saved plans").fetchSemanticsNodes().isNotEmpty()
        }
    }
    @After fun close() {
        context.stopService(Intent(context, MockLocationService::class.java))
        if (::activity.isInitialized) activity.close()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "acceptance")
        dir.mkdirs()
        device.takeScreenshot(File(dir, name + ".png"))
    }
    private fun saved() {
        compose.onNodeWithContentDescription("Saved plans").performClick()
        compose.onNodeWithText("Fixture Home").assertIsDisplayed()
    }
    private fun loadHome() {
        saved()
        compose.onNodeWithText("Fixture Home").performClick()
        compose.onNodeWithText("Snap to “Fixture Home”").performClick()
        compose.waitUntil(20_000) { MockState.status.value.running }
    }

    @Test fun uc08_savedCollectionIsVisible() {
        saved()
        compose.onNodeWithText("Fixture Office").assertIsDisplayed()
        compose.onNodeWithText("Fixture commute").assertIsDisplayed()
        screenshot("uc08-saved-list")
    }

    @Test fun uc10_savedSnapProvidesBothEndpointActions() {
        saved(); screenshot("uc10-missing-endpoint-actions")
        compose.onNodeWithText("Use as start", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Use as destination", ignoreCase = true).assertIsDisplayed()
    }

    @Test fun uc21_savedRouteProvidesItineraryComposition() {
        saved(); screenshot("uc21-missing-composition")
        compose.onNodeWithText("Add to itinerary", ignoreCase = true).assertIsDisplayed()
    }

    @Test fun uc11_snapStartsAndProviderAcceptanceBecomesFresh() {
        loadHome()
        compose.waitUntil(20_000) {
            val s = MockState.status.value
            val now = System.currentTimeMillis()
            s.running && s.lastFixMillis > 0 && s.lastFusedFixMillis > 0 &&
                now - s.lastFixMillis < 5000 && now - s.lastFusedFixMillis < 5000
        }
        assertEquals(33.50, MockState.status.value.lat, 0.001)
        screenshot("uc11-snap-output")
    }

    @Test fun uc39_stopEndsSnapOutput() {
        loadHome()
        compose.onNodeWithText("Stop", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { !MockState.status.value.running && !MockState.status.value.starting }
        compose.onNodeWithText("SIMULATION OFF", substring = true).assertExists()
        screenshot("uc39-off")
        // This checks output stop, not a fresh physical GPS fix or another app's display.
    }
}
