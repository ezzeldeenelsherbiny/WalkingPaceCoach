package com.openai.walkingpacecoach

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openai.walkingpacecoach.logic.TrackingConfig
import com.openai.walkingpacecoach.logic.VibrationController
import com.openai.walkingpacecoach.service.TrackingService
import com.openai.walkingpacecoach.ui.MainViewModel
import com.openai.walkingpacecoach.ui.SetupScreen
import com.openai.walkingpacecoach.ui.WalkingPaceCoachUi

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingStartConfig: TrackingConfig? = null
    private val refresh = mutableIntStateOf(0)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh.intValue++
        if (hasPreciseLocation() && pendingStartConfig != null) requestNotificationThenStart()
        else if (!hasPreciseLocation()) {
            pendingStartConfig = null
            explain("Precise location needed", "Choose Precise location in Permissions → Location. Approximate location cannot reliably measure walking speed.")
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh.intValue++
        if (granted) startPendingWorkout()
        else {
            pendingStartConfig = null
            explain("Notifications are disabled", "Enable notifications so you can see tracking controls and slowdown reminders when the screen is locked.")
        }
    }
    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val update = refresh.intValue
            WalkingPaceCoachUi(viewModel, ::beginStartFlow,
                { sendServiceAction(TrackingService.ACTION_PAUSE) },
                { sendServiceAction(TrackingService.ACTION_RESUME) },
                { sendServiceAction(TrackingService.ACTION_FINISH) },
                setup = {
                    SetupScreen(this, update,
                        onLocation = ::requestPreciseLocation,
                        onNotifications = ::enableNotifications,
                        onGps = { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        onBattery = ::requestBatteryExemption,
                        onBackgroundLocation = ::requestBackgroundLocation,
                        onAppSettings = ::openAppSettings,
                        onTest = ::testAlert)
                })
        }
    }
    override fun onResume() { super.onResume(); refresh.intValue++ }

    private fun hasPreciseLocation() = ContextCompat.checkSelfPermission(this,
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestPreciseLocation() {
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun beginStartFlow(config: TrackingConfig) {
        if (config.targetSpeedKmh <= 0.0 || !config.targetSpeedKmh.isFinite()) return
        if (!getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return
        }
        pendingStartConfig = config
        if (!hasPreciseLocation()) requestPreciseLocation() else requestNotificationThenStart()
    }
    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun requestNotificationThenStart() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) enableNotifications()
        else startPendingWorkout()
    }
    private fun startPendingWorkout() {
        val config = pendingStartConfig ?: return
        if (!hasPreciseLocation() || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return
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
        try { ContextCompat.startForegroundService(this, intent) }
        catch (_: RuntimeException) { explain("Cannot start tracking", "Check Location and battery settings, then start the walk again with the app open.") }
    }
    private fun sendServiceAction(action: String) {
        startService(Intent(this, TrackingService::class.java).setAction(action))
    }
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this).setTitle("Allow screen-off pace tracking")
            .setMessage("Continuous GPS and timely vibrations use battery. Allow this app to run without battery optimization during your walks. On some phones also choose Unrestricted battery and enable Auto-start in App settings.")
            .setPositiveButton("Continue") { _, _ -> openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"))) }
            .setNegativeButton("Later", null).show()
    }
    private fun requestBackgroundLocation() {
        if (!hasPreciseLocation()) { requestPreciseLocation(); return }
        if (Build.VERSION.SDK_INT < 29) return
        AlertDialog.Builder(this).setTitle("Optional recovery permission")
            .setMessage("Allow all the time lets an active walk recover location access if Android recreates its service. A normal walk started with the app open already uses a foreground location service. Location is collected only during a walk.")
            .setPositiveButton("Continue") { _, _ ->
                if (Build.VERSION.SDK_INT == 29) backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else openAppSettings()
            }.setNegativeButton("Later", null).show()
    }
    private fun testAlert() {
        if (!hasPreciseLocation()) { requestPreciseLocation(); return }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) { enableNotifications(); return }
        val intent = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_TEST_ALERT)
            .putExtra("testDelayMs", 5_000L)
        ContextCompat.startForegroundService(this, intent)
        AlertDialog.Builder(this).setTitle("Test in 5 seconds")
            .setMessage("Lock your screen now. Expect a slowdown test notification and a strong double vibration. If vibration is missing, check Do Not Disturb and system vibration settings.")
            .setPositiveButton("OK", null).show()
    }
    private fun explain(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Later", null).show()
    }
    private fun openAppSettings() = openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName")))
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: RuntimeException) {
            android.widget.Toast.makeText(this, "Open this app's permissions and battery settings in your phone Settings", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
