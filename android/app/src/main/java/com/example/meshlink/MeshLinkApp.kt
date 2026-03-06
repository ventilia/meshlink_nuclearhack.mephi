package com.example.meshlink

import android.app.Application
import android.util.Log
import com.example.meshlink.data.AppContainer
import com.example.meshlink.data.AppDataContainer
import com.example.meshlink.network.NetworkManager

class MeshLinkApp : Application() {

    companion object {
        private const val TAG = "MeshLinkApp"
    }

    lateinit var container: AppContainer
        private set

    var containerInitialized = false
        private set

    var networkManager: NetworkManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application started")
        MeshLinkAppProvider.app = this
    }

    fun initializeContainer(activity: MainActivity) {
        if (!containerInitialized) {
            Log.i(TAG, "Initializing AppDataContainer...")
            container = AppDataContainer(activity)
            containerInitialized = true
            Log.i(TAG, "AppDataContainer ready")
        }
    }

    fun notifyServiceContainerReady() {
    }
}