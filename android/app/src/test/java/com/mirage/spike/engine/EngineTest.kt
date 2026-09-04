package com.mirage.spike.engine

import com.mirage.spike.MockState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** A straight ~1 km line north from downtown Phoenix, 11 points. */
private fun straightKm(): RouteResult {
    val pts = (0..10).map { LatLng(33.4484 + it * 0.0009, -112.0740) }
    var d = 0.0
    for (i in 1 until pts.size) d += Geo.haversine(pts[i - 1], pts[i])
    return RouteResult(pts, d, 60.0)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MotionModelTest {

    @Test
    fun `constant drive reaches the end with monotonic progress and no NaN`() = runTest {
        val route = straightKm()
        val fixes = MotionModel(route, MotionParams(avgSpeedMps = 10.0, realism = Realism.CONSTANT, seed = 1L)).fixes().toList()
        assertTrue("should emit many fixes", fixes.size > 100)
        val last = fixes.last()
        assertEquals(route.points.last().lat, last.lat, 1e-9)
        assertEquals(route.points.last().lng, last.lng, 1e-9)
        assertEquals(1f, last.progress, 1e-6f)
        var prev = -1f
        for (f in fixes) {
            assertFalse(f.lat.isNaN() || f.lng.isNaN() || f.speedMps.isNaN() || f.bearingDeg.isNaN())
            assertTrue("progress within 0..1", f.progress in 0f..1f)
            assertTrue("progress never goes backwards", f.progress >= prev - 1e-6f)
            prev = f.progress
        }
        // 1 km at 10 m/s at 5 Hz ≈ 500 ticks (constant profile: no stops).
        assertTrue("tick count ${fixes.size}", fixes.size in 440..560)
    }

    @Test
    fun `consecutive fixes are continuous (no teleport)`() = runTest {
        val route = straightKm()
        val fixes = MotionModel(route, MotionParams(avgSpeedMps = 20.0, realism = Realism.BUSY, seed = 3L)).fixes().toList()
        for (i in 1 until fixes.size) {
            val step = Geo.haversine(LatLng(fixes[i - 1].lat, fixes[i - 1].lng), LatLng(fixes[i].lat, fixes[i].lng))
            assertTrue("step $step m too large at $i", step < 20.0 * 1.8 * 0.2 + 0.5)
        }
    }

    @Test
    fun `fast-forward covers the route in proportionally fewer ticks`() = runTest {
        val route = straightKm()
        val slow = MotionModel(route, MotionParams(avgSpeedMps = 10.0, realism = Realism.CONSTANT, seed = 1L)).fixes().toList().size
        val fast = MotionModel(route, MotionParams(avgSpeedMps = 10.0, realism = Realism.CONSTANT, seed = 1L, timeScale = 10.0)).fixes().toList().size
        assertTrue("slow=$slow fast=$fast", fast * 6 < slow)
    }

    @Test
    fun `a zero average speed still terminates`() = runTest {
        val fixes = MotionModel(straightKm(), MotionParams(avgSpeedMps = 0.0, realism = Realism.CONSTANT, seed = 1L, timeScale = 100.0)).fixes().toList()
        assertEquals(1f, fixes.last().progress, 1e-6f)
    }
}

class DwellModelTest {
    @Test
    fun `holding a point stays inside the building, walks at walking pace, never jumps`() {
        val anchor = Fix(33.4484, -112.0740, 0f, 0f, 4f)
        val m = DwellModel(anchor, 12.0, Random(7))
        var prev = LatLng(anchor.lat, anchor.lng)
        var walked = false
        repeat(5 * 60 * 40) { // 40 minutes at 5 Hz
            val f = m.next(0.2)
            val p = LatLng(f.lat, f.lng)
            assertTrue("left the building: ${Geo.haversine(LatLng(anchor.lat, anchor.lng), p)} m", Geo.haversine(LatLng(anchor.lat, anchor.lng), p) < 20.0)
            assertTrue("jump ${Geo.haversine(prev, p)} m", Geo.haversine(prev, p) < 5.0)
            assertTrue("walking pace", f.speedMps in 0f..1.9f)
            if (f.speedMps > 0f) walked = true
            prev = p
        }
        assertTrue("should have walked to the coffee station at least once in 40 min", walked)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FlightModelTest {
    @Test
    fun `flight climbs, cruises, descends and lands exactly at the destination`() = runTest {
        val phx = LatLng(33.4343, -112.0116); val lax = LatLng(33.9416, -118.4085)
        val fm = FlightModel(phx, lax, FlightParams(updateHz = 2, timeScale = 50.0))
        val fixes = fm.fixes().toList()
        assertTrue(fixes.size > 20)
        assertEquals(0.0, fixes.first().altitudeM, 1.0)
        assertTrue("reaches cruise altitude", fixes.maxOf { it.altitudeM } > 10_000.0)
        assertEquals(0.0, fixes.last().altitudeM, 1e-6)
        assertEquals(lax.lat, fixes.last().lat, 1e-9)
        assertEquals(lax.lng, fixes.last().lng, 1e-9)
        assertEquals(1f, fixes.last().progress, 1e-6f)
        assertEquals(65, fm.pathPoints.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ItineraryModelTest {
    @Test
    fun `dwell anchors where the leg actually ended, not at the raw stop point`() = runTest {
        val roadEnd = LatLng(33.5000, -112.0000)
        val searched = LatLng(33.6000, -112.1000) // far from the road-snapped endpoint
        val leg = flow { emit(Fix(33.4, -112.0, 10f, 0f, 4f)); emit(Fix(roadEnd.lat, roadEnd.lng, 0f, 0f, 4f)) }
        val stop = ItineraryStop("Office", searched, dwellMinutes = 1)
        val fixes = ItineraryModel.play(listOf(leg to stop), updateHz = 5, timeScale = 60.0).toList()
        val dwell = fixes.drop(2)
        assertTrue("should dwell", dwell.isNotEmpty())
        for (f in dwell) {
            assertTrue("dwell fix drifted to the searched point", Geo.haversine(roadEnd, LatLng(f.lat, f.lng)) < 30.0)
            assertTrue(f.progress in 0f..1f)
        }
        assertTrue(MockState.status.value.stepLabel.contains("complete"))
    }
}

class PolylineCodecTest {
    @Test
    fun `decodes Google's documented example`() {
        val pts = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5); assertEquals(-120.95, pts[1].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test(expected = RouteException::class)
    fun `a truncated polyline is a RouteException, not a crash`() {
        PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq")
    }
}
