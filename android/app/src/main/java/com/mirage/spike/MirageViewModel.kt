package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirage.spike.engine.FlightModel
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
    var avgMph by mutableStateOf(45f)
    var realism by mutableStateOf(Realism.REALISTIC)
    var mode by mutableStateOf(TravelMode.DRIVE)
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)

    private var lastRoute: RouteResult? = null
    private var isFlight = false

    val hasKey: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()
    private val routeEngine by lazy { GoogleDirectionsRouteEngine(BuildConfig.MAPS_API_KEY) }
    private val geocoder by lazy { GoogleGeocoder(BuildConfig.MAPS_API_KEY) }
    private val places by lazy { GooglePlaces(BuildConfig.MAPS_API_KEY) }

    fun setStartPoint(p: LatLng) { start = p; invalidateRoute() }
    fun setDestPoint(p: LatLng) { dest = p; invalidateRoute() }
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
                if (hit != null) { setDestPoint(hit.latLng); onFound(hit.latLng); return@launch }
            } catch (e: Exception) {
                error = e.message ?: "Places search failed"
                return@launch
            }
            // Places found nothing — try a plain-address geocode.
            try {
                val p = geocoder.geocode(query)
                if (p != null) { setDestPoint(p); onFound(p) } else error = "No match for “$query”"
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
            val params = MotionParams(avgSpeedMps = avgMph * 0.44704, realism = realism)
            PlaybackSource.current = MotionModel(r, params).fixes()
            PlaybackSource.label = "Route"
        }
        PlaybackSource.routePoints = routePts
        onStart()
    }

    fun onStopped() {
        phase = if (routePts.isEmpty()) Phase.IDLE else Phase.READY
    }
}
