package com.example.meshlink.data.local.profile

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class ProfileEntity(
    @PrimaryKey val peerId: String,
    val updateTimestamp: Long,
    val username: String,
    val imageFileName: String?
)