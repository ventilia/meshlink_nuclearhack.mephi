package com.example.meshlink.core

import android.util.Log

/**
 * MeshLogger — централизованное логирование с уровнями и русскими тегами.
 *
 * Уровни управления: каждую категорию можно включить/выключить.
 * Verbose-логи (пакеты, keepalive) по умолчанию выключены в release-сборке.
 */
object MeshLogger {

    // ── Теги ──────────────────────────────────────────────────────────────
    private const val СЕТЬ      = "МешЛинк/Сеть"
    private const val СООБЩ     = "МешЛинк/Сообщения"
    private const val ФАЙЛ      = "МешЛинк/Файлы"
    private const val ЗВОНОК    = "МешЛинк/Звонки"
    private const val МАРШРУТ   = "МешЛинк/Маршрутизация"
    private const val АУДИО     = "МешЛинк/Аудио"
    private const val СИСТЕМА   = "МешЛинк/Система"
    private const val БЕЗОПАС   = "МешЛинк/Безопасность"

    // ── Флаги управления детализацией ────────────────────────────────────
    var logVerbosePackets  = false  // отдельные пакеты — по умолчанию выключено
    var logVerboseKeepalive = false // keepalive — по умолчанию выключено
    var logVerboseAudio    = false  // аудио-фрагменты — по умолчанию выключено
    var logDebugRouting    = true   // изменения таблицы маршрутов

    // ── СЕТЬ ──────────────────────────────────────────────────────────────

    fun пирОбнаружен(peerId: String, username: String, shortCode: String, ip: String?) {
        Log.i(СЕТЬ, "✅ Новый пир | ${peerId.take(8)}... ник=$username код=$shortCode ip=${ip ?: "—"}")
    }

    fun пирОтключился(peerId: String, username: String) {
        Log.i(СЕТЬ, "❌ Пир отключился | ${peerId.take(8)}... ник=$username")
    }

    fun пирПропал(peerId: String) {
        Log.w(СЕТЬ, "⚠️ Пир не отвечает | ${peerId.take(8)}...")
    }

    fun соединениеУстановлено(peerId: String, ip: String, transport: String = "Wi-Fi Direct") {
        Log.i(СЕТЬ, "🔗 Соединение | $transport | ${peerId.take(8)}... @ $ip")
    }

    fun соединениеОшибка(peerId: String, ошибка: String) {
        Log.e(СЕТЬ, "🔴 Ошибка соединения | ${peerId.take(8)}... | $ошибка")
    }

    fun bleПирОбнаружен(peerId: String, shortCode: String, btAddress: String) {
        Log.i(СЕТЬ, "📡 BLE пир | ${peerId.take(8)}... код=$shortCode bt=$btAddress")
    }

    fun bleПирПотерян(btAddress: String) {
        Log.d(СЕТЬ, "📡 BLE пир потерян | bt=$btAddress")
    }

    fun bleСтатус(статус: String) {
        Log.i(СЕТЬ, "📡 BLE: $статус")
    }

    // ── МАРШРУТИЗАЦИЯ ─────────────────────────────────────────────────────

    fun meshМаршрутДобавлен(peerId: String, viaPeerId: String, hopCount: Int) {
        if (!logDebugRouting) return
        Log.i(МАРШРУТ, "🕸️ Маршрут добавлен | к ${peerId.take(8)}... через ${viaPeerId.take(8)}... хопов=$hopCount")
    }

    fun meshМаршрутУдалён(peerId: String) {
        if (!logDebugRouting) return
        Log.i(МАРШРУТ, "🗑️ Маршрут удалён | ${peerId.take(8)}...")
    }

    fun таблицаМаршрутов(количество: Int) {
        if (!logDebugRouting) return
        Log.d(МАРШРУТ, "📋 Таблица маршрутов обновлена | записей=$количество")
    }

    fun пакетРетранслируется(тип: String, от: String, кому: String, ttl: Int) {
        Log.d(МАРШРУТ, "🔀 Relay | тип=$тип | ${от.take(8)}→${кому.take(8)} | ttl=$ttl")
    }

    fun keepaliveОтправлен(количество: Int) {
        if (!logVerboseKeepalive) return
        Log.v(МАРШРУТ, "💓 Keepalive отправлен | получателей=$количество")
    }

