package com.example.repmate.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS test_items")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {

        db.execSQL("DROP TABLE IF EXISTS test_items")

        if (!columnExists(db, "workout_sessions", "ownerId")) {
            db.execSQL(
                """
                ALTER TABLE workout_sessions
                ADD COLUMN ownerId TEXT NOT NULL DEFAULT ''
                """.trimIndent()
            )
        }

        if (!tableExists(db, "calibration_profiles")) {
            db.execSQL(
                """
                CREATE TABLE calibration_profiles (
                    ownerId TEXT NOT NULL,
                    exercise TEXT NOT NULL,
                    minAmplitude REAL NOT NULL,
                    minRepDurationMs INTEGER NOT NULL,
                    maxRepDurationMs INTEGER NOT NULL,
                    cooldownMs INTEGER NOT NULL,
                    sampleCount INTEGER NOT NULL,
                    softestSampleAmplitude REAL NOT NULL,
                    loudestSampleAmplitude REAL NOT NULL,
                    shortestSampleMs INTEGER NOT NULL,
                    longestSampleMs INTEGER NOT NULL,
                    fastestSampleGapMs INTEGER NOT NULL,
                    notes TEXT NOT NULL,
                    PRIMARY KEY(ownerId, exercise)
                )
                """.trimIndent()
            )
        } else if (!columnExists(
                db,
                "calibration_profiles",
                "loudestSampleAmplitude"
            )
        ) {

            db.execSQL(
                """
                ALTER TABLE calibration_profiles
                ADD COLUMN loudestSampleAmplitude REAL NOT NULL DEFAULT 0
                """.trimIndent()
            )
        }
    }
}

private fun tableExists(
    db: SupportSQLiteDatabase,
    tableName: String
): Boolean {
    db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='$tableName'"
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

private fun columnExists(
    db: SupportSQLiteDatabase,
    tableName: String,
    columnName: String
): Boolean {
    db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")

        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                return true
            }
        }
    }

    return false
}