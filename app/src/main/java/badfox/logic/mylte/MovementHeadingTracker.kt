package badfox.logic.mylte

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Derives a stable movement heading from recent confirmed location displacement.
 *
 * Small position changes that do not exceed the combined uncertainty of consecutive fixes are
 * ignored. Confirmed movement segments are averaged as circular vectors so wraparound at 0/360°
 * and one-off contradictory segments do not make the displayed direction jump.
 */
class MovementHeadingTracker {
    private data class Segment(
        val bearingDegrees: Float,
        val weight: Double,
    )

    private val segments = ArrayDeque<Segment>()
    private var anchorLocation: Location? = null
    private var stableBearingDegrees: Float? = null

    fun update(location: Location): Float? {
        val anchor = anchorLocation
        if (anchor == null) {
            anchorLocation = Location(location)
            return stableBearingDegrees
        }

        val distanceMeters = anchor.distanceTo(location)
        val uncertaintyMeters = combinedUncertaintyMeters(anchor, location)
        if (distanceMeters < uncertaintyMeters) {
            return stableBearingDegrees
        }

        val segment = Segment(
            bearingDegrees = normalizeBearing(anchor.bearingTo(location)),
            weight = distanceMeters.toDouble() / uncertaintyMeters.coerceAtLeast(1f).toDouble(),
        )
        segments.addLast(segment)
        while (segments.size > MAX_SEGMENTS) {
            segments.removeFirst()
        }
        anchorLocation = Location(location)

        val vector = averageVector() ?: return stableBearingDegrees
        if (vector.second < MIN_RESULTANT_STRENGTH && stableBearingDegrees != null) {
            return stableBearingDegrees
        }

        stableBearingDegrees = normalizeBearing(
            Math.toDegrees(atan2(vector.first.second, vector.first.first)).toFloat()
        )
        return stableBearingDegrees
    }

    fun currentBearing(): Float? {
        return stableBearingDegrees
    }

    private fun averageVector(): Pair<Pair<Double, Double>, Double>? {
        if (segments.isEmpty()) return null

        var x = 0.0
        var y = 0.0
        var totalWeight = 0.0
        segments.forEach { segment ->
            val radians = Math.toRadians(segment.bearingDegrees.toDouble())
            x += cos(radians) * segment.weight
            y += sin(radians) * segment.weight
            totalWeight += segment.weight
        }
        if (totalWeight <= 0.0) return null

        val magnitude = hypot(x, y)
        val strength = magnitude / totalWeight
        return (x to y) to strength
    }

    private fun combinedUncertaintyMeters(first: Location, second: Location): Float {
        val firstAccuracy = if (first.hasAccuracy()) first.accuracy else MIN_MOVEMENT_METERS
        val secondAccuracy = if (second.hasAccuracy()) second.accuracy else MIN_MOVEMENT_METERS
        return max(
            MIN_MOVEMENT_METERS,
            (firstAccuracy + secondAccuracy) / 2f,
        )
    }

    private fun normalizeBearing(value: Float): Float {
        return ((value % 360f) + 360f) % 360f
    }

    private companion object {
        const val MAX_SEGMENTS = 6
        const val MIN_MOVEMENT_METERS = 6f
        const val MIN_RESULTANT_STRENGTH = 0.35
    }
}
