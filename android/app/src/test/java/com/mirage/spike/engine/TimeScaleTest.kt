package com.mirage.spike.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast-forward must be LIVE: the models read PlaybackSource.timeScale every tick. */
class TimeScaleTest {

    @After fun reset() { PlaybackSource.timeScale = 1.0 }

    private fun northRoute(meters: Double): RouteResult {
        val pts = (0..10).map { LatLng(33.45 + it * (meters / 10.0) / 111_320.0, -112.07) }
        return RouteResult(pts, meters, meters / 10.0)
    }

    @Test fun `live time scale speeds up a route ten times`() = runTest {
        val params = MotionParams(avgSpeedMps = 10.0, realism = Realism.CONSTANT, seed = 3L)
        PlaybackSource.timeScale = 1.0
        val normal = MotionModel(northRoute(1000.0), params).fixes().toList().size
        PlaybackSource.timeScale = 10.0
        val fast = MotionModel(northRoute(1000.0), params).fixes().toList().size
        // ~500 ticks at 1x, ~50 at 10x (± a couple for the final point)
        assertTrue("normal=$normal fast=$fast", normal in 450..560)
        assertTrue("normal=$normal fast=$fast", fast in 40..70)
    }

    @Test fun `changing the scale mid-flight takes effect immediately`() = runTest {
        PlaybackSource.timeScale = 1.0
        var ticks = 0
        FlightModel(LatLng(33.43, -112.01), LatLng(33.94, -118.41), FlightParams(updateHz = 2, timeScale = 20.0))
            .fixes().collect {
                ticks++
                if (ticks == 20) PlaybackSource.timeScale = 20.0   // 20 × 20 = 400× from here on
            }
        // At a flat 20x this flight needs roughly 240 ticks; the mid-flight boost cuts it well below that.
        assertTrue("ticks=$ticks", ticks < 120)
    }

    @Test fun `itinerary dwell honours the live scale`() = runTest {
        PlaybackSource.timeScale = 1.0
        val a = Fix(33.5, -112.0, 0f, 0f, 4f)
        val stop = ItineraryStop("Office", LatLng(33.5, -112.0), dwellMinutes = 10)
        val legs = listOf(kotlinx.coroutines.flow.flowOf(a) to stop)
        PlaybackSource.timeScale = 600.0        // 10 minutes of stay in ~1 s of real time
        val fixes = ItineraryModel.play(legs).toList()
        // 1 s real time at 5 Hz = ~5 dwell fixes (+1 leg fix)
        assertTrue("fixes=${fixes.size}", fixes.size in 4..12)
        assertEquals(1f, fixes.last().progress, 0.25f)
    }
}
