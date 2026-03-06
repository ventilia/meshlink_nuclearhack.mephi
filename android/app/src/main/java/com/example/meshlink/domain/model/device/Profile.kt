package com.example.meshlink.domain.model.device

import com.example.meshlink.data.local.profile.ProfileEntity

data class Profile(
    val peerId: String,
    val updateTimestamp: Long,
    val username: String,
    val imageFileName: String?
)

fun Profile.toProfileEntity() = ProfileEntity(peerId, updateTimestamp, username, imageFileName)
fun ProfileEntity.toProfile() = Profile(peerId, updateTimestamp, username, imageFileName)