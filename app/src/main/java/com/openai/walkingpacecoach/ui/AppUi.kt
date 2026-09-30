package com.openai.walkingpacecoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openai.walkingpacecoach.data.SpeedSampleEntity
import com.openai.walkingpacecoach.data.WorkoutEntity
import com.openai.walkingpacecoach.logic.AlertState
import com.openai.walkingpacecoach.logic.TrackingConfig
import com.openai.walkingpacecoach.logic.TrackingSnapshot
import com.openai.walkingpacecoach.logic.VibrationPattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class Screen { HOME, LIVE, HISTORY, DETAIL, SETTINGS, SETUP }

@Composable
fun WalkingPaceCoachUi(
    viewModel: MainViewModel,
    onStartWorkout: (TrackingConfig) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    setup: @Composable () -> Unit
) {
    val tracking by viewModel.tracking.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val workouts by viewModel.workouts.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val completedId by viewModel.lastCompletedWorkoutId.collectAsState()

    var screen by remember { mutableStateOf(if (tracking.isActive) Screen.LIVE else Screen.HOME) }

    LaunchedEffect(tracking.isActive) {
        if (tracking.isActive) screen = Screen.LIVE
    }
    LaunchedEffect(completedId) {
        val id = completedId ?: return@LaunchedEffect
        viewModel.loadWorkout(id)
        viewModel.consumeCompletedWorkout()
        screen = Screen.DETAIL
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (!tracking.isActive && screen != Screen.DETAIL) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = { screen = Screen.HOME }) { Text("Home") }
                        TextButton(onClick = { screen = Screen.HISTORY }) { Text("History") }
                        TextButton(onClick = { screen = Screen.SETTINGS }) { Text("Settings") }
                        TextButton(onClick = { screen = Screen.SETUP }) { Text("Phone setup") }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        tracking = tracking,
                        settings = settings,
                        onSettingsChanged = viewModel::updateSettings,
                        onStart = { onStartWorkout(settings.toTrackingConfig()) }
                    )
                    Screen.LIVE -> LiveScreen(tracking, onPause, onResume, onFinish)
                    Screen.HISTORY -> HistoryScreen(
                        workouts = workouts,
                        onOpen = { id -> viewModel.loadWorkout(id); screen = Screen.DETAIL }
                    )
                    Screen.DETAIL -> detail?.let {
                        WorkoutDetailScreen(
                            detail = it,
                            onBack = { viewModel.clearDetail(); screen = Screen.HISTORY },
                            onDelete = { id -> viewModel.deleteWorkout(id); screen = Screen.HISTORY }
                        )
                    } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading workout…") }
                    Screen.SETTINGS -> SettingsScreen(settings, viewModel::updateSettings)
                    Screen.SETUP -> setup()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    tracking: TrackingSnapshot,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onStart: () -> Unit
) {
    var targetText by remember(settings.targetSpeedKmh) { mutableStateOf(String.format(Locale.US, "%.1f", settings.targetSpeedKmh)) }
    val parsedTarget = targetText.toDoubleOrNull()
    val validTarget = parsedTarget != null && parsedTarget > 0.0 && parsedTarget <= 30.0

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("TARGET SPEED", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = targetText,
            onValueChange = { text ->
                targetText = text.replace(',', '.')
                targetText.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 30.0 }?.let {
                    onSettingsChanged(settings.copy(targetSpeedKmh = it))
                }
            },
            suffix = { Text("km/h") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = targetText.isNotBlank() && !validTarget,
            singleLine = true
        )
        if (!validTarget) Text("Enter a target above 0 and up to 30 km/h.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStart,
            enabled = validTarget,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text("START WALK", fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(28.dp))
        InfoCard("GPS status", if (tracking.isActive) tracking.gpsMessage else "Ready — GPS starts only during a workout")
        InfoCard("Current speed", tracking.smoothedSpeedKmh?.let { "%.1f km/h".format(it) } ?: "—")
        InfoCard("Target speed", "%.1f km/h".format(settings.targetSpeedKmh))
        InfoCard("Distance", "%.2f km".format(tracking.distanceMeters / 1000.0))
        InfoCard("Workout duration", formatDuration(tracking.elapsedMs))
        Spacer(Modifier.height(12.dp))
        Text(
            "Location is used only during an active walk to calculate GPS speed and distance. Workout data stays on this phone.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(value)
        }
    }
}

@Composable
private fun LiveScreen(
    tracking: TrackingSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CURRENT SPEED", fontWeight = FontWeight.Bold)
        Text(
            tracking.smoothedSpeedKmh?.let { "%.1f km/h".format(it) } ?: "—",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold
        )
        Text(tracking.gpsMessage, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))

        val status = when (tracking.alertState) {
            AlertState.ON_TARGET -> "ON TARGET"
            AlertState.BELOW_TARGET_PENDING -> "BELOW TARGET — GRACE PERIOD"
            AlertState.BELOW_TARGET_ALERTING -> "BELOW TARGET"
            AlertState.WAITING_FOR_GPS -> "WAITING FOR GPS"
            AlertState.PAUSED -> if (tracking.isManuallyPaused) "PAUSED" else "STOPPED — ALERT PAUSED"
        }
        Text(status, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        StatGrid(
            listOf(
                "TARGET" to "%.1f km/h".format(tracking.targetSpeedKmh),
                "AVERAGE" to "%.1f km/h".format(tracking.averageSpeedKmh),
                "DISTANCE" to "%.2f km".format(tracking.distanceMeters / 1000.0),
                "TIME" to formatDuration(tracking.elapsedMs)
            )
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = if (tracking.isManuallyPaused) onResume else onPause,
                modifier = Modifier.weight(1f).height(56.dp)
            ) { Text(if (tracking.isManuallyPaused) "RESUME" else "PAUSE") }
            Button(
                onClick = onFinish,
                modifier = Modifier.weight(1f).height(56.dp)
            ) { Text("FINISH") }
        }
    }
}

