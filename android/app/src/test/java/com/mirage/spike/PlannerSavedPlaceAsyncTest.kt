package com.mirage.spike

import com.mirage.spike.engine.ApiConfig
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.TravelMode
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Late device and provider responses must not undo a saved-place choice. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerSavedPlaceAsyncTest {
    private val home = SavedScenario(
        "home", "Home", "SNAP", 1L, false, null, "", LatLng(33.5, -112.0), "Home pin",
        TravelMode.DRIVE, emptyMap(), Realism.REALISTIC, null, emptyList(),
        destAddress = "Home fixture address", destPlaceId = "home-place-id",
    )
    private val quickReal = LatLng(33.6, -112.1)
    private val freshReal = LatLng(33.7, -112.2)

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()); MockState.reset() }
    @After fun cleanup() { MockState.reset(); Dispatchers.resetMain() }

    @Test fun remoteFailureDoesNotObscureSelectableSavedHome() = runTest {
        var calls = 0
        val vm = MirageViewModel { _, _, _ ->
            calls++
            throw IllegalStateException("Provider unavailable")
        }.apply {
            configureApi(ApiConfig(null, "fixture", "saved-place-test"))
            attachStore(InMemoryScenarioStore().apply { save(listOf(home)) })
        }
        vm.editLocationQuery(Field.END, "Home", null)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(vm.error)
        assertFalse(vm.suggestBusy)
        assertEquals(listOf("home"), vm.savedPlaceSuggestions.map { it.id })
        assertTrue(vm.pickSavedPlace(vm.savedPlaceSuggestions.single(), Field.END))
        assertEquals(home.dest, vm.dest)
        assertNull(vm.error)
    }

    @Test fun remoteFailureIsStillShownWhenNoSavedPlaceMatches() = runTest {
        val vm = MirageViewModel { _, _, _ -> throw IllegalStateException("Provider unavailable") }.apply {
            configureApi(ApiConfig(null, "fixture", "saved-place-test"))
        }
        vm.search("Unstored destination") {}
        advanceUntilIdle()
        assertTrue(vm.savedPlaceSuggestions.isEmpty())
        assertNotNull(vm.error)
        assertFalse(vm.suggestBusy)
    }

    @Test fun lateAutomaticFixesPreserveSavedStartBeforeRouteBuild() {
        val vm = MirageViewModel()
        vm.useMyLocation(quickReal)
        vm.setDestPoint(freshReal, "Office")
        assertTrue(vm.pickSavedPlace(home, Field.START))
        assertTrue(vm.routePts.isEmpty())
        vm.useMyLocation(quickReal)
        vm.refineMyLocation(freshReal)
        assertEquals(home.dest, vm.start)
        assertEquals("Home", vm.startName)
        assertEquals(freshReal, vm.dest)
        assertEquals(freshReal, vm.lastReal)
        assertFalse(vm.startFromReal)
        assertFalse(vm.useSimulatedStart)
        // The complete saved metadata remains available when this endpoint becomes a destination.
        vm.configureApi(ApiConfig(null, "", "saved-place-test"))
        assertTrue(vm.swapEndpoints())
        assertEquals(home.destAddress, vm.destAddress)
        assertEquals(home.destPlaceId, vm.destPlaceId)
    }

    @Test fun automaticRealStartsRefreshAfterStopAndExplicitRealChoiceStillWorks() {
        val vm = MirageViewModel()
        vm.useMyLocation(quickReal)
        vm.onStopped()
        vm.useMyLocation(freshReal)
        assertEquals(freshReal, vm.start)
        assertTrue(vm.startFromReal)
        assertTrue(vm.pickSavedPlace(home, Field.START))
        vm.pickRealStart(quickReal)
        assertEquals(quickReal, vm.start)
        assertEquals("My location", vm.startName)
        assertTrue(vm.startFromReal)
        assertFalse(vm.useSimulatedStart)
    }
}
