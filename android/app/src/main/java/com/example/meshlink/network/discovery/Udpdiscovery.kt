package com.example.meshlink.network.discovery

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*

/**
 * UdpDiscovery — ИСПРАВЛЕННАЯ ВЕРСИЯ v5
 *
 * ИСПРАВЛЕНИЕ #10 (ГЛАВНОЕ): Рефлексия broadcast через WiFi Direct интерфейс.
 *
 * ПРОБЛЕМА: Устройство имеет два активных интерфейса одновременно:
 *   - wlan0  → 192.168.0.106  (обычный Wi-Fi, роутер)
 *   - p2p0   → 192.168.49.1   (WiFi Direct group owner IP)
 *
 * DatagramSocket, биндированный на 0.0.0.0:8810, получает пакеты со ВСЕХ интерфейсов.
 * Broadcast, отправленный на 192.168.0.255, возвращается обратно через p2p-интерфейс
 * с source IP 192.168.49.1. Поскольку peerId в нём совпадает с собственным — пакет
 * отбрасывается как self-announce. Устройство B при этом никогда не видит пакеты A,
 * потому что находится в подсети 192.168.49.x (другой L2), куда роутер не пробрасывает
 * broadcast.
 *
 * РЕШЕНИЕ:
 * 1. ownIpSet — множество ВСЕХ собственных IPv4 адресов со всех интерфейсов.
 *    handleAnnounce отбрасывает пакет если senderIp ∈ ownIpSet (а не только по peerId).
 * 2. broadcastAnnounce() дополнительно шлёт unicast на IP всех уже известных пиров.
 *    Это критично для WiFi Direct: после первого TCP-соединения IP партнёра известен,
 *    и unicast надёжно доходит через p2p-интерфейс даже когда broadcast не работает.
 * 3. sendDirectAnnounce() остаётся для немедленного unicast при установке P2P-соединения.
 *
 * АНТИСПАМ:
 * - Интервал broadcast: 8с (меньше трафика)
 * - Один DatagramSocket вместо MulticastSocket — нет дублей пакетов
 */
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

    /**
     * Множество ВСЕХ собственных IPv4-адресов со всех интерфейсов.
     * Обновляется при каждом вызове updateOwnIp().
     * Используется в handleAnnounce для отсева рефлексий broadcast.
     */
    @Volatile private var ownIpSet: Set<String> = emptySet()

    fun updateOwnIdentity(peerId: String, username: String, shortCode: String, publicKeyHex: String) {
        ownPeerId = peerId
        ownUsername = username
        ownShortCode = shortCode
        ownPublicKeyHex = publicKeyHex
    }

    fun updateOwnIp(ip: String) {
        ownIp = ip
        // Собираем ВСЕ собственные IPv4 адреса со всех активных интерфейсов.
        // Это необходимо чтобы отфильтровать собственные broadcast-пакеты,
        // которые возвращаются через WiFi Direct (p2p0) интерфейс с другим source IP.
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

    // ─── Приёмник ────────────────────────────────────────────────────────────

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

            // ИСПРАВЛЕНИЕ #10: фильтруем как по peerId, так и по IP отправителя.
            // Если senderIp принадлежит нашему устройству (любой интерфейс) —
            // это рефлексия собственного broadcast через WiFi Direct p2p интерфейс.
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

    // ─── Отправщик ──────────────────────────────────────────────────────────

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

                // 1. Broadcast на каждый интерфейс (как раньше)
                getBroadcastAddresses().forEach { addr ->
                    if (sent.add(addr)) {
                        runCatching {
                            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(addr), UDP_PORT))
                            Log.v(TAG, "Announce broadcast → $addr:$UDP_PORT")
                        }
                    }
                }

                // 2. ИСПРАВЛЕНИЕ #10: unicast на IP всех уже известных пиров.
                // Критично для WiFi Direct: когда оба устройства в P2P-группе,
                // broadcast не проходит между подсетями (192.168.0.x и 192.168.49.x).
                // Но unicast на конкретный IP партнёра доходит всегда.
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

    // ─── Pruner ─────────────────────────────────────────────────────────────

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

    // ─── Direct unicast ─────────────────────────────────────────────────────

    /**
     * Немедленно отправить announce напрямую на конкретный IP.
     * Вызывается из NetworkManager при установке WiFi Direct соединения,
     * а также при переподключении к известным пирам.
     */
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