package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.store.SavedStop
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** A logical location remains in the proposal while an earlier location needs clarification. */
data class PlanCandidatePlace(val query: String, val hit: PlaceHit? = null, val saved: SavedScenario? = null) {
    val label: String get() = saved?.name ?: hit?.name ?: query
    val resolved: Boolean get() = hit != null
    internal fun toJson() = JSONObject().put("query", query).apply {
        hit?.let { put("hit", hitJson(it)) }; saved?.let { put("saved", it.toJson()) }
    }
    companion object {
        internal fun fromJson(o: JSONObject) = PlanCandidatePlace(o.getString("query"),
            o.optJSONObject("hit")?.let(::hitFromJson), o.optJSONObject("saved")?.let(SavedScenario::fromJson))
    }
}

data class PlanCandidateStop(
    val id: String,
    val place: PlanCandidatePlace,
    val stayMinutes: Int,
    val mode: TravelMode,
    val avgMph: Float,
    val template: SavedStop,
    val stayExplicit: Boolean = true,
    val clearHold: Boolean = false,
) {
    val label: String get() = place.label
    internal fun resolved(hit: PlaceHit, saved: SavedScenario?): PlanCandidateStop {
        val metadata = template.copy(name = saved?.name ?: hit.name, lat = hit.latLng.lat, lng = hit.latLng.lng,
            address = saved?.destAddress ?: hit.address, placeId = saved?.destPlaceId ?: hit.placeId,
            frozenRoute = null, arriveByMillis = null,
            arrivalActivity = saved?.arrivalActivity ?: ArrivalActivity.BUILDING,
            entrance = saved?.entrance, stayUntilLeave = !clearHold && (saved?.stayUntilLeave ?: false))
        return copy(place = place.copy(hit = hit, saved = saved), template = metadata,
            stayMinutes = if (stayExplicit) stayMinutes else saved?.defaultStayMinutes ?: stayMinutes)
    }
    internal fun savedStop(): SavedStop {
        val hit = requireNotNull(place.hit) { "Choose every location before applying the plan." }
        return template.copy(name = label, lat = hit.latLng.lat, lng = hit.latLng.lng,
            dwellMinutes = stayMinutes, mode = mode, avgMph = avgMph)
    }
    internal fun toJson() = JSONObject().put("id", id).put("place", place.toJson()).put("stay", stayMinutes)
        .put("mode", mode.name).put("speed", avgMph.toDouble()).put("explicitStay", stayExplicit)
        .put("clearHold", clearHold).put("template", stopEnvelope(template).toJson())
    companion object {
        internal fun fromJson(o: JSONObject) = PlanCandidateStop(o.getString("id"),
            PlanCandidatePlace.fromJson(o.getJSONObject("place")), o.getInt("stay"),
            TravelMode.valueOf(o.getString("mode")), o.getDouble("speed").toFloat(),
            SavedScenario.fromJson(o.getJSONObject("template")).stops.single(), o.getBoolean("explicitStay"), o.optBoolean("clearHold"))
    }
}

