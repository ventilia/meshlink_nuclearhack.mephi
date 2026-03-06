package com.example.meshlink.domain.model.chat

import com.example.meshlink.domain.model.device.Contact
import com.example.meshlink.domain.model.message.Message

data class ChatPreview(
    val contact: Contact,
    val unreadCount: Int,
    val lastMessage: Message?
) : Comparable<ChatPreview> {
    override fun compareTo(other: ChatPreview) =
        compareValuesBy(this, other, ChatPreview::lastMessage, ChatPreview::contact)
}