    fun keepaliveПолучен(fromPeerId: String, устройств: Int) {
        if (!logVerboseKeepalive) return
        Log.v(МАРШРУТ, "💓 Keepalive получен | от ${fromPeerId.take(8)}... устройств=$устройств")
    }

    // ── СООБЩЕНИЯ ─────────────────────────────────────────────────────────

    fun сообщениеОтправлено(тип: String, кому: String, messageId: Long) {
        Log.i(СООБЩ, "📤 Отправлено | тип=$тип кому=${кому.take(8)}... id=$messageId")
    }

    fun сообщениеПолучено(тип: String, от: String, messageId: Long) {
        Log.i(СООБЩ, "📥 Получено | тип=$тип от=${от.take(8)}... id=$messageId")
    }

    fun сообщениеДублируется(messageId: Long) {
        Log.d(СООБЩ, "🔄 Дубль отброшен | id=$messageId")
    }

    fun подтверждениеПолучено(messageId: Long, от: String) {
        Log.i(СООБЩ, "✅ Доставлено | id=$messageId от=${от.take(8)}...")
    }

    fun подтверждениеПрочтения(messageId: Long, от: String) {
        Log.i(СООБЩ, "👁️ Прочитано | id=$messageId от=${от.take(8)}...")
    }

    fun сообщениеСохранено(messageId: Long, тип: String) {
        Log.d(СООБЩ, "💾 Сохранено в БД | id=$messageId тип=$тип")
    }

    // ── ФАЙЛЫ ─────────────────────────────────────────────────────────────

    fun файлОтправляется(имя: String, кому: String, размерБайт: Long) {
        Log.i(ФАЙЛ, "📤 Отправка файла | $имя → ${кому.take(8)}... ${размерБайт / 1024}КБ")
    }

    fun файлОтправлен(имя: String, кому: String) {
        Log.i(ФАЙЛ, "✅ Файл отправлен | $имя → ${кому.take(8)}...")
    }

    fun файлОшибкаОтправки(имя: String, ошибка: String) {
        Log.e(ФАЙЛ, "🔴 Ошибка отправки | $имя | $ошибка")
    }

    fun файлПолучается(имя: String, от: String, размерБайт: Long) {
        Log.i(ФАЙЛ, "📥 Приём файла | $имя от ${от.take(8)}... ${размерБайт / 1024}КБ")
    }

    fun файлСохранён(имя: String, путь: String) {
        Log.i(ФАЙЛ, "💾 Файл сохранён | $имя → $путь")
    }

    fun файлОшибкаСохранения(имя: String, ошибка: String) {
        Log.e(ФАЙЛ, "🔴 Ошибка сохранения | $имя | $ошибка")
    }

    fun файлЧанк(transferId: String, chunk: Int, total: Int) {
        if (!logVerbosePackets) return
        Log.v(ФАЙЛ, "📦 Чанк $chunk/$total | transfer=${transferId.take(8)}")
    }

    fun файлПересборка(имя: String, успех: Boolean) {
        if (успех) Log.i(ФАЙЛ, "✅ Файл собран | $имя")
        else Log.e(ФАЙЛ, "🔴 Ошибка сборки файла | $имя")
    }

    fun изображениеПолучено(имя: String, от: String) {
        Log.i(ФАЙЛ, "🖼️ Изображение получено | $имя от ${от.take(8)}...")
    }

    // ── АУДИО ─────────────────────────────────────────────────────────────

    fun аудиоОтправляется(кому: String, длинаМс: Long) {
        Log.i(АУДИО, "🎤 Голосовое → ${кому.take(8)}... ~${длинаМс}мс")
    }

    fun аудиоПолучено(от: String, имя: String) {
        Log.i(АУДИО, "🎵 Голосовое от ${от.take(8)}... | $имя")
    }

    fun аудиоЗапись(статус: String) {
        Log.d(АУДИО, "🎙️ Запись: $статус")
    }

    fun аудиоВоспроизведение(имя: String, статус: String) {
        Log.d(АУДИО, "🔊 Воспроизведение: $статус | $имя")
    }

    fun фрагментЗвонкаОтправлен(кому: String, размерБайт: Int) {
        if (!logVerboseAudio) return
        Log.v(АУДИО, "🎙️ Фрагмент → ${кому.take(8)}... ${размерБайт}Б")
    }

