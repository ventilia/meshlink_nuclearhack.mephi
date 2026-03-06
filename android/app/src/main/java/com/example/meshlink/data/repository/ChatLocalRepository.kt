package com.example.meshlink.data.repository

import com.example.meshlink.data.local.account.AccountDAO
import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.data.local.alias.AliasDAO
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ChatLocalRepository(
    private val accountDAO: AccountDAO,
    private val profileDAO: ProfileDAO,
    private val messageDAO: MessageDAO,

    private val aliasDAO: AliasDAO
) : ChatRepository {

    override fun getAllChatPreviewsAsFlow(): Flow<List<ChatPreview>> = combine(
        accountDAO.getAllAsFlow().map { it.map(AccountEntity::toAccount) },
        profileDAO.getAllAsFlow().map { it.map(ProfileEntity::toProfile) },
        aliasDAO.getAllAsFlow()
    ) { accounts, profiles, aliases ->


        Triple(accounts, profiles, aliases)
    }.flatMapLatest { (accounts, profiles, aliases) ->
        if (accounts.isEmpty()) {
            flowOf(emptyList())
        } else {

            val unreadFlows: List<Flow<Pair<String, Int>>> = accounts.map { acc ->
                messageDAO.countUnreadAsFlow(acc.peerId).map { count ->
                    acc.peerId to count.toInt()
                }
            }

            val lastMsgFlows: List<Flow<Pair<String, Message?>>> = accounts.map { acc ->
                messageDAO.getByPeerIdAsFlow(acc.peerId).map { msgs ->
                    acc.peerId to msgs.maxByOrNull { it.timestamp }?.toMessage()
                }
            }


            val combinedUnread: Flow<Map<String, Int>> = if (unreadFlows.size == 1) {
                unreadFlows[0].map { mapOf(it) }
            } else {
                combine(unreadFlows) { pairs -> pairs.associate { it } }
            }


            val combinedLastMsg: Flow<Map<String, Message?>> = if (lastMsgFlows.size == 1) {
                lastMsgFlows[0].map { mapOf(it) }
            } else {
                combine(lastMsgFlows) { pairs -> pairs.associate { it } }
            }

            combine(combinedUnread, combinedLastMsg) { unreadMap, lastMsgMap ->
                accounts.map { acc ->
                    val profile = profiles.find { it.peerId == acc.peerId }
                    val alias = aliases.find { it.peerId == acc.peerId }

                    val resolvedUsername: String? =
                        alias?.alias?.takeIf { it.isNotBlank() }
                            ?: profile?.username?.takeIf { it.isNotBlank() }


                    val displayProfile = when {
                        resolvedUsername != null && profile != null ->
                            profile.copy(username = resolvedUsername)
                        resolvedUsername != null ->
                            Profile(acc.peerId, 0, resolvedUsername, null)
                        else -> profile
                    }

                    val contact = Contact(acc, displayProfile)
                    val unread = unreadMap[acc.peerId] ?: 0
                    val last = lastMsgMap[acc.peerId]
                    ChatPreview(contact, unread, last)
                }.sortedWith(compareByDescending { it.lastMessage?.timestamp })
            }
        }
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