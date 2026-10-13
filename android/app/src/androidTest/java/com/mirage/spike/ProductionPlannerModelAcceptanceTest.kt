package com.mirage.spike

import android.content.Intent
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Native inference with the real planner's JSON context, pending review and saved-place resolver. */
@ExperimentalLanguageModel
class ProductionPlannerModelAcceptanceTest {
    @Test fun realModelBuildsAndCorrectsAPlanThroughProductionContextWithoutStarting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalLanguageModel.configure(context)
        assertTrue("Experimental build and verified model are required for native planner acceptance", LocalLanguageModel.interpretationEnabled && LocalLanguageModel.ready)
        context.stopService(Intent(context, MockLocationService::class.java))

        val orchard = saved("model-orchard", "Fixture Orchard", "orchard", LatLng(1.1, 1.1))
        val gallery = saved("model-gallery", "Fixture Gallery", "gallery", LatLng(1.2, 1.2))
        var searchCalls = 0
        val vm = withContext(Dispatchers.Main) {
            MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
            InputDraftStore.useStorageForTest(TransientDraftStorage())
            // Keep the default NaturalLanguageInput::interpretPlan. Only external place search is injected.
            MirageViewModel(placeSearch = { _, _, _ ->
                searchCalls++
                error("Saved aliases must resolve without an external place lookup")
            }).apply {
                // A synthetic configured key makes any mistaken fallback reach the failing search stub.
                configureApi(ApiConfig("", "synthetic-unused-key", ""))
                attachStore(InMemoryScenarioStore())
                choosePlanMode(PlanMode.ITINERARY)
                setStartPoint(LatLng(1.0, 1.0), "Fixture Origin")
                savedScenarios.addAll(listOf(orchard, gallery))
            }
        }
        try {
            val before = withContext(Dispatchers.Main) { vm.capturePlanner() }
            val initial = propose(vm, "Visit the orchard for thirty minutes, then the gallery for forty five minutes.")
            assertTrue("The real model must produce a complete initial review", initial.canConfirm)
            assertTrue(initial.question.isBlank()); assertTrue(initial.choices.isEmpty())
            val initialCandidate = requireNotNull(initial.candidate)
            val initialStops = initialCandidate.preview
            assertEquals(listOf(orchard.id, gallery.id), initialStops.map { it.place.saved?.id })
            assertEquals(listOf(30, 45), initialStops.map { it.stayMinutes })
            assertEquals(listOf(before.scenario.travelMode, before.scenario.travelMode), initialStops.map { it.mode })
            assertEquals(before.scenario.start, initialCandidate.origin?.hit?.latLng)
            assertEquals(before.scenario.startName, initialCandidate.origin?.label)
            assertFalse(initialCandidate.originChanged)
            assertEquals(before.scenario.departureMillis, initialCandidate.departureMillis)
            withContext(Dispatchers.Main) {
                assertEquals(before, vm.capturePlanner())
                assertTrue(vm.stops.isEmpty())
                assertFalse(MockState.status.value.running); assertFalse(MockState.status.value.starting)
                assertNull(PlaybackSource.current)
            }

            val corrected = propose(vm, "Make the second stop thirty five minutes instead, keeping both places and the first stay unchanged.")
            assertTrue("The real model must correct the existing pending review", corrected.canConfirm)
            assertTrue(corrected.question.isBlank()); assertTrue(corrected.choices.isEmpty())
            val correctedCandidate = requireNotNull(corrected.candidate)
            val correctedStops = correctedCandidate.preview
            assertEquals(initialStops.map { it.id }, correctedStops.map { it.id })
            assertEquals(listOf(orchard.id, gallery.id), correctedStops.map { it.place.saved?.id })
            assertEquals(listOf(30, 35), correctedStops.map { it.stayMinutes })
            assertEquals(initialStops[0], correctedStops[0])
            assertEquals(initialStops[1].copy(stayMinutes = 35), correctedStops[1])
            assertEquals(initialCandidate.origin, correctedCandidate.origin)
            assertFalse(correctedCandidate.originChanged)
            assertEquals(initialCandidate.departureMillis, correctedCandidate.departureMillis)

            withContext(Dispatchers.Main) {
                assertEquals(before, vm.capturePlanner())
                assertTrue(vm.stops.isEmpty()); assertEquals(0, searchCalls)
                assertFalse(MockState.status.value.running); assertNull(PlaybackSource.current)
                assertTrue(vm.planning.confirm())
                assertFalse(vm.planning.confirm())
                assertEquals(listOf(orchard.name, gallery.name), vm.stops.map { it.name })
                assertEquals(listOf(orchard.dest, gallery.dest), vm.stops.map { it.point })
                assertEquals(listOf(orchard.destPlaceId, gallery.destPlaceId), vm.stops.map { it.placeId })
                assertEquals(listOf(30, 35), vm.stops.map { it.dwellMinutes })
                val applied = vm.capturePlanner()
                assertEquals(before.scenario.start, applied.scenario.start)
                assertEquals(before.scenario.startName, applied.scenario.startName)
                assertEquals(before.scenario.departureMillis, applied.scenario.departureMillis)
                assertFalse(MockState.status.value.running); assertFalse(MockState.status.value.starting)
                assertNull(PlaybackSource.current)
            }
        } finally {
            withContext(Dispatchers.Main) {
                vm.planning.reset()
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                InputDraftStore.useStorageForTest(null)
                InputDraftStore.configure(context)
                MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
            }
        }
    }

    private suspend fun propose(vm: MirageViewModel, instruction: String): PlannerReviewView {
        val began = android.os.SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main) { vm.planning.propose(instruction, Field.END) }
        return withTimeout(110_000) {
            vm.planning.state.first { !it.busy && it.input == instruction }
        }.also {
            // Timing and a synthetic case result only; no request text or context is logged.
            android.util.Log.i("MirageModelTest", "production_planner_review ready=${it.canConfirm} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - began}")
        }
    }

    private fun saved(id: String, name: String, alias: String, point: LatLng) = SavedScenario(
        id, name, "SNAP", 0, false, null, "", point, name, TravelMode.WALK, emptyMap(),
        Realism.REALISTIC, null, emptyList(), destAddress = "Synthetic $name address",
        destPlaceId = "$id-place", aliases = listOf(alias), defaultStayMinutes = 5
    )

    private class TransientDraftStorage : PlannerDraftStorage {
        private val values = mutableMapOf<String, Any>()
        override fun all(): Map<String, *> = values.toMap()
        override fun commit(values: Map<String, Any>, removals: Set<String>): Boolean {
            removals.forEach(this.values::remove)
            this.values.putAll(values)
            return true
        }
    }
}
