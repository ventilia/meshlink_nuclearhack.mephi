package com.example.meshlink.data.local.message

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.meshlink.domain.model.message.MessageState
import com.example.meshlink.domain.model.message.MessageType
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val messageId: Long = 0,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val messageState: MessageState,
    val messageType: MessageType,
    val content: String
)