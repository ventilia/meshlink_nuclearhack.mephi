package com.example.meshlink.network.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PacketType {
    const val KEEPALIVE          = 1
    const val PROFILE_REQUEST    = 2
    const val PROFILE_RESPONSE   = 3
    const val TEXT_MESSAGE       = 4
    const val FILE_MESSAGE       = 5
    const val AUDIO_MESSAGE      = 6
    const val ACK_RECEIVED       = 7
    const val ACK_READ           = 8
    const val CALL_REQUEST       = 9
    const val CALL_RESPONSE      = 10
    const val CALL_END           = 11
    const val CALL_AUDIO         = 12


    const val FILE_INIT          = 100
    const val FILE_CHUNK         = 101
    const val FILE_CHUNK_ACK     = 102
    const val FILE_COMPLETE      = 103
    const val FILE_STATUS_REQ    = 104
    const val FILE_STATUS_RESP   = 105
    const val FILE_CANCEL        = 106
}

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }