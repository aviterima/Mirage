package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirage.spike.audit.*
import com.mirage.spike.engine.ArrivalActivity
import com.mirage.spike.engine.ActivityKind
import com.mirage.spike.engine.ApiCheck
import com.mirage.spike.engine.DriveModel
import com.mirage.spike.engine.Signal
import com.mirage.spike.engine.LivePlan
import com.mirage.spike.engine.LiveSession
import com.mirage.spike.engine.PreparedLeg
import com.mirage.spike.engine.prepareLeg
import com.mirage.spike.engine.ApiConfig
import com.mirage.spike.engine.Fix
import com.mirage.spike.engine.KeyTester
import com.mirage.spike.engine.FlightModel
import com.mirage.spike.engine.Geo
import com.mirage.spike.engine.GoogleDirectionsRouteEngine
import com.mirage.spike.engine.GoogleGeocoder
import com.mirage.spike.engine.GooglePlaces
import com.mirage.spike.engine.ItineraryModel
import com.mirage.spike.engine.ItineraryStop
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.MotionModel
import com.mirage.spike.engine.MotionParams
import com.mirage.spike.engine.PlaceHit
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.RouteResult
import com.mirage.spike.engine.RouteSegment
import com.mirage.spike.engine.RouteSpec
import com.mirage.spike.engine.TransitModel
import com.mirage.spike.engine.TravelMode
import com.mirage.spike.store.InMemoryScenarioStore
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.store.SavedStop
import com.mirage.spike.store.ScenarioStore
import com.mirage.spike.store.savedItemSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Phase { IDLE, ROUTING, READY, RUNNING }

/** Which of the two location boxes a search result / pick applies to. */
enum class Field { START, END }

/** The three things Mirage does: be somewhere, travel A to B, or chain a day of stops. */
enum class PlanMode { SNAP, ROUTE, ITINERARY }

