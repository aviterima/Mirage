package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineInterpretationBoundaryTest {
    @Before fun setup() {
        assertFalse(LocalLanguageModel.interpretationEnabled)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; PlaybackSource.clearQueue()
        Conversation.resetPlanningSession()
    }

    @After fun cleanup() {
        Conversation.resetPlanningSession()
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; PlaybackSource.clearQueue()
        Dispatchers.resetMain()
    }

    @Test fun disabledCommandsAndConfirmationCannotEditSaveQueueOrStart() = runTest {
        var modelCalls = 0
        var searches = 0
        val store = InMemoryScenarioStore()
        val vm = MirageViewModel(planInterpret = { _, _ -> modelCalls++; error("No offline interpretation") },
            placeSearch = { _, _, _ -> searches++; error("No command-driven place lookup") }).apply {
            attachStore(store); setStartPoint(LatLng(1.0, 1.0), "Fixture origin")
            choosePlanMode(PlanMode.ITINERARY)
            pickResolvedPlace(PlaceHit(LatLng(1.1, 1.1), "Fixture stop"), Field.END)
        }
        val before = vm.capturePlanner()
        val savedBefore = store.load()
        SmartVoice.bindPlanner(vm)
        listOf("drive to Home", "add Home", "save this trip as Friday", "yes", "start simulation").forEach { words ->
            assertFalse(SmartVoice.handlePlanning(words, Field.END, PlanMode.ITINERARY, false))
            assertFalse(SmartVoice.handle(words, false))
            Conversation.submitPlanner(words, Field.END, PlanMode.ITINERARY)
        }
        advanceUntilIdle()
        assertEquals(before, vm.capturePlanner()); assertEquals(savedBefore, store.load())
        assertEquals(0, modelCalls); assertEquals(0, searches)
        assertFalse(SmartVoice.needsReply()); assertFalse(SmartVoice.hasChoices())
        assertFalse(Conversation.state.value.busy); assertTrue(Conversation.state.value.choices.isEmpty())
        assertFalse(MockState.status.value.running); assertFalse(MockState.status.value.starting)
        assertNull(PlaybackSource.current); assertNull(LiveSession.plan)
    }

    @Test fun disabledNativeEntryRejectsBeforeModelFilesOrEngineAreNeeded() = runTest {
        val calls: List<suspend () -> String> = listOf(
            { LocalLanguageModel.interpret("Synthetic system", "Synthetic request") },
            { LocalLanguageModel.interpretStructured("Synthetic system", "Synthetic request", "field") },
            { LocalLanguageModel.interpretStructured("Synthetic system", "Synthetic request", "plan") },
        )
        calls.forEach { call ->
            val failure = runCatching { call() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Use the editable fields and itinerary controls in this version.", failure?.message)
        }
    }

    @Test fun startingNewPlanRejectsACompletedOldManualSearch() = runTest {
        val lookup = CompletableDeferred<List<PlaceHit>>()
        val vm = MirageViewModel(placeSearch = { _, _, _ -> withContext(NonCancellable) { lookup.await() } }).apply {
            configureApi(ApiConfig(null, "synthetic-key", "offline-test"))
            setStartPoint(LatLng(1.0, 1.0), "Fixture origin")
        }
        vm.search("Old manual query") {}
        assertTrue(vm.suggestBusy)
        assertTrue(vm.startNewItinerary())
        lookup.complete(listOf(PlaceHit(LatLng(1.1, 1.1), "Old result")))
        advanceUntilIdle()
        assertTrue(vm.suggestions.isEmpty()); assertTrue(vm.savedPlaceSuggestions.isEmpty())
        assertFalse(vm.suggestBusy); assertTrue(vm.stops.isEmpty()); assertNull(vm.dest)
        assertNull(vm.error); assertNull(PlaybackSource.current); assertFalse(MockState.status.value.running)
    }
}
