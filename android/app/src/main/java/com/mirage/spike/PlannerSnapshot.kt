package com.mirage.spike

import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.RouteArchive
import com.mirage.spike.engine.RouteResult
import com.mirage.spike.engine.Signal
import com.mirage.spike.store.SavedScenario
import org.json.JSONArray
import org.json.JSONObject

/** Complete editing state, including an empty plan. No credentials or playback handles. */
data class PlannerSnapshot(
    val scenario: SavedScenario,
    val draftGeneration: Long,
    val loadedScenario: SavedScenario? = null,
    val startFromReal: Boolean = false,
    val useSimulatedStart: Boolean = false,
    val queueAfterCurrent: Boolean = false,
    val startAddress: String = "",
    val startPlaceId: String = "",
    val activeField: Field = Field.END,
    val timeScale: Float = 1f,
    val speedOverLimit: Float = 5f,
    val signal: Signal = Signal.GOOD,
    val preparedRoute: RouteResult? = null,
    val flightOrigin: LatLng? = null,
    val isFlight: Boolean = false,
    val routePoints: List<LatLng> = emptyList(),
    val routeDistanceM: Double = 0.0,
    val replayExactRoute: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1); put("scenario", scenario.toJson()); put("generation", draftGeneration)
        loadedScenario?.let { put("loaded", it.toJson()) }
        put("startFromReal", startFromReal); put("useSimulatedStart", useSimulatedStart)
        put("queueAfterCurrent", queueAfterCurrent); put("startAddress", startAddress); put("startPlaceId", startPlaceId)
        put("field", activeField.name); put("timeScale", timeScale.toDouble()); put("speedOverLimit", speedOverLimit.toDouble())
        put("signal", JSONObject().put("name", signal.name).put("accuracy", signal.accuracyM.toDouble())
            .put("jitter", signal.jitterM).put("dropUntil", signal.dropUntilMillis))
        preparedRoute?.let { put("preparedRoute", RouteArchive.encode(it)) }
        flightOrigin?.let { put("flightOrigin", JSONArray().put(it.lat).put(it.lng)) }
        put("isFlight", isFlight); put("routeDistance", routeDistanceM); put("exact", replayExactRoute)
        put("points", JSONArray().apply { routePoints.forEach { put(JSONArray().put(it.lat).put(it.lng)) } })
    }

    companion object {
        fun fromJson(json: JSONObject): PlannerSnapshot {
            require(json.getInt("version") == 1) { "Unsupported planner recovery version" }
            val sig = json.getJSONObject("signal")
            val points = json.optJSONArray("points") ?: JSONArray()
            return PlannerSnapshot(
                scenario = SavedScenario.fromJson(json.getJSONObject("scenario")),
                draftGeneration = json.getLong("generation"),
                loadedScenario = json.optJSONObject("loaded")?.let { SavedScenario.fromJson(it) },
                startFromReal = json.optBoolean("startFromReal"), useSimulatedStart = json.optBoolean("useSimulatedStart"),
                queueAfterCurrent = json.optBoolean("queueAfterCurrent"),
                startAddress = json.optString("startAddress"), startPlaceId = json.optString("startPlaceId"),
                activeField = Field.valueOf(json.getString("field")),
                timeScale = json.getDouble("timeScale").toFloat(), speedOverLimit = json.getDouble("speedOverLimit").toFloat(),
                signal = Signal(sig.getString("name"), sig.getDouble("accuracy").toFloat(), sig.getDouble("jitter"), sig.optLong("dropUntil")),
                preparedRoute = json.optJSONObject("preparedRoute")?.let(RouteArchive::decode),
                flightOrigin = json.optJSONArray("flightOrigin")?.let { LatLng(it.getDouble(0), it.getDouble(1)) },
                isFlight = json.optBoolean("isFlight"), routeDistanceM = json.optDouble("routeDistance"),
                replayExactRoute = json.optBoolean("exact"),
                routePoints = (0 until points.length()).map { points.getJSONArray(it).let { p -> LatLng(p.getDouble(0), p.getDouble(1)) } },
            )
        }
    }
}
