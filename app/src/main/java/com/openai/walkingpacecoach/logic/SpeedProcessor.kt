package com.openai.walkingpacecoach.logic

import android.location.Location
import kotlin.math.max

class SpeedProcessor(
    private val maxAccuracyMeters: Float = 35f,
    private val maxWalkingRunningSpeedKmh: Double = 30.0,
    private val smoothingWindow: Int = 5
) {
    data class Result(
        val valid: Boolean,
        val rawSpeedKmh: Double? = null,
        val smoothedSpeedKmh: Double? = null,
        val distanceIncrementMeters: Double = 0.0,
        val reason: String = ""
    )

    private val recentSpeeds = ArrayDeque<Double>()
    private var previousAccepted: Location? = null

    fun reset() {
        recentSpeeds.clear()
        previousAccepted = null
    }

    fun process(location: Location): Result {
        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > maxAccuracyMeters) {
            return Result(false, reason = "Waiting for accurate GPS…")
        }

        val previous = previousAccepted
        val dtSeconds = previous?.let { (location.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1_000_000_000.0 } ?: 0.0
        val gpsSpeedMps = if (location.hasSpeed() && location.speed >= 0f) location.speed.toDouble() else null
        val derivedSpeedMps = if (previous != null && dtSeconds > 0.5) {
            previous.distanceTo(location).toDouble() / dtSeconds
        } else null

        val selectedMps = gpsSpeedMps?.takeIf { it.isFinite() } ?: derivedSpeedMps
        if (selectedMps == null) {
            previousAccepted = Location(location)
            return Result(false, reason = "Waiting for speed fix…")
        }

        val rawKmh = selectedMps * 3.6
        if (!rawKmh.isFinite() || rawKmh < 0.0 || rawKmh > maxWalkingRunningSpeedKmh) {
            return Result(false, reason = "Ignoring implausible GPS reading")
        }

        var distanceIncrement = 0.0
        if (previous != null && dtSeconds in 0.5..15.0) {
            val distance = previous.distanceTo(location).toDouble()
            val impliedKmh = distance / dtSeconds * 3.6
            val accuracyAllowance = max(previous.accuracy, location.accuracy) * 2.0
            val maxPlausibleDistance = (maxWalkingRunningSpeedKmh / 3.6) * dtSeconds + accuracyAllowance
            if (distance <= maxPlausibleDistance && impliedKmh <= maxWalkingRunningSpeedKmh + 5.0) {
                distanceIncrement = distance
            }
        }

        previousAccepted = Location(location)
        recentSpeeds.addLast(rawKmh)
        while (recentSpeeds.size > smoothingWindow) recentSpeeds.removeFirst()

        // Trimmed rolling mean when enough points exist, otherwise ordinary mean.
        val sorted = recentSpeeds.sorted()
        val usable = if (sorted.size >= 5) sorted.drop(1).dropLast(1) else sorted
        val smoothed = usable.average()

        return Result(
            valid = true,
            rawSpeedKmh = rawKmh,
            smoothedSpeedKmh = smoothed,
            distanceIncrementMeters = distanceIncrement,
            reason = "GPS ${location.accuracy.toInt()} m"
        )
    }
}
