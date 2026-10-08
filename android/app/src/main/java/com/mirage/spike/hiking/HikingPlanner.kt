package com.mirage.spike.hiking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mirage.spike.engine.*
import kotlinx.coroutines.*
import java.util.Locale

/** Everything in this sheet is a draft until the user confirms the prepared trip. */
data class HikingDraft(
    val near: LatLng, val areaName: String = "current map area", val query: String = "", val areaQuery: String = "",
    val areas: List<PlaceHit> = emptyList(), val trails: List<HikingTrail> = emptyList(), val selected: HikingTrail? = null,
    val parking: List<TrailParking> = emptyList(), val selectedParking: TrailParking? = null,
    val miles: String = "", val wholeTrail: Boolean = true, val parkingMinutes: String = "2",
    val busy: Boolean = false, val error: String? = null, val searched: Boolean = false,
    val trip: HikingTrip? = null,
)
class HikingPlanner(private val scope: CoroutineScope, private val source: TrailSource = OsmTrailSource(),
    private val places: suspend (String,LatLng) -> List<PlaceHit>,
    private val route: suspend (RouteSpec) -> RouteResult) {
    var state by mutableStateOf<HikingDraft?>(null); private set
    private var generation=0L
    private var job: Job?=null
    fun open(near: LatLng) {close();state=HikingDraft(near)}
    fun close() {generation++;job?.cancel();state=null}
    private fun change(block: (HikingDraft)->HikingDraft) {generation++;job?.cancel();state=state?.let(block)?.copy(busy=false,trip=null,error=null)}
    fun query(text: String)=change {it.copy(query=text,trails=emptyList(),selected=null,parking=emptyList(),selectedParking=null,searched=false)}
    fun areaQuery(text: String)=change {it.copy(areaQuery=text,areas=emptyList())}
    fun chooseArea(hit: PlaceHit)=change {it.copy(near=hit.latLng,areaName=listOf(hit.name,hit.address).filter(String::isNotBlank).joinToString(" · "),areas=emptyList(),trails=emptyList(),selected=null,parking=emptyList(),selectedParking=null,searched=false)}
    fun miles(text: String)=change {it.copy(miles=text,wholeTrail=false)}
    fun wholeTrail()=change {d->d.copy(wholeTrail=true,miles=String.format(Locale.US,"%.2f",(d.selected?.maxHikeMeters ?: 0.0)/METERS_PER_MILE))}
    fun parkingMinutes(text: String)=change {it.copy(parkingMinutes=text)}
    fun chooseParking(parking: TrailParking)=change {it.copy(selectedParking=parking)}
    private fun work(block: suspend (HikingDraft)->HikingDraft) {
        val initial=state ?: return
        generation++;val serial=generation;job?.cancel();state=initial.copy(busy=true,error=null,trip=null)
        job=scope.launch {
            try {val next=block(initial);if(isActive && generation==serial)state=next.copy(busy=false)}
            catch(e: CancellationException){throw e}
            catch(e: Exception){if(generation==serial)state=state?.copy(busy=false,error=e.message ?: "Unable to prepare hike")}
        }
    }
    fun findArea()=work {d->
        require(d.areaQuery.isNotBlank()) {"Enter a city, park, or area."}
        val results=places(d.areaQuery,d.near)
        require(results.isNotEmpty()) {"No area found. Try a city and state."}
        d.copy(areas=results)
    }
    fun search()=work {d->d.copy(trails=source.search(d.query,d.near),selected=null,parking=emptyList(),selectedParking=null,searched=true)}
    fun select(trail: HikingTrail) {
        change {d->d.copy(selected=trail,parking=emptyList(),selectedParking=null,wholeTrail=true,
            miles=String.format(Locale.US,"%.2f",trail.maxHikeMeters/METERS_PER_MILE))}
        work {d->d.copy(parking=source.parking(trail))}
    }
    fun prepare(origin: LatLng?)=work {d->
        require(origin!=null) {"Set a starting location on the main map first."}
        val trail=d.selected ?: error("Choose a trail.")
        val parking=d.selectedParking ?: error("Choose or pin a parking location.")
        val miles=if(d.wholeTrail)trail.maxHikeMeters/METERS_PER_MILE else d.miles.trim().toDoubleOrNull() ?: error("Enter the miles to hike.")
        val minutes=d.parkingMinutes.toIntOrNull() ?: error("Enter parking time in whole minutes.")
        d.copy(trip=prepareHikingTrip(trail,parking,origin,miles,minutes,route))
    }
    fun takePrepared(): HikingTrip? {val trip=state?.trip ?: return null;close();return trip}
}
