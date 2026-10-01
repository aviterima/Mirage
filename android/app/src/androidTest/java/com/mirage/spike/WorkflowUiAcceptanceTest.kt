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
        context.getSharedPreferences("mirage_recovery",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.paused = false; PlaybackSource.signal = Signal.GOOD
        val home = snap("fixture-home", "Fixture Home", LatLng(33.50, -112.0))
        val office = snap("fixture-office", "Fixture Office", LatLng(33.60, -112.1))
        val route = home.copy(id = "fixture-route", name = "Fixture commute", kind = "ROUTE",
            start = home.dest, startName = home.name, dest = office.dest, destName = office.name)
        val itinerary = route.copy(id = "fixture-day", name = "Fixture day", kind = "ITINERARY",
            stops = listOf(
                SavedStop("First", 33.6, -112.1, 15, TravelMode.WALK, 3f),
                SavedStop("Second", 33.7, -112.2, 30, TravelMode.DRIVE, 45f),
                SavedStop("Third", 33.8, -112.3, 45, TravelMode.BIKE, 12f)))
        PrefsScenarioStore(context).save(listOf(home, office, route, itinerary))
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
        context.getSharedPreferences("mirage_recovery",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        device.waitForIdle()
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
        compose.onNodeWithText("Start at “Fixture Home”").performClick()
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

    @Test fun planningActionsRemainVisibleAndDraftRestoresWithoutStarting() {
        saved()
        compose.onNodeWithContentDescription("Load Fixture day").performScrollTo().performClick()
        compose.onNodeWithText("Start itinerary").assertIsDisplayed()
        compose.onNodeWithText("Add a stop · search, tap the map, or ⌖").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5000) { context.getSharedPreferences("mirage_recovery",android.content.Context.MODE_PRIVATE).contains("draft") }
        screenshot("planner-unobstructed")
        activity.close()
        activity=ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Restore your previous trip?").assertIsDisplayed()
        assertFalse(MockState.status.value.running)
        compose.onNodeWithText("Restore for editing").performClick()
        compose.onNodeWithContentDescription("Reorder stop 1: First").assertIsDisplayed()
        assertFalse(MockState.status.value.running)
    }

    @Test fun longItineraryAndLargerTextKeepAddFieldAbovePlaybackControls() {
        val store=PrefsScenarioStore(context)
        val base=store.load().first{it.id=="fixture-day"}
        store.save(store.load()+base.copy(id="long-day",name="Long day",createdAt=2,
            stops=(1..24).map{SavedStop("Stop $it",33.5+it*0.001,-112.0,15,TravelMode.WALK,3f)}))
        try {
            device.executeShellCommand("settings put system font_scale 1.3")
            activity.recreate()
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("Saved plans").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Saved plans").performClick()
            compose.onNodeWithContentDescription("Load Long day").performScrollTo().performClick()
            compose.onNodeWithText("Suggest order").performScrollTo().performClick()
            compose.onNodeWithText("24. Stop 24").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Keep current order").performClick()
            val add=compose.onNodeWithText("Add a stop · search, tap the map, or ⌖")
            add.performScrollTo().assertIsDisplayed()
            val action=compose.onNodeWithText("Start itinerary")
            action.assertIsDisplayed()
            assertTrue(add.fetchSemanticsNode().boundsInRoot.bottom <= action.fetchSemanticsNode().boundsInRoot.top)
            add.performClick()
            compose.onNode(hasSetTextAction() and hasText("Add a stop · search, tap the map, or ⌖")).performTextInput("Library")
            val input=compose.onNode(hasSetTextAction() and hasText("Library"))
            // Text injection does not guarantee that the platform IME has opened.
            // Tap the actual editor and capture it before checking the real insets and bounds.
            input.performClick()
            screenshot("large-text-keyboard-before-check")
            compose.waitUntil(15000) {
                var imeVisible=false
                activity.onActivity { imeVisible=it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
                val field=input.fetchSemanticsNode().boundsInRoot
                val footer=compose.onNodeWithTag("plannerFooter").fetchSemanticsNode().boundsInRoot
                imeVisible && field.height>0 && field.top>=0 && field.bottom<=footer.top
            }
            input.assertIsDisplayed()
            assertTrue("Keyboard must not let the footer cover the input",input.fetchSemanticsNode().boundsInRoot.bottom<=compose.onNodeWithTag("plannerFooter").fetchSemanticsNode().boundsInRoot.top)
            screenshot("large-text-keyboard-planner")
            device.pressBack()
        } finally {device.executeShellCommand("settings put system font_scale 1.0")}
    }

    @Test fun savedBrowserFiltersFavoritesAndOffersRecoverableDeletion() {
        saved()
        compose.onNodeWithContentDescription("Options for Fixture Home").performScrollTo().performClick()
        compose.onNodeWithText("Add favorite").performClick()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("★ Fixture Home").assertIsDisplayed()
        compose.onNodeWithText("Fixture Office").assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for Fixture Home").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Undo library change").performClick()
        compose.onNodeWithText("★ Fixture Home").assertIsDisplayed()
    }

    @Test fun swapButtonReversesTheVisibleRouteFields() {
        saved()
        compose.onNodeWithContentDescription("Load Fixture commute").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Swap start and destination").performClick()
        val home = compose.onNode(hasSetTextAction() and hasText("Fixture Home")).fetchSemanticsNode().boundsInRoot
        val office = compose.onNode(hasSetTextAction() and hasText("Fixture Office")).fetchSemanticsNode().boundsInRoot
        assertTrue("Office should now be above Home", office.top < home.top)
        screenshot("route-swapped")
        compose.onNodeWithContentDescription("Swap start and destination").performClick()
        assertTrue(compose.onNode(hasSetTextAction() and hasText("Fixture Home")).fetchSemanticsNode().boundsInRoot.top <
            compose.onNode(hasSetTextAction() and hasText("Fixture Office")).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun dragStopThenSaveAndReloadPreservesNewOrder() {
        saved()
        compose.onNodeWithContentDescription("Load Fixture day").performScrollTo().performClick()
        val first = compose.onNodeWithContentDescription("Reorder stop 1: First")
        val second = compose.onNodeWithContentDescription("Reorder stop 2: Second")
        val dy = second.fetchSemanticsNode().boundsInRoot.center.y - first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(0f, dy), 600) }
        compose.onNodeWithContentDescription("Reorder stop 1: Second").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reorder stop 2: First").assertIsDisplayed()
        screenshot("itinerary-dragged")
        // The same handle offers a tap menu for people who prefer buttons to dragging.
        compose.onNodeWithContentDescription("Reorder stop 2: First").performClick()
        compose.onNodeWithText("Move up").assertIsDisplayed()
        compose.onNodeWithText("Move down").performClick()
        compose.onNodeWithContentDescription("Reorder stop 3: First").performScrollTo().assertIsDisplayed()
        saved()
        compose.onNodeWithText("Name, e.g. Lunch run").performTextInput("Reordered fixture")
        compose.onNodeWithText("Save", useUnmergedTree = false).performClick()
        compose.waitUntil(5_000) { PrefsScenarioStore(context).load().any { it.name == "Reordered fixture" } }
        val stored = PrefsScenarioStore(context).load().first { it.name == "Reordered fixture" }
        assertEquals(listOf("Second", "Third", "First"), stored.stops.map { it.name })
        assertEquals(15, stored.stops.last().dwellMinutes)
        assertEquals(TravelMode.WALK, stored.stops.last().mode)
        compose.onNodeWithContentDescription("Load Reordered fixture").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Reorder stop 1: Second").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Reorder stop 3: First").performScrollTo().assertIsDisplayed()
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

