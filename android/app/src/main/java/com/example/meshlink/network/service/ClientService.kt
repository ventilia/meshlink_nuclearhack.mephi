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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket


class ClientService {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun sendTo(ipAddress: String, port: Int, tag: String, data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.bind(null)
                    socket.connect(InetSocketAddress(InetAddress.getByName(ipAddress), port), 3000)
                    socket.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Send error to $ipAddress:$port — ${e.message}")
            }
        }
    }

    private suspend fun sendJson(ipAddress: String, port: Int, tag: String, json: String) {
        sendTo(ipAddress, port, tag, json.encodeToByteArray())
    }

    suspend fun sendKeepalive(ipAddress: String, keepalive: NetworkKeepalive) =
        sendJson(ipAddress, ServerService.PORT_KEEPALIVE, "CLI_KEEPALIVE", json.encodeToString(keepalive))

    suspend fun sendProfileRequest(ipAddress: String, request: NetworkProfileRequest) =
        sendJson(ipAddress, ServerService.PORT_PROFILE_REQUEST, "CLI_PROFILE_REQ", json.encodeToString(request))

    suspend fun sendProfileResponse(ipAddress: String, response: NetworkProfileResponse) =
        sendJson(ipAddress, ServerService.PORT_PROFILE_RESPONSE, "CLI_PROFILE_RES", json.encodeToString(response))

    suspend fun sendTextMessage(ipAddress: String, message: NetworkTextMessage) =
        sendJson(ipAddress, ServerService.PORT_TEXT_MESSAGE, "CLI_TEXT", json.encodeToString(message))

    suspend fun sendFileMessage(ipAddress: String, message: NetworkFileMessage) =
        sendJson(ipAddress, ServerService.PORT_FILE_MESSAGE, "CLI_FILE", json.encodeToString(message))

    suspend fun sendAudioMessage(ipAddress: String, message: NetworkAudioMessage) =
        sendJson(ipAddress, ServerService.PORT_AUDIO_MESSAGE, "CLI_AUDIO", json.encodeToString(message))

    suspend fun sendMessageReceivedAck(ipAddress: String, ack: NetworkMessageAck) =
        sendJson(ipAddress, ServerService.PORT_MESSAGE_RECEIVED_ACK, "CLI_ACK_RCV", json.encodeToString(ack))

    suspend fun sendMessageReadAck(ipAddress: String, ack: NetworkMessageAck) =
        sendJson(ipAddress, ServerService.PORT_MESSAGE_READ_ACK, "CLI_ACK_READ", json.encodeToString(ack))

    suspend fun sendCallRequest(ipAddress: String, request: NetworkCallRequest) =
        sendJson(ipAddress, ServerService.PORT_CALL_REQUEST, "CLI_CALL_REQ", json.encodeToString(request))

    suspend fun sendCallResponse(ipAddress: String, response: NetworkCallResponse) =
        sendJson(ipAddress, ServerService.PORT_CALL_RESPONSE, "CLI_CALL_RES", json.encodeToString(response))

    suspend fun sendCallEnd(ipAddress: String, end: NetworkCallEnd) =
        sendJson(ipAddress, ServerService.PORT_CALL_END, "CLI_CALL_END", json.encodeToString(end))

    suspend fun sendCallFragment(ipAddress: String, fragment: ByteArray) =
        sendTo(ipAddress, ServerService.PORT_CALL_FRAGMENT, "CLI_CALL_FRAG", fragment)
}