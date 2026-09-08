package com.example.repmate.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TestEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RepMateDatabase : RoomDatabase() {

    abstract fun testDao(): TestDao
}