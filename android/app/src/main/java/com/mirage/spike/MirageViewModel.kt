package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirage.spike.engine.FlightModel
import com.mirage.spike.engine.Fix
import com.mirage.spike.engine.ItineraryModel
import com.mirage.spike.engine.ItineraryStop
import com.mirage.spike.engine.GoogleDirectionsRouteEngine
import com.mirage.spike.engine.GoogleGeocoder
import com.mirage.spike.engine.GooglePlaces
import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.MotionModel
import com.mirage.spike.engine.MotionParams
import com.mirage.spike.engine.PlaybackSource
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.RouteResult
import com.mirage.spike.engine.RouteSpec
import com.mirage.spike.engine.TravelMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

enum class Phase { IDLE, ROUTING, READY, RUNNING }

/** Drives the map screen: endpoints, routing, average speed, and simulation control. */
class MirageViewModel : ViewModel() {

    var start by mutableStateOf<LatLng?>(LatLng(START_LAT, START_LNG))
        private set
    var dest by mutableStateOf<LatLng?>(null)
        private set
    var routePts by mutableStateOf<List<LatLng>>(emptyList())
        private set
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
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)

    private var lastRoute: RouteResult? = null
    private var isFlight = false

    // Itinerary: an ordered list of stops with a dwell time at each.
    val stops = mutableStateListOf<ItineraryStop>()
    var itineraryMode by mutableStateOf(false)
    var itineraryBusy by mutableStateOf(false)
        private set
    var destName by mutableStateOf("Destination")
        private set

    val hasKey: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()
    private val routeEngine by lazy { GoogleDirectionsRouteEngine(BuildConfig.MAPS_API_KEY) }
    private val geocoder by lazy { GoogleGeocoder(BuildConfig.MAPS_API_KEY) }
    private val places by lazy { GooglePlaces(BuildConfig.MAPS_API_KEY) }

    fun setStartPoint(p: LatLng) { start = p; invalidateRoute() }
    fun setDestPoint(p: LatLng) { dest = p; destName = "Dropped pin"; invalidateRoute() }
    fun clearError() { error = null }

    private fun invalidateRoute() {
        routePts = emptyList(); lastRoute = null; isFlight = false
        if (phase == Phase.READY) phase = Phase.IDLE
    }

    fun chooseMode(m: TravelMode) { mode = m; invalidateRoute() }

    /** Search by place/business/landmark name (Places), biased to the current start;
     *  falls back to Geocoding for plain addresses. Surfaces the real API error. */
    fun search(query: String, onFound: (LatLng) -> Unit) {
        if (!hasKey) { error = "Add MAPS_API_KEY to search by name"; return }
        viewModelScope.launch {
            error = null
            try {
                val hit = places.searchText(query, start)
                if (hit != null) { setDestPoint(hit.latLng); destName = hit.name; onFound(hit.latLng); return@launch }
            } catch (e: Exception) {
                error = e.message ?: "Places search failed"
                return@launch
            }
            // Places found nothing — try a plain-address geocode.
            try {
                val p = geocoder.geocode(query)
                if (p != null) { setDestPoint(p); destName = query; onFound(p) } else error = "No match for “$query”"
            } catch (e: Exception) { error = e.message ?: "Search failed" }
        }
    }

    fun buildRoute() {
        val s = start ?: run { error = "Set a start point"; return }
        val d = dest ?: run { error = "Set a destination"; return }
        if (mode == TravelMode.FLY) {
            // Great-circle flight: computed locally, no routing API needed.
            val fm = FlightModel(s, d)
            routePts = fm.pathPoints; isFlight = true; lastRoute = null
            error = null; phase = Phase.READY
            return
        }
        if (!hasKey) { error = "Add MAPS_API_KEY to route"; return }
        viewModelScope.launch {
            error = null; phase = Phase.ROUTING
            try {
                val r = routeEngine.route(RouteSpec(s, d, mode = mode))
                lastRoute = r; routePts = r.points; isFlight = false; phase = Phase.READY
            } catch (e: Exception) { error = e.message; phase = Phase.IDLE }
        }
    }

    fun startSim(onStart: () -> Unit) {
        if (isFlight) {
            val s = start ?: return
            val d = dest ?: return
            PlaybackSource.current = FlightModel(s, d).fixes()
            PlaybackSource.label = "Flight"
        } else {
            val r = lastRoute ?: return
            val params = MotionParams(avgSpeedMps = avgMph * 0.44704, realism = realism, mode = mode)
            PlaybackSource.current = MotionModel(r, params).fixes()
            PlaybackSource.label = "Route"
        }
        PlaybackSource.routePoints = routePts
        onStart()
    }

    // ---- Itinerary ----------------------------------------------------------

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
        val s = start ?: run { error = "Set a start point"; return }
        if (stops.isEmpty()) { error = "Add at least one stop"; return }
        if (stops.any { it.mode != TravelMode.FLY } && !hasKey) { error = "Add MAPS_API_KEY to route"; return }
        viewModelScope.launch {
            error = null; itineraryBusy = true
            try {
                var from = s
                val legs = mutableListOf<Pair<Flow<Fix>, ItineraryStop>>()
                val allPts = mutableListOf<LatLng>()
                for (stop in stops) {
                    // Every leg travels with ITS OWN mode and speed.
                    val legFlow: Flow<Fix> = if (stop.mode == TravelMode.FLY) {
                        val fm = FlightModel(from, stop.point); allPts += fm.pathPoints; fm.fixes()
                    } else {
                        val r = routeEngine.route(RouteSpec(from, stop.point, mode = stop.mode)); allPts += r.points
                        val params = MotionParams(avgSpeedMps = stop.avgMph * 0.44704, realism = realism, mode = stop.mode)
                        MotionModel(r, params).fixes()
                    }
                    legs += legFlow to stop
                    from = stop.point
                }
                routePts = allPts
                PlaybackSource.current = ItineraryModel.play(legs)
                PlaybackSource.routePoints = allPts
                PlaybackSource.label = "Itinerary"
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
