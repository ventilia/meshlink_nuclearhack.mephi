package com.example.meshlink.network.service

import android.util.Log
import com.example.meshlink.domain.model.NetworkAudioMessage
import com.example.meshlink.domain.model.NetworkCallEnd
import com.example.meshlink.domain.model.NetworkCallRequest
import com.example.meshlink.domain.model.NetworkCallResponse
import com.example.meshlink.domain.model.NetworkFileMessage
import com.example.meshlink.domain.model.NetworkKeepalive
import com.example.meshlink.domain.model.NetworkMessageAck
import com.example.meshlink.domain.model.NetworkProfileRequest
import com.example.meshlink.domain.model.NetworkProfileResponse
import com.example.meshlink.domain.model.NetworkTextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.net.Socket

class ServerService {
    companion object {
        const val PORT_KEEPALIVE            = 8800
        const val PORT_PROFILE_REQUEST      = 8801
        const val PORT_PROFILE_RESPONSE     = 8802
        const val PORT_TEXT_MESSAGE         = 8803
        const val PORT_FILE_MESSAGE         = 8804
        const val PORT_AUDIO_MESSAGE        = 8805
        const val PORT_MESSAGE_RECEIVED_ACK = 8806
        const val PORT_MESSAGE_READ_ACK     = 8807
        const val PORT_CALL_REQUEST         = 8808
        const val PORT_CALL_RESPONSE        = 8809

        const val PORT_CALL_END             = 8812
        const val PORT_CALL_FRAGMENT        = 8811
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val serverSockets = mutableMapOf<Int, ServerSocket>()

    private fun getOrCreateServerSocket(port: Int): ServerSocket {
        return serverSockets.getOrPut(port) {
            ServerSocket(port).also {
                it.reuseAddress = true
                Log.d("ServerService", "Listening on port $port")
            }
        }
    }

    private suspend fun <T> listenOn(port: Int, tag: String, decode: (String) -> T): T? {
        return withContext(Dispatchers.IO) {
            try {
                val server = getOrCreateServerSocket(port)
                val client: Socket = server.accept()
                client.use {
                    val data = it.getInputStream().readBytes().decodeToString()
                    Log.d(tag, "Received on port $port: ${data.take(200)}")
                    decode(data)
                }
            } catch (e: Exception) {
                Log.w(tag, "Error on port $port: ${e.message}")
                null
            }
        }
    }

    private suspend fun <T> listenOnWithSender(
        port: Int,
        tag: String,
        decode: (String) -> T
    ): Pair<T, String?>? {
        return withContext(Dispatchers.IO) {
            try {
                val server = getOrCreateServerSocket(port)
                val client: Socket = server.accept()
                val senderIp = client.inetAddress?.hostAddress
                client.use {
                    val data = it.getInputStream().readBytes().decodeToString()
                    Log.d(tag, "Received on port $port from $senderIp: ${data.take(200)}")
                    decode(data) to senderIp
                }
            } catch (e: Exception) {
                Log.w(tag, "Error on port $port: ${e.message}")
                null
            }
        }
    }

    fun shutdown() {
        serverSockets.values.forEach { runCatching { it.close() } }
        serverSockets.clear()
    }

    suspend fun listenKeepaliveWithSender(): Pair<NetworkKeepalive, String?>? =
        listenOnWithSender(PORT_KEEPALIVE, "SRV_KEEPALIVE") { json.decodeFromString(it) }

    suspend fun listenProfileRequestWithSender(): Pair<NetworkProfileRequest, String?>? =
        listenOnWithSender(PORT_PROFILE_REQUEST, "SRV_PROFILE_REQ") { json.decodeFromString(it) }

    suspend fun listenProfileResponse(): NetworkProfileResponse? =
        listenOn(PORT_PROFILE_RESPONSE, "SRV_PROFILE_RES") { json.decodeFromString(it) }

    suspend fun listenTextMessage(): NetworkTextMessage? =
        listenOn(PORT_TEXT_MESSAGE, "SRV_TEXT") { json.decodeFromString(it) }

    suspend fun listenFileMessage(): NetworkFileMessage? =
        listenOn(PORT_FILE_MESSAGE, "SRV_FILE") { json.decodeFromString(it) }

    suspend fun listenAudioMessage(): NetworkAudioMessage? =
        listenOn(PORT_AUDIO_MESSAGE, "SRV_AUDIO") { json.decodeFromString(it) }

    suspend fun listenMessageReceivedAck(): NetworkMessageAck? =
        listenOn(PORT_MESSAGE_RECEIVED_ACK, "SRV_ACK_RCV") { json.decodeFromString(it) }

    suspend fun listenMessageReadAck(): NetworkMessageAck? =
        listenOn(PORT_MESSAGE_READ_ACK, "SRV_ACK_READ") { json.decodeFromString(it) }

    suspend fun listenCallRequest(): NetworkCallRequest? =
        listenOn(PORT_CALL_REQUEST, "SRV_CALL_REQ") { json.decodeFromString(it) }

    suspend fun listenCallResponse(): NetworkCallResponse? =
        listenOn(PORT_CALL_RESPONSE, "SRV_CALL_RES") { json.decodeFromString(it) }

    suspend fun listenCallEnd(): NetworkCallEnd? =
        listenOn(PORT_CALL_END, "SRV_CALL_END") { json.decodeFromString(it) }

    suspend fun listenCallFragment(): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val server = getOrCreateServerSocket(PORT_CALL_FRAGMENT)
                val client: Socket = server.accept()
                client.use { it.getInputStream().readBytes() }
            } catch (e: Exception) {
                Log.w("SRV_CALL_FRAG", "Error: ${e.message}")
                null
            }
        }
}