package com.mirage.spike.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Exercises every API the app needs with tiny requests and reports, per API, whether it
 * works and Google's own reason when it does not — so a user never has to guess which box
 * in the Cloud console they missed.
 */
class KeyTester(private val cfg: ApiConfig, private val client: OkHttpClient = OkHttpClient()) {

    suspend fun test(): List<ApiCheck> = withContext(Dispatchers.IO) {
        val here = LatLng(37.4221, -122.0841)
        val there = LatLng(37.4111, -122.0708)
        listOf(
            check("Geocoding") { GoogleGeocoder(cfg, client).geocode("1600 Amphitheatre Parkway, Mountain View") != null },
            check("Directions") { GoogleDirectionsRouteEngine(cfg, client).route(RouteSpec(here, there)).points.size >= 2 },
            check("Places (New)") { GooglePlaces(cfg, client).searchMany("coffee", here, 1).isNotEmpty() },
        )
    }

    private suspend fun check(name: String, call: suspend () -> Boolean): ApiCheck =
        try {
            if (call()) ApiCheck(name, true, "OK") else ApiCheck(name, false, "No result returned")
        } catch (e: Exception) {
            ApiCheck(name, false, e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "failed"))
        }
}
