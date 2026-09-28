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

/** Actual Activity journeys; flight fixtures avoid depending on live routing services. */
class ContinuationUiAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)
    private val home = LatLng(33.50, -112.0)
    private val office = LatLng(33.60, -112.1)
    private val cafe = LatLng(33.70, -112.2)
    private fun snap(id: String, name: String, at: LatLng) = SavedScenario(id, name, "SNAP", 1, false,
        null, "", at, name, TravelMode.FLY, emptyMap(), Realism.REALISTIC, null, emptyList(), "$name, Scottsdale, Arizona")
    @Before fun launch() {
        context.stopService(Intent(context, MockLocationService::class.java))
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.paused = false
        PlaybackSource.signal = Signal.GOOD; PlaybackSource.timeScale = 1.0
        val h = snap("home", "Fixture Home", home)
        val o = snap("office", "Fixture Office", office)
        val route = snap("route", "Fixture Route", cafe).copy(kind = "ROUTE", start = office, startName = "Fixture Office")
        val itinerary = h.copy(id = "itinerary", name = "Fixture Day", kind = "ITINERARY", start = home, startName = "Fixture Home",
            stops = listOf(SavedStop("Fixture Office", office.lat, office.lng, 17, TravelMode.FLY, 550f),
                SavedStop("Fixture Cafe", cafe.lat, cafe.lng, 23, TravelMode.FLY, 550f)))
        PrefsScenarioStore(context).save(listOf(h, o, route, itinerary))
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS").forEach {
            device.executeShellCommand("pm grant com.mirage.app android.permission.$it")
        }
        device.executeShellCommand("appops set com.mirage.app android:mock_location allow")
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Saved plans").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Saved plans").performClick()
        compose.onNodeWithContentDescription("Load Fixture Home").performClick()
        compose.onNodeWithText("Snap to “Fixture Home”").performClick()
        compose.waitUntil(20_000) { MockState.status.value.running && LiveSession.plan != null }
    }
    @After fun close() {
        context.stopService(Intent(context, MockLocationService::class.java))
        if (::activity.isInitialized) activity.close()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.paused = false
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); device.waitForIdle()
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/$name.png")
    }
    private fun open(source: String) {
        compose.onNodeWithText("Add destination").performClick()
        compose.onNodeWithText(source).performClick()
    }
    private fun ready() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("confirmContinuation") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun confirm() { ready(); compose.onNodeWithTag("confirmContinuation").performScrollTo().performClick() }
    private fun chooseSnap() {
        open("Snaps")
        compose.onNodeWithTag("continueSaved-office").performScrollTo().performClick()
        compose.onNodeWithText("fly").performScrollTo().performClick()
        ready()
    }
    @Test fun heldSimulationContinuesToSavedSnapFromCurrentPointWithClearMap() {
        chooseSnap()
        compose.onNodeWithText("Fixture Office, Scottsdale, Arizona").assertExists()
        compose.onNodeWithText("Review on map").performScrollTo().performClick()
        compose.waitForIdle()
        val top = compose.onNodeWithTag("liveStatus").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("liveControls").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Continuation preview must leave 75% clear map height", (bottom.top - top.bottom) / root.height >= 0.75f)
        screenshot("continuation-snap-map-preview")
        compose.onNodeWithText("Go now").performClick()
        compose.waitUntil(15_000) { LiveSession.state.value.stops.lastOrNull()?.stop?.point == office }
        assertTrue(Geo.haversine(home, LiveSession.plan!!.origin) < 20.0)
        screenshot("continuation-snap-running")
    }
    @Test fun addingSnapAfterCurrentStopDoesNotResumePausedSimulation() {
        compose.onNodeWithText("Pause").performClick()
        compose.waitUntil(10_000) { PlaybackSource.paused }
        chooseSnap()
        compose.onNodeWithText("After current stop").performScrollTo().performClick()
        confirm()
        compose.waitUntil(10_000) { LiveSession.state.value.stops.size == 2 }
        assertTrue(PlaybackSource.paused)
        assertEquals(office, LiveSession.state.value.stops[1].stop.point)
        screenshot("continuation-paused-addition")
    }
    @Test fun savedRouteConnectsItsStartWithoutJumpingOrReplacingTheTrip() {
        PlaybackSource.paused = true
        open("Routes")
        compose.onNodeWithTag("continueSaved-route").performScrollTo().performClick()
        compose.onNodeWithText("At end of trip").performScrollTo().performClick()
        confirm()
        compose.waitUntil(10_000) { LiveSession.state.value.stops.size == 3 }
        assertEquals(listOf(home, office, cafe), LiveSession.state.value.stops.map { it.stop.point })
        assertTrue(PlaybackSource.paused)
        assertTrue(Geo.haversine(home, LiveSession.plan!!.origin) < 20)
        screenshot("continuation-connected-route")
    }
    @Test fun savedItineraryAddsAllStopsAndPreservesItsStaysAndOriginal() {
        PlaybackSource.paused = true
        val original = PrefsScenarioStore(context).load().first { it.id == "itinerary" }
        open("Itineraries")
        compose.onNodeWithTag("continueSaved-itinerary").performScrollTo().performClick()
        compose.onNodeWithText("After current stop").performScrollTo().performClick()
        confirm()
        compose.waitUntil(10_000) { LiveSession.state.value.stops.size == 3 }
        assertEquals(listOf(0, 17, 23), LiveSession.state.value.stops.map { it.stop.dwellMinutes })
        assertEquals(original, PrefsScenarioStore(context).load().first { it.id == "itinerary" })
        assertTrue(PlaybackSource.paused)
        screenshot("continuation-itinerary-added")
    }
    @Test fun cancelAndMapBrowsingLeaveExistingSessionUntouched() {
        val before = LiveSession.state.value.stops
        chooseSnap()
        compose.onNodeWithText("Review on map").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Add destination").assertIsDisplayed()
        assertEquals(before, LiveSession.state.value.stops)
        assertTrue(MockState.status.value.running)
        screenshot("continuation-cancelled")
    }
}
