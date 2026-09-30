package com.openai.walkingpacecoach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.openai.walkingpacecoach.logic.TrackingConfig
import com.openai.walkingpacecoach.service.TrackingService
import com.openai.walkingpacecoach.ui.MainViewModel
import com.openai.walkingpacecoach.ui.WalkingPaceCoachUi

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingStartConfig: TrackingConfig? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            requestNotificationThenStart()
        } else {
            pendingStartConfig = null
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startPendingWorkout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WalkingPaceCoachUi(
                viewModel = viewModel,
                onStartWorkout = ::beginStartFlow,
                onPause = { sendServiceAction(TrackingService.ACTION_PAUSE) },
                onResume = { sendServiceAction(TrackingService.ACTION_RESUME) },
                onFinish = { sendServiceAction(TrackingService.ACTION_FINISH) }
            )
        }
    }

    private fun beginStartFlow(config: TrackingConfig) {
        if (config.targetSpeedKmh <= 0.0 || !config.targetSpeedKmh.isFinite()) return
        pendingStartConfig = config
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            requestNotificationThenStart()
        }
    }

    private fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPendingWorkout()
        }
    }

    private fun startPendingWorkout() {
        val config = pendingStartConfig ?: return
        pendingStartConfig = null
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_TARGET, config.targetSpeedKmh)
            putExtra(TrackingService.EXTRA_VIBRATION, config.vibrationEnabled)
            putExtra(TrackingService.EXTRA_WARNING_INTERVAL, config.warningIntervalSeconds)
            putExtra(TrackingService.EXTRA_FIRST_DELAY, config.firstWarningDelaySeconds)
            putExtra(TrackingService.EXTRA_ALERT_STOPPED, config.alertWhileStopped)
            putExtra(TrackingService.EXTRA_STATIONARY_SECONDS, config.stationaryPauseSeconds)
            putExtra(TrackingService.EXTRA_PATTERN, config.vibrationPattern.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, TrackingService::class.java).setAction(action))
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
