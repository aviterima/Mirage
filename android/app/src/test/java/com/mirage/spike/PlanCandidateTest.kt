package com.mirage.spike

import com.mirage.spike.engine.*
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.store.SavedStop
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class PlanCandidateTest {
    private fun base(stops: List<SavedStop> = emptyList(), kind: String = "ITINERARY") = PlannerSnapshot(
        SavedScenario("draft","Draft",kind,0,false,LatLng(33.0,-112.0),"Origin",LatLng(34.0,-112.0),"Existing destination",
            TravelMode.DRIVE,mapOf(TravelMode.DRIVE to 42f,TravelMode.WALK to 3f),Realism.REALISTIC,null,stops,
            defaultStayMinutes = 10),0,startAddress = "Origin address",startPlaceId = "origin-id")
    private fun stop(name: String) = SavedStop(name,34.0,-112.0,25,TravelMode.WALK,3f,
        address = "Stored address",placeId = "stored-id",arriveByMillis = 1900000000000,
        arrivalActivity = ArrivalActivity.TABLE,entrance = LatLng(34.0001,-112.0),stayUntilLeave = true)

    @Test fun allLogicalEditsExistBeforeFirstLocationIsResolved() {
        val before = base(listOf(stop("Existing")))
        val candidate = PlanCandidate.fromSnapshot(before).applyEdits(listOf(
            PlanEdit("add_stop",place="Cafe",minutes=45),PlanEdit("add_stop",place="Home"),
            PlanEdit("set_stay",target="stop:3",minutes=90),PlanEdit("set_departure",value="2027-01-05T17:00")),ZoneId.of("America/Phoenix"))
        assertEquals(listOf("Existing","Cafe","Home"),candidate.preview.map { it.label })
        assertEquals(listOf("Cafe","Home"),candidate.unresolved.map { it.second.query })
        assertEquals(90,candidate.preview.last().stayMinutes)
        assertEquals(before.scenario.stops.single(),candidate.preview.first().savedStop())
        assertEquals(candidate,PlanCandidate.fromJson(candidate.toJson()))
        assertNotNull(candidate.departureMillis)
        assertThrows(IllegalArgumentException::class.java) { candidate.toSnapshot(before) }
    }

    @Test fun sequentialStopReferencesAndCorrectionPreserveOtherPendingEntries() {
        val candidate = PlanCandidate.fromSnapshot(base(listOf(stop("A"),stop("B")))).applyEdits(listOf(
            PlanEdit("add_stop",place="C"),PlanEdit("move_stop",target="stop:3",value="1"),
            PlanEdit("set_stay",target="stop:1",minutes=65),PlanEdit("replace_stop",target="stop:3",place="D")))
        assertEquals(listOf("C","A","D"),candidate.preview.map { it.label })
        assertEquals(65,candidate.preview.first().stayMinutes)
        val corrected = candidate.applyEdits(listOf(PlanEdit("remove_stop",target="stop:1"),PlanEdit("set_stay",target="stop:2",minutes=50)))
        assertEquals(listOf("A","D"),corrected.preview.map { it.label })
        assertEquals(candidate.preview[1],corrected.preview[0])
        assertEquals(candidate.preview[2].id,corrected.preview[1].id)
    }

    @Test fun stayCorrectionClearsHoldAndPreservesOtherArrivalMetadata() {
        val before = base(listOf(stop("Appointment")))
        val changed = PlanCandidate.fromSnapshot(before).applyEdits(listOf(PlanEdit("set_stay",target="stop:1",minutes=75)))
        val result = changed.toSnapshot(before).scenario.stops.single()
        assertEquals(before.scenario.stops.single().copy(dwellMinutes=75,stayUntilLeave=false),result)
    }

    @Test fun explicitNewStayOverridesSavedHoldWhileUnspecifiedStayUsesSavedDefaults() {
        val before = base()
        val saved = before.scenario.copy(id="home",name="Home",kind="SNAP",defaultStayMinutes=120,
            stayUntilLeave=true,arrivalActivity=ArrivalActivity.TABLE,entrance=LatLng(34.0001,-112.0))
        val hit = PlaceHit(saved.dest!!,saved.name)
        val pending = PlanCandidate.fromSnapshot(before).applyEdits(listOf(PlanEdit("add_stop",place="Home",minutes=45)))
        val explicit = pending.resolve(pending.preview.single().id,hit,saved).toSnapshot(before).scenario.stops.single()
        assertEquals(45,explicit.dwellMinutes); assertFalse(explicit.stayUntilLeave)
        assertEquals(saved.arrivalActivity,explicit.arrivalActivity); assertEquals(saved.entrance,explicit.entrance)
        val unspecified = PlanCandidate.fromSnapshot(before).applyEdits(listOf(PlanEdit("add_stop",place="Home")))
        val result = unspecified.resolve(unspecified.preview.single().id,hit,saved).toSnapshot(before).scenario.stops.single()
        assertEquals(120,result.dwellMinutes); assertTrue(result.stayUntilLeave)
    }

    @Test fun addingToRoutePreservesExistingDestinationBeforeNewStop() {
        val before = base(kind="ROUTE")
        val pending = PlanCandidate.fromSnapshot(before).applyEdits(listOf(PlanEdit("add_stop",place="Next")))
        assertEquals(PlanMode.ITINERARY,pending.planMode)
        assertEquals(listOf("Existing destination","Next"),pending.preview.map { it.label })
        assertEquals(10,pending.preview.first().stayMinutes)
        assertNull(pending.destination)
    }

    @Test fun changingItineraryDestinationPreservesEarlierStopsAndRejectsMissingReferences() {
        val initial = PlanCandidate.fromSnapshot(base(listOf(stop("First"),stop("Last"))))
        val changed = initial.applyEdits(listOf(PlanEdit("set_destination",place="New last")))
        assertEquals(initial.preview.first(),changed.preview.first())
        assertEquals(listOf("First","New last"),changed.preview.map { it.label })
        assertThrows(IllegalArgumentException::class.java) { initial.applyEdits(listOf(
            PlanEdit("set_stay",target="stop:1",minutes=50),PlanEdit("remove_stop",target="stop:3"))) }
        assertEquals(25,initial.preview.first().stayMinutes)
    }

    @Test fun ambiguousOrSkippedDaylightSavingsTimeIsNotSilentlyAdjusted() {
        val initial = PlanCandidate.fromSnapshot(base())
        for (time in listOf("2027-03-14T02:30","2027-11-07T01:30")) assertThrows(IllegalArgumentException::class.java) {
            initial.applyEdits(listOf(PlanEdit("set_departure",value=time)),ZoneId.of("America/Los_Angeles"))
        }
    }

    @Test fun lastMentionedLocationTracksIdentityThroughReorderAndClearsAfterRemoval() {
        val initial = PlanCandidate.fromSnapshot(base(listOf(stop("First"),stop("Second"))))
        assertNull(initial.lastTarget)
        val changed = initial.applyEdits(listOf(PlanEdit("set_stay",target="stop:1",minutes=40),
            PlanEdit("move_stop",target="stop:1",value="2"),PlanEdit("set_departure",value="2027-01-05T17:00")))
        assertEquals(initial.preview.first().id,changed.lastTarget)
        assertEquals("First",changed.preview.last().label)
        assertEquals(changed,PlanCandidate.fromJson(changed.toJson()))
        assertNull(changed.applyEdits(listOf(PlanEdit("remove_stop",target="stop:2"))).lastTarget)
    }
}
