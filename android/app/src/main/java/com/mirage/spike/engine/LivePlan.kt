package com.mirage.spike.engine

import com.mirage.spike.MockState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.UUID

enum class ActivityKind { IDLE, ROUTING, TRAVELING, STAYING, HOLDING }
data class LiveStop(val id: String = UUID.randomUUID().toString(), val stop: ItineraryStop)
data class SessionView(
    val title: String = "", val stops: List<LiveStop> = emptyList(), val index: Int = -1,
    val activity: ActivityKind = ActivityKind.IDLE, val points: List<LatLng> = emptyList(),
    val remainingStaySeconds: Int = 0,
    val routeFailure: String? = null,
)

/** Execution is separate from the editable draft. All edits target stable stop IDs. */
object LiveSession {
    private val mutable = MutableStateFlow(SessionView())
    val state = mutable.asStateFlow()
    @Volatile var epoch: Long = 0
        private set
    @Volatile var plan: LivePlan? = null
        private set
    fun activate(p: LivePlan) { val snapshot = p.view(); synchronized(this) { if (plan !== p) epoch++; plan = p; mutable.value = snapshot } }
    @Synchronized fun publish(p: LivePlan, view: SessionView) { if (plan === p) mutable.value = view }
    @Synchronized fun holding(title: String, points: List<LatLng>) {
        epoch++; plan = null
        mutable.value = SessionView(title = title, activity = ActivityKind.HOLDING, points = points)
    }
    @Synchronized fun clear() { epoch++; plan = null; mutable.value = SessionView() }
}

data class PreparedLeg(val flow: Flow<Fix>, val points: List<LatLng>)

