package com.example.meshlink.core

import android.util.Log


object MeshLogger {

    private const val TAG_СЕТЬ      = "МешЛинк/Сеть"
    private const val TAG_СООБЩ     = "МешЛинк/Сообщения"
    private const val TAG_ФАЙЛ      = "МешЛинк/Файлы"
    private const val TAG_ЗВОНОК    = "МешЛинк/Звонки"
    private const val TAG_ПАКЕТ     = "МешЛинк/Пакеты"
    private const val TAG_МАРШРУТ   = "МешЛинк/Маршрутизация"
    private const val TAG_АУДИО     = "МешЛинк/Аудио"
    private const val TAG_СИСТЕМА   = "МешЛинк/Система"



    fun пирОбнаружен(peerId: String, username: String, shortCode: String, ip: String?) {
        Log.i(TAG_СЕТЬ, "✅ НОВЫЙ ПИР ОБНАРУЖЕН | id=${peerId.take(8)}... | ник=$username | код=$shortCode | ip=${ip ?: "неизвестно"}")
    }

    fun пирОтключился(peerId: String, username: String) {
        Log.i(TAG_СЕТЬ, "❌ ПИР ОТКЛЮЧИЛСЯ | id=${peerId.take(8)}... | ник=$username")
    }

    fun пирПропал(peerId: String) {
        Log.w(TAG_СЕТЬ, "⚠️ ПИР НЕ ОТВЕЧАЕТ (keepalive истёк) | id=${peerId.take(8)}...")
    }

    fun соединениеУстановлено(peerId: String, ip: String, transport: String = "Wi-Fi Direct") {
        Log.i(TAG_СЕТЬ, "🔗 СОЕДИНЕНИЕ УСТАНОВЛЕНО | транспорт=$transport | id=${peerId.take(8)}... | ip=$ip")
    }

    fun соединениеОшибка(peerId: String, ошибка: String) {
        Log.e(TAG_СЕТЬ, "🔴 ОШИБКА СОЕДИНЕНИЯ | id=${peerId.take(8)}... | причина=$ошибка")
    }

    fun meshМаршрутДобавлен(peerId: String, viaPeerId: String, hopCount: Int) {
        Log.i(TAG_МАРШРУТ, "🕸️ MESH МАРШРУТ | к=${peerId.take(8)}... | через=${viaPeerId.take(8)}... | хопов=$hopCount")
    }

    fun meshМаршрутУдалён(peerId: String) {
        Log.i(TAG_МАРШРУТ, "🗑️ MESH МАРШРУТ УДАЛЁН | к=${peerId.take(8)}...")
    }

    fun таблицаМаршрутов(количество: Int) {
        Log.d(TAG_МАРШРУТ, "📋 ТАБЛИЦА МАРШРУТОВ ОБНОВЛЕНА | записей=$количество")
    }

    fun keepaliveОтправлен(количество: Int = 0) {
        Log.v(TAG_ПАКЕТ, "💓 KEEPALIVE ОТПРАВЛЕН | получателей=$количество")
    }

    fun keepaliveПолучен(fromPeerId: String, устройствВСписке: Int) {
        Log.v(TAG_ПАКЕТ, "💓 KEEPALIVE ПОЛУЧЕН | от=${fromPeerId.take(8)}... | устройств в пакете=$устройствВСписке")
    }



    fun сообщениеОтправлено(тип: String, кому: String, messageId: Long) {
        Log.i(TAG_СООБЩ, "📤 СООБЩЕНИЕ ОТПРАВЛЕНО | тип=$тип | кому=${кому.take(8)}... | id=$messageId")
    }

    fun сообщениеПолучено(тип: String, от: String, messageId: Long) {
        Log.i(TAG_СООБЩ, "📥 СООБЩЕНИЕ ПОЛУЧЕНО | тип=$тип | от=${от.take(8)}... | id=$messageId")
    }

    fun сообщениеПересылается(messageId: Long, от: String, кому: String, ttl: Int) {
        Log.d(TAG_СООБЩ, "🔀 СООБЩЕНИЕ ПЕРЕСЫЛАЕТСЯ (relay) | id=$messageId | от=${от.take(8)} → кому=${кому.take(8)} | ttl=$ttl")
    }

