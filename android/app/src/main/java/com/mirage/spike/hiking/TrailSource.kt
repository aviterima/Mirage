package com.mirage.spike.hiking

import com.mirage.spike.engine.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface TrailSource {
    suspend fun search(name: String, near: LatLng): List<HikingTrail>
    suspend fun parking(trail: HikingTrail): List<TrailParking>
}

/** Explicit, bounded queries with a small cache; no keystroke queries or bulk downloads. */
class OsmTrailSource(private val endpoint: String = "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    private val backupEndpoint: String? = "https://overpass-api.de/api/interpreter") : TrailSource {
    private val client=OkHttpClient.Builder().callTimeout(45,TimeUnit.SECONDS).build()
    private val cache=linkedMapOf<String,Pair<Long,String>>()
    private val mutex=Mutex()
    private val retryAfter=mutableMapOf<String,Long>()
    private suspend fun query(ql: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        val now=System.currentTimeMillis()
        cache[ql]?.takeIf {now-it.first<900_000}?.let {return@withLock it.second}
        var lastFailure: IOException?=null
        for(server in listOfNotNull(endpoint,backupEndpoint).distinct()) {
            currentCoroutineContext().ensureActive()
            if(now<(retryAfter[server] ?: 0L))continue
            try {
                val req=Request.Builder().url(server.toHttpUrl().newBuilder().addQueryParameter("data",ql).build()).header("User-Agent","Mirage/0.15.1 (https://github.com/aviterima/Mirage)")
                    .get().build()
                val text=client.newCall(req).execute().use { r ->
                    if(r.code in listOf(429,406) || r.code>=500)throw IOException("Trail service unavailable (${r.code}).")
                    check(r.isSuccessful) {"Trail search was rejected (${r.code}). Try another area or name."}
                    val input=r.body?.byteStream() ?: throw IOException("Empty trail response")
                    val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                    while(output.size()<=4_000_000) {val n=input.read(chunk);if(n<0)break;output.write(chunk,0,n)}
                    val bytes=output.toByteArray()
                    check(bytes.size<=4_000_000) {"Too many trail details. Try a more specific trail name."}
                    val body=bytes.toString(Charsets.UTF_8)
                    if(JSONObject(body).optString("remark").isNotBlank())throw IOException("Trail search was incomplete.")
                    body
                }
                currentCoroutineContext().ensureActive()
                if(cache.size>=20)cache.remove(cache.keys.first())
                cache[ql]=System.currentTimeMillis() to text
                return@withLock text
            } catch(e: IOException) {
                currentCoroutineContext().ensureActive()
                retryAfter[server]=System.currentTimeMillis()+60_000
                lastFailure=e
            }
        }
        error("Trail services are busy or unreachable. Please wait a minute and try again."+(lastFailure?.message?.let {" $it"} ?: ""))
    } }

    override suspend fun search(name: String, near: LatLng): List<HikingTrail> {
        require(name.trim().length<=100) {"Use a trail name of at most 100 characters."}
        val literal=name.trim().map { if(it in "\\.^$|?*+()[]{}") "\\$it" else "$it" }.joinToString("")
        val pattern=JSONObject.quote(literal)
        val latDelta=50_000.0/111_320.0
        val lngDelta=latDelta/kotlin.math.cos(Math.toRadians(near.lat)).coerceAtLeast(0.1)
        val area="(${(near.lat-latDelta).coerceAtLeast(-85.0)},${(near.lng-lngDelta).coerceAtLeast(-180.0)},${(near.lat+latDelta).coerceAtMost(85.0)},${(near.lng+lngDelta).coerceAtMost(180.0)})"
        if(name.isBlank()) {
            // Discover light metadata first, then fetch full geometry for a bounded sample.
            // Limit is explicit in the UI; never infer a trail length from a center point.
            val metadata=query("[out:json][timeout:25][maxsize:268435456];(way$area[highway~\"^(path|footway|track|steps)$\"][name][area!=yes][access!~\"^(private|no)$\"][foot!~\"^(private|no)$\"];relation$area[route~\"^(hiking|foot)$\"][name][access!~\"^(private|no)$\"][foot!~\"^(private|no)$\"];);out tags center 1000;")
            val selectors=browseSelectors(metadata,near)
            if(selectors.isEmpty())return emptyList()
            return parseTrails(query("[out:json][timeout:25][maxsize:268435456];($selectors);out geom 100;"),allowSections=true)
                .filter {trail->trail.points.any {Geo.haversine(near,it)<=50_000.0}}
                .sortedBy {it.distanceFrom(near)}.take(20)
        }
        val q="[out:json][timeout:25][maxsize:268435456];way$area[highway~\"^(path|footway|track|steps)$\"][name][area!=yes]->.paths;relation$area[route~\"^(hiking|foot)$\"][name]->.routes;(way.paths[name~$pattern,i];relation.routes[name~$pattern,i];);out geom 100;"
        return parseTrails(query(q)).filter {trail->trail.points.any {Geo.haversine(near,it)<=50_000.0}}.sortedBy { Geo.haversine(near,it.points.first()) }.take(20)
    }
    override suspend fun parking(trail: HikingTrail): List<TrailParking> {
        val anchors=if(trail.loop)trail.points.filterIndexed { i,_ -> i % maxOf(1,trail.points.size/6)==0 }.take(6) else listOf(trail.points.first(),trail.points.last())
        val selectors=anchors.joinToString("") {"nwr(around:1200,${it.lat},${it.lng})[amenity=parking][access!~\"^(private|no)$\"];"}
        val json=JSONObject(query("[out:json][timeout:25][maxsize:67108864];($selectors);out center 100;"))
        return elements(json).mapNotNull { e ->
            val p=point(e.optJSONObject("center") ?: e) ?: return@mapNotNull null
            TrailParking("${e.optString("type")}/${e.optLong("id")}",e.optJSONObject("tags")?.optString("name")?.takeIf {it.isNotBlank()} ?: "Mapped parking",p)
        }.distinctBy {it.id}.sortedBy {p->anchors.minOf {Geo.haversine(p.point,it)}}.take(12)
    }
    companion object {
        /** Prefer nearby named groups, with at most 80 complete OSM elements / 20 names. */
        fun browseSelectors(text: String, near: LatLng): String {
            val root=JSONObject(text)
            require(root.optString("remark").isBlank()) {"Incomplete trail response"}
            val groups=elements(root).filter {
                it.optString("type") in setOf("way","relation") && it.optLong("id")>0 &&
                    !it.optJSONObject("tags")?.optString("name").isNullOrBlank() &&
                    it.optJSONObject("tags")?.optString("access") !in setOf("private","no") &&
                    it.optJSONObject("tags")?.optString("foot") !in setOf("private","no") &&
                    it.optJSONObject("center")?.let {c->point(c)}!=null
            }.groupBy {it.getJSONObject("tags").getString("name")}
                .values.sortedBy {g->g.minOf {Geo.haversine(near,point(it.getJSONObject("center"))!!)}}
            val chosen=mutableListOf<JSONObject>();var names=0
            for(group in groups) {
                if(names>=20 || chosen.size>=80)break
                chosen+=group.sortedBy {Geo.haversine(near,point(it.getJSONObject("center"))!!)}.take(80-chosen.size)
                names++
            }
            return chosen.groupBy {it.getString("type")}.entries.joinToString("") { (type,items)->
                "$type(id:${items.joinToString(","){it.getLong("id").toString()}});"
            }
        }
        private fun elements(o: JSONObject): List<JSONObject> = o.optJSONArray("elements")?.let {a->(0 until a.length()).map {a.getJSONObject(it)}} ?: emptyList()
        private fun point(o: JSONObject): LatLng? {
            val lat=o.optDouble("lat",Double.NaN);val lng=o.optDouble("lon",Double.NaN)
            return if(lat.isFinite()&&lng.isFinite()&&lat in -85.0..85.0&&lng in -180.0..180.0)LatLng(lat,lng) else null
        }
        private fun geometry(a: JSONArray?): List<LatLng>? {
            if(a==null || a.length() !in 2..20000)return null
            val p=(0 until a.length()).map {point(a.optJSONObject(it) ?: return null) ?: return null}
            return p
        }
        fun parseTrails(text: String, allowSections: Boolean = false): List<HikingTrail> {
            val root=JSONObject(text)
            require(root.optString("remark").isBlank()) {"Incomplete trail response"}
            val all=elements(root)
            require(all.size<100) {"Too many matches. Use a more specific trail name."}
            val results=mutableListOf<HikingTrail>();val covered=mutableSetOf<Long>()
            fun permitted(tags: JSONObject?) = tags?.optString("access") !in setOf("private","no") && tags?.optString("foot") !in setOf("private","no")
            all.filter {it.optString("type")=="relation"}.forEach {e->
                if(!permitted(e.optJSONObject("tags")))return@forEach
                val members=e.optJSONArray("members") ?: return@forEach
                val ways=(0 until members.length()).map {members.getJSONObject(it)}.filter {it.optString("type")=="way"}
                if(ways.isEmpty() || (0 until members.length()).any {members.getJSONObject(it).optString("type")=="relation"})return@forEach
                val parts=ways.map {geometry(it.optJSONArray("geometry")) ?: return@forEach}
                val points=TrailGeometry.join(parts) ?: return@forEach
                val id="relation/${e.getLong("id")}";val name=e.optJSONObject("tags")?.optString("name").orEmpty()
                if(name.isBlank())return@forEach
                results+=HikingTrail(id,name,points,"https://www.openstreetmap.org/$id")
                covered.addAll(ways.map {it.getLong("ref")})
            }
            all.filter {it.optString("type")=="way" && it.optLong("id") !in covered && permitted(it.optJSONObject("tags"))}
                .groupBy {it.optJSONObject("tags")?.optString("name").orEmpty()}.forEach { (name,ways) ->
                    if(name.isBlank())return@forEach
                    val parts=ways.map {geometry(it.optJSONArray("geometry")) ?: return@forEach}
                    val points=TrailGeometry.join(parts)
                    if(points!=null) {
                        val id="way/${ways.first().getLong("id")}";results+=HikingTrail(id,name,points,"https://www.openstreetmap.org/$id",mappedSection=true)
                    } else if(allowSections) ways.zip(parts).forEach { (way,part) ->
                        if(runCatching {TrailGeometry.validate(part)}.isSuccess) {
                            val id="way/${way.getLong("id")}";results+=HikingTrail(id,name,part,"https://www.openstreetmap.org/$id",mappedSection=true)
                        }
                    }
                }
            return results
        }
    }
}