/** Drives the map screen: endpoints, routing, average speed, itinerary, and simulation control. */
class MirageViewModel(
    val planInterpret: suspend (String, String) -> PlanInterpretation = NaturalLanguageInput::interpretPlan,
    val placeSearch: suspend (ApiConfig, String, LatLng?) -> List<PlaceHit> = { cfg, query, bias -> GooglePlaces(cfg).searchMany(query, bias, 8) },
) : ViewModel() {
    val planning = PlannerReviewController(this, viewModelScope, planInterpret)
    /** A diagnostic snapshot deliberately has no place names, coordinates or input text. */
    fun auditState(field: Field? = activeField) = AuditState(
        planMode = planMode, phase = phase, field = field, stopCount = stops.size,
        hasStart = tripStart() != null, hasDestination = dest != null,
        running = MockState.status.value.running, paused = PlaybackSource.paused,
        startFromReal = startFromReal, useSimulatedStart = useSimulatedStart,
        queueAfterCurrent = queueAfterCurrent,
    )
    internal fun auditEvent(action: AuditAction, outcome: AuditOutcome,
        details: AuditDetails = AuditDetails(), operation: Long? = null, field: Field? = activeField) {
        AuditTrail.record(action, outcome, auditState(field), details, operation)
    }
    val continuation = ContinuationPlanner(this, viewModelScope)
    val hiking = com.mirage.spike.hiking.HikingPlanner(viewModelScope,
        places = { query, near -> placeSearch(api, query, near) },
        route = { spec -> GoogleDirectionsRouteEngine(api).route(spec) })

    fun refreshHikingLocation(point: LatLng) { lastReal=point }
    fun hikingLocation(): com.mirage.spike.hiking.HikingLocation? {
        val st=MockState.status.value
        return if(st.running) com.mirage.spike.hiking.HikingLocation(LatLng(st.lat,st.lng),true)
        else lastReal?.let {com.mirage.spike.hiking.HikingLocation(it,false)}
    }
    fun hikingOrigin(): LatLng? = if(MockState.status.value.running) hikingLocation()?.point
        else if(planMode==PlanMode.ITINERARY && stops.isNotEmpty()) stops.last().point
        else if(planMode==PlanMode.ROUTE && dest!=null) dest else tripStart()

    fun addHikingTrip(trip: com.mirage.spike.hiking.HikingTrip) {
        val saved = SavedScenario(java.util.UUID.randomUUID().toString(), "Hike · ${trip.trail.name}", "ITINERARY",
            System.currentTimeMillis(), false, trip.origin, "Hiking trip start", trip.stops.last().point,
            trip.stops.last().name, TravelMode.DRIVE, emptyMap(), Realism.REALISTIC, null,
            trip.stops.map { it.toSavedStop() })
        if (MockState.status.value.running) continuation.beginSaved(saved)
        else {
            if(planMode==PlanMode.ROUTE && dest!=null) choosePlanMode(PlanMode.ITINERARY)
            if(planMode==PlanMode.ITINERARY && stops.isNotEmpty()) { stops.addAll(trip.stops); invalidateRoute() }
            else { if (!loadScenario(saved)) return; loadedScenario=null }
            notice = "Hike added. Review the itinerary, then tap Start."
        }
    }
    var arrivalActivity by mutableStateOf(ArrivalActivity.BUILDING)
    var arrivalEntrance by mutableStateOf<LatLng?>(null)
    var stayUntilLeave by mutableStateOf(false)
    var defaultStayMinutes by mutableStateOf(0)

    // ---- Endpoints -----------------------------------------------------------
    // No made-up default: until a real fix (or a pick) arrives the start is simply unset.
    var start by mutableStateOf<LatLng?>(null)
        private set
    var startName by mutableStateOf("")
        private set
    private var startAddress = ""
    private var startPlaceId = ""
    /** True when the start came from the phone's real position (not a pick/pin). */
    var startFromReal by mutableStateOf(false)
        private set
    var dest by mutableStateOf<LatLng?>(null)
        private set
    var destAddress by mutableStateOf("")
        private set
    var destPlaceId by mutableStateOf("")
        private set
    var destName by mutableStateOf("Destination")
        private set
    /** The box the user is editing; search results and picks land there. */
    var activeField by mutableStateOf(Field.END)
    /** Last real (non-mock) position we obtained; the only real fix available while spoofing. */
    var lastReal by mutableStateOf<LatLng?>(null)
        private set
    /** While simulating, the next trip normally begins where the simulation is right now. */
    var useSimulatedStart by mutableStateOf(true)
        private set
    /** While simulating: the next plan starts where the CURRENT one ends, and is queued behind it. */
    var queueAfterCurrent by mutableStateOf(false)
        private set
    /** One-line notice after queuing ("Queued: …"), cleared on the next change. */
    var notice by mutableStateOf<String?>(null)

    // ---- Route ---------------------------------------------------------------
    var routePts by mutableStateOf<List<LatLng>>(emptyList())
        private set
    var routeDistanceM by mutableStateOf(0.0)
        private set
    // Observable so the Start button re-evaluates the moment a route lands or is cleared.
    private var lastRoute by mutableStateOf<RouteResult?>(null)
    private var isFlight by mutableStateOf(false)
    private var flightOrigin by mutableStateOf<LatLng?>(null)

    // ---- Motion settings -----------------------------------------------------
    /** Each transport mode keeps its OWN speed (mph) — a walker and a car never share a slider. */
    val modeSpeeds = mutableStateMapOf(
        TravelMode.DRIVE to defaultSpeed(TravelMode.DRIVE),
        TravelMode.BIKE to defaultSpeed(TravelMode.BIKE),
        TravelMode.WALK to defaultSpeed(TravelMode.WALK),
    )
    var avgMph: Float
        get() = modeSpeeds[mode] ?: defaultSpeed(mode)
        set(v) { modeSpeeds[mode] = v }
    var realism by mutableStateOf(Realism.REALISTIC)
    var mode by mutableStateOf(TravelMode.DRIVE)
    /** Transit vehicle filter for Directions (null = any): bus, subway, train, tram, rail. */
    var transitPref by mutableStateOf<String?>(null)
        private set
    fun chooseTransitPref(p: String?) { transitPref = p; invalidateRoute() }
    /** The scheduled walk/ride legs of a plotted transit route (empty otherwise). */
    val transitSegments: List<RouteSegment> get() = lastRoute?.segments.orEmpty()
    /** Fast-forward for testing: positions advance N× faster than real time. Live: applies
     *  to whatever is playing the moment it changes. */
    private var timeScaleState by mutableStateOf(1f)
    var timeScale: Float
        get() = timeScaleState
        set(v) { timeScaleState = v; if (!MockState.status.value.running) PlaybackSource.timeScale = v.toDouble() }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)

    // ---- Itinerary: an ordered list of stops with a dwell time at each ------
    val stops = mutableStateListOf<ItineraryStop>()
    var planMode by mutableStateOf(PlanMode.ROUTE)
        private set
    var itineraryBusy by mutableStateOf(false)
        private set
    val dwellTotalMinutes: Int get() = stops.sumOf { it.dwellMinutes }

    // ---- Type-ahead: matching places for what the user is typing ------------
    val savedPlaceSuggestions = mutableStateListOf<SavedScenario>()
    val suggestions = mutableStateListOf<PlaceHit>()
    var suggestBusy by mutableStateOf(false)
        private set
    private var suggestJob: Job? = null
    private var searchSerial = 0L
    private var suggestedField = Field.END
    fun editLocationQuery(field: Field, query: String, bias: LatLng?, preserveSelection: Boolean = false) {
        activeField = field
        if ((field == Field.START && start != null) || (field == Field.END && dest != null))
            auditEvent(AuditAction.INPUT_EDIT, AuditOutcome.REQUESTED, AuditDetails(control = AuditControl.EDIT))
        // The planner buffers text because a partial word can become an instruction.
        // Only a place selection or an explicit Clear changes its resolved endpoint.
        if (!preserveSelection) {
            if (field == Field.END) { dest = null; destName = ""; destAddress = ""; destPlaceId = "" }
            else { startAddress = ""; startPlaceId = ""; start = null; startName = ""; startFromReal = false; useSimulatedStart = false; queueAfterCurrent = false }
            invalidateRoute()
        }
        suggest(query, bias)
    }

    // ---- API access: built-in key, the user's own key, or Mirage's hosted gateway -------
    var api by mutableStateOf(ApiConfig(BuildConfig.MIRAGE_API_BASE, BuildConfig.MAPS_API_KEY, ""))
        private set
    private var routeEngine = GoogleDirectionsRouteEngine(api)
    private var geocoder = GoogleGeocoder(api)
    private var places = GooglePlaces(api)
    val hasKey: Boolean get() = api.enabled
    /** Result of the last "Test key" run, per API; null = not run yet. */
    var keyTest by mutableStateOf<List<ApiCheck>?>(null)
        private set
    var keyTesting by mutableStateOf(false)
        private set

    fun configureApi(cfg: ApiConfig) {
        api = cfg
        routeEngine = GoogleDirectionsRouteEngine(cfg)
        geocoder = GoogleGeocoder(cfg)
        places = GooglePlaces(cfg)
        keyTest = null
    }

    /** Try every API with a tiny request and keep Google's exact reason for anything that fails. */
    fun testKey(cfg: ApiConfig = api) {
        viewModelScope.launch {
            keyTesting = true
            try { keyTest = KeyTester(cfg).test() } finally { keyTesting = false }
        }
    }

    /** An explicit user choice of start (pin, search pick, ⌖ menu). */
    fun setStartPoint(p: LatLng, name: String = "Dropped pin", address: String = "", placeId: String = "") {
        startAddress = address; startPlaceId = placeId;
        start = p; startName = name; startFromReal = false; useSimulatedStart = false; queueAfterCurrent = false; invalidateRoute()
        auditEvent(AuditAction.START_SOURCE, AuditOutcome.SUCCEEDED, field = Field.START)
    }

    /** ⌖ → "My real location" chosen by the user for the Start box. */
    fun pickRealStart(p: LatLng) {
        lastReal = p
        startAddress = ""; startPlaceId = ""
        start = p; startName = "My location"; startFromReal = true; useSimulatedStart = false; queueAfterCurrent = false; invalidateRoute()
        auditEvent(AuditAction.START_SOURCE, AuditOutcome.SUCCEEDED, AuditDetails(source = AuditSource.REAL_LOCATION))
    }

    /** ⌖ → "Where the current trip ends": plan from there and queue behind the current plan. */
    fun useTripEnd() { queueAfterCurrent = true; useSimulatedStart = false; invalidateRoute(); auditEvent(AuditAction.START_SOURCE, AuditOutcome.SUCCEEDED, AuditDetails(source = AuditSource.TRIP_END)) }

    /** Back to "begin where the simulation is now" (only meaningful while running). */
    fun useSimulatedPosition() { useSimulatedStart = true; queueAfterCurrent = false; invalidateRoute(); auditEvent(AuditAction.START_SOURCE, AuditOutcome.SUCCEEDED, AuditDetails(source = AuditSource.SIMULATED_LOCATION)) }

    /** Where the next trip begins: the live simulated position while running (unless the
     *  user chose an explicit start), else the start pin. */
    fun tripStart(): LatLng? {
        val st = MockState.status.value
        return when {
            st.running && queueAfterCurrent -> PlaybackSource.queueEnd() ?: LiveSession.state.value.stops.lastOrNull()?.stop?.point ?: PlaybackSource.endPoint ?: LatLng(st.lat, st.lng)
            st.running && useSimulatedStart -> LatLng(st.lat, st.lng)
            else -> start
        }
    }

    /** Swap resolved endpoints, never reverse an old road polyline or move the live simulation. */
    fun swapEndpoints(): Boolean {
        if (planMode != PlanMode.ROUTE) return false
        val origin = tripStart() ?: return false
        val destination = dest ?: return false
        val dynamicOrigin = MockState.status.value.running && (useSimulatedStart || queueAfterCurrent)
        val originName = when {
            dynamicOrigin && queueAfterCurrent -> "Where the current trip ends"
            dynamicOrigin -> "Current simulated position"
            else -> startName
        }
        val originAddress = if (dynamicOrigin) "" else startAddress
        val originPlaceId = if (dynamicOrigin) "" else startPlaceId
        clearSuggestions()
        autoStartAfterRoute = null
        setStartPoint(destination, destName, destAddress, destPlaceId)
        dest = origin; destName = originName
        destAddress = originAddress; destPlaceId = originPlaceId
        activeField = Field.END
        error = null
        // A return journey may use different roads. Prepare new directions without starting it.
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.SWAP))
        if (mode == TravelMode.FLY || hasKey) buildRoute()
        return true
    }

    // ---- Live controls while simulating ----------------------------------------------

    fun pause() { PlaybackSource.paused = true; auditEvent(AuditAction.SIMULATION, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.PAUSE)) }
    fun resume() { PlaybackSource.paused = false; auditEvent(AuditAction.SIMULATION, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.RESUME)) }
    /** Jump to the end of the leg / stay that is playing right now. */
    fun skipAhead() { PlaybackSource.requestSkip(); auditEvent(AuditAction.SIMULATION, AuditOutcome.REQUESTED, AuditDetails(control = AuditControl.SKIP)) }

    /** Driving: cruise this many mph over the posted limit (live; may be negative). */
    private var overLimitState by mutableStateOf(5f)
    var speedOverLimit: Float
        get() = overLimitState
        set(v) { overLimitState = v; if (!MockState.status.value.running) PlaybackSource.speedOverLimitMph = v.toDouble() }

    /** Simulated GPS quality (live). */
    var signal by mutableStateOf(Signal.GOOD)
        private set
    fun setSignalPreset(s: Signal) { signal = s; PlaybackSource.signal = s.copy(dropUntilMillis = PlaybackSource.signal.dropUntilMillis) }
    /** Lose the fix entirely for [seconds] (live), then recover. */
    fun dropSignal(seconds: Int) { PlaybackSource.signal = PlaybackSource.signal.withDropout(seconds) }

    /** Itinerary helpers while simulating: stay here, or continue from where the trip ends. */
    fun addStopAtSimulatedPosition(dwellMinutes: Int = 30) {
        val st = MockState.status.value
        if (!st.running) { error = "Nothing is being simulated right now"; return }
        stops.add(ItineraryStop("Current position", LatLng(st.lat, st.lng), dwellMinutes, mode, avgMph)); invalidateRoute()
    }
    fun addStopAtTripEnd(dwellMinutes: Int = 30) {
        val e = PlaybackSource.endPoint ?: run { error = "No trip is playing"; return }
        stops.add(ItineraryStop("Where the trip ends", e, dwellMinutes, mode, avgMph)); invalidateRoute()
    }
    /**
     * AUTOMATIC real fix (launch, after Stop). Adopts it as the start only when that does
     * not destroy the user's work: no start yet, or a start that already came from a real
     * fix. Explicit starts are protected even before routing. Never touches
     * [useSimulatedStart] — that is the user's choice.
     */
    fun useMyLocation(p: LatLng) {
        lastReal = p
        if (start != null && !startFromReal) return
        val moved = start?.let { Geo.haversine(it, p) >= 100.0 } ?: true
        startAddress = ""; startPlaceId = ""
        start = p; startName = "My location"; startFromReal = true
        if (moved) invalidateRoute()
    }

    /**
     * A better real fix arrived a little later. Only refine a start that came from a real
     * fix, and only invalidate a built route if the position actually moved (> 100 m) and
     * nothing is playing.
     */
    fun refineMyLocation(p: LatLng) {
        lastReal = p
        if (!startFromReal) return
        val cur = start
        if (cur != null && (Geo.haversine(cur, p) < 100.0 || MockState.status.value.running)) { start = p; return }
        startAddress = ""; startPlaceId = ""
        start = p; startName = "My location"; startFromReal = true
        invalidateRoute()
    }

    /** A Snap started: the next plan begins from where the simulation now is. */
    fun onSnapStarted() { useSimulatedStart = true; auditEvent(AuditAction.SIMULATION, AuditOutcome.REQUESTED, AuditDetails(control = AuditControl.START)) }
    /**
     * The End box / map tap. In Itinerary mode a chosen place is appended to the chain
     * as the next stop (with the mode and speed currently set), so building a day is
     * simply: pick, pick, pick.
     */
    fun setEntrance(p: LatLng?) {
        arrivalEntrance=p
        if(planMode==PlanMode.ITINERARY && stops.isNotEmpty()) stops[stops.lastIndex]=stops.last().copy(entrance=p,frozenRoute=null)
        invalidateRoute()
    }
    fun fineTuneDestination(p: LatLng) {
        if(planMode==PlanMode.ITINERARY && stops.isNotEmpty()) stops[stops.lastIndex]=stops.last().copy(point=p,frozenRoute=null)
        else dest=p
        invalidateRoute()
    }
    fun setDestPoint(p: LatLng, name: String = "Dropped pin", entrance: LatLng? = null) {
        arrivalEntrance = entrance
        destAddress = ""; destPlaceId = ""
        if (planMode == PlanMode.ITINERARY) {
            stops.add(ItineraryStop(name, p, defaultStayMinutes, mode, avgMph, arrivalActivity=arrivalActivity,entrance=arrivalEntrance,stayUntilLeave=stayUntilLeave))
            dest = null; destName = ""; error = null
            invalidateRoute()
        } else {
            dest = p; destName = name; invalidateRoute()
        }
        auditEvent(AuditAction.PLACE_SELECT, AuditOutcome.SUCCEEDED, field = Field.END)
    }

    fun choosePlanMode(m: PlanMode) {
        if (m == planMode) return
        // Carry a chosen End into the itinerary as a stop, and a single stop back out as the End.
        if (m == PlanMode.ITINERARY) {
            val d = dest
            if (d != null && stops.lastOrNull()?.point != d) stops.add(ItineraryStop(destName, d, defaultStayMinutes, mode, avgMph,destAddress,destPlaceId,frozenRoute=if(replayExactRoute)lastRoute else null,arrivalActivity=arrivalActivity,entrance=arrivalEntrance,stayUntilLeave=stayUntilLeave))
            dest = null; destName = ""
        } else if (planMode == PlanMode.ITINERARY && stops.size == 1) {
            val st = stops[0]; dest = st.point; destName = st.name
            destAddress=st.address;destPlaceId=st.placeId;arrivalActivity=st.arrivalActivity
            arrivalEntrance=st.entrance;stayUntilLeave=st.stayUntilLeave;defaultStayMinutes=st.dwellMinutes
            stops.clear()
        }
        planMode = m
        invalidateRoute()
        auditEvent(AuditAction.PLANNER_MODE, AuditOutcome.SUCCEEDED)
    }
    fun clearError() { error = null }

    /** Begin a separate unsaved plan without deleting saved items or touching playback. */
    fun startNewItinerary(): Boolean {
        if (MockState.status.value.let { it.running || it.starting }) {
            error = "Stop the current simulation before starting a new itinerary."
            auditEvent(AuditAction.PLANNER_MODE, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.REMOVE, reason = AuditReason.NOT_READY))
            return false
        }
        // The durable tombstone and input reset must succeed before losing any current work.
        if (!TripRecovery.clearDraft()) {
            error = TripRecovery.failure
            auditEvent(AuditAction.PLANNER_MODE, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.REMOVE, reason = AuditReason.STORAGE))
            return false
        }
        planning.reset()
        FieldSpeech.cancelPlanner()
        Conversation.resetPlanningSession()
        continuation.cancel()
        hiking.close()
        clearSuggestions()
        autoStartAfterRoute = null
        invalidateRoute()
        loadedScenario = null
        stops.clear()
        draftUndo = null; plannerUndo = null; draftUndoAvailable = false; dwellEditIndex = null
        start = lastReal; startName = if (lastReal != null) "My location" else ""
        startAddress = ""; startPlaceId = ""; startFromReal = true
        useSimulatedStart = false; queueAfterCurrent = false
        dest = null; destName = ""; destAddress = ""; destPlaceId = ""
        arrivalActivity = ArrivalActivity.BUILDING; arrivalEntrance = null
        stayUntilLeave = false; defaultStayMinutes = 0; departureMillis = null
        mode = TravelMode.DRIVE; realism = Realism.REALISTIC; transitPref = null
        modeSpeeds.clear()
        listOf(TravelMode.DRIVE, TravelMode.BIKE, TravelMode.WALK).forEach { modeSpeeds[it] = defaultSpeed(it) }
        timeScaleState = 1f
        planMode = PlanMode.ITINERARY; activeField = Field.END
        phase = Phase.IDLE; itineraryBusy = false; lastLocalAudit = null
        error = null
        auditEvent(AuditAction.PLANNER_MODE, AuditOutcome.SUCCEEDED,
            AuditDetails(control = AuditControl.REMOVE))
        return true
    }

    private var routeJob: Job? = null

    private fun invalidateRoute() {
        replayExactRoute=false
        notice = null
        routeJob?.cancel(); routeJob = null
        routePts = emptyList(); lastRoute = null; isFlight = false; flightOrigin = null; routeDistanceM = 0.0
        if (phase == Phase.READY || phase == Phase.ROUTING) phase = Phase.IDLE
    }

    /** True when Start can actually play something (a routed drive or a plotted flight). */
    val canStart: Boolean get() = lastRoute != null || (isFlight && flightOrigin != null)

    fun chooseMode(m: TravelMode) { mode = m; invalidateRoute() }

    /** Distance and a time estimate at the chosen speed, once a route/flight is plotted. */
    val routeSummary: String?
        get() {
            if (planMode != PlanMode.ROUTE || routePts.isEmpty() || routeDistanceM <= 0.0) return null
            val r = lastRoute
            if (mode == TravelMode.TRANSIT && r != null) {
                val rides = r.segments.count { it.transit != null }
                val arrive = r.segments.lastOrNull { it.transit != null }?.transit?.arrivalText
                val ff = if (timeScale > 1f) " · ${timeScale.toInt()}× fast-forward" else ""
                return "${fmtMiles(routeDistanceM)} · ${fmtDuration(r.durationSeconds / timeScale)} · $rides ${if (rides == 1) "ride" else "rides"}" +
                    (arrive?.let { " · arrive $it" } ?: "") + ff
            }
            val secs = if (isFlight) {
                routeDistanceM / 245.0 * 1.12
            } else {
                val base = routeDistanceM / (avgMph * 0.44704)
                base * when (realism) {
                    Realism.CONSTANT -> 1.0
                    Realism.REALISTIC -> 1.08
                    Realism.BUSY -> 1.25
                }
            }
            val ff = if (timeScale > 1f) " · ${timeScale.toInt()}× fast-forward" else ""
            return "${fmtMiles(routeDistanceM)} · about ${fmtDuration(secs / timeScale)}$ff"
        }

    // ---- Search ----------------------------------------------------------------

    private var lastLocalAudit: Pair<Field, Int>? = null

    /** Last keystroke and active field win, including uncancellable responses. */
    fun suggest(query: String, bias: LatLng?) = requestSearch(query, bias, true)
    private fun requestSearch(query: String, bias: LatLng?, debounce: Boolean) {
        clearSuggestions()
        val q = query.trim()
        suggestedField = activeField
        error = null
        if (q.isNotEmpty()) {
            savedPlaceSuggestions.addAll(savedItemSearch(savedScenarios, q, PlanMode.SNAP.name).filter { it.dest != null })
        }
        // Record result-count changes, never keystrokes or query content.
        val localAudit = activeField to savedPlaceSuggestions.size
        if (q.isNotEmpty() && lastLocalAudit != localAudit) {
            auditEvent(AuditAction.SEARCH, AuditOutcome.SUCCEEDED,
                AuditDetails(source = AuditSource.SAVED_PLACE, localMatches = savedPlaceSuggestions.size))
        }
        lastLocalAudit = localAudit.takeIf { q.isNotEmpty() }
        if (q.length < 2 || !hasKey) return
        val serial = searchSerial
        val field = activeField
        suggestBusy = true
        suggestJob = viewModelScope.launch {
            var operation: Long? = null
            var started = 0L
            val state = auditState(field)
            try {
                if (debounce) delay(350)
                operation = AuditTrail.newOperation()
                started = System.nanoTime()
                AuditTrail.record(AuditAction.SEARCH, AuditOutcome.REQUESTED, state,
                    AuditDetails(source = AuditSource.TYPEAHEAD, localMatches = savedPlaceSuggestions.size), operation)
                val hits = placeSearch(api, q, bias ?: tripStart())
                if (!isActive || serial != searchSerial || field != activeField) {
                    AuditTrail.record(AuditAction.SEARCH, AuditOutcome.IGNORED, state,
                        AuditDetails(reason = AuditReason.STALE, elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
                    return@launch
                }
                suggestions.addAll(hits)
                error = if (hits.isEmpty() && savedPlaceSuggestions.isEmpty()) "No matches. Try a full address or city." else null
                AuditTrail.record(AuditAction.SEARCH, AuditOutcome.SUCCEEDED, auditState(field),
                    AuditDetails(reason = if (hits.isEmpty()) AuditReason.NO_RESULTS else AuditReason.NONE,
                        source = AuditSource.TYPEAHEAD, localMatches = savedPlaceSuggestions.size,
                        remoteMatches = hits.size, elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
            } catch (e: CancellationException) {
                if (operation != null) AuditTrail.record(AuditAction.SEARCH, AuditOutcome.CANCELLED, state,
                    AuditDetails(elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
                throw e
            }
            catch (e: Exception) {
                if (serial == searchSerial && savedPlaceSuggestions.isEmpty()) error = describe(e, "Places search failed")
                if (operation != null) AuditTrail.record(AuditAction.SEARCH,
                    if (serial == searchSerial) AuditOutcome.FAILED else AuditOutcome.IGNORED, state,
                    AuditDetails(reason = if (serial == searchSerial) auditReason(e) else AuditReason.STALE,
                        elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
            }
            finally { if (serial == searchSerial) suggestBusy = false }
        }
    }
    fun clearSuggestions() {
        searchSerial++
        suggestJob?.cancel(); suggestJob = null
        savedPlaceSuggestions.clear(); suggestions.clear(); suggestBusy = false
    }
    fun pickSuggestion(hit: PlaceHit) {
        if (suggestedField != activeField || hit !in suggestions) {
            auditEvent(AuditAction.PLACE_SELECT, AuditOutcome.IGNORED, AuditDetails(reason = AuditReason.STALE, source = AuditSource.TYPEAHEAD))
            return
        }
        pickResolvedPlace(hit, activeField, AuditSource.TYPEAHEAD)
    }
    /** Shared application of an explicitly chosen touch or voice result to one planner field. */
    fun pickResolvedPlace(hit: PlaceHit, field: Field, source: AuditSource = AuditSource.VOICE) {
        clearSuggestions()
        autoStartAfterRoute = null
        activeField = field
        if (field == Field.START) setStartPoint(hit.latLng, hit.name, hit.address, hit.placeId) else {
            if (planMode == PlanMode.ITINERARY) rememberDraftEdit()
            setDestPoint(hit.latLng, hit.name)
            if (planMode == PlanMode.ITINERARY && stops.isNotEmpty()) {
                stops[stops.lastIndex] = stops.last().copy(address = hit.address, placeId = hit.placeId)
            } else { destAddress = hit.address; destPlaceId = hit.placeId }
        }
        error = null
        lastLocalAudit = null
        auditEvent(AuditAction.PLACE_SELECT, AuditOutcome.SUCCEEDED, AuditDetails(source = source))
    }
    /** Enter fetches choices and never silently chooses a previous result. */
    fun search(query: String, onFound: (LatLng) -> Unit) {
        requestSearch(query, tripStart(), false)
        if (!hasKey && savedPlaceSuggestions.isEmpty() && query.isNotBlank()) {
            error = "No saved places match. Add a Maps key in Setup to search other places."
            auditEvent(AuditAction.SEARCH, AuditOutcome.BLOCKED, AuditDetails(reason = AuditReason.NO_API))
        }
    }

    // ---- Single trip -------------------------------------------------------------

    fun buildRoute() {
        val operation = AuditTrail.newOperation()
        auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.REQUESTED, operation = operation)
        val s = tripStart() ?: run { error = "Set a start point"; auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.BLOCKED, AuditDetails(reason = AuditReason.NO_START), operation); return }
        val d = dest ?: run { error = "Set a destination"; auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.BLOCKED, AuditDetails(reason = AuditReason.NO_DESTINATION), operation); return }
        if (mode == TravelMode.FLY) {
            // Great-circle flight: computed locally, no routing API needed.
            val fm = FlightModel(s, d)
            routePts = fm.pathPoints; routeDistanceM = fm.totalMeters; isFlight = true; lastRoute = null; flightOrigin = s
            error = null; phase = Phase.READY
            auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.SUCCEEDED, operation = operation)
            return
        }
        if (!hasKey) { error = "Add MAPS_API_KEY to route"; auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.BLOCKED, AuditDetails(reason = AuditReason.NO_API), operation); return }
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            error = null; phase = Phase.ROUTING
            val started = System.nanoTime()
            try {
                val r = routeEngine.route(RouteSpec(s, arrivalEntrance ?: d, mode = mode, transitPreference = transitPref))
                if (!isActive) { auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.IGNORED, AuditDetails(reason = AuditReason.STALE), operation); return@launch }
                lastRoute = r; routePts = r.points; routeDistanceM = r.distanceMeters; isFlight = false
                phase = Phase.READY
                auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.SUCCEEDED, AuditDetails(elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
                autoStartAfterRoute?.let { go -> autoStartAfterRoute = null; startSim(go) }
            } catch (e: CancellationException) {
                auditEvent(AuditAction.ROUTE_BUILD, AuditOutcome.CANCELLED, AuditDetails(elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
                throw e
            } catch (e: Exception) {
                if (isActive) { error = describe(e, "Routing failed"); phase = Phase.IDLE }
                auditEvent(AuditAction.ROUTE_BUILD, if (isActive) AuditOutcome.FAILED else AuditOutcome.IGNORED,
                    AuditDetails(reason = if (isActive) auditReason(e) else AuditReason.STALE,
                        elapsedMs = (System.nanoTime() - started) / 1_000_000), operation)
            }
        }
    }

    fun startSim(onStart: () -> Unit) {
        auditEvent(AuditAction.SIMULATION, AuditOutcome.REQUESTED, AuditDetails(control = AuditControl.START))
        val origin = tripStart() ?: run { error = "Set a start point"; auditEvent(AuditAction.SIMULATION, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.START, reason = AuditReason.NO_START)); return }
        val destination = dest ?: run { error = "Set a destination"; auditEvent(AuditAction.SIMULATION, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.START, reason = AuditReason.NO_DESTINATION)); return }
        if (!canStart) { error = "Route was reset — tap Get route again"; auditEvent(AuditAction.SIMULATION, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.START, reason = AuditReason.NOT_READY)); return }
        val stop = ItineraryStop(destName, destination, defaultStayMinutes, mode, avgMph, destAddress, destPlaceId, frozenRoute=if(replayExactRoute) lastRoute else null, arrivalActivity=arrivalActivity,entrance=arrivalEntrance,stayUntilLeave=stayUntilLeave)
        if (MockState.status.value.running && queueAfterCurrent && LiveSession.plan != null) {
            LiveSession.plan?.append(stop)
            notice = "Added ${stop.name} after the existing stops"
            queueAfterCurrent = false; useSimulatedStart = true
            return
        }
        if (!queueAfterCurrent) {
            PlaybackSource.timeScale = timeScale.toDouble()
            PlaybackSource.speedOverLimitMph = speedOverLimit.toDouble()
        }
        // Re-route at execution: a moving start and a transit departure must be fresh.
        // A queued route is likewise resolved when it actually begins.
        val cfg = api
        val realismNow = realism
        val pref = transitPref
        val plan = LivePlan(destName, origin, listOf(stop), { from, to ->
            prepareLeg(cfg, from, to, realismNow, pref)
        }, departureMillis=departureMillis,defaultsRealism=realismNow,defaultsTransitPref=pref)
        linkLoadedPlan(plan)
        arm(plan.fixes(), listOf(origin), when (mode) { TravelMode.FLY -> "Flight"; TravelMode.TRANSIT -> "Transit"; TravelMode.DRIVE -> "Drive"; else -> "Route" }, destination, onStart)
    }

    /** The playback for one routed leg, by mode: real driving, transit timetable, or paced motion. */
    private fun legFlow(r: RouteResult, m: TravelMode, mph: Float): Flow<Fix> = when {
        m == TravelMode.TRANSIT -> TransitModel(r).fixes()
        m == TravelMode.DRIVE && r.segments.isNotEmpty() -> DriveModel(r, realism).fixes()
        else -> MotionModel(r, MotionParams(avgSpeedMps = mph * 0.44704, realism = realism, mode = m)).fixes()
    }

    /**
     * Either replace what is playing (default) or, when the start is "where the current
     * trip ends", queue behind it so it begins the moment the current plan arrives.
     */
    private fun arm(flow: Flow<Fix>, points: List<LatLng>, label: String, end: LatLng?, onStart: () -> Unit) {
        val running = MockState.status.value.running
        if (running && queueAfterCurrent) {
            PlaybackSource.enqueue(PlaybackSource.Queued(flow, points, label, end))
            notice = "Queued: $label starts when the current trip arrives (${PlaybackSource.queueSize()} waiting)"
            queueAfterCurrent = false; useSimulatedStart = true
            return
        }
        PlaybackSource.clearQueue()
        PlaybackSource.current = flow
        PlaybackSource.routePoints = points
        PlaybackSource.label = label
        PlaybackSource.endPoint = end
        useSimulatedStart = true; queueAfterCurrent = false
        onStart()
    }

    // ---- Itinerary ----------------------------------------------------------------

    private var draftUndo: List<ItineraryStop>? = null
    private data class PlannerUndo(val before: PlannerSnapshot, val after: PlannerSnapshot)
    private var plannerUndo: PlannerUndo? = null
    var draftUndoAvailable by mutableStateOf(false)
        private set
    private fun rememberDraftEdit() { plannerUndo=null; draftUndo=stops.toList(); draftUndoAvailable=true }
    fun undoDraftStops() {
        plannerUndo?.let { change ->
            if (!matchesPlannerSnapshot(change.after) || MockState.status.value.let { it.running || it.starting }) {
                error = "The plan changed after that instruction. Undo would replace newer edits."
                return
            }
            restorePlannerState(change.before, restorePreparedRoute = true)
            plannerUndo = null; draftUndo = null; draftUndoAvailable = false
            auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.UNDO))
            return
        }
        val before=draftUndo ?: return
        stops.clear(); stops.addAll(before); draftUndo=null; draftUndoAvailable=false
        dwellEditIndex=null; invalidateRoute()
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.UNDO))
    }
    fun removeStop(index: Int) {
        if (index !in stops.indices) return
        rememberDraftEdit()
        stops.removeAt(index)
        dwellEditIndex = null
        invalidateRoute()
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.REMOVE, index = index))
    }

    /** Cycle a stop's travel mode (Drive -> Bike -> Walk -> Fly) and give it that mode's speed. */
    fun cycleStopMode(index: Int) {
        if (index !in stops.indices) return
        val st = stops[index]
        val next = TravelMode.entries[(st.mode.ordinal + 1) % TravelMode.entries.size]
        stops[index] = st.copy(mode = next, avgMph = modeSpeeds[next] ?: defaultSpeed(next),frozenRoute=null)
    }

    fun adjustDwell(index: Int, deltaMinutes: Int) {
        if (index !in stops.indices) return
        val st = stops[index]
        stops[index] = st.copy(dwellMinutes = (st.dwellMinutes + deltaMinutes).coerceIn(0, 24 * 60))
    }

    fun moveStop(index: Int, delta: Int) {
        moveStopTo(index, index + delta)
    }

    fun moveStopTo(from: Int, to: Int) {
        if (from !in stops.indices || to !in stops.indices || from == to) return
        rememberDraftEdit()
        stops.add(to, stops.removeAt(from))
        dwellEditIndex = null
        invalidateRoute()
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.MOVE, index = to))
    }

    fun setStopMode(index: Int, m: TravelMode) {
        if (index !in stops.indices) return
        rememberDraftEdit()
        stops[index] = stops[index].copy(mode = m, avgMph = modeSpeeds[m] ?: defaultSpeed(m),frozenRoute=null)
        invalidateRoute()
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.EDIT, index = index))
    }

    /** Finish the day where it began. */
    fun addReturnToStart() {
        val s = tripStart() ?: run { error = "Set a start first"; return }
        stops.add(ItineraryStop("Trip start", s, 0, mode, avgMph))
        invalidateRoute()
    }

    /** One planned stop with the clock: when we get there and when we leave. */
    data class TimelineEntry(val index: Int, val arriveMillis: Long, val leaveMillis: Long, val legMinutes: Int, val legMiles: Double)

    /**
     * A schedule for the chain from a start time, using straight-line distance with a road
     * factor and each leg's own speed — good enough to read the day as a timetable while
     * building it (the real routing happens at Start).
     */
    fun timeline(startMillis: Long = departureMillis ?: System.currentTimeMillis()): List<TimelineEntry> {
        var from = tripStart() ?: stops.firstOrNull()?.point ?: return emptyList()
        var t = startMillis
        val out = mutableListOf<TimelineEntry>()
        stops.forEachIndexed { i, st ->
            val straight = Geo.haversine(from, st.point)
            val (meters, secs) = when (st.mode) {
                TravelMode.FLY -> straight to straight / 245.0 + 40 * 60.0             // taxi, climb, descent
                TravelMode.TRANSIT -> straight * 1.3 to straight * 1.3 / (12 * 0.44704) + 8 * 60.0   // incl. a wait
                else -> straight * 1.3 to straight * 1.3 / (st.avgMph.coerceAtLeast(1f) * 0.44704)
            }
            val arrive = t + (secs * 1000 / timeScale.coerceAtLeast(1f)).toLong()
            val leave = arrive + (st.dwellMinutes * 60_000L / timeScale.coerceAtLeast(1f)).toLong()
            out += TimelineEntry(i, arrive, leave, (secs / 60 / timeScale.coerceAtLeast(1f)).toInt(), meters / 1609.344)
            t = leave; from = st.point
        }
        return out
    }

    /** Index of the stop whose stay time is being edited (UI dialog), or null. */
    var dwellEditIndex by mutableStateOf<Int?>(null)

    /** Set exactly how long to stay at a stop (0 – 24 h). */
    fun setDwell(index: Int, minutes: Int) {
        if (index !in stops.indices) return
        rememberDraftEdit()
        stops[index] = stops[index].copy(dwellMinutes = minutes.coerceIn(0, 24 * 60))
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.EDIT, index = index))
    }

    // ---- Saved plans (Snap / Route / Itinerary by name) --------------------------

    val draftSavedName: String? get() = loadedScenario?.takeIf{sc->savedScenarios.any{it.id==sc.id}}?.name
    val draftGeneration: Long get() = TripRecovery.draftGeneration
    var departureMillis by mutableStateOf<Long?>(null)
    fun applyStopOrder(order: List<ItineraryStop>) {
        if(order.size!=stops.size || order.groupingBy{it}.eachCount()!=stops.groupingBy{it}.eachCount()) return
        rememberDraftEdit();stops.clear();stops.addAll(order);invalidateRoute()
    }
    fun setStopDeadline(index: Int, time: Long?) {
        if(index !in stops.indices)return
        rememberDraftEdit();stops[index]=stops[index].copy(arriveByMillis=time)
    }
    var replayExactRoute by mutableStateOf(false)
        private set
    fun setExactReplay(value: Boolean) {
        if(value && (lastRoute==null || mode !in setOf(TravelMode.DRIVE,TravelMode.BIKE,TravelMode.WALK))) {error="Calculate a road route first";return}
        if(value){startFromReal=false;useSimulatedStart=false;queueAfterCurrent=false}
        replayExactRoute=value
    }
    private var loadedScenario: SavedScenario? = null
    private var store: ScenarioStore = InMemoryScenarioStore()
    val savedScenarios = mutableStateListOf<SavedScenario>()

    /** Give the ViewModel its persistent store (the screen does this once). */
    fun attachStore(s: ScenarioStore) {
        store = s
        savedScenarios.clear(); savedScenarios.addAll(s.load().sortedByDescending { it.createdAt })
        libraryUndoAvailable = s.previous() != null
    }

    fun draftSnapshot(): SavedScenario? {
        if (!canSaveScenario) return null
        return capturePlanner().scenario
    }

    /** Unlike saved scenarios, an editing snapshot includes an empty plan and its origin policy. */
    fun capturePlanner(): PlannerSnapshot {
        val scenario = SavedScenario(loadedScenario?.id ?: "draft",loadedScenario?.name ?: "Recovered draft",planMode.name,0L,startFromReal && !useSimulatedStart && !queueAfterCurrent,start,startName,dest,destName,
            mode,modeSpeeds.toMap(),realism,transitPref,stops.map{it.toSavedStop()},destAddress,destPlaceId,frozenRoute=if(replayExactRoute)lastRoute else null,departureMillis=departureMillis,arrivalActivity=arrivalActivity,entrance=arrivalEntrance,stayUntilLeave=stayUntilLeave,defaultStayMinutes=defaultStayMinutes)
        return PlannerSnapshot(scenario, draftGeneration, loadedScenario, startFromReal, useSimulatedStart,
            queueAfterCurrent, startAddress, startPlaceId, activeField, timeScaleState, overLimitState, signal,
            lastRoute, flightOrigin, isFlight, routePts.toList(), routeDistanceM, replayExactRoute)
    }

    /** Resolve/review elsewhere, then commit every change as one guarded undoable operation. */
    fun applyPlannerProposal(before: PlannerSnapshot, after: PlannerSnapshot): Boolean {
        if (MockState.status.value.let { it.running || it.starting } || before.draftGeneration != draftGeneration ||
            after.draftGeneration != draftGeneration || !matchesPlannerSnapshot(before)) {
            error = "The plan changed. Review the instruction again before applying it."
            return false
        }
        if (!validPlannerSnapshot(after) || after.loadedScenario != before.loadedScenario ||
            after.scenario.id != before.scenario.id || after.scenario.name != before.scenario.name) {
            error = "The proposed changes contain invalid settings or a different saved identity."
            return false
        }
        restorePlannerState(after.copy(activeField = activeField), restorePreparedRoute = false)
        plannerUndo = PlannerUndo(before, capturePlanner())
        draftUndo = null; draftUndoAvailable = true
        auditEvent(AuditAction.STOP_EDIT, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.EDIT))
        return true
    }

    fun matchesPlannerSnapshot(snapshot: PlannerSnapshot): Boolean =
        capturePlanner().copy(activeField = snapshot.activeField) == snapshot

    /** Recovery restores editing only: no search, route request, library write or playback. */
    fun restorePlanner(snapshot: PlannerSnapshot): Boolean {
        if (MockState.status.value.let { it.running || it.starting } || snapshot.draftGeneration != draftGeneration ||
            !validPlannerSnapshot(snapshot)) return false
        restorePlannerState(snapshot, restorePreparedRoute = true)
        plannerUndo = null; draftUndo = null; draftUndoAvailable = false
        return true
    }

    private fun validPlannerSnapshot(snapshot: PlannerSnapshot): Boolean {
        fun point(p: LatLng?) = p == null || (p.lat.isFinite() && p.lng.isFinite() && p.lat in -90.0..90.0 && p.lng in -180.0..180.0)
        val sc = snapshot.scenario
        return sc.kind in PlanMode.entries.map { it.name } && point(sc.start) && point(sc.dest) && point(sc.entrance) &&
            sc.defaultStayMinutes in 0..1440 && sc.speeds.values.all { it.isFinite() && it > 0 } &&
            sc.stops.all { point(LatLng(it.lat,it.lng)) && point(it.entrance) && it.dwellMinutes in 0..1440 && it.avgMph.isFinite() && it.avgMph > 0 } &&
            snapshot.timeScale.isFinite() && snapshot.timeScale in 1f..100f && snapshot.speedOverLimit.isFinite() &&
            point(snapshot.flightOrigin) && snapshot.routePoints.all(::point) && snapshot.routeDistanceM.isFinite() && snapshot.routeDistanceM >= 0 &&
            snapshot.signal.accuracyM.isFinite() && snapshot.signal.accuracyM > 0 && snapshot.signal.jitterM.isFinite() && snapshot.signal.jitterM >= 0
    }

    private fun restorePlannerState(snapshot: PlannerSnapshot, restorePreparedRoute: Boolean) {
        clearSuggestions(); autoStartAfterRoute = null; routeJob?.cancel(); routeJob = null
        val sc = snapshot.scenario
        Snapshot.withMutableSnapshot {
            invalidateRoute()
            loadedScenario = snapshot.loadedScenario
            planMode = PlanMode.valueOf(sc.kind); activeField = snapshot.activeField
            start = sc.start; startName = sc.startName; startAddress = snapshot.startAddress; startPlaceId = snapshot.startPlaceId
            startFromReal = snapshot.startFromReal; useSimulatedStart = snapshot.useSimulatedStart; queueAfterCurrent = snapshot.queueAfterCurrent
            dest = sc.dest; destName = sc.destName; destAddress = sc.destAddress; destPlaceId = sc.destPlaceId
            stops.clear(); stops.addAll(sc.stops.map { it.toStop() })
            departureMillis = sc.departureMillis; mode = sc.travelMode; modeSpeeds.clear(); modeSpeeds.putAll(sc.speeds)
            realism = sc.realism; transitPref = sc.transitPref
            arrivalActivity = sc.arrivalActivity; arrivalEntrance = sc.entrance
            stayUntilLeave = sc.stayUntilLeave; defaultStayMinutes = sc.defaultStayMinutes
            timeScaleState = snapshot.timeScale; overLimitState = snapshot.speedOverLimit; signal = snapshot.signal
            if (restorePreparedRoute) {
                lastRoute = snapshot.preparedRoute; flightOrigin = snapshot.flightOrigin; isFlight = snapshot.isFlight
                routePts = snapshot.routePoints; routeDistanceM = snapshot.routeDistanceM; replayExactRoute = snapshot.replayExactRoute
            }
            phase = if (canStart) Phase.READY else Phase.IDLE
            dwellEditIndex = null; itineraryBusy = false; error = null
        }
    }
    fun recoverLive(record: RecoveredTrip, onStart: () -> Unit) {
        if(MockState.status.value.running) { error="Stop the current simulation before recovering another"; return }
        val sc=record.scenario
        val all=sc.stops.map{it.toStop()}
        if(all.isEmpty()) return
        val index=record.index.coerceIn(0,all.lastIndex)
        val held=record.activity in setOf(ActivityKind.STAYING,ActivityKind.HOLDING)
        val first=if(held) PreparedLeg(kotlinx.coroutines.flow.flow { emit(com.mirage.spike.engine.Fix(record.position.lat,record.position.lng,0f,0f,4f)) },listOf(record.position)) else null
        val cfg=api
        var recoveringLeg=true
        val plan=LivePlan(sc.name,sc.start ?: record.position,all,{from,to->
            val progress=if(recoveringLeg && !held) record.routeProgress else null
            val resumed=if(to.frozenRoute!=null && (Geo.haversine(from,to.frozenRoute.points.first())>1.0 || (progress ?: 0.0)>0)) to.copy(frozenRoute=com.mirage.spike.engine.RouteArchive.remaining(to.frozenRoute,from,progress)) else to
            val prepared=prepareLeg(cfg,from,resumed,sc.realism,sc.transitPref)
            recoveringLeg=false
            // A second checkpoint must still refer to the original archived route.
            val full=to.frozenRoute
            val part=resumed.frozenRoute
            if(full!=null && part!=null && full!==part && full.distanceMeters>0) {
                val remainingFraction=(part.distanceMeters/full.distanceMeters).coerceIn(0.0,1.0)
                prepared.copy(flow=prepared.flow.map {fix->
                    if(fix.progress<0)fix else fix.copy(progress=(1.0-remainingFraction+remainingFraction*fix.progress).toFloat())
                })
            } else prepared
        },first,
            resumePosition=record.position,resumeIndex=index,resumeStaySeconds=if(held)record.remainingStay else null,departureMillis=if(record.activity==ActivityKind.WAITING)sc.departureMillis else null,defaultsRealism=sc.realism,defaultsTransitPref=sc.transitPref)
        savedScenarios.firstOrNull{it.id==record.savedId}?.let { saved -> plan.markSaved(saved.id,saved.name,saved.stops.map{it.toStop()}) }
        PlaybackSource.paused=false
        timeScale=record.timeScale.toFloat();speedOverLimit=record.speedOffset.toFloat()
        PlaybackSource.timeScale=record.timeScale;PlaybackSource.speedOverLimitMph=record.speedOffset
        arm(plan.fixes(),listOf(record.position),sc.name,all.last().point,onStart)
    }

    /** Is there anything on screen worth saving in the current mode? */
    val canSaveScenario: Boolean
        get() = when (planMode) {
            PlanMode.SNAP, PlanMode.ROUTE -> dest != null
            PlanMode.ITINERARY -> stops.isNotEmpty()
        }

    fun saveScenario(name: String, replace: Boolean = false): Boolean {
        val operation = AuditTrail.newOperation()
        val control = if (replace) AuditControl.REPLACE else AuditControl.SAVE_COPY
        auditEvent(AuditAction.PLAN_SAVE, AuditOutcome.REQUESTED, AuditDetails(control = control), operation)
        fun finish(outcome: AuditOutcome, reason: AuditReason = AuditReason.NONE): Boolean {
            auditEvent(AuditAction.PLAN_SAVE, outcome, AuditDetails(control = control, reason = reason), operation)
            return outcome == AuditOutcome.SUCCEEDED
        }
        val replacing=if(replace)loadedScenario?.id else null
        val n = name.trim()
        if(replace && savedScenarios.none{it.id==replacing}) {error="The saved original no longer exists. Save a copy instead.";return finish(AuditOutcome.BLOCKED, AuditReason.MISSING_ITEM)}
        if (n.isBlank() || !canSaveScenario) return finish(AuditOutcome.BLOCKED, AuditReason.INVALID_INPUT)
        if (savedScenarios.any { it.name.equals(n, true) && it.id!=replacing }) { error = "That name already exists. Choose a different name."; return finish(AuditOutcome.BLOCKED, AuditReason.DUPLICATE_NAME) }
        val effectiveStart = tripStart()
        val realOrigin = startFromReal && !useSimulatedStart && !queueAfterCurrent
        val sc = SavedScenario(
            id = replacing ?: "${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}",
            name = n, kind = planMode.name, createdAt = System.currentTimeMillis(),
            startIsReal = realOrigin || effectiveStart == null,
            start = if (realOrigin) null else effectiveStart,
            startName = when { realOrigin -> ""; queueAfterCurrent -> "Previous trip end"; useSimulatedStart -> "Simulated location"; else -> startName },
            departureMillis=departureMillis, arrivalActivity=arrivalActivity,entrance=arrivalEntrance,stayUntilLeave=stayUntilLeave,defaultStayMinutes=defaultStayMinutes,
            frozenRoute = if(replayExactRoute) lastRoute else null,
            dest = dest, destName = destName, destAddress = destAddress, destPlaceId = destPlaceId,
            travelMode = mode, speeds = modeSpeeds.toMap(), realism = realism, transitPref = transitPref,
            stops = stops.map { SavedStop(it.name, it.point.lat, it.point.lng, it.dwellMinutes, it.mode, it.avgMph, it.address, it.placeId, it.routingRealism, it.routingTransitPref, it.ownRoutingPreferences, it.frozenRoute, it.arriveByMillis, it.arrivalActivity, it.entrance, it.stayUntilLeave) },
        )
        error = null
        val previous=savedScenarios.firstOrNull{it.id==sc.id}
        val persisted=sc.copy(favorite=previous?.favorite ?: false,aliases=previous?.aliases ?: emptyList(),lastUsedAt=previous?.lastUsedAt ?: 0L)
        val ok=writeLibrary(listOf(persisted) + savedScenarios.filterNot{it.id==sc.id})
        if(ok)loadedScenario=sc
        return finish(if (ok) AuditOutcome.SUCCEEDED else AuditOutcome.FAILED, if (ok) AuditReason.NONE else AuditReason.STORAGE)
    }

    /** Save the active plan including live stop edits, independent of the draft form. */
    fun saveActiveScenario(name: String, replace: Boolean = false): Boolean {
        val operation = AuditTrail.newOperation()
        val control = if (replace) AuditControl.REPLACE else AuditControl.SAVE_COPY
        auditEvent(AuditAction.PLAN_SAVE, AuditOutcome.REQUESTED, AuditDetails(control = control), operation)
        fun finish(outcome: AuditOutcome, reason: AuditReason = AuditReason.NONE): Boolean {
            auditEvent(AuditAction.PLAN_SAVE, outcome, AuditDetails(control = control, reason = reason), operation)
            return outcome == AuditOutcome.SUCCEEDED
        }
        val n = name.trim()
        val plan = LiveSession.plan ?: return finish(AuditOutcome.BLOCKED, AuditReason.NOT_READY)
        val view = plan.view()
        if (n.isBlank() || view.stops.isEmpty()) return finish(AuditOutcome.BLOCKED, AuditReason.INVALID_INPUT)
        val existingId = if (replace) view.savedId else null
        if (replace && existingId == null) { error = "Save this trip under a name first."; return finish(AuditOutcome.BLOCKED, AuditReason.MISSING_ITEM) }
        if (replace && savedScenarios.none { it.id == existingId }) { error = "The saved original no longer exists. Save as new instead."; return finish(AuditOutcome.BLOCKED, AuditReason.MISSING_ITEM) }
        if (savedScenarios.any { it.name.equals(n, true) && it.id != existingId }) { error = "That name already exists. Choose a different name."; return finish(AuditOutcome.BLOCKED, AuditReason.DUPLICATE_NAME) }
        val activeStops = plan.stopsForSave()
        val last = activeStops.last()
        val kind = PlanMode.ITINERARY
        val sc = SavedScenario(
            id = existingId ?: java.util.UUID.randomUUID().toString(), name = n, kind = kind.name,
            createdAt = System.currentTimeMillis(), startIsReal = false,
            start = plan.origin, startName = "Saved trip start",
            dest = last.point, destName = last.name, travelMode = last.mode, destAddress = last.address, destPlaceId = last.placeId,
            speeds = modeSpeeds.toMap(), realism = plan.defaultsRealism, transitPref = plan.defaultsTransitPref,
            departureMillis=plan.departureMillis,
            stops = activeStops.map { SavedStop(it.name, it.point.lat, it.point.lng, it.dwellMinutes, it.mode, it.avgMph, it.address, it.placeId, it.routingRealism, it.routingTransitPref, it.ownRoutingPreferences, it.frozenRoute, it.arriveByMillis, it.arrivalActivity, it.entrance, it.stayUntilLeave) },
        )
        error = null
        val previous=savedScenarios.firstOrNull{it.id==sc.id}
        val persisted=sc.copy(favorite=previous?.favorite ?: false,aliases=previous?.aliases ?: emptyList(),lastUsedAt=previous?.lastUsedAt ?: 0L)
        val updated = savedScenarios.filterNot { it.id == sc.id }.toMutableList().apply { add(0, persisted) }
        try { store.save(updated) } catch (e: Exception) { error = "Could not save: ${e.message}"; return finish(AuditOutcome.FAILED, AuditReason.STORAGE) }
        savedScenarios.clear(); savedScenarios.addAll(updated)
        plan.markSaved(sc.id, sc.name, activeStops)
        notice = "Saved ${sc.name}"
        return finish(AuditOutcome.SUCCEEDED)
    }

    private fun linkLoadedPlan(plan: LivePlan) {
        val sc = loadedScenario ?: return
        val original = if (sc.stops.isNotEmpty()) sc.stops.map { ItineraryStop(it.name, LatLng(it.lat, it.lng), it.dwellMinutes, it.mode, it.avgMph, it.address, it.placeId, it.routingRealism, it.routingTransitPref, it.ownRoutingPreferences, it.frozenRoute, it.arriveByMillis, it.arrivalActivity, it.entrance, it.stayUntilLeave) }
            else listOfNotNull(sc.dest?.let { ItineraryStop(sc.destName, it, sc.defaultStayMinutes, sc.travelMode, sc.speeds[sc.travelMode] ?: defaultSpeed(sc.travelMode), sc.destAddress, sc.destPlaceId, frozenRoute=sc.frozenRoute,arrivalActivity=sc.arrivalActivity,entrance=sc.entrance,stayUntilLeave=sc.stayUntilLeave) })
        plan.markSaved(sc.id, sc.name, original)
    }

    private fun validSavedPlace(sc: SavedScenario): Boolean {
        fun validPoint(point: LatLng) = point.lat.isFinite() && point.lng.isFinite() &&
            point.lat in -90.0..90.0 && point.lng in -180.0..180.0
        if (sc.kind != PlanMode.SNAP.name || sc.dest == null || !validPoint(sc.dest) ||
            (sc.entrance != null && !validPoint(sc.entrance)) || sc.defaultStayMinutes !in 0..1440) {
            error = "Choose a saved place with a valid location and stay."
            return false
        }
        return true
    }

    /** Apply one saved place to the chosen draft field without loading or starting its plan. */
    fun pickSavedPlace(sc: SavedScenario, field: Field, source: AuditSource = AuditSource.SAVED_PLACE): Boolean {
        val operation = AuditTrail.newOperation()
        AuditTrail.record(AuditAction.SAVED_SELECT, AuditOutcome.REQUESTED, auditState(field), AuditDetails(source = source), operation)
        if (!validSavedPlace(sc)) {
            AuditTrail.record(AuditAction.SAVED_SELECT, AuditOutcome.BLOCKED, auditState(field), AuditDetails(reason = AuditReason.INVALID_INPUT, source = source), operation)
            return false
        }
        val point = sc.dest ?: return false
        clearSuggestions()
        autoStartAfterRoute = null
        activeField = field
        if (field == Field.START) {
            setStartPoint(point, sc.name, sc.destAddress, sc.destPlaceId)
        } else {
            arrivalActivity = sc.arrivalActivity
            arrivalEntrance = sc.entrance
            stayUntilLeave = sc.stayUntilLeave
            defaultStayMinutes = sc.defaultStayMinutes
            if (planMode == PlanMode.ITINERARY) rememberDraftEdit()
            setDestPoint(point, sc.name, sc.entrance)
            if (planMode == PlanMode.ITINERARY) {
                stops[stops.lastIndex] = stops.last().copy(address = sc.destAddress, placeId = sc.destPlaceId)
            } else {
                destAddress = sc.destAddress; destPlaceId = sc.destPlaceId
            }
        }
        recordSavedUse(sc.id)
        error = null
        lastLocalAudit = null
        AuditTrail.record(AuditAction.SAVED_SELECT, AuditOutcome.SUCCEEDED, auditState(field), AuditDetails(source = source), operation)
        return true
    }

    fun useSavedPlaceAsStart(sc: SavedScenario) {
        if (!validSavedPlace(sc)) return
        if (planMode == PlanMode.SNAP) choosePlanMode(PlanMode.ROUTE)
        pickSavedPlace(sc, Field.START)
    }

    fun useSavedPlaceAsDestination(sc: SavedScenario) {
        if (!validSavedPlace(sc)) return
        if (planMode == PlanMode.SNAP) choosePlanMode(PlanMode.ROUTE)
        pickSavedPlace(sc, Field.END)
    }

    fun addSavedPlaceStop(sc: SavedScenario) {
        if (!validSavedPlace(sc)) return
        choosePlanMode(PlanMode.ITINERARY)
        pickSavedPlace(sc, Field.END)
    }

    /** Append in the draft only. A disconnected route requires an explicit connector. */
    fun appendSavedRoute(sc: SavedScenario, connectGap: Boolean = false): Boolean {
        if (sc.kind != PlanMode.ROUTE.name || sc.dest == null) { error = "Choose a saved route"; return false }
        val existing = when (planMode) {
            PlanMode.ITINERARY -> stops.toList()
            PlanMode.ROUTE -> dest?.let { listOf(ItineraryStop(destName, it, defaultStayMinutes, mode, avgMph, arrivalActivity=arrivalActivity,entrance=arrivalEntrance,stayUntilLeave=stayUntilLeave)) }.orEmpty()
            else -> emptyList()
        }
        val tail = existing.lastOrNull()?.point
        val origin = if (sc.startIsReal) lastReal else sc.start
        if (sc.startIsReal && origin == null) { error = "Real location is unavailable. Set a real start first."; return false }
        val resolvedOrigin = origin ?: tail ?: tripStart()
            ?: run { error = "Set a start location first"; return false }
        val gap = tail != null && Geo.haversine(tail, resolvedOrigin) > 25.0
        if (gap && !connectGap) { error = "This route starts elsewhere. Add a connecting leg?"; return false }
        if (existing.isEmpty()) setStartPoint(resolvedOrigin, sc.startName.ifBlank { "Route start" })
        planMode = PlanMode.ITINERARY
        stops.clear(); stops.addAll(existing)
        if (gap) stops.add(ItineraryStop(sc.startName.ifBlank { "Route start" }, resolvedOrigin, 0, TravelMode.DRIVE, defaultSpeed(TravelMode.DRIVE)))
        stops.add(ItineraryStop(sc.destName.ifBlank { sc.name }, sc.dest, sc.defaultStayMinutes, sc.travelMode, sc.speeds[sc.travelMode] ?: defaultSpeed(sc.travelMode),sc.destAddress,sc.destPlaceId,sc.realism,sc.transitPref,true,sc.frozenRoute,arrivalActivity=sc.arrivalActivity,entrance=sc.entrance,stayUntilLeave=sc.stayUntilLeave))
        invalidateRoute(); error = null; notice = "Route added. Review the itinerary before starting."
        return true
    }

    var libraryUndoAvailable by mutableStateOf(false)
        private set
    private var libraryUndo: List<SavedScenario>? = null
    fun savePlaceSettings(stop: ItineraryStop, existingId: String?): Boolean {
        val existing=savedScenarios.firstOrNull{it.id==existingId && it.kind=="SNAP"}
        val name=stop.name.ifBlank {"Dropped pin"}
        val unique=if(existing!=null) existing.name else generateSequence(1){it+1}.map {if(it==1)name else "$name ($it)"}.first {n->savedScenarios.none{it.name.equals(n,true)}}
        val sc=existing?.copy(dest=stop.point,arrivalActivity=stop.arrivalActivity,entrance=stop.entrance,stayUntilLeave=stop.stayUntilLeave,defaultStayMinutes=stop.dwellMinutes)
            ?: SavedScenario(java.util.UUID.randomUUID().toString(),unique,"SNAP",System.currentTimeMillis(),true,null,"",stop.point,stop.name,stop.mode,modeSpeeds.toMap(),realism,transitPref,emptyList(),stop.address,stop.placeId,arrivalActivity=stop.arrivalActivity,entrance=stop.entrance,stayUntilLeave=stop.stayUntilLeave,defaultStayMinutes=stop.dwellMinutes)
        val ok=writeLibrary(listOf(sc)+savedScenarios.filterNot{it.id==sc.id})
        if(ok)notice="Saved place settings: ${sc.name}"
        return ok
    }
    private fun writeLibrary(next: List<SavedScenario>): Boolean {
        return try {
            store.save(next)
            libraryUndo = savedScenarios.toList(); libraryUndoAvailable = true
            savedScenarios.clear(); savedScenarios.addAll(next)
            error = null; true
        } catch (e: Exception) { error = "Could not save: ${e.message}"; false }
    }
    fun undoLibrary(): Boolean {
        val previous = libraryUndo ?: store.previous() ?: return false
        if (!writeLibrary(previous)) return false
        libraryUndo = null; libraryUndoAvailable = false
        notice = "Saved items restored"; return true
    }
    fun appendResolvedStop(hit: PlaceHit, minutes: Int = 0) {
        choosePlanMode(PlanMode.ITINERARY)
        rememberDraftEdit()
        stops.add(ItineraryStop(hit.name,hit.latLng,minutes,mode,avgMph,hit.address,hit.placeId,arrivalActivity=arrivalActivity,stayUntilLeave=stayUntilLeave))
        invalidateRoute()
    }
    fun appendSavedItinerary(item: SavedScenario) {
        if (planMode != PlanMode.ITINERARY) choosePlanMode(PlanMode.ITINERARY)
        if (start == null) item.start?.let { setStartPoint(it,item.startName) }
        stops.addAll(item.stops.map { ItineraryStop(it.name,LatLng(it.lat,it.lng),it.dwellMinutes,it.mode,it.avgMph,it.address,it.placeId,it.routingRealism,it.routingTransitPref,it.ownRoutingPreferences,it.frozenRoute,it.arriveByMillis,it.arrivalActivity,it.entrance,it.stayUntilLeave) })
        invalidateRoute(); notice = "Added ${item.stops.size} stops; original unchanged"
    }

    fun deleteScenario(id: String): Boolean {
        val ok=writeLibrary(savedScenarios.filterNot { it.id == id })
        if(ok) notice="Deleted saved item · Undo is available"
        return ok
    }
    fun recordSavedUse(id: String) {
        val next=savedScenarios.map{if(it.id==id)it.copy(lastUsedAt=System.currentTimeMillis()) else it}
        try {store.save(next);savedScenarios.clear();savedScenarios.addAll(next)} catch (_: Exception) { /* Last-used metadata must not block travel. */ }
    }
    fun favoriteScenario(id: String) = writeLibrary(savedScenarios.map { if(it.id == id) it.copy(favorite = !it.favorite) else it })
    fun renameScenario(id: String, name: String, copy: Boolean = false): Boolean {
        val item = savedScenarios.firstOrNull { it.id == id } ?: return false
        val n = name.trim()
        if (n.isBlank() || savedScenarios.any { it.name.equals(n,true) && (copy || it.id != id) }) {
            error = "Choose a unique, nonblank name"; return false
        }
        val updated = item.copy(id = if(copy) java.util.UUID.randomUUID().toString() else id, name = n, createdAt = System.currentTimeMillis())
        val ok=writeLibrary(if(copy) listOf(updated) + savedScenarios else savedScenarios.map { if(it.id == id) updated else it })
        if(ok && !copy) {
            if(loadedScenario?.id==id) loadedScenario=updated
            if(LiveSession.state.value.savedId==id) LiveSession.plan?.renameSaved(n)
        }
        return ok
    }
    fun setAliases(id: String, text: String): Boolean {
        val aliases = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (aliases.any { it.length > 80 } || aliases.size > 12) { error = "Use up to 12 short aliases"; return false }
        return writeLibrary(savedScenarios.map { if(it.id == id) it.copy(aliases=aliases) else it })
    }
    fun importScenarios(items: List<SavedScenario>): Boolean {
        val next = savedScenarios.toMutableList()
        var added = 0
        items.forEach { item ->
            if (next.any { it.id == item.id && it == item }) return@forEach
            var name = item.name; var suffix = 2
            while (next.any { it.name.equals(name,true) }) { name = "${item.name} ($suffix)"; suffix++ }
            next += item.copy(id = java.util.UUID.randomUUID().toString(), name = name)
            added++
        }
        if(!writeLibrary(next)) return false
        notice = "Imported $added items; existing items preserved"; return true
    }

    /** Put a saved plan back on screen. A "real location" start uses today's real position. */
    fun loadScenario(sc: SavedScenario): Boolean {
        val operation = AuditTrail.newOperation()
        auditEvent(AuditAction.PLAN_LOAD, AuditOutcome.REQUESTED, operation = operation)
        // Even the same saved plan starts a new editing session. Invalidate old callbacks
        // durably before replacing the current plan or updating its library metadata.
        if (!TripRecovery.clearDraft()) {
            error = "Could not load the saved plan because the current draft could not be cleared. Try again."
            auditEvent(AuditAction.PLAN_LOAD, AuditOutcome.BLOCKED,
                AuditDetails(reason = AuditReason.STORAGE), operation)
            return false
        }
        planning.reset()
        Conversation.resetPlanningSession()
        FieldSpeech.cancelPlanner()
        autoStartAfterRoute = null
        draftUndo = null; plannerUndo = null; draftUndoAvailable = false; dwellEditIndex = null
        var recentWriteFailed = false
        loadedScenario = sc
        departureMillis=sc.departureMillis
        arrivalActivity=sc.arrivalActivity
        arrivalEntrance=sc.entrance; stayUntilLeave=sc.stayUntilLeave; defaultStayMinutes=sc.defaultStayMinutes
        val used = savedScenarios.map { if(it.id == sc.id) it.copy(lastUsedAt=System.currentTimeMillis()) else it }
        try { store.save(used); savedScenarios.clear(); savedScenarios.addAll(used) } catch (_: Exception) {
            recentWriteFailed = true
            /* Opening still works if recent metadata cannot be written. */
        }
        startAddress = ""; startPlaceId = ""
        queueAfterCurrent = false
        clearSuggestions()
        planMode = runCatching { PlanMode.valueOf(sc.kind) }.getOrDefault(PlanMode.ROUTE)
        mode = sc.travelMode
        sc.speeds.forEach { (m, v) -> modeSpeeds[m] = v }
        realism = sc.realism
        transitPref = sc.transitPref
        stops.clear()
        stops.addAll(sc.stops.map { ItineraryStop(it.name, LatLng(it.lat, it.lng), it.dwellMinutes, it.mode, it.avgMph, it.address, it.placeId, it.routingRealism, it.routingTransitPref, it.ownRoutingPreferences, it.frozenRoute, it.arriveByMillis, it.arrivalActivity, it.entrance, it.stayUntilLeave) })
        if (sc.startIsReal || sc.start == null) {
            val real = lastReal
            start = real; startName = "My location"; startFromReal = true
            useSimulatedStart = false
        } else {
            start = sc.start; startName = sc.startName; startFromReal = false; useSimulatedStart = false
        }
        dest = if(planMode==PlanMode.ITINERARY)null else sc.dest
        destName = if(planMode==PlanMode.ITINERARY)"" else sc.destName
        destAddress = if(planMode==PlanMode.ITINERARY)"" else sc.destAddress
        destPlaceId = if(planMode==PlanMode.ITINERARY)"" else sc.destPlaceId
        invalidateRoute()
        error = null
        auditEvent(AuditAction.PLAN_LOAD, AuditOutcome.SUCCEEDED,
            AuditDetails(reason = if (recentWriteFailed) AuditReason.STORAGE else AuditReason.NONE), operation)
        // Routes can be prepared straight away; the user then just taps Start.
        if(sc.frozenRoute!=null && mode in setOf(TravelMode.DRIVE,TravelMode.BIKE,TravelMode.WALK)) {
            lastRoute=sc.frozenRoute;routePts=sc.frozenRoute.points;routeDistanceM=sc.frozenRoute.distanceMeters;phase=Phase.READY;replayExactRoute=true
        } else if (planMode == PlanMode.ROUTE && dest != null && tripStart() != null && (mode == TravelMode.FLY || hasKey)) buildRoute()
        return true
    }

    /** Snapshot the draft; subsequent edits use LiveSession and stable stop IDs. */
    fun startItinerary(onStart: () -> Unit) {
        auditEvent(AuditAction.SIMULATION, AuditOutcome.REQUESTED, AuditDetails(control = AuditControl.START))
        val origin = tripStart() ?: run { error = "Set a start point"; auditEvent(AuditAction.SIMULATION, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.START, reason = AuditReason.NO_START)); return }
        if (stops.isEmpty()) { error = "Add at least one stop"; auditEvent(AuditAction.SIMULATION, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.START, reason = AuditReason.NO_STOPS)); return }
        if (stops.any { it.mode != TravelMode.FLY && it.frozenRoute==null } && !hasKey) { error = "Add a Maps key in Setup"; auditEvent(AuditAction.SIMULATION, AuditOutcome.BLOCKED, AuditDetails(control = AuditControl.START, reason = AuditReason.NO_API)); return }
        val snapshot = stops.toList()
        if (MockState.status.value.running && queueAfterCurrent && LiveSession.plan != null) {
            LiveSession.plan?.appendAll(snapshot)
            notice = "Added ${snapshot.size} stops after the existing stops"
            queueAfterCurrent = false; useSimulatedStart = true
            return
        }
        if (!queueAfterCurrent) {
            PlaybackSource.timeScale = timeScale.toDouble()
            PlaybackSource.speedOverLimitMph = speedOverLimit.toDouble()
        }
        val cfg = api; val realismNow = realism; val pref = transitPref
        val plan = LivePlan("Itinerary", origin, snapshot, { from, to -> prepareLeg(cfg, from, to, realismNow, pref) }, departureMillis=departureMillis,defaultsRealism=realismNow,defaultsTransitPref=pref)
        linkLoadedPlan(plan)
        arm(plan.fixes(), listOf(origin), "Itinerary", snapshot.last().point, onStart)
    }

    fun onStopped() {
        continuation.cancel(); clearSuggestions()
        phase = if (routePts.isEmpty()) Phase.IDLE else Phase.READY
        auditEvent(AuditAction.SIMULATION, AuditOutcome.SUCCEEDED, AuditDetails(control = AuditControl.STOP))
    }

    // ---- Automation (adb / scripts): see README "Automation" ------------------------

    /** Short token scripts must present; shown in Setup. */
    var automationToken by mutableStateOf("")
    private var autoStartAfterRoute: (() -> Unit)? = null

    /**
     * Execute one command. Returns a human-readable result (also shown as the notice).
     * Commands: pause, resume, skip, stop (handled by the caller), timescale value=N,
     * speed_over value=N, signal preset=good|urban|poor|indoor, drop seconds=N,
     * snap lat lng [name], route lat lng lat lng [mode=drive|bike|walk|transit|fly],
     * plan name=<saved plan> (loads and starts it).
     */
    fun runCommand(cmd: String, args: Map<String, String>, onStartService: () -> Unit, onStopService: () -> Unit): String {
        if (automationToken.isNotBlank() && args["token"] != automationToken) return "Rejected: bad or missing token"
        val d = { k: String -> args[k]?.toDoubleOrNull() }
        val result = when (cmd.lowercase()) {
            "pause" -> { pause(); "Paused" }
            "resume" -> { resume(); "Resumed" }
            "skip" -> { skipAhead(); "Skipped ahead" }
            "stop" -> { onStopService(); onStopped(); "Stopped — real location" }
            "timescale" -> { timeScale = (d("value") ?: 1.0).toFloat().coerceIn(1f, 100f); PlaybackSource.timeScale = timeScale.toDouble(); "Fast-forward ${timeScale.toInt()}×" }
            "speed_over" -> { speedOverLimit = (d("value") ?: 5.0).toFloat().coerceIn(-15f, 25f); PlaybackSource.speedOverLimitMph = speedOverLimit.toDouble(); "Cruise at limit ${if (speedOverLimit >= 0) "+" else ""}${speedOverLimit.toInt()} mph" }
            "signal" -> {
                val s = Signal.PRESETS.firstOrNull { it.name.equals(args["preset"] ?: "", ignoreCase = true) }
                if (s == null) "Unknown preset" else { setSignalPreset(s); "Signal ${s.name}" }
            }
            "drop" -> { dropSignal((d("seconds") ?: 30.0).toInt().coerceIn(1, 3600)); "GPS dropped" }
            "place", "snap" -> {
                val lat = d("lat"); val lng = d("lng")
                if (lat == null || lng == null) "Place needs lat and lng" else {
                    planMode = PlanMode.SNAP
                    dest = LatLng(lat, lng); destName = args["name"] ?: "Place"
                    PlaybackSource.current = null; PlaybackSource.routePoints = listOf(dest!!); PlaybackSource.label = "Static"
                    PlaybackSource.endPoint = dest; PlaybackSource.clearQueue(); PlaybackSource.paused = false
                    onSnapStarted(); onStartService(); "Simulating at $lat,$lng"
                }
            }
            "route" -> {
                val lat = d("lat"); val lng = d("lng"); val lat2 = d("lat2"); val lng2 = d("lng2")
                if (lat2 == null || lng2 == null) "route needs lat2 and lng2 (and optionally lat, lng for the start)" else {
                    planMode = PlanMode.ROUTE
                    args["mode"]?.let { m -> TravelMode.entries.firstOrNull { it.name.equals(m, true) }?.let { mode = it } }
                    if (lat != null && lng != null) setStartPoint(LatLng(lat, lng), "Script start") else useSimulatedPosition()
                    dest = LatLng(lat2, lng2); destName = args["name"] ?: "Script end"
                    autoStartAfterRoute = onStartService
                    buildRoute(); "Routing, will start on arrival of the route"
                }
            }
            "plan" -> {
                val sc = savedScenarios.firstOrNull { it.name.equals(args["name"] ?: "", ignoreCase = true) }
                if (sc == null) "No saved plan named ${args["name"]}" else if (!loadScenario(sc)) {
                    error ?: "Could not load the saved plan."
                } else {
                    when (planMode) {
                        PlanMode.SNAP -> { dest?.let { PlaybackSource.current = null; PlaybackSource.routePoints = listOf(it); PlaybackSource.label = "Static"; PlaybackSource.endPoint = it; onSnapStarted(); onStartService() }; "Place started: ${sc.name}" }
                        PlanMode.ITINERARY -> { startItinerary(onStartService); "Starting itinerary: ${sc.name}" }
                        PlanMode.ROUTE -> { autoStartAfterRoute = onStartService; if (canStart) startSim(onStartService); "Starting route: ${sc.name}" }
                    }
                }
            }
            else -> "Unknown command: $cmd"
        }
        notice = result
        return result
    }
}

