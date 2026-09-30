package com.mirage.spike.store

import com.mirage.spike.engine.RouteArchive
import com.mirage.spike.engine.RouteResult
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
    val address: String = "",
    val placeId: String = "",
    val routingRealism: Realism? = null,
    val routingTransitPref: String? = null,
    val ownRoutingPreferences: Boolean = false,
    val frozenRoute: RouteResult? = null,
    val arriveByMillis: Long? = null,
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
    val destAddress: String = "",
    val destPlaceId: String = "",
    val favorite: Boolean = false,
    val lastUsedAt: Long = 0L,
    val aliases: List<String> = emptyList(),
    val frozenRoute: RouteResult? = null,
    val departureMillis: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        departureMillis?.let {put("departureMillis",it)}
        frozenRoute?.let { put("frozenRoute", RouteArchive.encode(it)) }
        put("favorite", favorite); put("lastUsedAt", lastUsedAt); put("aliases", JSONArray(aliases))
        put("id", id); put("name", name); put("kind", kind); put("createdAt", createdAt)
        put("startIsReal", startIsReal)
        start?.let { put("startLat", it.lat); put("startLng", it.lng) }
        put("startName", startName)
        dest?.let { put("destLat", it.lat); put("destLng", it.lng) }
        put("destName", destName); put("destAddress", destAddress); put("destPlaceId", destPlaceId)
        put("travelMode", travelMode.name)
        put("speeds", JSONObject().apply { speeds.forEach { (m, v) -> put(m.name, v.toDouble()) } })
        put("realism", realism.name)
        transitPref?.let { put("transitPref", it) }
        put("stops", JSONArray().apply {
            stops.forEach { s ->
                put(JSONObject().apply {
                    put("name", s.name); put("lat", s.lat); put("lng", s.lng)
                    put("dwell", s.dwellMinutes); put("mode", s.mode.name); put("avgMph", s.avgMph.toDouble())
                    put("address", s.address); put("placeId", s.placeId)
                    s.routingRealism?.let { put("routingRealism", it.name) }
                    s.routingTransitPref?.let { put("routingTransitPref", it) }
                    put("ownRoutingPreferences", s.ownRoutingPreferences)
                    s.arriveByMillis?.let {put("arriveByMillis",it)}
                    s.frozenRoute?.let {put("frozenRoute",RouteArchive.encode(it))}
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
                        s.optString("address"), s.optString("placeId"),
                        runCatching { Realism.valueOf(s.optString("routingRealism")) }.getOrNull(),
                        s.optString("routingTransitPref").takeIf { it.isNotBlank() }, s.optBoolean("ownRoutingPreferences", false),
                        s.optJSONObject("frozenRoute")?.let { RouteArchive.decode(it) },
                        if(s.has("arriveByMillis"))s.getLong("arriveByMillis") else null,
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
                stops = stops, destAddress = o.optString("destAddress"), destPlaceId = o.optString("destPlaceId"),
                departureMillis=if(o.has("departureMillis"))o.getLong("departureMillis") else null,
                frozenRoute = o.optJSONObject("frozenRoute")?.let { RouteArchive.decode(it) },
                favorite = o.optBoolean("favorite"), lastUsedAt = o.optLong("lastUsedAt"),
                aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
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
    fun previous(): List<SavedScenario>? = null
}

class InMemoryScenarioStore : ScenarioStore {
    private var text: String? = null
    override fun load(): List<SavedScenario> = SavedScenario.listFromJson(text)
    override fun save(list: List<SavedScenario>) { text = SavedScenario.listToJson(list) }
}
