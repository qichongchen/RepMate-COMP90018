package com.repmate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalibrationProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: CalibrationProfileEntity)

    @Query(
        """
        SELECT * FROM calibration_profiles
        WHERE exercise = :exercise
        LIMIT 1
        """
    )
    suspend fun getProfile(exercise: String): CalibrationProfileEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM calibration_profiles
            WHERE exercise = :exercise
        )
        """
    )
    suspend fun hasProfile(exercise: String): Boolean
}