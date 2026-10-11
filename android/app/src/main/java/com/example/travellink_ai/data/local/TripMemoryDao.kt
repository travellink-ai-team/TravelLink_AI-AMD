package com.example.travellink_ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: LocalTripMemory)

    @Query("SELECT * FROM trip_memories WHERE tripId = :tripId LIMIT 1")
    suspend fun getByTripId(tripId: String): LocalTripMemory?

    /** 供回憶總覽/歷史頁標記用：某使用者所有有內容的回憶，最新在前。 */
    @Query("SELECT * FROM trip_memories WHERE userId = :userId ORDER BY updatedAt DESC")
    fun getAllByUser(userId: String): Flow<List<LocalTripMemory>>

    @Query("SELECT tripId FROM trip_memories WHERE userId = :userId")
    suspend fun getTripIdsByUser(userId: String): List<String>
}
