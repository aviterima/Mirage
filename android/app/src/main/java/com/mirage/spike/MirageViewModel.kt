package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirage.spike.engine.Fix
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Phase { IDLE, ROUTING, READY, RUNNING }

/** Which of the two location boxes a search result / pick applies to. */
enum class Field { START, END }

/** The three things Mirage does: be somewhere, travel A to B, or chain a day of stops. */
enum class PlanMode { SNAP, ROUTE, ITINERARY }

/** Drives the map screen: endpoints, routing, average speed, itinerary, and simulation control. */
class MirageViewModel : ViewModel() {

    // ---- Endpoints -----------------------------------------------------------
    // No made-up default: until a real fix (or a pick) arrives the start is simply unset.
    var start by mutableStateOf<LatLng?>(null)
        private set
    var startName by mutableStateOf("")
        private set
    /** True when the start came from the phone's real position (not a pick/pin). */
    var startFromReal by mutableStateOf(false)
        private set
    var dest by mutableStateOf<LatLng?>(null)
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
        set(v) { timeScaleState = v; PlaybackSource.timeScale = v.toDouble() }

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
    val suggestions = mutableStateListOf<PlaceHit>()
    var suggestBusy by mutableStateOf(false)
        private set
    private var suggestJob: Job? = null

    val hasKey: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()
    private val routeEngine by lazy { GoogleDirectionsRouteEngine(BuildConfig.MAPS_API_KEY) }
    private val geocoder by lazy { GoogleGeocoder(BuildConfig.MAPS_API_KEY) }
    private val places by lazy { GooglePlaces(BuildConfig.MAPS_API_KEY) }

    /** An explicit user choice of start (pin, search pick, ⌖ menu). */
    fun setStartPoint(p: LatLng, name: String = "Dropped pin") {
        start = p; startName = name; startFromReal = false; useSimulatedStart = false; invalidateRoute()
    }

    /** ⌖ → "My real location" chosen by the user for the Start box. */
    fun pickRealStart(p: LatLng) {
        lastReal = p
        start = p; startName = "My location"; startFromReal = true; useSimulatedStart = false; invalidateRoute()
    }

    /** Back to "begin where the simulation is now" (only meaningful while running). */
    fun useSimulatedPosition() { useSimulatedStart = true; invalidateRoute() }

    /** Where the next trip begins: the live simulated position while running (unless the
     *  user chose an explicit start), else the start pin. */
    fun tripStart(): LatLng? {
        val st = MockState.status.value
        return if (st.running && useSimulatedStart) LatLng(st.lat, st.lng) else start
    }
    /**
     * AUTOMATIC real fix (launch, after Stop). Adopts it as the start only when that does
     * not destroy the user's work: no start yet, a start that already came from a real fix,
     * or no route built. Never touches [useSimulatedStart] — that is the user's choice.
     */
    fun useMyLocation(p: LatLng) {
        lastReal = p
        if (start != null && !startFromReal && routePts.isNotEmpty()) return
        val moved = start?.let { Geo.haversine(it, p) >= 100.0 } ?: true
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
        start = p; startName = "My location"; startFromReal = true
        invalidateRoute()
    }

    /** A Snap started: the next plan begins from where the simulation now is. */
    fun onSnapStarted() { useSimulatedStart = true }
    /**
     * The End box / map tap. In Itinerary mode a chosen place is appended to the chain
     * as the next stop (with the mode and speed currently set), so building a day is
     * simply: pick, pick, pick.
     */
    fun setDestPoint(p: LatLng, name: String = "Dropped pin") {
        if (planMode == PlanMode.ITINERARY) {
            stops.add(ItineraryStop(name, p, 30, mode, avgMph))
            dest = null; destName = ""; error = null
            invalidateRoute()
        } else {
            dest = p; destName = name; invalidateRoute()
        }
    }

    fun choosePlanMode(m: PlanMode) {
        if (m == planMode) return
        // Carry a chosen End into the itinerary as a stop, and a single stop back out as the End.
        if (m == PlanMode.ITINERARY) {
            val d = dest
            if (d != null && stops.lastOrNull()?.point != d) stops.add(ItineraryStop(destName, d, 30, mode, avgMph))
            dest = null; destName = ""
        } else if (planMode == PlanMode.ITINERARY && stops.size == 1) {
            val st = stops[0]; dest = st.point; destName = st.name
            stops.clear()
        }
        planMode = m
        invalidateRoute()
    }
    fun clearError() { error = null }

    private var routeJob: Job? = null

