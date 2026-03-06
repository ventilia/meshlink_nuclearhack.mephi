package com.example.meshlink.data

import android.content.Context
import com.example.meshlink.HandlerFactory
import com.example.meshlink.MainActivity
import com.example.meshlink.data.local.AppDataStore
import com.example.meshlink.data.local.AppDatabase
import com.example.meshlink.data.local.FileManager
import com.example.meshlink.data.local.alias.AliasDAO
import com.example.meshlink.data.repository.*
import com.example.meshlink.domain.repository.*
import com.example.meshlink.network.CallManager
import com.example.meshlink.network.NetworkManager
import com.example.meshlink.network.wifidirect.WiFiDirectBroadcastReceiver

interface AppContainer {
    val context: Context
    val handlerFactory: HandlerFactory
    val chatRepository: ChatRepository
    val contactRepository: ContactRepository
    val ownAccountRepository: OwnAccountRepository
    val ownProfileRepository: OwnProfileRepository
    val fileManager: FileManager
    val networkManager: NetworkManager
    val callManager: CallManager
    val aliasDao: AliasDAO
}

class AppDataContainer(activity: MainActivity) : AppContainer {
    override val context: Context = activity.applicationContext

    override val handlerFactory = HandlerFactory(context)

    private val db by lazy { AppDatabase.get(context) }

    override val chatRepository: ChatRepository by lazy {
        ChatLocalRepository(db.accountDao(), db.profileDao(), db.messageDao())
    }

    override val contactRepository: ContactRepository by lazy {
        ContactLocalRepository(db.accountDao(), db.profileDao())
    }

    override val ownAccountRepository: OwnAccountRepository by lazy {
        OwnAccountLocalRepository(AppDataStore.accountStore(context))
    }

    override val ownProfileRepository: OwnProfileRepository by lazy {
        OwnProfileLocalRepository(AppDataStore.profileStore(context))
    }

    override val fileManager: FileManager by lazy {
        FileManager(context)
    }

    override val aliasDao: AliasDAO by lazy {
        db.aliasDao()
    }

    private val wifiDirectReceiver by lazy {
        WiFiDirectBroadcastReceiver(context)
    }

    override val networkManager: NetworkManager by lazy {
        NetworkManager(
            context = context,
            ownAccountRepository = ownAccountRepository,
            ownProfileRepository = ownProfileRepository,
            receiver = wifiDirectReceiver,
            chatRepository = chatRepository,
            contactRepository = contactRepository,
            fileManager = fileManager
        )
    }

    override val callManager: CallManager by lazy {
        CallManager(context)
    }
}