@Composable
private fun StatGrid(items: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        items.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { (label, value) ->
                    Card(Modifier.weight(1f).padding(vertical = 5.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        SettingSwitch("Vibration", settings.vibrationEnabled) { onChange(settings.copy(vibrationEnabled = it)) }
        SettingSwitch("Alert while stopped", settings.alertWhileStopped) { onChange(settings.copy(alertWhileStopped = it)) }
        NumberSetting("Warning interval (seconds)", settings.warningIntervalSeconds, 2, 60) {
            onChange(settings.copy(warningIntervalSeconds = it))
        }
        NumberSetting("Delay before first warning (seconds)", settings.firstWarningDelaySeconds, 1, 30) {
            onChange(settings.copy(firstWarningDelaySeconds = it))
        }
        NumberSetting("Stationary pause delay (seconds)", settings.stationaryPauseSeconds, 3, 120) {
            onChange(settings.copy(stationaryPauseSeconds = it))
        }
        Text("Vibration pattern", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VibrationPattern.entries.forEach { pattern ->
                OutlinedButton(onClick = { onChange(settings.copy(vibrationPattern = pattern)) }) {
                    Text(if (settings.vibrationPattern == pattern) "✓ ${pattern.name}" else pattern.name)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Slowdown reminders use vibration and a visible notification. This app does not play audio. Check Phone setup to test alerts and background permissions.")
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }
            text.toIntOrNull()?.coerceIn(min, max)?.let(onChange)
        },
        label = { Text(label) },
        supportingText = { Text("$min–$max") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@Composable
private fun HistoryScreen(workouts: List<WorkoutEntity>, onOpen: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("HISTORY", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (workouts.isEmpty()) {
            Text("No completed workouts yet.")
        } else {
            LazyColumn {
                items(workouts, key = { it.id }) { workout ->
                    Card(
                        onClick = { onOpen(workout.id) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(formatDate(workout.startedAtEpochMs), fontWeight = FontWeight.Bold)
                            Text("%.2f km • %s".format(workout.distanceMeters / 1000.0, formatDuration(workout.durationMs)))
                            Text("Avg %.1f km/h • Target %.0f%%".format(workout.averageSpeedKmh, workout.targetAchievementPercent))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutDetailScreen(detail: WorkoutDetail, onBack: () -> Unit, onDelete: (Long) -> Unit) {
    val w = detail.workout
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("WORKOUT COMPLETE", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(formatDate(w.startedAtEpochMs))
        Spacer(Modifier.height(12.dp))
        InfoCard("Distance", "%.2f km".format(w.distanceMeters / 1000.0))
        InfoCard("Duration", formatDuration(w.durationMs))
        InfoCard("Average speed", "%.2f km/h".format(w.averageSpeedKmh))
        InfoCard("Maximum speed", "%.2f km/h".format(w.maxSpeedKmh))
        InfoCard("Target", "%.1f km/h".format(w.targetSpeedKmh))
        InfoCard("At/above target", formatDuration(w.timeAtOrAboveTargetMs))
        InfoCard("Below target", formatDuration(w.timeBelowTargetMs))
        InfoCard("Target achievement", "%.0f%%".format(w.targetAchievementPercent))
        InfoCard("Slowdown alerts", w.warningCount.toString())
        InfoCard("Longest target streak", formatDuration(w.longestTargetStreakMs))
        InfoCard("Longest below-target period", formatDuration(w.longestBelowTargetMs))
        Spacer(Modifier.height(18.dp))
        Text("Speed performance", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        SpeedGraph(detail.samples, w.targetSpeedKmh, Modifier.fillMaxWidth().height(240.dp).padding(top = 8.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("BACK") }
            Button(onClick = { onDelete(w.id) }, modifier = Modifier.weight(1f)) { Text("DELETE") }
        }
    }
}

@Composable
private fun SpeedGraph(samples: List<SpeedSampleEntity>, target: Double, modifier: Modifier = Modifier) {
    if (samples.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Not enough GPS samples for graph") }
        return
    }
    val maxElapsed = samples.last().elapsedMs.coerceAtLeast(1L)
    val maxSpeed = max(target + 1.0, samples.maxOf { it.speedKmh } + 0.5)
    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val left = 34.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val width = (right - left).coerceAtLeast(1f)
        val height = (bottom - top).coerceAtLeast(1f)

        repeat(5) { i ->
            val y = top + height * i / 4f
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
        }

        val targetY = bottom - ((target / maxSpeed).toFloat().coerceIn(0f, 1f) * height)
        drawLine(targetColor, Offset(left, targetY), Offset(right, targetY), strokeWidth = 3f)

        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = left + (sample.elapsedMs.toFloat() / maxElapsed.toFloat()) * width
            val y = bottom - ((sample.speedKmh / maxSpeed).toFloat().coerceIn(0f, 1f) * height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 4f))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

