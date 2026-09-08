package com.example.repmate.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TestDao {

    @Insert
    suspend fun insert(item: TestEntity)

    @Query("SELECT * FROM test_items")
    suspend fun getAll(): List<TestEntity>
}