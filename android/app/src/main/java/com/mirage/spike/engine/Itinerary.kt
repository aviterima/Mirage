package com.mirage.spike.engine

import com.mirage.spike.MockState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.Random

/** One itinerary stop: where, what to call it, and how long to stay there. */
data class ItineraryStop(
    val name: String,
    val point: LatLng,
    val dwellMinutes: Int,
    /** How this leg is traveled; each leg has its own mode and speed. */
    val mode: TravelMode = TravelMode.DRIVE,
    val avgMph: Float = 45f,
)

/**
 * Plays an itinerary as ONE continuous fix stream: travel each leg, then dwell at the
 * stop (still, with an occasional short walk, like a person at a place) for its
 * duration, then the next leg.
 * When the last stop's dwell ends the stream completes and the service keeps holding
 * that point until the user taps Stop.
 */
object ItineraryModel {
    fun play(legs: List<Pair<Flow<Fix>, ItineraryStop>>, updateHz: Int = 5, timeScale: Double = 1.0): Flow<Fix> = flow {
        val rnd = Random()
        val dtMillis = (1000 / updateHz).toLong()
        for ((leg, stop) in legs) {
            MockState.update { it.copy(stepLabel = "Traveling to ${stop.name}") }
            emitAll(leg)
            val anchor = Fix(stop.point.lat, stop.point.lng, 0f, 0f, 4f)
            val dwell = DwellModel(anchor, 12.0, rnd)
            // Wall-clock dwell; in fast-forward the same simulated stay passes sooner.
            val totalMs = (stop.dwellMinutes * 60_000L / timeScale).toLong()
            var elapsed = 0L
            var shownMin = -1L
            while (elapsed < totalMs) {
                val remainMin = (((totalMs - elapsed) * timeScale).toLong() + 59_999L) / 60_000L
                if (remainMin != shownMin) {
                    shownMin = remainMin
                    MockState.update { it.copy(stepLabel = "At ${stop.name} \u2014 $remainMin min left") }
                }
                val prog = if (totalMs > 0) (elapsed.toDouble() / totalMs).toFloat() else 1f
                emit(dwell.next(dtMillis / 1000.0).copy(progress = prog, remainingSec = ((totalMs - elapsed) / 1000).toInt()))
                delay(dtMillis)
                elapsed += dtMillis
            }
        }
        MockState.update { it.copy(stepLabel = "Itinerary complete \u2014 holding last stop") }
    }
}
