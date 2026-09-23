package com.example.repmate.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.repmate.data.local.RepScoreEntity
import com.repmate.data.local.SessionDao
import com.repmate.data.local.WorkoutSessionEntity
import com.repmate.data.local.CalibrationProfileDao
import com.repmate.data.local.CalibrationProfileEntity

@Database(
    entities = [
        WorkoutSessionEntity::class,
        RepScoreEntity::class,
        CalibrationProfileEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class RepMateDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun calibrationProfileDao(): CalibrationProfileDao
}