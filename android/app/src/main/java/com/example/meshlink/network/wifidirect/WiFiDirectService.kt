package com.example.meshlink.network.wifidirect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

    /**
     * BroadcastReceiver для отслеживания состояния экрана.
     * Когда экран гаснет — останавливаем активный UDP-discovery,
     * чтобы не жечь батарею. TCP-сервер при этом продолжает работать,
     * входящие соединения и сообщения принимаются в штатном режиме.
     * Когда экран включается — возобновляем discovery.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Экран выключен — пауза discovery")
                    networkManager?.pauseDiscovery()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Экран включён — возобновляем discovery")
                    networkManager?.resumeDiscovery()
                }
            }
        }
    }
    private var screenReceiverRegistered = false

    // ── Жизненный цикл ──────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_START && networkManager == null) {
            startForegroundNotification()
            acquireMulticastLock()
            initialize()
            registerScreenReceiver()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")

        // Wi-Fi Direct receiver
        if (registered) {
            runCatching { unregisterReceiver(receiver) }
            registered = false
        }

        // Screen receiver
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }

        // MulticastLock
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

    // ── Инициализация ────────────────────────────────────────────────────────

    private fun initialize() {
        Log.i(TAG, "initialize: application=${application?.javaClass?.name}")
        val app = application
        if (app !is MeshLinkApp) {
            Log.e(TAG, "MeshLinkApp not ready: actual type=${app?.javaClass?.name}")
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

        // Уведомляем MeshLinkApp — GlobalCallManager подключится к NetworkManager
        // и с этого момента рингтон будет работать на любом экране.
        app.notifyServiceContainerReady()

        Log.i(TAG, "NetworkManager started ✓")
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

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
        Log.i(TAG, "Screen receiver registered")

        // Сразу проверяем текущее состояние экрана
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            Log.d(TAG, "Экран уже выключен при старте — сразу ставим discovery на паузу")
            networkManager?.pauseDiscovery()
        }
    }

    // ── Уведомление foreground ───────────────────────────────────────────────

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MeshLink Background",
                // IMPORTANCE_MIN — нет иконки в статусбаре, нет звука, минимальный приоритет.
                // Это самый «тихий» режим: уведомление живёт, но почти не заметно.
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "MeshLink mesh network — keeps connection alive"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshLink")
            .setContentText("Mesh сеть активна")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            // Скрываем уведомление из шторки на современных устройствах
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}