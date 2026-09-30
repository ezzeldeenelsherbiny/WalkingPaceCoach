package com.openai.walkingpacecoach.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openai.walkingpacecoach.logic.VibrationController
import com.openai.walkingpacecoach.service.TrackingService

@Composable
fun SetupScreen(context: Context, refresh: Int, onLocation: () -> Unit,
    onNotifications: () -> Unit, onGps: () -> Unit, onBattery: () -> Unit,
    onBackgroundLocation: () -> Unit, onAppSettings: () -> Unit, onTest: () -> Unit) {
    // Reading refresh forces fresh status checks after returning from Android Settings.
    @Suppress("UNUSED_VARIABLE") val version = refresh
    val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(NotificationManager::class.java)
    val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val trackingChannel = manager.getNotificationChannel(TrackingService.CHANNEL_ID)
    val alertChannel = manager.getNotificationChannel(TrackingService.ALERT_CHANNEL_ID)
    val channelsEnabled = listOfNotNull(trackingChannel, alertChannel).all { it.importance != NotificationManager.IMPORTANCE_NONE }
    val gps = context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
    val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Phone setup & alert test", style = MaterialTheme.typography.headlineSmall)
        Text("${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}")
        Spacer(Modifier.height(16.dp))
        SetupRow("Precise location", precise, "Allow Precise location", onLocation)
        SetupRow("GPS / Location enabled", gps, "Turn on Location", onGps)
        SetupRow("Notifications and channels", notifications && channelsEnabled, "Notification settings", onNotifications)
        SetupRow("Screen-off battery allowance", exempt, "Allow background tracking", onBattery)
        SetupRow("Optional all-time location for recovery", background, "Recovery permission", onBackgroundLocation)
        Text(if (VibrationController(context).hasVibrator()) "Vibration hardware: available" else "This device has no vibrator")
        Text(if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
            "Do Not Disturb: off" else "Do Not Disturb: active — alerts may be suppressed")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("TEST ALERT — LOCK SCREEN") }
        OutlinedButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth()) { Text("OPEN APP SETTINGS") }
        Text("Start your walk while the app is open, then lock the screen. The ongoing notification controls the walk. For Samsung, Xiaomi, Oppo, OnePlus and other phones, check Unrestricted battery / Never sleeping apps / Auto-start if shown in App settings.")
        Spacer(Modifier.height(12.dp))
        Text("Android Force stop, the Active apps Stop button, or powering off the phone ends tracking. Reopen the app to start again. System Do Not Disturb and blocked notification channels are respected.")
        Text("Use outdoors. Weak GPS, stale readings, or unreliable speed estimates pause pace alerts instead of guessing your speed.")
    }
}

@Composable
private fun SetupRow(label: String, ready: Boolean, button: String, action: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("${if (ready) "✓" else "!"} $label: ${if (ready) "ready" else "needs attention"}")
            TextButton(onClick = action) { Text(button) }
        }
    }
}
