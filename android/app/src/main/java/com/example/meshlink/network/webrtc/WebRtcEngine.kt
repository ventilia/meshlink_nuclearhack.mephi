package com.example.meshlink.network.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRtcEngine — центральный движок WebRTC для MeshLink.
 *
 * Архитектура:
 * ┌─────────────────────────────────────────────────────────┐
 * │  MeshLink Mesh Network (транспорт сигналинга)           │
 * │   keepalive / TCP / WiFi Direct                         │
 * └──────────────────┬──────────────────────────────────────┘
 *                    │ SDP offer/answer + ICE candidates
 *                    ▼
 * ┌─────────────────────────────────────────────────────────┐
 * │  WebRtcEngine (этот файл)                              │
 * │  • PeerConnectionFactory (один на приложение)          │
 * │  • PeerConnection per call                             │
 * │  • AudioTrack + VideoTrack (опционально)               │
 * │  • DTLS-SRTP шифрование (из коробки)                   │
 * │  • Адаптивный битрейт, jitter buffer, NACK/FEC         │
 * └─────────────────────────────────────────────────────────┘
 *
 * Транспорт медиа: WebRTC DataChannel / RTP поверх UDP.
 * Сигналинг: вся SDP/ICE информация пересылается через
 * существующие mesh-пакеты (NetworkManager).
 *
 * Метрики качества доступны через RTCStatsReport каждые 2 секунды.
 */
