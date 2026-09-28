package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mirage.spike.engine.*
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.*

enum class Placement(val label: String) { NOW("Go now"), NEXT("After current stop"), END("At end of trip") }
enum class DestinationSource(val label: String) { SEARCH("Search"), SNAP("Snaps"), ROUTE("Routes"), ITINERARY("Itineraries") }

data class ContinuationDraft(
    val epoch: Long,
    val anchorId: String?,
    val placement: Placement,
    val source: DestinationSource = DestinationSource.SEARCH,
    val query: String = "",
    val hits: List<PlaceHit> = emptyList(),
    val searching: Boolean = false,
    val stops: List<ItineraryStop> = emptyList(),
    val title: String = "",
    val savedOrigin: LatLng? = null,
    val savedOriginName: String = "Route start",
    val connectStart: Boolean = true,
    val adaptedRealStart: Boolean = false,
    val mode: TravelMode = TravelMode.DRIVE,
    val keepRemaining: Boolean = true,
    val points: List<LatLng> = emptyList(),
    val previewBusy: Boolean = false,
    val ready: Boolean = false,
    val summary: String = "",
    val error: String? = null,
    val editorOpen: Boolean = true,
)

/** Selecting saved content creates an isolated operation, never loading the general draft. */
class ContinuationPlanner(private val vm: MirageViewModel, private val scope: CoroutineScope) {
    var state by mutableStateOf<ContinuationDraft?>(null)
        private set
    private var serial = 0L
    private var searchJob: Job? = null
    private var previewJob: Job? = null

