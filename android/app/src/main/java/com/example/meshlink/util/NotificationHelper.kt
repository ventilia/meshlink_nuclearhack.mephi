package com.example.meshlink.util

import android.annotation.SuppressLint
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


object NotificationHelper {

    const val CHANNEL_MESSAGES = "meshlink_messages"
    const val CHANNEL_CALLS    = "meshlink_calls"

    private const val CALL_NOTIFICATION_ID = 9001


    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                enableLights(true)
                enableVibration(true)
            }

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


    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        context: Context,
        senderName: String,
        text: String,
        peerId: String
    ) {

        if (!hasNotificationPermission(context)) return

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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(300)))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

            .setGroup("meshlink_messages_$peerId")
            .build()


        NotificationManagerCompat.from(context).notify(peerId.hashCode(), notification)
    }


    @SuppressLint("MissingPermission")
    fun showCallNotification(
        context: Context,
        callerName: String,
        peerId: String
    ) {
        if (!hasNotificationPermission(context)) return

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
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)

            .setFullScreenIntent(openPendingIntent, true)

            .setOngoing(true)
            .build()

        NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notification)
    }


    fun dismissCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)
    }


    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}