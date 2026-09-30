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
import android.location.LocationManager
import android.location.LocationListener
import android.os.PowerManager
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

        const val CHANNEL_ID = "walking_tracking_v2"
        const val ALERT_CHANNEL_ID = "walking_pace_alerts_v2"
        const val ALERT_NOTIFICATION_ID = 4402
        const val ACTION_TEST_ALERT = "com.openai.walkingpacecoach.TEST_ALERT"
        private const val NOTIFICATION_ID = 4401

        private val _snapshot = MutableStateFlow(TrackingSnapshot())
        val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

        private val _lastCompletedWorkoutId = MutableStateFlow<Long?>(null)
        val lastCompletedWorkoutId: StateFlow<Long?> = _lastCompletedWorkoutId.asStateFlow()

        fun clearLastCompletedWorkoutId() { _lastCompletedWorkoutId.value = null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewedAtMs = 0L
    private var lastGpsFixMs = 0L
    private var finishing = false
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

    private val locationCallback = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            val now = SystemClock.elapsedRealtime()
            if (location.provider == LocationManager.GPS_PROVIDER) lastGpsFixMs = now
            // Prefer GNSS; network fixes are fallback only and still pass accuracy checks.
            if (location.provider != LocationManager.GPS_PROVIDER && now - lastGpsFixMs < 6_000L) return
            handleLocation(location)
        }
        override fun onProviderEnabled(provider: String) { requestLocationUpdates() }
        override fun onProviderDisabled(provider: String) {
            processor.reset()
            publish(currentSnapshot().copy(gpsMessage = "GPS disabled — enable Location"))
        }
        @Deprecated("Legacy callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        vibrator = VibrationController(this)
        createNotificationChannel()
        scope.launch {
            while (isActive) {
                delay(2_000L)
                if (isRunning && !manuallyPaused) {
                    acquireTrackingWakeLock()
                    publish(currentSnapshot())
                }
                val last = lastLocationReceivedElapsedMs
                if (isRunning && !manuallyPaused && last != null && SystemClock.elapsedRealtime() - last > 6_000L) {
                    vibrator.cancel()
                    clearPaceAlert()
                    processor.reset()
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
        if (intent?.action == ACTION_TEST_ALERT) {
            startAsForeground()
            scope.launch {
                delay(intent.getLongExtra("testDelayMs", 0L).coerceIn(0L, 10_000L))
                showPaceAlert(test = true)
                vibrator.warn(VibrationPattern.STRONG)
                if (!isRunning) {
                    ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startTracking(configFromIntent(intent))
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_FINISH -> finishTracking(save = true)
            ACTION_STOP_ONLY -> finishTracking(save = false)
            else -> if (prefs.getBoolean("active", false) && !isRunning) restoreAfterProcessRecreation()
        }
        return if (isRunning) START_STICKY else START_NOT_STICKY
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
        if (isRunning || finishing) return
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

        try {
            startAsForeground()
            if (!manuallyPaused) acquireTrackingWakeLock()
        } catch (_: RuntimeException) {
            isRunning = false
            prefs.edit().clear().apply()
            publish(currentSnapshot().copy(gpsMessage = "Reopen the app and grant precise location to restart tracking"))
            stopSelf()
            return
        }
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
        if (manuallyPaused) pauseStartedElapsedMs = SystemClock.elapsedRealtime()
        isRunning = true
        try {
            startAsForeground()
            if (!manuallyPaused) acquireTrackingWakeLock()
        } catch (_: RuntimeException) {
            isRunning = false
            prefs.edit().clear().apply()
            publish(currentSnapshot().copy(gpsMessage = "Reopen the app and grant precise location to restart tracking"))
            stopSelf()
            return
        }
        requestLocationUpdates()
        publish(
            TrackingSnapshot(
                isActive = true,
                isManuallyPaused = manuallyPaused,
                alertState = if (manuallyPaused) AlertState.PAUSED else AlertState.WAITING_FOR_GPS,
                gpsMessage = if (manuallyPaused) "Workout paused" else "Restored — waiting for accurate GPS…",
                distanceMeters = distanceMeters,
                elapsedMs = restoredBaseElapsedMs,
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
        clearPaceAlert()
        releaseTrackingWakeLock()
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
        acquireTrackingWakeLock()
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
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs < 0L || ageMs > 6_000L) return
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
            clearPaceAlert()
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
        if (decision.shouldVibrate) {
            showPaceAlert()
            if (config.vibrationEnabled) vibrator.warn(config.vibrationPattern)
            warningCount++
        }
        if (decision.state != AlertState.BELOW_TARGET_ALERTING) {
            vibrator.cancel()
            clearPaceAlert()
        }

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
        finishing = save
        releaseTrackingWakeLock()
        clearPaceAlert()
        vibrator.cancel()
        locationManager.removeUpdates(locationCallback)
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
                try {
                    val id = app.repository.saveWorkout(workout, samplesCopy)
                    _lastCompletedWorkoutId.value = id
                } finally {
                    finishing = false
                    stopSelf()
                }
            }
            return
        }

        publish(finalSnapshot.copy(isActive = false, isManuallyPaused = false, gpsMessage = "Workout complete"))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestLocationUpdates() {
        if (!isRunning || !hasLocationPermission()) return
        try {
            locationManager.removeUpdates(locationCallback)
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { locationManager.allProviders.contains(it) && locationManager.isProviderEnabled(it) }
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 1_000L, 0f, locationCallback, mainLooper)
            }
            if (providers.isEmpty()) publish(currentSnapshot().copy(gpsMessage = "Enable phone Location/GPS"))
        } catch (_: SecurityException) {
            publish(currentSnapshot().copy(gpsMessage = "Precise location permission is needed"))
            finishTracking(save = true)
        }
    }

    private fun acquireTrackingWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (wakeLock?.isHeld == true && now - wakeRenewedAtMs < 5 * 60 * 1000L) return
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WalkingPaceCoach:ActiveWalk")
            .also { it.setReferenceCounted(false); wakeLock = it }
        // Bounded lease, renewed only while a user-started workout is active.
        lock.acquire(10 * 60 * 1000L)
        wakeRenewedAtMs = now
    }

    private fun releaseTrackingWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun clearPaceAlert() {
        getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
    }

    private fun showPaceAlert(test: Boolean = false) {
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val speed = _snapshot.value.smoothedSpeedKmh?.let { "%.1f".format(it) } ?: "—"
        val text = if (test) "Test alert: check vibration and lock-screen notifications" else
            "Keep the pace: $speed km/h; target ${"%.1f".format(config.targetSpeedKmh)} km/h"
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (test) "Walking Pace Coach — test" else "Increase your walking speed")
            .setContentText(text).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false).setAutoCancel(true).setTimeoutAfter(30_000L)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "Active walk / background GPS", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent tracking controls while a walk is active"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID,
            "Slowdown reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Visible pace reminders; vibration is controlled by the app setting"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the activity is not finishing the user-started workout.
        if (isRunning) persistActiveState()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseTrackingWakeLock()
        clearPaceAlert()
        if (isRunning) {
            runCatching { locationManager.removeUpdates(locationCallback) }
            vibrator.cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

