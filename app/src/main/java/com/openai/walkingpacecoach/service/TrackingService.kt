package com.openai.walkingpacecoach.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openai.walkingpacecoach.MainActivity
import com.openai.walkingpacecoach.R
import com.openai.walkingpacecoach.WalkingPaceCoachApp
import com.openai.walkingpacecoach.data.SpeedSampleDraft
import com.openai.walkingpacecoach.data.WorkoutEntity
import com.openai.walkingpacecoach.logic.AlertState
import com.openai.walkingpacecoach.logic.AlertStateMachine
import com.openai.walkingpacecoach.logic.SpeedProcessor
import com.openai.walkingpacecoach.logic.TrackingConfig
import com.openai.walkingpacecoach.logic.TrackingSnapshot
import com.openai.walkingpacecoach.logic.VibrationController
import com.openai.walkingpacecoach.logic.VibrationPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "com.openai.walkingpacecoach.START"
        const val ACTION_PAUSE = "com.openai.walkingpacecoach.PAUSE"
        const val ACTION_RESUME = "com.openai.walkingpacecoach.RESUME"
        const val ACTION_FINISH = "com.openai.walkingpacecoach.FINISH"
        const val ACTION_STOP_ONLY = "com.openai.walkingpacecoach.STOP_ONLY"

        const val EXTRA_TARGET = "target"
        const val EXTRA_VIBRATION = "vibration"
        const val EXTRA_WARNING_INTERVAL = "warning_interval"
        const val EXTRA_FIRST_DELAY = "first_delay"
        const val EXTRA_ALERT_STOPPED = "alert_stopped"
        const val EXTRA_STATIONARY_SECONDS = "stationary_seconds"
        const val EXTRA_PATTERN = "pattern"

        private const val CHANNEL_ID = "walking_tracking"
        private const val NOTIFICATION_ID = 4401

        private val _snapshot = MutableStateFlow(TrackingSnapshot())
        val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

        private val _lastCompletedWorkoutId = MutableStateFlow<Long?>(null)
        val lastCompletedWorkoutId: StateFlow<Long?> = _lastCompletedWorkoutId.asStateFlow()

        fun clearLastCompletedWorkoutId() { _lastCompletedWorkoutId.value = null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var vibrator: VibrationController
    private val processor = SpeedProcessor()
    private val alertMachine = AlertStateMachine()
    private val samples = mutableListOf<SpeedSampleDraft>()

    private var config = TrackingConfig()
    private var startedAtWallMs = 0L
    private var startedAtElapsedMs = 0L
    private var accumulatedPauseMs = 0L
    private var restoredBaseElapsedMs = 0L
    private var pauseStartedElapsedMs: Long? = null
    private var lastStatsElapsedMs: Long? = null
    private var distanceMeters = 0.0
    private var maxSpeedKmh = 0.0
    private var atTargetMs = 0L
    private var belowTargetMs = 0L
    private var currentTargetStreakMs = 0L
    private var currentBelowStreakMs = 0L
    private var longestTargetStreakMs = 0L
    private var longestBelowStreakMs = 0L
    private var warningCount = 0
    private var manuallyPaused = false
    private var isRunning = false
    private var lastLocationReceivedElapsedMs: Long? = null

    private val prefs by lazy { getSharedPreferences("active_workout", Context.MODE_PRIVATE) }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleLocation(location)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        vibrator = VibrationController(this)
        createNotificationChannel()
        scope.launch {
            while (isActive) {
                delay(2_000L)
                val last = lastLocationReceivedElapsedMs
                if (isRunning && !manuallyPaused && last != null && SystemClock.elapsedRealtime() - last > 6_000L) {
                    vibrator.cancel()
                    alertMachine.update(SystemClock.elapsedRealtime(), null, config, manuallyPaused = false)
                    lastStatsElapsedMs = null
                    publish(currentSnapshot().copy(
                        alertState = AlertState.WAITING_FOR_GPS,
                        gpsMessage = "Waiting for accurate GPS…",
                        currentSpeedKmh = null,
                        smoothedSpeedKmh = null
                    ))
                    updateNotification()
                    lastLocationReceivedElapsedMs = null
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(configFromIntent(intent))
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_FINISH -> finishTracking(save = true)
            ACTION_STOP_ONLY -> finishTracking(save = false)
            else -> if (prefs.getBoolean("active", false) && !isRunning) restoreAfterProcessRecreation()
        }
        return START_STICKY
    }

    private fun configFromIntent(intent: Intent) = TrackingConfig(
        targetSpeedKmh = intent.getDoubleExtra(EXTRA_TARGET, 5.0),
        vibrationEnabled = intent.getBooleanExtra(EXTRA_VIBRATION, true),
        warningIntervalSeconds = intent.getIntExtra(EXTRA_WARNING_INTERVAL, 5).coerceIn(2, 60),
        firstWarningDelaySeconds = intent.getIntExtra(EXTRA_FIRST_DELAY, 4).coerceIn(1, 30),
        alertWhileStopped = intent.getBooleanExtra(EXTRA_ALERT_STOPPED, false),
        stationaryPauseSeconds = intent.getIntExtra(EXTRA_STATIONARY_SECONDS, 10).coerceIn(3, 120),
        vibrationPattern = runCatching {
            VibrationPattern.valueOf(intent.getStringExtra(EXTRA_PATTERN) ?: VibrationPattern.DOUBLE.name)
        }.getOrDefault(VibrationPattern.DOUBLE)
    )

    private fun startTracking(newConfig: TrackingConfig) {
        if (isRunning) return
        if (newConfig.targetSpeedKmh <= 0.0 || !newConfig.targetSpeedKmh.isFinite()) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        resetSession()
        config = newConfig
        startedAtWallMs = System.currentTimeMillis()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        isRunning = true
        persistActiveState()

        startAsForeground()
        requestLocationUpdates()
        publish(
            TrackingSnapshot(
                isActive = true,
                alertState = AlertState.WAITING_FOR_GPS,
                gpsMessage = "Waiting for accurate GPS…",
                targetSpeedKmh = config.targetSpeedKmh
            )
        )
    }

    private fun restoreAfterProcessRecreation() {
        if (!hasLocationPermission()) {
            prefs.edit().clear().apply()
            stopSelf()
            return
        }
        config = TrackingConfig(
            targetSpeedKmh = prefs.getFloat("target", 5f).toDouble(),
            vibrationEnabled = prefs.getBoolean("vibration", true),
            warningIntervalSeconds = prefs.getInt("interval", 5),
            firstWarningDelaySeconds = prefs.getInt("delay", 4),
            alertWhileStopped = prefs.getBoolean("alertStopped", false),
            stationaryPauseSeconds = prefs.getInt("stationary", 10),
            vibrationPattern = runCatching { VibrationPattern.valueOf(prefs.getString("pattern", "DOUBLE") ?: "DOUBLE") }
                .getOrDefault(VibrationPattern.DOUBLE)
        )
        startedAtWallMs = prefs.getLong("startedWall", System.currentTimeMillis())
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        distanceMeters = prefs.getFloat("distance", 0f).toDouble()
        maxSpeedKmh = prefs.getFloat("maxSpeed", 0f).toDouble()
        restoredBaseElapsedMs = prefs.getLong("elapsed", 0L)
        atTargetMs = prefs.getLong("atTarget", 0L)
        belowTargetMs = prefs.getLong("belowTarget", 0L)
        warningCount = prefs.getInt("warnings", 0)
        longestTargetStreakMs = prefs.getLong("longestTarget", 0L)
        longestBelowStreakMs = prefs.getLong("longestBelow", 0L)
        manuallyPaused = prefs.getBoolean("paused", false)
        isRunning = true
        startAsForeground()
        requestLocationUpdates()
        publish(
            TrackingSnapshot(
                isActive = true,
                isManuallyPaused = manuallyPaused,
                alertState = if (manuallyPaused) AlertState.PAUSED else AlertState.WAITING_FOR_GPS,
                gpsMessage = if (manuallyPaused) "Workout paused" else "Restored — waiting for accurate GPS…",
                distanceMeters = distanceMeters,
                elapsedMs = atTargetMs + belowTargetMs,
                targetSpeedKmh = config.targetSpeedKmh,
                timeAtOrAboveTargetMs = atTargetMs,
                timeBelowTargetMs = belowTargetMs,
                warningCount = warningCount,
                longestTargetStreakMs = longestTargetStreakMs,
                longestBelowTargetMs = longestBelowStreakMs
            )
        )
    }

    private fun resetSession() {
        processor.reset()
        alertMachine.reset()
        samples.clear()
        accumulatedPauseMs = 0L
        restoredBaseElapsedMs = 0L
        pauseStartedElapsedMs = null
        lastStatsElapsedMs = null
        distanceMeters = 0.0
        maxSpeedKmh = 0.0
        atTargetMs = 0L
        belowTargetMs = 0L
        currentTargetStreakMs = 0L
        currentBelowStreakMs = 0L
        longestTargetStreakMs = 0L
        longestBelowStreakMs = 0L
        warningCount = 0
        manuallyPaused = false
        lastLocationReceivedElapsedMs = null
    }

    private fun pauseTracking() {
        if (!isRunning || manuallyPaused) return
        manuallyPaused = true
        pauseStartedElapsedMs = SystemClock.elapsedRealtime()
        lastStatsElapsedMs = null
        vibrator.cancel()
        alertMachine.reset()
        persistActiveState()
        publish(currentSnapshot().copy(
            isManuallyPaused = true,
            alertState = AlertState.PAUSED,
            gpsMessage = "Workout paused",
            currentSpeedKmh = null,
            smoothedSpeedKmh = null
        ))
        updateNotification()
    }

    private fun resumeTracking() {
        if (!isRunning || !manuallyPaused) return
        val now = SystemClock.elapsedRealtime()
        pauseStartedElapsedMs?.let { accumulatedPauseMs += now - it }
        pauseStartedElapsedMs = null
        manuallyPaused = false
        lastStatsElapsedMs = null
        processor.reset()
        alertMachine.reset()
        persistActiveState()
        publish(currentSnapshot().copy(
            isManuallyPaused = false,
            alertState = AlertState.WAITING_FOR_GPS,
            gpsMessage = "Waiting for accurate GPS…",
            currentSpeedKmh = null,
            smoothedSpeedKmh = null
        ))
        updateNotification()
    }

    private fun handleLocation(location: android.location.Location) {
        if (!isRunning) return
        val now = SystemClock.elapsedRealtime()
        lastLocationReceivedElapsedMs = now
        if (manuallyPaused) {
            publish(currentSnapshot().copy(
                isManuallyPaused = true,
                alertState = AlertState.PAUSED,
                gpsMessage = "Workout paused",
                lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            ))
            return
        }

        val result = processor.process(location)
        if (!result.valid || result.smoothedSpeedKmh == null) {
            vibrator.cancel()
            alertMachine.update(now, null, config, manuallyPaused = false)
            lastStatsElapsedMs = null
            publish(currentSnapshot().copy(
                alertState = AlertState.WAITING_FOR_GPS,
                gpsMessage = result.reason.ifBlank { "Waiting for accurate GPS…" },
                currentSpeedKmh = null,
                smoothedSpeedKmh = null,
                lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            ))
            updateNotification()
            return
        }

        val smoothed = result.smoothedSpeedKmh
        val raw = result.rawSpeedKmh ?: smoothed
        distanceMeters += result.distanceIncrementMeters
        maxSpeedKmh = max(maxSpeedKmh, smoothed)

        val decision = alertMachine.update(now, smoothed, config, manuallyPaused = false)
        if (decision.shouldVibrate && config.vibrationEnabled) {
            vibrator.warn(config.vibrationPattern)
            warningCount++
        }
        if (decision.state != AlertState.BELOW_TARGET_ALERTING) vibrator.cancel()

        val previousTick = lastStatsElapsedMs
        if (previousTick != null) {
            val dt = (now - previousTick).coerceIn(0L, 5_000L)
            when {
                decision.autoSuppressedForStop -> {
                    currentTargetStreakMs = 0L
                    currentBelowStreakMs = 0L
                }
                smoothed >= config.targetSpeedKmh -> {
                    atTargetMs += dt
                    currentTargetStreakMs += dt
                    currentBelowStreakMs = 0L
                    longestTargetStreakMs = max(longestTargetStreakMs, currentTargetStreakMs)
                }
                else -> {
                    belowTargetMs += dt
                    currentBelowStreakMs += dt
                    currentTargetStreakMs = 0L
                    longestBelowStreakMs = max(longestBelowStreakMs, currentBelowStreakMs)
                }
            }
        }
        lastStatsElapsedMs = now

        val elapsed = activeElapsedMs(now)
        if (samples.isEmpty() || elapsed - samples.last().elapsedMs >= 900L) {
            samples += SpeedSampleDraft(elapsed, smoothed, location.accuracy)
        }

        val average = if (elapsed > 0L) (distanceMeters / 1000.0) / (elapsed / 3_600_000.0) else 0.0
        val gpsMessage = when {
            decision.autoSuppressedForStop -> "Stopped — pace alerts paused"
            else -> result.reason
        }

        publish(
            TrackingSnapshot(
                isActive = true,
                isManuallyPaused = false,
                alertState = decision.state,
                gpsMessage = gpsMessage,
                currentSpeedKmh = raw,
                smoothedSpeedKmh = smoothed,
                averageSpeedKmh = average,
                maxSpeedKmh = maxSpeedKmh,
                distanceMeters = distanceMeters,
                elapsedMs = elapsed,
                targetSpeedKmh = config.targetSpeedKmh,
                timeAtOrAboveTargetMs = atTargetMs,
                timeBelowTargetMs = belowTargetMs,
                warningCount = warningCount,
                longestTargetStreakMs = longestTargetStreakMs,
                longestBelowTargetMs = longestBelowStreakMs,
                lastAccuracyMeters = location.accuracy
            )
        )
        persistActiveState()
        updateNotification()
    }

    private fun activeElapsedMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val livePause = pauseStartedElapsedMs?.let { now - it } ?: 0L
        return (restoredBaseElapsedMs + now - startedAtElapsedMs - accumulatedPauseMs - livePause).coerceAtLeast(0L)
    }

    private fun currentSnapshot(): TrackingSnapshot {
        val elapsed = activeElapsedMs()
        val average = if (elapsed > 0L) (distanceMeters / 1000.0) / (elapsed / 3_600_000.0) else 0.0
        return _snapshot.value.copy(
            isActive = isRunning,
            isManuallyPaused = manuallyPaused,
            distanceMeters = distanceMeters,
            elapsedMs = elapsed,
            targetSpeedKmh = config.targetSpeedKmh,
            averageSpeedKmh = average,
            timeAtOrAboveTargetMs = atTargetMs,
            timeBelowTargetMs = belowTargetMs,
            warningCount = warningCount,
            longestTargetStreakMs = longestTargetStreakMs,
            longestBelowTargetMs = longestBelowStreakMs,
            maxSpeedKmh = maxSpeedKmh
        )
    }

    private fun finishTracking(save: Boolean) {
        if (!isRunning) {
            stopSelf()
            return
        }
        val finalSnapshot = currentSnapshot()
        isRunning = false
        vibrator.cancel()
        fusedClient.removeLocationUpdates(locationCallback)
        prefs.edit().clear().apply()

        if (save && finalSnapshot.elapsedMs > 0L) {
            val app = application as WalkingPaceCoachApp
            val workout = WorkoutEntity(
                startedAtEpochMs = startedAtWallMs,
                endedAtEpochMs = System.currentTimeMillis(),
                durationMs = finalSnapshot.elapsedMs,
                distanceMeters = distanceMeters,
                averageSpeedKmh = finalSnapshot.averageSpeedKmh,
                maxSpeedKmh = maxSpeedKmh,
                targetSpeedKmh = config.targetSpeedKmh,
                timeAtOrAboveTargetMs = atTargetMs,
                timeBelowTargetMs = belowTargetMs,
                warningCount = warningCount,
                longestTargetStreakMs = longestTargetStreakMs,
                longestBelowTargetMs = longestBelowStreakMs
            )
            val samplesCopy = samples.toList()
            publish(finalSnapshot.copy(isActive = false, isManuallyPaused = false, gpsMessage = "Workout complete"))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            scope.launch {
                val id = app.repository.saveWorkout(workout, samplesCopy)
                _lastCompletedWorkoutId.value = id
                stopSelf()
            }
            return
        }

        publish(finalSnapshot.copy(isActive = false, isManuallyPaused = false, gpsMessage = "Workout complete"))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(700L)
            .setMaxUpdateDelayMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            publish(currentSnapshot().copy(
                alertState = AlertState.WAITING_FOR_GPS,
                gpsMessage = "Location permission unavailable"
            ))
        }
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active walking workout",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while GPS pace tracking is active"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseAction = if (manuallyPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel = if (manuallyPaused) "Resume" else "Pause"
        val pausePending = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(pauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val finishPending = PendingIntent.getService(
            this,
            2,
            Intent(this, TrackingService::class.java).setAction(ACTION_FINISH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val s = _snapshot.value
        val speedText = s.smoothedSpeedKmh?.let { String.format("%.1f km/h", it) } ?: "Waiting for GPS"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Walking Pace Coach")
            .setContentText("$speedText • target ${String.format("%.1f", config.targetSpeedKmh)} km/h")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, pauseLabel, pausePending)
            .addAction(0, "Finish", finishPending)
            .build()
    }

    private fun updateNotification() {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun persistActiveState() {
        if (!isRunning) return
        prefs.edit()
            .putBoolean("active", true)
            .putFloat("target", config.targetSpeedKmh.toFloat())
            .putBoolean("vibration", config.vibrationEnabled)
            .putInt("interval", config.warningIntervalSeconds)
            .putInt("delay", config.firstWarningDelaySeconds)
            .putBoolean("alertStopped", config.alertWhileStopped)
            .putInt("stationary", config.stationaryPauseSeconds)
            .putString("pattern", config.vibrationPattern.name)
            .putLong("startedWall", startedAtWallMs)
            .putFloat("distance", distanceMeters.toFloat())
            .putFloat("maxSpeed", maxSpeedKmh.toFloat())
            .putLong("elapsed", activeElapsedMs())
            .putLong("atTarget", atTargetMs)
            .putLong("belowTarget", belowTargetMs)
            .putInt("warnings", warningCount)
            .putLong("longestTarget", longestTargetStreakMs)
            .putLong("longestBelow", longestBelowStreakMs)
            .putBoolean("paused", manuallyPaused)
            .apply()
    }

    private fun publish(snapshot: TrackingSnapshot) {
        _snapshot.value = snapshot
    }

    override fun onDestroy() {
        if (isRunning) {
            runCatching { fusedClient.removeLocationUpdates(locationCallback) }
            vibrator.cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
