package com.mirage.spike

import com.mirage.spike.engine.*
import org.junit.Assert.*
import org.junit.Test

class VoiceNameMatcherTest {
    private val office = "Western Digital (Irvine)"

    @Test fun fullSpokenCityNameIgnoresOnlyParenthesesCaseAndWhitespace() {
        assertTrue(VoiceNameMatcher.matches(office, "western   digital Irvine"))
        assertTrue(VoiceNameMatcher.matches(office, " Western Digital ( Irvine ) "))
        assertFalse(VoiceNameMatcher.matches(office, "Western Digital"))
        assertFalse(VoiceNameMatcher.matches(office, "Western Digital San Jose"))
        assertFalse(VoiceNameMatcher.matches("A-B (Irvine)", "A B Irvine"))
        assertFalse(VoiceNameMatcher.matches("", ""))
    }

    @Test fun savedNameAndAliasMentionsRetainWordBoundariesAndRequireCityWords() {
        assertTrue(VoiceNameMatcher.mentioned(office, "Please go to Western Digital Irvine now"))
        assertTrue(VoiceNameMatcher.mentioned("My office (Irvine)", "add my office irvine"))
        assertTrue(VoiceNameMatcher.mentioned("Home", "Go to Home"))
        assertFalse(VoiceNameMatcher.mentioned(office, "Go to Western Digital"))
        assertFalse(VoiceNameMatcher.mentioned(office, "Go to Western Digital Irvinex"))
        assertFalse(VoiceNameMatcher.mentioned("Home", "Go to Homewood"))
    }

    @Test fun uniqueSavedBindingRestoresCanonicalNameAndPreservesRequestedControls() {
        val request = VoiceIntent("add_place", "Western Digital Irvine", Placement.END,
            minutes = 45, afterStopId = "fixture-anchor", travelMode = TravelMode.WALK)
        assertEquals(request.copy(action = "add_saved", target = office),
            VoiceNameMatcher.bind(request, listOf(office), emptyList()))
        val saved = VoiceIntent("add_saved", "western digital irvine")
        assertEquals(saved.copy(target = office), VoiceNameMatcher.bind(saved, listOf(office), emptyList()))
    }

    @Test fun ambiguousFormattedAndUnformattedSavedNamesCannotBind() {
        for (target in listOf("Western Digital Irvine", office)) {
            val result = runCatching { VoiceNameMatcher.bind(VoiceIntent("add_place", target),
                listOf(office, "Western Digital Irvine"), emptyList()) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
        val request = VoiceIntent("add_place", "Western Digital")
        assertEquals(request, VoiceNameMatcher.bind(request,
            listOf(office, "Western Digital (San Jose)"), emptyList()))
    }

    @Test fun upcomingEditsBindUniqueFullNamesAndRejectAmbiguousStops() {
        for (action in listOf("move", "remove", "stay")) {
            val request = VoiceIntent(action, "Western Digital Irvine", minutes = 15, position = 2)
            assertEquals(request.copy(target = office), VoiceNameMatcher.bind(request, emptyList(),
                listOf(office, "Western Digital (San Jose)")))
            assertTrue(runCatching { VoiceNameMatcher.bind(request, emptyList(),
                listOf(office, "Western Digital Irvine")) }.exceptionOrNull() is IllegalStateException)
        }
        val anchors = listOf(office, "Western Digital Irvine")
        assertNull(anchors.singleOrNull { VoiceNameMatcher.matches(it, "Western Digital Irvine") })
    }

    @Test fun customSaveNamesAndUnmatchedDestinationsRemainUnchanged() {
        val custom = VoiceIntent("save_new", "Western Digital Irvine")
        assertEquals(custom, VoiceNameMatcher.bind(custom, listOf(office), listOf(office)))
        val remote = VoiceIntent("add_place", "Different destination Irvine")
        assertEquals(remote, VoiceNameMatcher.bind(remote, listOf(office), emptyList()))
        val stayHere = VoiceIntent("stay", minutes = 15)
        assertEquals(stayHere, VoiceNameMatcher.bind(stayHere, listOf(office), listOf(office)))
    }

    @Test fun confirmedFollowUpResolvesFullCityAnchorButRejectsMissingCityOrAmbiguity() {
        fun stop(id: String, name: String) = LiveStop(id,
            ItineraryStop(name, LatLng(33.0, -112.0), 15, TravelMode.WALK, 3f))
        val entries = listOf(stop("current", "Current stop"), stop("target", office),
            stop("anchor", "Lunch (Dublin)"))
        val followUp = VoiceFollowUp().apply { remember("target", 7, 1_000) }
        assertEquals(VoiceIntent("move", office, position = 2),
            followUp.resolve("Actually put it after Lunch Dublin", entries, 0, 7, 2_000))
        assertNull(followUp.resolve("Put it after Lunch", entries, 0, 7, 2_000))
        assertNull(followUp.resolve("Put it after Lunch Dublin",
            entries + stop("duplicate", "Lunch Dublin"), 0, 7, 2_000))
    }
}
