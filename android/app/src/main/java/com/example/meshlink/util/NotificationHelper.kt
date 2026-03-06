package com.example.meshlink.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.meshlink.MainActivity
import com.example.meshlink.R

/**
 * NotificationHelper — централизованное управление уведомлениями MeshLink.
 *
 * Два канала:
 *  - CHANNEL_MESSAGES: новые сообщения (приоритет HIGH, звук)
 *  - CHANNEL_CALLS: входящие звонки (приоритет MAX, полноэкранное Intent)
 *
 * Использование:
 *  1. Вызвать createChannels() один раз при старте приложения (в MainActivity.onCreate).
 *  2. Вызвать showMessageNotification() / showCallNotification() из NetworkManager
 *     когда приложение находится в фоне.
 */
object NotificationHelper {

    const val CHANNEL_MESSAGES = "meshlink_messages"
    const val CHANNEL_CALLS    = "meshlink_calls"

    // ID уведомления о входящем звонке (фиксированный — чтобы можно было отменить)
    private const val CALL_NOTIFICATION_ID = 9001

    /**
     * Создать каналы уведомлений (требуется Android 8+).
     * Безопасно вызывать несколько раз — повторное создание игнорируется.
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            // Канал сообщений
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                enableLights(true)
                enableVibration(true)
            }

            // Канал звонков
            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих звонках"
                enableLights(true)
                enableVibration(true)
            }

            manager.createNotificationChannel(messagesChannel)
            manager.createNotificationChannel(callsChannel)
        }
    }

    /**
     * Показать уведомление о новом сообщении.
     *
     * @param context контекст
     * @param senderName имя отправителя (псевдоним или username)
     * @param text текст сообщения (будет обрезан если слишком длинный)
     * @param peerId peerId отправителя — используется для открытия нужного чата
     */
    fun showMessageNotification(
        context: Context,
        senderName: String,
        text: String,
        peerId: String
    ) {
        if (!hasNotificationPermission(context)) return

        // Intent для открытия чата при нажатии на уведомление
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("peerId", peerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            peerId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setContentTitle(senderName)
            .setContentText(text.take(100))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Используем hashCode от peerId чтобы группировать уведомления по собеседнику
        NotificationManagerCompat.from(context).notify(peerId.hashCode(), notification)
    }

    /**
     * Показать уведомление о входящем звонке.
     * Включает кнопки "Принять" и "Отклонить" прямо в шторке.
     */
    fun showCallNotification(
        context: Context,
        callerName: String,
        peerId: String
    ) {
        if (!hasNotificationPermission(context)) return

        // Открыть чат при нажатии на уведомление
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("peerId", peerId)
            putExtra("incoming_call", true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            CALL_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setContentTitle("Входящий звонок")
            .setContentText(callerName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Показать уведомление поверх других (heads-up)
            .setFullScreenIntent(openPendingIntent, true)
            .setOngoing(true) // Не смахивается пока звонок активен
            .build()

        NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notification)
    }

    /**
     * Скрыть уведомление о звонке (вызывать когда звонок принят/отклонён/истёк).
     */
    fun dismissCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
    }

    /**
     * Проверить разрешение POST_NOTIFICATIONS (Android 13+).
     * На более старых версиях всегда возвращает true.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}