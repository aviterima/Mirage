package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContinuationTest {
    private val a = LatLng(33.50, -112.0)
    private val b = LatLng(33.60, -112.1)
    private val c = LatLng(33.70, -112.2)
    private val n = LatLng(33.65, -112.15)
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()); MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.paused = false }
    @After fun after() { LiveSession.clear(); MockState.reset(); PlaybackSource.paused = false; Dispatchers.resetMain() }
    private fun stop(name: String, at: LatLng, stay: Int = 0) = ItineraryStop(name, at, stay, TravelMode.FLY, 550f)
    private fun fixture(kind: String = "SNAP") = SavedScenario("n", "New place", kind, 1, false, b, "B", n, "New place",
        TravelMode.FLY, emptyMap(), Realism.BUSY, "rail", listOf(SavedStop("New place", n.lat, n.lng, 17, TravelMode.FLY, 550f)),
        "123 Test Street, Scottsdale, Arizona", "fixture-place")
    private fun vm() = MirageViewModel().apply { mode = TravelMode.FLY }
    private fun live(): LivePlan {
        MockState.update { it.copy(running = true, lat = a.lat, lng = a.lng) }
        return LivePlan("Trip", a, listOf(stop("B", b, 30), stop("C", c)), { _, target ->
            PreparedLeg(flowOf(Fix(target.point.lat, target.point.lng, 0f, 0f, 4f)), listOf(a, target.point))
        })
    }
    @Test fun pinSelectionAndReplacementStayIsolatedUntilConfirmed() = runTest {
        val plan=live();val job=launch {plan.fixes().collect {}};advanceTimeBy(1000)
        val original=plan.view().stops
        val vm=vm()
        vm.continuation.beginReplace(original[1].id)
        vm.continuation.mapPick("destination")
        vm.continuation.pickPin(n);runCurrent()
        vm.continuation.mapPick("entrance")
        vm.continuation.pickPin(c);runCurrent()
        vm.continuation.stayUntilLeave(true)
        assertEquals(original,plan.view().stops)
        assertEquals(c,vm.continuation.state!!.stops.single().entrance)
        assertTrue(vm.continuation.commit {fail("Replacement must not restart")})
        assertEquals(original[1].id,plan.view().stops[1].id)
        assertEquals(n,plan.view().stops[1].stop.point)
        assertTrue(plan.view().stops[1].stop.stayUntilLeave)
        assertTrue(plan.undoEdit());assertEquals(original,plan.view().stops)
        vm.continuation.begin();vm.continuation.mapPick("destination");vm.continuation.cancelMapPick()
        assertNull(vm.continuation.state!!.pinTarget)
        vm.continuation.cancel();assertEquals(original,plan.view().stops)
        job.cancelAndJoin()
    }
    @Test fun nextAndEndAreDifferentAndPreserveCurrentStay() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val current = plan.view().stops[0].id
        val remain = plan.view().remainingStaySeconds
        assertTrue(plan.insertAfter(current, listOf(stop("N", n))))
        assertEquals(listOf("B", "N", "C"), plan.view().stops.map { it.stop.name })
        assertEquals(remain, plan.view().remainingStaySeconds)
        assertTrue(plan.insertAfter(current, listOf(stop("End", n)), true))
        assertEquals(listOf("B", "N", "C", "End"), plan.view().stops.map { it.stop.name })
        job.cancelAndJoin()
    }
    @Test fun blockInsertionHasOneCompleteSnapshotAndUniqueIds() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        assertTrue(plan.insertAfter(plan.view().stops[0].id, listOf(stop("N1", n), stop("N2", n))))
        val view = plan.view()
        assertEquals(listOf("B", "N1", "N2", "C"), view.stops.map { it.stop.name })
        assertEquals(4, view.stops.map { it.id }.distinct().size)
        job.cancelAndJoin()
    }
    @Test fun savedRouteAddsConnectionFromLiveOriginWithoutChangingDraft() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val vm = vm(); vm.setStartPoint(c, "Old draft")
        vm.continuation.begin(Placement.NOW, DestinationSource.ROUTE)
        vm.continuation.saved(fixture("ROUTE")); runCurrent()
        val d = vm.continuation.state!!
        assertEquals(listOf(b, n), vm.continuation.block(d, a).map { it.point })
        assertEquals(c, vm.start)
        assertEquals("123 Test Street, Scottsdale, Arizona", d.stops.single().address)
        assertEquals(Realism.BUSY, d.stops.single().routingRealism)
        vm.continuation.cancel(); job.cancelAndJoin()
    }
    @Test fun savedSnapIsAPlaceAndNeverImportsItsSavedOrigin() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val vm = vm(); vm.continuation.begin(Placement.NOW, DestinationSource.SNAP)
        vm.continuation.saved(fixture()); runCurrent()
        val d = vm.continuation.state!!
        assertNull(d.savedOrigin); assertEquals(listOf(n), vm.continuation.block(d, a).map { it.point })
        assertEquals(0, d.stops.single().dwellMinutes)
        vm.continuation.cancel(); job.cancelAndJoin()
    }
    @Test fun pausedItineraryAppendPreservesPauseAndTemplateAndCommitsOnce() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        PlaybackSource.paused = true
        val vm = vm(); val saved = fixture("ITINERARY")
        vm.continuation.begin(Placement.NEXT, DestinationSource.ITINERARY)
        vm.continuation.saved(saved); runCurrent()
        assertTrue(vm.continuation.state!!.ready)
        assertTrue(vm.continuation.commit { fail("Append must not restart service") })
        assertFalse(vm.continuation.commit { fail("Duplicate commit") })
        assertTrue(PlaybackSource.paused)
        assertEquals(listOf("B", "New place", "C"), plan.view().stops.map { it.stop.name })
        assertEquals(17, plan.view().stops[1].stop.dwellMinutes)
        assertEquals(17, saved.stops.single().dwellMinutes)
        job.cancelAndJoin()
    }
    @Test fun goNowUsesLatestPositionAndKeepsUnvisitedStops() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val vm = vm(); vm.continuation.begin(Placement.NOW); vm.continuation.saved(fixture()); runCurrent()
        MockState.update { it.copy(lat = b.lat, lng = b.lng) }
        var started = false
        assertTrue(vm.continuation.commit { started = true })
        assertTrue(started); assertEquals(listOf(b), PlaybackSource.routePoints)
        job.cancelAndJoin()
        val newJob = launch { PlaybackSource.current!!.collect {} }; advanceTimeBy(100)
        assertEquals(listOf("New place", "C"), LiveSession.state.value.stops.map { it.stop.name })
        newJob.cancelAndJoin()
    }
    @Test fun stoppedOrChangedSessionRejectsPreparedAddition() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val vm = vm(); vm.continuation.begin(); vm.continuation.saved(fixture()); runCurrent()
        LiveSession.clear(); MockState.update { it.copy(running = false) }
        assertFalse(vm.continuation.commit { fail("Must never restart stopped session") })
        assertNotNull(vm.continuation.state!!.error)
        vm.continuation.cancel(); job.cancelAndJoin()
    }
    @Test fun completedInsertionAnchorIsRejected() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val anchor = plan.view().stops[0].id
        PlaybackSource.requestSkip(); advanceTimeBy(1000)
        assertFalse(plan.insertAfter(anchor, listOf(stop("N", n))))
        assertEquals(listOf("B", "C"), plan.view().stops.map { it.stop.name })
        job.cancelAndJoin()
    }
    @Test fun routeFailureHoldsAndRetainsPendingStopsUntilRetry() = runTest {
        var failRoute = true
        val fixes = mutableListOf<Fix>()
        val plan = LivePlan("Recover", a, listOf(stop("B", b), stop("C", c)), { from, to ->
            if (failRoute) error("Offline")
            PreparedLeg(flowOf(Fix(to.point.lat, to.point.lng, 0f, 0f, 4f)), listOf(from, to.point))
        })
        val job = launch { plan.fixes().collect { fixes += it } }; advanceTimeBy(1000)
        assertNotNull(plan.view().routeFailure)
        assertEquals(listOf("B", "C"), plan.remainingForContinuation().map { it.name })
        assertTrue(fixes.size >= 4); assertTrue(fixes.all { it.lat == a.lat && it.lng == a.lng })
        failRoute = false; plan.retryFailed(); advanceTimeBy(1500)
        assertNull(plan.view().routeFailure); assertEquals(ActivityKind.HOLDING, plan.view().activity)
        job.cancelAndJoin()
    }
    @Test fun editedTextImmediatelyInvalidatesOldCoordinatesAndSuggestions() = runTest {
        val hit = PlaceHit(n, "Moxy", "Scottsdale, Arizona", "scottsdale")
        val vm = MirageViewModel { _, _, _ -> listOf(hit) }
        vm.configureApi(ApiConfig(null, "fixture", "test"))
        vm.search("Moxy") {}; runCurrent(); vm.pickSuggestion(hit)
        assertEquals(n, vm.dest); assertEquals(hit.address, vm.destAddress)
        vm.editLocationQuery(Field.END, "Different hotel", a)
        assertNull(vm.dest); assertEquals("", vm.destAddress); assertTrue(vm.suggestions.isEmpty())
        vm.clearSuggestions()
    }
    @Test fun oldUncancellableSearchCannotOverwriteNewQueryOrDifferentField() = runTest {
        val vm = MirageViewModel { _, query, _ ->
            withContext(NonCancellable) { delay(if (query == "old") 1000 else 10) }
            listOf(PlaceHit(n, query, query, query))
        }
        vm.configureApi(ApiConfig(null, "fixture", "test"))
        vm.search("old") {}; runCurrent()
        vm.search("new") {}; advanceTimeBy(1100); runCurrent()
        assertEquals(listOf("new"), vm.suggestions.map { it.name })
        vm.activeField = Field.START; vm.pickSuggestion(vm.suggestions.single())
        assertNull(vm.start); assertNull(vm.dest); vm.clearSuggestions()
    }
    @Test fun continuationTypingAndCancelCannotApplyLateResults() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val vm = MirageViewModel { _, query, _ -> withContext(NonCancellable) { delay(1000) }; listOf(PlaceHit(n, query)) }
        vm.configureApi(ApiConfig(null, "fixture", "test")); vm.continuation.begin()
        vm.continuation.query("Moxy Scottsdale", true); runCurrent(); vm.continuation.cancel()
        advanceTimeBy(1500); assertNull(vm.continuation.state)
        assertEquals(2, plan.view().stops.size); job.cancelAndJoin()
    }
    @Test fun newMetadataRoundTripsAndLegacyRecordsStillLoad() {
        val saved = fixture("ITINERARY")
        assertEquals(saved, SavedScenario.fromJson(saved.toJson()))
        val old = saved.toJson().apply { remove("destAddress"); remove("destPlaceId") }
        val restored = SavedScenario.fromJson(old)
        assertEquals(saved.dest, restored.dest); assertEquals(saved.id, restored.id)
        assertEquals("", restored.destAddress)
    }
    @Test fun clearingRealStartCannotRestoreAHiddenLocationOnTheNextFix() {
        val vm = vm(); vm.pickRealStart(a)
        vm.editLocationQuery(Field.START, "", null)
        vm.refineMyLocation(b)
        assertNull(vm.start); assertFalse(vm.startFromReal)
    }
    @Test fun invalidSavedOriginAndEmptyItineraryCannotBeCommitted() = runTest {
        val plan = live(); val job = launch { plan.fixes().collect {} }; advanceTimeBy(1000)
        val vm = vm(); vm.continuation.begin(Placement.NOW, DestinationSource.ROUTE)
        vm.continuation.saved(fixture("ROUTE").copy(start = LatLng(91.0, 0.0))); runCurrent()
        assertNotNull(vm.continuation.state!!.error); assertFalse(vm.continuation.state!!.ready)
        vm.continuation.source(DestinationSource.ITINERARY)
        vm.continuation.saved(fixture("ITINERARY").copy(stops = emptyList())); runCurrent()
        assertNotNull(vm.continuation.state!!.error)
        assertFalse(vm.continuation.commit { fail("Invalid addition must not start") })
        vm.continuation.cancel(); job.cancelAndJoin()
    }
}
