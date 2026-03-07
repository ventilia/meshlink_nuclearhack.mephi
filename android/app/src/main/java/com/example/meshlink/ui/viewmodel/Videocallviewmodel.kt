// ФАЙЛ: C:\Users\GAMER\AndroidStudioProjects\meshlink_nuclearhack.mephi\android\app\src\main\java\com\example\meshlink\ui\viewmodel\Videocallviewmodel.kt
package com.example.meshlink.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.MeshLinkAppProvider
import com.example.meshlink.core.MeshLogger
import com.example.meshlink.domain.model.CallType
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

    private val _isSpeakerOn = MutableStateFlow(true)
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
    private var isIncoming: Boolean = false

    init {
        Log.i(TAG, "VideoCallViewModel created for ${peerId.take(8)}")
        setupSignalingCallbacks()
        observeCallSignals()
        observeVideoManagerCallbacks()
        observeMetrics()
    }

    // ── ИСПРАВЛЕНО: Привязка генерации WebRTC сигналов к NetworkManager ──────
    private fun setupSignalingCallbacks() {
        videoCallManager?.onSdpGenerated = { id, sdpJson ->
            if (sdpJson.contains("\"type\":\"offer\"")) {
                networkManager.sendWebRtcOffer(id, sdpJson, true)
            } else if (sdpJson.contains("\"type\":\"answer\"")) {
                networkManager.sendWebRtcAnswer(id, sdpJson)
            }
        }
        videoCallManager?.onIceCandidateGenerated = { id, candidateJson ->
            networkManager.sendWebRtcIceCandidate(id, candidateJson)
        }
    }

    fun onPermissionsGranted(isIncoming: Boolean) {
        Log.i(TAG, "Permissions granted, isIncoming=$isIncoming")
        this.isIncoming = isIncoming

        if (isIncoming) {
            _callState.value = VideoCallState.INCOMING
        } else {
            _callState.value = VideoCallState.OUTGOING
            sendVideoCallRequest()
            startOutgoingTimeout()
        }
    }

    private fun sendVideoCallRequest() {
        viewModelScope.launch {
            Log.i(TAG, "Sending VIDEO call request to ${peerId.take(8)}")
            MeshLogger.звонокИсходящий(peerId)
            networkManager.sendCallRequest(peerId, CallType.VIDEO)
        }
    }

    // ── ИСПРАВЛЕНО: Наблюдение за WebRTC офферами и ответами ─────────────────
    private fun observeCallSignals() {
        // Ответ на исходящий ВИДЕО звонок
        viewModelScope.launch {
            networkManager.callResponse.collect { res ->
                if (res != null && res.senderId == peerId && res.callType == CallType.VIDEO) {
                    timeoutJob?.cancel()
                    Log.i(TAG, "Video callResponse from ${peerId.take(8)}: accepted=${res.accepted}")
                    if (res.accepted) {
                        MeshLogger.звонокПринят(peerId)
                        _callState.value = VideoCallState.ACTIVE
                        startVideoCall() // Инициатор запускает сессию и генерирует Offer
                    } else {
                        MeshLogger.звонокОтклонён(peerId)
                        _callState.value = VideoCallState.ENDED
                    }
                }
            }
        }

        // Завершение ВИДЕО звонка
        viewModelScope.launch {
            networkManager.callEnd.collect { end ->
                if (end != null && end.senderId == peerId && end.callType == CallType.VIDEO) {
                    MeshLogger.звонокЗавершён(peerId, "удалённый пир")
                    Log.i(TAG, "Video callEnd from ${peerId.take(8)}")
                    stopVideoCall()
                    _callState.value = VideoCallState.ENDED
                }
            }
        }

        // Входящий WebRTC Offer
        viewModelScope.launch {
            networkManager.webRtcOffer.collect { offer ->
                if (offer != null && offer.senderId == peerId && offer.callType == CallType.VIDEO) {
                    Log.i(TAG, "WebRTC Offer received from ${peerId.take(8)}")
                    if (_callState.value == VideoCallState.ACTIVE || _callState.value == VideoCallState.INCOMING) {
                        videoCallManager?.handleOffer(peerId, offer.sdpJson)
                    }
                }
            }
        }

        // Входящий WebRTC Answer
        viewModelScope.launch {
            networkManager.webRtcAnswer.collect { answer ->
                if (answer != null && answer.senderId == peerId && answer.callType == CallType.VIDEO) {
                    Log.i(TAG, "WebRTC Answer received from ${peerId.take(8)}")
                    videoCallManager?.handleAnswer(peerId, answer.sdpJson)
                }
            }
        }

        // Входящие ICE кандидаты
        viewModelScope.launch {
            networkManager.webRtcIceCandidate.collect { candidate ->
                if (candidate != null && candidate.senderId == peerId && candidate.callType == CallType.VIDEO) {
                    Log.v(TAG, "WebRTC ICE received from ${peerId.take(8)}")
                    videoCallManager?.handleIceCandidate(peerId, candidate.candidateJson)
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
            videoCallManager?.metrics?.collect { vm ->
                _metrics.value = vm
            }
        }
    }

    // ── Управление звонком ────────────────────────────────────────────────────
    fun acceptCall() {
        Log.i(TAG, "acceptCall: ${peerId.take(8)}")
        MeshLogger.звонокПринят(peerId)
        networkManager.sendCallResponse(peerId, true, CallType.VIDEO)
        _callState.value = VideoCallState.ACTIVE
        // ИСПРАВЛЕНО: Принимающая сторона НЕ генерирует оффер. Она ждет WebRtcOffer.
    }

    fun rejectCall() {
        Log.i(TAG, "rejectCall: ${peerId.take(8)}")
        MeshLogger.звонокОтклонён(peerId)
        networkManager.sendCallResponse(peerId, false, CallType.VIDEO)
        _callState.value = VideoCallState.ENDED
    }

    fun cancelCall() {
        Log.i(TAG, "cancelCall (timeout/user): ${peerId.take(8)}")
        timeoutJob?.cancel()
        networkManager.sendCallEnd(peerId, CallType.VIDEO)
        stopVideoCall()
        _callState.value = VideoCallState.ENDED
    }

    fun endCall() {
        Log.i(TAG, "endCall: ${peerId.take(8)}")
        MeshLogger.звонокЗавершён(peerId, "локально")
        networkManager.sendCallEnd(peerId, CallType.VIDEO)
        stopVideoCall()
        _callState.value = VideoCallState.ENDED
    }

    private fun startVideoCall() {
        MeshLogger.звонокАктивен(peerId)
        Log.i(TAG, "Starting video call session with ${peerId.take(8)}")

        val manager = videoCallManager
        if (manager != null && container.webRtcEngine != null) {
            // Только инициатор (Caller) вызывает startSession (генерация оффера)
            if (!isIncoming) {
                manager.startSession(peerId, null)
                setSpeakerEnabled(true)
            }
        } else {
            Log.e(TAG, "WebRtcEngine not available!")
            _callState.value = VideoCallState.ENDED
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
                networkManager.sendCallEnd(peerId, CallType.VIDEO)
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

    // ── Управление медиа ──────────────────────────────────────────────────────
    fun toggleMute() {
        val muted = !_isMuted.value
        _isMuted.value = muted
        videoCallManager?.setMuted(muted)
    }

    fun toggleSpeaker() {
        val speakerOn = !_isSpeakerOn.value
        _isSpeakerOn.value = speakerOn
        setSpeakerEnabled(speakerOn)
    }

    private fun setSpeakerEnabled(enabled: Boolean) {
        if (_isSpeakerOn.value != enabled) _isSpeakerOn.value = enabled
        if (enabled != callManager.isSpeakerOn) {
            callManager.toggleSpeaker()
        }
    }

    fun toggleCamera() {
        val cameraOn = !_isCameraOn.value
        _isCameraOn.value = cameraOn
        videoCallManager?.setCameraEnabled(cameraOn)
    }

    fun flipCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
        videoCallManager?.flipCamera()
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

    override fun onCleared() {
        super.onCleared()
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