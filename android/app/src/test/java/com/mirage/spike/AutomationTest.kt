package com.mirage.spike

import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Signal
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
class AutomationTest {
    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()); MockState.reset(); PlaybackSource.paused = false; PlaybackSource.signal = Signal.GOOD; PlaybackSource.timeScale = 1.0 }
    @After fun reset() { Dispatchers.resetMain(); PlaybackSource.paused = false; PlaybackSource.timeScale = 1.0; PlaybackSource.signal = Signal.GOOD }

    @Test fun `commands need the token and drive the live controls`() {
        val vm = MirageViewModel().apply { attachStore(InMemoryScenarioStore()); automationToken = "abcd1234" }
        val t = mapOf("token" to "abcd1234")
        assertTrue(vm.runCommand("pause", emptyMap(), {}, {}).startsWith("Rejected"))
        assertFalse(PlaybackSource.paused)
        vm.runCommand("pause", t, {}, {}); assertTrue(PlaybackSource.paused)
        vm.runCommand("resume", t, {}, {}); assertFalse(PlaybackSource.paused)
        vm.runCommand("timescale", t + ("value" to "5"), {}, {}); assertEquals(5.0, PlaybackSource.timeScale, 0.0)
        vm.runCommand("signal", t + ("preset" to "poor"), {}, {}); assertEquals("Poor", PlaybackSource.signal.name)
        vm.runCommand("speed_over", t + ("value" to "-3"), {}, {}); assertEquals(-3.0, PlaybackSource.speedOverLimitMph, 0.0)
        var started = false
        val r = vm.runCommand("snap", t + mapOf("lat" to "33.5", "lng" to "-112.0", "name" to "Office"), { started = true }, {})
        assertTrue(r, started); assertEquals(PlanMode.SNAP, vm.planMode); assertEquals("Office", vm.destName)
        assertEquals(LatLng(33.5, -112.0), PlaybackSource.endPoint)
        assertTrue(vm.runCommand("bogus", t, {}, {}).startsWith("Unknown"))
    }

    @Test fun `queue after arrival is only offered while running and clears on explicit starts`() {
        val vm = MirageViewModel().apply { attachStore(InMemoryScenarioStore()) }
        vm.useMyLocation(LatLng(33.4484, -112.0740))
        vm.useTripEnd(); assertTrue(vm.queueAfterCurrent)
        vm.setStartPoint(LatLng(33.5, -112.0), "Pin"); assertFalse(vm.queueAfterCurrent)
        vm.useTripEnd(); vm.useSimulatedPosition(); assertFalse(vm.queueAfterCurrent); assertTrue(vm.useSimulatedStart)
    }
}
