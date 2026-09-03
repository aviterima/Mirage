package com.mirage.spike.engine

import com.mirage.spike.MockState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.Random

/** One itinerary stop: where, what to call it, and how long to stay there. */
data class ItineraryStop(val name: String, val point: LatLng, val dwellMinutes: Int)

/**
 * Plays an itinerary as ONE continuous fix stream: travel each leg, then dwell at the
 * stop (dithered, so it never looks frozen) for its duration, then the next leg.
 * When the last stop's dwell ends the stream completes and the service keeps holding
 * that point until the user taps Stop.
 */
object ItineraryModel {
    fun play(legs: List<Pair<Flow<Fix>, ItineraryStop>>, updateHz: Int = 5): Flow<Fix> = flow {
        val rnd = Random()
        val dtMillis = (1000 / updateHz).toLong()
        for ((leg, stop) in legs) {
            MockState.update { it.copy(stepLabel = "Traveling to ${stop.name}") }
            emitAll(leg)
            val anchor = Fix(stop.point.lat, stop.point.lng, 0f, 0f, 4f)
            val totalMs = stop.dwellMinutes * 60_000L
            var elapsed = 0L
            var shownMin = -1L
            while (elapsed < totalMs) {
                val remainMin = (totalMs - elapsed + 59_999L) / 60_000L
                if (remainMin != shownMin) {
                    shownMin = remainMin
                    MockState.update { it.copy(stepLabel = "At ${stop.name} \u2014 $remainMin min left") }
                }
                emit(MotionModel.dither(anchor, 6.0, rnd))
                delay(dtMillis)
                elapsed += dtMillis
            }
        }
        MockState.update { it.copy(stepLabel = "Itinerary complete \u2014 holding last stop") }
    }
}
