package com.mirage.spike.store

import org.json.JSONArray
import org.json.JSONObject

/** Credential-free, versioned interchange. Validate the complete input before writing anything. */
object BackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    fun encode(items: List<SavedScenario>): String = JSONObject().put("format", "mirage-backup")
        .put("version", 1).put("items", JSONArray(items.map { it.toJson() })).toString(2)
    fun decode(text: String): List<SavedScenario> {
        require(text.toByteArray().size <= MAX_BYTES) { "Backup exceeds 2 MB" }
        val root = JSONObject(text)
        require(root.optString("format") == "mirage-backup" && root.optInt("version") == 1) { "Unsupported Mirage backup" }
        val items = root.getJSONArray("items")
        require(items.length() <= 1000) { "Import at most 1,000 items at a time" }
        return (0 until items.length()).map { index ->
            val raw=items.getJSONObject(index)
            val modes=com.mirage.spike.engine.TravelMode.values().map{it.name}.toSet()
            val realism=com.mirage.spike.engine.Realism.values().map{it.name}.toSet()
            require(raw.getString("travelMode") in modes && raw.getString("realism") in realism) { "Invalid travel settings" }
            raw.optJSONObject("speeds")?.let { speeds -> speeds.keys().forEach { key ->
                val speed=speeds.getDouble(key)
                require(key in modes && speed.isFinite() && speed in 1.0..1000.0) { "Invalid speed" }
            } }
            raw.optJSONArray("stops")?.let { stops -> (0 until stops.length()).forEach { i ->
                val stop=stops.getJSONObject(i)
                require(stop.getString("mode") in modes) { "Invalid stop mode" }
                if(stop.has("routingRealism")) require(stop.getString("routingRealism") in realism) { "Invalid routing settings" }
            } }
            val item = SavedScenario.fromJson(raw)
            require(item.name.isNotBlank() && item.name.length <= 160 && item.kind in setOf("SNAP", "ROUTE", "ITINERARY")) { "Invalid item ${index + 1}" }
            require(item.stops.size <= 100 && item.aliases.size <= 12 && item.aliases.all { it.length <= 80 }) { "Too many stops or aliases" }
            val points = listOfNotNull(item.start, item.dest) + item.stops.map { com.mirage.spike.engine.LatLng(it.lat, it.lng) }
            require(points.all { it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }) { "Invalid location" }
            require(item.stops.all { it.dwellMinutes in 0..1440 && it.avgMph.isFinite() && it.avgMph in 1f..1000f }) { "Invalid stay or speed" }
            require(if(item.kind == "ITINERARY") item.stops.isNotEmpty() else item.dest != null) { "Missing destination" }
            item
        }
    }
}
