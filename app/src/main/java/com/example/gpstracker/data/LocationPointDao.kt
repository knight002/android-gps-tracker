package com.example.gpstracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationPointDao {
    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getPointsBySession(sessionId: Long): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getPointsBySessionSync(sessionId: Long): List<LocationPoint>

    @Query("SELECT COUNT(*) FROM location_points WHERE sessionId = :sessionId")
    suspend fun getPointCount(sessionId: Long): Int

    @Insert
    suspend fun insertPoint(point: LocationPoint): Long

    @Query("DELETE FROM location_points WHERE sessionId = :sessionId")
    suspend fun deletePointsBySession(sessionId: Long)
}
