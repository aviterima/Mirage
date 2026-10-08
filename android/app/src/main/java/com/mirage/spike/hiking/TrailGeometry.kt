package com.mirage.spike.hiking

import com.mirage.spike.engine.*
import java.util.Locale

const val METERS_PER_MILE = 1609.344
fun milesText(meters: Double) = String.format(Locale.US, "%.2f mi", meters / METERS_PER_MILE)

data class HikingTrail(val id: String, val name: String, val points: List<LatLng>, val sourceUrl: String,
    val mappedSection: Boolean = false) {
    val meters get() = TrailGeometry.length(points)
    val loop get() = points.size > 2 && Geo.haversine(points.first(), points.last()) < 1.0
    val maxHikeMeters get() = if (loop) meters else meters * 2
}
data class TrailParking(val id: String, val name: String, val point: LatLng)
data class HikingTrip(val trail: HikingTrail, val parking: TrailParking, val origin: LatLng,
    val trailMeters: Double, val walkMeters: Double, val driveMeters: Double, val stops: List<ItineraryStop>)

object TrailGeometry {
    fun length(points: List<LatLng>) = points.zipWithNext().sumOf { (a,b) -> Geo.haversine(a,b) }
    fun validate(points: List<LatLng>) {
        require(points.size in 2..20000) { "Trail geometry is missing or too large." }
        require(points.all { it.lat.isFinite() && it.lng.isFinite() && it.lat in -85.0..85.0 && it.lng in -180.0..180.0 }) { "Invalid trail coordinates." }
        require(length(points) in 10.0..160000.0) { "This mapped trail is too short or too long to simulate." }
    }
    /** Join only matching endpoints; branches/disconnected fragments are never bridged. */
    fun join(parts: List<List<LatLng>>): List<LatLng>? {
        if (parts.isEmpty() || parts.any { it.size < 2 }) return null
        if (parts.sumOf { it.size } > 20000) return null
        fun same(a: LatLng,b: LatLng) = Geo.haversine(a,b) < 1.0
        val endpoints = parts.flatMap { listOf(it.first(),it.last()) }
        if (endpoints.any { p -> endpoints.count { same(it,p) } > 2 }) return null
        val remaining=parts.toMutableList()
        val open = remaining.indexOfFirst { p -> endpoints.count { same(it,p.first()) } == 1 || endpoints.count { same(it,p.last()) } == 1 }
        val first=remaining.removeAt(if(open<0)0 else open)
        val line=(if(endpoints.count { same(it,first.first()) }==1 || open<0) first else first.reversed()).toMutableList()
        while(remaining.isNotEmpty()) {
            val i=remaining.indexOfFirst { same(line.last(),it.first()) || same(line.last(),it.last()) }
            if(i<0) return null
            val next=remaining.removeAt(i).let { if(same(line.last(),it.first()))it else it.reversed() }
            line.addAll(next.drop(1))
        }
        if(same(line.first(),line.last()))line[line.lastIndex]=line.first()
        return runCatching { validate(line);line.toList() }.getOrNull()
    }
    fun orient(trail: HikingTrail, parking: LatLng): HikingTrail {
        val pts=trail.points
        val oriented=if(trail.loop) {
            val ring=pts.dropLast(1)
            val nearest=ring.indices.minBy { Geo.haversine(parking,ring[it]) }
            (ring.drop(nearest)+ring.take(nearest)).let {it+it.first()}
        } else if(Geo.haversine(parking,pts.last())<Geo.haversine(parking,pts.first()))pts.reversed() else pts
        return trail.copy(points=oriented)
    }
    fun prefix(points: List<LatLng>, meters: Double): List<LatLng> {
        require(meters.isFinite() && meters>0 && meters<=length(points)+0.01)
        val out=mutableListOf(points.first());var left=meters
        for((a,b) in points.zipWithNext()) {
            val d=Geo.haversine(a,b)
            if(d<0.001)continue
            if(left<=d) {out.add(Geo.gcInterp(a,b,(left/d).coerceIn(0.0,1.0)));return out}
            out.add(b);left-=d
        }
        return out
    }
    /** Requested distance is total on-trail mileage, always ending back at trailhead. */
    fun hike(trail: HikingTrail, miles: Double): RouteResult {
        validate(trail.points)
        val meters=miles*METERS_PER_MILE
        require(meters.isFinite() && meters>=0.01*METERS_PER_MILE) { "Enter at least 0.01 mile." }
        require(meters<=trail.maxHikeMeters+0.01) { "Choose no more than ${milesText(trail.maxHikeMeters)} on this mapped trail." }
        val points=if(trail.loop && kotlin.math.abs(meters-trail.meters)<0.01)trail.points else {
            val outbound=prefix(trail.points,meters/2)
            outbound+outbound.dropLast(1).reversed()
        }
        val distance=length(points)
        return RouteResult(points,distance,distance/(2.5*0.44704),
            listOf(RouteSegment(points,distance,distance/(2.5*0.44704),instruction="Hike ${trail.name} · © OpenStreetMap contributors")))
    }
}

