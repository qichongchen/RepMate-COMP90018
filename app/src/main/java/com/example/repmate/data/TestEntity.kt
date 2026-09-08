package com.example.repmate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "test_items")
data class TestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val name: String
)

