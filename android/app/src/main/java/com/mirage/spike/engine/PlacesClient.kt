package com.mirage.spike.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PlaceHit(val latLng: LatLng, val name: String)

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

    suspend fun searchText(query: String, bias: LatLng?): PlaceHit? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext null

        val payload = JSONObject().put("textQuery", query)
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
            val places = JSONObject(body).optJSONArray("places")
            if (places == null || places.length() == 0) return@use null
            val first = places.getJSONObject(0)
            val loc = first.getJSONObject("location")
            val name = first.optJSONObject("displayName")?.optString("text").takeUnless { it.isNullOrBlank() } ?: query
            PlaceHit(LatLng(loc.getDouble("latitude"), loc.getDouble("longitude")), name)
        }
    }
}
