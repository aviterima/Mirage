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
    @Test fun entranceAndStayPreferencesRoundTripAndInvalidEntranceIsRejected(){
        val item=scenario().copy(defaultStayMinutes=45,stayUntilLeave=true,entrance=origin,
            stops=listOf(scenario().stops.single().copy(entrance=origin,stayUntilLeave=true)))
        assertEquals(listOf(item),BackupCodec.decode(BackupCodec.encode(listOf(item))))
        val raw=org.json.JSONObject(BackupCodec.encode(listOf(item)))
        raw.getJSONArray("items").getJSONObject(0).put("entranceLat",133.0)
        assertThrows(IllegalArgumentException::class.java){BackupCodec.decode(raw.toString())}
    }
    @Test fun malformedBackupCannotPartiallyImport(){
        assertThrows(IllegalArgumentException::class.java){BackupCodec.decode(BackupCodec.encode(listOf(scenario())).replace("WALK","HOVERCRAFT"))}
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
        assertNull(vm.dest);assertEquals("",vm.destName)
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
    @Test fun archivedRouteResumeUsesRemainingGeometryAndRejectsOffRouteCheckpoint(){
        val end=LatLng(33.2,-112.0)
        val route=RouteResult(listOf(origin,LatLng(33.1,-112.0),end),22000.0,1200.0)
        val point=LatLng(33.05,-112.0)
        val remaining=RouteArchive.remaining(route,point)
        assertEquals(point,remaining.points.first());assertEquals(end,remaining.points.last())
        assertTrue(remaining.durationSeconds<route.durationSeconds)
        assertThrows(IllegalArgumentException::class.java){RouteArchive.remaining(route,LatLng(34.0,-113.0))}
    }
    @Test fun recoveryKeepsCompletedStopsAndResumesAtCheckpointWithoutStartingUntilRequested() = runTest {
        val original=scenario().copy(stops=listOf(stop("Visited",33.1).toSavedStop(),stop("Current",33.2).toSavedStop(),stop("Future",33.3).toSavedStop()),realism=Realism.BUSY)
        val record=RecoveredTrip(original,LatLng(33.2,-112.0),1,ActivityKind.STAYING,300,null,timeScale=5.0,speedOffset=3.0)
        val vm=MirageViewModel().apply{configureApi(ApiConfig(null,"","test"))}
        var started=false
        assertFalse(MockState.status.value.running)
        vm.recoverLive(record){started=true}
        assertTrue(started)
        val fixes=PlaybackSource.current!!.take(1).toList()
        assertEquals(record.position.lat,fixes.single().lat,0.0)
        assertEquals(original.stops.map{it.name},LiveSession.state.value.stops.map{it.stop.name})
        assertEquals(1,LiveSession.state.value.index)
        assertEquals(Realism.BUSY,LiveSession.plan!!.defaultsRealism)
        assertEquals(5.0,PlaybackSource.timeScale,0.0)
        PlaybackSource.timeScale=1.0;PlaybackSource.speedOverLimitMph=0.0;PlaybackSource.current=null
    }
    @Test fun futureDepartureHoldsOriginWithoutRequestingRoute()=runTest {
        var routed=false
        val plan=LivePlan("Later",origin,listOf(stop("Office",33.1)),{_,to -> routed=true;PreparedLeg(flowOf(Fix(to.point.lat,to.point.lng,0f,0f,4f)),listOf(to.point))},departureMillis=System.currentTimeMillis()+60_000)
        val fix=plan.fixes().take(1).toList().single()
        assertFalse(routed);assertEquals(ActivityKind.WAITING,plan.view().activity)
        assertEquals(origin.lat,fix.lat,0.0);assertEquals(origin.lng,fix.lng,0.0)
        assertEquals(-1,plan.view().index)
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
        assertEquals(VoiceIntent("move","Office",position=2),context.resolve("Add this stop at the end",entries,0,7,2000))
        assertNull(context.resolve("remove it",entries,0,8,2000))
        context.remember(entries[1].id,7,1000);assertNull(context.resolve("remove it",entries,0,7,122000))
    }
    @Test fun followUpRejectsDuplicateNamesAndVisitedTargets(){
        val one=LiveStop(stop=stop("Office",33.1));val entries=listOf(one,LiveStop(stop=stop("Office",33.2)))
        val context=VoiceFollowUp();context.remember(one.id,7,1000)
        assertNull(context.resolve("remove it",entries,-1,7,2000));assertNull(context.resolve("remove it",entries,0,7,2000))
    }
}
