package com.example.meshlink.domain.repository

import com.example.meshlink.domain.model.device.Profile
import kotlinx.coroutines.flow.Flow

interface OwnProfileRepository {
    fun getProfileAsFlow(): Flow<Profile>
    suspend fun getProfile(): Profile
    suspend fun setPeerId(peerId: String)
    suspend fun setUsername(username: String)
    suspend fun setImageFileName(fileName: String)
}