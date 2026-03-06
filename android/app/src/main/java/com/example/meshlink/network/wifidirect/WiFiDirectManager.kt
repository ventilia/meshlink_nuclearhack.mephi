package com.example.meshlink.network.wifidirect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log


class WiFiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WiFiDirectMgr"
    }


    private val wifiP2pManager: WifiP2pManager? by lazy {
        (context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager).also {
            if (it != null) Log.i(TAG, "WifiP2pManager obtained ✓")
            else Log.e(TAG, "WifiP2pManager unavailable on this device ✗")
        }
    }

    private val channel: WifiP2pManager.Channel? by lazy {
        wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "⚠ Channel disconnected — Wi-Fi Direct framework reset. " +
                    "App may need to reinitialize.")
        }.also {
            if (it != null) Log.i(TAG, "WifiP2pManager.Channel initialized ✓")
            else Log.e(TAG, "WifiP2pManager.Channel init FAILED ✗")
        }
    }


    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val ok = fineLocation || nearbyWifi
        if (!ok) {
            Log.w(TAG, "Missing permissions: ACCESS_FINE_LOCATION=$fineLocation, " +
                    "NEARBY_WIFI_DEVICES=$nearbyWifi (API ${Build.VERSION.SDK_INT})")
        }
        return ok
    }

    private fun checkReady(operation: String): Boolean {
        if (wifiP2pManager == null) {
            Log.e(TAG, "$operation: WifiP2pManager is null (Wi-Fi Direct not supported?)")
            return false
        }
        if (channel == null) {
            Log.e(TAG, "$operation: Channel is null (initialization failed?)")
            return false
        }
        return true
    }


    private fun reasonStr(reason: Int) = when (reason) {
        WifiP2pManager.ERROR           -> "INTERNAL_ERROR($reason)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED($reason)"
        WifiP2pManager.BUSY            -> "BUSY($reason)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS($reason)"
        else                           -> "UNKNOWN($reason)"
    }


    fun discoverPeers(listener: WifiP2pManager.ActionListener) {
        if (!checkReady("discoverPeers")) {
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "discoverPeers: skipped — insufficient permissions")
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        try {
            Log.d(TAG, "discoverPeers() → starting Wi-Fi Direct scan...")
            wifiP2pManager!!.discoverPeers(channel!!, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "discoverPeers: scan started ✓")
                    listener.onSuccess()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers: FAILED — ${reasonStr(reason)}")
                    listener.onFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "discoverPeers: SecurityException — ${e.message}")
            listener.onFailure(WifiP2pManager.ERROR)
        }
    }


    fun stopPeerDiscovery(listener: WifiP2pManager.ActionListener? = null) {
        if (!checkReady("stopPeerDiscovery")) return
        val cb = listener ?: object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "stopPeerDiscovery: stopped ✓") }
            override fun onFailure(r: Int) { Log.w(TAG, "stopPeerDiscovery: failed — ${reasonStr(r)}") }
        }
        wifiP2pManager!!.stopPeerDiscovery(channel!!, cb)
    }

    fun requestPeers(callback: (WifiP2pDeviceList) -> Unit) {
        if (!checkReady("requestPeers")) return
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "requestPeers: skipped — insufficient permissions")
            return
        }
        try {
            wifiP2pManager!!.requestPeers(channel!!) { deviceList: WifiP2pDeviceList ->
                val count = deviceList.deviceList.size
                Log.d(TAG, "requestPeers result: $count device(s) found")
                deviceList.deviceList.forEachIndexed { i, d ->
                    Log.v(TAG, "  peer[$i] name='${d.deviceName}' mac=${d.deviceAddress} " +
                            "status=${d.status}")
                }
                callback(deviceList)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "requestPeers: SecurityException — ${e.message}")
        }
    }


    fun createGroup(listener: WifiP2pManager.ActionListener) {
        if (!checkReady("createGroup")) {
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "createGroup: skipped — insufficient permissions")
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        try {
            Log.i(TAG, "createGroup() → requesting autonomous group ownership...")
            wifiP2pManager!!.createGroup(channel!!, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "createGroup: group created ✓  (this device = Group Owner, IP=192.168.49.1)")
                    listener.onSuccess()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "createGroup: FAILED — ${reasonStr(reason)}")
                    listener.onFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "createGroup: SecurityException — ${e.message}")
            listener.onFailure(WifiP2pManager.ERROR)
        }
    }


    fun removeGroup(listener: WifiP2pManager.ActionListener) {
        if (!checkReady("removeGroup")) {
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        try {
            Log.i(TAG, "removeGroup() → dissolving current P2P group...")
            wifiP2pManager!!.removeGroup(channel!!, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "removeGroup: group dissolved ✓")
                    listener.onSuccess()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "removeGroup: FAILED — ${reasonStr(reason)}")
                    listener.onFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "removeGroup: SecurityException — ${e.message}")
            listener.onFailure(WifiP2pManager.ERROR)
        }
    }


    fun requestConnectionInfo(callback: (WifiP2pInfo) -> Unit) {
        if (!checkReady("requestConnectionInfo")) return
        wifiP2pManager!!.requestConnectionInfo(channel!!) { info: WifiP2pInfo ->
            Log.d(TAG, "requestConnectionInfo: groupFormed=${info.groupFormed} " +
                    "isGroupOwner=${info.isGroupOwner} " +
                    "ownerAddr=${info.groupOwnerAddress?.hostAddress ?: "null"}")
            callback(info)
        }
    }


    fun requestGroupInfo(callback: (WifiP2pGroup?) -> Unit) {
        if (!checkReady("requestGroupInfo")) return
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "requestGroupInfo: skipped — insufficient permissions")
            callback(null)
            return
        }
        try {
            wifiP2pManager!!.requestGroupInfo(channel!!) { group: WifiP2pGroup? ->
                if (group != null) {
                    Log.d(TAG, "requestGroupInfo: ssid='${group.networkName}' " +
                            "isOwner=${group.isGroupOwner} " +
                            "clients=${group.clientList.size} " +
                            "passphrase='${group.passphrase}'")
                    group.clientList.forEach { c ->
                        Log.v(TAG, "  client: '${c.deviceName}' [${c.deviceAddress}]")
                    }
                } else {
                    Log.d(TAG, "requestGroupInfo: no active group")
                }
                callback(group)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "requestGroupInfo: SecurityException — ${e.message}")
            callback(null)
        }
    }

    fun connect(config: WifiP2pConfig, listener: WifiP2pManager.ActionListener) {
        if (!checkReady("connect")) {
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "connect: skipped — insufficient permissions")
            listener.onFailure(WifiP2pManager.ERROR)
            return
        }
        try {
            Log.i(TAG, "connect() → deviceAddress=${config.deviceAddress} " +
                    "groupOwnerIntent=${config.groupOwnerIntent}")
            wifiP2pManager!!.connect(channel!!, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "connect: connection initiated ✓ (invitation sent)")
                    listener.onSuccess()
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "connect: FAILED — ${reasonStr(reason)}")
                    listener.onFailure(reason)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "connect: SecurityException — ${e.message}")
            listener.onFailure(WifiP2pManager.ERROR)
        }
    }


    fun cancelConnect(listener: WifiP2pManager.ActionListener? = null) {
        if (!checkReady("cancelConnect")) return
        val cb = listener ?: object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "cancelConnect: cancelled ✓") }
            override fun onFailure(r: Int) { Log.w(TAG, "cancelConnect: failed — ${reasonStr(r)}") }
        }
        wifiP2pManager!!.cancelConnect(channel!!, cb)
    }
}