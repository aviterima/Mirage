package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Saved places compose a draft directly, without starting or replacing a trip. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerSavedPlacesTest {
    private val origin = LatLng(33.6, -112.1)
    private val homePoint = LatLng(33.5, -112.0)
    private val entrance = LatLng(33.5001, -112.0001)
    private val home = SavedScenario(
        "home", "Home", "SNAP", 1L, false, null, "", homePoint, "House pin",
        TravelMode.DRIVE, emptyMap(), Realism.REALISTIC, null, emptyList(),
        destAddress = "Home fixture address", destPlaceId = "home-place-id",
        aliases = listOf("My house"), defaultStayMinutes = 45,
        arrivalActivity = ArrivalActivity.TABLE, entrance = entrance, stayUntilLeave = true,
    )

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
    }

    @After fun cleanup() {
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
        Dispatchers.resetMain()
    }

    private fun vm(items: List<SavedScenario> = listOf(home)): MirageViewModel {
        val store = InMemoryScenarioStore().apply { save(items) }
        return MirageViewModel().apply {
            configureApi(ApiConfig(null, "", "saved-place-test"))
            attachStore(store)
        }
    }

    private fun assertNotStarted() {
        assertFalse(MockState.status.value.running)
        assertNull(PlaybackSource.current)
        assertNull(LiveSession.plan)
    }

    @Test fun routeDestinationKeepsOriginAndSavesHomeWithoutStarting() {
        val vm = vm()
        vm.setStartPoint(origin, "Office")
        vm.chooseMode(TravelMode.WALK)
        vm.avgMph = 4f
        assertTrue(vm.pickSavedPlace(home, Field.END))
        assertEquals(PlanMode.ROUTE, vm.planMode)
        assertEquals(origin, vm.tripStart()); assertEquals("Office", vm.startName)
        assertEquals(homePoint, vm.dest); assertEquals("Home", vm.destName)
        assertEquals(home.destAddress, vm.destAddress); assertEquals(home.destPlaceId, vm.destPlaceId)
        assertEquals(TravelMode.WALK, vm.mode); assertEquals(4f, vm.avgMph, 0f)
        assertEquals(home.arrivalActivity, vm.arrivalActivity)
        assertEquals(entrance, vm.arrivalEntrance); assertTrue(vm.stayUntilLeave)
        assertEquals(45, vm.defaultStayMinutes)
        assertNotStarted()
        assertTrue(vm.saveScenario("Walk home"))
        val route = vm.savedScenarios.single { it.name == "Walk home" }
        assertEquals(origin, route.start); assertEquals(homePoint, route.dest)
        assertEquals(home.destPlaceId, route.destPlaceId)
        vm.loadScenario(route)
        assertEquals(origin, vm.tripStart()); assertEquals(homePoint, vm.dest)
        assertNotStarted()
    }

    @Test fun selectingDestinationPreservesRealAndSimulatedOriginPolicies() {
        val real = vm()
        real.pickRealStart(origin)
        assertTrue(real.pickSavedPlace(home, Field.END))
        assertTrue(real.startFromReal); assertFalse(real.useSimulatedStart)
        assertEquals(origin, real.tripStart())
        assertNotStarted()

        val simulated = vm()
        simulated.setStartPoint(LatLng(30.0, -100.0), "Old draft origin")
        MockState.update { it.copy(running = true, lat = origin.lat, lng = origin.lng) }
        simulated.useSimulatedPosition()
        val liveStatus = MockState.status.value
        assertTrue(simulated.pickSavedPlace(home, Field.END))
        assertTrue(simulated.useSimulatedStart)
        assertEquals(origin, simulated.tripStart())
        assertEquals(liveStatus, MockState.status.value)
        assertNull(PlaybackSource.current); assertNull(LiveSession.plan)
    }

    @Test fun itineraryAppendsHomeAndPreservesEarlierStopSettings() {
        val vm = vm()
        vm.setStartPoint(origin, "Office")
        vm.choosePlanMode(PlanMode.ITINERARY)
        vm.chooseMode(TravelMode.WALK)
        vm.setDestPoint(LatLng(33.55, -112.05), "Lunch")
        vm.setDwell(0, 17)
        vm.setStopDeadline(0, 1_900_000_000_000L)
        val existing = vm.stops.toList()
        assertTrue(vm.pickSavedPlace(home, Field.END))
        assertEquals(PlanMode.ITINERARY, vm.planMode)
        assertEquals(origin, vm.tripStart())
        assertEquals(existing, vm.stops.dropLast(1))
        val added = vm.stops.last()
        assertEquals("Home", added.name); assertEquals(homePoint, added.point)
        assertEquals(TravelMode.WALK, added.mode)
        assertEquals(45, added.dwellMinutes); assertTrue(added.stayUntilLeave)
        assertEquals(ArrivalActivity.TABLE, added.arrivalActivity)
        assertEquals(entrance, added.entrance)
        assertEquals(home.destAddress, added.address); assertEquals(home.destPlaceId, added.placeId)
        assertNull(vm.dest)
        assertNotStarted()
        assertTrue(vm.saveScenario("New day"))
        vm.loadScenario(vm.savedScenarios.single { it.name == "New day" })
        assertEquals(existing + added, vm.stops.toList())
        assertNotStarted()
    }

    @Test fun choosingSavedStartPreservesDestinationAndItinerary() {
        val vm = vm()
        vm.setDestPoint(origin, "Office")
        assertTrue(vm.pickSavedPlace(home, Field.START))
        assertEquals(homePoint, vm.tripStart()); assertEquals(origin, vm.dest)
        vm.choosePlanMode(PlanMode.ITINERARY)
        vm.setDwell(0, 20)
        val stops = vm.stops.toList()
        assertTrue(vm.pickSavedPlace(home, Field.START))
        assertEquals(PlanMode.ITINERARY, vm.planMode)
        assertEquals(stops, vm.stops.toList())
        assertNotStarted()
    }

    @Test fun namesAndAliasesOfferOnlySavedPlacesWithoutNetworkOrApiKey() = runTest {
        var networkCalls = 0
        val store = InMemoryScenarioStore().apply {
            save(listOf(home, home.copy(id = "route", name = "Home commute", kind = "ROUTE"),
                home.copy(id = "missing", name = "Home without a pin", dest = null)))
        }
        val vm = MirageViewModel { _, _, _ -> networkCalls++; emptyList() }.apply {
            configureApi(ApiConfig(null, "", "saved-place-test")); attachStore(store)
        }
        vm.editLocationQuery(Field.END, "hOm", null)
        advanceUntilIdle()
        assertEquals(listOf("home"), vm.savedPlaceSuggestions.map { it.id })
        vm.editLocationQuery(Field.END, "MY HOUSE", null)
        advanceUntilIdle()
        assertEquals(listOf("home"), vm.savedPlaceSuggestions.map { it.id })
        vm.search("Home") {}
        advanceUntilIdle()
        assertEquals(listOf("home"), vm.savedPlaceSuggestions.map { it.id })
        assertNull(vm.error); assertEquals(0, networkCalls)
        assertTrue(vm.pickSavedPlace(vm.savedPlaceSuggestions.single(), Field.END))
        assertEquals(homePoint, vm.dest)
        assertTrue(vm.savedPlaceSuggestions.isEmpty()); assertTrue(vm.suggestions.isEmpty())
        assertNotStarted()
    }

    @Test fun selectingSavedPlaceRejectsLateUncancellableSearch() = runTest {
        var searched = false
        val remote = PlaceHit(LatLng(34.0, -113.0), "Home store", "Remote address", "remote")
        val vm = MirageViewModel { _, _, _ ->
            searched = true
            withContext(NonCancellable) { delay(1000) }
            listOf(remote)
        }.apply {
            configureApi(ApiConfig(null, "fixture", "saved-place-test"))
            attachStore(InMemoryScenarioStore().apply { save(listOf(home)) })
        }
        vm.setStartPoint(origin, "Office")
        vm.editLocationQuery(Field.END, "Home", null)
        advanceTimeBy(400); runCurrent()
        assertTrue(searched)
        assertTrue(vm.pickSavedPlace(home, Field.END))
        advanceUntilIdle()
        assertTrue(vm.suggestions.isEmpty()); assertTrue(vm.savedPlaceSuggestions.isEmpty())
        assertFalse(vm.suggestBusy)
        vm.pickSuggestion(remote)
        assertEquals(homePoint, vm.dest); assertEquals(origin, vm.tripStart())
        assertNotStarted()
    }

    @Test fun nonPlaceOrMissingCoordinatesCannotReplaceTheDraft() {
        val vm = vm()
        vm.setStartPoint(origin, "Office")
        vm.setDestPoint(homePoint, "Existing destination")
        val before = vm.draftSnapshot()
        assertFalse(vm.pickSavedPlace(home.copy(kind = "ROUTE"), Field.END))
        assertFalse(vm.pickSavedPlace(home.copy(dest = null), Field.START))
        assertEquals(before, vm.draftSnapshot())
        assertNotStarted()
    }
}
