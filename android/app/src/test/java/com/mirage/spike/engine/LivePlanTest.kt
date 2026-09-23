package com.mirage.spike.engine

import com.mirage.spike.MockState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LivePlanTest {
    private val a = LatLng(33.0, -112.0)
    private val b = LatLng(33.1, -112.1)
    private fun stop(name: String, p: LatLng = b, minutes: Int = 0) = ItineraryStop(name, p, minutes)
    @After fun reset() { LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.consumeSkip(); PlaybackSource.timeScale = 1.0; MockState.reset() }
    @Test fun `draft edits do not mutate active stops`() {
        val draft = mutableListOf(stop("One"), stop("Two"))
        val plan = LivePlan("Trip", a, draft, { _, _ -> error("unused") })
        draft.clear()
        assertEquals(2, plan.view().stops.size)
    }
    @Test fun `stable identities survive reordering and upcoming stay changes`() {
        val plan = LivePlan("Trip", a, listOf(stop("One"), stop("Two")), { _, _ -> error("unused") })
        val id = plan.view().stops[1].id
        assertTrue(plan.move(id, -1))
        assertTrue(plan.setStay(id, 45))
        assertEquals("Two", plan.view().stops[0].stop.name)
        assertEquals(45, plan.view().stops[0].stop.dwellMinutes)
    }
    @Test fun `appending after arrival starts a new leg and completed stop cannot be removed`() = runTest {
        val routed = mutableListOf<String>()
        val plan = LivePlan("Trip", a, listOf(stop("One")), { from, target ->
            routed += target.name
            PreparedLeg(flow { emit(Fix(target.point.lat, target.point.lng, 0f, 0f, 4f)) }, listOf(from, target.point))
        })
        val playback = launch { plan.fixes().collect {} }
        delay(1000)
        assertEquals(ActivityKind.HOLDING, plan.view().activity)
        assertFalse(plan.remove(plan.view().stops[0].id))
        plan.append(stop("Two"))
        delay(1000)
        assertEquals(listOf("One", "Two"), routed)
        playback.cancelAndJoin()
    }
    @Test fun `routing delay still emits holding fixes`() = runTest {
        var fixes = 0
        val plan = LivePlan("Trip", a, listOf(stop("One")), { from, to ->
            delay(2000)
            PreparedLeg(flow { emit(Fix(to.point.lat, to.point.lng, 0f, 0f, 4f)) }, listOf(from, to.point))
        })
        val playback = launch { plan.fixes().collect { fixes++ } }
        delay(1000)
        assertTrue(fixes >= 4)
        assertEquals(ActivityKind.ROUTING, plan.view().activity)
        playback.cancelAndJoin()
    }
    @Test fun `extending a stay changes the executing timer`() = runTest {
        val plan = LivePlan("Trip", a, listOf(stop("One", minutes = 1)), { from, to ->
            PreparedLeg(flow { emit(Fix(to.point.lat, to.point.lng, 0f, 0f, 4f)) }, listOf(from, to.point))
        })
        val playback = launch { plan.fixes().collect {} }
        delay(1000)
        val before = plan.view().remainingStaySeconds
        assertTrue(plan.extendStay(15))
        assertEquals(before + 900, plan.view().remainingStaySeconds)
        playback.cancelAndJoin()
    }
}
