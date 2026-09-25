package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Desired acceptance behavior: failures intentionally expose current product defects.
 * These are component journeys, not substitutes for the 50 full device workflows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowAcceptanceTest {
    private val home = LatLng(33.5, -112.0)
    private val office = LatLng(33.6, -112.1)
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
        PlaybackSource.timeScale = 1.0; PlaybackSource.consumeSkip()
    }
    @After fun cleanup() {
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
        PlaybackSource.timeScale = 1.0; PlaybackSource.consumeSkip()
        Dispatchers.resetMain()
    }
    private fun vm() = MirageViewModel().apply {
        configureApi(ApiConfig(null, "", "acceptance-test"))
        attachStore(InMemoryScenarioStore())
    }

    @Test fun savedPlacesComposeRouteWithoutReplacingOtherEndpoint() {
        val vm = vm()
        vm.choosePlanMode(PlanMode.SNAP); vm.setDestPoint(home, "Home"); vm.saveScenario("Home")
        val a = vm.savedScenarios.first()
        vm.setDestPoint(office, "Office"); vm.saveScenario("Office")
        val b = vm.savedScenarios.first()
        vm.useSavedPlaceAsStart(a); vm.useSavedPlaceAsDestination(b)
        assertEquals(PlanMode.ROUTE, vm.planMode)
        assertEquals(home, vm.tripStart()); assertEquals(office, vm.dest)
        assertEquals(2, vm.savedScenarios.size)
    }

    @Test fun disconnectedRoutesRequireConnectorWithoutPartialMutation() {
        val vm = vm()
        vm.choosePlanMode(PlanMode.ROUTE); vm.setStartPoint(home, "Home"); vm.setDestPoint(office, "Office")
        vm.saveScenario("Commute")
        val route = vm.savedScenarios.single()
        vm.choosePlanMode(PlanMode.SNAP)
        assertTrue(vm.appendSavedRoute(route))
        val before = vm.stops.toList()
        assertFalse(vm.appendSavedRoute(route))
        assertEquals(before, vm.stops.toList())
        assertTrue(vm.appendSavedRoute(route, true))
        assertEquals(listOf(office, home, office), vm.stops.map { it.point })
        assertEquals(0, vm.stops[1].dwellMinutes)
    }

    @Test fun durationParserRejectsMalformedAndSupportsCompoundWords() {
        assertEquals(27, CommandParser.minutes("twenty-seven minutes"))
        assertEquals(120, CommandParser.minutes("two hours"))
        listOf("-5 minutes", "1.5 minutes", "minus five minutes", "999999999999999999999 minutes", "five one minutes", ".5 minutes", "twenty seventeen minutes", "one hundred five minutes").forEach {
            assertNull(it, CommandParser.minutes(it))
        }
    }

    @Test fun uc09_duplicateNameCannotSilentlyDestroyOriginal() {
        val vm = vm()
        vm.choosePlanMode(PlanMode.SNAP); vm.setDestPoint(home, "Home Phoenix")
        assertTrue(vm.saveScenario("Home"))
        val original = vm.savedScenarios.single()
        vm.setDestPoint(office, "Home elsewhere")
        vm.saveScenario("HOME")
        assertTrue("No replace approval was given; original ID and coordinates must survive",
            vm.savedScenarios.any { it.id == original.id && it.dest == home })
    }

    @Test fun uc03_savedRealOriginMustNotResolveToSimulatedLocation() {
        val vm = vm()
        vm.pickRealStart(home); vm.setDestPoint(office, "Office")
        assertTrue(vm.saveScenario("Real origin"))
        val saved = vm.savedScenarios.single()
        MockState.update { it.copy(running = true, lat = 40.0, lng = -74.0) }
        vm.loadScenario(saved)
        assertEquals("A saved real-origin route must retain that origin policy", home, vm.tripStart())
    }

    @Test fun uc28_returnToStartMustUseEffectiveSimulatedOrigin() {
        val vm = vm()
        vm.setStartPoint(home, "Old draft")
        MockState.update { it.copy(running = true, lat = office.lat, lng = office.lng) }
        vm.useSimulatedPosition(); vm.choosePlanMode(PlanMode.ITINERARY)
        vm.setDestPoint(LatLng(34.0, -112.0), "Next")
        val effectiveOrigin = vm.tripStart()
        vm.addReturnToStart()
        assertEquals("Return must use the trip origin, not a stale draft field",
            effectiveOrigin, vm.stops.last().point)
    }

    @Test fun uc37_spokenSeventeenMinutesIsExact() {
        assertEquals(SpokenCommand.Extend(17), CommandParser.parse("extend stay by seventeen minutes"))
    }

    @Test fun uc44_negativeDurationMustNotBecomePositive() {
        assertTrue("Malformed negative duration must be rejected",
            CommandParser.parse("stay for -5 minutes") is SpokenCommand.Unknown)
    }

    @Test fun uc25_whenArrivingJourneyMustNotTurnIntoStayOnly() {
        assertTrue("Destination intent must not be discarded",
            CommandParser.parse("when i arrive, drive to the office then stay for thirty minutes")
                is SpokenCommand.Journey)
    }

    @Test fun uc45_spokenStatusDisclosesUnconfirmedOutput() {
        MockState.update { it.copy(running = true, health = Health.AMBER,
            message = "Waiting for Google location feed confirmation", label = "Test trip") }
        val method = Conversation::class.java.getDeclaredMethod("describeStatus")
        method.isAccessible = true
        val answer = (method.invoke(Conversation) as String).lowercase()
        assertTrue("Spoken status omitted the degraded output: " + answer,
            listOf("unconfirmed", "not confirmed", "waiting for google", "warning", "output").any { it in answer })
    }

    @Test fun uc33_staleFusedOutputNeverClaimsActive() {
        val now = 100_000L
        val s = MockStatus(running = true, health = Health.GREEN,
            lastFixMillis = now, lastFusedFixMillis = now - 5001)
        assertTrue(simulationStatusText(s, ActivityKind.TRAVELING, now).startsWith("NEEDS ATTENTION"))
    }

    @Test fun uc44_bareStopRequiresClarification() {
        assertEquals(SpokenCommand.ClarifyStop, CommandParser.parse("stop"))
        assertEquals(SpokenCommand.Stop, CommandParser.parse("stop simulation"))
    }

    @Test fun uc27_upcomingEditsPreserveCurrentIdentity() = runTest {
        val plan = LivePlan("Day", home,
            listOf(ItineraryStop("Office", office, 60), ItineraryStop("Lunch", home, 30)),
            { from, to -> PreparedLeg(flowOf(Fix(to.point.lat, to.point.lng, 0f, 0f, 4f)), listOf(from, to.point)) })
        val job = launch { plan.fixes().collect {} }
        try {
            advanceTimeBy(1000); runCurrent()
            val current = plan.view().stops.first().id
            val upcoming = plan.view().stops.last().id
            assertTrue(plan.setStay(upcoming, 45))
            assertFalse(plan.remove(current))
            assertEquals(current, plan.view().stops.first().id)
            assertEquals(45, plan.view().stops.last().stop.dwellMinutes)
        } finally { job.cancelAndJoin() }
    }

    @Test fun uc29_fullTemplateDoesNotSilentlyShortenCurrentStay() = runTest {
        val plan = LivePlan("Day", home, listOf(ItineraryStop("Office", office, 30)),
            { from, to -> PreparedLeg(flowOf(Fix(to.point.lat, to.point.lng, 0f, 0f, 4f)), listOf(from, to.point)) })
        val job = launch { plan.fixes().collect {} }
        try {
            advanceTimeBy(120_000); runCurrent()
            val vm = vm()
            assertTrue(vm.saveActiveScenario("Full day"))
            assertEquals("Save trip advertises replay from start; configured stay should remain 30",
                30, vm.savedScenarios.single().stops.single().dwellMinutes)
        } finally { job.cancelAndJoin() }
    }

    @Test fun uc46_savedCollectionSurvivesNewViewModel() {
        val store = InMemoryScenarioStore()
        val vm = MirageViewModel().apply { attachStore(store) }
        vm.choosePlanMode(PlanMode.SNAP); vm.setDestPoint(home, "Home"); vm.saveScenario("Home")
        val expected = vm.savedScenarios.toList()
        assertEquals(expected, MirageViewModel().apply { attachStore(store) }.savedScenarios.toList())
    }
}

