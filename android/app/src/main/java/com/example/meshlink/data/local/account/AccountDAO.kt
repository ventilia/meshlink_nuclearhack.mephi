package com.example.meshlink.data.local.account

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDAO {
    @Query("SELECT * FROM AccountEntity")
    fun getAllAsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM AccountEntity WHERE peerId = :peerId")
    fun getByPeerIdAsFlow(peerId: String): Flow<AccountEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: AccountEntity)

    @Delete
    suspend fun delete(entity: AccountEntity)
}