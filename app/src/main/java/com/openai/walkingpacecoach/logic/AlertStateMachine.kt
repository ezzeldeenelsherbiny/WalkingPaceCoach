package com.openai.walkingpacecoach.logic

class AlertStateMachine {
    private var belowSinceMs: Long? = null
    private var stationarySinceMs: Long? = null
    private var lastWarningMs: Long? = null

    data class Decision(
        val state: AlertState,
        val shouldVibrate: Boolean,
        val autoSuppressedForStop: Boolean
    )

    fun reset() {
        belowSinceMs = null
        stationarySinceMs = null
        lastWarningMs = null
    }

    fun update(
        nowMs: Long,
        validSpeedKmh: Double?,
        config: TrackingConfig,
        manuallyPaused: Boolean
    ): Decision {
        if (manuallyPaused) {
            belowSinceMs = null
            stationarySinceMs = null
            lastWarningMs = null
            return Decision(AlertState.PAUSED, false, false)
        }

        if (validSpeedKmh == null) {
            belowSinceMs = null
            stationarySinceMs = null
            lastWarningMs = null
            return Decision(AlertState.WAITING_FOR_GPS, false, false)
        }

        val stationaryThresholdKmh = 0.6
        if (validSpeedKmh <= stationaryThresholdKmh) {
            if (stationarySinceMs == null) stationarySinceMs = nowMs
        } else {
            stationarySinceMs = null
        }

        val stationaryLongEnough = stationarySinceMs?.let {
            nowMs - it >= config.stationaryPauseSeconds * 1000L
        } == true

        if (!config.alertWhileStopped && stationaryLongEnough) {
            belowSinceMs = null
            lastWarningMs = null
            return Decision(AlertState.PAUSED, false, true)
        }

        val hysteresisKmh = 0.15
        val enterBelow = config.targetSpeedKmh - hysteresisKmh
        val exitBelow = config.targetSpeedKmh + hysteresisKmh

        val currentlyBelowFlow = belowSinceMs != null || lastWarningMs != null
        val isBelow = if (currentlyBelowFlow) validSpeedKmh < exitBelow else validSpeedKmh < enterBelow

        if (!isBelow) {
            belowSinceMs = null
            lastWarningMs = null
            return Decision(AlertState.ON_TARGET, false, false)
        }

        if (belowSinceMs == null) belowSinceMs = nowMs
        val pendingFor = nowMs - (belowSinceMs ?: nowMs)
        if (pendingFor < config.firstWarningDelaySeconds * 1000L) {
            return Decision(AlertState.BELOW_TARGET_PENDING, false, false)
        }

        val shouldWarn = lastWarningMs == null ||
            nowMs - (lastWarningMs ?: 0L) >= config.warningIntervalSeconds * 1000L
        if (shouldWarn) lastWarningMs = nowMs
        return Decision(AlertState.BELOW_TARGET_ALERTING, shouldWarn, false)
    }
}
