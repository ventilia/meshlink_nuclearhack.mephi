package com.example.meshlink.network.discovery

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*


class UdpDiscovery(private val tcpPort: Int = 8800) {

    companion object {
        private const val TAG = "UdpDiscovery"
        const val UDP_PORT = 8810
        private const val BROADCAST_INTERVAL_MS = 8_000L
        private const val PEER_TIMEOUT_MS = 40_000L
        private const val MAX_PACKET_SIZE = 2048
    }

    @Serializable
    data class AnnouncePacket(
        val peerId: String,
        val username: String,
        val shortCode: String,
        val publicKeyHex: String,
        val tcpPort: Int,
        val selfIp: String = ""
    )

    data class DiscoveredPeer(
        val ip: String,
        val announce: AnnouncePacket,
        val lastSeen: Long
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _peers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val peers: StateFlow<Map<String, DiscoveredPeer>> = _peers

    @Volatile private var receiveSocket: DatagramSocket? = null
    @Volatile var ownPeerId: String = ""; private set
    @Volatile private var ownUsername: String = ""
    @Volatile private var ownShortCode: String = ""
    @Volatile private var ownPublicKeyHex: String = ""
    @Volatile private var ownIp: String = ""

    @Volatile private var ownIpSet: Set<String> = emptySet()

    fun updateOwnIdentity(peerId: String, username: String, shortCode: String, publicKeyHex: String) {
        ownPeerId = peerId
        ownUsername = username
        ownShortCode = shortCode
        ownPublicKeyHex = publicKeyHex
    }

    fun updateOwnIp(ip: String) {
        ownIp = ip

        ownIpSet = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                }
                ?.toSet() ?: setOf(ip)
        } catch (e: Exception) {
            setOf(ip)
        }
        Log.d(TAG, "Own IPs updated: $ownIpSet")
    }

    fun start() {
        startReceiver()
        startBroadcaster()
        startPruner()
        Log.i(TAG, "UDP discovery started on port $UDP_PORT (interval=${BROADCAST_INTERVAL_MS}ms)")
    }

    fun stop() {
        scope.cancel()
        runCatching { receiveSocket?.close() }
        Log.i(TAG, "UDP discovery stopped")
    }



    private fun startReceiver() {
        scope.launch {
            var retries = 0
            while (isActive) {
                try {
                    val socket = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 2_000
                        bind(InetSocketAddress(UDP_PORT))
                    }
                    receiveSocket = socket
                    Log.i(TAG, "UDP receiver bound on port $UDP_PORT ✓")
                    retries = 0

                    val buf = ByteArray(MAX_PACKET_SIZE)
                    val packet = DatagramPacket(buf, buf.size)

                    while (isActive) {
                        try {
                            packet.setData(buf, 0, buf.size)
                            socket.receive(packet)
                            val senderIp = packet.address?.hostAddress ?: continue
                            val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            Log.v(TAG, "UDP packet from $senderIp (${packet.length} bytes)")
                            handleAnnounce(senderIp, data)
                        } catch (_: SocketTimeoutException) {
                            // нормально — нужно для корректного stop()
                        } catch (e: Exception) {
                            if (!isActive) break
                            Log.w(TAG, "Receive loop error: ${e.message}")
                        }
                    }
                    socket.close()

                } catch (e: Exception) {
                    if (!isActive) break
                    retries++
                    val delay = minOf(2_000L * retries, 15_000L)
                    Log.e(TAG, "Receiver start FAILED (retry $retries in ${delay}ms): ${e.message}")
                    delay(delay)
                }
            }
        }
    }

    private fun handleAnnounce(senderIp: String, data: String) {
        try {
            val announce = json.decodeFromString<AnnouncePacket>(data)

            Log.d(TAG, "Announce from=$senderIp peer=${announce.peerId.take(8)} own=${ownPeerId.take(8)}")

            if (announce.peerId.isBlank()) return


            if (announce.peerId == ownPeerId || senderIp in ownIpSet) {
                Log.d(TAG, "Skip self-announce from $senderIp (self-reflection via secondary interface, ownIpSet=$ownIpSet)")
                return
            }

            val effectiveIp = announce.selfIp.takeIf { it.isNotBlank() } ?: senderIp
            val now = System.currentTimeMillis()
            val current = _peers.value.toMutableMap()
            val isNew = !current.containsKey(announce.peerId)

            current[announce.peerId] = DiscoveredPeer(
                ip = effectiveIp,
                announce = announce,
                lastSeen = now
            )
            _peers.value = current

            if (isNew) {
                Log.i(TAG, "✓ New peer via UDP: '${announce.username}' sc=${announce.shortCode} @ $effectiveIp")
            } else {
                Log.v(TAG, "Updated peer '${announce.username}' @ $effectiveIp")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad UDP packet from $senderIp: ${e.message} | data='${data.take(200)}'")
        }
    }



    private fun startBroadcaster() {
        scope.launch {
            delay(500)
            while (isActive) {
                if (ownPeerId.isNotBlank()) broadcastAnnounce()
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    private fun broadcastAnnounce() {
        val data = buildAnnouncePayload() ?: return
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1_000

                val sent = mutableSetOf<String>()


                getBroadcastAddresses().forEach { addr ->
                    if (sent.add(addr)) {
                        runCatching {
                            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(addr), UDP_PORT))
                            Log.v(TAG, "Announce broadcast → $addr:$UDP_PORT")
                        }
                    }
                }


                _peers.value.values.forEach { peer ->
                    if (sent.add(peer.ip)) {
                        runCatching {
                            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(peer.ip), UDP_PORT))
                            Log.v(TAG, "Announce unicast → ${peer.ip}:$UDP_PORT")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Broadcaster error: ${e.message}")
        }
    }

    private fun buildAnnouncePayload(): ByteArray? {
        if (ownPeerId.isBlank()) return null
        return json.encodeToString(
            AnnouncePacket(
                peerId = ownPeerId,
                username = ownUsername,
                shortCode = ownShortCode,
                publicKeyHex = ownPublicKeyHex,
                tcpPort = tcpPort,
                selfIp = ownIp
            )
        ).encodeToByteArray()
    }

    private fun getBroadcastAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.forEach { iface ->
                    iface.interfaceAddresses?.forEach { ifAddr ->
                        val addr = ifAddr.address
                        val broadcast = ifAddr.broadcast
                        if (addr is Inet4Address && broadcast != null) {
                            broadcast.hostAddress?.takeIf { it !in result }?.let { result.add(it) }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "getBroadcastAddresses: ${e.message}")
        }
        if (result.isEmpty()) result.add("255.255.255.255")
        return result
    }



    private fun startPruner() {
        scope.launch {
            while (isActive) {
                delay(15_000)
                val now = System.currentTimeMillis()
                val current = _peers.value
                val alive = current.filter { now - it.value.lastSeen < PEER_TIMEOUT_MS }
                if (alive.size < current.size) {
                    Log.i(TAG, "UDP pruned ${current.size - alive.size} stale peer(s)")
                    _peers.value = alive
                }
            }
        }
    }


    fun sendDirectAnnounce(ip: String) {
        if (ownPeerId.isBlank() || ip.isBlank()) return
        scope.launch {
            try {
                val data = buildAnnouncePayload() ?: return@launch
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), UDP_PORT))
                }
                Log.d(TAG, "Direct announce → $ip")
            } catch (e: Exception) {
                Log.d(TAG, "Direct announce to $ip failed: ${e.message}")
            }
        }
    }
}