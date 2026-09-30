package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSaveTest {
    private val a=LatLng(33.5,-112.0)
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()); LiveSession.clear(); MockState.update { it.copy(running=true) } }
    @After fun after() { LiveSession.clear(); MockState.reset(); Dispatchers.resetMain() }
    private fun trip() = LivePlan("Trip",a,listOf(ItineraryStop("First",a,30),ItineraryStop("Second",a,15)), { _,s -> PreparedLeg(flowOf(Fix(s.point.lat,s.point.lng,0f,0f,4f)),listOf(a)) })
    @Test fun saveThenAddThenUpdateRetainsIdAndSurvivesReload() {
        val store=InMemoryScenarioStore(); val vm=MirageViewModel();vm.attachStore(store)
        val plan=trip();LiveSession.activate(plan)
        assertTrue(vm.saveActiveScenario("Tuesday")); val id=plan.view().savedId
        assertFalse(plan.view().dirty)
        plan.append(ItineraryStop("Third",a,7));assertTrue(plan.view().dirty)
        assertTrue(vm.saveActiveScenario("Tuesday",true));assertEquals(id,store.load().single().id)
        assertEquals(listOf("First","Second","Third"),store.load().single().stops.map { it.name })
        assertFalse(plan.view().dirty)
        val next=MirageViewModel();next.attachStore(store);assertEquals(3,next.savedScenarios.single().stops.size)
    }
    @Test fun saveAsNewPreservesOriginalAndRejectsDuplicateName() {
        val store=InMemoryScenarioStore();val vm=MirageViewModel();vm.attachStore(store);val plan=trip();LiveSession.activate(plan)
        assertTrue(vm.saveActiveScenario("Original")); val original=store.load().single()
        plan.append(ItineraryStop("Third",a,7))
        assertFalse(vm.saveActiveScenario("Original"));assertTrue(plan.view().dirty)
        assertTrue(vm.saveActiveScenario("Copy"));assertEquals(original,store.load().first { it.id==original.id })
        assertEquals(2,store.load().size)
    }
    @Test fun elapsedStayDoesNotMarkSavedTripDirtyAndEditsDo() = runTest {
        val plan=trip();val job=launch { plan.fixes().collect {} };advanceTimeBy(1000)
        plan.markSaved("id","Name",plan.stopsForSave());val stay=plan.view().remainingStaySeconds
        advanceTimeBy(1000);assertTrue(plan.view().remainingStaySeconds<stay);assertFalse(plan.view().dirty)
        assertTrue(plan.setStay(plan.view().stops[1].id,45));assertTrue(plan.view().dirty)
        job.cancelAndJoin()
    }
    @Test fun failedPersistenceDoesNotMarkTripSaved() {
        val store=object:ScenarioStore { override fun load()=emptyList<SavedScenario>();override fun save(list:List<SavedScenario>){ error("disk full") } }
        val vm=MirageViewModel();vm.attachStore(store);val plan=trip();LiveSession.activate(plan)
        assertFalse(vm.saveActiveScenario("Test"));assertTrue(plan.view().dirty);assertTrue(vm.savedScenarios.isEmpty())
    }
    @Test fun modelCannotInventDestinationNamesFromVagueReferences() {
        assertFalse(VoiceIntent("add_saved","Home").groundedIn("Take me there"))
        assertTrue(VoiceIntent("add_place","Moxy Scottsdale").groundedIn("Please add the Moxy in Scottsdale"))
        assertTrue(VoiceIntent("add_saved","Home").groundedIn("After this take me to my saved home location"))
    }
    @Test fun strictVoiceIntentRejectsUnsupportedActionsAndInvalidDurations() {
        fun json(action:String,minutes:Int)= """{"action":"$action","target":"","placement":"NEXT","minutes":$minutes,"position":0}"""
        assertEquals(20,VoiceIntent.parse(json("extend",20)).minutes)
        for(text in listOf(json("delete_all",0),json("stay",-1),json("stay",1441),json("stay",0))) assertTrue(runCatching { VoiceIntent.parse(text) }.isFailure)
    }
}
