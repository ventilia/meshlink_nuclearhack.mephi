package com.example.meshlink.data.repository

import androidx.datastore.core.DataStore
import com.example.meshlink.data.local.profile.ProfileEntity
import com.example.meshlink.domain.model.device.Profile
import com.example.meshlink.domain.model.device.toProfile
import com.example.meshlink.domain.repository.OwnProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class OwnProfileLocalRepository(
    private val store: DataStore<ProfileEntity>
) : OwnProfileRepository {

    override fun getProfileAsFlow(): Flow<Profile> = store.data.map { it.toProfile() }
    override suspend fun getProfile(): Profile = store.data.first().toProfile()
    override suspend fun setPeerId(peerId: String) = store.updateData { it.copy(peerId = peerId) }.let {}
    override suspend fun setUsername(username: String) = store.updateData { it.copy(username = username) }.let {}
    override suspend fun setImageFileName(fileName: String) = store.updateData { it.copy(imageFileName = fileName) }.let {}
}