/** Routes future legs just in time, holding the last location throughout API latency. */
class LivePlan(
    val title: String,
    val origin: LatLng,
    stops: List<ItineraryStop>,
    private val route: suspend (LatLng, ItineraryStop) -> PreparedLeg,
    private val firstLeg: PreparedLeg? = null,
) {
    private val entries = stops.map { LiveStop(stop = it) }.toMutableList()
    private var index = -1
    private var kind = ActivityKind.IDLE
    private var points = firstLeg?.points.orEmpty()
    private var staySeconds = 0.0
    private var extraOnArrivalSeconds = 0.0

    @Volatile private var routeFailure: String? = null
    @Volatile private var retryRequested = false
    @Synchronized fun retryFailed() { retryRequested = true }
    @Synchronized fun remainingForContinuation(): List<ItineraryStop> {
        val first = if (kind == ActivityKind.TRAVELING || kind == ActivityKind.ROUTING || routeFailure != null) index.coerceAtLeast(0) else (index + 1).coerceAtLeast(0)
        return entries.drop(first).map { it.stop }
    }
    /** Insert complete blocks atomically; never reuse a completed anchor. */
    @Synchronized fun insertAfter(anchor: String?, added: List<ItineraryStop>, atEnd: Boolean = false): Boolean {
        if (added.isEmpty()) return false
        val at = if (atEnd) entries.size else {
            val i = entries.indexOfFirst { it.id == anchor }
            if (i < index || i < 0) return false
            i + 1
        }
        entries.addAll(at, added.map { LiveStop(stop = it) }); publish(); return true
    }
    @Synchronized fun appendAll(stops: List<ItineraryStop>) { entries.addAll(stops.map { LiveStop(stop = it) }); publish() }
    @Synchronized fun view() = SessionView(title, entries.toList(), index, kind, points, staySeconds.toInt(), routeFailure)
    private fun publish() = LiveSession.publish(this, view())
    /** Full replay template: elapsed time never changes configured durations. */
    @Synchronized fun stopsForSave(): List<ItineraryStop> = entries.map { it.stop }
    @Synchronized fun append(stop: ItineraryStop) { entries += LiveStop(stop = stop); publish() }
    @Synchronized fun remove(id: String): Boolean {
        val i = entries.indexOfFirst { it.id == id }
        if (i <= index || i < 0) return false
        entries.removeAt(i); publish(); return true
    }
    @Synchronized fun move(id: String, delta: Int): Boolean {
        val i = entries.indexOfFirst { it.id == id }; val j = i + delta
        if (i <= index || j <= index || i !in entries.indices || j !in entries.indices) return false
        val item = entries.removeAt(i); entries.add(j, item); publish(); return true
    }
    @Synchronized fun setStay(id: String, minutes: Int): Boolean {
        val i = entries.indexOfFirst { it.id == id }
        if (i <= index || i < 0) return false
        entries[i] = entries[i].copy(stop = entries[i].stop.copy(dwellMinutes = minutes.coerceIn(0, 1440)))
        publish(); return true
    }
    @Synchronized fun setMode(id: String, mode: TravelMode): Boolean {
        val i = entries.indexOfFirst { it.id == id }
        if (i <= index || i < 0) return false
        entries[i] = entries[i].copy(stop = entries[i].stop.copy(mode = mode, avgMph = when(mode) {
            TravelMode.WALK -> 3f; TravelMode.BIKE -> 12f; TravelMode.FLY -> 550f; TravelMode.TRANSIT -> 20f; else -> 45f
        }))
        publish(); return true
    }
    @Synchronized fun setCurrentStay(minutes: Int): Boolean {
        if (index !in entries.indices) return false
        val seconds = minutes.coerceIn(0, 1440) * 60.0
        entries[index] = entries[index].copy(stop = entries[index].stop.copy(dwellMinutes = minutes.coerceIn(0, 1440)))
        if (kind == ActivityKind.TRAVELING || kind == ActivityKind.ROUTING) {
            extraOnArrivalSeconds = 0.0
        } else {
            staySeconds = seconds
            kind = if (seconds > 0) ActivityKind.STAYING else ActivityKind.HOLDING
        }
        publish(); return true
    }
    @Synchronized fun extendStay(minutes: Int): Boolean {
        if (kind == ActivityKind.STAYING || kind == ActivityKind.HOLDING) { kind = ActivityKind.STAYING; staySeconds = (staySeconds + minutes * 60).coerceIn(0.0, 86400.0) }
        else if (kind == ActivityKind.TRAVELING || kind == ActivityKind.ROUTING) extraOnArrivalSeconds = 0.0
        else return false
        entries[index] = entries[index].copy(stop = entries[index].stop.copy(dwellMinutes = (entries[index].stop.dwellMinutes + minutes).coerceIn(0, 1440)))
        publish(); return true
    }
    @Synchronized private fun next(): ItineraryStop? {
        if (index + 1 >= entries.size) return null
        index++; kind = ActivityKind.ROUTING; publish(); return entries[index].stop
    }
    @Synchronized private fun traveling(p: List<LatLng>) { points = p; kind = ActivityKind.TRAVELING; publish() }
    @Synchronized private fun beginStay(stop: ItineraryStop) {
        kind = ActivityKind.STAYING; staySeconds = entries[index].stop.dwellMinutes * 60.0 + extraOnArrivalSeconds
        extraOnArrivalSeconds = 0.0; publish()
    }
    @Synchronized private fun tickStay(dt: Double): Double { staySeconds = (staySeconds - dt).coerceAtLeast(0.0); publish(); return staySeconds }
    @Synchronized private fun remaining() = staySeconds
    @Synchronized private fun hasNext() = index + 1 < entries.size
    @Synchronized private fun hold() { kind = ActivityKind.HOLDING; publish() }

    fun fixes(): Flow<Fix> = flow {
        LiveSession.activate(this@LivePlan)
        var last = Fix(origin.lat, origin.lng, 0f, 0f, 4f)
        while (true) {
            val stop = next()
            if (stop == null) {
                hold()
                MockState.update { it.copy(stepLabel = "Arrived — holding until you leave or Stop", legIndex = index) }
                val dwell = DwellModel(last.copy(speedMps = 0f))
                while (!hasNext() || remaining() > 0) {
                    if (remaining() <= 0 && PlaybackSource.queueSize() > 0) return@flow
                    if (remaining() > 0 && PlaybackSource.consumeSkip()) { tickStay(86400.0); hold() }
                    emit(dwell.next(0.2 * PlaybackSource.timeScale).copy(progress = -1f, remainingSec = if (remaining() > 0) (remaining() / PlaybackSource.timeScale).toInt() else -1))
                    delay(200)
                    if (remaining() > 0 && tickStay(0.2 * PlaybackSource.timeScale) <= 0) hold()
                }
                continue
            }
            var prepared: PreparedLeg? = null
            var skipped = false
            while (prepared == null && !skipped) {
                try {
                    routeFailure = null
                    synchronized(this@LivePlan) { kind = ActivityKind.ROUTING; publish() }
                    prepared = if (index == 0 && firstLeg != null) firstLeg else coroutineScope {
                        MockState.update { it.copy(stepLabel = "Finding route to ${stop.name}", legIndex = index) }
                        val task = async { route(LatLng(last.lat, last.lng), stop) }
                        while (!task.isCompleted) {
                            emit(last.copy(speedMps = 0f, progress = -1f, remainingSec = -1)); delay(200)
                        }
                        task.await()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    routeFailure = e.message ?: "Route unavailable"
                    retryRequested = false
                    hold()
                    MockState.update { it.copy(stepLabel = "Route failed: $routeFailure. Holding here; retry or skip this stop.") }
                    while (!retryRequested) {
                        if (PlaybackSource.consumeSkip()) { skipped = true; break }
                        emit(last.copy(speedMps = 0f, progress = -1f, remainingSec = -1)); delay(200)
                    }
                }
            }
            if (skipped) { routeFailure = null; continue }
            val leg = prepared ?: continue
            traveling(leg.points)
            PlaybackSource.routePoints = leg.points
            PlaybackSource.endPoint = leg.points.lastOrNull()
            MockState.update { it.copy(stepLabel = "To ${stop.name}", legIndex = index) }
            leg.flow.collect { last = it; emit(it) }
            beginStay(stop)
            val dwell = DwellModel(last.copy(speedMps = 0f), 12.0)
            MockState.update { it.copy(stepLabel = "At ${stop.name}") }
            while (remaining() > 0) {
                if (PlaybackSource.consumeSkip()) { tickStay(86400.0); break }
                emit(dwell.next(0.2 * PlaybackSource.timeScale).copy(remainingSec = (remaining() / PlaybackSource.timeScale).toInt()))
                delay(200)
                tickStay(0.2 * PlaybackSource.timeScale)
            }
        }
    }
}

/** Shared by visual planning and conversational commands. */
suspend fun prepareLeg(cfg: ApiConfig, from: LatLng, stop: ItineraryStop, realism: Realism = Realism.REALISTIC, transitPreference: String? = null): PreparedLeg {
    if (stop.mode == TravelMode.FLY) {
        val flight = FlightModel(from, stop.point)
        return PreparedLeg(flight.fixes(), flight.pathPoints)
    }
    val effectiveRealism = stop.routingRealism ?: realism
    val effectiveTransit = if (stop.ownRoutingPreferences) stop.routingTransitPref else transitPreference
    val r = GoogleDirectionsRouteEngine(cfg).route(RouteSpec(from, stop.point, mode = stop.mode, transitPreference = effectiveTransit))
    val flow = when {
        stop.mode == TravelMode.TRANSIT -> TransitModel(r).fixes()
        stop.mode == TravelMode.DRIVE && r.segments.isNotEmpty() -> DriveModel(r, effectiveRealism).fixes()
        else -> MotionModel(r, MotionParams(avgSpeedMps = stop.avgMph * 0.44704, realism = effectiveRealism, mode = stop.mode)).fixes()
    }
    return PreparedLeg(flow, r.points)
}

/** Snap is a one-stop session too, so timed stays and future stops remain editable. */
fun holdPlan(at: LatLng, name: String, cfg: ApiConfig): LivePlan {
    val stop = ItineraryStop(name, at, 0)
    return LivePlan(name, at, listOf(stop), { from, to -> prepareLeg(cfg, from, to) },
        PreparedLeg(flow { emit(Fix(at.lat, at.lng, 0f, 0f, 4f)) }, listOf(at)))
}

