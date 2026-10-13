package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlaceResolutionEquivalenceTest {
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
        InputDraftStore.useStorageForTest(MemoryPlannerDraftStorage())
    }

    @After fun cleanup() {
        InputDraftStore.useStorageForTest(null); Conversation.resetPlanningSession()
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; Dispatchers.resetMain()
    }

    @Test fun uniqueAliasAndSavedIdPreserveTheSameSavedMetadataAndExplicitStay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val location = LatLng(33.41, -111.82)
        val entry = LatLng(33.42, -111.83)
        val saved = SavedScenario("studio-9", "Studio", "SNAP", 0, false, null, "", location, "Studio",
            TravelMode.WALK, emptyMap(), Realism.REALISTIC, null, emptyList(), destAddress = "Stored address",
            destPlaceId = "stored-place-id", aliases = listOf("atelier"), defaultStayMinutes = 85,
            arrivalActivity = ArrivalActivity.TABLE, entrance = entry, stayUntilLeave = true)

        for (requestedStay in listOf(-1, 20)) {
            val resolved = mutableListOf<ItineraryStop>()
            for (reference in listOf("saved:studio-9", "ATELIER")) {
                val vm = MirageViewModel({ _, _ -> PlanInterpretation(listOf(
                    PlanEdit("add_stop", place = reference, minutes = requestedStay))) },
                    { _, _, _ -> error("An exact unique saved alias must resolve without a network lookup") })
                vm.configureApi(ApiConfig.NONE); vm.attachStore(InMemoryScenarioStore())
                vm.choosePlanMode(PlanMode.ITINERARY); vm.setStartPoint(LatLng(33.0, -111.0), "Origin")
                vm.savedScenarios.add(saved)
                vm.planning.propose("visit the atelier", Field.END); advanceUntilIdle()

                val review = vm.planning.state.value
                assertTrue(review.canConfirm); assertTrue(review.choices.isEmpty())
                assertEquals(saved, review.candidate!!.preview.single().place.saved)
                assertTrue(vm.stops.isEmpty())
                assertTrue(vm.planning.confirm())
                val stop = vm.stops.single()
                assertEquals(saved.name, stop.name); assertEquals(saved.destAddress, stop.address)
                assertEquals(saved.destPlaceId, stop.placeId); assertEquals(saved.arrivalActivity, stop.arrivalActivity)
                assertEquals(entry, stop.entrance)
                assertEquals(if (requestedStay < 0) 85 else requestedStay, stop.dwellMinutes)
                assertEquals(requestedStay < 0, stop.stayUntilLeave)
                assertFalse(MockState.status.value.running); assertNull(PlaybackSource.current)
                resolved += stop
            }
            assertEquals(resolved[0], resolved[1])
        }
    }
}
