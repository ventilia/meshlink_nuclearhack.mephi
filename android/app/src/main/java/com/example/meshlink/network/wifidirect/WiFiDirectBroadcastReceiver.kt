package com.example.meshlink.network.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WiFiDirectBroadcastReceiver(context: Context) : BroadcastReceiver() {

    companion object {
        private const val TAG = "WifiDirect"
        const val GROUP_OWNER_IP = "192.168.49.1"

        private const val INVITED_STUCK_TIMEOUT_MS = 12_000L
        private const val CONNECT_RETRY_DELAY_MS = 2_000L
        private const val DISCOVERY_MIN_INTERVAL_MS = 5_000L
        private const val GROUP_FALLBACK_DELAY_MS = 10_000L

        fun deviceStatusString(status: Int) = when (status) {
            WifiP2pDevice.CONNECTED   -> "CONNECTED"
            WifiP2pDevice.INVITED     -> "INVITED"
            WifiP2pDevice.FAILED      -> "FAILED"
            WifiP2pDevice.AVAILABLE   -> "AVAILABLE"
            WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
            else                      -> "UNKNOWN($status)"
        }
    }

    val manager = WiFiDirectManager(context)


    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers

    private val _isWifiDirectEnabled = MutableStateFlow(false)
    val isWifiDirectEnabled: StateFlow<Boolean> = _isWifiDirectEnabled

    private val _groupOwnerAddress = MutableStateFlow<String?>(null)
    val groupOwnerAddress: StateFlow<String?> = _groupOwnerAddress

    private val _isGroupOwner = MutableStateFlow(false)
    val isGroupOwner: StateFlow<Boolean> = _isGroupOwner

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected


    @Volatile private var isDiscovering = false
    @Volatile private var lastDiscoveryTime = 0L

    @Volatile private var groupCreationAttempted = false
    @Volatile private var connectAttemptInProgress = false
    @Volatile private var actuallyConnected = false

    @Volatile private var lastConnectAttemptTime = 0L
    @Volatile private var lastConnectAttemptAddress = ""

    private val pendingConnects = mutableListOf<String>()
    private val connectRetries = mutableMapOf<String, Int>()
    private val MAX_CONNECT_RETRIES = 2

    private val handler = Handler(Looper.getMainLooper())

    private var invitedStuckRunnable: Runnable? = null
    private var groupFallbackRunnable: Runnable? = null


    fun discoverPeers() {
        val now = System.currentTimeMillis()
        if (isDiscovering && now - lastDiscoveryTime < DISCOVERY_MIN_INTERVAL_MS) {
            Log.v(TAG, "discoverPeers() skipped — cooldown")
            return
        }
        lastDiscoveryTime = now
        isDiscovering = true

        manager.discoverPeers(object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Discovery started ✓")
            }
            override fun onFailure(reason: Int) {
                isDiscovering = false
                Log.w(TAG, "Discovery failed: ${reasonStr(reason)}")
                if (reason == WifiP2pManager.BUSY) {
                    handler.postDelayed({ discoverPeers() }, 3_000)
                }
            }
        })
    }


    fun connectToPeer(deviceAddress: String) {
        if (actuallyConnected) {
            Log.d(TAG, "connectToPeer: already connected, skip")
            return
        }
        if (connectAttemptInProgress) {
            if (!pendingConnects.contains(deviceAddress)) {
                pendingConnects.add(deviceAddress)
                Log.d(TAG, "connectToPeer: queued $deviceAddress")
            }
            return
        }

        val retries = connectRetries.getOrDefault(deviceAddress, 0)
        if (retries >= MAX_CONNECT_RETRIES) {
            Log.w(TAG, "Max retries for $deviceAddress — creating group as fallback")
            connectRetries.remove(deviceAddress)
            createGroupIfNeeded()
            return
        }

        connectAttemptInProgress = true
        cancelGroupFallback()


        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            groupOwnerIntent = 0
        }

        Log.i(TAG, "Connecting → $deviceAddress (attempt ${retries + 1})...")
        lastConnectAttemptTime = System.currentTimeMillis()
        lastConnectAttemptAddress = deviceAddress

        scheduleInvitedStuckReset(deviceAddress)

        manager.connect(config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Invite sent → $deviceAddress ✓ (waiting for response...)")
                connectRetries[deviceAddress] = retries + 1
                connectAttemptInProgress = false
            }
            override fun onFailure(reason: Int) {
                connectAttemptInProgress = false
                cancelInvitedStuckReset()
                Log.w(TAG, "Connect → $deviceAddress FAILED: ${reasonStr(reason)}")
                when (reason) {
                    WifiP2pManager.BUSY -> forceResetAndRetry(deviceAddress, 3_000)
                    else -> {
                        connectRetries[deviceAddress] = (connectRetries[deviceAddress] ?: 0) + 1
                        tryNextPendingOrCreateGroup()
                    }
                }
            }
        })
    }

    private fun forceResetAndRetry(deviceAddress: String, delayMs: Long) {
        Log.i(TAG, "Force reset P2P state, will retry $deviceAddress in ${delayMs}ms")
        manager.removeGroup(object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "P2P state cleared ✓")
                resetConnectionState()
                if (deviceAddress.isNotBlank()) {
                    handler.postDelayed({ connectToPeer(deviceAddress) }, delayMs)
                } else {
                    handler.postDelayed({ discoverPeers() }, delayMs)
                }
            }
            override fun onFailure(r: Int) {
                Log.d(TAG, "removeGroup failed (ok if no group): ${reasonStr(r)}")
                resetConnectionState()
                if (deviceAddress.isNotBlank()) {
                    handler.postDelayed({ connectToPeer(deviceAddress) }, delayMs)
                } else {
                    handler.postDelayed({ discoverPeers() }, delayMs)
                }
            }
        })
    }

    private fun scheduleInvitedStuckReset(deviceAddress: String) {
        cancelInvitedStuckReset()
        invitedStuckRunnable = Runnable {
            if (!actuallyConnected) {
                Log.w(TAG, "INVITED stuck for ${INVITED_STUCK_TIMEOUT_MS}ms → force reset + retry $deviceAddress")
                connectAttemptInProgress = false
                forceResetAndRetry(deviceAddress, CONNECT_RETRY_DELAY_MS)
            }
        }.also { handler.postDelayed(it, INVITED_STUCK_TIMEOUT_MS) }
    }

    private fun cancelInvitedStuckReset() {
        invitedStuckRunnable?.let { handler.removeCallbacks(it); invitedStuckRunnable = null }
    }

    private fun tryNextPendingOrCreateGroup() {
        val next = pendingConnects.removeFirstOrNull()
        if (next != null) {
            handler.postDelayed({ connectToPeer(next) }, CONNECT_RETRY_DELAY_MS)
        } else {

            scheduleGroupFallback()
        }
    }



    private fun scheduleGroupFallback() {
        cancelGroupFallback()
        groupFallbackRunnable = Runnable {
            if (!actuallyConnected) {
                Log.i(TAG, "Group fallback triggered — no peers found after timeout")
                createGroupIfNeeded()
            }
        }.also { handler.postDelayed(it, GROUP_FALLBACK_DELAY_MS) }
    }

    private fun cancelGroupFallback() {
        groupFallbackRunnable?.let { handler.removeCallbacks(it); groupFallbackRunnable = null }
    }

    fun createGroupIfNeeded() {
        if (actuallyConnected) return
        if (groupCreationAttempted) {
            Log.d(TAG, "createGroupIfNeeded: already attempted")
            return
        }
        groupCreationAttempted = true

        manager.requestGroupInfo { group: WifiP2pGroup? ->
            if (group != null) {
                Log.i(TAG, "Group exists: ssid=${group.networkName}, isOwner=${group.isGroupOwner}")
                manager.requestConnectionInfo { info: WifiP2pInfo ->
                    actuallyConnected = true
                    _isGroupOwner.value = info.isGroupOwner
                    _groupOwnerAddress.value = if (info.isGroupOwner) GROUP_OWNER_IP
                    else info.groupOwnerAddress?.hostAddress
                    _isConnected.value = true
                }
            } else {
                Log.i(TAG, "Creating autonomous group...")
                manager.createGroup(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Autonomous group created ✓ (IP=$GROUP_OWNER_IP)")
                        actuallyConnected = true
                        _isGroupOwner.value = true
                        _groupOwnerAddress.value = GROUP_OWNER_IP
                        _isConnected.value = true
                    }
                    override fun onFailure(reason: Int) {
                        groupCreationAttempted = false
                        Log.w(TAG, "createGroup() failed: ${reasonStr(reason)}")
                        if (reason == WifiP2pManager.BUSY) {
                            manager.removeGroup(object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    handler.postDelayed({ createGroupIfNeeded() }, 2_000)
                                }
                                override fun onFailure(r: Int) {
                                    handler.postDelayed({ createGroupIfNeeded() }, 3_000)
                                }
                            })
                        }
                    }
                })
            }
        }
    }


    private fun resetConnectionState() {
        groupCreationAttempted = false
        connectAttemptInProgress = false
        actuallyConnected = false
        _groupOwnerAddress.value = null
        _isGroupOwner.value = false
        _isConnected.value = false
        pendingConnects.clear()
        connectRetries.clear()
        cancelInvitedStuckReset()
        cancelGroupFallback()
        Log.d(TAG, "Connection state reset")
    }

    private fun reasonStr(reason: Int) = when (reason) {
        WifiP2pManager.ERROR           -> "INTERNAL_ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY            -> "BUSY"
        else                           -> "reason=$reason"
    }


    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                _isWifiDirectEnabled.value = enabled
                Log.i(TAG, "Wi-Fi Direct: ${if (enabled) "ENABLED" else "DISABLED"}")
                if (enabled) discoverPeers() else resetConnectionState()
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                isDiscovering = false
                manager.requestPeers { deviceList ->
                    val newPeers = deviceList.deviceList.toList()
                    _peers.value = newPeers

                    Log.d(TAG, "Peers updated: ${newPeers.size} device(s)")
                    newPeers.forEachIndexed { i, d ->
                        Log.d(TAG, "  peer[$i] '${d.deviceName}' [${d.deviceAddress}] ${deviceStatusString(d.status)}")
                    }

                    if (actuallyConnected) {
                        Log.d(TAG, "Actually connected, ignoring peer list")
                        return@requestPeers
                    }

                    if (newPeers.isEmpty()) {

                        scheduleGroupFallback()
                        return@requestPeers
                    }

                    val availablePeers = newPeers.filter { it.status == WifiP2pDevice.AVAILABLE }
                    val invitedPeers   = newPeers.filter { it.status == WifiP2pDevice.INVITED }
                    val connectedPeers = newPeers.filter { it.status == WifiP2pDevice.CONNECTED }

                    when {

                        availablePeers.isNotEmpty() -> {
                            Log.i(TAG, "${availablePeers.size} AVAILABLE peer(s) found — connecting...")
                            cancelGroupFallback()

                            if (invitedPeers.isNotEmpty() && !connectAttemptInProgress) {
                                Log.i(TAG, "Clearing stale INVITED state before new connect")
                                manager.removeGroup(object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        resetConnectionState()
                                        val first = availablePeers.first()
                                        availablePeers.drop(1).forEach { pendingConnects.add(it.deviceAddress) }
                                        handler.postDelayed({ connectToPeer(first.deviceAddress) }, 500)
                                    }
                                    override fun onFailure(r: Int) {
                                        resetConnectionState()
                                        val first = availablePeers.first()
                                        availablePeers.drop(1).forEach { pendingConnects.add(it.deviceAddress) }
                                        connectToPeer(first.deviceAddress)
                                    }
                                })
                            } else if (!connectAttemptInProgress) {
                                val first = availablePeers.first()
                                availablePeers.drop(1).forEach { pendingConnects.add(it.deviceAddress) }
                                connectToPeer(first.deviceAddress)
                            }
                        }

                        invitedPeers.isNotEmpty() && connectedPeers.isEmpty() -> {
                            val stuckMs = System.currentTimeMillis() - lastConnectAttemptTime
                            if (stuckMs > INVITED_STUCK_TIMEOUT_MS && !connectAttemptInProgress) {
                                Log.w(TAG, "All peers INVITED for ${stuckMs}ms — forcing reset")
                                val addr = lastConnectAttemptAddress.ifBlank {
                                    invitedPeers.firstOrNull()?.deviceAddress ?: ""
                                }
                                cancelInvitedStuckReset()
                                forceResetAndRetry(addr, CONNECT_RETRY_DELAY_MS)
                            } else {
                                Log.d(TAG, "INVITED peers, waiting... (${stuckMs}ms elapsed)")
                            }
                        }

                        connectedPeers.isNotEmpty() -> {
                            Log.i(TAG, "CONNECTED peer(s) found — requesting connection info")
                            manager.requestConnectionInfo { info: WifiP2pInfo ->
                                if (info.groupFormed) {
                                    actuallyConnected = true
                                    cancelInvitedStuckReset()
                                    cancelGroupFallback()
                                    _isGroupOwner.value = info.isGroupOwner
                                    _groupOwnerAddress.value = if (info.isGroupOwner) GROUP_OWNER_IP
                                    else info.groupOwnerAddress?.hostAddress
                                    _isConnected.value = true
                                    Log.i(TAG, "★ Connected via CONNECTED peer — isOwner=${info.isGroupOwner}")
                                }
                            }
                        }

                        else -> {
                            Log.d(TAG, "No actionable peers, scheduling group fallback")
                            scheduleGroupFallback()
                        }
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                }

                if (networkInfo?.isConnected == true) {
                    actuallyConnected = true
                    cancelInvitedStuckReset()
                    cancelGroupFallback()
                    Log.i(TAG, "P2P connected ✓ — requesting connection info...")

                    manager.requestConnectionInfo { info: WifiP2pInfo ->
                        _isGroupOwner.value = info.isGroupOwner
                        val addr = if (info.isGroupOwner) GROUP_OWNER_IP
                        else info.groupOwnerAddress?.hostAddress
                        _groupOwnerAddress.value = addr
                        _isConnected.value = true

                        Log.i(TAG, if (info.isGroupOwner) "★ Group Owner — IP=$GROUP_OWNER_IP"
                        else "★ Client — GO IP=$addr")

                        manager.requestGroupInfo { group: WifiP2pGroup? ->
                            if (group != null) {
                                Log.i(TAG, "Group: ssid='${group.networkName}', clients=${group.clientList.size}")
                                group.clientList.forEach { Log.i(TAG, "  client: '${it.deviceName}'") }
                            }
                        }
                    }
                } else {
                    if (_groupOwnerAddress.value != null) {
                        Log.i(TAG, "P2P disconnected — resetting")
                    }
                    resetConnectionState()
                    handler.postDelayed({ discoverPeers() }, 1_000)
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                device?.let {
                    Log.i(TAG, "Own device: '${it.deviceName}' [${it.deviceAddress}] ${deviceStatusString(it.status)}")
                }
            }
        }
    }
}