class WebRtcEngine(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcEngine"

        // Для mesh-сети без публичного STUN нам нужен только host candidate.
        // STUN нужен если хотим пробиваться через NAT между разными сетями.
        // Для LAN/Wi-Fi Direct достаточно host.
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        private const val AUDIO_CODEC_OPUS = "opus"
        private const val VIDEO_CODEC_VP8  = "VP8"
        private const val VIDEO_CODEC_H264 = "H264"

        // Bitrate ограничения — подобраны для mesh Wi-Fi Direct
        private const val AUDIO_START_BITRATE_BPS = 32_000      // 32 kbps
        private const val VIDEO_START_BITRATE_BPS = 512_000     // 512 kbps
        private const val VIDEO_MAX_BITRATE_BPS   = 2_000_000   // 2 Mbps

        @Volatile
        private var factoryInstance: PeerConnectionFactory? = null
        private val factoryLock = Any()

        fun getOrCreateFactory(context: Context, audioDeviceModule: AudioDeviceModule?): PeerConnectionFactory {
            return factoryInstance ?: synchronized(factoryLock) {
                factoryInstance ?: buildFactory(context, audioDeviceModule).also {
                    factoryInstance = it
                }
            }
        }

        private fun buildFactory(context: Context, adm: AudioDeviceModule?): PeerConnectionFactory {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val options = PeerConnectionFactory.Options().apply {
                // Отключаем шифрование на уровне network interface — WebRTC сам шифрует через DTLS
                networkIgnoreMask = 0
            }
            val encoderFactory = DefaultVideoEncoderFactory(
                EglBase.create().eglBaseContext,
                /* enableIntelVp8Encoder= */ true,
                /* enableH264HighProfile= */ true
            )
            val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)
            return PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        }
    }

    // ── Состояние движка ──────────────────────────────────────────────────────

    private val isInitialized = AtomicBoolean(false)

    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState

    // Активные сессии: peerId → WebRtcSession
    private val sessions = ConcurrentHashMap<String, WebRtcSession>()

    // ── Фабрика и глобальные ресурсы ──────────────────────────────────────────

    private lateinit var factory: PeerConnectionFactory
    private lateinit var eglBase: EglBase
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WebRtcEngine").apply { isDaemon = true }
    }

    // ── Источники медиа ──────────────────────────────────────────────────────

    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    private var cameraVideoCapturer: CameraVideoCapturer? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    // ── Обратные вызовы сигналинга ────────────────────────────────────────────

    /**
     * Вызывается когда нужно отправить SDP offer/answer через mesh-сеть.
     * Формат JSON: { "type": "offer"|"answer", "sdp": "...", "peerId": "..." }
     */
    var onSignalingMessage: ((peerId: String, json: String) -> Unit)? = null

    /**
     * Вызывается когда нужно отправить ICE candidate через mesh.
     * Формат JSON: { "sdpMid": "...", "sdpMLineIndex": N, "candidate": "..." }
     */
    var onIceCandidate: ((peerId: String, json: String) -> Unit)? = null

    /**
     * Вызывается когда соединение установлено (ICE connected).
     */
    var onCallConnected: ((peerId: String) -> Unit)? = null

    /**
     * Вызывается при разрыве соединения.
     */
    var onCallDisconnected: ((peerId: String) -> Unit)? = null

    /**
     * Вызывается при получении удалённого видеопотока.
     */
    var onRemoteVideoTrack: ((peerId: String, track: VideoTrack) -> Unit)? = null

    /**
     * Вызывается при обновлении метрик.
     */
    var onMetricsUpdate: ((peerId: String, metrics: WebRtcMetrics) -> Unit)? = null

    // ── Инициализация ─────────────────────────────────────────────────────────

    fun initialize() {
        if (isInitialized.getAndSet(true)) return
        Log.i(TAG, "Initializing WebRTC engine...")
        engineExecutor.execute {
            try {
                eglBase = EglBase.create()

                audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .setSamplesReadyCallback(null)
                    .createAudioDeviceModule()

                factory = getOrCreateFactory(context, audioDeviceModule)

                // Создаём общие источники аудио и видео
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                }
                localAudioSource = factory.createAudioSource(audioConstraints)
                localAudioTrack = factory.createAudioTrack("audio_track_0", localAudioSource).apply {
                    setEnabled(true)
                }

                _engineState.value = EngineState.READY
                Log.i(TAG, "WebRTC engine initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC init failed: ${e.message}", e)
                _engineState.value = EngineState.ERROR
            }
        }
    }

    /**
     * Инициализировать видеосурс (вызывать только при нужде в камере).
     * Тяжёлая операция — делается лениво при видеозвонке.
     */
    fun initializeVideo(eglContext: EglBase.Context? = null) {
        engineExecutor.execute {
            if (localVideoSource != null) return@execute
            try {
                localVideoSource = factory.createVideoSource(/* isScreencast= */ false)
                localVideoTrack = factory.createVideoTrack("video_track_0", localVideoSource).apply {
                    setEnabled(true)
                }

                // Попытка создать камеру
                cameraVideoCapturer = createCameraCapturerOrNull()
                cameraVideoCapturer?.initialize(
                    SurfaceTextureHelper.create("CameraTexture", eglBase.eglBaseContext),
                    context,
                    localVideoSource!!.capturerObserver
                )
                // 720p @ 30fps — хорошее качество без перегрузки Wi-Fi Direct
                cameraVideoCapturer?.startCapture(1280, 720, 30)
                Log.i(TAG, "Video capture started: 1280×720@30fps")
            } catch (e: Exception) {
                Log.e(TAG, "Video init failed: ${e.message}", e)
            }
        }
    }

    private fun createCameraCapturerOrNull(): CameraVideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(context)
            // Предпочитаем фронтальную камеру
            val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            frontCamera?.let { enumerator.createCapturer(it, null) }
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 not available, trying Camera1: ${e.message}")
            try {
                val enumerator = Camera1Enumerator(false)
                val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                front?.let { enumerator.createCapturer(it, null) }
            } catch (e2: Exception) {
                Log.e(TAG, "No camera available: ${e2.message}")
                null
            }
        }
    }

    // ── Управление сессиями ───────────────────────────────────────────────────

    /**
     * Начать исходящий звонок — создаём PeerConnection и генерируем SDP offer.
     *
     * @param peerId    peer ID удалённого собеседника
     * @param withVideo true = видеозвонок
     */
    fun startOutgoingCall(peerId: String, withVideo: Boolean) {
        Log.i(TAG, "startOutgoingCall → ${peerId.take(8)} withVideo=$withVideo")
        engineExecutor.execute {
            val session = createSession(peerId, isInitiator = true, withVideo = withVideo)
            sessions[peerId] = session

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
            }

            session.peerConnection.createOffer(object : SdpObserverAdapter("createOffer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    session.peerConnection.setLocalDescription(object : SdpObserverAdapter("setLocal") {
                        override fun onSetSuccess() {
                            val preferredSdp = preferCodecs(sdp.description, withVideo)
                            val json = """{"type":"offer","sdp":${escapeJson(preferredSdp)}}"""
                            Log.d(TAG, "Sending offer to ${peerId.take(8)}")
                            onSignalingMessage?.invoke(peerId, json)
                        }
                    }, sdp)
                }
            }, constraints)
        }
    }

    /**
     * Обработать входящий SDP offer — создаём сессию и отвечаем answer.
     */
    fun handleOffer(peerId: String, sdpJson: String, withVideo: Boolean) {
        Log.i(TAG, "handleOffer from ${peerId.take(8)}")
        engineExecutor.execute {
            val session = createSession(peerId, isInitiator = false, withVideo = withVideo)
            sessions[peerId] = session

            val sdpString = extractSdp(sdpJson)
            val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdpString)

            session.peerConnection.setRemoteDescription(object : SdpObserverAdapter("setRemoteOffer") {
                override fun onSetSuccess() {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
                    }
                    session.peerConnection.createAnswer(object : SdpObserverAdapter("createAnswer") {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            session.peerConnection.setLocalDescription(object : SdpObserverAdapter("setLocalAnswer") {
                                override fun onSetSuccess() {
                                    val preferredSdp = preferCodecs(sdp.description, withVideo)
                                    val json = """{"type":"answer","sdp":${escapeJson(preferredSdp)}}"""
                                    Log.d(TAG, "Sending answer to ${peerId.take(8)}")
                                    onSignalingMessage?.invoke(peerId, json)
                                    // Применяем накопленные ICE candidates
                                    session.flushPendingCandidates()
                                }
                            }, SessionDescription(SessionDescription.Type.ANSWER, preferCodecs(sdp.description, withVideo)))
                        }
                    }, constraints)
                }
            }, remoteDesc)
        }
    }

    /**
     * Обработать SDP answer от удалённого пира.
     */
    fun handleAnswer(peerId: String, sdpJson: String) {
        Log.i(TAG, "handleAnswer from ${peerId.take(8)}")
        engineExecutor.execute {
            val session = sessions[peerId] ?: run {
                Log.w(TAG, "No session for ${peerId.take(8)} when handling answer")
                return@execute
            }
            val sdpString = extractSdp(sdpJson)
            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
            session.peerConnection.setRemoteDescription(object : SdpObserverAdapter("setRemoteAnswer") {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set for ${peerId.take(8)}")
                    session.flushPendingCandidates()
                }
            }, remoteDesc)
        }
    }

    /**
     * Добавить ICE candidate от удалённого пира.
     */
    fun addIceCandidate(peerId: String, candidateJson: String) {
        engineExecutor.execute {
            val session = sessions[peerId] ?: run {
                Log.w(TAG, "No session for ${peerId.take(8)} when adding ICE candidate")
                return@execute
            }
            try {
                val candidate = parseIceCandidate(candidateJson)
                if (session.peerConnection.remoteDescription != null) {
                    session.peerConnection.addIceCandidate(candidate)
                } else {
                    // Буферизуем до готовности remote description
                    session.pendingCandidates.add(candidate)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add ICE candidate: ${e.message}")
            }
        }
    }

    /**
     * Завершить звонок с указанным пиром.
     */
    fun endCall(peerId: String) {
        Log.i(TAG, "endCall for ${peerId.take(8)}")
        engineExecutor.execute {
            sessions.remove(peerId)?.dispose()
        }
    }

    /**
     * Завершить все активные сессии.
     */
    fun endAllCalls() {
        engineExecutor.execute {
            sessions.values.forEach { it.dispose() }
            sessions.clear()
        }
    }

    // ── Управление медиа ──────────────────────────────────────────────────────

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        Log.d(TAG, "Microphone: ${if (muted) "MUTED" else "ACTIVE"}")
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        if (enabled) {
            cameraVideoCapturer?.startCapture(1280, 720, 30)
        } else {
            cameraVideoCapturer?.stopCapture()
        }
        Log.d(TAG, "Camera: ${if (enabled) "ON" else "OFF"}")
    }

    fun flipCamera() {
        val capturer = cameraVideoCapturer ?: return
        if (capturer is CameraVideoCapturer) {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    Log.i(TAG, "Camera switched: front=$isFrontCamera")
                }
                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.w(TAG, "Camera switch error: $errorDescription")
                }
            })
        }
    }

    /**
     * Подключить локальный SurfaceViewRenderer к локальному видеопотоку.
     */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setMirror(true)
        renderer.setEnableHardwareScaler(true)
        localVideoTrack?.addSink(renderer)
        Log.d(TAG, "Local renderer attached")
    }

    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
        renderer.release()
    }

    // ── Создание PeerConnection ───────────────────────────────────────────────

    private fun createSession(peerId: String, isInitiator: Boolean, withVideo: Boolean): WebRtcSession {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Ключевые параметры для низкой задержки в LAN:
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            keyType = PeerConnection.KeyType.ECDSA
        }

        val session = WebRtcSession(peerId = peerId, isInitiator = isInitiator)

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.v(TAG, "ICE candidate generated for ${peerId.take(8)}: ${candidate.sdp.take(60)}")
                val json = buildString {
                    append("""{"sdpMid":"""); append(escapeJson(candidate.sdpMid ?: ""))
                    append(""","sdpMLineIndex":"""); append(candidate.sdpMLineIndex)
                    append(""","candidate":"""); append(escapeJson(candidate.sdp))
                    append("}")
                }
                onIceCandidate?.invoke(peerId, json)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE state change for ${peerId.take(8)}: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        Log.i(TAG, "✅ WebRTC CONNECTED to ${peerId.take(8)}")
                        session.startMetricsCollection()
                        onCallConnected?.invoke(peerId)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "⚠️ WebRTC DISCONNECTED from ${peerId.take(8)}")
                        onCallDisconnected?.invoke(peerId)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.e(TAG, "❌ WebRTC ICE FAILED for ${peerId.take(8)}")
                        onCallDisconnected?.invoke(peerId)
                    }
                    else -> {}
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track() ?: return
                Log.i(TAG, "Remote track received for ${peerId.take(8)}: kind=${track.kind()}")
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    onRemoteVideoTrack?.invoke(peerId, track as VideoTrack)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    onRemoteVideoTrack?.invoke(peerId, track as VideoTrack)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.v(TAG, "ICE gathering: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "PeerConnection state ${peerId.take(8)}: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        }

        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        session.peerConnection = pc
        session.metricsCallback = { metrics ->
            onMetricsUpdate?.invoke(peerId, metrics)
        }

        // Добавляем аудиопоток
        localAudioTrack?.let { track ->
            pc.addTrack(track, listOf("meshlink_stream"))
        }

        // Добавляем видеопоток если нужен
        if (withVideo) {
            localVideoTrack?.let { track ->
                pc.addTrack(track, listOf("meshlink_stream"))
            }
        }

        return session
    }

    // ── Предпочтение кодеков ──────────────────────────────────────────────────

    /**
     * Переставляем предпочтительные кодеки в начало SDP.
     * Для аудио: Opus (лучшее качество + встроенный FEC).
     * Для видео: H264 > VP8 (лучше на мобильных через hardware codec).
     */
    private fun preferCodecs(sdp: String, withVideo: Boolean): String {
        var result = preferAudioCodec(sdp, AUDIO_CODEC_OPUS)
        if (withVideo) {
            result = preferVideoCodec(result, VIDEO_CODEC_H264)
        }
        // Устанавливаем целевые битрейты в SDP
        result = setBitrates(result, withVideo)
        return result
    }

    private fun preferAudioCodec(sdp: String, codec: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mAudioIndex = lines.indexOfFirst { it.startsWith("m=audio") }
        if (mAudioIndex < 0) return sdp

        val codecPayload = lines.subList(mAudioIndex, lines.size)
            .firstOrNull { it.contains("a=rtpmap") && it.contains(codec, ignoreCase = true) }
            ?.let { line -> line.substringAfter("a=rtpmap:").substringBefore(" ") }
            ?: return sdp

        val mLine = lines[mAudioIndex]
        val parts = mLine.split(" ").toMutableList()
        val payloadIndex = parts.indexOf(codecPayload)
        if (payloadIndex > 3) {
            parts.removeAt(payloadIndex)
            parts.add(3, codecPayload)
            lines[mAudioIndex] = parts.joinToString(" ")
        }
        return lines.joinToString("\r\n")
    }

    private fun preferVideoCodec(sdp: String, codec: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mVideoIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (mVideoIndex < 0) return sdp

        val codecPayload = lines.subList(mVideoIndex, lines.size)
            .firstOrNull { it.contains("a=rtpmap") && it.contains(codec, ignoreCase = true) }
            ?.let { line -> line.substringAfter("a=rtpmap:").substringBefore(" ") }
            ?: return sdp

        val mLine = lines[mVideoIndex]
        val parts = mLine.split(" ").toMutableList()
        val payloadIndex = parts.indexOf(codecPayload)
        if (payloadIndex > 3) {
            parts.removeAt(payloadIndex)
            parts.add(3, codecPayload)
            lines[mVideoIndex] = parts.joinToString(" ")
        }
        return lines.joinToString("\r\n")
    }

    private fun setBitrates(sdp: String, withVideo: Boolean): String {
        // Вставляем b=AS: в audio и video секции для контроля полосы
        val lines = sdp.split("\r\n").toMutableList()
        val result = mutableListOf<String>()
        var inAudio = false
        var inVideo = false
        var audioSet = false
        var videoSet = false

        for (line in lines) {
            when {
                line.startsWith("m=audio") -> { inAudio = true; inVideo = false }
                line.startsWith("m=video") -> { inAudio = false; inVideo = true }
                line.startsWith("m=") -> { inAudio = false; inVideo = false }
            }
            result.add(line)
            if (inAudio && !audioSet && line.startsWith("c=")) {
                result.add("b=AS:${AUDIO_START_BITRATE_BPS / 1000}")
                audioSet = true
            }
            if (withVideo && inVideo && !videoSet && line.startsWith("c=")) {
                result.add("b=AS:${VIDEO_MAX_BITRATE_BPS / 1000}")
                videoSet = true
            }
        }
        return result.joinToString("\r\n")
    }

    // ── Вспомогательные функции ───────────────────────────────────────────────

    private fun escapeJson(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    }

    private fun extractSdp(json: String): String {
        return json.substringAfter("\"sdp\":\"").substringBefore("\"}")
            .replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")
    }

    private fun parseIceCandidate(json: String): IceCandidate {
        val sdpMid = json.substringAfter("\"sdpMid\":\"").substringBefore("\"")
        val sdpMLineIndex = json.substringAfter("\"sdpMLineIndex\":").substringBefore(",").trim().toIntOrNull() ?: 0
        val candidate = json.substringAfter("\"candidate\":\"").substringBefore("\"}")
            .replace("\\n", "\n").replace("\\r", "\r")
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    // ── Освобождение ──────────────────────────────────────────────────────────

    fun release() {
        engineExecutor.execute {
            endAllCalls()
            cameraVideoCapturer?.stopCapture()
            cameraVideoCapturer?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            localVideoSource?.dispose()
            localAudioSource?.dispose()
            audioDeviceModule?.release()
            factoryInstance?.dispose()
            factoryInstance = null
            eglBase.release()
            isInitialized.set(false)
            _engineState.value = EngineState.IDLE
            Log.i(TAG, "WebRTC engine released")
        }
        engineExecutor.shutdown()
    }

    enum class EngineState { IDLE, READY, ERROR }
}

// ── SDP observer adapter ─────────────────────────────────────────────────────

open class SdpObserverAdapter(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.e("SdpObserver", "$tag onCreateFailure: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.e("SdpObserver", "$tag onSetFailure: $error")
    }
}