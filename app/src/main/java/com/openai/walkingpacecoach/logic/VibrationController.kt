package com.openai.walkingpacecoach.logic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationController(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    fun hasVibrator() = vibrator.hasVibrator()

    fun warn(pattern: VibrationPattern): Boolean {
        if (!hasVibrator()) return false
        val (timings, amplitudes) = when (pattern) {
            VibrationPattern.LIGHT -> longArrayOf(0, 120, 120, 120) to intArrayOf(0, 110, 0, 110)
            VibrationPattern.DOUBLE -> longArrayOf(0, 220, 160, 220) to intArrayOf(0, 200, 0, 200)
            VibrationPattern.STRONG -> longArrayOf(0, 350, 180, 350) to intArrayOf(0, 255, 0, 255)
        }
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else VibrationEffect.createWaveform(timings, -1)
        // Notification usage permits background vibration and respects system DND settings.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_NOTIFICATION).build())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        return true // A request was sent; the OS can still suppress it under DND.
    }
    fun cancel() = vibrator.cancel()
}
