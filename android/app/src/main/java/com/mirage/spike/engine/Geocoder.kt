package com.mirage.spike.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Look up any place by name/address → coordinates (Google Geocoding API). */
class GoogleGeocoder(
    private val cfg: ApiConfig,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun geocode(query: String): LatLng? = withContext(Dispatchers.IO) {
        if (!cfg.enabled || query.isBlank()) return@withContext null
        val url = cfg.geocodeUrl("address=${URLEncoder.encode(query, "UTF-8")}")
        val req = Request.Builder().url(url).apply { cfg.headers().forEach { (k, v) -> addHeader(k, v) } }.build()
        client.newCall(req).execute().use { resp ->
            CreditsState.report(resp.header(ApiConfig.CREDITS_HEADER))
            val body = resp.body?.string() ?: return@withContext null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
            if (json.optString("status") != "OK") return@withContext null
            val results = json.getJSONArray("results")
            if (results.length() == 0) return@withContext null
            val loc = results.getJSONObject(0)
                .getJSONObject("geometry").getJSONObject("location")
            LatLng(loc.getDouble("lat"), loc.getDouble("lng"))
        }
    }
}
