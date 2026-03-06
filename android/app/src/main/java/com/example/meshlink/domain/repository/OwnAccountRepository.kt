package com.example.meshlink.domain.repository

import com.example.meshlink.domain.model.device.Account
import kotlinx.coroutines.flow.Flow

interface OwnAccountRepository {
    fun getAccountAsFlow(): Flow<Account>
    suspend fun getAccount(): Account
    suspend fun setPeerId(peerId: String)
    suspend fun setProfileUpdateTimestamp(ts: Long)
}