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
        context.getSharedPreferences("mirage_recovery",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.paused = false
        PlaybackSource.signal = Signal.GOOD; PlaybackSource.timeScale = 1.0
        val h = snap("home", "Fixture Home", home)
        val o = snap("office", "Fixture Office", office)
        val route = snap("route", "Fixture Route", cafe).copy(kind = "ROUTE", start = office, startName = "Fixture Office")
        val itinerary = h.copy(id = "itinerary", name = "Fixture Day", aliases=listOf("daytrip"), kind = "ITINERARY", start = home, startName = "Fixture Home",
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
        compose.onNodeWithText("Start at “Fixture Home”").performClick()
        compose.waitUntil(20_000) { MockState.status.value.running && LiveSession.plan != null }
    }
    @Test fun savedLibraryAddsDestinationToActiveHoldingSession() {
        val before = LiveSession.plan!!
        compose.onNodeWithText("Saved", useUnmergedTree=false).performClick()
        compose.onNodeWithContentDescription("Add Fixture Office to current trip").performScrollTo().performClick()
        compose.onNodeWithTag("continuationEditor").assertIsDisplayed()
        compose.onNodeWithText("Fixture Office").assertExists()
        assertSame(before, LiveSession.plan)
        compose.onNodeWithText("Cancel").performClick()
        assertSame(before, LiveSession.plan)
        assertTrue(MockState.status.value.running)
    }
    @After fun close() {
        context.stopService(Intent(context, MockLocationService::class.java))
        if (::activity.isInitialized) activity.close()
        context.getSharedPreferences("mirage_recovery",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.paused = false
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); device.waitForIdle()
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/$name.png")
    }
    private fun open(source: String) {
        compose.onNodeWithText("Add stop").performClick()
        compose.onNodeWithText(source).performClick()
    }
    private fun ready() {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("confirmContinuation") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun confirm() { ready(); compose.onNodeWithTag("confirmContinuation").performScrollTo().performClick() }
    private fun assertPausedOutput() {
        compose.waitUntil(10_000) {
            val status = MockState.status.value
            status.paused && locationOutputIssue(status) == null
        }
        // Wait for the sheet to close and Compose to render the service status.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("PAUSED · simulated location held").fetchSemanticsNodes().isNotEmpty()
        }
        // Cover provider callbacks both before and after the header timer ticks.
        repeat(12) {
            compose.onNodeWithText("PAUSED · simulated location held").assertIsDisplayed()
            android.os.SystemClock.sleep(100)
        }
    }
    private fun chooseSnap() {
        open("Saved places")
        compose.onNodeWithTag("continueSaved-office").performScrollTo().performClick()
        compose.onNodeWithText("fly").performScrollTo().performClick()
        ready()
    }
    @Test fun movingRouteShowsEverySavedSourceAndAcceptsRouteAndLegacyPlace() {
        // Start travelling, then enter through the same Add stop control used on a live route.
        chooseSnap()
        confirm()
        compose.waitUntil(15_000) { LiveSession.state.value.activity == ActivityKind.TRAVELING }
        val origin = LiveSession.plan!!.origin
        val currentId = LiveSession.state.value.stops.first().id
        compose.onNodeWithText("Add stop").performClick()
        DestinationSource.entries.forEach { source ->
            compose.onNodeWithTag("destinationSource-${source.name}").assertIsDisplayed()
            compose.onNodeWithText(source.label).assertIsDisplayed()
        }
        screenshot("live-route-visible-saved-sources")
        compose.onNodeWithText("Saved routes").performClick()
        compose.onNodeWithTag("continueSaved-route").performScrollTo().performClick()
        confirm()
        compose.waitUntil(10_000) { LiveSession.state.value.stops.size == 2 }
        assertEquals(listOf(office, cafe), LiveSession.state.value.stops.map { it.stop.point })
        assertEquals(currentId, LiveSession.state.value.stops.first().id)
        assertEquals(origin, LiveSession.plan!!.origin)
        assertFalse(PlaybackSource.paused)
        open("Saved places")
        compose.onNodeWithTag("continueSaved-home").performScrollTo().performClick()
        compose.onNodeWithText("At end of trip").performScrollTo().performClick()
        confirm()
        compose.waitUntil(10_000) { LiveSession.state.value.stops.size == 3 }
        assertEquals(home, LiveSession.state.value.stops.last().stop.point)
        assertEquals("SNAP", PrefsScenarioStore(context).load().single { it.id == "home" }.kind)
        assertFalse(PlaybackSource.paused)
        screenshot("live-route-saved-route-and-place-added")
    }

    @Test fun savedSourcesRemainVisibleWithLargeText() {
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            activity.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Add stop").fetchSemanticsNodes().isNotEmpty() }
            activity.onActivity { assertTrue(it.resources.configuration.fontScale >= 1.29f) }
            compose.onNodeWithText("Add stop").performClick()
            DestinationSource.entries.forEach { source ->
                compose.onNodeWithTag("destinationSource-${source.name}").assertIsDisplayed()
            }
            screenshot("large-text-visible-saved-sources")
            compose.onNodeWithText("Saved itineraries").performClick()
            compose.onNodeWithTag("continueSaved-itinerary").performScrollTo().assertIsDisplayed()
        } finally { device.executeShellCommand("settings put system font_scale 1.0") }
    }

    @Test fun contextualVoiceUsesAliasThenEditsConfirmedUpcomingStop() {
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithContentDescription("Talk").performClick()
        fun say(words:String) {
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(words)
            compose.onNodeWithText("Send").performScrollTo().performClick()
            compose.waitUntil(15000) { !Conversation.state.value.busy && SmartVoice.needsReply() }
        }
        say("Add daytrip next")
        compose.onNodeWithText("Confirm").performScrollTo().performClick()
        compose.waitUntil(5000) {LiveSession.state.value.stops.size==3}
        assertTrue(PlaybackSource.paused)
        say("Make its stay forty five minutes")
        assertEquals(23,LiveSession.state.value.stops.last().stop.dwellMinutes)
        compose.onNodeWithText("Confirm").performScrollTo().performClick()
        compose.waitUntil(5000) {LiveSession.state.value.stops.last().stop.dwellMinutes==45}
        assertEquals("Fixture Cafe",LiveSession.state.value.stops.last().stop.name)
        assertEquals(17,LiveSession.state.value.stops[1].stop.dwellMinutes)
        assertTrue(PlaybackSource.paused)
        screenshot("contextual-voice-review")
    }

    @Test fun heldSimulationContinuesToSavedSnapFromCurrentPointWithClearMap() {
        chooseSnap()
        compose.onNode(hasText("Fixture Office, Scottsdale, Arizona") and
            hasAnyAncestor(hasTestTag("continuationEditor"))).assertExists()
        compose.onNodeWithText("Review on map").performScrollTo().performClick()
        compose.waitForIdle()
        val top = compose.onNodeWithTag("liveStatus").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("liveControls").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        screenshot("continuation-snap-map-preview")
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
        assertPausedOutput()
        screenshot("continuation-paused-addition")
    }
    @Test fun savedRouteConnectsItsStartWithoutJumpingOrReplacingTheTrip() {
        PlaybackSource.paused = true
        open("Saved routes")
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
        open("Saved itineraries")
        compose.onNodeWithTag("continueSaved-itinerary").performScrollTo().performClick()
        compose.onNodeWithText("After current stop").performScrollTo().performClick()
        confirm()
        compose.waitUntil(10_000) { LiveSession.state.value.stops.size == 3 }
        assertEquals(listOf(0, 17, 23), LiveSession.state.value.stops.map { it.stop.dwellMinutes })
        val after=PrefsScenarioStore(context).load().first { it.id == "itinerary" }
        assertTrue("Using a saved item records recent use", after.lastUsedAt > original.lastUsedAt)
        assertEquals("Trip content and identity remain unchanged", original, after.copy(lastUsedAt=original.lastUsedAt))
        assertTrue(PlaybackSource.paused)
        assertPausedOutput()
        screenshot("continuation-itinerary-added")
    }
    @Test fun cancelAndMapBrowsingLeaveExistingSessionUntouched() {
        val before = LiveSession.state.value.stops
        chooseSnap()
        compose.onNodeWithText("Review on map").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Add stop").assertIsDisplayed()
        assertEquals(before, LiveSession.state.value.stops)
        assertTrue(MockState.status.value.running)
        screenshot("continuation-cancelled")
    }
    @Test fun addSaveUpdateAndReloadLiveItineraryWithoutStopping() {
        compose.onNodeWithText("Pause").performClick()
        compose.waitUntil(10_000) { PlaybackSource.paused }
        chooseSnap()
        compose.onNodeWithText("At end of trip").performScrollTo().performClick()
        confirm()
        compose.onNodeWithText("My itinerary").performClick()
        compose.onNodeWithTag("itinerarySaveStatus").assertTextEquals("Unsaved changes")
        compose.onNodeWithTag("saveTripAsNew").performClick()
        compose.onNodeWithTag("itineraryName").performTextInput("My Tuesday")
        compose.onNodeWithTag("confirmSaveItinerary").performClick()
        compose.onNodeWithTag("itinerarySaveStatus").assertTextEquals("All changes saved")
        val saved=PrefsScenarioStore(context).load().single { it.name=="My Tuesday" }
        assertEquals(2,saved.stops.size)
        compose.onNodeWithText("Back to map").performClick()
        open("Saved routes")
        compose.onNodeWithTag("continueSaved-route").performScrollTo().performClick()
        compose.onNodeWithText("At end of trip").performScrollTo().performClick()
        confirm()
        compose.onNodeWithText("My itinerary").performClick()
        compose.onNodeWithTag("itinerarySaveStatus").assertTextEquals("Unsaved changes")
        compose.onNodeWithTag("saveTripChanges").performClick()
        compose.onNodeWithTag("itinerarySaveStatus").assertTextEquals("All changes saved")
        val updated=PrefsScenarioStore(context).load().single { it.name=="My Tuesday" }
        assertEquals(saved.id,updated.id)
        assertTrue(updated.stops.size>saved.stops.size)
        assertTrue(MockState.status.value.running)
        assertTrue(PlaybackSource.paused)
        screenshot("itinerary-saved-during-simulation")
    }

}
