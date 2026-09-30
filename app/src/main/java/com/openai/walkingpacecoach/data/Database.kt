package com.openai.walkingpacecoach.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val targetSpeedKmh: Double,
    val timeAtOrAboveTargetMs: Long,
    val timeBelowTargetMs: Long,
    val warningCount: Int,
    val longestTargetStreakMs: Long,
    val longestBelowTargetMs: Long
) {
    val targetAchievementPercent: Double
        get() {
            val denom = timeAtOrAboveTargetMs + timeBelowTargetMs
            return if (denom <= 0L) 0.0 else timeAtOrAboveTargetMs * 100.0 / denom
        }
}

@Entity(
    tableName = "speed_samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workoutId")]
)
data class SpeedSampleEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val elapsedMs: Long,
    val speedKmh: Double,
    val accuracyMeters: Float
)

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSamples(samples: List<SpeedSampleEntity>)

    @Query("SELECT * FROM workouts ORDER BY startedAtEpochMs DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :id LIMIT 1")
    suspend fun getWorkout(id: Long): WorkoutEntity?

    @Query("SELECT * FROM speed_samples WHERE workoutId = :workoutId ORDER BY elapsedMs ASC")
    suspend fun getSamples(workoutId: Long): List<SpeedSampleEntity>

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: Long)
}

@Database(
    entities = [WorkoutEntity::class, SpeedSampleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "walking_pace_coach.db"
            ).build().also { INSTANCE = it }
        }
    }
}

class WorkoutRepository(private val dao: WorkoutDao) {
    val workouts: Flow<List<WorkoutEntity>> = dao.observeWorkouts()

    suspend fun saveWorkout(workout: WorkoutEntity, samples: List<SpeedSampleDraft>): Long {
        val id = dao.insertWorkout(workout)
        if (samples.isNotEmpty()) {
            dao.insertSamples(samples.map {
                SpeedSampleEntity(
                    workoutId = id,
                    elapsedMs = it.elapsedMs,
                    speedKmh = it.speedKmh,
                    accuracyMeters = it.accuracyMeters
                )
            })
        }
        return id
    }

    suspend fun getWorkout(id: Long) = dao.getWorkout(id)
    suspend fun getSamples(id: Long) = dao.getSamples(id)
    suspend fun deleteWorkout(id: Long) = dao.deleteWorkout(id)
}

data class SpeedSampleDraft(
    val elapsedMs: Long,
    val speedKmh: Double,
    val accuracyMeters: Float
)
