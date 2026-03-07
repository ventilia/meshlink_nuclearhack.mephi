package com.example.meshlink

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import com.example.meshlink.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GlobalCallManager — отслеживает входящие звонки на уровне Application,
 * независимо от того, какой экран сейчас открыт.
 *
 * Проблема, которую решает: рингтон раньше запускался только внутри ChatViewModel
 * конкретного пира, поэтому при звонке с главного экрана, экрана настроек или
 * любого другого экрана рингтон не звучал — пользователь пропускал звонок.
 *
 * Теперь:
 *  - GlobalCallManager живёт в Application-scope и стартует сразу после
 *    инициализации NetworkManager (вызывается из notifyServiceContainerReady()).
 *  - Рингтон играет ВСЕГДА при входящем звонке, пока приложение активно.
 *  - Когда приложение в фоне — уведомление уже показывает NetworkManager,
 *    а рингтон системы играет через уведомление.
 *  - ChatViewModel больше не управляет рингтоном — только UI-состоянием
 *    (принять / отклонить).
 */
class GlobalCallManager(private val app: Application) {

    companion object {
        private const val TAG = "GlobalCallManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ringtonePlayer: MediaPlayer? = null
    private var observeJob: Job? = null

    // ── Публичный API ────────────────────────────────────────────────────────

    /**
     * Подключается к потокам NetworkManager и начинает отслеживать звонки.
     * Вызывать один раз из MeshLinkApp.notifyServiceContainerReady().
     */
    fun attach(networkManager: NetworkManager) {
        observeJob?.cancel()
        observeJob = scope.launch {
            // Входящий звонок → запускаем рингтон
            launch {
                networkManager.callRequest.collectLatest { req ->
                    if (req != null) {
                        Log.i(TAG, "Входящий звонок от ${req.senderId.take(8)} — запускаем рингтон")
                        startRingtone()
                    } else {
                        stopRingtone()
                    }
                }
            }

            // Ответ на звонок (принят / отклонён) → останавливаем рингтон
            launch {
                networkManager.callResponse.collectLatest { res ->
                    if (res != null) {
                        Log.d(TAG, "Ответ на звонок — останавливаем рингтон")
                        stopRingtone()
                    }
                }
            }

            // Звонок завершён удалённо → останавливаем рингтон
            launch {
                networkManager.callEnd.collectLatest { end ->
                    if (end != null) {
                        Log.d(TAG, "Звонок завершён — останавливаем рингтон")
                        stopRingtone()
                    }
                }
            }
        }
        Log.i(TAG, "GlobalCallManager подключён к NetworkManager")
    }

    /**
     * Явная остановка рингтона — вызывается из ChatViewModel при принятии
     * или отклонении звонка, чтобы рингтон прекратился мгновенно без
     * ожидания emit в flow.
     */
    fun stopRingtone() {
        try {
            ringtonePlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        ringtonePlayer = null
    }

    /**
     * Освобождает ресурсы при уничтожении Application.
     * На практике Application живёт всё время, но для порядка.
     */
    fun release() {
        stopRingtone()
        observeJob?.cancel()
        scope.cancel()
        Log.i(TAG, "GlobalCallManager освобождён")
    }

    // ── Внутренние методы ────────────────────────────────────────────────────

    private fun startRingtone() {
        // Не запускаем повторно, если уже играет
        ringtonePlayer?.let {
            if (it.isPlaying) return
        }
        stopRingtone()

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return

            ringtonePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
                Log.i(TAG, "Рингтон запущен ✓")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось запустить рингтон: ${e.message}")
            ringtonePlayer = null
        }
    }
}