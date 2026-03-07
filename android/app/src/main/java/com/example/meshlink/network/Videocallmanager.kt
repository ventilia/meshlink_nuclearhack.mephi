package com.example.meshlink.network

import android.content.Context
import android.util.Log
import android.view.Surface
import com.example.meshlink.network.webrtc.WebRtcEngine
import com.example.meshlink.network.webrtc.WebRtcMetrics
import com.example.meshlink.network.webrtc.CallQualityLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * VideoCallManager — управляет видеозвонком через WebRTC.
 *
 * Архитектура:
 * 1. WebRtcEngine — основной движок (WebRTC P2P)
 * 2. CallManager — fallback для аудио (UDP)
 * 3. Сигналинг идёт через NetworkManager (mesh TCP)
 */
class VideoCallManager(
    private val context: Context,
    private val engine: WebRtcEngine
) {
    companion object {
        private const val TAG = "VideoCallManager"
    }

    // ── Метрики ───────────────────────────────────────────────────────────────
    private val _metrics = MutableStateFlow(VideoMetrics())
    val metrics: StateFlow<VideoMetrics> = _metrics

    // ── Рендереры ─────────────────────────────────────────────────────────────
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var currentPeerId: String? = null

    // ── Обратные вызовы для NetworkManager ────────────────────────────────────
    var onSdpGenerated: ((peerId: String, sdpJson: String) -> Unit)? = null
    var onIceCandidateGenerated: ((peerId: String, candidateJson: String) -> Unit)? = null
    var onConnected: ((peerId: String) -> Unit)? = null
    var onDisconnected: ((peerId: String) -> Unit)? = null

    // ── Инициализация ─────────────────────────────────────────────────────────
    init {
        setupEngineCallbacks()
    }

    private fun setupEngineCallbacks() {
        engine.onSignalingMessage = { peerId, json ->
            Log.d(TAG, "SDP generated for ${peerId.take(8)}: ${json.take(60)}...")
            onSdpGenerated?.invoke(peerId, json)
        }
        engine.onIceCandidate = { peerId, json ->
            Log.v(TAG, "ICE candidate for ${peerId.take(8)}")
            onIceCandidateGenerated?.invoke(peerId, json)
        }
        engine.onCallConnected = { peerId ->
            Log.i(TAG, "✅ Video call connected to ${peerId.take(8)}")
            onConnected?.invoke(peerId)
        }
        engine.onCallDisconnected = { peerId ->
            Log.w(TAG, "❌ Video call disconnected from ${peerId.take(8)}")
            onDisconnected?.invoke(peerId)
        }
        engine.onRemoteVideoTrack = { peerId, track ->
            Log.i(TAG, "🎥 Remote video track from ${peerId.take(8)}")
            attachRemoteTrack(track)
        }
        engine.onMetricsUpdate = { peerId, webRtcMetrics ->
            updateMetrics(webRtcMetrics)
        }
    }

    // ── Управление сессией ────────────────────────────────────────────────────
    fun startSession(peerId: String, surface: Surface?) {
        Log.i(TAG, "Starting video session with ${peerId.take(8)}")
        currentPeerId = peerId
        engine.initializeVideo(null)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            engine.startOutgoingCall(peerId, withVideo = true)
        }, 500)
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        engine.attachLocalRenderer(renderer)
        Log.d(TAG, "Local renderer attached")
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        engine.detachLocalRenderer(renderer)
        localRenderer = null
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        renderer.init(EglBase.create().eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
        Log.d(TAG, "Remote renderer attached")
    }

    fun detachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer?.let { r ->
            currentRemoteTrack?.removeSink(r)
        }
        renderer.release()
        remoteRenderer = null
    }

    private var currentRemoteTrack: VideoTrack? = null

    private fun attachRemoteTrack(track: VideoTrack) {
        currentRemoteTrack = track
        remoteRenderer?.let { renderer ->
            track.addSink(renderer)
            Log.d(TAG, "Remote video track added to renderer")
        }
    }

    // ── Обработка входящей сигнализации ──────────────────────────────────────
    fun handleOffer(peerId: String, sdpJson: String) {
        Log.i(TAG, "Handling SDP offer from ${peerId.take(8)}")
        currentPeerId = peerId
        engine.initializeVideo(null)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            engine.handleOffer(peerId, sdpJson, withVideo = true)
        }, 500)
    }

    fun handleAnswer(peerId: String, sdpJson: String) {
        Log.i(TAG, "Handling SDP answer from ${peerId.take(8)}")
        engine.handleAnswer(peerId, sdpJson)
    }

    fun handleIceCandidate(peerId: String, candidateJson: String) {
        engine.addIceCandidate(peerId, candidateJson)
    }

    // ── Управление медиа ──────────────────────────────────────────────────────
    fun setMuted(muted: Boolean) {
        engine.setMuted(muted)
    }

    fun setCameraEnabled(enabled: Boolean) {
        engine.setCameraEnabled(enabled)
    }

    fun flipCamera() {
        engine.flipCamera()
    }

    // ── Завершение ────────────────────────────────────────────────────────────
    fun stopSession() {
        val peerId = currentPeerId ?: return
        Log.i(TAG, "Stopping video session for ${peerId.take(8)}")
        engine.endCall(peerId)
        currentRemoteTrack = null
        currentPeerId = null
        _metrics.value = VideoMetrics()
    }

    // ── Метрики ───────────────────────────────────────────────────────────────
    private fun updateMetrics(webRtcMetrics: WebRtcMetrics) {
        _metrics.value = VideoMetrics(
            rttMs = webRtcMetrics.rttMs,
            lossRatePercent = webRtcMetrics.lossRatePercent,
            jitterMs = webRtcMetrics.jitterMs,
            jitterBufferSize = webRtcMetrics.jitterBufferSizeMs,
            bitrateBps = webRtcMetrics.availableBitrateBps,
            frameWidth = webRtcMetrics.frameWidth,
            frameHeight = webRtcMetrics.frameHeight,
            framesPerSecond = webRtcMetrics.framesPerSecond,
            quality = when (webRtcMetrics.quality) {
                CallQualityLevel.GOOD -> CallQuality.GOOD
                CallQualityLevel.FAIR -> CallQuality.FAIR
                CallQualityLevel.POOR -> CallQuality.POOR
            }
        )
    }
}

/**
 * Метрики видеозвонка.
 */
data class VideoMetrics(
    val rttMs: Long = -1L,
    val lossRatePercent: Float = 0f,
    val jitterMs: Long = 0L,
    val jitterBufferSize: Int = 0,
    val bitrateBps: Long = 0L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val framesPerSecond: Float = 0f,
    val quality: CallQuality = CallQuality.GOOD
) {
    val bitrateKbps: Int get() = (bitrateBps / 1000).toInt()
    val videoResolution: String get() = if (frameWidth > 0) "${frameWidth}×${frameHeight}" else ""
}

enum class CallQuality {
    GOOD,
    FAIR,
    POOR
}