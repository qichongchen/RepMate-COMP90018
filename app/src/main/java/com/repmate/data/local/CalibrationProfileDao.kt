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
        WHERE ownerId = :ownerId
          AND exercise = :exercise
        LIMIT 1
        """
    )
    suspend fun getProfile(ownerId: String, exercise: String): CalibrationProfileEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM calibration_profiles
            WHERE ownerId = :ownerId
              AND exercise = :exercise
        )
        """
    )
    suspend fun hasProfile(
        ownerId: String,
        exercise: String
    ): Boolean
}