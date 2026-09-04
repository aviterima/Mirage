package com.mirage.spike.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PlaceHit(val latLng: LatLng, val name: String, val address: String = "")

class PlacesException(message: String) : Exception(message)

/**
 * Places API (New) Text Search — finds businesses, POIs, and landmarks by name
 * (not just addresses), biased toward the user's current area.
 */
class GooglePlaces(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val jsonType = "application/json".toMediaType()

    /** Best single match (used when the user presses Enter without picking from the list). */
    suspend fun searchText(query: String, bias: LatLng?): PlaceHit? = searchMany(query, bias, 1).firstOrNull()

    /**
     * Up to [max] relevant matches for a name/address, biased toward [bias] — this is what
     * feeds the type-ahead list so "Pomo" shows every Pomo nearby, not just the closest.
     */
    suspend fun searchMany(query: String, bias: LatLng?, max: Int = 8): List<PlaceHit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext emptyList()

        val payload = JSONObject().put("textQuery", query).put("maxResultCount", max.coerceIn(1, 20))
        if (bias != null) {
            payload.put(
                "locationBias",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put("center", JSONObject().put("latitude", bias.lat).put("longitude", bias.lng))
                        .put("radius", 30000.0)
                )
            )
        }

        val req = Request.Builder()
            .url("https://places.googleapis.com/v1/places:searchText")
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", "places.location,places.displayName,places.formattedAddress")
            .post(payload.toString().toRequestBody(jsonType))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                throw PlacesException(msg ?: "Places error ${resp.code}")
            }
            val places = JSONObject(body).optJSONArray("places") ?: return@use emptyList()
            (0 until places.length()).mapNotNull { i ->
                val pl = places.getJSONObject(i)
                val loc = pl.optJSONObject("location") ?: return@mapNotNull null
                val lat = loc.optDouble("latitude", Double.NaN); val lng = loc.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
                val name = pl.optJSONObject("displayName")?.optString("text").takeUnless { it.isNullOrBlank() } ?: query
                PlaceHit(LatLng(lat, lng), name, pl.optString("formattedAddress"))
            }
        }
    }
}
