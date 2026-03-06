package com.example.meshlink.network.wifidirect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.R


class WiFiDirectService : Service() {

    companion object {
        private const val TAG = "WiFiDirectService"
        const val ACTION_START = "com.example.meshlink.START"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "meshlink_bg"
    }

    private var networkManager: com.example.meshlink.network.NetworkManager? = null
    private var receiver: WiFiDirectBroadcastReceiver? = null
    private var registered = false

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_START && networkManager == null) {
            startForegroundNotification()
            acquireMulticastLock()
            initialize()
        }
        return START_STICKY
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("meshlink_udp_lock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.i(TAG, "MulticastLock acquired ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun initialize() {
        val app = application as? MeshLinkApp ?: run {
            Log.e(TAG, "MeshLinkApp not ready")
            return
        }
        if (!app.containerInitialized) {
            Log.e(TAG, "Container not initialized")
            return
        }
        val container = app.container

        val rcv = container.networkManager.receiver
        receiver = rcv

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(rcv, filter)
        registered = true
        Log.i(TAG, "WiFi Direct receiver registered")

        val nm = container.networkManager
        networkManager = nm
        app.networkManager = nm

        nm.start()
        nm.startDiscoverPeersHandler()

        Log.i(TAG, "NetworkManager started ✓")
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeshLink Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps MeshLink discoverable" }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshLink")
            .setContentText("Looking for nearby devices...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")

        if (registered) {
            runCatching { unregisterReceiver(receiver) }
            registered = false
        }

        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock release error: ${e.message}")
        }
        multicastLock = null

        networkManager?.stop()
        networkManager = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}