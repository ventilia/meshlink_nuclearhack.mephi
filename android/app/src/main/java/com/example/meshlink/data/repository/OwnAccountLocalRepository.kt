package com.example.meshlink.data.repository

import androidx.datastore.core.DataStore
import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.domain.model.device.Account
import com.example.meshlink.domain.model.device.toAccount
import com.example.meshlink.domain.repository.OwnAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class OwnAccountLocalRepository(
    private val store: DataStore<AccountEntity>
) : OwnAccountRepository {

    override fun getAccountAsFlow(): Flow<Account> = store.data.map { it.toAccount() }
    override suspend fun getAccount(): Account = store.data.first().toAccount()
    override suspend fun setPeerId(peerId: String) = store.updateData { it.copy(peerId = peerId) }.let {}
    override suspend fun setProfileUpdateTimestamp(ts: Long) = store.updateData { it.copy(profileUpdateTimestamp = ts) }.let {}
}