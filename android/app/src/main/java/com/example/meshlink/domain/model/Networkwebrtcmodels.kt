package com.example.meshlink.domain.model

import kotlinx.serialization.Serializable

/**
 * WebRTC сигнальные пакеты — передаются через существующую mesh-сеть.
 */
@Serializable
data class NetworkWebRtcOffer(
    val senderId: String,
    val receiverId: String,
    val sdpJson: String,
    val withVideo: Boolean = true,
    val ttl: Int = 5
)

@Serializable
data class NetworkWebRtcAnswer(
    val senderId: String,
    val receiverId: String,
    val sdpJson: String,
    val ttl: Int = 5
)

@Serializable
data class NetworkWebRtcIceCandidate(
    val senderId: String,
    val receiverId: String,
    val candidateJson: String,
    val ttl: Int = 5
)