package com.openai.walkingpacecoach.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openai.walkingpacecoach.WalkingPaceCoachApp
import com.openai.walkingpacecoach.data.SpeedSampleEntity
import com.openai.walkingpacecoach.data.WorkoutEntity
import com.openai.walkingpacecoach.logic.TrackingConfig
import com.openai.walkingpacecoach.logic.VibrationPattern
import com.openai.walkingpacecoach.service.TrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppSettings(
    val targetSpeedKmh: Double = 5.0,
    val vibrationEnabled: Boolean = true,
    val warningIntervalSeconds: Int = 5,
    val firstWarningDelaySeconds: Int = 4,
    val alertWhileStopped: Boolean = false,
    val stationaryPauseSeconds: Int = 10,
    val vibrationPattern: VibrationPattern = VibrationPattern.DOUBLE
) {
    fun toTrackingConfig() = TrackingConfig(
        targetSpeedKmh,
        vibrationEnabled,
        warningIntervalSeconds,
        firstWarningDelaySeconds,
        alertWhileStopped,
        stationaryPauseSeconds,
        vibrationPattern
    )
}

data class WorkoutDetail(
    val workout: WorkoutEntity,
    val samples: List<SpeedSampleEntity>
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WalkingPaceCoachApp
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val tracking = TrackingService.snapshot
    val lastCompletedWorkoutId = TrackingService.lastCompletedWorkoutId
    val workouts = app.repository.workouts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _detail = MutableStateFlow<WorkoutDetail?>(null)
    val detail: StateFlow<WorkoutDetail?> = _detail.asStateFlow()

    fun updateSettings(new: AppSettings) {
        _settings.value = new
        prefs.edit()
            .putFloat("target", new.targetSpeedKmh.toFloat())
            .putBoolean("vibration", new.vibrationEnabled)
            .putInt("interval", new.warningIntervalSeconds)
            .putInt("delay", new.firstWarningDelaySeconds)
            .putBoolean("alertStopped", new.alertWhileStopped)
            .putInt("stationary", new.stationaryPauseSeconds)
            .putString("pattern", new.vibrationPattern.name)
            .apply()
    }

    fun loadWorkout(id: Long) {
        viewModelScope.launch {
            val workout = app.repository.getWorkout(id) ?: return@launch
            _detail.value = WorkoutDetail(workout, app.repository.getSamples(id))
        }
    }

    fun clearDetail() { _detail.value = null }

    fun deleteWorkout(id: Long) {
        viewModelScope.launch {
            app.repository.deleteWorkout(id)
            if (_detail.value?.workout?.id == id) _detail.value = null
        }
    }

    fun consumeCompletedWorkout() = TrackingService.clearLastCompletedWorkoutId()

    private fun loadSettings() = AppSettings(
        targetSpeedKmh = prefs.getFloat("target", 5f).toDouble(),
        vibrationEnabled = prefs.getBoolean("vibration", true),
        warningIntervalSeconds = prefs.getInt("interval", 5),
        firstWarningDelaySeconds = prefs.getInt("delay", 4),
        alertWhileStopped = prefs.getBoolean("alertStopped", false),
        stationaryPauseSeconds = prefs.getInt("stationary", 10),
        vibrationPattern = runCatching {
            VibrationPattern.valueOf(prefs.getString("pattern", "DOUBLE") ?: "DOUBLE")
        }.getOrDefault(VibrationPattern.DOUBLE)
    )
}