/** Immutable review state. No geocoding, playback, library writes or ViewModel mutations. */
data class PlanCandidate(
    val planMode: PlanMode,
    val origin: PlanCandidatePlace?,
    val destination: PlanCandidateStop?,
    val stops: List<PlanCandidateStop>,
    val departureMillis: Long?,
    val defaultMode: TravelMode,
    val speeds: Map<TravelMode, Float>,
    val defaultStayMinutes: Int,
    val originChanged: Boolean = false,
    val lastTarget: String? = null,
) {
    val preview: List<PlanCandidateStop> get() = if (planMode == PlanMode.ITINERARY) stops else listOfNotNull(destination)
    val resolved: Boolean get() = (origin?.resolved != false) && preview.all { it.place.resolved }
    val unresolved: List<Pair<String, PlanCandidatePlace>> get() = buildList {
        origin?.takeUnless { it.resolved }?.let { add("origin" to it) }
        preview.filterNot { it.place.resolved }.forEach { add(it.id to it.place) }
    }

    fun applyEdits(edits: List<PlanEdit>, zone: ZoneId = ZoneId.systemDefault()): PlanCandidate {
        require(edits.isNotEmpty() && edits.size <= NaturalLanguageInput.MAX_EDITS) { "Review between one and twelve changes." }
        require(edits.count { it.op == "add_stop" } <= 8) { "Add at most eight stops in one review." }
        var next = this
        edits.forEach { edit ->
            fun index(): Int {
                val i = edit.target.removePrefix("stop:").toIntOrNull()?.minus(1)
                require(edit.target.startsWith("stop:") && i != null && i in next.stops.indices) { "That stop is no longer in this plan. Specify the stop again." }
                return i
            }
            fun changeTarget(change: (PlanCandidateStop) -> PlanCandidateStop) {
                if (edit.target == "destination") {
                    if (next.planMode == PlanMode.ITINERARY) {
                        require(next.stops.isNotEmpty()) { "Add a destination before changing it." }
                        next = next.copy(stops = next.stops.dropLast(1) + change(next.stops.last()), lastTarget = next.stops.last().id)
                    } else next = next.copy(destination = change(requireNotNull(next.destination) { "Add a destination before changing it." }), lastTarget = next.destination?.id)
                } else {
                    val i = index(); next = next.copy(stops = next.stops.mapIndexed { n, stop -> if (n == i) change(stop) else stop }, lastTarget = next.stops[i].id)
                }
            }
            fun changedPlace(previous: PlanCandidateStop?, id: String = UUID.randomUUID().toString()): PlanCandidateStop {
                require(edit.place.isNotBlank() && edit.place.length <= 240 && edit.minutes in -1..1440) { "Specify a location and a valid stay." }
                val m = previous?.mode ?: next.defaultMode
                val speed = previous?.avgMph ?: next.speeds[m] ?: defaultSpeed(m)
                val stay = if (edit.minutes >= 0) edit.minutes else previous?.stayMinutes ?: next.defaultStayMinutes
                val template = previous?.template ?: SavedStop(edit.place, 0.0, 0.0, stay, m, speed)
                return PlanCandidateStop(previous?.id ?: id, PlanCandidatePlace(edit.place), stay, m, speed,
                    template.copy(address = "", placeId = "", entrance = null, frozenRoute = null),
                    stayExplicit = edit.minutes >= 0 || previous != null, clearHold = edit.minutes >= 0)
            }
            when (edit.op) {
                "set_start" -> {
                    require(edit.place.isNotBlank() && edit.place.length <= 240)
                    next = next.copy(origin = PlanCandidatePlace(edit.place), originChanged = true, lastTarget = "origin")
                }
                "set_destination" -> if (next.planMode == PlanMode.ITINERARY) {
                    val stop = changedPlace(next.stops.lastOrNull())
                    next = next.copy(stops = next.stops.dropLast(if (next.stops.isEmpty()) 0 else 1) + stop, lastTarget = stop.id)
                } else {
                    val stop = changedPlace(next.destination, "destination")
                    next = next.copy(destination = stop, lastTarget = stop.id)
                }
                "add_stop" -> {
                    // A route already on screen becomes the first leg of the itinerary.
                    if (next.planMode != PlanMode.ITINERARY) next = next.copy(planMode = PlanMode.ITINERARY,
                        stops = listOfNotNull(next.destination), destination = null)
                    val at = if (edit.target in setOf("", "end")) next.stops.size else index() + 1
                    val stop = changedPlace(null)
                    next = next.copy(stops = next.stops.toMutableList().apply { add(at, stop) }, lastTarget = stop.id)
                }
                "replace_stop" -> { val i = index(); next = next.copy(stops = next.stops.mapIndexed { n, stop -> if (n == i) changedPlace(stop) else stop }, lastTarget = next.stops[i].id) }
                "remove_stop" -> { val i = index(); next = next.copy(stops = next.stops.filterIndexed { n, _ -> n != i }, lastTarget = null) }
                "move_stop" -> {
                    val from = index(); val to = edit.value.toIntOrNull()?.minus(1)
                    require(to != null && to in next.stops.indices) { "Choose a position within this itinerary." }
                    next = next.copy(stops = next.stops.toMutableList().apply { add(to, removeAt(from)) }, lastTarget = next.stops[from].id)
                }
                "set_stay" -> {
                    require(edit.minutes in 0..1440) { "A stay must be between 0 and 1440 minutes." }
                    changeTarget { it.copy(stayMinutes = edit.minutes, stayExplicit = true, clearHold = true,
                        template = it.template.copy(stayUntilLeave = false)) }
                }
                "set_mode" -> {
                    val mode = TravelMode.valueOf(edit.value)
                    changeTarget { it.copy(mode = mode, avgMph = next.speeds[mode] ?: defaultSpeed(mode), template = it.template.copy(frozenRoute = null)) }
                }
                "set_departure" -> {
                    val local = LocalDateTime.parse(edit.value)
                    val offsets = zone.rules.getValidOffsets(local)
                    require(offsets.size == 1) { "That local time is skipped or repeated by a clock change. Choose an unambiguous departure time." }
                    next = next.copy(departureMillis = local.toInstant(offsets.single()).toEpochMilli())
                }
                "clear_departure" -> next = next.copy(departureMillis = null)
                else -> error("Unsupported planning change.")
            }
            require(next.stops.size <= 100) { "An itinerary can contain at most 100 stops." }
        }
        return next
    }

    internal fun resolve(slot: String, hit: PlaceHit, saved: SavedScenario?): PlanCandidate {
        require(hit.latLng.lat.isFinite() && hit.latLng.lng.isFinite() && hit.latLng.lat in -90.0..90.0 && hit.latLng.lng in -180.0..180.0)
        if (slot == "origin") return copy(origin = requireNotNull(origin).copy(hit = hit, saved = saved))
        if (destination?.id == slot) return copy(destination = destination.resolved(hit, saved))
        require(stops.any { it.id == slot }) { "The proposed stop was removed." }
        return copy(stops = stops.map { if (it.id == slot) it.resolved(hit, saved) else it })
    }

    fun toSnapshot(base: PlannerSnapshot): PlannerSnapshot {
        require(resolved) { "Choose every location before applying the plan." }
        val final = if (planMode == PlanMode.ITINERARY) stops.lastOrNull() else destination
        val end = final?.savedStop()
        val old = base.scenario
        val sc = old.copy(kind = planMode.name, start = origin?.hit?.latLng ?: old.start,
            startName = origin?.label ?: old.startName, startIsReal = if (originChanged) false else old.startIsReal,
            dest = if (planMode == PlanMode.ITINERARY) null else end?.let { LatLng(it.lat, it.lng) },
            destName = if (planMode == PlanMode.ITINERARY) "" else end?.name ?: old.destName,
            destAddress = if (planMode == PlanMode.ITINERARY) "" else end?.address ?: old.destAddress,
            destPlaceId = if (planMode == PlanMode.ITINERARY) "" else end?.placeId ?: old.destPlaceId,
            stops = if (planMode == PlanMode.ITINERARY) stops.map { it.savedStop() } else old.stops,
            departureMillis = departureMillis, frozenRoute = null,
            travelMode = if (planMode == PlanMode.ITINERARY) old.travelMode else final?.mode ?: old.travelMode,
            defaultStayMinutes = if (planMode == PlanMode.ITINERARY) old.defaultStayMinutes else final?.stayMinutes ?: old.defaultStayMinutes,
            arrivalActivity = if (planMode == PlanMode.ITINERARY) old.arrivalActivity else end?.arrivalActivity ?: old.arrivalActivity,
            entrance = if (planMode == PlanMode.ITINERARY || end == null) old.entrance else end.entrance,
            stayUntilLeave = if (planMode == PlanMode.ITINERARY) old.stayUntilLeave else end?.stayUntilLeave ?: old.stayUntilLeave)
        return base.copy(scenario = sc,
            startFromReal = if (originChanged) false else base.startFromReal,
            useSimulatedStart = if (originChanged) false else base.useSimulatedStart,
            queueAfterCurrent = if (originChanged) false else base.queueAfterCurrent,
            startAddress = if (originChanged) origin?.saved?.destAddress ?: origin?.hit?.address.orEmpty() else base.startAddress,
            startPlaceId = if (originChanged) origin?.saved?.destPlaceId ?: origin?.hit?.placeId.orEmpty() else base.startPlaceId,
            preparedRoute = null, flightOrigin = null, isFlight = false, routePoints = emptyList(), routeDistanceM = 0.0, replayExactRoute = false)
    }

    fun toJson(): JSONObject = JSONObject().put("mode", planMode.name).put("originChanged", originChanged)
        .put("defaultMode", defaultMode.name).put("defaultStay", defaultStayMinutes).put("lastTarget", lastTarget)
        .put("speeds", JSONObject().apply { speeds.forEach { (m, s) -> put(m.name, s.toDouble()) } })
        .put("stops", JSONArray().apply { stops.forEach { put(it.toJson()) } }).apply {
            origin?.let { put("origin", it.toJson()) }; destination?.let { put("destination", it.toJson()) }
            departureMillis?.let { put("departure", it) }
        }

    companion object {
        fun fromSnapshot(snapshot: PlannerSnapshot): PlanCandidate {
            val s = snapshot.scenario
            fun stop(value: SavedStop, id: String) = PlanCandidateStop(id,
                PlanCandidatePlace(value.name, PlaceHit(LatLng(value.lat,value.lng),value.name,value.address,value.placeId)),
                value.dwellMinutes,value.mode,value.avgMph,value)
            val dest = s.dest?.let { point -> stop(SavedStop(s.destName,point.lat,point.lng,s.defaultStayMinutes,
                s.travelMode,s.speeds[s.travelMode] ?: defaultSpeed(s.travelMode),s.destAddress,s.destPlaceId,
                frozenRoute = s.frozenRoute,arrivalActivity = s.arrivalActivity,entrance = s.entrance,stayUntilLeave = s.stayUntilLeave), "destination") }
            return PlanCandidate(PlanMode.valueOf(s.kind), s.start?.let { PlanCandidatePlace(s.startName,
                PlaceHit(it,s.startName,snapshot.startAddress,snapshot.startPlaceId)) }, dest,
                s.stops.mapIndexed { i, value -> stop(value,"existing:$i") }, s.departureMillis, s.travelMode, s.speeds, s.defaultStayMinutes)
        }
        fun fromJson(o: JSONObject): PlanCandidate {
            val array = o.getJSONArray("stops"); require(array.length() <= 100)
            val speed = o.getJSONObject("speeds")
            return PlanCandidate(PlanMode.valueOf(o.getString("mode")), o.optJSONObject("origin")?.let(PlanCandidatePlace::fromJson),
                o.optJSONObject("destination")?.let(PlanCandidateStop::fromJson),
                (0 until array.length()).map { PlanCandidateStop.fromJson(array.getJSONObject(it)) },
                if(o.has("departure")) o.getLong("departure") else null, TravelMode.valueOf(o.getString("defaultMode")),
                speed.keys().asSequence().associate { TravelMode.valueOf(it) to speed.getDouble(it).toFloat() },
                o.getInt("defaultStay"),o.getBoolean("originChanged"),o.optString("lastTarget").takeIf { it.isNotBlank() })
        }
    }
}

internal fun hitJson(hit: PlaceHit) = JSONObject().put("lat",hit.latLng.lat).put("lng",hit.latLng.lng)
    .put("name",hit.name).put("address",hit.address).put("id",hit.placeId)
internal fun hitFromJson(o: JSONObject) = PlaceHit(LatLng(o.getDouble("lat"),o.getDouble("lng")),o.getString("name"),o.getString("address"),o.getString("id"))
private fun stopEnvelope(stop: SavedStop) = SavedScenario("","","ITINERARY",0L,false,null,"",null,"",
    stop.mode,emptyMap(),Realism.REALISTIC,null,listOf(stop))
