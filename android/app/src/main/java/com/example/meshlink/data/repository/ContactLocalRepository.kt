package com.example.meshlink.data.repository

import com.example.meshlink.data.local.account.AccountDAO
import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.data.local.profile.ProfileDAO
import com.example.meshlink.data.local.profile.ProfileEntity
import com.example.meshlink.domain.model.device.*
import com.example.meshlink.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ContactLocalRepository(
    private val accountDAO: AccountDAO,
    private val profileDAO: ProfileDAO
) : ContactRepository {

    override fun getAllContactsAsFlow(): Flow<List<Contact>> = combine(
        accountDAO.getAllAsFlow().map { it.map(AccountEntity::toAccount) },
        profileDAO.getAllAsFlow().map { it.map(ProfileEntity::toProfile) }
    ) { accounts, profiles ->
        accounts.map { acc -> Contact(acc, profiles.find { it.peerId == acc.peerId }) }
    }

    override fun getContactByPeerIdAsFlow(peerId: String): Flow<Contact?> = combine(
        accountDAO.getByPeerIdAsFlow(peerId).map { it?.toAccount() },
        profileDAO.getByPeerIdAsFlow(peerId).map { it?.toProfile() }
    ) { acc, profile -> if (acc != null) Contact(acc, profile) else null }

    override suspend fun addOrUpdateAccount(account: Account) =
        accountDAO.insertOrUpdate(account.toAccountEntity())

    override suspend fun addOrUpdateProfile(profile: Profile) =
        profileDAO.insertOrUpdate(profile.toProfileEntity())
}