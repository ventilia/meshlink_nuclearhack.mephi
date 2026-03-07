package com.example.meshlink.network.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        private const val AUDIO_BITRATE_BPS = 32_000
        private const val AUDIO_PTIME_MS = 20
        private const val VIDEO_BITRATE_BPS = 600_000
        private const val VIDEO_FPS = 24
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
    }

    private val _state = MutableStateFlow(WebRtcState.IDLE)
    val state: StateFlow<WebRtcState> = _state

    private val _metrics = MutableStateFlow(WebRtcMetrics())
    val metrics: StateFlow<WebRtcMetrics> = _metrics

    private val _iceConnectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val iceConnectionState: StateFlow<PeerConnection.IceConnectionState> = _iceConnectionState

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private val pendingIceCandidates = ConcurrentLinkedQueue<IceCandidate>()
    private var metricsJob: Job? = null
    private val scope = CoroutineScope(
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "WebRtc-Worker").also { it.isDaemon = true }
        }.asCoroutineDispatcher() + SupervisorJob()
    )

    var onIceCandidate: ((String) -> Unit)? = null
    var onOfferReady: ((String) -> Unit)? = null
    var onAnswerReady: ((String) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onConnectionEstablished: (() -> Unit)? = null
    var onConnectionFailed: (() -> Unit)? = null
    var onConnectionClosed: (() -> Unit)? = null

    fun init() {
        if (factory != null) return

        Log.i(TAG, "Инициализация WebRTC PeerConnectionFactory")

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            false
        )
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        Log.i(TAG, "WebRTC инициализирован успешно")
    }

    fun createOffer(withVideo: Boolean, localSurface: VideoSink? = null) {
        scope.launch {
            try {
                _state.value = WebRtcState.CONNECTING
                ensureFactory()
                createPeerConnection()
                addLocalTracks(withVideo, localSurface)

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
                    optional.add(MediaConstraints.KeyValuePair("googCpuOveruseDetection", "false"))
                }

                peerConnection!!.createOffer(object : SimpleSdpObserver("createOffer") {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        Log.d(TAG, "Offer создан, устанавливаем localDescription")
                        val modifiedSdp = applyAudioOptimizations(sdp)
                        peerConnection!!.setLocalDescription(
                            SimpleSdpObserver("setLocalDesc-offer"),
                            modifiedSdp
                        )
                        onOfferReady?.invoke(modifiedSdp.description)
                    }
                }, constraints)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка создания offer: ${e.message}", e)
                _state.value = WebRtcState.FAILED
            }
        }
    }

    fun handleOffer(sdpString: String, withVideo: Boolean, localSurface: VideoSink? = null) {
        scope.launch {
            try {
                _state.value = WebRtcState.CONNECTING
                ensureFactory()
                createPeerConnection()
                addLocalTracks(withVideo, localSurface)

                val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
                peerConnection!!.setRemoteDescription(SimpleSdpObserver("setRemoteDesc-offer"), remoteSdp)

                flushPendingIceCandidates()

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (withVideo) "true" else "false"))
                }

                peerConnection!!.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        val modifiedSdp = applyAudioOptimizations(sdp)
                        peerConnection!!.setLocalDescription(
                            SimpleSdpObserver("setLocalDesc-answer"),
                            modifiedSdp
                        )
                        onAnswerReady?.invoke(modifiedSdp.description)
                    }
                }, constraints)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки offer: ${e.message}", e)
                _state.value = WebRtcState.FAILED
            }
        }
    }

    fun handleAnswer(sdpString: String) {
        scope.launch {
            try {
                val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
                peerConnection?.setRemoteDescription(SimpleSdpObserver("setRemoteDesc-answer"), remoteSdp)
                flushPendingIceCandidates()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки answer: ${e.message}", e)
            }
        }
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        scope.launch {
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
            if (peerConnection?.remoteDescription != null) {
                peerConnection?.addIceCandidate(candidate)
                Log.d(TAG, "ICE кандидат добавлен: $sdpMid")
            } else {
                Log.d(TAG, "ICE кандидат буферизован (remoteDesc ещё не установлен)")
                pendingIceCandidates.add(candidate)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        Log.d(TAG, "Микрофон: ${if (muted) "ВЫКЛЮЧЕН" else "ВКЛЮЧЁН"}")
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        if (enabled) {
            videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        } else {
            videoCapturer?.stopCapture()
        }
        Log.d(TAG, "Камера: ${if (enabled) "ВКЛЮЧЕНА" else "ВЫКЛЮЧЕНА"}")
    }

    fun flipCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        Log.d(TAG, "Камера перевёрнута")
    }

    fun setRemoteVideoSink(sink: VideoSink?) {
        remoteVideoSinkRef = sink
        remoteVideoTrackRef?.let { track ->
            sink?.let { track.addSink(it) }
        }
    }

    private var remoteVideoSinkRef: VideoSink? = null
    private var remoteVideoTrackRef: VideoTrack? = null

    fun hangup() {
        scope.launch {
            Log.i(TAG, "hangup() — завершение WebRTC сессии")
            metricsJob?.cancel()
            stopCapture()
            peerConnection?.close()
            peerConnection = null
            localAudioTrack?.dispose()
            localAudioTrack = null
            localVideoTrack?.dispose()
            localVideoTrack = null
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            pendingIceCandidates.clear()
            remoteVideoTrackRef = null
            remoteVideoSinkRef = null
            _state.value = WebRtcState.IDLE
            _metrics.value = WebRtcMetrics()
        }
    }

    fun dispose() {
        scope.launch {
            hangup()
            factory?.dispose()
            factory = null
            eglBase?.release()
            eglBase = null
            scope.cancel()
        }
    }

    private fun ensureFactory() {
        if (factory == null) init()
    }

    private fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceConnectionReceivingTimeout = 5_000
        }

        peerConnection?.close()
        peerConnection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE кандидат сгенерирован: ${candidate.sdpMid}")
                val json = """{"sdpMid":"${candidate.sdpMid}","sdpMLineIndex":${candidate.sdpMLineIndex},"sdp":"${candidate.sdp.replace("\"", "\\\"")}"}"""
                onIceCandidate?.invoke(json)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE состояние: $state")
                _iceConnectionState.value = state
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        _state.value = WebRtcState.CONNECTED
                        onConnectionEstablished?.invoke()
                        startMetricsCollection()
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.w(TAG, "ICE FAILED — пробуем ICE restart")
                        _state.value = WebRtcState.FAILED
                        attemptIceRestart()
                        onConnectionFailed?.invoke()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "ICE DISCONNECTED — ожидаем переподключения")
                        _state.value = WebRtcState.RECONNECTING
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        _state.value = WebRtcState.IDLE
                        onConnectionClosed?.invoke()
                    }
                    else -> {}
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track() ?: return
                Log.i(TAG, "Получен удалённый трек: kind=${track.kind()}")
                if (track is VideoTrack) {
                    remoteVideoTrackRef = track
                    remoteVideoSinkRef?.let { track.addSink(it) }
                    onRemoteVideoTrack?.invoke(track)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d(TAG, "onTrack: ${transceiver.mediaType}")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling: $state")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE Gathering: $state")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "PeerConnection: $state")
            }

            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() { Log.d(TAG, "onRenegotiationNeeded") }
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        })

        if (peerConnection == null) {
            throw IllegalStateException("Не удалось создать PeerConnection")
        }
        Log.i(TAG, "PeerConnection создан")
    }

    private fun addLocalTracks(withVideo: Boolean, localSurface: VideoSink?) {
        val f = factory ?: return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)

        val audioSender = peerConnection!!.addTrack(localAudioTrack!!, listOf("stream0"))
        audioSender?.let { setTrackBitrate(it, AUDIO_BITRATE_BPS, AUDIO_BITRATE_BPS, AUDIO_BITRATE_BPS) }

        Log.d(TAG, "Аудиотрек добавлен")

        if (withVideo) {
            addVideoTrack(localSurface)
        }
    }

    private fun addVideoTrack(localSurface: VideoSink?) {
        val f = factory ?: return
        val egl = eglBase ?: return

        videoCapturer = createCameraCapturer()
        if (videoCapturer == null) {
            Log.w(TAG, "Камера не найдена — видеотрек не добавлен")
            return
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
        val videoSource = f.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        videoCapturer!!.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        localVideoTrack = f.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)
        localSurface?.let { localVideoTrack?.addSink(it) }

        val videoSender = peerConnection!!.addTrack(localVideoTrack!!, listOf("stream0"))
        videoSender?.let { setTrackBitrate(it, VIDEO_BITRATE_BPS / 4, VIDEO_BITRATE_BPS, VIDEO_BITRATE_BPS) }

        Log.d(TAG, "Видеотрек добавлен (${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}fps)")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                val capturer = enumerator.createCapturer(name, null)
                if (capturer != null) return capturer
            }
        }
        for (name in enumerator.deviceNames) {
            val capturer = enumerator.createCapturer(name, null)
            if (capturer != null) return capturer
        }
        return null
    }

    private fun stopCapture() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null
    }

    private fun flushPendingIceCandidates() {
        while (pendingIceCandidates.isNotEmpty()) {
            val c = pendingIceCandidates.poll() ?: break
            peerConnection?.addIceCandidate(c)
            Log.d(TAG, "Применён буферизованный ICE кандидат")
        }
    }

    private fun applyAudioOptimizations(sdp: SessionDescription): SessionDescription {
        var desc = sdp.description

        if (!desc.contains("useinbandfec=1")) {
            desc = desc.replace(
                Regex("a=rtpmap:(\\d+) opus/48000/2"),
                "a=rtpmap:\$1 opus/48000/2\r\na=fmtp:\$1 minptime=$AUDIO_PTIME_MS;useinbandfec=1;maxaveragebitrate=$AUDIO_BITRATE_BPS"
            )
        }

        desc = desc.lines()
            .filter { line ->
                !line.contains("telephone-event", ignoreCase = true) &&
                        !line.contains("CN ", ignoreCase = true)
            }
            .joinToString("\r\n")

        return SessionDescription(sdp.type, desc)
    }

    private fun setTrackBitrate(sender: RtpSender, minBps: Int, maxBps: Int, startBps: Int) {
        try {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings[0].apply {
                    minBitrateBps = minBps
                    maxBitrateBps = maxBps
                    numTemporalLayers = 1
                }
                sender.parameters = params
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось установить битрейт: ${e.message}")
        }
    }

    private fun attemptIceRestart() {
        scope.launch {
            delay(2_000)
            val pc = peerConnection ?: return@launch
            if (_state.value == WebRtcState.FAILED || _state.value == WebRtcState.RECONNECTING) {
                Log.i(TAG, "Запуск ICE restart")
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }
                pc.createOffer(object : SimpleSdpObserver("iceRestart") {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObserver("iceRestart-setLocal"), sdp)
                        onOfferReady?.invoke(sdp.description)
                    }
                }, constraints)
            }
        }
    }

    private fun startMetricsCollection() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (isActive && _state.value == WebRtcState.CONNECTED) {
                delay(1_000)
                collectStats()
            }
        }
    }

    private fun collectStats() {
        peerConnection?.getStats { report ->
            var rttMs = -1L
            var lossPercent = 0f
            var jitterMs = 0L
            var frameRate = 0f
            var resolWidth = 0
            var resolHeight = 0

            for (statsMap in report.statsMap.values) {
                when (statsMap.type) {
                    "inbound-rtp" -> {
                        val members = statsMap.members
                        val jitterSec = (members["jitter"] as? Double) ?: 0.0
                        jitterMs = (jitterSec * 1000).toLong()

                        val packetsLost = (members["packetsLost"] as? Int) ?: 0
                        val packetsReceived = (members["packetsReceived"] as? Int) ?: 1
                        if (packetsLost > 0) {
                            lossPercent = packetsLost * 100f / (packetsLost + packetsReceived)
                        }

                        frameRate = (members["framesPerSecond"] as? Double)?.toFloat() ?: 0f
                        resolWidth = (members["frameWidth"] as? Int) ?: 0
                        resolHeight = (members["frameHeight"] as? Int) ?: 0
                    }
                    "candidate-pair" -> {
                        val members = statsMap.members
                        val nominated = members["nominated"] as? Boolean ?: false
                        if (nominated) {
                            val rtt = (members["currentRoundTripTime"] as? Double) ?: -1.0
                            if (rtt >= 0) rttMs = (rtt * 1000).toLong()
                        }
                    }
                }
            }

            _metrics.value = WebRtcMetrics(
                rttMs = rttMs,
                lossRatePercent = lossPercent,
                jitterMs = jitterMs,
                jitterBufferSizeMs = 0,
                availableBitrateBps = 0L,
                frameWidth = resolWidth,
                frameHeight = resolHeight,
                framesPerSecond = frameRate
            )

            val metrics = _metrics.value
            Log.d(TAG, "Метрики: RTT=${metrics.rttMs}ms loss=${String.format("%.1f", metrics.lossRatePercent)}% jitter=${metrics.jitterMs}ms fps=${metrics.framesPerSecond} res=${metrics.frameWidth}x${metrics.frameHeight}")
        }
    }

    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext
}

enum class WebRtcState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

open class SimpleSdpObserver(private val opName: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e("SimpleSdpObserver", "$opName createFailure: $error")
    }
    override fun onSetFailure(error: String) {
        Log.e("SimpleSdpObserver", "$opName setFailure: $error")
    }
}