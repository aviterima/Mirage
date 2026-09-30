package com.mirage.spike.engine

import org.json.JSONArray
import org.json.JSONObject

/** Exact geometry and segment timing for repeatable road-route playback; transit remains fresh. */
object RouteArchive {
    private fun points(list: List<LatLng>)=JSONArray(list.map{JSONArray(listOf(it.lat,it.lng))})
    private fun readPoints(a: JSONArray): List<LatLng> {
        require(a.length() in 1..50000) { "Invalid archived route size" }
        return (0 until a.length()).map { i -> val p=a.getJSONArray(i); val lat=p.getDouble(0);val lng=p.getDouble(1)
            require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0); LatLng(lat,lng) }
    }
    fun encode(r: RouteResult)=JSONObject().put("points",points(r.points)).put("distance",r.distanceMeters).put("seconds",r.durationSeconds)
        .put("fetched",r.fetchedAtMillis).put("segments",JSONArray(r.segments.map{ s ->
            JSONObject().put("points",points(s.points)).put("distance",s.distanceMeters).put("seconds",s.durationSeconds)
                .put("instruction",s.instruction).put("maneuver",s.maneuver)
        }))
    fun decode(o: JSONObject): RouteResult {
        val distance=o.getDouble("distance");val seconds=o.getDouble("seconds")
        require(distance.isFinite() && seconds.isFinite() && distance>=0 && seconds>=0)
        val segments=o.optJSONArray("segments") ?: JSONArray()
        require(segments.length()<=10000)
        return RouteResult(readPoints(o.getJSONArray("points")),distance,seconds,(0 until segments.length()).map { i ->
            val s=segments.getJSONObject(i);val d=s.getDouble("distance");val t=s.getDouble("seconds")
            require(d.isFinite()&&t.isFinite()&&d>=0&&t>=0)
            val p=s.getJSONArray("points")
            RouteSegment(if(p.length()==0) emptyList() else readPoints(p),d,t,null,s.optString("instruction"),s.optString("maneuver"))
        },fetchedAtMillis=o.optLong("fetched"))
    }
}
