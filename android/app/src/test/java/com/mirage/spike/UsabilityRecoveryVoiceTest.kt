package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class UsabilityRecoveryVoiceTest {
    private val origin=LatLng(33.0,-112.0)
    private fun stop(name: String, lat: Double)=ItineraryStop(name,LatLng(lat,-112.0),30,TravelMode.WALK,3f,address="$name address",placeId="$name-id")
    private fun scenario()=SavedScenario("id","Day","ITINERARY",1,false,origin,"Home",null,"",TravelMode.WALK,emptyMap(),Realism.REALISTIC,null,
        listOf(stop("Office",33.1).toSavedStop()),favorite=true,aliases=listOf("work"))
    @Before fun before(){Dispatchers.setMain(UnconfinedTestDispatcher());MockState.reset();LiveSession.clear()}
    @After fun after(){Dispatchers.resetMain();LiveSession.clear()}
    @Test fun backupRoundTripPreservesMetadataAndContainsNoCredentials(){
        val item=scenario();val json=BackupCodec.encode(listOf(item))
        assertEquals(listOf(item),BackupCodec.decode(json));assertFalse(json.contains("api_key"));assertFalse(json.contains("install_id"))
    }
    @Test fun malformedBackupCannotPartiallyImport(){
        val json=BackupCodec.encode(listOf(scenario())).replace("33.1","133.1")
        assertThrows(IllegalArgumentException::class.java){BackupCodec.decode(json)}
        assertThrows(IllegalArgumentException::class.java){BackupCodec.decode(BackupCodec.encode(listOf(scenario())).replace("\"version\": 1","\"version\": 99"))}
    }
    @Test fun importsKeepOriginalAndRenameConflicts(){
        val store=InMemoryScenarioStore();store.save(listOf(scenario()))
        val vm=MirageViewModel().apply{attachStore(store)}
        assertTrue(vm.importScenarios(listOf(scenario().copy(id="different"))))
        assertEquals(listOf("Day","Day (2)"),vm.savedScenarios.map{it.name})
        assertTrue(vm.undoLibrary());assertEquals(listOf(scenario()),store.load())
    }
    @Test fun failedDeleteDoesNotChangeCollection(){
        val store=object:ScenarioStore {override fun load()=listOf(scenario());override fun save(list:List<SavedScenario>){error("disk full")}}
        val vm=MirageViewModel().apply{attachStore(store)}
        assertFalse(vm.deleteScenario("id"));assertEquals(1,vm.savedScenarios.size);assertNotNull(vm.error)
    }
    @Test fun namedDraftUpdatePreservesIdentityAndFavorite(){
        val store=InMemoryScenarioStore();store.save(listOf(scenario()))
        val vm=MirageViewModel().apply{configureApi(ApiConfig(null,"","test"));attachStore(store);loadScenario(scenario())}
        vm.setDwell(0,75);assertTrue(vm.saveScenario("Day",true))
        assertEquals(1,store.load().size);assertEquals("id",store.load().single().id);assertTrue(store.load().single().favorite)
        assertEquals(listOf("work"),store.load().single().aliases);assertEquals(75,store.load().single().stops.single().dwellMinutes)
    }
    @Test fun geometricSuggestionPreservesEndAndStopSettings(){
        val stops=listOf(stop("Far",33.4),stop("Near",33.1),stop("End",33.5))
        val proposed=TripPlanning.suggest(origin,stops)
        assertEquals(stops.last(),proposed.last());assertEquals(stops.toSet(),proposed.toSet());assertTrue(TripPlanning.distance(origin,proposed)<=TripPlanning.distance(origin,stops))
        assertThrows(IllegalArgumentException::class.java){TripPlanning.suggest(origin,stops.map{it.copy(arriveByMillis=1L)})}
    }
    @Test fun exactRouteArchiveKeepsPathAndSegmentTiming(){
        val route=RouteResult(listOf(origin,LatLng(33.1,-112.0)),1200.0,90.0,listOf(RouteSegment(listOf(origin,LatLng(33.1,-112.0)),1200.0,90.0,instruction="Turn left")),fetchedAtMillis=123)
        assertEquals(route,RouteArchive.decode(RouteArchive.encode(route)))
        val saved=scenario().copy(stops=listOf(scenario().stops.first().copy(frozenRoute=route,arriveByMillis=10000)))
        assertEquals(saved,BackupCodec.decode(BackupCodec.encode(listOf(saved))).single())
    }
    @Test fun liveUndoOnlyChangesFutureStopsAndExpiresAfterProgress()= runTest {
        val stops=listOf(stop("Current",33.1),stop("Office",33.2),stop("Lunch",33.3))
        val plan=LivePlan("Day",origin,stops,{_,to->PreparedLeg(flowOf(Fix(to.point.lat,to.point.lng,0f,0f,4f)),listOf(to.point))})
        plan.fixes().take(1).toList()
        val before=plan.view();assertTrue(plan.move(before.stops[2].id,-1));assertTrue(plan.undoEdit());assertEquals(before.stops,plan.view().stops)
        assertTrue(plan.remove(before.stops[2].id));plan.fixes().take(1).toList();assertFalse(plan.undoEdit());assertEquals(1,plan.view().index)
    }
    @Test fun followUpUsesStableConfirmedStopAndExpires(){
        val entries=listOf(LiveStop(stop=stop("Current",33.1)),LiveStop(stop=stop("Office",33.2)),LiveStop(stop=stop("Lunch",33.3)))
        val context=VoiceFollowUp();context.remember(entries[1].id,7,1000)
        assertEquals(VoiceIntent("stay","Office",minutes=45),context.resolve("Make its stay forty five minutes",entries,0,7,2000))
        assertEquals(VoiceIntent("move","Office",position=2),context.resolve("Actually put it after Lunch",entries,0,7,2000))
        assertNull(context.resolve("remove it",entries,0,8,2000))
        context.remember(entries[1].id,7,1000);assertNull(context.resolve("remove it",entries,0,7,122000))
    }
    @Test fun followUpRejectsDuplicateNamesAndVisitedTargets(){
        val one=LiveStop(stop=stop("Office",33.1));val entries=listOf(one,LiveStop(stop=stop("Office",33.2)))
        val context=VoiceFollowUp();context.remember(one.id,7,1000)
        assertNull(context.resolve("remove it",entries,-1,7,2000));assertNull(context.resolve("remove it",entries,0,7,2000))
    }
}
