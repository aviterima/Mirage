package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerLoadBoundaryTest {
    private lateinit var disk: MemoryPlannerDraftStorage
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher()); MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
        disk = MemoryPlannerDraftStorage(); InputDraftStore.useStorageForTest(disk)
        FieldSpeech.cancel(); Conversation.resetPlanningSession()
    }
    @After fun cleanup() {
        FieldSpeech.cancel(); Conversation.resetPlanningSession(); InputDraftStore.useStorageForTest(null)
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; Dispatchers.resetMain()
    }
    private fun model() = MirageViewModel(planInterpret = { _, _ ->
        PlanInterpretation(listOf(PlanEdit("set_departure",value="2027-01-05T17:00")))
    }).apply {
        configureApi(ApiConfig.NONE); attachStore(InMemoryScenarioStore())
        choosePlanMode(PlanMode.ITINERARY); setStartPoint(LatLng(33.5,-112.0),"Home")
        setDestPoint(LatLng(33.6,-112.1),"Office"); saveScenario("Workday")
    }

    @Test fun loadingTheSamePlanInvalidatesOldTextReviewAndUndoWithoutStarting() {
        val vm = model(); val saved = vm.savedScenarios.single()
        vm.planning.propose("leave at five",Field.END)
        assertTrue(vm.planning.state.value.canConfirm)
        val before = vm.capturePlanner(); val inputGeneration = InputDraftStore.generation
        assertTrue(InputDraftStore.write("planner.entry.end","unfinished same-label instruction"))
        assertTrue(InputDraftStore.write("saved.other.name","Unrelated edit"))
        val other = FieldSpeech.begin("saved.other.name"); FieldSpeech.finish(other,"Unrelated edit")
        val field = FieldSpeech.begin("planner.departure"); FieldSpeech.partial(field,"tomorrow")
        val speech = PlannerSpeech.begin(Field.END); PlannerSpeech.partial(speech,"then home")
        vm.setDwell(0,65); assertTrue(vm.draftUndoAvailable)
        assertTrue(vm.loadScenario(saved))
        assertTrue(vm.draftGeneration > before.draftGeneration)
        assertEquals("Home",vm.startName); assertEquals("Office",vm.stops.single().name)
        assertEquals(saved.stops,vm.stops.map { it.toSavedStop() }); assertEquals("Workday",vm.draftSavedName)
        assertNull(vm.planning.state.value.candidate); assertFalse(vm.planning.confirm())
        assertFalse(PlannerSpeech.state.value.active); assertNull(PlannerSpeech.state.value.target)
        assertFalse(FieldSpeech.state.value.active); assertTrue(FieldSpeech.results.value.containsKey("saved.other.name"))
        assertNull(InputDraftStore.read("planner.review")); assertNull(InputDraftStore.read("planner.entry.end"))
        assertEquals("Unrelated edit",InputDraftStore.read("saved.other.name"))
        assertFalse(InputDraftStore.saveIfCurrent("planner.entry.end","late callback",inputGeneration))
        assertFalse(vm.applyPlannerProposal(before,before)); assertFalse(vm.draftUndoAvailable)
        assertEquals(saved,vm.savedScenarios.single().copy(lastUsedAt=saved.lastUsedAt))
        assertNull(PlaybackSource.current); assertFalse(MockState.status.value.running)
    }

    @Test fun failedLoadKeepsEveryCurrentDraftAndDoesNotStartOldPlanThroughAutomation() {
        val vm = model(); val saved = vm.savedScenarios.single()
        vm.planning.propose("leave at five",Field.END)
        val before = vm.capturePlanner(); val library = vm.savedScenarios.toList()
        val review = vm.planning.state.value; val input = InputDraftStore.read("planner.review")
        val speech = PlannerSpeech.begin(Field.END); PlannerSpeech.partial(speech,"unfinished")
        disk.fail = true
        assertFalse(vm.loadScenario(saved))
        assertEquals(before,vm.capturePlanner()); assertEquals(library,vm.savedScenarios.toList())
        assertEquals(review,vm.planning.state.value); assertEquals(input,InputDraftStore.read("planner.review"))
        assertTrue(PlannerSpeech.isActive(speech)); assertNotNull(vm.error)
        vm.automationToken="fixture-token"
        var starts = 0
        val result = vm.runCommand("plan",mapOf("token" to "fixture-token","name" to saved.name),{starts++},{})
        assertTrue(result.contains("Could not load")); assertEquals(0,starts)
        assertEquals(before,vm.capturePlanner()); assertEquals(library,vm.savedScenarios.toList())
        assertNull(PlaybackSource.current)
    }
}
