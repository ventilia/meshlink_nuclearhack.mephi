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

    // Existing callbacks
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


    var onFileInit: ((NetworkFileInit) -> Unit)? = null
    var onFileChunk: ((NetworkFileChunk, String) -> Unit)? = null
    var onFileChunkAck: ((NetworkFileChunkAck) -> Unit)? = null
    var onFileComplete: ((NetworkFileComplete) -> Unit)? = null

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


                100 ->
                    onFileInit?.invoke(json.decodeFromString(payload.decodeToString()))
                101 ->
                    onFileChunk?.invoke(json.decodeFromString(payload.decodeToString()), senderIp)
                102 ->
                    onFileChunkAck?.invoke(json.decodeFromString(payload.decodeToString()))
                103 ->
                    Log.d(TAG, "FILE_RETRY received (not implemented)")
                104 ->
                    onFileComplete?.invoke(json.decodeFromString(payload.decodeToString()))
                105 ->
                    Log.d(TAG, "FILE_STATUS_REQ received (not implemented)")
                106 ->
                    Log.d(TAG, "FILE_STATUS_RESP received (not implemented)")
                107 ->
                    Log.d(TAG, "FILE_CANCEL received (not implemented)")

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