    fun фрагментЗвонкаПолучен(от: String, размерБайт: Int) {
        if (!logVerboseAudio) return
        Log.v(АУДИО, "🔊 Фрагмент ← ${от.take(8)}... ${размерБайт}Б")
    }

    // ── ЗВОНКИ ────────────────────────────────────────────────────────────

    fun звонокИсходящий(кому: String) {
        Log.i(ЗВОНОК, "📞 Исходящий → ${кому.take(8)}...")
    }

    fun звонокВходящий(от: String) {
        Log.i(ЗВОНОК, "📲 Входящий ← ${от.take(8)}...")
    }

    fun звонокПринят(пиром: String) {
        Log.i(ЗВОНОК, "✅ Звонок принят | ${пиром.take(8)}...")
    }

    fun звонокОтклонён(пиром: String) {
        Log.i(ЗВОНОК, "❌ Звонок отклонён | ${пиром.take(8)}...")
    }

    fun звонокАктивен(пир: String) {
        Log.i(ЗВОНОК, "🔴 Звонок активен | ${пир.take(8)}...")
    }

    fun звонокЗавершён(пир: String, инициатор: String = "локально") {
        Log.i(ЗВОНОК, "📵 Звонок завершён | ${пир.take(8)}... инициатор=$инициатор")
    }

    // ── БЕЗОПАСНОСТЬ ──────────────────────────────────────────────────────

    fun дублирующийПакетОтброшен(ключ: String, от: String) {
        Log.d(БЕЗОПАС, "🔄 Дубль | ключ=${ключ.take(12)} от=${от.take(8)}...")
    }

    fun rateLimitПревышен(peerId: String) {
        Log.w(БЕЗОПАС, "🚫 Rate limit | ${peerId.take(8)}...")
    }

    fun подозрительнаяАктивность(peerId: String, причина: String) {
        Log.w(БЕЗОПАС, "⚠️ Подозрительная активность | ${peerId.take(8)}... | $причина")
    }

    // ── СИСТЕМА ───────────────────────────────────────────────────────────

    fun системаСтарт(peerId: String, shortCode: String) {
        Log.i(СИСТЕМА, "🚀 MeshLink запущен | ${peerId.take(16)}... код=$shortCode")
    }

    fun системаОшибка(контекст: String, ошибка: String) {
        Log.e(СИСТЕМА, "💥 Ошибка | $контекст | $ошибка")
    }

    fun профильОбновлён(peerId: String, username: String) {
        Log.i(СИСТЕМА, "👤 Профиль обновлён | ${peerId.take(8)}... ник=$username")
    }

    fun запросПрофиля(от: String, кому: String) {
        Log.d(СИСТЕМА, "📋 Запрос профиля | ${от.take(8)}... → ${кому.take(8)}...")
    }

    fun профильОтправлен(кому: String) {
        Log.d(СИСТЕМА, "📋 Профиль отправлен | ${кому.take(8)}...")
    }

    // ── Совместимость со старым кодом ─────────────────────────────────────
    // Алиасы для предотвращения ошибок компиляции при переименовании функций

    fun файлОтправкаОшибка(имяФайла: String, ошибка: String) = файлОшибкаОтправки(имяФайла, ошибка)
    fun файлОшибкаСохранения2(имяФайла: String, ошибка: String) = файлОшибкаСохранения(имяФайла, ошибка)

    // ── Пакеты (verbose, по умолчанию выключено) ──────────────────────────

    fun пакетОтправлен(тип: String, кому: String, размерБайт: Int) {
        if (!logVerbosePackets) return
        Log.v(СИСТЕМА, "→ $тип к ${кому.take(8)}... ${размерБайт}Б")
    }

    fun пакетПолучен(тип: String, от: String, размерБайт: Int) {
        if (!logVerbosePackets) return
        Log.v(СИСТЕМА, "← $тип от ${от.take(8)}... ${размерБайт}Б")
    }

    fun пакетОшибкаДесериализации(тип: String, ошибка: String) {
        Log.e(СИСТЕМА, "🔴 Ошибка разбора пакета | тип=$тип | $ошибка")
    }

    fun пакетОтброшен(причина: String) {
        Log.w(СИСТЕМА, "⚠️ Пакет отброшен | $причина")
    }
}