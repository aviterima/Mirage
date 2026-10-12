package com.mirage.spike

import com.mirage.spike.audit.AuditSource
import com.mirage.spike.audit.AuditTrail
import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.store.ScenarioStore
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Audit real planner operations without changing their draft, persistence or simulation behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditInteractionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val origin = LatLng(33.6123456, -112.1123456)
    private val homePoint = LatLng(33.5123456, -112.0123456)
    private val privateKey = "PRIVATE-api-key-827"
    private val home = SavedScenario("PRIVATE-saved-id-827", "PRIVATE Home 827", "SNAP", 1,
        false, null, "", homePoint, "PRIVATE destination pin 827", TravelMode.DRIVE,
        emptyMap(), Realism.REALISTIC, null, emptyList(),
        destAddress = "827 PRIVATE Cypress Lane", destPlaceId = "PRIVATE-provider-place-id-827",
        aliases = listOf("PRIVATE house alias 827"), defaultStayMinutes = 17)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AuditTrail.resetForTests()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
    }

    @After fun cleanup() {
        AuditTrail.resetForTests()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
        Dispatchers.resetMain()
    }

    private fun TestScope.startAudit() { AuditTrail.initializeForTests(temporary.newFolder(), backgroundScope) }
    private fun vm(store: ScenarioStore = InMemoryScenarioStore().apply { save(listOf(home)) }) =
        MirageViewModel { _, _, _ -> error("Unexpected provider request") }.apply {
            configureApi(ApiConfig(null, "", "PRIVATE-install-id-827")); attachStore(store)
        }
    private fun String.events(action: String) = JSONObject(this).getJSONArray("events").let { array ->
        (0 until array.length()).map(array::getJSONObject).filter { it.getString("action") == action }
    }
    private fun assertPrivate(report: String) {
        listOf("PRIVATE", home.id, home.name, home.destName, home.destAddress, home.destPlaceId,
            home.aliases.single(), homePoint.lat.toString(), homePoint.lng.toString(),
            origin.lat.toString(), origin.lng.toString(), privateKey).forEach {
            assertFalse("Audit must not contain user/provider data", report.contains(it))
        }
    }
    private fun assertIdle() {
        assertFalse(MockState.status.value.running)
        assertNull(PlaybackSource.current); assertNull(LiveSession.plan)
    }
    private fun paired(events: List<JSONObject>, terminal: String): Pair<JSONObject, JSONObject> {
        val result = events.single { it.getString("outcome") == terminal }
        val request = events.single {
            it.getString("outcome") == "REQUESTED" && it.getLong("operation") == result.getLong("operation")
        }
        assertTrue(request.getLong("sequence") < result.getLong("sequence"))
        return request to result
    }

    @Test fun savedAliasSelectionSaveAndReopenAreCorrelatedAndRedacted() = runTest {
        startAudit()
        val vm = vm()
        vm.configureApi(ApiConfig(null, privateKey, "PRIVATE-install-id-827"))
        // Disable remote lookup; this journey exercises the local alias and saved-place source.
        vm.configureApi(ApiConfig(null, "", "PRIVATE-install-id-827"))
        vm.setStartPoint(origin, "PRIVATE Office 827")
        vm.editLocationQuery(Field.END, home.aliases.single(), null)
        assertEquals(home.id, vm.savedPlaceSuggestions.single().id)
        assertTrue(vm.pickSavedPlace(vm.savedPlaceSuggestions.single(), Field.END, AuditSource.TYPEAHEAD))
        assertEquals(origin, vm.tripStart()); assertEquals(homePoint, vm.dest)
        assertTrue(vm.saveScenario("PRIVATE route 827"))
        val saved = vm.savedScenarios.single { it.name == "PRIVATE route 827" }
        assertEquals(origin, saved.start); assertEquals(homePoint, saved.dest)
        vm.loadScenario(saved)
        assertEquals(origin, vm.tripStart()); assertEquals(homePoint, vm.dest)
        assertEquals(home.destAddress, vm.destAddress); assertEquals(17, vm.defaultStayMinutes)
        assertIdle()

        val report = AuditTrail.exportReport()
        val (before, after) = paired(report.events("SAVED_SELECT"), "SUCCEEDED")
        assertFalse(before.getJSONObject("state").getBoolean("hasDestination"))
        assertTrue(after.getJSONObject("state").getBoolean("hasDestination"))
        assertEquals("END", after.getJSONObject("state").getString("field"))
        assertEquals("ROUTE", after.getJSONObject("state").getString("planMode"))
        assertEquals("TYPEAHEAD", after.getJSONObject("details").getString("source"))
        assertFalse(after.getJSONObject("state").getBoolean("running"))
        paired(report.events("PLAN_SAVE"), "SUCCEEDED")
        paired(report.events("PLAN_LOAD"), "SUCCEEDED")
        assertPrivate(report)
    }

    @Test fun invalidSavedPlaceAndDuplicateSaveAreBlockedWithoutMutatingDraftOrLibrary() = runTest {
        startAudit()
        val vm = vm()
        vm.setStartPoint(origin, "PRIVATE Office 827"); vm.setDestPoint(homePoint, home.name)
        val draft = vm.draftSnapshot(); val library = vm.savedScenarios.toList()
        assertFalse(vm.pickSavedPlace(home.copy(kind = "ROUTE"), Field.END))
        assertFalse(vm.saveScenario(home.name))
        assertEquals(draft, vm.draftSnapshot()); assertEquals(library, vm.savedScenarios.toList())
        assertIdle()
        val report = AuditTrail.exportReport()
        val invalid = paired(report.events("SAVED_SELECT"), "BLOCKED").second
        assertEquals("INVALID_INPUT", invalid.getJSONObject("details").getString("reason"))
        val duplicate = paired(report.events("PLAN_SAVE"), "BLOCKED").second
        assertEquals("DUPLICATE_NAME", duplicate.getJSONObject("details").getString("reason"))
        assertPrivate(report)
    }

    @Test fun staleUncancellableSearchHasACorrelatedTerminalAndCannotReplaceSavedHome() = runTest {
        startAudit()
        var providerCalled = false
        val remote = PlaceHit(LatLng(34.1234567, -113.1234567), "PRIVATE remote name",
            "PRIVATE remote address", "PRIVATE remote id")
        val vm = MirageViewModel { _, query, _ ->
            assertEquals("PRIVATE query 827", query)
            providerCalled = true
            withContext(NonCancellable) { delay(1_000) }
            listOf(remote)
        }.apply {
            configureApi(ApiConfig(null, privateKey, "PRIVATE-install-id-827"))
            attachStore(InMemoryScenarioStore().apply { save(listOf(home)) })
        }
        vm.setStartPoint(origin, "PRIVATE Office 827")
        vm.editLocationQuery(Field.END, "PRIVATE query 827", null)
        advanceTimeBy(400); runCurrent(); assertTrue(providerCalled)
        assertTrue(vm.pickSavedPlace(home, Field.END))
        advanceUntilIdle()
        vm.pickSuggestion(remote)
        assertEquals(homePoint, vm.dest); assertEquals(origin, vm.tripStart())
        assertTrue(vm.suggestions.isEmpty()); assertIdle()
        val report = AuditTrail.exportReport()
        val requests = report.events("SEARCH").filter { it.getString("outcome") == "REQUESTED" }
        val request = requests.single()
        val terminal = report.events("SEARCH").single {
            it.optLong("operation", -1) == request.getLong("operation") &&
                it.getString("outcome") in setOf("CANCELLED", "IGNORED")
        }
        assertTrue(request.getLong("sequence") < terminal.getLong("sequence"))
        assertFalse(report.events("SEARCH").any {
            it.optLong("operation", -1) == request.getLong("operation") && it.getString("outcome") == "SUCCEEDED"
        })
        assertPrivate(report)
        assertFalse(report.contains(remote.latLng.lat.toString()))
        assertFalse(report.contains(remote.latLng.lng.toString()))
    }

    @Test fun routePreconditionsAndStoreFailureHaveTypedReasonsWithoutPrivateExceptionText() = runTest {
        startAudit()
        val failedStore = object : ScenarioStore {
            override fun load() = listOf(home)
            override fun save(list: List<SavedScenario>) { throw IOException("PRIVATE storage path and secret 827") }
        }
        val vm = vm(failedStore)
        vm.buildRoute()
        vm.setStartPoint(origin, "PRIVATE Office 827"); vm.setDestPoint(homePoint, home.name)
        vm.buildRoute()
        val before = vm.savedScenarios.toList()
        assertFalse(vm.saveScenario("PRIVATE failed route 827"))
        assertEquals(before, vm.savedScenarios.toList())
        assertEquals(homePoint, vm.dest); assertEquals(origin, vm.tripStart()); assertIdle()
        val report = AuditTrail.exportReport()
        val blocked = report.events("ROUTE_BUILD").filter { it.getString("outcome") == "BLOCKED" }
        assertEquals(setOf("NO_START", "NO_API"), blocked.map { it.getJSONObject("details").getString("reason") }.toSet())
        blocked.forEach { result ->
            assertEquals(1, report.events("ROUTE_BUILD").count {
                it.getString("outcome") == "REQUESTED" && it.getLong("operation") == result.getLong("operation")
            })
        }
        val failedSave = paired(report.events("PLAN_SAVE"), "FAILED").second
        assertEquals("STORAGE", failedSave.getJSONObject("details").getString("reason"))
        assertPrivate(report)
    }

    @Test fun continuationCancelAndConfirmHaveDistinctCorrelatedOutcomesAndPreservePause() = runTest {
        startAudit()
        MockState.update { it.copy(running = true, lat = origin.lat, lng = origin.lng) }
        val office = ItineraryStop("PRIVATE Office 827", origin, 30, TravelMode.FLY, 550f)
        val lastStop = ItineraryStop("PRIVATE final stop 827", LatLng(33.7123456, -112.2123456), 0, TravelMode.FLY, 550f)
        val plan = LivePlan("PRIVATE active trip 827", origin, listOf(office, lastStop), { from, target ->
            PreparedLeg(flowOf(Fix(target.point.lat, target.point.lng, 0f, 0f, 4f)), listOf(from, target.point))
        })
        val job = launch { plan.fixes().collect {} }
        try {
            advanceTimeBy(1_000); runCurrent()
            PlaybackSource.paused = true
            val vm = vm().apply { mode = TravelMode.FLY }
            val original = plan.view().stops
            vm.continuation.begin(Placement.NEXT, DestinationSource.SNAP)
            vm.continuation.saved(home); runCurrent()
            vm.continuation.cancel()
            assertEquals(original, plan.view().stops)
            vm.continuation.begin(Placement.NEXT, DestinationSource.SNAP)
            vm.continuation.saved(home); runCurrent()
            assertTrue(vm.continuation.state!!.ready)
            assertTrue(vm.continuation.commit { fail("Appending must not restart simulation") })
            assertEquals(listOf(office.point, homePoint, lastStop.point), plan.view().stops.map { it.stop.point })
            assertTrue(PlaybackSource.paused)
            val report = AuditTrail.exportReport()
            val events = report.events("CONTINUATION")
            val cancelled = events.single { it.getString("outcome") == "CANCELLED" }
            val confirmed = events.single { it.getString("outcome") == "SUCCEEDED" }
            assertNotEquals(cancelled.getLong("operation"), confirmed.getLong("operation"))
            assertEquals("CONFIRM", confirmed.getJSONObject("details").getString("control"))
            for (terminal in listOf(cancelled, confirmed)) assertEquals(1, events.count {
                it.getString("outcome") == "REQUESTED" && it.getJSONObject("details").getString("control") == "OPEN" &&
                    it.getLong("operation") == terminal.getLong("operation")
            })
            assertPrivate(report)
        } finally { job.cancelAndJoin() }
    }
}
