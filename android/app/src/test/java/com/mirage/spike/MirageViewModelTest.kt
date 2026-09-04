package com.mirage.spike

import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The state machine behind the screen, exercised without Android: endpoints, plan modes,
 * how automatic real fixes interact with the user's work, and what Start needs.
 * Flights are used for routing because they need no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MirageViewModelTest {

    private val phx = LatLng(33.4484, -112.0740)
    private val tempe = LatLng(33.4255, -111.9400)
    private val lax = LatLng(33.9416, -118.4085)

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); MockState.reset(); PlaybackSource.current = null }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun plotFlight(vm: MirageViewModel, to: LatLng) {
        vm.chooseMode(TravelMode.FLY)
        vm.setDestPoint(to, "Airport")
        vm.buildRoute()
    }

    @Test
    fun `fresh app has no start until a real fix arrives, then adopts it`() {
        val vm = MirageViewModel()
        assertNull(vm.start)
        vm.useMyLocation(phx)
        assertEquals(phx, vm.start); assertTrue(vm.startFromReal); assertEquals("My location", vm.startName)
    }

    @Test
    fun `Start needs a plotted route and says so otherwise`() {
        val vm = MirageViewModel()
        var started = false
        vm.startSim { started = true }
        assertFalse(started); assertNotNull(vm.error)
        vm.useMyLocation(phx)
        plotFlight(vm, lax)
        assertTrue(vm.canStart)
        vm.startSim { started = true }
        assertTrue(started)
        assertEquals("Flight", PlaybackSource.label)
        assertNotNull(PlaybackSource.current)
        assertTrue("next plan defaults to the simulated position", vm.useSimulatedStart)
    }

    @Test
    fun `a late automatic real fix never wipes a route the user built`() {
        val vm = MirageViewModel()
        vm.useMyLocation(phx)
        plotFlight(vm, lax)
        assertTrue(vm.canStart)
        // The refining fix lands 20 m away: keep the route.
        vm.refineMyLocation(LatLng(phx.lat + 0.00018, phx.lng))
        assertTrue(vm.canStart)
        // A quick fix while an EXPLICIT start with a route exists: ignored.
        vm.setStartPoint(tempe, "Pin"); plotFlight(vm, lax); assertTrue(vm.canStart)
        vm.useMyLocation(phx)
        assertEquals(tempe, vm.start); assertTrue(vm.canStart)
        // But a genuinely different real position (5 km) on a real-fix start invalidates.
        val vm2 = MirageViewModel(); vm2.useMyLocation(phx); plotFlight(vm2, lax)
        vm2.refineMyLocation(LatLng(phx.lat + 0.05, phx.lng))
        assertFalse(vm2.canStart); assertTrue(vm2.routePts.isEmpty())
    }

    @Test
    fun `automatic fixes never flip the start away from the simulated position`() {
        val vm = MirageViewModel()
        vm.useMyLocation(phx)
        vm.onSnapStarted()
        assertTrue(vm.useSimulatedStart)
        vm.useMyLocation(tempe); vm.refineMyLocation(tempe)
        assertTrue("only an explicit choice may clear it", vm.useSimulatedStart)
        vm.setStartPoint(tempe, "Pin")
        assertFalse(vm.useSimulatedStart)
        vm.useSimulatedPosition()
        assertTrue(vm.useSimulatedStart)
    }

    @Test
    fun `plan modes carry the End into the itinerary and a lone stop back out`() {
        val vm = MirageViewModel()
        vm.useMyLocation(phx)
        vm.setDestPoint(tempe, "Tempe")
        vm.choosePlanMode(PlanMode.ITINERARY)
        assertEquals(1, vm.stops.size); assertNull(vm.dest)
        vm.choosePlanMode(PlanMode.ROUTE)
        assertEquals(tempe, vm.dest); assertEquals("Tempe", vm.destName); assertTrue(vm.stops.isEmpty())
        // A new End after that becomes the (only) stop, not a duplicate.
        vm.setDestPoint(lax, "LAX")
        vm.choosePlanMode(PlanMode.ITINERARY)
        assertEquals(1, vm.stops.size); assertEquals("LAX", vm.stops[0].name)
    }

    @Test
    fun `itinerary mode chains every chosen place as the next stop`() {
        val vm = MirageViewModel()
        vm.useMyLocation(phx)
        vm.choosePlanMode(PlanMode.ITINERARY)
        vm.setDestPoint(tempe, "Tempe")
        vm.setDestPoint(lax, "LAX")
        assertEquals(listOf("Tempe", "LAX"), vm.stops.map { it.name })
        assertNull(vm.dest)
        vm.cycleStopMode(1)
        assertEquals(TravelMode.BIKE, vm.stops[1].mode)
        vm.adjustDwell(0, -45)
        assertEquals(0, vm.stops[0].dwellMinutes)
        vm.removeStop(0)
        assertEquals(listOf("LAX"), vm.stops.map { it.name })
    }

    @Test
    fun `changing an endpoint or mode clears the plotted route`() {
        val vm = MirageViewModel()
        vm.useMyLocation(phx); plotFlight(vm, lax)
        assertTrue(vm.canStart)
        vm.chooseMode(TravelMode.DRIVE)
        assertFalse(vm.canStart); assertTrue(vm.routePts.isEmpty())
        vm.chooseMode(TravelMode.FLY); vm.buildRoute(); assertTrue(vm.canStart)
        vm.setDestPoint(tempe, "Tempe")
        assertFalse(vm.canStart)
    }

    @Test
    fun `speeds are remembered per mode with sensible ranges`() {
        val vm = MirageViewModel()
        vm.chooseMode(TravelMode.DRIVE); vm.avgMph = 60f
        vm.chooseMode(TravelMode.WALK)
        assertEquals(3f, vm.avgMph, 0f)
        vm.chooseMode(TravelMode.DRIVE)
        assertEquals(60f, vm.avgMph, 0f)
        assertTrue(speedRange(TravelMode.FLY).contains(defaultSpeed(TravelMode.FLY)))
        assertEquals("0.6 mi", fmtMiles(1000.0)); assertEquals("2 h 5 min", fmtDuration(7500.0))
    }
}
