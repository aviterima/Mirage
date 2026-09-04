package com.mirage.spike.engine

import java.util.Random
import kotlin.math.min

/**
 * How a phone behaves when its owner is *at* a place (an office, a restaurant, home).
 * The anchor is "the desk": the fix sits there almost all the time with only sub-metre
 * GPS noise. Every few minutes the person gets up, walks at walking pace to a nearby
 * spot inside the same building (the coffee station, a colleague's desk — at most
 * [radiusM] away), lingers there briefly, and walks back to the desk. Nothing ever
 * teleports and the position never wanders off the anchor.
 *
 * Stateful: call [next] once per emitted fix with the elapsed seconds since the last one.
 */
class DwellModel(
    private val anchor: Fix,
    private val radiusM: Double = 12.0,
    private val rnd: Random = Random(),
) {
    private enum class State { AT_DESK, WALK_OUT, AWAY, WALK_BACK }

    private val desk = LatLng(anchor.lat, anchor.lng)
    private var state = State.AT_DESK
    private var spot = desk            // current position
    private var target = desk          // where the current walk is heading
    private var walkSpeed = 0.0
    private var walkBearing = 0.0
    private var remainingS = 30.0 + rnd.nextDouble() * 90.0   // settle on arrival before the first trip
    // Slow GPS "breathing": indoor fixes wander a few metres over tens of seconds.
    private var driftN = 0.0
    private var driftE = 0.0

    fun next(dtSec: Double): Fix = when (state) {
        State.AT_DESK, State.AWAY -> {
            remainingS -= dtSec
            if (remainingS <= 0) {
                if (state == State.AT_DESK) startWalk(pickNearbySpot(), State.WALK_OUT)
                else startWalk(desk, State.WALK_BACK)
            }
            // Sitting: realistic indoor GPS noise — a slow drift of up to ~3 m plus per-fix
            // jitter — so the position is genuinely fresh every fix (location sharing and
            // other consumers treat a perfectly frozen point as "not updating").
            driftN = (driftN * 0.995 + rnd.nextGaussian() * 0.08).coerceIn(-3.0, 3.0)
            driftE = (driftE * 0.995 + rnd.nextGaussian() * 0.08).coerceIn(-3.0, 3.0)
            val n = Geo.offset(spot, driftN + rnd.nextGaussian() * 0.6, driftE + rnd.nextGaussian() * 0.6)
            anchor.copy(lat = n.lat, lng = n.lng, speedMps = 0f, bearingDeg = walkBearing.toFloat(),
                accuracyM = 6f + rnd.nextFloat() * 6f)
        }
        State.WALK_OUT, State.WALK_BACK -> {
            val remaining = Geo.haversine(spot, target)
            val step = min(walkSpeed * dtSec, remaining)
            spot = Geo.offset(spot, step * Math.cos(Math.toRadians(walkBearing)), step * Math.sin(Math.toRadians(walkBearing)))
            if (remaining - step < 0.3) {
                spot = target
                if (state == State.WALK_OUT) { state = State.AWAY; remainingS = awaySeconds() }
                else { state = State.AT_DESK; remainingS = deskSeconds() }
            }
            anchor.copy(lat = spot.lat, lng = spot.lng, speedMps = walkSpeed.toFloat(),
                bearingDeg = walkBearing.toFloat(), accuracyM = 5f + rnd.nextFloat() * 3f)
        }
    }

    /** A spot inside the building envelope: 4 m to [radiusM] from the desk. */
    private fun pickNearbySpot(): LatLng {
        val ang = rnd.nextDouble() * 2 * Math.PI
        val r = 4.0 + (radiusM - 4.0).coerceAtLeast(0.0) * rnd.nextDouble()
        return Geo.offset(desk, r * Math.cos(ang), r * Math.sin(ang))
    }

    private fun startWalk(to: LatLng, walking: State) {
        target = to
        walkSpeed = 1.2 + rnd.nextGaussian() * 0.15           // ~2.7 mph, a person walking indoors
        walkBearing = Geo.bearing(spot, target)
        state = walking
    }

    /** 3–15 minutes at the desk between trips. */
    private fun deskSeconds() = 180.0 + rnd.nextDouble() * 720.0
    /** 20 s – 3 min at the coffee station before heading back. */
    private fun awaySeconds() = 20.0 + rnd.nextDouble() * 160.0
}
