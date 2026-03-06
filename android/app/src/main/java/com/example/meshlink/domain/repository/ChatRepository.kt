package com.example.meshlink.domain.repository

import com.example.meshlink.domain.model.chat.ChatPreview
import com.example.meshlink.domain.model.message.Message
import com.example.meshlink.domain.model.message.MessageState
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getAllChatPreviewsAsFlow(): Flow<List<ChatPreview>>
    fun getMessagesByPeerIdAsFlow(peerId: String): Flow<List<Message>>
    suspend fun addMessage(message: Message): Long
    suspend fun updateMessageState(messageId: Long, state: MessageState)
    suspend fun getMessageByMessageId(messageId: Long): Message?
    suspend fun getAllMessagesByReceiverPeerId(peerId: String): List<Message>

    /** Удалить все сообщения с конкретным пиром */
    suspend fun deleteAllMessagesByPeerId(peerId: String)
}
