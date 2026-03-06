package com.example.meshlink.data.local.profile

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDAO {
    @Query("SELECT * FROM ProfileEntity")
    fun getAllAsFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM ProfileEntity WHERE peerId = :peerId")
    fun getByPeerIdAsFlow(peerId: String): Flow<ProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: ProfileEntity)

    @Delete
    suspend fun delete(entity: ProfileEntity)
}