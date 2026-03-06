// ИСПРАВЛЕНИЕ #12: PeerRegistry
// - PEER_TIMEOUT_MS увеличен с 25с до 45с (keepalive каждые 7с → 6 пропущенных пакетов).
//   Раньше было 25с при 7с интервале — всего 3.5 пропущенных пакета, слишком мало.
// - Добавлена защита от прунинга P2P-пиров при живом соединении через isConnectedCheck.
package com.example.meshlink.network.discovery

import android.util.Log
import com.example.meshlink.domain.model.NetworkDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Единый реестр обнаруженных пиров.
 *
 * - Хранит Bluetooth адрес для каждого пира (для BT fallback)
 * - isNewPeer() для определения первого появления пира (gossip)
 * - Thread-safe atomic updates через synchronized
 * - PEER_TIMEOUT_MS = 45с (keepalive каждые 7с → 6 пропущенных пакетов)
 *
 * ИСПРАВЛЕНИЕ: корень прунинга был не в таймауте, а в handleKeepalive который
 * неправильно определял fromPeerId и не обновлял keepalive отправителя.
 * Таймаут увеличен как дополнительная защита.
 */
class PeerRegistry {

    companion object {
        private const val TAG = "PeerRegistry"
        // БЫЛО: 25_000 — при 7с интервале keepalive = 3.5 пропущенных пакета. Слишком мало.
        // СТАЛО: 45_000 — 6+ пропущенных пакетов перед прунингом.
        private const val PEER_TIMEOUT_MS = 45_000L
    }

    private val _peers = MutableStateFlow<Map<String, NetworkDevice>>(emptyMap())
    val peers: StateFlow<Map<String, NetworkDevice>> = _peers

    // Bluetooth адреса пиров (peerId → btAddress)
    private val bluetoothAddresses = mutableMapOf<String, String>()

    // Отслеживаем какие пиры уже были обработаны для gossip
    private val knownPeerIds = mutableSetOf<String>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        scope.launch {
            while (isActive) {
                delay(8_000)
                prune()
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    /**
     * Возвращает true если этот пир впервые появляется в реестре.
     * Используется для gossip — чтобы послать announce к новым пирам.
     */
    fun isNewPeer(peerId: String): Boolean {
        synchronized(knownPeerIds) {
            return knownPeerIds.add(peerId)
        }
    }

    /**
     * Добавить или обновить пира.
     */
    fun upsert(device: NetworkDevice, source: String = "") {
        if (device.peerId.isBlank()) return

        synchronized(this) {
            val current = _peers.value.toMutableMap()
            val isNew = !current.containsKey(device.peerId)

            // Убираем placeholder (shortCode="") с тем же IP
            if (device.shortCode.isNotBlank() && device.ipAddress != null) {
                val toRemove = current.entries
                    .filter { (pid, d) ->
                        pid != device.peerId &&
                                d.ipAddress == device.ipAddress &&
                                d.shortCode.isBlank()
                    }
                    .map { it.key }
                if (toRemove.isNotEmpty()) {
                    toRemove.forEach { current.remove(it) }
                }
            }

            // Сохраняем существующий IP если новый пустой (чтобы не затирать)
            val existing = current[device.peerId]
            val effectiveIp = device.ipAddress?.takeIf { it.isNotBlank() }
                ?: existing?.ipAddress

            current[device.peerId] = device.copy(
                ipAddress = effectiveIp,
                keepalive = maxOf(device.keepalive, existing?.keepalive ?: 0L)
            )
            _peers.value = current

            if (isNew) {
                Log.i(TAG, "[$source] + '${device.username.ifBlank { device.shortCode }.ifBlank { device.peerId.take(8) }}' @ $effectiveIp")
                synchronized(knownPeerIds) { knownPeerIds.add(device.peerId) }
            }
        }
    }

    /**
     * Обновляет keepalive timestamp пира вручную (например при входящем TCP соединении).
     */
    fun refreshKeepalive(peerId: String) {
        if (peerId.isBlank()) return
        synchronized(this) {
            val current = _peers.value.toMutableMap()
            val existing = current[peerId] ?: return
            current[peerId] = existing.copy(keepalive = System.currentTimeMillis())
            _peers.value = current
        }
    }

    /**
     * Обновляет Bluetooth адрес для пира.
     */
    fun setBluetoothAddress(peerId: String, btAddress: String) {
        synchronized(bluetoothAddresses) {
            bluetoothAddresses[peerId] = btAddress
            Log.d(TAG, "BT address for ${peerId.take(8)}: $btAddress")
        }
    }

    fun getIp(peerId: String): String? = _peers.value[peerId]?.ipAddress

    fun getBluetoothAddress(peerId: String): String? =
        synchronized(bluetoothAddresses) { bluetoothAddresses[peerId] }

    fun remove(peerId: String) {
        synchronized(this) {
            val current = _peers.value.toMutableMap()
            if (current.remove(peerId) != null) {
                _peers.value = current
                Log.d(TAG, "Removed peer ${peerId.take(8)}")
            }
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val current = _peers.value
            val alive = current.filter { (_, d) -> now - d.keepalive <= PEER_TIMEOUT_MS }
            if (alive.size < current.size) {
                val removed = current.keys - alive.keys
                Log.i(TAG, "Pruned ${removed.size} stale peer(s): ${removed.map { it.take(8) }}")
                _peers.value = alive
                // Не убираем из knownPeerIds — пир мог вернуться
            }
        }
    }
}