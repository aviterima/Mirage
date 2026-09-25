package com.mirage.spike

import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.TravelMode
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.store.SavedStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }

    @Test fun activeTripSaveIncludesEditsAndIgnoresDraft() {
        val store = InMemoryScenarioStore()
        val vm = MirageViewModel().apply { attachStore(store) }
        vm.choosePlanMode(PlanMode.SNAP)
        vm.setDestPoint(LatLng(0.0, 0.0), "Unrelated draft")
        val origin = LatLng(33.4, -112.0)
        val plan = com.mirage.spike.engine.LivePlan("Trip", origin,
            listOf(com.mirage.spike.engine.ItineraryStop("First", LatLng(33.5,-112.0), 10)),
            { _, _ -> error("Saving must not route") })
        com.mirage.spike.engine.LiveSession.activate(plan)
        try {
            plan.append(com.mirage.spike.engine.ItineraryStop("Added live", LatLng(33.6,-112.0), 20))
            assertTrue(vm.saveActiveScenario("Active"))
            val saved = vm.savedScenarios.single()
            assertEquals("ITINERARY", saved.kind)
            assertEquals(origin, saved.start)
            assertEquals(listOf("First","Added live"), saved.stops.map { it.name })
            assertEquals(20, saved.stops.last().dwellMinutes)
            assertEquals("Unrelated draft", vm.destName)
            assertEquals(saved, MirageViewModel().apply { attachStore(store) }.savedScenarios.single())
        } finally { com.mirage.spike.engine.LiveSession.clear() }
    }

    @Test fun `scenario survives a JSON round trip`() {
        val sc = SavedScenario(
            id = "1", name = "Lunch run", kind = "ITINERARY", createdAt = 42L, startIsReal = true,
            start = null, startName = "", dest = null, destName = "",
            travelMode = TravelMode.TRANSIT, speeds = mapOf(TravelMode.DRIVE to 40f, TravelMode.WALK to 3.5f),
            realism = Realism.BUSY, transitPref = "subway",
            stops = listOf(SavedStop("Pomo", 33.5, -112.0, 75, TravelMode.WALK, 3.5f), SavedStop("Office", 33.6, -112.1, 240, TravelMode.DRIVE, 40f)),
        )
        val back = SavedScenario.listFromJson(SavedScenario.listToJson(listOf(sc)))
        assertEquals(listOf(sc), back)
    }

    @Test fun `save, reload in a fresh ViewModel, and load back onto the screen`() {
        val store = InMemoryScenarioStore()
        val vm = MirageViewModel().apply { attachStore(store) }
        vm.choosePlanMode(PlanMode.ITINERARY)
        vm.setDestPoint(LatLng(33.50, -112.00), "Pomo")
        vm.setDestPoint(LatLng(33.60, -112.10), "Office")
        vm.setDwell(0, 75)
        assertTrue(vm.canSaveScenario)
        assertTrue(vm.saveScenario("Lunch run"))
        assertEquals(1, vm.savedScenarios.size)
        // Duplicate names are rejected without changing the original.
        assertFalse(vm.saveScenario("lunch RUN"))
        assertEquals(1, vm.savedScenarios.size)

        val vm2 = MirageViewModel().apply { attachStore(store) }
        assertEquals(1, vm2.savedScenarios.size)
        val sc = vm2.savedScenarios[0]
        assertEquals("Lunch run", sc.name)
        vm2.loadScenario(sc)
        assertEquals(PlanMode.ITINERARY, vm2.planMode)
        assertEquals(2, vm2.stops.size)
        assertEquals(75, vm2.stops[0].dwellMinutes)
        assertEquals("Office", vm2.stops[1].name)
        assertNull(vm2.dest)
    }

    @Test fun `a snap saves and loads its place`() {
        val vm = MirageViewModel().apply { attachStore(InMemoryScenarioStore()) }
        vm.choosePlanMode(PlanMode.SNAP)
        assertFalse(vm.canSaveScenario)
        vm.setDestPoint(LatLng(48.8584, 2.2945), "Eiffel Tower")
        assertTrue(vm.saveScenario("Paris"))
        vm.setDestPoint(LatLng(0.0, 0.0), "Elsewhere")
        vm.loadScenario(vm.savedScenarios[0])
        assertEquals("Eiffel Tower", vm.destName)
        assertEquals(48.8584, vm.dest!!.lat, 1e-9)
    }

    @Test fun `stay time is clamped to a day`() {
        val vm = MirageViewModel().apply { attachStore(InMemoryScenarioStore()) }
        vm.choosePlanMode(PlanMode.ITINERARY)
        vm.setDestPoint(LatLng(33.5, -112.0), "A")
        vm.setDwell(0, 5000); assertEquals(1440, vm.stops[0].dwellMinutes)
        vm.setDwell(0, -5); assertEquals(0, vm.stops[0].dwellMinutes)
        vm.setDwell(0, 45); assertEquals(45, vm.stops[0].dwellMinutes)
    }
}

