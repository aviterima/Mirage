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
        device.executeShellCommand("am force-stop com.google.android.apps.nexuslauncher")
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Saved plans").fetchSemanticsNodes().isNotEmpty()
        }
    }
    @After fun close() {
        println("ACCEPTANCE final status: " + MockState.status.value)
        screenshot("final-" + System.currentTimeMillis())
        context.stopService(Intent(context, MockLocationService::class.java))
        if (::activity.isInitialized) activity.close()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
    }
    private fun screenshot(name: String) {
        assertEquals("No system dialog may cover the app", "com.mirage.app", device.currentPackageName)
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/" + name + ".png")
    }
    private fun saved() {
        compose.onNodeWithContentDescription("Saved plans").performClick()
        compose.onNode(hasText("Fixture Home") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }
    private fun loadHome() {
        saved()
        compose.onNodeWithContentDescription("Load Fixture Home").performClick()
        compose.onNodeWithText("Snap to “Fixture Home”").performClick()
        compose.waitUntil(20_000) { MockState.status.value.running }
    }

    @Test fun uc08_savedCollectionIsVisible() {
        saved()
        compose.onNode(hasText("Fixture Office") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("Fixture commute") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        screenshot("uc08-saved-list")
    }

    @Test fun uc10_savedSnapProvidesBothEndpointActions() {
        saved(); compose.onNode(hasText("Fixture Home") and hasAnyAncestor(isDialog())).performClick(); screenshot("uc10-endpoint-actions")
        compose.onNodeWithText("Use as start", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Use as destination", ignoreCase = true).assertIsDisplayed()
    }

    @Test fun uc21_savedRouteProvidesItineraryComposition() {
        saved(); compose.onNode(hasText("Fixture commute") and hasAnyAncestor(isDialog())).performClick(); screenshot("uc21-composition")
        compose.onNodeWithText("Add to itinerary", ignoreCase = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun savedSnapsBuildBothRouteEndpoints() {
        saved()
        compose.onNode(hasText("Fixture Home") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Use as start").performScrollTo().performClick()
        saved()
        compose.onNode(hasText("Fixture Office") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Use as destination").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Fixture Home")).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Fixture Office")).assertIsDisplayed()
        screenshot("saved-snaps-route-endpoints")
    }

    @Test fun savedRouteCompositionPromptsForDisconnectedLeg() {
        saved()
        compose.onNode(hasText("Fixture commute") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Add to itinerary").performScrollTo().performClick()
        saved()
        compose.onNode(hasText("Fixture commute") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Add to itinerary").performScrollTo().performClick()
        compose.onNodeWithText("Connect these routes?").assertIsDisplayed()
        screenshot("route-connector-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Saved plans").assertIsDisplayed()
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

    @Test fun uc32_liveMapHasSeventyFivePercentClearHeight() {
        loadHome()
        val top = compose.onNodeWithTag("liveStatus").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("liveControls").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val generousClearFraction = (bottom.top - top.bottom) / root.height
        println("ACCEPTANCE uc32 clear-height fraction: " + generousClearFraction)
        screenshot("uc32-live-map")
        assertTrue("Clear map height is only " + generousClearFraction,
            generousClearFraction >= 0.75f)
    }

}

