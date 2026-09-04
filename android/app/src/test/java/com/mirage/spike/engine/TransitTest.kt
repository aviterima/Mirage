package com.mirage.spike.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransitTest {

    @After fun reset() { PlaybackSource.timeScale = 1.0 }

    private fun line(from: LatLng, to: LatLng, n: Int) =
        (0..n).map { LatLng(from.lat + (to.lat - from.lat) * it / n, from.lng + (to.lng - from.lng) * it / n) }

    @Test fun `walk, wait for the timetable, ride with station halts, arrive on the line`() = runTest {
        val t0 = 1_700_000_000L
        val home = LatLng(33.450, -112.070)
        val stopA = LatLng(33.451, -112.070)   // ~111 m walk
        val stopB = LatLng(33.470, -112.070)   // ~2.1 km ride
        val walk = RouteSegment(line(home, stopA, 2), 111.0, 90.0)
        val ride = RouteSegment(
            line(stopA, stopB, 10), 2110.0, 240.0,
            TransitDetails(TransitVehicle.SUBWAY, "Blue", "Downtown", "A St", "B St",
                departureEpoch = t0 + 120, arrivalEpoch = t0 + 360, departureText = "10:02", arrivalText = "10:06", numStops = 3),
        )
        val route = RouteResult(walk.points + ride.points, 2221.0, 400.0, listOf(walk, ride))
        val fixes = TransitModel(route, now = { t0 }).fixes().toList()

        val end = fixes.last()
        assertEquals(stopB.lat, end.lat, 1e-6); assertEquals(stopB.lng, end.lng, 1e-6)
        // Waited on the platform for the scheduled departure (still, at stop A).
        val waiting = fixes.count { it.speedMps == 0f && Geo.haversine(LatLng(it.lat, it.lng), stopA) < 15 }
        assertTrue("waiting fixes=$waiting", waiting > 60)
        // Never faster than a subway, never a teleport between ticks.
        assertTrue(fixes.all { it.speedMps <= 28.5f })
        for (i in 1 until fixes.size) {
            val d = Geo.haversine(LatLng(fixes[i - 1].lat, fixes[i - 1].lng), LatLng(fixes[i].lat, fixes[i].lng))
            assertTrue("jump of $d m at $i", d < 8.0)
        }
        // Halted at the two intermediate stations (zero speed away from both ends).
        val halts = fixes.count {
            it.speedMps == 0f && Geo.haversine(LatLng(it.lat, it.lng), stopA) > 100 && Geo.haversine(LatLng(it.lat, it.lng), stopB) > 20
        }
        assertTrue("halt fixes=$halts", halts >= 150)
    }

    private val sample = """
    {"status":"OK","routes":[{"overview_polyline":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
      "legs":[{"distance":{"value":5200},"duration":{"value":1500},"steps":[
        {"travel_mode":"WALKING","distance":{"value":300},"duration":{"value":240},"polyline":{"points":"_p~iF~ps|U_ulLnnqC"}},
        {"travel_mode":"TRANSIT","distance":{"value":4600},"duration":{"value":900},"polyline":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
         "transit_details":{"departure_stop":{"name":"Central"},"arrival_stop":{"name":"Airport"},
           "departure_time":{"value":1700000120,"text":"10:02am"},"arrival_time":{"value":1700001020,"text":"10:17am"},
           "headsign":"Airport","num_stops":4,"line":{"name":"Blue Line","short_name":"Blue","vehicle":{"type":"SUBWAY","name":"Subway"}}}},
        {"travel_mode":"WALKING","distance":{"value":300},"duration":{"value":360},"polyline":{"points":"_p~iF~ps|U_ulLnnqC"}}
      ]}]}]}
    """.trimIndent()

    @Test fun `directions transit steps become walk and ride segments`() {
        val r = GoogleDirectionsRouteEngine.parseDirections(sample, transit = true)
        assertEquals(3, r.segments.size)
        assertEquals(null, r.segments[0].transit)
        val td = r.segments[1].transit!!
        assertEquals(TransitVehicle.SUBWAY, td.vehicle)
        assertEquals("Blue", td.line)
        assertEquals("Central", td.fromStop); assertEquals("Airport", td.toStop)
        assertEquals(1700000120L, td.departureEpoch); assertEquals(4, td.numStops)
        assertEquals(5200.0, r.distanceMeters, 0.0); assertEquals(1500.0, r.durationSeconds, 0.0)
        assertEquals(3, r.points.size)
    }

    @Test fun `no transit connection is a clear message`() {
        try {
            GoogleDirectionsRouteEngine.parseDirections("""{"status":"ZERO_RESULTS","routes":[]}""", transit = true)
            fail("expected RouteException")
        } catch (e: RouteException) {
            assertTrue(e.message!!.contains("transit"))
        }
    }
}
