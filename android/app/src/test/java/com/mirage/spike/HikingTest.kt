package com.mirage.spike

import com.mirage.spike.hiking.*
import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HikingTest {
    private val a=LatLng(33.5,-112.0)
    private val b=Geo.offset(a,1000.0,0.0)
    private val c=Geo.offset(b,0.0,1000.0)
    private val trail=HikingTrail("way/1","Fixture Canyon",listOf(a,b,c),"https://www.openstreetmap.org/way/1")
    private val parking=TrailParking("parking/1","Trail parking",Geo.offset(a,-100.0,0.0))
    @Before fun setup(){Dispatchers.setMain(UnconfinedTestDispatcher());MockState.reset();LiveSession.clear();PlaybackSource.timeScale=1.0;PlaybackSource.clearQueue()}
    @After fun cleanup(){Dispatchers.resetMain();LiveSession.clear();MockState.reset();PlaybackSource.timeScale=1.0}
    private fun routed(s: RouteSpec): RouteResult {
        val pts=listOf(s.origin,Geo.gcInterp(s.origin,s.destination,0.5),s.destination)
        val d=TrailGeometry.length(pts);return RouteResult(pts,d,d/1.1)
    }
    @Test fun requestedMileageReturnsToTrailheadWithoutShortcut() {
        val r=TrailGeometry.hike(trail,1.0)
        assertEquals(METERS_PER_MILE,r.distanceMeters,0.05)
        assertEquals(a,r.points.first());assertEquals(a,r.points.last())
        r.points.forEach {assertEquals(a.lng,it.lng,1e-10)}
        assertEquals(METERS_PER_MILE/2,Geo.haversine(a,r.points[r.points.size/2]),0.05)
    }
    @Test fun recoveryOnReturnHalfDoesNotRepeatOutboundTrail() {
        val route=TrailGeometry.hike(trail,1.0)
        val position=Geo.gcInterp(a,route.points[1],0.5)
        val resumed=RouteArchive.remaining(route,position,0.75)
        assertEquals(METERS_PER_MILE*0.25,resumed.distanceMeters,0.1)
        assertEquals(a,resumed.points.last())
    }
    @Test fun repeatedRecoveryKeepsReturnDirectionAndOriginalProgress()=runTest {
        val trip=prepareHikingTrip(trail,parking,c,1.0,2,::routed)
        val route=trip.stops[2].frozenRoute!!
        val vm=MirageViewModel();vm.attachStore(InMemoryScenarioStore());vm.addHikingTrip(trip);vm.saveScenario("Recovery hike")
        val saved=vm.savedScenarios.single()
        val position=Geo.gcInterp(a,route.points[1],0.5)
        vm.recoverLive(RecoveredTrip(saved,position,2,ActivityKind.TRAVELING,0,null,routeProgress=0.75)){}
        val first=PlaybackSource.current!!.first {it.progress>=0f}
        assertTrue(first.progress>0.75f && first.progress<0.76f)
        assertTrue(Geo.haversine(LatLng(first.lat,first.lng),a)<Geo.haversine(position,a))
        vm.recoverLive(RecoveredTrip(saved,LatLng(first.lat,first.lng),2,ActivityKind.TRAVELING,0,null,routeProgress=first.progress.toDouble())){}
        val second=PlaybackSource.current!!.first {it.progress>=0f}
        assertTrue(second.progress>first.progress)
        assertTrue(Geo.haversine(LatLng(second.lat,second.lng),a)<Geo.haversine(LatLng(first.lat,first.lng),a))
    }
    @Test fun fullLoopUsesLoopAndShortLoopReturnsAlongActualPath() {
        val loop=trail.copy(points=listOf(a,b,c,a))
        assertTrue(loop.loop)
        assertEquals(loop.points,TrailGeometry.hike(loop,loop.meters/METERS_PER_MILE).points)
        val short=TrailGeometry.hike(loop,0.25)
        assertEquals(0.25*METERS_PER_MILE,short.distanceMeters,0.05)
        assertEquals(a,short.points.last())
    }
    @Test fun invalidOrExcessMileageCannotBecomeALongerHike() {
        listOf(-1.0,0.0,Double.NaN,Double.POSITIVE_INFINITY,1000.0).forEach {
            assertThrows(IllegalArgumentException::class.java){TrailGeometry.hike(trail,it)}
        }
    }
    @Test fun disconnectedOrBranchingSegmentsAreRejected() {
        assertNull(TrailGeometry.join(listOf(listOf(a,b),listOf(c,Geo.offset(c,100.0,0.0)))))
        assertNull(TrailGeometry.join(listOf(listOf(a,b),listOf(b,c),listOf(b,Geo.offset(b,-100.0,100.0)))))
        assertEquals(listOf(c,b,a),TrailGeometry.join(listOf(listOf(b,c),listOf(b,a))))
    }
    @Test fun parkingSelectsNearestEndpointAndRotatesLoops() {
        assertEquals(c,TrailGeometry.orient(trail,c).points.first())
        val loop=trail.copy(points=listOf(a,b,c,a))
        val rotated=TrailGeometry.orient(loop,b)
        assertEquals(b,rotated.points.first());assertEquals(b,rotated.points.last());assertEquals(loop.meters,rotated.meters,0.01)
    }
    @Test fun driveParkWalkHikeReturnHaveExactModesAndRoundTripGeometry()=runTest {
        val requests=mutableListOf<RouteSpec>()
        val trip=prepareHikingTrip(trail,parking,Geo.offset(a,-1000.0,0.0),1.0,2){requests+=it;routed(it)}
        assertEquals(listOf(TravelMode.DRIVE,TravelMode.WALK),requests.map{it.mode})
        assertEquals(listOf(TravelMode.DRIVE,TravelMode.WALK,TravelMode.WALK,TravelMode.WALK),trip.stops.map{it.mode})
        assertEquals(2,trip.stops.first().dwellMinutes);assertEquals(ArrivalActivity.PARKED,trip.stops.first().arrivalActivity)
        assertEquals(parking.point,trip.stops.last().point)
        assertEquals(trip.stops[1].frozenRoute!!.points.reversed(),trip.stops.last().frozenRoute!!.points)
        assertEquals(METERS_PER_MILE,trip.trailMeters,0.05)
        assertEquals(TrailGeometry.length(trip.stops[1].frozenRoute!!.points)*2,trip.walkMeters,0.01)
    }
    @Test fun playbackDrivesParksHikesAndFinishesAtCar()=runTest {
        val trip=prepareHikingTrip(trail,parking,c,1.0,2,::routed)
        PlaybackSource.timeScale=100.0
        val visited=mutableListOf<Pair<Int,ActivityKind>>()
        val plan=LivePlan("Test hike",c,trip.stops,{from,stop->prepareLeg(ApiConfig(null,"","test"),from,stop,Realism.CONSTANT)})
        val fixes=plan.fixes().onEach {visited+=LiveSession.state.value.let {it.index to it.activity}}
            .takeWhile {LiveSession.state.value.activity!=ActivityKind.HOLDING}.toList()
        assertEquals(listOf(0,1,2,3),visited.filter {it.second==ActivityKind.TRAVELING}.map {it.first}.distinct())
        assertTrue(visited.contains(0 to ActivityKind.STAYING))
        assertEquals(ActivityKind.HOLDING,LiveSession.state.value.activity)
        assertEquals(parking.point,LatLng(fixes.last().lat,fixes.last().lng))
        assertEquals(0f,fixes.last().speedMps)
    }
    @Test fun unreachableParkingOrDisconnectedWalkCannotPrepare()=runTest {
        try {prepareHikingTrip(trail,parking,c,1.0,2){s->if(s.mode==TravelMode.WALK)RouteResult(listOf(c,b),100.0,100.0) else routed(s)};fail("Gap accepted")}
        catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("continuous walking"))}
    }
    @Test fun savedHikeRoundTripsExactGeometryAndParkingState()=runTest {
        val trip=prepareHikingTrip(trail,parking,c,1.0,2,::routed)
        val vm=MirageViewModel();vm.attachStore(InMemoryScenarioStore())
        vm.addHikingTrip(trip)
        assertTrue(vm.saveScenario("Weekend hike"))
        val saved=vm.savedScenarios.single();val reloaded=SavedScenario.fromJson(saved.toJson())
        assertEquals(saved,reloaded)
        assertEquals(ArrivalActivity.PARKED,reloaded.stops.first().arrivalActivity)
        assertEquals(METERS_PER_MILE,reloaded.stops[2].frozenRoute!!.distanceMeters,0.05)
    }
    @Test fun newlyPreparedHikeDoesNotClaimToBeSavedWhenStarted()=runTest {
        val trip=prepareHikingTrip(trail,parking,c,1.0,2,::routed)
        val vm=MirageViewModel();vm.attachStore(InMemoryScenarioStore());vm.addHikingTrip(trip)
        vm.startItinerary {}
        PlaybackSource.current!!.first()
        assertNull(LiveSession.state.value.savedId)
        assertTrue(LiveSession.state.value.dirty)
    }
    @Test fun addingHikePreservesEarlierDraftStops()=runTest {
        val trip=prepareHikingTrip(trail,parking,c,1.0,2,::routed)
        val vm=MirageViewModel();vm.setStartPoint(a);vm.choosePlanMode(PlanMode.ITINERARY)
        val old=ItineraryStop("Lunch",c,30);vm.stops+=old
        vm.addHikingTrip(trip)
        assertEquals(old,vm.stops.first());assertEquals(5,vm.stops.size)
    }
    @Test fun lateSearchCannotReopenClosedSheet()=runTest {
        val source=object:TrailSource {
            override suspend fun search(name:String,near:LatLng):List<HikingTrail>{withContext(NonCancellable){delay(1000)};return listOf(trail)}
            override suspend fun parking(trail:HikingTrail)=listOf(parking)
        }
        val p=HikingPlanner(this,source,{_,_->emptyList()},::routed)
        p.open(a);p.query("Fixture");p.search();runCurrent();p.close();advanceUntilIdle()
        assertNull(p.state)
    }
    @Test fun editingMilesInvalidatesPreparedTripAndInvalidInputIsVisible()=runTest {
        val source=object:TrailSource {
            override suspend fun search(name:String,near:LatLng)=listOf(trail)
            override suspend fun parking(trail:HikingTrail)=listOf(parking)
        }
        val p=HikingPlanner(this,source,{_,_->emptyList()},::routed)
        p.open(c);p.select(trail);advanceUntilIdle();p.chooseParking(parking);p.miles("1.0");p.prepare(c);advanceUntilIdle()
        assertNotNull(p.state!!.trip)
        p.miles("1000");assertNull(p.state!!.trip);p.prepare(c);advanceUntilIdle()
        assertNull(p.state!!.trip);assertNotNull(p.state!!.error)
    }
    @Test fun parkedDwellDoesNotWanderOrWalkInside()=runTest {
        val positions=mutableListOf<Fix>()
        val stop=ItineraryStop("Parking",a,2,TravelMode.DRIVE,arrivalActivity=ArrivalActivity.PARKED)
        val plan=LivePlan("Park",a,listOf(stop),{_,_->PreparedLeg(flowOf(Fix(a.lat,a.lng,0f,0f,4f)),listOf(a))})
        val job=launch {plan.fixes().collect{positions+=it}};advanceTimeBy(1500)
        assertTrue(positions.size>4);assertTrue(positions.all {it.lat==a.lat && it.lng==a.lng && it.speedMps==0f})
        assertFalse(MockState.status.value.stepLabel.contains("Walking"));job.cancelAndJoin()
    }
    @Test fun partialServiceResponseAndMissingGeometryAreRejected() {
        assertThrows(IllegalArgumentException::class.java){OsmTrailSource.parseTrails("""{"remark":"timeout","elements":[]}""")}
        val json="""{"elements":[{"type":"way","id":1,"tags":{"name":"Broken"},"geometry":[{"lat":33.5,"lon":-112},{"lat":33.6}]}]}"""
        assertTrue(OsmTrailSource.parseTrails(json).isEmpty())
    }
    @Test fun realEchoCanyonResponseProducesAContinuousUsableTrail() {
        val json=javaClass.getResource("/hiking/echo-canyon-osm.json")!!.readText()
        val found=OsmTrailSource.parseTrails(json).single()
        assertEquals("Echo Canyon Trail",found.name)
        assertTrue(found.mappedSection)
        assertEquals(1556.729720492359,found.meters,0.1)
        val hike=TrailGeometry.hike(found,1.0)
        assertEquals(METERS_PER_MILE,hike.distanceMeters,0.1)
        assertEquals(hike.points.first(),hike.points.last())
    }
    @Test fun mappedNamedWaysKeepHonestSectionLabel() {
        val json="""{"elements":[{"type":"way","id":1,"tags":{"name":"Canyon"},"geometry":[{"lat":33.5,"lon":-112},{"lat":33.51,"lon":-112}]}]}"""
        val hit=OsmTrailSource.parseTrails(json).single()
        assertEquals("Canyon",hit.name);assertTrue(hit.mappedSection);assertTrue(hit.meters>1000)
    }
    @Test fun blankNameBrowsesTypedCityAndAmbiguousCityWaitsForSelection()=runTest {
        val seen=mutableListOf<Pair<String,LatLng>>()
        val source=object:TrailSource {
            override suspend fun search(name:String,near:LatLng):List<HikingTrail>{seen+=name to near;return listOf(trail)}
            override suspend fun parking(trail:HikingTrail)=emptyList<TrailParking>()
        }
        val tucson=PlaceHit(LatLng(32.22,-110.97),"Tucson","Arizona", "city")
        val other=tucson.copy(name="Tucson park",placeId="park")
        var ambiguous=false
        val p=HikingPlanner(this,source,{_,_->if(ambiguous)listOf(tucson,other) else listOf(tucson)},::routed)
        p.open(a);p.areaQuery("Tucson");p.search();advanceUntilIdle()
        assertEquals(listOf("" to tucson.latLng),seen);assertTrue(p.state!!.searched)
        p.areaQuery("Tucson park");ambiguous=true;p.search();advanceUntilIdle()
        assertEquals(1,seen.size);assertEquals(2,p.state!!.areas.size)
        p.chooseArea(other);advanceUntilIdle()
        assertEquals(2,seen.size);assertEquals(other.latLng,seen.last().second)
    }
    @Test fun browseWithoutAreaUsesCurrentSearchCenterAndCityFailureNeverSearchesOldArea()=runTest {
        var calls=0
        val source=object:TrailSource {
            override suspend fun search(name:String,near:LatLng):List<HikingTrail>{calls++;assertEquals("",name);assertEquals(a,near);return listOf(trail)}
            override suspend fun parking(trail:HikingTrail)=emptyList<TrailParking>()
        }
        val p=HikingPlanner(this,source,{_,_->emptyList()},::routed)
        p.open(a);p.search();advanceUntilIdle();assertEquals(1,calls)
        p.areaQuery("Missing city");p.search();advanceUntilIdle()
        assertEquals(1,calls);assertNotNull(p.state!!.error);assertTrue(p.state!!.trails.isEmpty())
    }
    @Test fun distancesUseRealOrSimulatedPositionInsteadOfPlannedRouteEnd() {
        val vm=MirageViewModel();assertNull(vm.hikingLocation())
        vm.useMyLocation(a);vm.setStartPoint(c);vm.choosePlanMode(PlanMode.ITINERARY);vm.stops+=ItineraryStop("Lunch",b,0)
        assertEquals(a,vm.hikingLocation()!!.point);assertFalse(vm.hikingLocation()!!.simulated)
        assertEquals(b,vm.hikingOrigin())
        MockState.update {it.copy(running=true,lat=c.lat,lng=c.lng)}
        assertEquals(c,vm.hikingLocation()!!.point);assertTrue(vm.hikingLocation()!!.simulated)
        val far=Geo.offset(a,-METERS_PER_MILE,0.0)
        assertEquals(METERS_PER_MILE,trail.distanceFrom(far),0.1)
        assertTrue(HikingLocation(far,true).distanceLabel(trail).contains("1.00 mi from simulated location"))
    }
    @Test fun addingTrailToRouteRetainsDestinationBeforeHike()=runTest {
        val vm=MirageViewModel();vm.setStartPoint(a);vm.choosePlanMode(PlanMode.ROUTE);vm.setDestPoint(c,"Lunch")
        assertEquals(c,vm.hikingOrigin())
        vm.addHikingTrip(prepareHikingTrip(trail,parking,c,1.0,2,::routed))
        assertEquals(PlanMode.ITINERARY,vm.planMode);assertEquals("Lunch",vm.stops.first().name)
        assertEquals(c,vm.stops.first().point);assertEquals(5,vm.stops.size);assertEquals(a,vm.tripStart())
    }
    @Test fun browseMetadataBoundsGeometryAndExcludesPrivatePaths() {
        val elements=org.json.JSONArray()
        for(i in 1..120)elements.put(org.json.JSONObject().put("type","way").put("id",i)
            .put("center",org.json.JSONObject().put("lat",a.lat+i*0.0001).put("lon",a.lng))
            .put("tags",org.json.JSONObject().put("name","Trail ${i%10}").put("access",if(i==1)"private" else "yes")))
        val selectors=OsmTrailSource.browseSelectors(org.json.JSONObject().put("elements",elements).toString(),a)
        val ids=selectors.substringAfter("id:").substringBefore(")").split(",")
        assertEquals(80,ids.size);assertFalse(ids.contains("1"));assertTrue(selectors.startsWith("way(id:"))
        assertThrows(IllegalArgumentException::class.java){OsmTrailSource.browseSelectors("""{"remark":"timeout","elements":[]}""",a)}
    }
    @Test fun browseDisconnectedNamedWaysReturnsHonestSectionsWithoutJoiningGaps() {
        val json="""{"elements":[{"type":"way","id":1,"tags":{"name":"Canyon"},"geometry":[{"lat":33.5,"lon":-112},{"lat":33.51,"lon":-112}]},{"type":"way","id":2,"tags":{"name":"Canyon"},"geometry":[{"lat":33.6,"lon":-112},{"lat":33.61,"lon":-112}]}]}"""
        assertTrue(OsmTrailSource.parseTrails(json).isEmpty())
        val found=OsmTrailSource.parseTrails(json,allowSections=true)
        assertEquals(2,found.size);assertTrue(found.all {it.mappedSection && it.meters<1200})
    }

}
