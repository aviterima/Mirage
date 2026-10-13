package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerVoiceSessionTest {
    private val real = LatLng(33.6, -117.7)
    private val office = PlaceHit(LatLng(33.7, -117.8), "Office (Irvine)", "Fixture address", "fixture-place")
    private val home = SavedScenario("home", "Home", "SNAP", 1L, false, null, "", real, "Home pin",
        TravelMode.DRIVE, emptyMap(), Realism.REALISTIC, null, emptyList(), destAddress = "Home fixture address")

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; PlaybackSource.clearQueue()
        Conversation.resetPlanningSession()
    }
    @After fun cleanup() {
        Conversation.resetPlanningSession()
        MockState.reset(); LiveSession.clear(); PlaybackSource.current = null; PlaybackSource.clearQueue()
        Dispatchers.resetMain()
    }

    @Test fun newItineraryClearsIdentityStopsTimingUndoAndRecoveryGeneration() {
        val old = home.copy(id = "old-day", name = "Old day", kind = "ITINERARY", start = office.latLng, startName = "Old start",
            stops = listOf(ItineraryStop("Old stop", real, 60, TravelMode.WALK, 3f).toSavedStop()),
            departureMillis = 1234L, defaultStayMinutes = 45, stayUntilLeave = true, entrance = office.latLng)
        val store = InMemoryScenarioStore().apply { save(listOf(home, old)) }
        val vm = MirageViewModel().apply {
            configureApi(ApiConfig.NONE); attachStore(store); useMyLocation(real); loadScenario(old)
            setDwell(0, 75); dwellEditIndex = 0; hiking.open(real); timeScale = 5f
        }
        val oldDraft = vm.draftSnapshot()!!
        val savedBefore = store.load()
        val generation = vm.draftGeneration
        assertTrue(vm.draftUndoAvailable)

        assertTrue(vm.startNewItinerary())

        assertEquals(PlanMode.ITINERARY, vm.planMode)
        assertEquals(real, vm.start); assertEquals("My location", vm.startName)
        assertTrue(vm.startFromReal); assertFalse(vm.useSimulatedStart); assertFalse(vm.queueAfterCurrent)
        assertNull(vm.dest); assertTrue(vm.stops.isEmpty()); assertNull(vm.draftSavedName)
        assertNull(vm.draftSnapshot()); assertFalse(vm.canSaveScenario); assertFalse(vm.canStart)
        assertNull(vm.departureMillis); assertEquals(0, vm.defaultStayMinutes); assertFalse(vm.stayUntilLeave)
        assertNull(vm.arrivalEntrance); assertNull(vm.dwellEditIndex); assertNull(vm.hiking.state); assertNull(vm.continuation.state)
        assertFalse(vm.draftUndoAvailable); vm.undoDraftStops(); assertTrue(vm.stops.isEmpty())
        assertEquals(Phase.IDLE, vm.phase); assertTrue(vm.routePts.isEmpty()); assertEquals(1f, vm.timeScale, 0f)
        assertTrue(vm.draftGeneration > generation)
        assertFalse(TripRecovery.saveDraftIfCurrent(oldDraft, generation))
        assertNull(TripRecovery.draft()); assertEquals(savedBefore, store.load())

        vm.pickResolvedPlace(office, Field.END)
        assertTrue(vm.saveScenario("Fresh day"))
        assertEquals(3, store.load().size)
        assertEquals(savedBefore.first { it.id == "old-day" }, store.load().first { it.id == "old-day" })
        assertNotEquals("old-day", store.load().single { it.name == "Fresh day" }.id)
        assertFalse(MockState.status.value.running); assertNull(PlaybackSource.current)
    }

    @Test fun newItineraryDropsPreparedRouteAndDoesNotInventAStartWithoutRealLocation() {
        val vm = MirageViewModel().apply {
            setStartPoint(real, "Old explicit start"); setDestPoint(office.latLng, office.name)
            chooseMode(TravelMode.FLY); buildRoute()
        }
        assertTrue(vm.canStart); assertEquals(Phase.READY, vm.phase); assertTrue(vm.routePts.isNotEmpty())
        assertTrue(vm.startNewItinerary())
        assertNull(vm.start); assertEquals("", vm.startName); assertNull(vm.tripStart())
        assertNull(vm.dest); assertFalse(vm.canStart); assertTrue(vm.routePts.isEmpty())
        assertEquals(0.0, vm.routeDistanceM, 0.0); assertEquals(Phase.IDLE, vm.phase)
        vm.useMyLocation(real)
        assertEquals(real, vm.tripStart()); assertTrue(vm.startFromReal)
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun newItineraryCancelsLateTypeaheadAndVoiceResolution() = runTest {
        val result = CompletableDeferred<List<PlaceHit>>()
        val vm = MirageViewModel { _, _, _ -> withContext(NonCancellable) { result.await() } }.apply {
            configureApi(ApiConfig(null, "fixture", "session-test")); useMyLocation(real)
        }
        SmartVoice.bindPlanner(vm)
        vm.search("old destination") {}
        Conversation.submitPlanner("drive to Office", Field.END, PlanMode.ROUTE)
        assertTrue(vm.suggestBusy); assertTrue(Conversation.state.value.busy)

        assertTrue(vm.startNewItinerary())
        result.complete(listOf(office))
        advanceUntilIdle()

        assertTrue(vm.suggestions.isEmpty()); assertTrue(vm.savedPlaceSuggestions.isEmpty()); assertFalse(vm.suggestBusy)
        assertTrue(vm.stops.isEmpty()); assertNull(vm.dest); assertNull(vm.error)
        assertEquals(ChatView(), Conversation.state.value); assertFalse(SmartVoice.needsReply())
        Conversation.submitPlanner("yes", Field.END, PlanMode.ITINERARY)
        assertTrue(vm.stops.isEmpty()); assertNull(PlaybackSource.current)
    }

    @Test fun resetRefusesRunningOrStartingSimulationWithoutChangingDraftOrPlayback() {
        val vm = MirageViewModel().apply { setStartPoint(real); setDestPoint(office.latLng, office.name) }
        val before = vm.draftSnapshot()
        val playing = emptyFlow<Fix>()
        PlaybackSource.current = playing
        PlaybackSource.paused = true
        for (running in listOf(true, false)) {
            MockState.update { it.copy(running = running, starting = !running) }
            assertFalse(vm.startNewItinerary())
            assertEquals(before, vm.draftSnapshot()); assertSame(playing, PlaybackSource.current)
            assertTrue(PlaybackSource.paused)
        }
        PlaybackSource.paused = false
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun resetRemovesConversationHistoryChoicesAndPriorConfirmations() = runTest {
        val vm = MirageViewModel().apply { attachStore(InMemoryScenarioStore().apply { save(listOf(home)) }) }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("drive to Home", Field.END, PlanMode.ROUTE)
        assertTrue(SmartVoice.needsReply()); assertTrue(Conversation.state.value.lines.isNotEmpty())
        Conversation.smartChoices(listOf(office))

        assertTrue(vm.startNewItinerary())

        assertEquals(ChatView(), Conversation.state.value); assertFalse(Conversation.needsReply())
        Conversation.submitPlanner("yes", Field.END, PlanMode.ITINERARY)
        advanceUntilIdle()
        assertTrue(vm.stops.isEmpty()); assertNull(vm.dest); assertNull(PlaybackSource.current)
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun plannerVoiceAppliesSavedPlaceToChosenStartAndRouteDestination() = runTest {
        val vm = MirageViewModel().apply {
            configureApi(ApiConfig.NONE); attachStore(InMemoryScenarioStore().apply { save(listOf(home)) })
            setStartPoint(office.latLng, office.name)
        }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("drive to Home", Field.START, PlanMode.ROUTE)
        assertEquals(office.latLng, vm.start)
        Conversation.submitPlanner("yes", Field.START, PlanMode.ROUTE)
        assertEquals(real, vm.start); assertEquals("Home", vm.startName); assertNull(vm.dest)
        Conversation.submitPlanner("drive to Home", Field.END, PlanMode.ROUTE)
        Conversation.submitPlanner("yes", Field.END, PlanMode.ROUTE)
        assertEquals(real, vm.dest); assertEquals("Home", vm.destName); assertEquals(home.destAddress, vm.destAddress)
        assertTrue(vm.stops.isEmpty()); assertEquals(PlanMode.ROUTE, vm.planMode)
        assertFalse(MockState.status.value.running); assertNull(PlaybackSource.current)
        vm.choosePlanMode(PlanMode.SNAP)
        Conversation.submitPlanner("snap to Home", Field.END, PlanMode.SNAP)
        Conversation.submitPlanner("yes", Field.END, PlanMode.SNAP)
        assertEquals(real, vm.dest); assertEquals("Home", vm.destName); assertEquals(PlanMode.SNAP, vm.planMode)
        assertNull(PlaybackSource.current)
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun plannerSnapPreparesDestinationAndItineraryAppendsWithoutStarting() = runTest {
        val vm = MirageViewModel { _, _, _ -> listOf(office) }.apply {
            configureApi(ApiConfig(null, "fixture", "session-test")); useMyLocation(real); choosePlanMode(PlanMode.SNAP)
        }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("snap to Office", Field.END, PlanMode.SNAP)
        Conversation.submitPlanner("yes", Field.END, PlanMode.SNAP)
        assertEquals(office.latLng, vm.dest); assertEquals(office.address, vm.destAddress)
        assertEquals(PlanMode.SNAP, vm.planMode); assertTrue(vm.stops.isEmpty()); assertNull(PlaybackSource.current)

        vm.startNewItinerary()
        Conversation.submitPlanner("walk to Office then stay for thirty minutes", Field.END, PlanMode.ITINERARY)
        Conversation.submitPlanner("yes", Field.END, PlanMode.ITINERARY)
        assertNull(vm.dest); assertEquals(1, vm.stops.size)
        assertEquals(office.name, vm.stops.single().name); assertEquals(30, vm.stops.single().dwellMinutes)
        assertEquals(TravelMode.WALK, vm.stops.single().mode); assertFalse(MockState.status.value.running)
        assertNull(PlaybackSource.current)
    }

    @Test fun compoundAndUnknownPlannerInstructionsNeverReachLegacyAutoStart() = runTest {
        var searches = 0
        val vm = MirageViewModel { _, _, _ -> searches++; listOf(office) }.apply {
            configureApi(ApiConfig(null, "fixture", "session-test")); useMyLocation(real)
        }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("drive to Office then walk to Home", Field.END, PlanMode.ROUTE)
        Conversation.submitPlanner("go there now instead", Field.END, PlanMode.ROUTE)
        Conversation.submitPlanner("start simulation", Field.END, PlanMode.ROUTE)
        advanceUntilIdle()
        assertEquals(0, searches); assertNull(vm.dest); assertTrue(vm.stops.isEmpty())
        assertFalse(MockState.status.value.running); assertNull(PlaybackSource.current)
        assertFalse(SmartVoice.needsReply())
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun changingFieldFeedbackCancelsAnUnconfirmedChoice() = runTest {
        val vm = MirageViewModel { _, _, _ -> listOf(office, office.copy(name = "Other office")) }.apply {
            configureApi(ApiConfig(null, "fixture", "session-test")); useMyLocation(real)
        }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("drive to Office", Field.END, PlanMode.ROUTE)
        assertEquals(2, Conversation.state.value.choices.size)
        Conversation.clearPlannerFeedback()
        assertEquals(ChatView(), Conversation.state.value); assertFalse(SmartVoice.needsReply())
        Conversation.choose(0)
        Conversation.submitPlanner("yes", Field.START, PlanMode.ROUTE)
        assertEquals(real, vm.start); assertNull(vm.dest); assertNull(PlaybackSource.current)
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun spokenChoiceThenConfirmationKeepsThePendingFieldAndAppliesItsSelectedPlace() = runTest {
        val other = office.copy(name = "Other office", latLng = LatLng(34.0, -118.0))
        val vm = MirageViewModel { _, _, _ -> listOf(office, other) }.apply {
            configureApi(ApiConfig(null, "fixture", "session-test")); useMyLocation(real)
        }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("drive to Office", Field.END, PlanMode.ROUTE, spoken = true)
        assertEquals(listOf(office, other), Conversation.state.value.choices)
        Conversation.submitPlanner("one", Field.END, PlanMode.ROUTE, spoken = true)
        assertTrue(SmartVoice.needsReply()); assertTrue(Conversation.state.value.choices.isEmpty())
        assertNull(vm.dest)
        Conversation.submitPlanner("yes", Field.END, PlanMode.ROUTE, spoken = true)
        assertEquals(office.latLng, vm.dest); assertEquals(office.name, vm.destName)
        assertEquals(real, vm.start); assertTrue(vm.stops.isEmpty()); assertFalse(SmartVoice.needsReply())
        assertNull(PlaybackSource.current); assertFalse(MockState.status.value.running)
    }

    @Category(ExperimentalLanguageModelTests::class)
    @Test fun staleConfirmationCannotMoveThePendingResultIntoAnotherField() = runTest {
        val vm = MirageViewModel { _, _, _ -> listOf(office) }.apply {
            configureApi(ApiConfig(null, "fixture", "session-test")); useMyLocation(real)
        }
        SmartVoice.bindPlanner(vm)
        Conversation.submitPlanner("drive to Office", Field.END, PlanMode.ROUTE)
        assertTrue(SmartVoice.needsReply())
        vm.activeField = Field.START
        // A delayed callback still carries END: it must not change the active field back.
        Conversation.submitPlanner("yes", Field.END, PlanMode.ROUTE)
        assertEquals(Field.START, vm.activeField); assertEquals(real, vm.start); assertNull(vm.dest)
        assertFalse(SmartVoice.needsReply()); assertNull(PlaybackSource.current)
        Conversation.submitPlanner("drive to Office", Field.END, PlanMode.ROUTE)
        vm.activeField = Field.START
        // A generic/background confirmation also has to honor the captured planner field.
        Conversation.submit("yes")
        assertEquals(real, vm.start); assertNull(vm.dest); assertFalse(SmartVoice.needsReply())
    }
}
