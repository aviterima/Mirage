package com.mirage.spike.hiking

import com.mirage.spike.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
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
class OsmTrailSource(private val endpoint: String = "https://overpass.private.coffee/api/interpreter") : TrailSource {
    private val client=OkHttpClient.Builder().callTimeout(45,TimeUnit.SECONDS).build()
    private val cache=linkedMapOf<String,Pair<Long,String>>()
    private val mutex=Mutex()
    private var retryAfter=0L
    private suspend fun query(ql: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        val now=System.currentTimeMillis()
        cache[ql]?.takeIf {now-it.first<900_000}?.let {return@withLock it.second}
        check(now>=retryAfter) {"Trail service is busy. Please wait a minute and try again."}
        val req=Request.Builder().url(endpoint).header("User-Agent","Mirage/0.15.0 (https://github.com/aviterima/Mirage)")
            .post(FormBody.Builder().add("data",ql).build()).build()
        client.newCall(req).execute().use { r ->
            if(r.code in listOf(429,406,504))retryAfter=now+60_000
            check(r.isSuccessful) {"Trail service unavailable (${r.code}). Please try again later."}
            val input=r.body?.byteStream() ?: error("Empty trail response")
            val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
            while(output.size()<=4_000_000) {val n=input.read(chunk);if(n<0)break;output.write(chunk,0,n)}
            val bytes=output.toByteArray()
            check(bytes.size<=4_000_000) {"Too many trail details. Try a more specific trail name."}
            val text=bytes.toString(Charsets.UTF_8)
            check(JSONObject(text).optString("remark").isBlank()) {"Trail search was incomplete. Try a more specific name."}
            if(cache.size>=20)cache.remove(cache.keys.first())
            cache[ql]=now to text
            text
        }
    } }
    override suspend fun search(name: String, near: LatLng): List<HikingTrail> {
        require(name.trim().length in 3..100) {"Enter a trail name of at least 3 characters."}
        val literal=name.trim().map { if(it in "\\.^$|?*+()[]{}") "\\$it" else "$it" }.joinToString("")
        val pattern=JSONObject.quote(literal)
        val area="(around:50000,${near.lat},${near.lng})"
        val q="[out:json][timeout:25][maxsize:33554432];(relation$area[route~\"^(hiking|foot)$\"][name~$pattern,i];way$area[highway~\"^(path|footway|track|steps)$\"][name~$pattern,i][area!=yes];);out geom 100;"
        return parseTrails(query(q)).sortedBy { Geo.haversine(near,it.points.first()) }.take(20)
    }
    override suspend fun parking(trail: HikingTrail): List<TrailParking> {
        val anchors=if(trail.loop)trail.points.filterIndexed { i,_ -> i % maxOf(1,trail.points.size/6)==0 }.take(6) else listOf(trail.points.first(),trail.points.last())
        val selectors=anchors.joinToString("") {"nwr(around:1200,${it.lat},${it.lng})[amenity=parking][access!~\"^(private|no)$\"];"}
        val json=JSONObject(query("[out:json][timeout:25][maxsize:16777216];($selectors);out center 100;"))
        return elements(json).mapNotNull { e ->
            val p=point(e.optJSONObject("center") ?: e) ?: return@mapNotNull null
            TrailParking("${e.optString("type")}/${e.optLong("id")}",e.optJSONObject("tags")?.optString("name")?.takeIf {it.isNotBlank()} ?: "Mapped parking",p)
        }.distinctBy {it.id}.sortedBy {p->anchors.minOf {Geo.haversine(p.point,it)}}.take(12)
    }
    companion object {
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
        fun parseTrails(text: String): List<HikingTrail> {
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
                    val points=TrailGeometry.join(parts) ?: return@forEach
                    val id="way/${ways.first().getLong("id")}";results+=HikingTrail(id,name,points,"https://www.openstreetmap.org/$id",mappedSection=true)
                }
            return results
        }
    }
}
