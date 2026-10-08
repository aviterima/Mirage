package com.mirage.spike

import android.content.Context
import com.mirage.spike.engine.*
import com.mirage.spike.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

internal fun SavedStop.toStop() = ItineraryStop(name,LatLng(lat,lng),dwellMinutes,mode,avgMph,address,placeId,routingRealism,routingTransitPref,ownRoutingPreferences,frozenRoute,arriveByMillis,arrivalActivity,entrance,stayUntilLeave)
internal fun ItineraryStop.toSavedStop() = SavedStop(name,point.lat,point.lng,dwellMinutes,mode,avgMph,address,placeId,routingRealism,routingTransitPref,ownRoutingPreferences,frozenRoute,arriveByMillis,arrivalActivity,entrance,stayUntilLeave)

data class RecoveredTrip(val scenario: SavedScenario, val position: LatLng, val index: Int, val activity: ActivityKind, val remainingStay: Int, val savedId: String?, val timeScale: Double = 1.0, val speedOffset: Double = 0.0, val routeProgress: Double? = null)

/** Process-scoped checkpointing remains active when the map Activity is backgrounded. */
object TripRecovery {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var prefs: android.content.SharedPreferences?=null
    private var observer: Job?=null
    @Volatile private var suppressedEpoch=-1L
    @Volatile var failure: String?=null
        private set
    fun configure(context: Context) {
        if(prefs!=null) return
        prefs=context.applicationContext.getSharedPreferences("mirage_recovery",Context.MODE_PRIVATE)
        observer=scope.launch {
            launch { LiveSession.state.map { Triple(it.stops,it.index,it.activity) }.distinctUntilChanged().collect { checkpoint() } }
            while(isActive) { delay(15_000); checkpoint() }
        }
    }
    @Synchronized private fun checkpoint() {
        val plan=LiveSession.plan ?: return
        val view=plan.view(); val status=MockState.status.value
        if(!status.running || view.stops.isEmpty() || LiveSession.epoch==suppressedEpoch) return
        val last=view.stops.last().stop
        val scenario=SavedScenario(view.savedId ?: "recovery",view.savedName.ifBlank {plan.title},"ITINERARY",System.currentTimeMillis(),false,
            plan.origin,"Trip start",last.point,last.name,last.mode,emptyMap(),plan.defaultsRealism,plan.defaultsTransitPref,view.stops.map{it.stop.toSavedStop()},departureMillis=plan.departureMillis)
        val json=JSONObject().put("scenario",scenario.toJson()).put("lat",status.lat).put("lng",status.lng)
            .put("routeProgress",status.progress.toDouble()).put("timeScale",PlaybackSource.timeScale).put("speedOffset",PlaybackSource.speedOverLimitMph)
            .put("index",view.index).put("activity",view.activity.name).put("stay",view.remainingStaySeconds).put("savedId",view.savedId)
        failure=if(prefs?.edit()?.putString("live",json.toString())?.commit()==true) null else "Could not preserve the latest trip checkpoint"
    }
    @Synchronized fun clearLive() { suppressedEpoch=LiveSession.epoch; prefs?.edit()?.remove("live")?.commit() }
    fun live(): RecoveredTrip? = runCatching {
        val json=JSONObject(prefs?.getString("live",null) ?: return null)
        RecoveredTrip(SavedScenario.fromJson(json.getJSONObject("scenario")),LatLng(json.getDouble("lat"),json.getDouble("lng")),
            json.getInt("index"),ActivityKind.valueOf(json.getString("activity")),json.getInt("stay"),json.optString("savedId").takeIf {it.isNotBlank() && it!="null"},json.optDouble("timeScale",1.0).coerceIn(1.0,100.0),json.optDouble("speedOffset",0.0).coerceIn(-10.0,15.0),json.optDouble("routeProgress",Double.NaN).takeIf {it.isFinite() && it in 0.0..1.0})
    }.getOrNull()
    fun draft(): SavedScenario? = prefs?.getString("draft",null)?.let {runCatching{SavedScenario.fromJson(JSONObject(it))}.getOrNull()}
    fun saveDraft(item: SavedScenario) { failure=if(prefs?.edit()?.putString("draft",item.toJson().toString())?.commit()==true) null else "Could not preserve the latest draft" }
}
