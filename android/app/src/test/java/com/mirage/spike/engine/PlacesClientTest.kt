package com.mirage.spike.engine

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlacesClientTest {
    private lateinit var server: MockWebServer
    private lateinit var places: GooglePlaces

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        places = GooglePlaces(ApiConfig(server.url("/").toString(), "", "fixture-install"))
    }

    @After fun teardown() { server.shutdown() }

    @Test fun searchRequestsStructuredCityAndPreservesAddressAndIdentity() = runBlocking {
        val address = "123 Example Drive, Irvine, CA 92618, USA"
        enqueue(place("Western Digital", components(
            component("Orange County", "administrative_area_level_2"),
            component("Postal Town", "postal_town"),
            component("Irvine", "political", "locality"),
        ), address))

        val result = places.searchMany("Western Digital", LatLng(33.6, -117.7)).single()

        assertEquals("Western Digital (Irvine)", result.name)
        assertEquals(address, result.address)
        assertEquals("fixture-place", result.placeId)
        assertEquals(LatLng(33.6, -117.7), result.latLng)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/places/searchText", request.path)
        assertTrue(request.getHeader("X-Goog-FieldMask")!!.split(',').contains("places.addressComponents"))
        assertEquals("Western Digital", JSONObject(request.body.readUtf8()).getString("textQuery"))
    }

    @Test fun enterSearchUsesPostalTownWhenLocalityIsUnavailable() = runBlocking {
        enqueue(place("Museum", components(
            component("Westminster", "sublocality_level_1"),
            component(" London ", "postal_town"),
        )))

        assertEquals("Museum (London)", places.searchText("museum", null)!!.name)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(1, JSONObject(request.body.readUtf8()).getInt("maxResultCount"))
    }

    @Test fun absentCityDoesNotInventOneFromAddressCountyOrNeighborhood() = runBlocking {
        enqueue(
            place("Remote trail", components(component("Orange County", "administrative_area_level_2"))),
            place("Shop", components(component("Woodbridge", "neighborhood"))),
            place("Landmark", null),
        )

        assertEquals(listOf("Remote trail", "Shop", "Landmark"), places.searchMany("places", null).map { it.name })
    }

    @Test fun blankOrMalformedCityComponentsDoNotProduceEmptyOrNullSuffixes() = runBlocking {
        val invalid = components(
            component(" ", "locality"),
            JSONObject().put("longText", JSONObject.NULL).put("types", JSONArray().put("locality")),
            JSONObject().put("longText", 123).put("types", JSONArray().put("locality")),
            JSONObject().put("longText", "Unclassified"),
        ).put(JSONObject.NULL).put("invalid component")
        enqueue(
            place("Museum", JSONArray(invalid.toString()).put(component("London", "postal_town"))),
            place("Remote trail", invalid),
        )

        assertEquals(listOf("Museum (London)", "Remote trail"), places.searchMany("places", null).map { it.name })
    }

    @Test fun anExistingCitySuffixOrCityNameIsNotDuplicated() = runBlocking {
        val irvine = components(component("Irvine", "locality"))
        enqueue(
            place(" Western Digital (Irvine) ", irvine),
            place("Western Digital (IRVINE)", irvine),
            place("Irvine", irvine),
            place("Western Digital (Headquarters)", irvine),
        )

        assertEquals(
            listOf("Western Digital (Irvine)", "Western Digital (IRVINE)", "Irvine", "Western Digital (Headquarters) (Irvine)"),
            places.searchMany("places", null).map { it.name },
        )
    }

    @Test fun missingDisplayNameUsesQueryAndStillAddsKnownCity() = runBlocking {
        val irvine = components(component("Irvine", "locality"))
        val missingName = place("", irvine).apply { remove("displayName") }
        val nullName = place("", irvine).put("displayName", JSONObject().put("text", JSONObject.NULL))
        enqueue(missingName, nullName, place(" ", irvine))

        assertEquals(List(3) { "Western Digital (Irvine)" }, places.searchMany(" Western Digital ", null).map { it.name })
    }

    private fun enqueue(vararg results: JSONObject) {
        val array = JSONArray()
        results.forEach { array.put(it) }
        server.enqueue(MockResponse().setBody(JSONObject().put("places", array).toString()))
    }

    private fun place(name: String, components: JSONArray?, address: String = "123 Example Drive, Irvine, CA 92618, USA") =
        JSONObject()
            .put("id", "fixture-place")
            .put("location", JSONObject().put("latitude", 33.6).put("longitude", -117.7))
            .put("displayName", JSONObject().put("text", name))
            .put("formattedAddress", address)
            .apply { if (components != null) put("addressComponents", components) }

    private fun component(text: String, vararg types: String) =
        JSONObject().put("longText", text).put("types", JSONArray(types.toList()))

    private fun components(vararg items: JSONObject) = JSONArray(items.toList())
}
