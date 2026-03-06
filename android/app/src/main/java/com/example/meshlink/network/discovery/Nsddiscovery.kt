package com.example.meshlink.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * NSD (mDNS/Bonjour) discovery — находит устройства в LAN сети.
 *
 * Работает поверх UDP: когда WiFi Direct недоступен или устройства
 * подключены к одному роутеру — NSD обнаруживает их через multicast DNS.
 *
 * Это третий уровень discovery (после UDP broadcast и WiFi Direct):
 *   WiFi Direct → UDP broadcast → NSD/mDNS
 *
 * Ключевые особенности:
 * - Регистрирует сервис с именем содержащим peerId (для идентификации)
 * - При нахождении сервиса — резолвим IP и добавляем в callback
 * - Автоматический рестарт discovery при потере сервисов
 */
class NsdDiscovery(
    private val context: Context,
    private val onPeerFound: (ip: String, port: Int, peerId: String, username: String) -> Unit,
    private val onPeerLost: (peerId: String) -> Unit
) {
    companion object {
        private const val TAG = "NsdDiscovery"
        private const val SERVICE_TYPE = "_meshlink._tcp."
        private const val KEY_PEER_ID = "pid"
        private const val KEY_USERNAME = "usr"
        private const val KEY_SHORT_CODE = "sc"
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolving = mutableSetOf<String>()

    private var ownServiceName: String = ""
    private var isDiscovering = false
    private var isRegistered = false

    fun start(peerId: String, username: String, shortCode: String, tcpPort: Int) {
        if (peerId.isBlank()) return
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable")
            return
        }

        ownServiceName = "ml_${peerId.take(12)}"
        registerService(peerId, username, shortCode, tcpPort)
        startDiscovery()
    }

    fun stop() {
        stopDiscovery()
        unregisterService()
        nsdManager = null
    }

    // ── Регистрация нашего сервиса ─────────────────────────────────

    private fun registerService(
        peerId: String,
        username: String,
        shortCode: String,
        port: Int
    ) {
        val mgr = nsdManager ?: return
        if (isRegistered) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = ownServiceName
            serviceType = SERVICE_TYPE
            setPort(port)
            // TXT записи — дополнительная информация о пире
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute(KEY_PEER_ID, peerId.take(32))
                setAttribute(KEY_USERNAME, username.take(32))
                setAttribute(KEY_SHORT_CODE, shortCode)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD registration failed: $code")
                isRegistered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD unregistration failed: $code")
            }

            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD registered: '${info.serviceName}' on port $port")
                isRegistered = true
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD unregistered: '${info.serviceName}'")
                isRegistered = false
            }
        }

        registrationListener = listener
        try {
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD registerService error: ${e.message}")
        }
    }

    private fun unregisterService() {
        val mgr = nsdManager ?: return
        val listener = registrationListener ?: return
        try {
            mgr.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD unregisterService error: ${e.message}")
        }
        registrationListener = null
        isRegistered = false
    }

    // ── Discovery ──────────────────────────────────────────────────

    private fun startDiscovery() {
        val mgr = nsdManager ?: return
        if (isDiscovering) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                Log.w(TAG, "NSD discovery start failed: $code")
                isDiscovering = false
                // Пробуем перезапустить через 5 секунд
                Thread.sleep(5000)
                startDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {
                Log.w(TAG, "NSD discovery stop failed: $code")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "NSD discovery started for $serviceType")
                isDiscovering = true
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped")
                isDiscovering = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                // Фильтруем себя
                if (name == ownServiceName) return
                Log.d(TAG, "NSD service found: '$name'")
                // Резолвим только если ещё не резолвим этот сервис
                if (resolving.add(name)) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                Log.d(TAG, "NSD service lost: '$name'")
                resolving.remove(name)
                // Извлекаем peerId из имени сервиса
                val peerId = name.removePrefix("ml_")
                if (peerId.isNotBlank()) onPeerLost(peerId)
            }
        }

        discoveryListener = listener
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD discoverServices error: ${e.message}")
        }
    }

    private fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        if (!isDiscovering) return
        try {
            mgr.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NSD stopDiscovery error: ${e.message}")
        }
        discoveryListener = null
        isDiscovering = false
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD resolve failed for '${info.serviceName}': $code")
                resolving.remove(info.serviceName)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving.remove(info.serviceName)
                val ip = info.host?.hostAddress ?: run {
                    Log.w(TAG, "NSD resolve: no host address for '${info.serviceName}'")
                    return
                }
                val port = info.port
                val name = info.serviceName

                // Извлекаем peerId из имени или TXT записей
                var peerId = name.removePrefix("ml_")
                var username = ""

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        val attrs = info.attributes
                        val pidBytes = attrs[KEY_PEER_ID]
                        if (pidBytes != null) peerId = String(pidBytes)
                        val usrBytes = attrs[KEY_USERNAME]
                        if (usrBytes != null) username = String(usrBytes)
                    } catch (e: Exception) {
                        Log.d(TAG, "NSD TXT parse error: ${e.message}")
                    }
                }

                Log.i(TAG, "NSD resolved: '${info.serviceName}' @ $ip:$port peerId=${peerId.take(8)}")
                onPeerFound(ip, port, peerId, username)
            }
        })
    }
}