/** A user-readable reason, never a blank one. */
private fun describe(e: Exception, fallback: String): String =
    e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName?.let { "$fallback ($it)" } ?: fallback)

/** Sensible default speed (mph) for each way of getting around. */
fun defaultSpeed(m: TravelMode): Float = when (m) {
    TravelMode.DRIVE -> 45f
    TravelMode.BIKE -> 12f
    TravelMode.WALK -> 3f
    TravelMode.TRANSIT -> 20f   // informational only: transit follows the timetable
    TravelMode.FLY -> 550f
}

/** Slider range (mph) that makes sense for each mode. */
fun speedRange(m: TravelMode): ClosedFloatingPointRange<Float> = when (m) {
    TravelMode.DRIVE -> 5f..80f
    TravelMode.BIKE -> 3f..25f
    TravelMode.WALK -> 1f..6f
    TravelMode.TRANSIT -> 5f..60f
    TravelMode.FLY -> 400f..600f
}

fun fmtMiles(meters: Double): String {
    val mi = meters / 1609.344
    return if (mi < 10) String.format("%.1f mi", mi) else "${mi.roundToInt()} mi"
}

fun fmtDuration(sec: Double): String {
    val s = sec.coerceAtLeast(0.0)
    if (s < 60) return "${s.toInt()} s"
    val mins = (s / 60).roundToInt()
    if (mins < 60) return "$mins min"
    val h = mins / 60; val m = mins % 60
    return if (m == 0) "$h h" else "$h h $m min"
}

