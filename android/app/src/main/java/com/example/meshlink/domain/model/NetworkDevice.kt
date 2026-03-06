package com.example.meshlink.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)

@Serializable
data class NetworkDevice(
    val peerId: String,
    val username: String,
    val shortCode: String,
    val publicKeyHex: String,
    val ipAddress: String?,
    val keepalive: Long,
    val hopCount: Int = 1,
    val viaPeerId: String? = null
) {
    val isDirect: Boolean get() = hopCount <= 1 && viaPeerId == null

    val isMeshRelay: Boolean get() = hopCount > 1 && viaPeerId != null
}

@Serializable
data class NetworkKeepalive(
    val devices: List<NetworkDevice>,

    val senderPeerId: String? = null,

    val routingTableJson: String? = null
)


@Serializable
data class NetworkProfileRequest(
    val senderId: String,
    val receiverId: String
)

@Serializable
data class NetworkProfileResponse(
    val senderId: String,
    val receiverId: String,
    val username: String,
    val imageBase64: String?
)

// ─── Messages ─────────────────────────────────────────────────────────────────
@Serializable
data class NetworkTextMessage(
    val messageId: Long,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val text: String,

    val ttl: Int = 5
)

@Serializable
data class NetworkFileMessage(
    val messageId: Long,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val fileName: String,
    val fileBase64: String,
    val ttl: Int = 5
)

@Serializable
data class NetworkAudioMessage(
    val messageId: Long,
    val senderId: String,
    val receiverId: String,
    val timestamp: Long,
    val audioBase64: String,
    val ttl: Int = 5
)

@Serializable
data class NetworkMessageAck(
    val messageId: Long,
    val senderId: String,
    val receiverId: String
)

// ─── Calls ────────────────────────────────────────────────────────────────────
@Serializable
data class NetworkCallRequest(
    val senderId: String,
    val receiverId: String
)

@Serializable
data class NetworkCallResponse(
    val senderId: String,
    val receiverId: String,
    val accepted: Boolean
)

@Serializable
data class NetworkCallEnd(
    val senderId: String,
    val receiverId: String
)
