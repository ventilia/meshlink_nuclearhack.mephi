package com.example.meshlink.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.MeshLinkAppProvider
import com.example.meshlink.core.MeshLogger
import com.example.meshlink.network.CallMetrics
import com.example.meshlink.network.CallQuality
import com.example.meshlink.network.VideoCallManager
import com.example.meshlink.network.VideoMetrics
import com.example.meshlink.ui.screen.VideoCallState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

class VideoCallViewModel(
    application: Application,
    val peerId: String
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoCallViewModel"
        private const val OUTGOING_TIMEOUT_MS = 45_000L
    }

    private val container = (application as MeshLinkApp).container
    private val networkManager = container.networkManager
    private val videoCallManager: VideoCallManager? = container.videoCallManager
    private val callManager = container.callManager

    // ── UI состояние ──────────────────────────────────────────────────────────

    private val _callState = MutableStateFlow(VideoCallState.OUTGOING)
    val callState: StateFlow<VideoCallState> = _callState

    private val _metrics = MutableStateFlow(VideoMetrics())
    val metrics: StateFlow<VideoMetrics> = _metrics

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(true)  // По умолчанию включён для видео
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera

    private val _isCameraOn = MutableStateFlow(true)
    val isCameraOn: StateFlow<Boolean> = _isCameraOn

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration

    // ── Внутреннее состояние ──────────────────────────────────────────────────

    private var durationJob: Job? = null
    private var metricsJob: Job? = null
    private var timeoutJob: Job? = null

    init {
        Log.i(TAG, "VideoCallViewModel created for ${peerId.take(8)}")
        observeCallSignals()
        observeVideoManagerCallbacks()
        observeMetrics()
    }

    // ── Инициализация разрешений ──────────────────────────────────────────────

    fun onPermissionsGranted(isIncoming: Boolean) {
        Log.i(TAG, "Permissions granted, isIncoming=$isIncoming")
        if (isIncoming) {
            _callState.value = VideoCallState.INCOMING
        } else {
            // Исходящий — уже показываем OUTGOING, ждём принятия через сигналинг
            startOutgoingTimeout()
        }
    }

    // ── Наблюдение сигналинга ─────────────────────────────────────────────────

    private fun observeCallSignals() {
        // Ответ на исходящий звонок
        viewModelScope.launch {
            networkManager.callResponse.collect { res ->
                if (res != null && res.senderId == peerId) {
                    timeoutJob?.cancel()
                    Log.i(TAG, "callResponse from ${peerId.take(8)}: accepted=${res.accepted}")
                    if (res.accepted) {
                        MeshLogger.звонокПринят(peerId)
                        startVideoCall()
                    } else {
                        MeshLogger.звонокОтклонён(peerId)
                        _callState.value = VideoCallState.ENDED
                    }
                }
            }
        }

        // Завершение звонка удалённой стороной
        viewModelScope.launch {
            networkManager.callEnd.collect { end ->
                if (end != null && end.senderId == peerId) {
                    MeshLogger.звонокЗавершён(peerId, "удалённый пир")
                    Log.i(TAG, "callEnd from ${peerId.take(8)}")
                    stopVideoCall()
                    _callState.value = VideoCallState.ENDED
                }
            }
        }
    }

    private fun observeVideoManagerCallbacks() {
        val manager = videoCallManager ?: return

        manager.onConnected = { connectedPeerId ->
            if (connectedPeerId == peerId) {
                Log.i(TAG, "✅ WebRTC connected to ${peerId.take(8)}")
                _callState.value = VideoCallState.ACTIVE
                startDurationTimer()
            }
        }

        manager.onDisconnected = { disconnectedPeerId ->
            if (disconnectedPeerId == peerId) {
                Log.w(TAG, "❌ WebRTC disconnected from ${peerId.take(8)}")
                _callState.value = VideoCallState.ENDED
            }
        }
    }

    private fun observeMetrics() {
        metricsJob = viewModelScope.launch {
            val manager = videoCallManager
            if (manager != null) {
                manager.metrics.collect { vm ->
                    _metrics.value = vm
                }
            } else {
                // Fallback — аудио метрики
                callManager.metrics.collect { am ->
                    _metrics.value = VideoMetrics(
                        rttMs           = am.rttMs,
                        lossRatePercent = am.lossRatePercent,
                        jitterMs        = am.jitterMs,
                        quality         = am.quality
                    )
                }
            }
        }
    }

    // ── Управление звонком ────────────────────────────────────────────────────

    fun acceptCall() {
        Log.i(TAG, "acceptCall: ${peerId.take(8)}")
        MeshLogger.звонокПринят(peerId)
        networkManager.sendCallResponse(peerId, true)
        _callState.value = VideoCallState.ACTIVE
        startVideoCall()
    }

    fun rejectCall() {
        Log.i(TAG, "rejectCall: ${peerId.take(8)}")
        MeshLogger.звонокОтклонён(peerId)
        networkManager.sendCallResponse(peerId, false)
        _callState.value = VideoCallState.ENDED
    }

    fun cancelCall() {
        Log.i(TAG, "cancelCall (timeout/user): ${peerId.take(8)}")
        timeoutJob?.cancel()
        networkManager.sendCallEnd(peerId)
        stopVideoCall()
        _callState.value = VideoCallState.ENDED
    }

    fun endCall() {
        Log.i(TAG, "endCall: ${peerId.take(8)}")
        MeshLogger.звонокЗавершён(peerId, "локально")
        networkManager.sendCallEnd(peerId)
        stopVideoCall()
        _callState.value = VideoCallState.ENDED
    }

    private fun startVideoCall() {
        MeshLogger.звонокАктивен(peerId)
        Log.i(TAG, "Starting video call session with ${peerId.take(8)}")

        val manager = videoCallManager
        if (manager != null) {
            // WebRTC путь — лучшее качество, адаптивный битрейт, DTLS шифрование
            Log.i(TAG, "Using WebRTC for video call")
            manager.startSession(peerId, null)
            // Включаем динамик для видеозвонка
            setSpeakerEnabled(true)
        } else {
            // Fallback — UDP аудио без видео
            Log.w(TAG, "VideoCallManager not available, falling back to UDP audio")
            val ip = networkManager.getIpForPeer(peerId)
            if (ip != null) {
                callManager.startSession(ip)
                setSpeakerEnabled(true)
                // Без WebRTC переходим в ACTIVE сразу
                _callState.value = VideoCallState.ACTIVE
                startDurationTimer()
            } else {
                Log.e(TAG, "No IP available for ${peerId.take(8)}")
                _callState.value = VideoCallState.ENDED
            }
        }
    }

    private fun stopVideoCall() {
        durationJob?.cancel()
        durationJob = null
        _callDuration.value = 0L

        videoCallManager?.stopSession()
        callManager.stopSession()
        networkManager.resetCallState()
    }

    private fun startOutgoingTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(OUTGOING_TIMEOUT_MS)
            if (isActive && _callState.value == VideoCallState.OUTGOING) {
                Log.w(TAG, "Outgoing video call timeout for ${peerId.take(8)}")
                MeshLogger.звонокЗавершён(peerId, "таймаут")
                networkManager.sendCallEnd(peerId)
                _callState.value = VideoCallState.ENDED
            }
        }
    }

    private fun startDurationTimer() {
        _callDuration.value = 0L
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _callDuration.value++
            }
        }
    }

    // ── Управление медиа во время звонка ─────────────────────────────────────

    /**
     * Заглушить/разглушить микрофон.
     * Реальное заглушение через WebRtcEngine.setMuted() или CallManager.setMuted().
     */
    fun toggleMute() {
        val muted = !_isMuted.value
        _isMuted.value = muted
        Log.d(TAG, "Mute: $muted")

        if (videoCallManager != null) {
            videoCallManager.setMuted(muted)
        } else {
            callManager.setMuted(muted)
        }
    }

    /**
     * Переключить динамик/наушники.
     * Реальное переключение через AudioManager.
     */
    fun toggleSpeaker() {
        val speakerOn = !_isSpeakerOn.value
        _isSpeakerOn.value = speakerOn
        setSpeakerEnabled(speakerOn)
    }

    private fun setSpeakerEnabled(enabled: Boolean) {
        if (_isSpeakerOn.value != enabled) _isSpeakerOn.value = enabled
        // CallManager управляет AudioManager
        // ИСПРАВЛЕНО: isSpeakerOn — это property, не function (убраны скобки)
        if (enabled != callManager.isSpeakerOn) {  // <-- ИСПРАВЛЕНО: без ()
            callManager.toggleSpeaker()
        }
    }

    /**
     * Включить/выключить камеру.
     */
    fun toggleCamera() {
        val cameraOn = !_isCameraOn.value
        _isCameraOn.value = cameraOn
        videoCallManager?.setCameraEnabled(cameraOn)
        Log.d(TAG, "Camera: $cameraOn")
    }

    /**
     * Переключить фронтальную/тыловую камеру.
     */
    fun flipCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
        videoCallManager?.flipCamera()
        Log.d(TAG, "Camera flipped: front=${_isFrontCamera.value}")
    }

    // ── Рендереры ─────────────────────────────────────────────────────────────

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        videoCallManager?.attachLocalRenderer(renderer)
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        videoCallManager?.detachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        videoCallManager?.attachRemoteRenderer(renderer)
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        videoCallManager?.detachRemoteRenderer(renderer)
    }

    // ── Жизненный цикл ────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared for ${peerId.take(8)}")
        durationJob?.cancel()
        metricsJob?.cancel()
        timeoutJob?.cancel()
        stopVideoCall()
    }
}

class VideoCallViewModelFactory(private val peerId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoCallViewModel::class.java)) {
            val app = MeshLinkAppProvider.app ?: error("MeshLinkApp не инициализирован")
            return VideoCallViewModel(app as Application, peerId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}