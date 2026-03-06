package com.example.meshlink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.data.local.profile.ProfileEntity
import com.example.meshlink.data.local.serializer.AccountSerializer
import com.example.meshlink.data.local.serializer.ProfileSerializer

object AppDataStore {
    private val Context.accountStore by dataStore("account.json", AccountSerializer)
    private val Context.profileStore by dataStore("profile.json", ProfileSerializer)

    fun accountStore(ctx: Context): DataStore<AccountEntity> = ctx.accountStore
    fun profileStore(ctx: Context): DataStore<ProfileEntity> = ctx.profileStore
}