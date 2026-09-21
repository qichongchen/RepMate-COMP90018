package com.example.repmate.di

import android.content.Context
import androidx.room.Room
import com.example.repmate.data.RepMateDatabase
import com.repmate.data.local.SessionDao
import com.repmate.data.local.CalibrationProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.example.repmate.data.MIGRATION_2_3
import com.example.repmate.data.MIGRATION_3_4

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): RepMateDatabase {
        return Room.databaseBuilder(
            context,
            RepMateDatabase::class.java,
            "repmate_database"
        )
            .addMigrations(
                MIGRATION_2_3,
                MIGRATION_3_4
            )
            .build()
    }


    @Provides
    fun provideSessionDao(
        database: RepMateDatabase
    ): SessionDao {
        return database.sessionDao()
    }

    @Provides
    fun provideCalibrationProfileDao(
        database: RepMateDatabase
    ): CalibrationProfileDao {
        return database.calibrationProfileDao()
    }
}