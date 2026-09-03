package com.mirage.spike.engine

import java.util.Random
import kotlin.math.min

/**
 * How a phone behaves when its owner is *at* a place (an office, a restaurant, home):
 * still almost all the time — the fix sits on one spot with only sub-metre GPS noise —
 * and every few minutes the person gets up and walks, at walking pace, a short way to
 * another spot within [radiusM] of the anchor, then sits still again. Nothing ever
 * teleports and nothing drifts away from the anchor.
 *
 * Stateful: call [next] once per emitted fix with the elapsed seconds since the last one.
 */
class DwellModel(
    private val anchor: Fix,
    private val radiusM: Double = 20.0,
    private val rnd: Random = Random(),
) {
    private enum class State { STILL, WALK }

    private var state = State.STILL
    private var spot = LatLng(anchor.lat, anchor.lng)   // where we are sitting / walking from
    private var target = spot                            // where the current walk is heading
    private var walkSpeed = 0.0
    private var walkBearing = 0.0
    private var stillRemainingS = firstStillSeconds()

    fun next(dtSec: Double): Fix = when (state) {
        State.STILL -> {
            stillRemainingS -= dtSec
            if (stillRemainingS <= 0) beginWalk()
            // Sitting: tiny GPS wobble only, no reported speed.
            val n = Geo.offset(spot, rnd.nextGaussian() * 0.35, rnd.nextGaussian() * 0.35)
            anchor.copy(lat = n.lat, lng = n.lng, speedMps = 0f, bearingDeg = walkBearing.toFloat(),
                accuracyM = 4f + rnd.nextFloat() * 2f)
        }
        State.WALK -> {
            val remaining = Geo.haversine(spot, target)
            val step = min(walkSpeed * dtSec, remaining)
            spot = Geo.offset(spot, step * Math.cos(Math.toRadians(walkBearing)), step * Math.sin(Math.toRadians(walkBearing)))
            if (remaining - step < 0.3) {
                spot = target; state = State.STILL; stillRemainingS = stillSeconds()
            }
            anchor.copy(lat = spot.lat, lng = spot.lng, speedMps = walkSpeed.toFloat(),
                bearingDeg = walkBearing.toFloat(), accuracyM = 5f + rnd.nextFloat() * 3f)
        }
    }

    private fun beginWalk() {
        // Usually a short hop to another spot near the anchor; sometimes back to the anchor itself.
        target = if (rnd.nextDouble() < 0.3) LatLng(anchor.lat, anchor.lng) else {
            val ang = rnd.nextDouble() * 2 * Math.PI
            val r = radiusM * (0.3 + 0.7 * rnd.nextDouble())
            Geo.offset(LatLng(anchor.lat, anchor.lng), r * Math.cos(ang), r * Math.sin(ang))
        }
        if (Geo.haversine(spot, target) < 2.0) { stillRemainingS = stillSeconds(); return }
        walkSpeed = 1.2 + rnd.nextGaussian() * 0.15           // ~2.7 mph, a person walking indoors
        walkBearing = Geo.bearing(spot, target)
        state = State.WALK
    }

    /** 1–8 minutes between moves. */
    private fun stillSeconds() = 60.0 + rnd.nextDouble() * 420.0
    /** Settle for a moment on arrival before the first wander. */
    private fun firstStillSeconds() = 30.0 + rnd.nextDouble() * 90.0
}
