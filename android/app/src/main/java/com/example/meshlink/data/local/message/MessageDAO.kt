package com.example.meshlink.data.local.message

import androidx.room.*
import com.example.meshlink.domain.model.message.MessageState
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDAO {
    @Query("""
        SELECT * FROM MessageEntity 
        WHERE senderId = :peerId OR receiverId = :peerId 
        ORDER BY timestamp ASC
    """)
    fun getByPeerIdAsFlow(peerId: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM MessageEntity 
        WHERE senderId = :peerId OR receiverId = :peerId 
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun getLastByPeerId(peerId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM MessageEntity WHERE senderId = :peerId AND messageState = 'MESSAGE_RECEIVED'")
    suspend fun countUnread(peerId: String): Long

    @Query("SELECT * FROM MessageEntity WHERE messageId = :messageId")
    suspend fun getById(messageId: Long): MessageEntity?

    @Query("SELECT * FROM MessageEntity WHERE receiverId = :peerId ORDER BY timestamp ASC")
    suspend fun getAllByReceiverId(peerId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity): Long

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("UPDATE MessageEntity SET messageState = :state WHERE messageId = :id")
    suspend fun updateState(id: Long, state: MessageState)

    @Query("DELETE FROM MessageEntity WHERE senderId = :peerId OR receiverId = :peerId")
    suspend fun deleteAllByPeerId(peerId: String)
}
