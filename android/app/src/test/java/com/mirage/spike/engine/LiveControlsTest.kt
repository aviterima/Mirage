package com.mirage.spike.engine

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun seg(instr: String, meters: Double, seconds: Double, from: LatLng, to: LatLng, n: Int = 8, maneuver: String = "") =
    RouteSegment((0..n).map { LatLng(from.lat + (to.lat - from.lat) * it / n, from.lng + (to.lng - from.lng) * it / n) }, meters, seconds, null, instr, maneuver)

class DriveModelTest {
    @Before fun reset() { PlaybackSource.timeScale = 1.0; PlaybackSource.speedOverLimitMph = 5.0; PlaybackSource.paused = false }
    @After fun done() { PlaybackSource.timeScale = 1.0; PlaybackSource.speedOverLimitMph = 5.0 }

    @Test fun `posted limits are inferred from road class and timing`() {
        val a = LatLng(33.45, -112.07); val b = LatLng(33.50, -112.07)
        assertEquals(65, DriveModel.limitMph(seg("Merge onto I-10 W", 5000.0, 190.0, a, b)))          // 59 mph avg on an interstate
        assertEquals(35, DriveModel.limitMph(seg("Take the ramp to I-17", 400.0, 40.0, a, b, maneuver = "ramp-right")))
        assertEquals(45, DriveModel.limitMph(seg("Continue onto E Camelback Rd", 2000.0, 110.0, a, b))) // 40 mph avg arterial
        assertEquals(30, DriveModel.limitMph(seg("Turn left onto N 7th St", 800.0, 80.0, a, b)))      // 22 mph avg local
        assertEquals(25, DriveModel.limitMph(seg("Turn right onto W Elm Ln", 300.0, 45.0, a, b)))     // 15 mph avg residential
    }

    private fun city(): RouteResult {
        val p0 = LatLng(33.4500, -112.0700); val p1 = LatLng(33.4600, -112.0700)
        val p2 = LatLng(33.4600, -112.0550); val p3 = LatLng(33.4700, -112.0550)
        val s1 = seg("Head north on N 7th St", 1110.0, 100.0, p0, p1)           // ~25 mph → 30 limit
        val s2 = seg("Turn right onto E Thomas Rd", 1390.0, 80.0, p1, p2, maneuver = "turn-right") // ~39 mph → 45 limit
        val s3 = seg("Turn left onto N 16th St", 1110.0, 95.0, p2, p3, maneuver = "turn-left")
        val pts = s1.points + s2.points.drop(1) + s3.points.drop(1)
        return RouteResult(pts, 3610.0, 275.0, listOf(s1, s2, s3))
    }

    @Test fun `city drive obeys limit plus five, slows for turns, stops at lights, arrives`() = runTest {
        val route = city()
        val fixes = DriveModel(route, Realism.BUSY, seed = 11L).fixes().toList()
        val end = route.points.last()
        assertEquals(end.lat, fixes.last().lat, 1e-9); assertEquals(end.lng, fixes.last().lng, 1e-9)
        val maxMph = fixes.maxOf { it.speedMps } / 0.44704
        assertTrue("max $maxMph mph", maxMph <= (45 + 5) * 1.08)
        // Stopped somewhere in the middle (a light), not only at the very end.
        val stopsMid = fixes.drop(20).dropLast(5).count { it.speedMps == 0f }
        assertTrue("mid-route zero-speed fixes = $stopsMid", stopsMid >= 40)
        // No teleports: at 5 Hz even 50 mph is ~4.5 m per tick.
        for (i in 1 until fixes.size) {
            val d = Geo.haversine(LatLng(fixes[i - 1].lat, fixes[i - 1].lng), LatLng(fixes[i].lat, fixes[i].lng))
            assertTrue("jump $d m at $i", d < 7.0)
        }
        // With no lights at all (CONSTANT) the drive is shorter.
        val calm = DriveModel(route, Realism.CONSTANT, seed = 11L).fixes().toList()
        assertTrue(calm.size < fixes.size)
        assertFalse(calm.drop(20).dropLast(3).any { it.speedMps == 0f })
    }

    @Test fun `over-limit setting is live`() = runTest {
        val route = city()
        PlaybackSource.speedOverLimitMph = -10.0
        val slow = DriveModel(route, Realism.CONSTANT, seed = 2L).fixes().toList()
        PlaybackSource.speedOverLimitMph = 10.0
        val fast = DriveModel(route, Realism.CONSTANT, seed = 2L).fixes().toList()
        assertTrue("slow=${slow.size} fast=${fast.size}", fast.size < slow.size)
    }
}

class SkipQueueSignalTest {
    @After fun done() { PlaybackSource.clearQueue(); PlaybackSource.signal = Signal.GOOD; PlaybackSource.timeScale = 1.0 }

    @Test fun `skip ahead ends the current leg at once`() = runTest {
        val pts = (0..10).map { LatLng(33.45 + it * 0.0009, -112.07) }
        val route = RouteResult(pts, 1000.0, 100.0)
        var n = 0
        val fixes = MotionModel(route, MotionParams(10.0, Realism.CONSTANT, seed = 1L)).fixes()
            .toList().also { }  // full run for reference
        val skipped = mutableListOf<Fix>()
        MotionModel(route, MotionParams(10.0, Realism.CONSTANT, seed = 1L)).fixes().collect {
            skipped += it; n++
            if (n == 25) PlaybackSource.requestSkip()
        }
        assertTrue("full=${fixes.size} skipped=${skipped.size}", skipped.size < 30 && fixes.size > 400)
        assertEquals(pts.last().lat, skipped.last().lat, 1e-9)
        assertEquals(1f, skipped.last().progress, 1e-6f)
    }

    @Test fun `queued plans come out in order and can be cleared`() {
        PlaybackSource.enqueue(PlaybackSource.Queued(flowOf(), emptyList(), "A", null))
        PlaybackSource.enqueue(PlaybackSource.Queued(flowOf(), emptyList(), "B", null))
        assertEquals(2, PlaybackSource.queueSize())
        assertEquals("A", PlaybackSource.pollQueue()!!.label)
        PlaybackSource.clearQueue()
        assertNull(PlaybackSource.pollQueue())
    }

    @Test fun `signal presets and dropouts`() {
        assertFalse(Signal.GOOD.dropped)
        val d = Signal.URBAN.withDropout(60)
        assertTrue(d.dropped); assertEquals("Urban", d.name); assertEquals(12f, d.accuracyM, 0f)
        assertFalse(Signal.POOR.copy(dropUntilMillis = 1L).dropped)
    }
}
