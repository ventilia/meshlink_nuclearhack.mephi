package com.example.meshlink.data.local.alias

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable


@Serializable
@Entity(tableName = "AliasEntity")
data class AliasEntity(
    @PrimaryKey val peerId: String,
    val alias: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface AliasDAO {
    @Query("SELECT * FROM AliasEntity WHERE peerId = :peerId")
    fun getByPeerIdAsFlow(peerId: String): Flow<AliasEntity?>

    @Query("SELECT * FROM AliasEntity")
    fun getAllAsFlow(): Flow<List<AliasEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: AliasEntity)

    @Query("DELETE FROM AliasEntity WHERE peerId = :peerId")
    suspend fun deleteByPeerId(peerId: String)
}
