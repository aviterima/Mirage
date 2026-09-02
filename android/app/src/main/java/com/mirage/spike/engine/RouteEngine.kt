package com.mirage.spike.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** A pluggable routing provider. Google Directions is the default (SPEC §7). */
interface RouteEngine {
    /** @throws RouteException on failure. */
    suspend fun route(spec: RouteSpec): RouteResult
}

class RouteException(message: String) : Exception(message)

/**
 * Google Directions API route engine. Returns real, directed, road-legal geometry
 * (one-way streets, turn restrictions, highway ramps are all baked into the polyline).
 */
class GoogleDirectionsRouteEngine(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : RouteEngine {

    override suspend fun route(spec: RouteSpec): RouteResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw RouteException("No Google Maps API key configured")

        val origin = "${spec.origin.lat},${spec.origin.lng}"
        val dest = "${spec.destination.lat},${spec.destination.lng}"
        val waypoints = if (spec.waypoints.isEmpty()) "" else
            "&waypoints=" + enc(spec.waypoints.joinToString("|") { "${it.lat},${it.lng}" })

        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${enc(origin)}&destination=${enc(dest)}$waypoints" +
            "&mode=${spec.mode.apiValue}&key=$apiKey"

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: throw RouteException("Empty Directions response")
            val json = JSONObject(body)
            when (val status = json.optString("status")) {
                "OK" -> Unit
                else -> throw RouteException("Directions: $status ${json.optString("error_message")}")
            }
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) throw RouteException("No route found")
            val route = routes.getJSONObject(0)
            val encoded = route.getJSONObject("overview_polyline").getString("points")
            val points = PolylineCodec.decode(encoded)

            var distance = 0.0
            var duration = 0.0
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                distance += leg.getJSONObject("distance").getDouble("value")
                duration += leg.getJSONObject("duration").getDouble("value")
            }
            RouteResult(points, distance, duration)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