/** Freeze each leg only after routing succeeds; hiking never gets road-rerouted. */
suspend fun prepareHikingTrip(trail: HikingTrail, parking: TrailParking, origin: LatLng, miles: Double,
    parkingMinutes: Int, route: suspend (RouteSpec) -> RouteResult): HikingTrip {
    require(parkingMinutes in 0..60) { "Parking time must be between 0 and 60 minutes." }
    val selected=TrailGeometry.orient(trail,parking.point)
    val hike=TrailGeometry.hike(selected,miles)
    val drive=route(RouteSpec(origin,parking.point,mode=TravelMode.DRIVE))
    require(drive.points.size>=2 && Geo.haversine(drive.points.last(),parking.point)<=150) { "Driving directions do not reach this parking area. Choose another parking location." }
    val car=drive.points.last();val head=selected.points.first()
    val connector=if(Geo.haversine(car,head)<1) null else route(RouteSpec(car,head,mode=TravelMode.WALK)).let {
        require(it.points.size>=2 && Geo.haversine(it.points.first(),car)<=5 && Geo.haversine(it.points.last(),head)<=5) {
            "No continuous walking connection from this parking area to the trailhead. Choose another parking location."
        }
        val pts=listOf(car)+it.points+head
        val distance=TrailGeometry.length(pts)
        RouteResult(pts,distance,distance/(2.5*0.44704))
    }
    val stopList=mutableListOf(ItineraryStop("Park · ${parking.name}",car,parkingMinutes,TravelMode.DRIVE,45f,
        frozenRoute=drive,arrivalActivity=ArrivalActivity.PARKED))
    if(connector!=null)stopList+=ItineraryStop("Walk to ${trail.name} trailhead",head,0,TravelMode.WALK,2.5f,
        frozenRoute=connector,arrivalActivity=ArrivalActivity.TRAILHEAD)
    stopList+=ItineraryStop("Hike ${trail.name} · ${milesText(hike.distanceMeters)}",head,0,TravelMode.WALK,2.5f,
        address="Trail data © OpenStreetMap contributors · ${trail.sourceUrl}",frozenRoute=hike,arrivalActivity=ArrivalActivity.TRAILHEAD)
    if(connector!=null)stopList+=ItineraryStop("Return to parking · ${parking.name}",car,0,TravelMode.WALK,2.5f,
        frozenRoute=connector.copy(points=connector.points.reversed()),arrivalActivity=ArrivalActivity.PARKED)
    else stopList[stopList.lastIndex]=stopList.last().copy(arrivalActivity=ArrivalActivity.PARKED)
    return HikingTrip(selected,parking,origin,hike.distanceMeters,(connector?.distanceMeters ?: 0.0)*2,drive.distanceMeters,stopList)
}
