package com.mirage.spike

import com.mirage.spike.engine.ApiConfig
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.TravelMode
import com.mirage.spike.store.InMemoryScenarioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ItineraryBuilderTest {

    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }

    private fun vmWithDay(): MirageViewModel {
        val vm = MirageViewModel().apply { attachStore(InMemoryScenarioStore()) }
        vm.choosePlanMode(PlanMode.ITINERARY)
        vm.useMyLocation(LatLng(33.4484, -112.0740))          // downtown Phoenix
        vm.setDestPoint(LatLng(33.5091, -112.0263), "Pomo")   // ~7 km NE
        vm.setDestPoint(LatLng(33.6054, -111.8890), "Office") // ~17 km further
        return vm
    }

    @Test fun `timeline reads like a timetable and respects stay times`() {
        val vm = vmWithDay()
        vm.setDwell(0, 60)
        val t0 = 1_700_000_000_000L
        val tl = vm.timeline(t0)
        assertEquals(2, tl.size)
        // Drive at 45 mph over ~9 km with the road factor: a handful of minutes, not hours.
        assertTrue("leg1=${tl[0].legMinutes}", tl[0].legMinutes in 4..20)
        assertEquals(60L * 60_000L, tl[0].leaveMillis - tl[0].arriveMillis)
        assertTrue(tl[1].arriveMillis > tl[0].leaveMillis)
        assertTrue(tl[1].legMiles > tl[0].legMiles)
    }

    @Test fun `stops can be reordered and their mode changed inline`() {
        val vm = vmWithDay()
        vm.moveStop(1, -1)
        assertEquals("Office", vm.stops[0].name); assertEquals("Pomo", vm.stops[1].name)
        vm.moveStop(0, -1)   // no-op at the top
        assertEquals("Office", vm.stops[0].name)
        vm.setStopMode(0, TravelMode.WALK)
        assertEquals(TravelMode.WALK, vm.stops[0].mode)
        assertEquals(3f, vm.stops[0].avgMph, 0.01f)
    }

    @Test fun `return to start appends the start as the last stop`() {
        val vm = vmWithDay()
        vm.addReturnToStart()
        assertEquals(3, vm.stops.size)
        assertEquals(33.4484, vm.stops[2].point.lat, 1e-9)
        assertEquals(0, vm.stops[2].dwellMinutes)
        assertTrue(vm.stops[2].name.contains("start"))
    }

    @Test fun `api config builds direct or hosted urls and knows when it is usable`() {
        val direct = ApiConfig(null, "KEY123", "inst")
        assertEquals(ApiConfig.Mode.DIRECT, direct.mode)
        assertTrue(direct.enabled)
        assertTrue(direct.directionsUrl("origin=a&destination=b").endsWith("&key=KEY123"))
        assertEquals(mapOf("X-Goog-Api-Key" to "KEY123"), direct.headers(places = true))

        val hosted = ApiConfig("https://api.mirage.example/ ", "", "inst-1")
        assertEquals(ApiConfig.Mode.HOSTED, hosted.mode)
        assertTrue(hosted.enabled)
        assertEquals("https://api.mirage.example/v1/directions?origin=a", hosted.directionsUrl("origin=a"))
        assertEquals("https://api.mirage.example/v1/places/searchText", hosted.placesSearchUrl())
        assertEquals(mapOf("X-Mirage-Install" to "inst-1"), hosted.headers(places = true))

        assertFalse(ApiConfig(null, "", "x").enabled)
    }
}
