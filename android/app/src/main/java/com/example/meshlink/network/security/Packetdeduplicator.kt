package com.example.meshlink.network.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * PacketDeduplicator — защита от дублирования пакетов, replay-атак, петель и спама.
 *
 * Принцип работы:
 * - Каждое сообщение идентифицируется по ключу: senderId + messageId (или transferId).
 * - При первом получении пакета он регистрируется с временной меткой.
 * - Повторное получение того же ключа в течение TTL отклоняется.
 * - Rate-limiter считает количество пакетов от каждого пира за окно времени.
 * - Если пир превышает лимит — пакеты от него временно блокируются.
 *
 * Потокобезопасно через ConcurrentHashMap.
 */
class PacketDeduplicator {

    companion object {
        private const val TAG = "МешЛинк/Безопасность"

        // Пакет считается дублем, если получен повторно в течение этого времени
        private const val DEDUP_WINDOW_MS = 120_000L   // 2 минуты

        // Очистка устаревших записей каждые N операций
        private const val CLEANUP_INTERVAL = 500

        // Rate limiting: максимум пакетов за окно времени
        private const val RATE_LIMIT_WINDOW_MS = 1_000L  // 1 секунда
        private const val RATE_LIMIT_MAX_PACKETS = 50     // максимум 50 пакетов/сек от одного пира
        private const val RATE_LIMIT_BLOCK_MS = 5_000L   // блокировка на 5 секунд при превышении
    }

    // Ключ → время первого получения
    private val seen = ConcurrentHashMap<String, Long>()

    // peerId → (количество пакетов, начало окна)
    private val rateCounts = ConcurrentHashMap<String, RateWindow>()

    // peerId → время снятия блокировки (0 = не заблокирован)
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    private var opCount = 0

    private data class RateWindow(val count: Int, val windowStart: Long)

    /**
     * Проверяет, нужно ли обработать пакет.
     *
     * @param senderId  peerId отправителя
     * @param packetKey уникальный ключ пакета (messageId, transferId и т.п.)
     * @param packetType тип пакета для логирования
     * @return true если пакет новый и не превышает лимит; false если дубль или спам
     */
    fun shouldProcess(senderId: String, packetKey: String, packetType: String = ""): Boolean {
        val now = System.currentTimeMillis()

        // Проверяем блокировку по rate limit
        val blocked = blockedUntil[senderId] ?: 0L
        if (now < blocked) {
            Log.w(TAG, "⛔ Пакет отклонён — пир заблокирован за спам: ${senderId.take(8)}... тип=$packetType")
            return false
        }

        // Проверяем дедупликацию
        val dedupeKey = "$senderId:$packetKey"
        val firstSeen = seen.putIfAbsent(dedupeKey, now)
        if (firstSeen != null) {
            // Пакет уже видели
            if (now - firstSeen < DEDUP_WINDOW_MS) {
                Log.d(TAG, "🔄 Дубль отброшен: key=${packetKey.take(12)} от ${senderId.take(8)}...")
                return false
            } else {
                // Истёк TTL — разрешаем повтор (обновляем время)
                seen[dedupeKey] = now
            }
        }

        // Rate limit
        if (!checkRateLimit(senderId, now)) {
            blockedUntil[senderId] = now + RATE_LIMIT_BLOCK_MS
            Log.w(TAG, "🚫 Rate limit превышен, пир заблокирован: ${senderId.take(8)}... на ${RATE_LIMIT_BLOCK_MS / 1000}с")
            return false
        }

        // Периодическая очистка старых записей
        opCount++
        if (opCount % CLEANUP_INTERVAL == 0) {
            cleanup(now)
        }

        return true
    }

    /**
     * Упрощённая проверка для keepalive (без дедупликации по id, только rate limit).
     */
    fun shouldProcessKeepalive(senderId: String): Boolean {
        val now = System.currentTimeMillis()
        val blocked = blockedUntil[senderId] ?: 0L
        if (now < blocked) return false
        return checkRateLimit(senderId, now)
    }

    /**
     * Проверяет, не видели ли мы уже этот пакет для relay-форвардинга.
     * Используется чтобы не ретранслировать пакет обратно источнику.
     */
    fun markForwarded(senderId: String, packetKey: String): Boolean {
        val now = System.currentTimeMillis()
        val forwardKey = "fwd:$senderId:$packetKey"
        val existing = seen.putIfAbsent(forwardKey, now)
        return existing == null // true = ещё не форвардили, можно форвардить
    }

    /**
     * Сбросить блокировку пира (например, после переподключения).
     */
    fun unblockPeer(peerId: String) {
        blockedUntil.remove(peerId)
        rateCounts.remove(peerId)
        Log.d(TAG, "Блокировка снята: ${peerId.take(8)}...")
    }

    /**
     * Принудительно зарегистрировать пакет как отправленный нами,
     * чтобы избежать его обработки при получении обратно (петля).
     */
    fun markAsSent(ownId: String, packetKey: String) {
        seen["$ownId:$packetKey"] = System.currentTimeMillis()
    }

    // ── Внутренние методы ─────────────────────────────────────────────────

    private fun checkRateLimit(senderId: String, now: Long): Boolean {
        val window = rateCounts[senderId]
        return if (window == null || now - window.windowStart > RATE_LIMIT_WINDOW_MS) {
            rateCounts[senderId] = RateWindow(1, now)
            true
        } else if (window.count < RATE_LIMIT_MAX_PACKETS) {
            rateCounts[senderId] = window.copy(count = window.count + 1)
            true
        } else {
            false
        }
    }

    private fun cleanup(now: Long) {
        val cutoff = now - DEDUP_WINDOW_MS
        val toRemove = seen.entries
            .filter { it.value < cutoff }
            .map { it.key }
        toRemove.forEach { seen.remove(it) }

        // Снимаем истёкшие блокировки
        blockedUntil.entries
            .filter { it.value < now }
            .forEach { blockedUntil.remove(it.key) }

        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Очистка: удалено ${toRemove.size} устаревших записей дедупликации")
        }
    }
}