    fun сообщениеДублируется(messageId: Long) {
        Log.d(TAG_СООБЩ, "🔄 ДУБЛИРУЮЩИЙ ПАКЕТ ОТБРОШЕН | id=$messageId")
    }

    fun подтверждениеПолучено(messageId: Long, от: String) {
        Log.i(TAG_СООБЩ, "✅ ПОДТВЕРЖДЕНИЕ ДОСТАВКИ | id=$messageId | от=${от.take(8)}...")
    }

    fun подтверждениеПрочтения(messageId: Long, от: String) {
        Log.i(TAG_СООБЩ, "👁️ ПОДТВЕРЖДЕНИЕ ПРОЧТЕНИЯ | id=$messageId | от=${от.take(8)}...")
    }

    fun сообщениеСохранено(messageId: Long, тип: String) {
        Log.d(TAG_СООБЩ, "💾 СООБЩЕНИЕ СОХРАНЕНО В БД | id=$messageId | тип=$тип")
    }


    fun файлОтправляется(имяФайла: String, кому: String, размерБайт: Long) {
        val размерКб = размерБайт / 1024
        Log.i(TAG_ФАЙЛ, "📤 ОТПРАВКА ФАЙЛА НАЧАТА | файл=$имяФайла | кому=${кому.take(8)}... | размер=${размерКб}КБ")
    }

    fun файлОтправлен(имяФайла: String, кому: String) {
        Log.i(TAG_ФАЙЛ, "✅ ФАЙЛ УСПЕШНО ОТПРАВЛЕН | файл=$имяФайла | кому=${кому.take(8)}...")
    }

    fun файлОтправкаОшибка(имяФайла: String, ошибка: String) {
        Log.e(TAG_ФАЙЛ, "🔴 ОШИБКА ОТПРАВКИ ФАЙЛА | файл=$имяФайла | причина=$ошибка")
    }

    fun файлПолучается(имяФайла: String, от: String, размерБайт: Long) {
        val размерКб = размерБайт / 1024
        Log.i(TAG_ФАЙЛ, "📥 ПОЛУЧЕНИЕ ФАЙЛА | файл=$имяФайла | от=${от.take(8)}... | размер=${размерКб}КБ")
    }

    fun файлСохранён(имяФайла: String, путь: String) {
        Log.i(TAG_ФАЙЛ, "💾 ФАЙЛ СОХРАНЁН | файл=$имяФайла | путь=$путь")
    }

    fun файлОшибкаСохранения(имяФайла: String, ошибка: String) {
        Log.e(TAG_ФАЙЛ, "🔴 ОШИБКА СОХРАНЕНИЯ ФАЙЛА | файл=$имяФайла | причина=$ошибка")
    }

    fun изображениеПолучено(имяФайла: String, от: String) {
        Log.i(TAG_ФАЙЛ, "🖼️ ИЗОБРАЖЕНИЕ ПОЛУЧЕНО | файл=$имяФайла | от=${от.take(8)}...")
    }

    fun аудиоОтправляется(кому: String, длинаМс: Long = 0) {
        Log.i(TAG_АУДИО, "🎤 ГОЛОСОВОЕ СООБЩЕНИЕ ОТПРАВЛЯЕТСЯ | кому=${кому.take(8)}... | длина~${длинаМс}мс")
    }

    fun аудиоПолучено(от: String, имяФайла: String) {
        Log.i(TAG_АУДИО, "🎵 ГОЛОСОВОЕ СООБЩЕНИЕ ПОЛУЧЕНО | от=${от.take(8)}... | файл=$имяФайла")
    }

    fun аудиоЗапись(статус: String) {
        Log.d(TAG_АУДИО, "🎙️ ЗАПИСЬ АУДИО: $статус")
    }

    fun аудиоВоспроизведение(имяФайла: String, статус: String) {
        Log.d(TAG_АУДИО, "🔊 ВОСПРОИЗВЕДЕНИЕ: $статус | файл=$имяФайла")
    }



