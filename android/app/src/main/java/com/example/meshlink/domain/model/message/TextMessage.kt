package com.example.meshlink.domain.model.message

import com.example.meshlink.data.local.message.MessageEntity

data class TextMessage(
    override val messageId: Long,
    override val senderId: String,
    override val receiverId: String,
    override val timestamp: Long,
    override val messageState: MessageState,
    val text: String
) : Message() {
    override fun toMessageEntity() = MessageEntity(
        messageId = messageId,
        senderId = senderId,
        receiverId = receiverId,
        timestamp = timestamp,
        messageState = messageState,
        messageType = MessageType.TEXT_MESSAGE,
        content = text
    )
}