    fun begin(placement: Placement? = null, source: DestinationSource = DestinationSource.SEARCH) {
        cancel()
        if (!MockState.status.value.running) return
        val view = LiveSession.state.value
        val current = view.stops.getOrNull(view.index)
        val holding = view.activity == ActivityKind.HOLDING && view.routeFailure == null
        val choice = placement ?: if (holding || LiveSession.plan == null || current == null) Placement.NOW else Placement.NEXT
        state = ContinuationDraft(LiveSession.epoch, current?.id, choice, source,
            mode = current?.stop?.mode ?: vm.mode)
    }
    fun cancel() { serial++; searchJob?.cancel(); previewJob?.cancel(); state = null }
    fun edit(open: Boolean) { state = state?.copy(editorOpen = open) }
    fun source(value: DestinationSource) {
        val d = state ?: return
        serial++; searchJob?.cancel(); previewJob?.cancel()
        state = d.copy(source = value, query = "", hits = emptyList(), searching = false,
            stops = emptyList(), title = "", summary = "", savedOrigin = null, adaptedRealStart = false,
            points = emptyList(), ready = false, previewBusy = false, error = null)
    }
    fun placement(value: Placement) {
        val d = state ?: return
        state = d.copy(placement = value, hits = emptyList(), searching = false)
        refresh()
    }
    fun keepRemaining(keep: Boolean) { state = state?.copy(keepRemaining = keep) }
    fun connectStart(connect: Boolean) { state = state?.copy(connectStart = connect); refresh() }
    fun mode(mode: TravelMode) {
        val d = state ?: return
        val stops = if (d.source in listOf(DestinationSource.SEARCH, DestinationSource.SNAP))
            d.stops.map { it.copy(mode = mode, avgMph = defaultSpeed(mode)) } else d.stops
        state = d.copy(mode = mode, stops = stops); refresh()
    }
    fun stay(minutes: Int) {
        val d = state ?: return
        if (d.stops.size == 1) state = d.copy(stops = listOf(d.stops.single().copy(dwellMinutes = minutes.coerceIn(0, 1440))))
    }
    fun onlyStop(index: Int) {
        val d = state ?: return
        val stop = d.stops.getOrNull(index) ?: return
        state = d.copy(stops = listOf(stop), savedOrigin = null, title = stop.name)
        refresh()
    }
    fun fromStop(index: Int) {
        val d = state ?: return
        if (index !in d.stops.indices) return
        state = d.copy(stops = d.stops.drop(index), savedOrigin = null, title = "From ${d.stops[index].name}")
        refresh()
    }
    fun origin(d: ContinuationDraft = state ?: error("No addition")): LatLng {
        check(MockState.status.value.running && d.epoch == LiveSession.epoch) { "The session changed. Close this addition and choose Add destination again." }
        val view = LiveSession.plan?.view()
        return when (d.placement) {
            Placement.NOW -> MockState.status.value.let { LatLng(it.lat, it.lng) }
            Placement.END -> view?.stops?.lastOrNull()?.stop?.point
                ?: MockState.status.value.let { LatLng(it.lat, it.lng) }
            Placement.NEXT -> {
                val i = view?.stops?.indexOfFirst { it.id == d.anchorId } ?: -1
                check(view != null && i >= view.index && i >= 0) { "The trip advanced. Close this addition and select the next stop again." }
                view.stops[i].stop.point
            }
        }
    }
    fun originLabel(d: ContinuationDraft): String = when (d.placement) {
        Placement.NOW -> "From your current simulated location"
        Placement.NEXT -> "After " + (LiveSession.state.value.stops.firstOrNull { it.id == d.anchorId }?.stop?.name ?: "current stop") + " and its stay"
        Placement.END -> "After the entire remaining trip"
    }
    fun query(text: String, immediate: Boolean = false) {
        val d = state ?: return
        serial++; val generation = serial
        searchJob?.cancel(); previewJob?.cancel()
        val q = text.trim()
        state = d.copy(query = text, hits = emptyList(), searching = q.length >= 2,
            stops = emptyList(), title = "", summary = "", savedOrigin = null, adaptedRealStart = false,
            points = emptyList(), ready = false, previewBusy = false, error = null)
        if (q.length < 2) return
        searchJob = scope.launch {
            try {
                if (!immediate) delay(350)
                check(vm.hasKey) { "Add your Maps key in Setup to search." }
                val hits = vm.placeSearch(vm.api, q, origin(d))
                if (isActive && generation == serial) state = state?.copy(hits = hits, searching = false,
                    error = if (hits.isEmpty()) "No matches. Try the full address and city." else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == serial) state = state?.copy(searching = false, error = e.message) }
        }
    }
    fun pick(hit: PlaceHit) {
        val d = state ?: return
        if (hit !in d.hits) return
        serial++; searchJob?.cancel()
        state = d.copy(query = hit.name, hits = emptyList(), searching = false, title = hit.name,
            savedOrigin = null, adaptedRealStart = false,
            stops = listOf(ItineraryStop(hit.name, hit.latLng, 0, d.mode, defaultSpeed(d.mode), hit.address, hit.placeId)))
        refresh()
    }
    fun saved(sc: SavedScenario, destinationOnly: Boolean = false) {
        val d = state ?: return
        serial++; searchJob?.cancel()
        val selected = when {
            sc.kind == "ITINERARY" && !destinationOnly -> sc.stops.map {
                ItineraryStop(it.name, LatLng(it.lat, it.lng), it.dwellMinutes, it.mode, it.avgMph,
                    it.address, it.placeId, it.routingRealism ?: sc.realism,
                    if (it.ownRoutingPreferences) it.routingTransitPref else sc.transitPref, true)
            }
            else -> sc.dest?.let {
                val mode = if (sc.kind == "SNAP") d.mode else sc.travelMode
                listOf(ItineraryStop(if (sc.kind == "SNAP") sc.name else sc.destName.ifBlank { sc.name },
                    it, 0, mode, sc.speeds[mode] ?: defaultSpeed(mode), sc.destAddress, sc.destPlaceId,
                    sc.realism, sc.transitPref, true))
            }.orEmpty()
        }
        if (selected.isEmpty()) {
            previewJob?.cancel()
            state = d.copy(stops = emptyList(), ready = false, previewBusy = false, points = emptyList(),
                searching = false, error = "This saved item has no destination. Choose another item.")
            return
        }
        state = d.copy(stops = selected, title = sc.name, hits = emptyList(), searching = false,
            savedOrigin = if (!destinationOnly && sc.kind != "SNAP" && !sc.startIsReal) sc.start else null,
            savedOriginName = sc.startName.ifBlank { "Saved start" }, connectStart = true,
            adaptedRealStart = sc.startIsReal && sc.kind != "SNAP", mode = selected.firstOrNull()?.mode ?: d.mode)
        refresh()
    }
    fun block(d: ContinuationDraft, from: LatLng): List<ItineraryStop> {
        val coordinates = d.stops.map { it.point } + listOfNotNull(d.savedOrigin.takeIf { d.connectStart })
        require(coordinates.all { it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }) { "This saved item has invalid coordinates. Choose another destination." }
        val start = d.savedOrigin
        if (start == null || !d.connectStart || Geo.haversine(from, start) <= 5.0) return d.stops
        return listOf(ItineraryStop(d.savedOriginName, start, 0, d.mode, defaultSpeed(d.mode))) + d.stops
    }
    private fun refresh() {
        val d = state ?: return
        serial++; val generation = serial
        searchJob?.cancel(); previewJob?.cancel()
        state = d.copy(ready = false, points = emptyList(), summary = "", error = null, searching = false, previewBusy = d.stops.isNotEmpty())
        if (d.stops.isEmpty()) return
        previewJob = scope.launch {
            try {
                val from = origin(d)
                val first = block(d, from).first()
                val points: List<LatLng>
                val summary: String
                if (first.mode == TravelMode.FLY) {
                    val flight = FlightModel(from, first.point)
                    points = flight.pathPoints; summary = "Flight · ${fmtMiles(flight.totalMeters)}"
                } else {
                    check(vm.hasKey) { "A Maps key is needed to prepare this route. Your existing trip is unchanged." }
                    val route = GoogleDirectionsRouteEngine(vm.api).route(RouteSpec(from, first.point, mode = first.mode,
                        transitPreference = if (first.ownRoutingPreferences) first.routingTransitPref else vm.transitPref))
                    points = route.points; summary = "${first.mode.name.lowercase()} · ${fmtMiles(route.distanceMeters)}"
                }
                if (isActive && generation == serial) state = state?.copy(points = points, summary = summary, ready = true, previewBusy = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == serial) state = state?.copy(error = e.message ?: "Could not prepare route", previewBusy = false) }
        }
    }
    fun retryPreview() = refresh()
    /** Only the explicit confirmation button commits, once. */
    fun commit(onStart: () -> Unit): Boolean {
        val d = state ?: return false
        if (!d.ready || d.previewBusy || d.stops.isEmpty()) return false
        try {
            val from = origin(d)
            val additions = block(d, from)
            val old = LiveSession.plan
            if (d.placement != Placement.NOW && old != null) {
                check(old.insertAfter(d.anchorId, additions, d.placement == Placement.END)) {
                    "The trip advanced. Close this addition and select the next stop again."
                }
                vm.notice = "Added ${additions.size} stop(s) ${if (d.placement == Placement.END) "at the end" else "after the current stop"}${if (PlaybackSource.paused) " · still paused" else ""}"
            } else {
                val remaining = if (d.keepRemaining) old?.remainingForContinuation().orEmpty() else emptyList()
                val all = additions + remaining
                val cfg = vm.api; val realism = vm.realism; val transit = vm.transitPref
                val plan = LivePlan(d.title, from, all, { a, b -> prepareLeg(cfg, a, b, realism, transit) })
                PlaybackSource.current = plan.fixes(); PlaybackSource.routePoints = listOf(from)
                PlaybackSource.label = d.title; PlaybackSource.endPoint = all.last().point
                if (!d.keepRemaining) PlaybackSource.clearQueue()
                vm.notice = "Continuing to ${additions.first().name}"
                onStart()
            }
            cancel()
            return true
        } catch (e: Exception) {
            state = state?.copy(error = e.message, ready = false, editorOpen = true)
            return false
        }
    }
}
