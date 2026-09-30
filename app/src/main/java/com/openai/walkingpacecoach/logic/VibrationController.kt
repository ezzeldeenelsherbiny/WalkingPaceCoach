package com.openai.walkingpacecoach.logic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationController(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun warn(pattern: VibrationPattern) {
        if (!vibrator.hasVibrator()) return
        val (timings, amplitudes) = when (pattern) {
            VibrationPattern.LIGHT -> longArrayOf(0, 120, 120, 120) to intArrayOf(0, 110, 0, 110)
            VibrationPattern.DOUBLE -> longArrayOf(0, 220, 160, 220) to intArrayOf(0, 200, 0, 200)
            VibrationPattern.STRONG -> longArrayOf(0, 350, 180, 350) to intArrayOf(0, 255, 0, 255)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    fun cancel() = vibrator.cancel()
}
