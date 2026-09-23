package com.mirage.spike

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mirage.spike.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatLine(val user: Boolean, val text: String)
data class ChatView(val lines: List<ChatLine> = emptyList(), val choices: List<PlaceHit> = emptyList(), val busy: Boolean = false)

/** Process-scoped command controller: voice works with the Activity in the background. */
object Conversation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ChatView())
    val state = mutable.asStateFlow()
    val speech = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private var context: Context? = null
    private var api = ApiConfig("", "", "")
    private var real: LatLng? = null
    private var job: Job? = null
    private var serial = 0L
    private var pending: SpokenCommand.Journey? = null
    private var resolved = mutableListOf<ItineraryStop>()
    private var pendingSpoken = false
    private var clarifyStop = false
    fun needsReply() = clarifyStop || mutable.value.choices.isNotEmpty()
    private var lastRequest: SpokenCommand.Journey? = null

    fun configure(ctx: Context, cfg: ApiConfig, realPosition: LatLng?) {
        context = ctx.applicationContext; api = cfg; real = realPosition ?: real
    }
    fun notice(text: String) { scope.launch { reply(text, false) } }
    fun cancelPending() {
        serial++; job?.cancel(); job = null; pending = null; resolved.clear(); clarifyStop = false
        mutable.update { it.copy(choices = emptyList(), busy = false) }
    }
    private fun reply(text: String, spoken: Boolean) {
        mutable.update { it.copy(lines = (it.lines + ChatLine(false, text)).takeLast(100)) }
        if (spoken) speech.tryEmit(text)
    }
    fun submit(text: String, spoken: Boolean = false) {
        if (text.isBlank()) return
        mutable.update { it.copy(lines = (it.lines + ChatLine(true, text)).takeLast(100)) }
        val normalized = CommandParser.normalize(text)
        if (normalized in listOf("go there now instead", "go there now", "go there next")) {
            val prior = pending ?: lastRequest
            if (prior == null) { reply("Name a destination first.", spoken); return }
            val request = prior.copy(next = normalized == "go there next")
            cancelPending(); begin(request, spoken); return
        }
        val command = CommandParser.parse(text)
        if (command is SpokenCommand.Choice) { choose(command.ordinal - 1, spoken); return }
        // A new instruction supersedes unresolved place searches and delayed routing.
        cancelPending()
        val st = MockState.status.value
        when (command) {
            SpokenCommand.Pause -> if (st.running) { PlaybackSource.paused = true; reply("Paused here. Simulation remains on.", spoken) } else reply("No simulation is running.", spoken)
            SpokenCommand.Resume -> if (st.running) { PlaybackSource.paused = false; reply("Resuming.", spoken) } else reply("No simulation is running.", spoken)
            SpokenCommand.Stop -> {
                context?.let { it.startService(Intent(it, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP)) }
                reply("Stopping simulation and requesting your real location.", spoken)
            }
            SpokenCommand.ClarifyStop -> { clarifyStop = true; reply("Pause here, or end the simulation? Say pause or stop simulation.", spoken) }
            SpokenCommand.Skip -> {
                val kind = LiveSession.state.value.activity
                if (st.running && kind in listOf(ActivityKind.TRAVELING, ActivityKind.STAYING)) {
                    PlaybackSource.paused = false; PlaybackSource.requestSkip()
                    reply(if (kind == ActivityKind.STAYING) "Ending this stay." else "Jumping to arrival.", spoken)
                } else reply("There is no journey or timed stay to skip.", spoken)
            }
            is SpokenCommand.Stay -> {
                if (LiveSession.plan?.setCurrentStay(command.minutes) == true) reply("Stay set to ${command.minutes} minutes. Simulation will remain on afterward.", spoken)
                else reply("Start a simulation before setting a stay.", spoken)
            }
            is SpokenCommand.Extend -> {
                if (LiveSession.plan?.extendStay(command.minutes) == true) reply("Added ${command.minutes} minutes to this stop's stay.", spoken)
                else reply("There is no timed stop to extend. Say drive to a place, then stay for thirty minutes.", spoken)
            }
            is SpokenCommand.Scale -> { PlaybackSource.timeScale = command.factor; reply("Fast-forward set to ${command.factor.toInt()} times.", spoken) }
            is SpokenCommand.Remove -> {
                val view = LiveSession.state.value
                val stop = view.stops.drop(view.index + 1).getOrNull(command.ordinal - 1)
                if (stop != null && LiveSession.plan?.remove(stop.id) == true) reply("Removed ${stop.stop.name} from upcoming stops.", spoken)
                else reply("There is no upcoming stop ${command.ordinal} to remove.", spoken)
            }
            SpokenCommand.Status -> reply(describeStatus(), spoken)
            SpokenCommand.Cancel -> reply("Cancelled the pending instruction. Your simulation is unchanged.", spoken)
            SpokenCommand.Help -> reply("Try: drive to a place; after this, walk to a place; drive to a place then stay for thirty minutes then drive to another place; pause; continue; extend stay by ten minutes; remove the second stop; what happens next; or stop simulation.", spoken)
            is SpokenCommand.Journey -> begin(command, spoken)
            else -> reply("I didn't understand that instruction. Try ‘drive to [place]’, ‘pause’, ‘what happens next’, or ‘help’.", spoken)
        }
    }
    fun choose(index: Int, spoken: Boolean = pendingSpoken) {
        val hit = mutable.value.choices.getOrNull(index) ?: run { reply("Choose one of the listed places by number.", spoken); return }
        val request = pending ?: return
        val leg = request.legs.getOrNull(resolved.size) ?: return
        resolved += ItineraryStop(hit.name, hit.latLng, leg.minutes, leg.mode, defaultSpeed(leg.mode))
        mutable.update { it.copy(choices = emptyList()) }
        resolveNext(request, spoken)
    }
    private fun begin(request: SpokenCommand.Journey, spoken: Boolean) {
        if (!api.enabled) { reply("Add your Maps key in Setup to search for places.", spoken); return }
        pending = request; pendingSpoken = spoken; lastRequest = request
        resolveNext(request, spoken)
    }
    private fun position(): LatLng? = MockState.status.value.let { if (it.running) LatLng(it.lat, it.lng) else real }
    private fun resolveNext(request: SpokenCommand.Journey, spoken: Boolean) {
        val generation = serial
        job = scope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                while (resolved.size < request.legs.size) {
                    val leg = request.legs[resolved.size]
                    val hits = GooglePlaces(api).searchMany(leg.query, resolved.lastOrNull()?.point ?: position(), 3)
                    ensureActive(); if (serial != generation) return@launch
                    if (hits.isEmpty()) { reply("No match for ${leg.query}. Try a more specific name or address.", spoken); return@launch }
                    // Never silently choose among multiple business branches.
                    if (hits.size > 1) {
                        mutable.update { it.copy(choices = hits) }
                        reply("Which place? " + hits.mapIndexed { i, h -> "${i + 1}: ${h.name}, ${h.address}" }.joinToString(". "), spoken)
                        return@launch
                    }
                    val hit = hits.single()
                    resolved += ItineraryStop(hit.name, hit.latLng, leg.minutes, leg.mode, defaultSpeed(leg.mode))
                    continue
                }
                val ctx = context ?: error("Open Mirage first")
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    reply("Open Setup and grant precise location permission first.", spoken); return@launch
                }
                val stops = resolved.toList()
                val origin = (if (request.snap) stops.first().point else position()) ?: run { reply("Waiting for your real location. Pick a start on the map first.", spoken); return@launch }
                if (request.next && MockState.status.value.running && LiveSession.plan != null && !request.snap) {
                    stops.forEach { LiveSession.plan?.append(it) }
                    reply("Added ${stops.joinToString { it.name }} after the existing stops.", spoken)
                } else {
                    val planOrigin = if (request.next && MockState.status.value.running) PlaybackSource.endPoint ?: origin else origin
                    val cfg = api
                    if (request.snap) {
                        val hold = holdPlan(stops.first().point, stops.first().name, cfg)
                        PlaybackSource.current = hold.fixes(); PlaybackSource.routePoints = listOf(stops.first().point)
                        PlaybackSource.endPoint = stops.first().point; PlaybackSource.label = stops.first().name
                        PlaybackSource.clearQueue(); PlaybackSource.paused = false
                        ContextCompat.startForegroundService(ctx, Intent(ctx, MockLocationService::class.java))
                        reply("Starting a hold at ${stops.first().name}.", spoken)
                    } else {
                        val plan = LivePlan(if (stops.size == 1) stops.first().name else "Itinerary", planOrigin, stops, { from, to -> prepareLeg(cfg, from, to) })
                        if (request.next && MockState.status.value.running) {
                            PlaybackSource.enqueue(PlaybackSource.Queued(plan.fixes(), listOf(planOrigin), plan.title, stops.last().point))
                            reply("Queued ${plan.title} after arrival.", spoken)
                        } else {
                            PlaybackSource.clearQueue(); PlaybackSource.paused = false
                            PlaybackSource.current = plan.fixes(); PlaybackSource.routePoints = listOf(planOrigin)
                            PlaybackSource.endPoint = stops.last().point; PlaybackSource.label = plan.title
                            ContextCompat.startForegroundService(ctx, Intent(ctx, MockLocationService::class.java))
                            reply("Starting ${plan.title}. Finding the route now.", spoken)
                        }
                    }
                }
                pending = null; resolved.clear()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { reply("Could not apply that instruction: ${e.message}", spoken) }
            finally { if (serial == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun describeStatus(): String {
        val st = MockState.status.value
        if (!st.running) return "No simulation is running."
        val v = LiveSession.state.value
        val current = v.stops.getOrNull(v.index)?.stop?.name ?: st.label
        val next = v.stops.getOrNull(v.index + 1)?.stop?.name
        return (if (PlaybackSource.paused) "Paused at $current." else when(v.activity) {
            ActivityKind.ROUTING -> "Finding a route to $current."
            ActivityKind.TRAVELING -> "Traveling to $current."
            ActivityKind.STAYING -> "Staying at $current, about ${(v.remainingStaySeconds + 59) / 60} minutes left."
            else -> "Holding at $current."
        }) + (next?.let { " Next: $it." } ?: " No further stops.")
    }
}
