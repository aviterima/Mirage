package com.mirage.spike.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the billable Google web APIs (Directions, Places, Geocoding) are reached.
 *
 * DIRECT: the app calls Google itself with a key (built in, or pasted by the user in Setup).
 * HOSTED: the app calls Mirage's own gateway, which holds the Google key server-side, meters
 *         each request against the install's credits and returns the same Google JSON.
 *
 * The Maps SDK for Android (the map tiles) always uses the build-time key in the manifest;
 * that key is app-restricted and free of charge, so it is safe to ship.
 */
class ApiConfig(
    hostedBase: String?,
    val directKey: String,
    val installId: String,
) {
    enum class Mode { DIRECT, HOSTED }

    val hostedBase: String? = hostedBase?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    val mode: Mode get() = if (hostedBase != null) Mode.HOSTED else Mode.DIRECT
    /** Can routing/search work at all? */
    val enabled: Boolean get() = mode == Mode.HOSTED || directKey.isNotBlank()

    fun directionsUrl(query: String): String =
        if (mode == Mode.HOSTED) "$hostedBase/v1/directions?$query"
        else "https://maps.googleapis.com/maps/api/directions/json?$query&key=$directKey"

    fun geocodeUrl(query: String): String =
        if (mode == Mode.HOSTED) "$hostedBase/v1/geocode?$query"
        else "https://maps.googleapis.com/maps/api/geocode/json?$query&key=$directKey"

    fun placesSearchUrl(): String =
        if (mode == Mode.HOSTED) "$hostedBase/v1/places/searchText"
        else "https://places.googleapis.com/v1/places:searchText"

    /** Headers every request carries; the direct Places call adds its key here. */
    fun headers(places: Boolean = false): Map<String, String> =
        if (mode == Mode.HOSTED) mapOf("X-Mirage-Install" to installId)
        else if (places) mapOf("X-Goog-Api-Key" to directKey) else emptyMap()

    companion object {
        /** The gateway reports the install's remaining credits on every response. */
        const val CREDITS_HEADER = "X-Mirage-Credits"
        val NONE = ApiConfig(null, "", "")
    }
}

/** Remaining hosted credits (-1 = unknown / not hosted), for the Setup screen. */
object CreditsState {
    private val _credits = MutableStateFlow(-1)
    val credits: StateFlow<Int> = _credits.asStateFlow()
    fun report(headerValue: String?) { headerValue?.toIntOrNull()?.let { _credits.value = it } }
}

data class ApiCheck(val api: String, val ok: Boolean, val detail: String)
