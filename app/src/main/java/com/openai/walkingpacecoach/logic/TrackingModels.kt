package com.openai.walkingpacecoach.logic

enum class AlertState {
    WAITING_FOR_GPS,
    ON_TARGET,
    BELOW_TARGET_PENDING,
    BELOW_TARGET_ALERTING,
    PAUSED
}

data class TrackingConfig(
    val targetSpeedKmh: Double = 5.0,
    val vibrationEnabled: Boolean = true,
    val warningIntervalSeconds: Int = 5,
    val firstWarningDelaySeconds: Int = 4,
    val alertWhileStopped: Boolean = false,
    val stationaryPauseSeconds: Int = 10,
    val vibrationPattern: VibrationPattern = VibrationPattern.DOUBLE
)

enum class VibrationPattern { LIGHT, DOUBLE, STRONG }

data class TrackingSnapshot(
    val isActive: Boolean = false,
    val isManuallyPaused: Boolean = false,
    val alertState: AlertState = AlertState.WAITING_FOR_GPS,
    val gpsMessage: String = "GPS idle",
    val currentSpeedKmh: Double? = null,
    val smoothedSpeedKmh: Double? = null,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val elapsedMs: Long = 0,
    val targetSpeedKmh: Double = 5.0,
    val timeAtOrAboveTargetMs: Long = 0,
    val timeBelowTargetMs: Long = 0,
    val warningCount: Int = 0,
    val longestTargetStreakMs: Long = 0,
    val longestBelowTargetMs: Long = 0,
    val lastAccuracyMeters: Float? = null
)
