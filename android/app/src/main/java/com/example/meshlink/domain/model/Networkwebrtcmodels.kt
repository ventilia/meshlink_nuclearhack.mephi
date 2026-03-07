// ==========================================
// ФАЙЛ: C:\Users\GAMER\AndroidStudioProjects\meshlink_nuclearhack.mephi\android\app\src\main\java\com\example\meshlink\domain\model\Networkwebrtcmodels.kt
// ==========================================
package com.example.meshlink.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.InternalSerializationApi

@OptIn(InternalSerializationApi::class)
/**
 * WebRTC сигнальные пакеты — ТОЛЬКО для видеозвонков.
 * Аудиозвонки используют NetworkCallRequest с callType = AUDIO
 */
@Serializable
data class NetworkWebRtcOffer(
    val senderId: String,
    val receiverId: String,
    val sdpJson: String,
    val withVideo: Boolean = true,
    val ttl: Int = 5,
    val callType: CallType = CallType.VIDEO
)

@Serializable
data class NetworkWebRtcAnswer(
    val senderId: String,
    val receiverId: String,
    val sdpJson: String,
    val ttl: Int = 5,
    val callType: CallType = CallType.VIDEO
)

@Serializable
data class NetworkWebRtcIceCandidate(
    val senderId: String,
    val receiverId: String,
    val candidateJson: String,
    val ttl: Int = 5,
    val callType: CallType = CallType.VIDEO
)