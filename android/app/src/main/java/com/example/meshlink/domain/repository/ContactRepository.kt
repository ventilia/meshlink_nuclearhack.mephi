package com.example.meshlink.domain.repository

import com.example.meshlink.domain.model.device.Account
import com.example.meshlink.domain.model.device.Contact
import com.example.meshlink.domain.model.device.Profile
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun getAllContactsAsFlow(): Flow<List<Contact>>
    fun getContactByPeerIdAsFlow(peerId: String): Flow<Contact?>
    suspend fun addOrUpdateAccount(account: Account)
    suspend fun addOrUpdateProfile(profile: Profile)
}