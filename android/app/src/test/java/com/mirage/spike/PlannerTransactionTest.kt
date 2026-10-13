package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerTransactionTest {
    private val home = LatLng(33.5, -112.0)
    private val office = LatLng(33.6, -112.1)
    private lateinit var disk: MemoryPlannerDraftStorage
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
        disk = MemoryPlannerDraftStorage(); InputDraftStore.useStorageForTest(disk)
    }
    @After fun cleanup() {
        Conversation.resetPlanningSession()
        InputDraftStore.useStorageForTest(null)
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
        Dispatchers.resetMain()
    }
    private fun model() = MirageViewModel().apply {
        configureApi(ApiConfig.NONE); attachStore(InMemoryScenarioStore())
        setStartPoint(home, "Home", "Home address", "home-id")
        choosePlanMode(PlanMode.ITINERARY)
        setDestPoint(office, "Existing appointment")
        setDwell(0, 37); departureMillis = 1_900_000_000_000L
        saveScenario("Named day")
    }

    @Test fun multipleStopsStayDepartureAndEndpointChangesCommitAndUndoOnce() {
        val vm = model()
        val before = vm.capturePlanner()
        val library = vm.savedScenarios.toList()
        val added = ItineraryStop("Lunch", LatLng(33.7,-112.2), 60, TravelMode.WALK, 3f,
            address = "Lunch address", placeId = "lunch-id", entrance = LatLng(33.7001,-112.2),
            arrivalActivity = ArrivalActivity.TABLE, stayUntilLeave = true).toSavedStop()
        val candidate = before.copy(scenario = before.scenario.copy(
            start = office, startName = "Office", departureMillis = 1_900_003_600_000L,
            stops = before.scenario.stops.map { it.copy(dwellMinutes = 75) } + added))
        vm.activeField = Field.START // focus is not a semantic change
        assertTrue(vm.applyPlannerProposal(before, candidate))
        assertEquals(Field.START, vm.activeField)
        assertEquals(2, vm.stops.size); assertEquals(75, vm.stops.first().dwellMinutes)
        assertEquals(added, vm.stops.last().toSavedStop())
        assertEquals(office, vm.start); assertEquals(candidate.scenario.departureMillis, vm.departureMillis)
        assertEquals("Named day", vm.draftSavedName); assertEquals(library, vm.savedScenarios.toList())
        assertNull(PlaybackSource.current); assertFalse(MockState.status.value.running)
        vm.undoDraftStops()
        assertEquals(before, vm.capturePlanner())
        assertFalse(vm.draftUndoAvailable); assertEquals(library, vm.savedScenarios.toList())
        vm.undoDraftStops()
        assertEquals(before, vm.capturePlanner())
    }

    @Test fun staleEmptyDraftAndOldGenerationCannotApply() {
        val vm = MirageViewModel().apply { configureApi(ApiConfig.NONE) }
        val empty = vm.capturePlanner()
        assertTrue(empty.scenario.stops.isEmpty()); assertNull(empty.scenario.dest)
        vm.setStartPoint(home, "Chosen later")
        assertFalse(vm.applyPlannerProposal(empty, empty.copy(scenario = empty.scenario.copy(departureMillis = 1L))))
        assertEquals(home, vm.start)
        val current = vm.capturePlanner()
        assertTrue(vm.startNewItinerary())
        assertFalse(vm.applyPlannerProposal(current, current))
        assertTrue(vm.stops.isEmpty()); assertNull(vm.start)
    }

    @Test fun invalidFinalChangeCannotPartiallyApplyEarlierChanges() {
        val vm = model(); val before = vm.capturePlanner()
        val bad = before.copy(scenario = before.scenario.copy(departureMillis = 4L,
            stops = before.scenario.stops + before.scenario.stops.single().copy(lat = 200.0)))
        assertFalse(vm.applyPlannerProposal(before, bad))
        assertEquals(before, vm.capturePlanner())
        assertFalse(vm.applyPlannerProposal(before, before.copy(scenario = before.scenario.copy(id = "different"))))
        assertEquals(before, vm.capturePlanner())
    }

    @Test fun proposalUndoRefusesToOverwriteLaterUnrelatedEdits() {
        val vm = model(); val before = vm.capturePlanner()
        assertTrue(vm.applyPlannerProposal(before, before.copy(scenario = before.scenario.copy(departureMillis = 5L))))
        vm.stops.add(ItineraryStop("Later manual addition", home, 20))
        val later = vm.capturePlanner()
        vm.undoDraftStops()
        assertEquals(later, vm.capturePlanner()); assertNotNull(vm.error)
    }

    @Test fun legacyStopUndoStillRevertsTheLastManualStopEdit() {
        val vm = model(); val before = vm.stops.toList()
        vm.setDwell(0, 90)
        vm.undoDraftStops()
        assertEquals(before, vm.stops.toList())
        assertEquals("Named day", vm.draftSavedName)
    }

    @Test fun emptySnapshotRoundTripsAndRestoresWithoutRoutingOrStarting() {
        var requests = 0
        val vm = MirageViewModel { _, _, _ -> requests++; emptyList() }.apply {
            configureApi(ApiConfig.NONE); setStartPoint(home, "Home", "Home address", "home-id")
            choosePlanMode(PlanMode.ITINERARY); departureMillis = 1234L
        }
        val snapshot = vm.capturePlanner()
        assertEquals(snapshot, PlannerSnapshot.fromJson(snapshot.toJson()))
        assertTrue(TripRecovery.savePlannerIfCurrent(snapshot, vm.draftGeneration))
        val restored = MirageViewModel { _, _, _ -> requests++; emptyList() }
        assertTrue(restored.restorePlanner(TripRecovery.plannerSnapshot()!!))
        assertEquals(snapshot, restored.capturePlanner())
        assertTrue(restored.stops.isEmpty()); assertNull(restored.dest)
        assertEquals(0, requests); assertNull(PlaybackSource.current)
    }

    @Test fun failedDurableResetLeavesPlanIdentityInputsAndGenerationIntact() {
        val vm = model(); val before = vm.capturePlanner()
        assertTrue(InputDraftStore.write("planner.end", "unfinished words"))
        assertTrue(TripRecovery.savePlannerIfCurrent(before, before.draftGeneration))
        disk.fail = true
        assertFalse(vm.startNewItinerary())
        assertEquals(before, vm.capturePlanner())
        assertEquals("unfinished words", InputDraftStore.read("planner.end"))
        assertEquals(before, TripRecovery.plannerSnapshot())
        assertNotNull(vm.error)
    }
}
