package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerReviewControllerTest {
    private lateinit var disk: MemoryPlannerDraftStorage
    private val home = LatLng(33.5,-112.0)
    private val other = LatLng(33.6,-112.1)
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null
        disk = MemoryPlannerDraftStorage(); InputDraftStore.useStorageForTest(disk)
    }
    @After fun cleanup() {
        InputDraftStore.useStorageForTest(null); Conversation.resetPlanningSession()
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; Dispatchers.resetMain()
    }
    private fun saved(id: String = "home", name: String = "Home") = SavedScenario(id,name,"SNAP",0,false,null,"",home,name,
        TravelMode.WALK,emptyMap(),Realism.REALISTIC,null,emptyList(),destAddress="Stored address",destPlaceId="original-id",
        aliases=listOf("my house"),defaultStayMinutes=90,arrivalActivity=ArrivalActivity.TABLE,entrance=other,stayUntilLeave=true)
    private fun model(interpreter: suspend (String,String)->PlanInterpretation,
        search: suspend (ApiConfig,String,LatLng?)->List<PlaceHit> = {_,_,_->error("Unexpected network lookup")}) =
        MirageViewModel(interpreter,search).apply {
            configureApi(ApiConfig.NONE); attachStore(InMemoryScenarioStore()); choosePlanMode(PlanMode.ITINERARY)
            setStartPoint(home,"Origin"); savedScenarios.add(saved())
        }

    @Test fun ambiguousFirstPlaceRetainsEveryLaterStopAndAppliesOnceWithUndo() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val cafeA = PlaceHit(other,"Cafe A","First city","a")
        val cafeB = PlaceHit(home,"Cafe B","Second city","b")
        val searched = mutableListOf<String>()
        val vm = model({_,_->PlanInterpretation(listOf(PlanEdit("add_stop",place="Cafe",minutes=45),
            PlanEdit("add_stop",place="saved:home",minutes=60),PlanEdit("set_departure",value="2027-01-05T17:00")))},
            {_,query,_->searched+=query; listOf(cafeA,cafeB)})
        vm.configureApi(ApiConfig("","fixture-key",""))
        val before = vm.capturePlanner()
        vm.planning.propose("Cafe, then Home for an hour; leave at five tomorrow",Field.END)
        advanceUntilIdle()
        val pending = vm.planning.state.value
        assertEquals(2,pending.candidate!!.preview.size); assertEquals(2,pending.choices.size)
        assertFalse(pending.canConfirm); assertEquals(before,vm.capturePlanner())
        vm.planning.choose(cafeB); advanceUntilIdle()
        assertTrue(vm.planning.state.value.canConfirm); assertEquals(listOf("Cafe"),searched)
        assertEquals(before,vm.capturePlanner()); assertTrue(vm.planning.confirm())
        assertEquals(listOf("Cafe B","Home"),vm.stops.map { it.name }); assertEquals(60,vm.stops.last().dwellMinutes)
        assertNull(vm.dest); assertEquals("",vm.destName)
        assertFalse(vm.stops.last().stayUntilLeave); assertEquals("original-id",vm.stops.last().placeId)
        assertFalse(MockState.status.value.running); assertNull(PlaybackSource.current)
        assertFalse(vm.planning.confirm()); assertNull(InputDraftStore.read("planner.review"))
        vm.undoDraftStops(); assertEquals(before,vm.capturePlanner())
    }

    @Test fun clarificationRetainsPendingPlanAndContextAcrossProcessRestore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contexts = mutableListOf<String>()
        val vm = model({ text,context -> contexts+=context
            if (text == "first") PlanInterpretation(listOf(PlanEdit("add_stop",place="my house")))
            else PlanInterpretation(emptyList(),"What departure time did you mean?") })
        vm.planning.propose("first",Field.END); advanceUntilIdle()
        vm.planning.propose("leave in the morning",Field.START); advanceUntilIdle()
        assertEquals("Home",vm.planning.state.value.candidate!!.preview.single().label)
        assertTrue(contexts.last().contains("saved_places")); assertTrue(contexts.last().contains("my house"))
        assertTrue(contexts.last().contains("timezone")); assertTrue(contexts.last().contains("earlier_instructions"))
        assertEquals("stop:1",org.json.JSONObject(contexts.last()).getString("last_mentioned_target"))
        val before = vm.capturePlanner()
        assertTrue(TripRecovery.savePlannerIfCurrent(before,before.draftGeneration))
        InputDraftStore.useStorageForTest(disk)
        val restored = model({_,_->error("Recovery must not interpret automatically")})
        assertTrue(restored.restorePlanner(TripRecovery.plannerSnapshot()!!))
        restored.planning.restorePending()
        val review = restored.planning.state.value
        assertEquals("leave in the morning",review.input); assertEquals("What departure time did you mean?",review.question)
        assertEquals(vm.planning.state.value.candidate,review.candidate); assertFalse(review.canConfirm)
        assertTrue(restored.stops.isEmpty()); assertFalse(restored.planning.confirm())
    }

    @Test fun correctionDuringAmbiguityKeepsLaterStopsWithoutDuplicatingThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model({text,_->if(text=="first") PlanInterpretation(listOf(PlanEdit("add_stop",place="Cafe"),PlanEdit("add_stop",place="saved:home")))
            else PlanInterpretation(listOf(PlanEdit("remove_stop",target="stop:1"),PlanEdit("set_stay",target="stop:1",minutes=35)))},
            {_,_,_->listOf(PlaceHit(home,"Cafe one"),PlaceHit(other,"Cafe two"))})
        vm.configureApi(ApiConfig("","fixture-key",""))
        vm.planning.propose("first",Field.END); advanceUntilIdle()
        val retainedId = vm.planning.state.value.candidate!!.preview.last().id
        vm.planning.propose("skip the cafe, thirty five minutes at home",Field.END); advanceUntilIdle()
        val view = vm.planning.state.value
        assertTrue(view.canConfirm); assertEquals(retainedId,view.candidate!!.preview.single().id)
        assertEquals(35,view.candidate.preview.single().stayMinutes)
        assertTrue(vm.planning.confirm()); assertEquals(1,vm.stops.size)
    }

    @Test fun lateUncancellableInterpretationCannotRestoreAnOldSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model({_,_->withContext(NonCancellable) { delay(100); PlanInterpretation(listOf(PlanEdit("add_stop",place="saved:home"))) }})
        vm.planning.propose("go home",Field.END); runCurrent()
        assertTrue(vm.planning.state.value.busy); assertTrue(vm.startNewItinerary())
        advanceUntilIdle()
        assertTrue(vm.stops.isEmpty()); assertNull(vm.planning.state.value.candidate)
        assertNull(InputDraftStore.read("planner.review")); assertFalse(vm.planning.confirm())
    }

    @Test fun manualPlanEditAndSavedRecordChangeBothBlockStaleConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model({_,_->PlanInterpretation(listOf(PlanEdit("add_stop",place="saved:home")))})
        vm.planning.propose("home",Field.END); advanceUntilIdle()
        vm.departureMillis=2000L
        assertFalse(vm.planning.confirm()); assertTrue(vm.stops.isEmpty()); assertEquals(2000L,vm.departureMillis)
        vm.planning.propose("home",Field.END); advanceUntilIdle()
        vm.savedScenarios[0]=vm.savedScenarios[0].copy(dest=other)
        assertFalse(vm.planning.confirm()); assertTrue(vm.stops.isEmpty())
    }

    @Test fun duplicateSavedAliasRequiresExplicitChoiceEvenWithoutMapsKey() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model({_,_->PlanInterpretation(listOf(PlanEdit("add_stop",place="my house")))})
        vm.savedScenarios.add(saved("second","Other Home").copy(dest=other))
        vm.planning.propose("my house",Field.END); advanceUntilIdle()
        val view=vm.planning.state.value
        assertEquals(2,view.choices.size); assertFalse(view.canConfirm); assertTrue(vm.stops.isEmpty())
        vm.planning.choose(view.choices.last()); advanceUntilIdle()
        assertTrue(vm.planning.confirm()); assertEquals("Other Home",vm.stops.single().name)
    }

    @Test fun remoteFailureRetainsCompleteLogicalPlanAndRawInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = model({_,_->PlanInterpretation(listOf(PlanEdit("add_stop",place="Cafe"),PlanEdit("add_stop",place="saved:home")))},
            {_,_,_->throw IllegalStateException("provider failure")})
        vm.configureApi(ApiConfig("","fixture-key",""))
        vm.planning.propose("cafe then home",Field.END); advanceUntilIdle()
        assertEquals(2,vm.planning.state.value.candidate!!.preview.size)
        assertEquals("cafe then home",vm.planning.state.value.input); assertFalse(vm.planning.state.value.busy)
        assertNotNull(InputDraftStore.read("planner.review")); assertTrue(vm.stops.isEmpty())
    }
}
