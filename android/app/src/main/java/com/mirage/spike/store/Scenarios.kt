package com.mirage.spike.store

import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.Realism
import com.mirage.spike.engine.TravelMode
import org.json.JSONArray
import org.json.JSONObject

/** One stop of a saved itinerary. */
data class SavedStop(
    val name: String,
    val lat: Double,
    val lng: Double,
    val dwellMinutes: Int,
    val mode: TravelMode,
    val avgMph: Float,
)

/**
 * A named, reusable plan: a Snap, a Route or an Itinerary, with everything needed to
 * put it back on screen. A start that was "my real location" is stored as a flag rather
 * than coordinates, so reloading it later uses wherever the phone really is then.
 */
data class SavedScenario(
    val id: String,
    val name: String,
    val kind: String,                  // SNAP / ROUTE / ITINERARY (PlanMode name)
    val createdAt: Long,
    val startIsReal: Boolean,
    val start: LatLng?,
    val startName: String,
    val dest: LatLng?,
    val destName: String,
    val travelMode: TravelMode,
    val speeds: Map<TravelMode, Float>,
    val realism: Realism,
    val transitPref: String?,
    val stops: List<SavedStop>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind); put("createdAt", createdAt)
        put("startIsReal", startIsReal)
        start?.let { put("startLat", it.lat); put("startLng", it.lng) }
        put("startName", startName)
        dest?.let { put("destLat", it.lat); put("destLng", it.lng) }
        put("destName", destName)
        put("travelMode", travelMode.name)
        put("speeds", JSONObject().apply { speeds.forEach { (m, v) -> put(m.name, v.toDouble()) } })
        put("realism", realism.name)
        transitPref?.let { put("transitPref", it) }
        put("stops", JSONArray().apply {
            stops.forEach { s ->
                put(JSONObject().apply {
                    put("name", s.name); put("lat", s.lat); put("lng", s.lng)
                    put("dwell", s.dwellMinutes); put("mode", s.mode.name); put("avgMph", s.avgMph.toDouble())
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): SavedScenario {
            fun mode(s: String?) = runCatching { TravelMode.valueOf(s ?: "") }.getOrDefault(TravelMode.DRIVE)
            val speeds = mutableMapOf<TravelMode, Float>()
            o.optJSONObject("speeds")?.let { sp -> sp.keys().forEach { k -> speeds[mode(k)] = sp.optDouble(k, 45.0).toFloat() } }
            val stops = mutableListOf<SavedStop>()
            o.optJSONArray("stops")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    stops += SavedStop(
                        s.optString("name"), s.getDouble("lat"), s.getDouble("lng"),
                        s.optInt("dwell", 30), mode(s.optString("mode")), s.optDouble("avgMph", 45.0).toFloat(),
                    )
                }
            }
            return SavedScenario(
                id = o.optString("id"), name = o.optString("name"), kind = o.optString("kind", "ROUTE"),
                createdAt = o.optLong("createdAt", 0L),
                startIsReal = o.optBoolean("startIsReal", false),
                start = if (o.has("startLat")) LatLng(o.getDouble("startLat"), o.getDouble("startLng")) else null,
                startName = o.optString("startName"),
                dest = if (o.has("destLat")) LatLng(o.getDouble("destLat"), o.getDouble("destLng")) else null,
                destName = o.optString("destName"),
                travelMode = mode(o.optString("travelMode")),
                speeds = speeds,
                realism = runCatching { Realism.valueOf(o.optString("realism")) }.getOrDefault(Realism.REALISTIC),
                transitPref = o.optString("transitPref").takeIf { it.isNotBlank() },
                stops = stops,
            )
        }

        fun listToJson(list: List<SavedScenario>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<SavedScenario> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i -> runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull() }
            }.getOrDefault(emptyList())
        }
    }
}

/** Where saved plans live. The app uses SharedPreferences; tests use memory. */
interface ScenarioStore {
    fun load(): List<SavedScenario>
    fun save(list: List<SavedScenario>)
}

class InMemoryScenarioStore : ScenarioStore {
    private var text: String? = null
    override fun load(): List<SavedScenario> = SavedScenario.listFromJson(text)
    override fun save(list: List<SavedScenario>) { text = SavedScenario.listToJson(list) }
}
