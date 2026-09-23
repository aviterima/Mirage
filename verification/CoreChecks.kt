import com.mirage.spike.*
import com.mirage.spike.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
fun main() = runBlocking {
    var assertions = 0
    fun verify(ok: Boolean, label: String) { check(ok) { label }; assertions++; println("PASS: $label") }
    verify(CommandParser.parse("Stop!") == SpokenCommand.ClarifyStop, "ambiguous stop")
    verify(CommandParser.parse("stop simulation and return to my real location") == SpokenCommand.Stop, "explicit stop")
    val journey = CommandParser.parse("Drive to the library then stay for thirty minutes then walk to the cafe") as SpokenCommand.Journey
    verify(journey.legs.size == 2 && journey.legs[0].minutes == 30 && journey.legs[1].mode == TravelMode.WALK, "multi-stop parsing")
    verify((CommandParser.parse("after this, drive to Sky Harbor") as SpokenCommand.Journey).next, "queue intent")
    verify(CommandParser.parse("don't stop the simulation") is SpokenCommand.Unknown, "negative phrase cannot stop")
    verify(CommandParser.parse("stay for another half an hour") == SpokenCommand.Extend(30), "spoken extension")
    verify(CommandParser.parse("stay for thirty minutes") == SpokenCommand.Stay(30), "set stay distinct from extension")
    verify(CommandParser.minutes("100 hours") == null, "duration bound")
    verify(CommandParser.parse("the second one") == SpokenCommand.Choice(2), "place selection ordinal")
    verify(CommandParser.parse("after this, snap to London") is SpokenCommand.Unknown, "queued snap cannot execute immediately")
    val a = LatLng(33.0, -112.0); val b = LatLng(33.1, -112.1)
    val routed = mutableListOf<String>()
    val draft = mutableListOf(ItineraryStop("One", b, 0))
    val plan = LivePlan("Trip", a, draft, { from, target ->
        routed += target.name
        delay(300)
        PreparedLeg(flow { emit(Fix(target.point.lat, target.point.lng, 0f, 0f, 4f)) }, listOf(from, target.point))
    })
    draft.clear()
    verify(plan.view().stops.size == 1, "draft isolated")
    var ticks = 0
    val play = launch { plan.fixes().collect { ticks++ } }
    delay(700)
    verify(ticks >= 3, "fixes continue during routing")
    verify(plan.view().activity == ActivityKind.HOLDING, "holds after arrival")
    verify(!plan.remove(plan.view().stops[0].id), "completed stop protected")
    verify(plan.setCurrentStay(1) && plan.view().activity == ActivityKind.STAYING, "timed stay from hold")
    val before = plan.view().remainingStaySeconds
    verify(plan.extendStay(2) && plan.view().remainingStaySeconds == before + 120, "live extension")
    plan.append(ItineraryStop("Two", a, 0))
    delay(300)
    verify(routed.size == 1, "future stop waits for active stay")
    PlaybackSource.requestSkip()
    delay(1000)
    verify(routed == listOf("One", "Two"), "leave now resumes appended stop")
    plan.append(ItineraryStop("Three", b, 0)); plan.append(ItineraryStop("Four", a, 0))
    val fourth = plan.view().stops.last().id
    verify(plan.move(fourth, -1) && plan.setMode(fourth, TravelMode.WALK), "future reorder and mode")
    verify(plan.setStay(fourth, 20), "future stay edit")
    play.cancelAndJoin()
    LiveSession.clear(); PlaybackSource.clearQueue(); PlaybackSource.consumeSkip()
    println("$assertions checks passed")
}
