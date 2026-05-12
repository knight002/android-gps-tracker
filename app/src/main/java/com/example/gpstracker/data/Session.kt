package com.example.gpstracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long?,
    val totalPoints: Int,
    val movementThresholdM: Double = 20.0,
    val dwellTimeS: Int = 15,
    val trackingIntervalS: Int = 5,
    val dwellingIntervalS: Int = 30
)
