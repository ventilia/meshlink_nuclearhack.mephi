package com.example.meshlink.domain.model.device

import com.example.meshlink.data.local.account.AccountEntity

data class Account(
    val peerId: String,         // hex от Rust
    val profileUpdateTimestamp: Long
)

fun Account.toAccountEntity() = AccountEntity(peerId, profileUpdateTimestamp)
fun AccountEntity.toAccount() = Account(peerId, profileUpdateTimestamp)