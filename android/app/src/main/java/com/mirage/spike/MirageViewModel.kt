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
import com.mirage.spike.engine.FlightParams
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
import com.mirage.spike.engine.RouteSpec
import com.mirage.spike.engine.TravelMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Phase { IDLE, ROUTING, READY, RUNNING }

/** Drives the map screen: endpoints, routing, average speed, itinerary, and simulation control. */
class MirageViewModel : ViewModel() {

    // ---- Endpoints -----------------------------------------------------------
    var start by mutableStateOf<LatLng?>(LatLng(START_LAT, START_LNG))
        private set
    var startName by mutableStateOf("Map point")
        private set
    var dest by mutableStateOf<LatLng?>(null)
        private set
    var destName by mutableStateOf("Destination")
        private set
    /** True while the next search pick / map tap should set the START rather than the destination. */
    var pickingStart by mutableStateOf(false)
    /** While simulating, the next trip normally begins where the simulation is right now. */
    var useSimulatedStart by mutableStateOf(true)
        private set

    // ---- Route ---------------------------------------------------------------
    var routePts by mutableStateOf<List<LatLng>>(emptyList())
        private set
    var routeDistanceM by mutableStateOf(0.0)
        private set
    private var lastRoute: RouteResult? = null
    private var isFlight = false
    private var flightOrigin: LatLng? = null

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
    /** Fast-forward for testing: positions advance N× faster than real time. */
    var timeScale by mutableStateOf(1f)

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)

    // ---- Itinerary: an ordered list of stops with a dwell time at each ------
    val stops = mutableStateListOf<ItineraryStop>()
    var itineraryMode by mutableStateOf(false)
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

    fun setStartPoint(p: LatLng, name: String = "Dropped pin") {
        start = p; startName = name; useSimulatedStart = false; pickingStart = false; invalidateRoute()
    }

    /** Back to "begin where the simulation is now" (only meaningful while running). */
    fun useSimulatedPosition() { useSimulatedStart = true; pickingStart = false; invalidateRoute() }

    /** A map tap sets whichever endpoint the user is currently choosing. */
    fun placeTapped(p: LatLng) { if (pickingStart) setStartPoint(p) else setDestPoint(p) }

    /** Where the next trip begins: the live simulated position while running (unless the
     *  user chose an explicit start), else the start pin. */
    fun tripStart(): LatLng? {
        val st = MockState.status.value
        return if (st.running && useSimulatedStart) LatLng(st.lat, st.lng) else start
    }
    fun useMyLocation(p: LatLng) = setStartPoint(p, "My location")
    fun setDestPoint(p: LatLng) { dest = p; destName = "Dropped pin"; invalidateRoute() }
    fun clearError() { error = null }

    private fun invalidateRoute() {
        routePts = emptyList(); lastRoute = null; isFlight = false; routeDistanceM = 0.0
        if (phase == Phase.READY) phase = Phase.IDLE
    }

    fun chooseMode(m: TravelMode) { mode = m; invalidateRoute() }

    /** Distance and a time estimate at the chosen speed, once a route/flight is plotted. */
    val routeSummary: String?
        get() {
            if (routePts.isEmpty() || routeDistanceM <= 0.0) return null
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
                suggestions.clear(); suggestions.addAll(hits)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Places search failed"
            } finally { suggestBusy = false }
        }
    }

    fun clearSuggestions() { suggestJob?.cancel(); suggestions.clear(); suggestBusy = false }

    /** The user picked one row from the list: it becomes the start or the destination. */
    fun pickSuggestion(hit: PlaceHit) {
        clearSuggestions()
        if (pickingStart) setStartPoint(hit.latLng, hit.name)
        else { setDestPoint(hit.latLng); destName = hit.name }
        error = null
    }

    private fun applySearchResult(p: LatLng, name: String) {
        if (pickingStart) setStartPoint(p, name) else { setDestPoint(p); destName = name }
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
                error = e.message ?: "Places search failed"
                return@launch
            }
            // Places found nothing — try a plain-address geocode.
            try {
                val p = geocoder.geocode(query)
                if (p != null) { applySearchResult(p, query); onFound(p) } else error = "No match for “$query”"
            } catch (e: Exception) { error = e.message ?: "Search failed" }
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
        viewModelScope.launch {
            error = null; phase = Phase.ROUTING
            try {
                val r = routeEngine.route(RouteSpec(s, d, mode = mode))
                lastRoute = r; routePts = r.points; routeDistanceM = r.distanceMeters; isFlight = false
                phase = Phase.READY
            } catch (e: Exception) { error = e.message; phase = Phase.IDLE }
        }
    }

    fun startSim(onStart: () -> Unit) {
        val ts = timeScale.toDouble()
        if (isFlight) {
            val s = flightOrigin ?: return
            val d = dest ?: return
            PlaybackSource.current = FlightModel(s, d, FlightParams(timeScale = ts)).fixes()
            PlaybackSource.label = "Flight"
        } else {
            val r = lastRoute ?: return
            val params = MotionParams(avgSpeedMps = avgMph * 0.44704, realism = realism, mode = mode, timeScale = ts)
            PlaybackSource.current = MotionModel(r, params).fixes()
            PlaybackSource.label = "Route"
        }
        PlaybackSource.routePoints = routePts
        useSimulatedStart = true; pickingStart = false
        onStart()
    }

    // ---- Itinerary ----------------------------------------------------------------

    fun addStop(dwellMinutes: Int = 30) {
        val d = dest ?: run { error = "Search or tap a destination first"; return }
        stops.add(ItineraryStop(destName, d, dwellMinutes, mode, avgMph))
        error = null
    }

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

    /** Route every leg up front, then arm ONE continuous stream: travel, dwell, travel, dwell... */
    fun startItinerary(onStart: () -> Unit) {
        val s = tripStart() ?: run { error = "Set a start point"; return }
        if (stops.isEmpty()) { error = "Add at least one stop"; return }
        if (stops.any { it.mode != TravelMode.FLY } && !hasKey) { error = "Add MAPS_API_KEY to route"; return }
        val ts = timeScale.toDouble()
        viewModelScope.launch {
            error = null; itineraryBusy = true
            try {
                var from = s
                val legs = mutableListOf<Pair<Flow<Fix>, ItineraryStop>>()
                val allPts = mutableListOf<LatLng>()
                for (stop in stops) {
                    // Every leg travels with ITS OWN mode and speed.
                    val legFlow: Flow<Fix> = if (stop.mode == TravelMode.FLY) {
                        val fm = FlightModel(from, stop.point, FlightParams(timeScale = ts)); allPts += fm.pathPoints; fm.fixes()
                    } else {
                        val r = routeEngine.route(RouteSpec(from, stop.point, mode = stop.mode)); allPts += r.points
                        val params = MotionParams(avgSpeedMps = stop.avgMph * 0.44704, realism = realism, mode = stop.mode, timeScale = ts)
                        MotionModel(r, params).fixes()
                    }
                    legs += legFlow to stop
                    from = stop.point
                }
                routePts = allPts
                PlaybackSource.current = ItineraryModel.play(legs, timeScale = ts)
                PlaybackSource.routePoints = allPts
                PlaybackSource.label = "Itinerary"
                useSimulatedStart = true; pickingStart = false
                onStart()
            } catch (e: Exception) {
                error = e.message ?: "Itinerary routing failed"
            } finally { itineraryBusy = false }
        }
    }

    fun onStopped() {
        phase = if (routePts.isEmpty()) Phase.IDLE else Phase.READY
    }
}

/** Sensible default speed (mph) for each way of getting around. */
fun defaultSpeed(m: TravelMode): Float = when (m) {
    TravelMode.DRIVE -> 45f
    TravelMode.BIKE -> 12f
    TravelMode.WALK -> 3f
    TravelMode.FLY -> 550f
}

/** Slider range (mph) that makes sense for each mode. */
fun speedRange(m: TravelMode): ClosedFloatingPointRange<Float> = when (m) {
    TravelMode.DRIVE -> 5f..80f
    TravelMode.BIKE -> 3f..25f
    TravelMode.WALK -> 1f..6f
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
