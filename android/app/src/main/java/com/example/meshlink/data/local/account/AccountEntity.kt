package com.example.meshlink.data.local.account

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class AccountEntity(
    @PrimaryKey val peerId: String,
    val profileUpdateTimestamp: Long
)