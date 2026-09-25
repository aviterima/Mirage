package com.mirage.spike.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class FlightTimingTest {
    @Before fun reset() { PlaybackSource.timeScale = 1.0; PlaybackSource.consumeSkip() }
    @After fun cleanup() { PlaybackSource.timeScale = 1.0; PlaybackSource.consumeSkip() }

    @Test fun taxiTimeDoesNotGrowWithFlightDistance() = runTest {
        val start = LatLng(0.0, 0.0)
        for (end in listOf(LatLng(0.0, 10.0), LatLng(0.0, 60.0))) {
            val fixes = FlightModel(start, end, FlightParams(timeScale = 10.0)).fixes().toList()
            val groundBeforeClimb = fixes.takeWhile { it.altitudeM == 0.0 }
            assertEquals("120 seconds taxi plus 35 seconds takeoff", 31, groundBeforeClimb.size)
            assertTrue(groundBeforeClimb.maxOf { it.speedMps } <= 80f)
        }
    }

    @Test fun etaMatchesActualPlaybackAndMotionIsContinuous() = runTest {
        val model = FlightModel(LatLng(0.0, 0.0), LatLng(0.0, 6.0))
        val fixes = model.fixes().toList()
        val elapsed = (fixes.size - 1) * 0.5
        assertTrue(abs(fixes.first().remainingSec - elapsed) <= 1.0)
        assertTrue(abs(model.durationSeconds - elapsed) <= 0.5)
        fixes.zipWithNext().forEach { (a, b) ->
            val distance = Geo.haversine(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng))
            assertTrue("displacement consistent with reported speed", abs(distance - (a.speedMps + b.speedMps) * 0.25) < 0.6)
            assertTrue(b.progress >= a.progress)
            assertTrue(b.remainingSec <= a.remainingSec)
        }
        assertEquals(0f, fixes.last().speedMps, 0f)
        assertEquals(0, fixes.last().remainingSec)
    }

    @Test fun shortFlightUsesLowerPeakAltitudeAndSpeed() = runTest {
        val fixes = FlightModel(LatLng(0.0, 0.0), LatLng(0.0, 0.5), FlightParams(timeScale = 10.0)).fixes().toList()
        assertTrue(fixes.maxOf { it.altitudeM } in 1.0..5000.0)
        assertTrue(fixes.maxOf { it.speedMps } < 245f)
        assertEquals(0.5, fixes.last().lng, 1e-9)
        assertEquals(1f, fixes.last().progress, 0f)
    }

    @Test fun identicalEndpointsFinishImmediately() = runTest {
        val point = LatLng(33.4, -112.0)
        val fixes = FlightModel(point, point).fixes().toList()
        assertEquals(1, fixes.size)
        assertEquals(1f, fixes.single().progress, 0f)
        assertEquals(0, fixes.single().remainingSec)
    }
}
