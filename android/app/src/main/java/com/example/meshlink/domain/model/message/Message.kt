package com.example.meshlink.domain.model.message

import com.example.meshlink.data.local.message.MessageEntity

enum class MessageType { TEXT_MESSAGE, FILE_MESSAGE, AUDIO_MESSAGE }
enum class MessageState { MESSAGE_SENT, MESSAGE_RECEIVED, MESSAGE_READ }

sealed class Message : Comparable<Message> {
    abstract val messageId: Long
    abstract val senderId: String  // peer_id hex
    abstract val receiverId: String
    abstract val timestamp: Long
    abstract val messageState: MessageState
    abstract fun toMessageEntity(): MessageEntity

    override fun compareTo(other: Message) = compareValuesBy(this, other, Message::timestamp)
}