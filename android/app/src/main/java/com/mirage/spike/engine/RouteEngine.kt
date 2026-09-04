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
        // Transit: ask for the next departures from now, optionally filtered by vehicle family.
        val transitExtra = if (spec.mode == TravelMode.TRANSIT)
            "&departure_time=now" + (spec.transitPreference?.let { "&transit_mode=$it" } ?: "") else ""

        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${enc(origin)}&destination=${enc(dest)}$waypoints" +
            "&mode=${spec.mode.apiValue}$transitExtra&key=$apiKey"

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: throw RouteException("Empty Directions response")
            if (!resp.isSuccessful) throw RouteException("Directions HTTP ${resp.code}")
            parseDirections(body, transit = spec.mode == TravelMode.TRANSIT)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** Parse a Directions JSON body. For transit, also builds the walk/ride segments. */
        fun parseDirections(body: String, transit: Boolean): RouteResult {
            val json = runCatching { JSONObject(body) }.getOrElse { throw RouteException("Directions returned an unreadable response") }
            when (val status = json.optString("status")) {
                "OK" -> Unit
                "ZERO_RESULTS" -> throw RouteException(if (transit) "No transit connection found for this trip right now" else "No route found")
                else -> throw RouteException("Directions: $status ${json.optString("error_message")}")
            }
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) throw RouteException("No route found")
            val route = routes.getJSONObject(0)
            val encoded = route.getJSONObject("overview_polyline").getString("points")
            val points = PolylineCodec.decode(encoded)

            var distance = 0.0
            var duration = 0.0
            val segments = mutableListOf<RouteSegment>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                distance += leg.getJSONObject("distance").getDouble("value")
                duration += leg.getJSONObject("duration").getDouble("value")
                if (!transit) continue
                val steps = leg.optJSONArray("steps") ?: continue
                for (j in 0 until steps.length()) {
                    val st = steps.getJSONObject(j)
                    val pts = st.optJSONObject("polyline")?.optString("points")?.takeIf { it.isNotBlank() }
                        ?.let { PolylineCodec.decode(it) } ?: emptyList()
                    val dist = st.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
                    val dur = st.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
                    val td = st.optJSONObject("transit_details")
                    if (st.optString("travel_mode") == "TRANSIT" && td != null) {
                        val line = td.optJSONObject("line")
                        val lineName = line?.optString("short_name")?.takeIf { it.isNotBlank() }
                            ?: line?.optString("name")?.takeIf { it.isNotBlank() } ?: "Transit"
                        val vehicle = TransitVehicle.from(line?.optJSONObject("vehicle")?.optString("type"))
                        val dep = td.optJSONObject("departure_time"); val arr = td.optJSONObject("arrival_time")
                        segments += RouteSegment(
                            pts, dist, dur,
                            TransitDetails(
                                vehicle = vehicle, line = lineName, headsign = td.optString("headsign"),
                                fromStop = td.optJSONObject("departure_stop")?.optString("name") ?: "stop",
                                toStop = td.optJSONObject("arrival_stop")?.optString("name") ?: "stop",
                                departureEpoch = dep?.optLong("value", 0L) ?: 0L,
                                arrivalEpoch = arr?.optLong("value", 0L) ?: 0L,
                                departureText = dep?.optString("text") ?: "",
                                arrivalText = arr?.optString("text") ?: "",
                                numStops = td.optInt("num_stops", 1).coerceAtLeast(1),
                            ),
                        )
                    } else {
                        // Walking (merge consecutive walks into one).
                        val prev = segments.lastOrNull()
                        if (prev != null && prev.transit == null) {
                            segments[segments.size - 1] = prev.copy(
                                points = prev.points + pts, distanceMeters = prev.distanceMeters + dist,
                                durationSeconds = prev.durationSeconds + dur,
                            )
                        } else segments += RouteSegment(pts, dist, dur)
                    }
                }
            }
            return RouteResult(points, distance, duration, segments)
        }
    }
}
