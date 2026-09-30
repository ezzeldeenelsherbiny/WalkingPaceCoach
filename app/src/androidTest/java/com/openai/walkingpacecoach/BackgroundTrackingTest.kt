package com.openai.walkingpacecoach

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openai.walkingpacecoach.logic.SpeedProcessor
import com.openai.walkingpacecoach.service.TrackingService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundTrackingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = instrumentation.uiAutomation
        .executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    private fun permissions() {
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("dumpsys deviceidle whitelist +${context.packageName}")
        shell("appops set ${context.packageName} android:mock_location allow")
    }
    @Suppress("DEPRECATION")
    @Test fun screenOffTrackingProducesRepeatedSlowdownNotificationsAndStopsCleanly() {
        permissions()
        val manager = context.getSystemService(LocationManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false,
                false, true, true, true, 1, 1)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_START)
                    .putExtra(TrackingService.EXTRA_TARGET, 2.0)
                    .putExtra(TrackingService.EXTRA_FIRST_DELAY, 1)
                    .putExtra(TrackingService.EXTRA_WARNING_INTERVAL, 2))
            }
            SystemClock.sleep(1500)
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            SystemClock.sleep(1000)
            assertFalse("Screen must be off for this test", power.isInteractive)
            repeat(9) { index ->
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 52.0 + index * 0.000003
                        longitude = 4.0
                        accuracy = 4f
                        speed = 0.4f // 1.44 km/h, below the 2 km/h target
                        speedAccuracyMetersPerSecond = 0.1f
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    })
                SystemClock.sleep(1100)
            }
            val snapshot = TrackingService.snapshot.value
            assertTrue("Foreground service remains active", snapshot.isActive)
            assertTrue("Native GPS callbacks arrive with screen off", snapshot.smoothedSpeedKmh != null)
            assertTrue("Repeated warning decisions while screen off", snapshot.warningCount >= 2)
            assertTrue("Distance updates", snapshot.distanceMeters > 0.0)
            assertTrue("Tracking notification visible", notifications.activeNotifications.any { it.id == 4401 })
            assertTrue("Slowdown notification visible", notifications.activeNotifications.any { it.id == TrackingService.ALERT_NOTIFICATION_ID })
            assertFalse("The test must keep the screen off", power.isInteractive)
            context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP_ONLY))
            SystemClock.sleep(1000)
            assertFalse(TrackingService.snapshot.value.isActive)
            assertFalse(notifications.activeNotifications.any { it.id == 4401 || it.id == TrackingService.ALERT_NOTIFICATION_ID })
        } finally {
            context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP_ONLY))
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            scenario.close()
        }
    }
    @Test fun gpsQualityRejectsUncertainAndOutOfOrderFixes() {
        val processor = SpeedProcessor()
        val first = Location("gps").apply {
            latitude = 52.0; longitude = 4.0; accuracy = 4f; speed = 1f
            elapsedRealtimeNanos = 1_000_000_000L
            speedAccuracyMetersPerSecond = 0.1f
        }
        assertTrue(processor.process(first).valid)
        assertFalse(processor.process(first).valid)
        val uncertain = Location(first).apply {
            elapsedRealtimeNanos = 2_000_000_000L
            speedAccuracyMetersPerSecond = 2f
        }
        assertFalse(processor.process(uncertain).valid)
        val inaccurate = Location(first).apply { accuracy = 100f; elapsedRealtimeNanos = 3_000_000_000L }
        assertFalse(processor.process(inaccurate).valid)
    }
}
