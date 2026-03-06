package com.example.meshlink.network.transport

import android.util.Log
import com.example.meshlink.domain.model.*
import com.example.meshlink.network.protocol.PacketType
import com.example.meshlink.network.protocol.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket


class MeshClient(private val port: Int = 8800) {

    companion object {
        private const val TAG = "MeshClient"
        private const val CONNECT_TIMEOUT_MS = 3_000
    }

    /**
     * Отправить keepalive и вернуть собственный IP (socket.localAddress).
     * Возвращает null если соединение не удалось.
     */
    suspend fun sendKeepaliveReturnLocalIp(ip: String, data: NetworkKeepalive): String? {
        return withContext(Dispatchers.IO) {
            try {
                var localIp: String? = null
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = 5_000
                    localIp = socket.localAddress?.hostAddress
                    val payload = json.encodeToString(data).encodeToByteArray()
                    val dos = DataOutputStream(socket.getOutputStream())
                    dos.writeInt(PacketType.KEEPALIVE)
                    dos.writeInt(payload.size)
                    dos.write(payload)
                    dos.flush()
                }
                localIp
            } catch (e: Exception) {
                Log.d(TAG, "sendKeepalive to $ip failed: ${e.message}")
                null
            }
        }
    }

    suspend fun sendKeepalive(ip: String, data: NetworkKeepalive) =
        sendJson(ip, PacketType.KEEPALIVE, json.encodeToString(data))

    suspend fun sendProfileRequest(ip: String, data: NetworkProfileRequest) =
        sendJson(ip, PacketType.PROFILE_REQUEST, json.encodeToString(data))

    suspend fun sendProfileResponse(ip: String, data: NetworkProfileResponse) =
        sendJson(ip, PacketType.PROFILE_RESPONSE, json.encodeToString(data))

    suspend fun sendTextMessage(ip: String, data: NetworkTextMessage) =
        sendJson(ip, PacketType.TEXT_MESSAGE, json.encodeToString(data))

    suspend fun sendFileMessage(ip: String, data: NetworkFileMessage) =
        sendJson(ip, PacketType.FILE_MESSAGE, json.encodeToString(data))

    suspend fun sendAudioMessage(ip: String, data: NetworkAudioMessage) =
        sendJson(ip, PacketType.AUDIO_MESSAGE, json.encodeToString(data))

    suspend fun sendAckReceived(ip: String, data: NetworkMessageAck) =
        sendJson(ip, PacketType.ACK_RECEIVED, json.encodeToString(data))

    suspend fun sendAckRead(ip: String, data: NetworkMessageAck) =
        sendJson(ip, PacketType.ACK_READ, json.encodeToString(data))

    suspend fun sendCallRequest(ip: String, data: NetworkCallRequest) =
        sendJson(ip, PacketType.CALL_REQUEST, json.encodeToString(data))

    suspend fun sendCallResponse(ip: String, data: NetworkCallResponse) =
        sendJson(ip, PacketType.CALL_RESPONSE, json.encodeToString(data))

    suspend fun sendCallEnd(ip: String, data: NetworkCallEnd) =
        sendJson(ip, PacketType.CALL_END, json.encodeToString(data))

    suspend fun sendCallAudio(ip: String, audioBytes: ByteArray) =
        sendRaw(ip, PacketType.CALL_AUDIO, audioBytes)


    private suspend fun sendJson(ip: String, type: Int, jsonStr: String) =
        sendRaw(ip, type, jsonStr.encodeToByteArray())

    suspend fun sendRaw(ip: String, type: Int, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = 5_000
                    val dos = DataOutputStream(socket.getOutputStream())
                    dos.writeInt(type)
                    dos.writeInt(payload.size)
                    dos.write(payload)
                    dos.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Send type=$type to $ip failed: ${e.message}")
            }
        }
    }
}