    private fun invalidateRoute() {
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

    /** Refresh the pick list as the user types (debounced; last keystroke wins). */
    fun suggest(query: String, bias: LatLng?) {
        suggestJob?.cancel()
        val q = query.trim()
        if (q.length < 2 || !hasKey) { suggestions.clear(); suggestBusy = false; return }
        suggestJob = viewModelScope.launch {
            delay(350)
            suggestBusy = true
            try {
                val hits = places.searchMany(q, bias ?: start, 8)
                if (!isActive) return@launch
                suggestions.clear(); suggestions.addAll(hits)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Places search failed"
            } finally { if (isActive) suggestBusy = false }
        }
    }

    fun clearSuggestions() { suggestJob?.cancel(); suggestions.clear(); suggestBusy = false }

    /** The user picked one row from the list: it becomes the start or the destination. */
    fun pickSuggestion(hit: PlaceHit) {
        clearSuggestions()
        applySearchResult(hit.latLng, hit.name)
        error = null
    }

    private fun applySearchResult(p: LatLng, name: String) {
        if (activeField == Field.START) setStartPoint(p, name) else setDestPoint(p, name)
    }

    /** Search by place/business/landmark name (Places), biased to the current start;
     *  falls back to Geocoding for plain addresses. Surfaces the real API error. */
    fun search(query: String, onFound: (LatLng) -> Unit) {
        if (!hasKey) { error = "Add MAPS_API_KEY to search by name"; return }
        clearSuggestions()
        viewModelScope.launch {
            error = null
            try {
                val hit = places.searchText(query, start)
                if (hit != null) { applySearchResult(hit.latLng, hit.name); onFound(hit.latLng); return@launch }
            } catch (e: Exception) {
                error = describe(e, "Places search failed")
                return@launch
            }
            // Places found nothing — try a plain-address geocode.
            try {
                val p = geocoder.geocode(query)
                if (p != null) { applySearchResult(p, query); onFound(p) } else error = "No match for “$query”"
            } catch (e: Exception) { error = describe(e, "Search failed") }
        }
    }

    // ---- Single trip -------------------------------------------------------------

    fun buildRoute() {
        val s = tripStart() ?: run { error = "Set a start point"; return }
        val d = dest ?: run { error = "Set a destination"; return }
        if (mode == TravelMode.FLY) {
            // Great-circle flight: computed locally, no routing API needed.
            val fm = FlightModel(s, d)
            routePts = fm.pathPoints; routeDistanceM = fm.totalMeters; isFlight = true; lastRoute = null; flightOrigin = s
            error = null; phase = Phase.READY
            return
        }
        if (!hasKey) { error = "Add MAPS_API_KEY to route"; return }
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            error = null; phase = Phase.ROUTING
            try {
                val r = routeEngine.route(RouteSpec(s, d, mode = mode, transitPreference = transitPref))
                if (!isActive) return@launch
                lastRoute = r; routePts = r.points; routeDistanceM = r.distanceMeters; isFlight = false
                phase = Phase.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { error = describe(e, "Routing failed"); phase = Phase.IDLE }
        }
    }

    fun startSim(onStart: () -> Unit) {
        PlaybackSource.timeScale = timeScale.toDouble()
        if (isFlight) {
            val s = flightOrigin ?: run { error = "Flight was reset — tap Plot flight again"; return }
            val d = dest ?: run { error = "Set an End"; return }
            PlaybackSource.current = FlightModel(s, d).fixes()
            PlaybackSource.label = "Flight"
        } else {
            val r = lastRoute ?: run { error = "Route was reset — tap Get route again"; return }
            if (mode == TravelMode.TRANSIT) {
                PlaybackSource.current = TransitModel(r).fixes()
                PlaybackSource.label = "Transit"
            } else {
                val params = MotionParams(avgSpeedMps = avgMph * 0.44704, realism = realism, mode = mode)
                PlaybackSource.current = MotionModel(r, params).fixes()
                PlaybackSource.label = "Route"
            }
        }
        PlaybackSource.routePoints = routePts
        useSimulatedStart = true
        onStart()
    }

    // ---- Itinerary ----------------------------------------------------------------

    fun removeStop(index: Int) { if (index in stops.indices) stops.removeAt(index) }

    /** Cycle a stop's travel mode (Drive -> Bike -> Walk -> Fly) and give it that mode's speed. */
    fun cycleStopMode(index: Int) {
        if (index !in stops.indices) return
        val st = stops[index]
        val next = TravelMode.entries[(st.mode.ordinal + 1) % TravelMode.entries.size]
        stops[index] = st.copy(mode = next, avgMph = modeSpeeds[next] ?: defaultSpeed(next))
    }

    fun adjustDwell(index: Int, deltaMinutes: Int) {
        if (index !in stops.indices) return
        val st = stops[index]
        stops[index] = st.copy(dwellMinutes = (st.dwellMinutes + deltaMinutes).coerceIn(0, 24 * 60))
    }

    /** Index of the stop whose stay time is being edited (UI dialog), or null. */
    var dwellEditIndex by mutableStateOf<Int?>(null)

    /** Set exactly how long to stay at a stop (0 – 24 h). */
    fun setDwell(index: Int, minutes: Int) {
        if (index !in stops.indices) return
        stops[index] = stops[index].copy(dwellMinutes = minutes.coerceIn(0, 24 * 60))
    }

    // ---- Saved plans (Snap / Route / Itinerary by name) --------------------------

    private var store: ScenarioStore = InMemoryScenarioStore()
    val savedScenarios = mutableStateListOf<SavedScenario>()

    /** Give the ViewModel its persistent store (the screen does this once). */
    fun attachStore(s: ScenarioStore) {
        store = s
        savedScenarios.clear(); savedScenarios.addAll(s.load().sortedByDescending { it.createdAt })
    }

    /** Is there anything on screen worth saving in the current mode? */
    val canSaveScenario: Boolean
        get() = when (planMode) {
            PlanMode.SNAP, PlanMode.ROUTE -> dest != null
            PlanMode.ITINERARY -> stops.isNotEmpty()
        }

    fun saveScenario(name: String): Boolean {
        val n = name.trim()
        if (n.isBlank() || !canSaveScenario) return false
        val sc = SavedScenario(
            id = "${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}",
            name = n, kind = planMode.name, createdAt = System.currentTimeMillis(),
            startIsReal = startFromReal || start == null,
            start = if (startFromReal) null else start, startName = if (startFromReal) "" else startName,
            dest = dest, destName = destName,
            travelMode = mode, speeds = modeSpeeds.toMap(), realism = realism, transitPref = transitPref,
            stops = stops.map { SavedStop(it.name, it.point.lat, it.point.lng, it.dwellMinutes, it.mode, it.avgMph) },
        )
        // Same name replaces the older copy.
        savedScenarios.removeAll { it.name.equals(n, ignoreCase = true) }
        savedScenarios.add(0, sc)
        store.save(savedScenarios.toList())
        return true
    }

    fun deleteScenario(id: String) {
        savedScenarios.removeAll { it.id == id }
        store.save(savedScenarios.toList())
    }

    /** Put a saved plan back on screen. A "real location" start uses today's real position. */
    fun loadScenario(sc: SavedScenario) {
        clearSuggestions()
        planMode = runCatching { PlanMode.valueOf(sc.kind) }.getOrDefault(PlanMode.ROUTE)
        mode = sc.travelMode
        sc.speeds.forEach { (m, v) -> modeSpeeds[m] = v }
        realism = sc.realism
        transitPref = sc.transitPref
        stops.clear()
        stops.addAll(sc.stops.map { ItineraryStop(it.name, LatLng(it.lat, it.lng), it.dwellMinutes, it.mode, it.avgMph) })
        if (sc.startIsReal || sc.start == null) {
            val real = lastReal
            if (real != null) { start = real; startName = "My location"; startFromReal = true }
            useSimulatedStart = true
        } else {
            start = sc.start; startName = sc.startName; startFromReal = false; useSimulatedStart = false
        }
        dest = sc.dest; destName = sc.destName
        invalidateRoute()
        error = null
        // Routes can be prepared straight away; the user then just taps Start.
        if (planMode == PlanMode.ROUTE && dest != null && tripStart() != null && (mode == TravelMode.FLY || hasKey)) buildRoute()
    }

    /** Route every leg up front, then arm ONE continuous stream: travel, dwell, travel, dwell... */
    fun startItinerary(onStart: () -> Unit) {
        val s = tripStart() ?: run { error = "Set a start point"; return }
        if (stops.isEmpty()) { error = "Add at least one stop"; return }
        if (stops.any { it.mode != TravelMode.FLY } && !hasKey) { error = "Add MAPS_API_KEY to route"; return }
        PlaybackSource.timeScale = timeScale.toDouble()
        viewModelScope.launch {
            error = null; itineraryBusy = true
            try {
                var from = s
                val legs = mutableListOf<Pair<Flow<Fix>, ItineraryStop>>()
                val allPts = mutableListOf<LatLng>()
                for (stop in stops) {
                    // Every leg travels with ITS OWN mode and speed, and the next one starts
                    // exactly where this one ended (road-snapped), so nothing teleports.
                    var legEnd: LatLng = stop.point
                    val legFlow: Flow<Fix> = if (stop.mode == TravelMode.FLY) {
                        val fm = FlightModel(from, stop.point); allPts += fm.pathPoints; fm.fixes()
                    } else {
                        val r = routeEngine.route(RouteSpec(from, stop.point, mode = stop.mode, transitPreference = transitPref)); allPts += r.points
                        legEnd = r.points.lastOrNull() ?: stop.point
                        if (stop.mode == TravelMode.TRANSIT) TransitModel(r).fixes() else {
                            val params = MotionParams(avgSpeedMps = stop.avgMph * 0.44704, realism = realism, mode = stop.mode)
                            MotionModel(r, params).fixes()
                        }
                    }
                    legs += legFlow to stop
                    from = legEnd
                }
                if (planMode != PlanMode.ITINERARY || !isActive) return@launch  // user moved on meanwhile
                routePts = allPts
                PlaybackSource.current = ItineraryModel.play(legs)
                PlaybackSource.routePoints = allPts
                PlaybackSource.label = "Itinerary"
                useSimulatedStart = true
                onStart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e, "Itinerary routing failed")
            } finally { itineraryBusy = false }
        }
    }

    fun onStopped() {
        phase = if (routePts.isEmpty()) Phase.IDLE else Phase.READY
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
