package com.example.meshlink.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NetworkFileInit(
    val transferId: String,
    val senderId: String,
    val receiverId: String,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val chunkSize: Int,
    val totalChunks: Int,
    val mimeType: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 5
)

@Serializable
data class NetworkFileChunk(
    val transferId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkHash: String,
    val data: String,
    val ttl: Int = 5
)

@Serializable
data class NetworkFileChunkAck(
    val transferId: String,
    val chunkIndex: Int,
    val receivedHash: String,
    val success: Boolean
)

@Serializable
data class NetworkFileComplete(
    val transferId: String,
    val senderId: String,
    val receiverId: String,
    val success: Boolean,
    val error: String? = null,
    val finalHash: String? = null
)

@Serializable
data class NetworkFileStatusRequest(
    val transferId: String,
    val lastReceivedChunk: Int? = null
)

@Serializable
data class NetworkFileStatusResponse(
    val transferId: String,
    val totalChunks: Int,
    val receivedChunks: List<Int>,
    val canResume: Boolean
)