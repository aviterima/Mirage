package com.mirage.spike.engine

import org.junit.Assert.*
import org.junit.Test

class ArrivalModelTest {
    private val road = Fix(33.5,-112.0,12f,0f,4f)
    @Test fun `walk reaches interior pin without teleporting or overshooting`() {
        val pin = Geo.offset(LatLng(road.lat,road.lng),20.0,10.0)
        val walk = ArrivalModel(road,pin)
        assertTrue(walk.canWalk)
        var previous=road
        var count=0
        while(!walk.complete) {
            val fix=walk.next(0.2)
            assertTrue(Geo.haversine(LatLng(previous.lat,previous.lng),LatLng(fix.lat,fix.lng)) <= 0.241)
            previous=fix;count++
            assertTrue(count<200)
        }
        assertEquals(pin.lat,previous.lat,0.00000001)
        assertEquals(pin.lng,previous.lng,0.00000001)
        assertEquals(0f,previous.speedMps)
    }
    @Test fun `bad or distant pins do not invent an indoor journey`() {
        for(pin in listOf(LatLng(Double.NaN,0.0),LatLng(34.0,-112.0))) {
            val walk=ArrivalModel(road,pin)
            assertFalse(walk.canWalk)
            assertTrue(walk.complete)
            assertEquals(road.lat,walk.next(0.2).lat,0.0)
        }
    }
    @Test fun `seated stay remains at the selected table for an hour`() {
        val dwell=DwellModel(road,0.0)
        repeat(18000) {
            val fix=dwell.next(0.2)
            assertEquals(road.lat,fix.lat,0.0)
            assertEquals(road.lng,fix.lng,0.0)
            assertEquals(0f,fix.speedMps)
        }
    }
}
