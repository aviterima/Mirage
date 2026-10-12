package com.mirage.spike

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mirage.spike.engine.*
import com.mirage.spike.store.PrefsScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*

/** Real selection, save UI and on-device persistence with a deterministic Places result.
 * Google response parsing is separately covered by the Places HTTP tests.
 */
class PlaceNamingUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val hit = PlaceHit(LatLng(33.65, -117.73), "Western Digital (Irvine)",
        "Irvine, CA, USA (fixture)", "fixture-western-digital-irvine")
    private lateinit var vm: MirageViewModel
    private var showSaved by mutableStateOf(false)
    private var loaded: SavedScenario? = null
    private var searchCalls = 0

    @Before fun setup() {
        context.stopService(Intent(context, MockLocationService::class.java))
        context.getSharedPreferences("mirage_scenarios", Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
        showSaved = false; loaded = null; searchCalls = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = MirageViewModel { _, query, _ ->
                assertEquals("Western Digital", query)
                searchCalls++
                listOf(hit)
            }.apply {
                configureApi(ApiConfig(null, "fixture", "place-naming-test"))
                attachStore(PrefsScenarioStore(context))
                choosePlanMode(PlanMode.SNAP)
            }
        }
        compose.setContent {
            MaterialTheme {
                if (showSaved) SavedPlansDialog(vm, onDismiss = { showSaved = false },
                    onLoaded = { loaded = it; showSaved = false })
            }
        }
    }

    @After fun cleanup() {
        compose.runOnIdle { if (::vm.isInitialized) vm.viewModelScope.cancel() }
        context.getSharedPreferences("mirage_scenarios", Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
    }

    private fun selectSearchResultAndOpenSave() {
        compose.runOnIdle { vm.editLocationQuery(Field.END, "Western Digital", null) }
        compose.waitUntil(5_000) { vm.suggestions.singleOrNull() == hit }
        compose.runOnIdle {
            assertEquals(1, searchCalls)
            vm.pickSuggestion(vm.suggestions.single())
            assertEquals(hit.name, vm.destName)
            assertEquals(hit.latLng, vm.dest)
            assertEquals(hit.address, vm.destAddress)
            assertEquals(hit.placeId, vm.destPlaceId)
            showSaved = true
            assertNotStarted()
        }
        compose.onNodeWithTag("savedPlanName").assertIsDisplayed().assertTextContains(hit.name)
    }

    private fun saveAndReopen(expectedName: String): SavedScenario {
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.waitUntil(5_000) { PrefsScenarioStore(context).load().any { it.name == expectedName } }
        val persisted = PrefsScenarioStore(context).load().single { it.name == expectedName }
        assertEquals("SNAP", persisted.kind)
        assertEquals(hit.name, persisted.destName)
        assertEquals(hit.latLng, persisted.dest)
        assertEquals(hit.address, persisted.destAddress)
        assertEquals(hit.placeId, persisted.destPlaceId)
        compose.onNodeWithContentDescription("Load $expectedName").performScrollTo().performClick()
        compose.onNodeWithTag("savedPlanName").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(persisted.id, loaded?.id)
            assertEquals(expectedName, vm.draftSavedName)
            assertEquals(PlanMode.SNAP, vm.planMode)
            assertEquals(hit.name, vm.destName)
            assertEquals(hit.latLng, vm.dest)
            assertEquals(hit.address, vm.destAddress)
            assertEquals(hit.placeId, vm.destPlaceId)
            assertNotStarted()
        }
        return persisted
    }

    private fun assertNotStarted() {
        assertFalse(MockState.status.value.running)
        assertNull(PlaybackSource.current)
        assertNull(LiveSession.plan)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle(); device.waitForIdle()
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/$name.png")
    }

    @Test fun lookupNameAndCityArePrefilledAndSaveWithoutTyping() {
        selectSearchResultAndOpenSave()
        screenshot("place-name-prefilled")
        saveAndReopen(hit.name)
    }

    @Test fun prefilledNameCanBeEditedAndPreviousSavedNameDoesNotReplaceNewLookup() {
        compose.runOnIdle {
            vm.setDestPoint(LatLng(34.0, -118.0), "Previous house pin")
            assertTrue(vm.saveScenario("Home"))
            vm.loadScenario(vm.savedScenarios.single())
            assertEquals("Home", vm.draftSavedName)
        }
        selectSearchResultAndOpenSave()
        compose.onNodeWithTag("savedPlanName").performClick().performTextReplacement("My office")
        device.pressBack()
        // A separate sheet state change must not overwrite the editable suggestion.
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithTag("savedPlanName").performScrollTo().assertTextContains("My office")
        screenshot("place-name-custom")
        // Make the newly saved non-favorite visible for the real Open action below.
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        saveAndReopen("My office")
        val original = PrefsScenarioStore(context).load().single { it.name == "Home" }
        assertEquals("Previous house pin", original.destName)
        assertEquals(LatLng(34.0, -118.0), original.dest)
    }
}
