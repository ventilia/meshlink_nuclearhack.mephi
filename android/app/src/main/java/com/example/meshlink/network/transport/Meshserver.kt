package com.example.meshlink.network.transport

import android.util.Log
import com.example.meshlink.domain.model.*
import com.example.meshlink.network.protocol.PacketType
import com.example.meshlink.network.protocol.json
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.SocketException

class MeshServer(val port: Int = 8800) {
    companion object {
        private const val TAG = "MeshServer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var isRunning = false

    var onKeepalive: ((NetworkKeepalive, String) -> Unit)? = null
    var onProfileRequest: ((NetworkProfileRequest, String) -> Unit)? = null
    var onProfileResponse: ((NetworkProfileResponse) -> Unit)? = null
    var onTextMessage: ((NetworkTextMessage) -> Unit)? = null
    var onFileMessage: ((NetworkFileMessage) -> Unit)? = null
    var onAudioMessage: ((NetworkAudioMessage) -> Unit)? = null
    var onAckReceived: ((NetworkMessageAck) -> Unit)? = null
    var onAckRead: ((NetworkMessageAck) -> Unit)? = null
    var onCallRequest: ((NetworkCallRequest) -> Unit)? = null
    var onCallResponse: ((NetworkCallResponse) -> Unit)? = null
    var onCallEnd: ((NetworkCallEnd) -> Unit)? = null
    var onCallAudio: ((ByteArray, String) -> Unit)? = null

    // ── WebRTC callbacks ────────────────────────────────────────────────────
    var onWebRtcOffer: ((NetworkWebRtcOffer) -> Unit)? = null
    var onWebRtcAnswer: ((NetworkWebRtcAnswer) -> Unit)? = null
    var onWebRtcIceCandidate: ((NetworkWebRtcIceCandidate) -> Unit)? = null

    fun start() {
        scope.launch {
            var retries = 0
            while (isActive && !isRunning) {
                try {
                    val ss = ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(port))
                        soTimeout = 0
                    }
                    serverSocket = ss
                    isRunning = true
                    Log.i(TAG, "✓ TCP server listening on port $port")
                    while (isActive) {
                        try {
                            val client = ss.accept()
                            launch { handleClient(client) }
                        } catch (e: SocketException) {
                            if (!isActive) break
                            Log.w(TAG, "accept() error: ${e.message}")
                            delay(300)
                        }
                    }
                } catch (e: Exception) {
                    isRunning = false
                    retries++
                    Log.e(TAG, "Server start failed (attempt $retries) port=$port: ${e.message}")
                    if (retries < 5) delay(2_000L * retries)
                    else {
                        Log.e(TAG, "Giving up after $retries retries — port $port unavailable")
                        break
                    }
                }
            }
        }
    }

    private fun handleClient(socket: java.net.Socket) {
        val senderIp = socket.inetAddress?.hostAddress ?: "unknown"
        try {
            socket.use {
                socket.soTimeout = 15_000
                val dis = DataInputStream(socket.getInputStream())
                val type = dis.readInt()
                val length = dis.readInt()
                if (length < 0 || length > 50 * 1024 * 1024) {
                    Log.w(TAG, "Bad packet length=$length from $senderIp, ignoring")
                    return
                }
                val payload = ByteArray(length)
                dis.readFully(payload)
                dispatch(type, payload, senderIp)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client $senderIp error: ${e.message}")
        }
    }

    private fun dispatch(type: Int, payload: ByteArray, senderIp: String) {
        try {
            when (type) {
                PacketType.KEEPALIVE ->
                    onKeepalive?.invoke(json.decodeFromString(payload.decodeToString()), senderIp)
                PacketType.PROFILE_REQUEST ->
                    onProfileRequest?.invoke(json.decodeFromString(payload.decodeToString()), senderIp)
                PacketType.PROFILE_RESPONSE ->
                    onProfileResponse?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.TEXT_MESSAGE ->
                    onTextMessage?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.FILE_MESSAGE ->
                    onFileMessage?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.AUDIO_MESSAGE ->
                    onAudioMessage?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.ACK_RECEIVED ->
                    onAckReceived?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.ACK_READ ->
                    onAckRead?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.CALL_REQUEST ->
                    onCallRequest?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.CALL_RESPONSE ->
                    onCallResponse?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.CALL_END ->
                    onCallEnd?.invoke(json.decodeFromString(payload.decodeToString()))
                PacketType.CALL_AUDIO ->
                    onCallAudio?.invoke(payload, senderIp)
                // ── WebRTC пакеты ────────────────────────────────────────────
                PacketType.WEBRTC_OFFER -> {
                    val offer = json.decodeFromString<NetworkWebRtcOffer>(payload.decodeToString())
                    onWebRtcOffer?.invoke(offer)
                }
                PacketType.WEBRTC_ANSWER -> {
                    val answer = json.decodeFromString<NetworkWebRtcAnswer>(payload.decodeToString())
                    onWebRtcAnswer?.invoke(answer)
                }
                PacketType.WEBRTC_ICE_CANDIDATE -> {
                    val candidate = json.decodeFromString<NetworkWebRtcIceCandidate>(payload.decodeToString())
                    onWebRtcIceCandidate?.invoke(candidate)
                }
                else ->
                    Log.w(TAG, "Unknown packet type=$type from $senderIp")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dispatch error type=$type from $senderIp: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        runCatching { serverSocket?.close() }
        Log.i(TAG, "Server stopped")
    }
}