    fun звонокИсходящий(кому: String) {
        Log.i(TAG_ЗВОНОК, "📞 ИСХОДЯЩИЙ ЗВОНОК | кому=${кому.take(8)}...")
    }

    fun звонокВходящий(от: String) {
        Log.i(TAG_ЗВОНОК, "📲 ВХОДЯЩИЙ ЗВОНОК | от=${от.take(8)}...")
    }

    fun звонокПринят(пиром: String) {
        Log.i(TAG_ЗВОНОК, "✅ ЗВОНОК ПРИНЯТ | пир=${пиром.take(8)}...")
    }

    fun звонокОтклонён(пиром: String) {
        Log.i(TAG_ЗВОНОК, "❌ ЗВОНОК ОТКЛОНЁН | пир=${пиром.take(8)}...")
    }

    fun звонокАктивен(пир: String) {
        Log.i(TAG_ЗВОНОК, "🔴 ЗВОНОК АКТИВЕН | пир=${пир.take(8)}...")
    }

    fun звонокЗавершён(пир: String, инициатор: String = "локально") {
        Log.i(TAG_ЗВОНОК, "📵 ЗВОНОК ЗАВЕРШЁН | пир=${пир.take(8)}... | инициатор=$инициатор")
    }

    fun фрагментЗвонкаОтправлен(кому: String, размерБайт: Int) {
        Log.v(TAG_ЗВОНОК, "🎙️ АУДИО ФРАГМЕНТ ОТПРАВЛЕН | кому=${кому.take(8)}... | байт=$размерБайт")
    }

    fun фрагментЗвонкаПолучен(от: String, размерБайт: Int) {
        Log.v(TAG_ЗВОНОК, "🔊 АУДИО ФРАГМЕНТ ПОЛУЧЕН | от=${от.take(8)}... | байт=$размерБайт")
    }



    fun пакетОтправлен(тип: String, кому: String, размерБайт: Int) {
        Log.v(TAG_ПАКЕТ, "→ ПАКЕТ ОТПРАВЛЕН | тип=$тип | кому=${кому.take(8)}... | байт=$размерБайт")
    }

    fun пакетПолучен(тип: String, от: String, размерБайт: Int) {
        Log.v(TAG_ПАКЕТ, "← ПАКЕТ ПОЛУЧЕН | тип=$тип | от=${от.take(8)}... | байт=$размерБайт")
    }

    fun пакетОшибкаДесериализации(тип: String, ошибка: String) {
        Log.e(TAG_ПАКЕТ, "🔴 ОШИБКА РАЗБОРА ПАКЕТА | тип=$тип | причина=$ошибка")
    }

    fun пакетОтброшен(причина: String) {
        Log.w(TAG_ПАКЕТ, "⚠️ ПАКЕТ ОТБРОШЕН | причина=$причина")
    }



    fun системаСтарт(peerId: String, shortCode: String) {
        Log.i(TAG_СИСТЕМА, "🚀 MESHLINK ЗАПУЩЕН | peerId=${peerId.take(16)}... | shortCode=$shortCode")
    }

    fun системаОшибка(контекст: String, ошибка: String) {
        Log.e(TAG_СИСТЕМА, "💥 СИСТЕМНАЯ ОШИБКА | контекст=$контекст | ошибка=$ошибка")
    }

    fun профильОбновлён(peerId: String, username: String) {
        Log.i(TAG_СИСТЕМА, "👤 ПРОФИЛЬ ОБНОВЛЁН | id=${peerId.take(8)}... | ник=$username")
    }

    fun запросПрофиля(от: String, кому: String) {
        Log.d(TAG_СИСТЕМА, "📋 ЗАПРОС ПРОФИЛЯ | от=${от.take(8)}... | кому=${кому.take(8)}...")
    }

    fun профильОтправлен(кому: String) {
        Log.d(TAG_СИСТЕМА, "📋 ПРОФИЛЬ ОТПРАВЛЕН | кому=${кому.take(8)}...")
    }
}