package com.openai.walkingpacecoach

import android.app.Application
import com.openai.walkingpacecoach.data.AppDatabase
import com.openai.walkingpacecoach.data.WorkoutRepository

class WalkingPaceCoachApp : Application() {
    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { WorkoutRepository(database.workoutDao()) }
}
