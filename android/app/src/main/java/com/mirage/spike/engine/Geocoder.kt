package com.mirage.spike.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Look up any place by name/address → coordinates (Google Geocoding API). */
class GoogleGeocoder(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun geocode(query: String): LatLng? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext null
        val url = "https://maps.googleapis.com/maps/api/geocode/json" +
            "?address=${URLEncoder.encode(query, "UTF-8")}&key=$apiKey"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            if (json.optString("status") != "OK") return@withContext null
            val results = json.getJSONArray("results")
            if (results.length() == 0) return@withContext null
            val loc = results.getJSONObject(0)
                .getJSONObject("geometry").getJSONObject("location")
            LatLng(loc.getDouble("lat"), loc.getDouble("lng"))
        }
    }
}
