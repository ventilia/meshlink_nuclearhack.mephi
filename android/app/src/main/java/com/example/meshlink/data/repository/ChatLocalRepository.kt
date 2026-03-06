package com.example.meshlink.data.repository

import com.example.meshlink.data.local.account.AccountDAO
import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.data.local.message.MessageDAO
import com.example.meshlink.data.local.message.MessageEntity
import com.example.meshlink.data.local.profile.ProfileDAO
import com.example.meshlink.data.local.profile.ProfileEntity
import com.example.meshlink.domain.model.chat.ChatPreview
import com.example.meshlink.domain.model.device.*
import com.example.meshlink.domain.model.message.*
import com.example.meshlink.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ChatLocalRepository(
    private val accountDAO: AccountDAO,
    private val profileDAO: ProfileDAO,
    private val messageDAO: MessageDAO
) : ChatRepository {

    override fun getAllChatPreviewsAsFlow(): Flow<List<ChatPreview>> = combine(
        accountDAO.getAllAsFlow().map { it.map(AccountEntity::toAccount) },
        profileDAO.getAllAsFlow().map { it.map(ProfileEntity::toProfile) }
    ) { accounts, profiles ->
        accounts.map { acc ->
            val profile = profiles.find { it.peerId == acc.peerId }
            val contact = Contact(acc, profile)
            val unread = messageDAO.countUnread(acc.peerId).toInt()
            val last = messageDAO.getLastByPeerId(acc.peerId)?.toMessage()
            ChatPreview(contact, unread, last)
        }.sortedWith(compareByDescending { it.lastMessage?.timestamp })
    }

    override fun getMessagesByPeerIdAsFlow(peerId: String): Flow<List<Message>> =
        messageDAO.getByPeerIdAsFlow(peerId).map { it.map(MessageEntity::toMessage) }

    override suspend fun addMessage(message: Message): Long =
        messageDAO.insert(message.toMessageEntity())

    override suspend fun updateMessageState(messageId: Long, state: MessageState) =
        messageDAO.updateState(messageId, state)

    override suspend fun getMessageByMessageId(messageId: Long): Message? =
        messageDAO.getById(messageId)?.toMessage()

    override suspend fun getAllMessagesByReceiverPeerId(peerId: String): List<Message> =
        messageDAO.getAllByReceiverId(peerId).map { it.toMessage() }

    override suspend fun deleteAllMessagesByPeerId(peerId: String) =
        messageDAO.deleteAllByPeerId(peerId)
}


fun MessageEntity.toMessage(): Message = when (messageType) {
    MessageType.TEXT_MESSAGE  -> TextMessage(messageId, senderId, receiverId, timestamp, messageState, content)
    MessageType.FILE_MESSAGE  -> FileMessage(messageId, senderId, receiverId, timestamp, messageState, content)
    MessageType.AUDIO_MESSAGE -> AudioMessage(messageId, senderId, receiverId, timestamp, messageState, content)
}

fun AccountEntity.toAccount() = Account(peerId, profileUpdateTimestamp)
fun ProfileEntity.toProfile() = Profile(peerId, updateTimestamp, username, imageFileName)
