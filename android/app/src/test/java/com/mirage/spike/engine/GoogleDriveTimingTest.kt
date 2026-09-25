package com.mirage.spike.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GoogleDriveTimingTest {
    @Before fun reset() { PlaybackSource.timeScale = 1.0; PlaybackSource.consumeSkip() }
    @After fun cleanup() { PlaybackSource.timeScale = 1.0; PlaybackSource.consumeSkip() }
    private fun route(): RouteResult {
        val a = LatLng(0.0, 0.0); val b = LatLng(0.0, 0.05); val c = LatLng(0.05, 0.05)
        return RouteResult(listOf(a, b, c), 11120.0, 600.0, listOf(
            RouteSegment(listOf(a,b),5560.0,180.0,instruction="Continue on I-10"),
            RouteSegment(listOf(b,c),5560.0,220.0,instruction="Turn left onto Main St",maneuver="turn-left")))
    }
    @Test fun matchesGoogleDurationWithoutAddedStops() = runTest {
        val fixes = DriveModel(route()).fixes().toList()
        assertTrue(abs((fixes.size - 1) * 0.2 - 600.0) <= 0.21)
        assertEquals(600, fixes.first().remainingSec)
        assertEquals(0, fixes.last().remainingSec)
        assertFalse(fixes.drop(1).dropLast(1).any { it.speedMps == 0f })
        assertEquals(0.05, fixes.last().lat, 1e-9)
        fixes.zipWithNext().forEach { (a,b) ->
            assertTrue(b.progress >= a.progress)
            assertTrue(Geo.haversine(LatLng(a.lat,a.lng),LatLng(b.lat,b.lng)) < 10.0)
        }
    }
    @Test fun fastForwardStillScalesGoogleTiming() = runTest {
        PlaybackSource.timeScale = 10.0
        val fixes = DriveModel(route()).fixes().toList()
        assertEquals(301, fixes.size)
        assertEquals(60, fixes.first().remainingSec)
    }
    @Test fun trafficDurationIsUsedAndStepBudgetsAreScaled() {
        val body = """{"status":"OK","routes":[{"overview_polyline":{"points":""},"legs":[{"distance":{"value":1000},"duration":{"value":100},"duration_in_traffic":{"value":150},"steps":[{"distance":{"value":1000},"duration":{"value":100}}]}]}]}"""
        val result = GoogleDirectionsRouteEngine.parseDirections(body, false)
        assertEquals(150.0,result.durationSeconds,0.0)
        assertEquals(150.0,result.segments.single().durationSeconds,0.0)
        assertTrue(result.trafficAware)
        val fallback = GoogleDirectionsRouteEngine.parseDirections(body.replace("\"duration_in_traffic\":{\"value\":150},",""), false)
        assertEquals(100.0,fallback.durationSeconds,0.0)
        assertFalse(fallback.trafficAware)
    }
}
