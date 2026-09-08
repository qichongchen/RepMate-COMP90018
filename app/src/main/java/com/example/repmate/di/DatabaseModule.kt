package com.example.repmate.di

import android.content.Context
import androidx.room.Room
import com.example.repmate.data.RepMateDatabase
import com.example.repmate.data.TestDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
        ).build()
    }

    @Provides
    fun provideTestDao(
        database: RepMateDatabase
    ): TestDao {
        return database.testDao()
    }
}