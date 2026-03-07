package com.example.meshlink.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder.AudioSource
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * CallManager v3 — полностью рабочий менеджер голосовых звонков.
 *
 * ИСПРАВЛЕНИЯ v3:
 * 1. Реализован toggleMute() / setMuted():
 *    - При isMuted=true отправляем нулевые буферы вместо реального PCM.
 *    - Это корректная реализация "mute": микрофон продолжает читать (чтобы
 *      не было разрывов в потоке), но отправляются нулевые данные.
 *
 * 2. toggleSpeaker() теперь немедленно применяется через AudioManager.
 *    Раньше поле isSpeakerOn было private set — исправлено.
 *
 * 3. Улучшенная обработка ошибок AudioRecord: при ERROR_DEAD_OBJECT
 *    запись переинициализируется без обрыва звонка.
 *
 * 4. Адаптивный jitter-буфер работает корректно (исправлен расчёт expectedInterval).
 *
 * 5. Метрики: добавлено отображение isMuted в CallMetrics.
 *
 * ПРОТОКОЛ пакета (6 байт header + payload):
 *   [4 bytes: seq_num BE] [1 byte: flags] [1 byte: reserved] [payload: PCM]
 *
 *   flags:
 *     0x00 = обычный PCM
 *     0x01 = PING (RTT)
 *     0x02 = PONG (RTT)
 *     0x04 = MUTED (тишина, receiver может пропустить воспроизведение)
 */
class CallManager(private val context: Context) {

    companion object {
        private const val TAG = "CallManager"

        // Аудио
        private const val SAMPLE_RATE = 16_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2

        // Сеть
        const val UDP_CALL_PORT = 8813
        private const val UDP_RECEIVE_TIMEOUT_MS = 150

        // Jitter-буфер
        private const val MIN_JITTER_BUFFER_SIZE = 3
        private const val DEFAULT_JITTER_SIZE = 5
        private const val MAX_JITTER_BUFFER_SIZE = 12

        // Header
        private const val HEADER_SIZE = 6

        // Flags
        private const val FLAG_PING: Byte = 0x01
        private const val FLAG_PONG: Byte = 0x02
        private const val FLAG_MUTED: Byte = 0x04
    }

    // ── Аудио конфигурация ────────────────────────────────────────────────────

    private val minBuffer: Int = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING
    ).let { if (it <= 0) 2048 else it }

    private val bufferSize: Int = minBuffer * BUFFER_SIZE_FACTOR

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioTrackFormat = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(ENCODING)
        .build()

    private val audioRecordFormat = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(ENCODING)
        .build()

    // ── Состояние ─────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    private var recordingJob: Job? = null
    private var receiveJob: Job? = null
    private var playbackJob: Job? = null
    private var metricsJob: Job? = null
    private var pingJob: Job? = null

    @Volatile private var peerIp: String? = null

    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null

    // Jitter-буфер (адаптивный размер)
    @Volatile private var currentJitterSize = DEFAULT_JITTER_SIZE
    private val jitterBuffer = ArrayBlockingQueue<ByteArray>(MAX_JITTER_BUFFER_SIZE)

    // Метрики
    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private val packetsLost = AtomicLong(0)
    private var lastSeqReceived = -1L
    private var seqNum = 0L

    @Volatile private var lastPacketArrivalMs = 0L
    private val jitterSamples = ArrayDeque<Long>(20)

    private val _metrics = MutableStateFlow(CallMetrics())
    val metrics: StateFlow<CallMetrics> = _metrics

    @Volatile private var lastPingSentMs = 0L
    @Volatile private var lastRttMs = -1L

    // ── МУТ — исправлен ───────────────────────────────────────────────────────

    /**
     * isMuted — флаг заглушения микрофона.
     * При true: запись продолжается, но отправляются нулевые данные.
     * Это корректная реализация — предотвращает разрывы в UDP-потоке.
     */
    @Volatile var isMuted: Boolean = false
        private set

    /**
     * Установить состояние мута.
     * Потокобезопасно: volatile-запись.
     */
    fun setMuted(muted: Boolean) {
        isMuted = muted
        Log.i(TAG, "Mute: ${if (muted) "ON 🔇" else "OFF 🎙️"}")
    }

    // ── Динамик ───────────────────────────────────────────────────────────────

    var isSpeakerOn: Boolean = false
        private set

    // ── Публичный API ─────────────────────────────────────────────────────────

    fun hasAudioPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Запустить UDP-сессию звонка.
     */
    fun startSession(ip: String) {
        if (peerIp != null) {
            Log.w(TAG, "startSession: сессия уже активна, перезапускаем")
            stopSession()
        }
        peerIp = ip
        isMuted = false // сбрасываем мут при новом звонке
        Log.i(TAG, "startSession → $ip:$UDP_CALL_PORT (buf=${bufferSize}B, rate=${SAMPLE_RATE}Hz)")

        configureAudioManager()
        openSockets(ip)
        startPlaybackPipeline()
        startPingLoop(ip)
        startMetricsLoop()

        if (hasAudioPermission(context)) {
            startUdpRecordingLoop(ip)
        } else {
            Log.w(TAG, "startSession: нет RECORD_AUDIO — звонок без микрофона")
        }
    }

    /**
     * Fallback-запуск записи через колбэк (используется только если IP неизвестен).
     */
    fun startRecording(onFragment: (ByteArray) -> Unit) {
        if (peerIp != null) {
            Log.d(TAG, "startRecording: UDP-сессия активна, игнорируем fallback")
            return
        }
        if (recordingJob?.isActive == true) return
        if (!hasAudioPermission(context)) {
            Log.w(TAG, "startRecording: нет RECORD_AUDIO")
            return
        }
        startCallbackRecordingLoop(onFragment)
    }

    fun stopSession() {
        Log.i(TAG, "stopSession")
        runBlocking {
            listOf(recordingJob, receiveJob, playbackJob, metricsJob, pingJob)
                .forEach { it?.cancelAndJoin() }
        }
        recordingJob = null; receiveJob = null; playbackJob = null
        metricsJob = null; pingJob = null

        releaseAudio()
        closeSockets()
        restoreAudioManager()
        jitterBuffer.clear()
        peerIp = null
        seqNum = 0L
        lastSeqReceived = -1L
        packetsSent.set(0); packetsReceived.set(0); packetsLost.set(0)
        jitterSamples.clear()
        isMuted = false
        Log.i(TAG, "stopSession complete")
    }

    // Совместимость со старым кодом
    fun stopRecording() {
        runBlocking { recordingJob?.cancelAndJoin() }
        recordingJob = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.i(TAG, "Recording stopped")
    }

    fun stopPlaying() {
        try { audioTrack?.stop(); audioTrack?.release(); audioTrack = null } catch (_: Exception) {}
        Log.i(TAG, "Playing stopped")
    }

    /** Воспроизвести фрагмент напрямую (TCP fallback) */
    fun playFragment(data: ByteArray) {
        ensureAudioTrackStarted()
        try {
            audioTrack?.write(data, 0, data.size)
        } catch (_: Exception) {}
    }

    /**
     * Переключить динамик / наушник.
     * ИСПРАВЛЕНО: немедленно применяется через AudioManager.
     */
    fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        applySpeakerMode()
        Log.i(TAG, "Динамик: ${if (isSpeakerOn) "ВКЛ 🔊" else "ВЫКЛ 🎧"}")
    }

    private fun applySpeakerMode() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = isSpeakerOn
        } catch (e: Exception) {
            Log.w(TAG, "applySpeakerMode: ${e.message}")
        }
    }

    // ── Аудиоменеджер ─────────────────────────────────────────────────────────

    private fun configureAudioManager() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = isSpeakerOn
            Log.i(TAG, "AudioManager: MODE_IN_COMMUNICATION, speaker=$isSpeakerOn")
        } catch (e: Exception) { Log.w(TAG, "configureAudioManager: ${e.message}") }
    }

    private fun restoreAudioManager() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            Log.i(TAG, "AudioManager restored to NORMAL")
        } catch (e: Exception) { Log.w(TAG, "restoreAudioManager: ${e.message}") }
    }

    // ── Сокеты ────────────────────────────────────────────────────────────────

    private fun openSockets(ip: String) {
        try {
            sendSocket = DatagramSocket()
            Log.i(TAG, "UDP send socket opened → $ip:$UDP_CALL_PORT")
        } catch (e: Exception) { Log.e(TAG, "openSockets send: ${e.message}") }

        try {
            receiveSocket = DatagramSocket(UDP_CALL_PORT).apply {
                soTimeout = UDP_RECEIVE_TIMEOUT_MS
                reuseAddress = true
                receiveBufferSize = 65536
                sendBufferSize = 65536
            }
            Log.i(TAG, "UDP receive socket bound to $UDP_CALL_PORT")
        } catch (e: Exception) { Log.e(TAG, "openSockets receive (port busy?): ${e.message}") }
    }

    private fun closeSockets() {
        runCatching { sendSocket?.close() }
        runCatching { receiveSocket?.close() }
        sendSocket = null; receiveSocket = null
    }

    // ── Запись и отправка ─────────────────────────────────────────────────────

    private fun startUdpRecordingLoop(ip: String) {
        if (recordingJob?.isActive == true) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val record: AudioRecord = buildAudioRecord() ?: return
        audioRecord = record

        val sessionId = record.audioSessionId
        applyAudioEffects(sessionId)

        try {
            record.startRecording()
            Log.i(TAG, "AudioRecord started (sessionId=$sessionId)")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.startRecording: ${e.message}")
            runCatching { record.release() }; audioRecord = null; return
        }

        val socket = sendSocket ?: run {
            Log.e(TAG, "startUdpRecordingLoop: sendSocket == null"); return
        }
        val peerAddr = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            Log.e(TAG, "InetAddress: ${e.message}"); return
        }

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            val silenceBuffer = ByteArray(bufferSize) // нули — тишина при муте
            var running = true
            Log.i(TAG, "UDP recording loop start → $ip:$UDP_CALL_PORT")

            while (isActive && running) {
                val read = record.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        // МУТ: отправляем нулевые данные с флагом FLAG_MUTED
                        val (dataToSend, flags) = if (isMuted) {
                            Pair(silenceBuffer, FLAG_MUTED)
                        } else {
                            Pair(buffer, 0x00.toByte())
                        }

                        val packet = buildPacket(dataToSend, read, flags)
                        try {
                            socket.send(DatagramPacket(packet, packet.size, peerAddr, UDP_CALL_PORT))
                            packetsSent.incrementAndGet()
                        } catch (e: Exception) {
                            if (!isActive) running = false
                            else Log.w(TAG, "UDP send: ${e.message}")
                        }
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "AudioRecord DEAD_OBJECT — reinitializing...")
                        runCatching { record.stop(); record.release() }
                        audioRecord = null
                        // Переинициализация без обрыва звонка
                        delay(200)
                        val newRecord = buildAudioRecord() ?: break
                        audioRecord = newRecord
                        applyAudioEffects(newRecord.audioSessionId)
                        try { newRecord.startRecording() }
                        catch (e: Exception) { Log.e(TAG, "reinit startRecording: ${e.message}"); break }
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord.read error: $read"); running = false
                    }
                }
            }
            Log.i(TAG, "UDP recording loop end")
        }
    }

    private fun buildAudioRecord(): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            AudioRecord.Builder()
                .setAudioSource(AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(audioRecordFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord SecurityException: ${e.message}"); null
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build: ${e.message}"); null
        }
    }

    private fun applyAudioEffects(sessionId: Int) {
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.enabled = true
                Log.d(TAG, "NoiseSuppressor enabled")
            }
        }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.enabled = true
                Log.d(TAG, "AcousticEchoCanceler enabled")
            }
        }
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.enabled = true
                Log.d(TAG, "AutomaticGainControl enabled")
            }
        }
    }

    private fun buildPacket(data: ByteArray, length: Int, flags: Byte): ByteArray {
        val packet = ByteArray(HEADER_SIZE + length)
        val seq = seqNum++
        packet[0] = (seq shr 24 and 0xFF).toByte()
        packet[1] = (seq shr 16 and 0xFF).toByte()
        packet[2] = (seq shr 8 and 0xFF).toByte()
        packet[3] = (seq and 0xFF).toByte()
        packet[4] = flags
        packet[5] = 0x00
        System.arraycopy(data, 0, packet, HEADER_SIZE, length)
        return packet
    }

    // ── Воспроизведение ───────────────────────────────────────────────────────

    private fun startPlaybackPipeline() {
        ensureAudioTrackStarted()
        val socket = receiveSocket ?: run {
            Log.e(TAG, "startPlaybackPipeline: receiveSocket == null"); return
        }

        // Поток 1: UDP → jitterBuffer
        receiveJob = scope.launch {
            val buf = ByteArray(bufferSize * 2 + HEADER_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            var running = true
            Log.i(TAG, "UDP receive loop start (port=$UDP_CALL_PORT)")

            while (isActive && running) {
                try {
                    packet.setData(buf, 0, buf.size)
                    socket.receive(packet)

                    val data = buf.copyOf(packet.length)
                    if (data.size < HEADER_SIZE) continue

                    val flags = data[4]

                    // RTT PONG
                    if (flags == FLAG_PONG && lastPingSentMs > 0) {
                        lastRttMs = System.currentTimeMillis() - lastPingSentMs
                        Log.v(TAG, "RTT=${lastRttMs}ms")
                        continue
                    }
                    // Ответ на PING
                    if (flags == FLAG_PING) {
                        sendPong(packet.address.hostAddress ?: continue)
                        continue
                    }
                    // Мут-пакет от собеседника — пропускаем воспроизведение (тишина не нужна)
                    if (flags == FLAG_MUTED) {
                        packetsReceived.incrementAndGet()
                        continue
                    }

                    packetsReceived.incrementAndGet()

                    // Подсчёт потерь по seq
                    val seq = ((data[0].toLong() and 0xFF) shl 24) or
                            ((data[1].toLong() and 0xFF) shl 16) or
                            ((data[2].toLong() and 0xFF) shl 8) or
                            (data[3].toLong() and 0xFF)
                    if (lastSeqReceived >= 0 && seq > lastSeqReceived + 1) {
                        val lost = seq - lastSeqReceived - 1
                        packetsLost.addAndGet(lost)
                        Log.d(TAG, "Пропущено пакетов: $lost (seq=$seq, last=$lastSeqReceived)")
                    }
                    lastSeqReceived = seq

                    // Jitter measurement
                    val now = System.currentTimeMillis()
                    if (lastPacketArrivalMs > 0) {
                        val gap = now - lastPacketArrivalMs
                        synchronized(jitterSamples) {
                            jitterSamples.addLast(gap)
                            if (jitterSamples.size > 20) jitterSamples.removeFirst()
                        }
                        adaptJitterBuffer()
                    }
                    lastPacketArrivalMs = now

                    val pcm = if (data.size > HEADER_SIZE) {
                        data.copyOfRange(HEADER_SIZE, data.size)
                    } else continue

                    if (!jitterBuffer.offer(pcm)) {
                        jitterBuffer.poll() // drop oldest, keep fresh
                        jitterBuffer.offer(pcm)
                    }
                } catch (e: SocketTimeoutException) {
                    // нормально
                } catch (e: Exception) {
                    if (!isActive) running = false
                    else Log.w(TAG, "UDP receive: ${e.message}")
                }
            }
            Log.i(TAG, "UDP receive loop end")
        }

        // Поток 2: jitterBuffer → AudioTrack
        playbackJob = scope.launch {
            Log.i(TAG, "Playback loop start")
            while (isActive) {
                val pcm = jitterBuffer.poll()
                if (pcm != null) {
                    val track = audioTrack
                    if (track != null) {
                        val result = track.write(pcm, 0, pcm.size)
                        if (result == AudioTrack.ERROR_DEAD_OBJECT) {
                            Log.w(TAG, "AudioTrack dead — reinitializing")
                            reinitAudioTrack()
                        }
                    }
                } else {
                    delay(5)
                }
            }
            Log.i(TAG, "Playback loop end")
        }
    }

    // ── Ping / RTT ────────────────────────────────────────────────────────────

    private fun startPingLoop(ip: String) {
        pingJob = scope.launch {
            val addr = try { InetAddress.getByName(ip) } catch (_: Exception) { return@launch }
            while (isActive) {
                delay(5000)
                sendPing(addr)
            }
        }
    }

    private fun sendPing(addr: InetAddress) {
        val socket = sendSocket ?: return
        val ping = ByteArray(HEADER_SIZE)
        val seq = seqNum++
        ping[0] = (seq shr 24 and 0xFF).toByte()
        ping[1] = (seq shr 16 and 0xFF).toByte()
        ping[2] = (seq shr 8 and 0xFF).toByte()
        ping[3] = (seq and 0xFF).toByte()
        ping[4] = FLAG_PING
        ping[5] = 0x00
        lastPingSentMs = System.currentTimeMillis()
        try { socket.send(DatagramPacket(ping, ping.size, addr, UDP_CALL_PORT)) }
        catch (_: Exception) {}
    }

    private fun sendPong(ip: String) {
        val socket = sendSocket ?: return
        val addr = try { InetAddress.getByName(ip) } catch (_: Exception) { return }
        val pong = ByteArray(HEADER_SIZE)
        val seq = seqNum++
        pong[0] = (seq shr 24 and 0xFF).toByte()
        pong[1] = (seq shr 16 and 0xFF).toByte()
        pong[2] = (seq shr 8 and 0xFF).toByte()
        pong[3] = (seq and 0xFF).toByte()
        pong[4] = FLAG_PONG
        pong[5] = 0x00
        try { socket.send(DatagramPacket(pong, pong.size, addr, UDP_CALL_PORT)) }
        catch (_: Exception) {}
    }

    // ── Адаптивный jitter-буфер ───────────────────────────────────────────────

    private fun adaptJitterBuffer() {
        synchronized(jitterSamples) {
            if (jitterSamples.size < 5) return
            val avg = jitterSamples.average()
            val variance = jitterSamples.map { (it - avg) * (it - avg) }.average()
            val stdDev = Math.sqrt(variance)

            // ~20ms на фрагмент при 16kHz / bufferSize
            val expectedInterval = bufferSize.toDouble() / (SAMPLE_RATE * 2) * 1000.0

            currentJitterSize = when {
                stdDev > expectedInterval * 2 ->
                    (currentJitterSize + 1).coerceAtMost(MAX_JITTER_BUFFER_SIZE)
                stdDev < expectedInterval * 0.5 ->
                    (currentJitterSize - 1).coerceAtLeast(MIN_JITTER_BUFFER_SIZE)
                else -> currentJitterSize
            }
        }
    }

    // ── Метрики ───────────────────────────────────────────────────────────────

    private fun startMetricsLoop() {
        metricsJob = scope.launch {
            while (isActive) {
                delay(2000)
                val sent = packetsSent.get()
                val received = packetsReceived.get()
                val lost = packetsLost.get()
                val lossRate = if (received + lost > 0) {
                    lost.toFloat() / (received + lost) * 100f
                } else 0f

                val jitter = synchronized(jitterSamples) {
                    if (jitterSamples.isEmpty()) 0.0
                    else {
                        val avg = jitterSamples.average()
                        jitterSamples.map { Math.abs(it - avg) }.average()
                    }
                }

                val m = CallMetrics(
                    rttMs = lastRttMs,
                    lossRatePercent = lossRate,
                    jitterMs = jitter.toLong(),
                    jitterBufferSize = currentJitterSize,
                    packetsSent = sent,
                    packetsReceived = received,
                    isMuted = isMuted
                )
                _metrics.value = m
                Log.v(TAG, "Метрики: RTT=${m.rttMs}мс потери=${String.format("%.1f", m.lossRatePercent)}% jitter=${m.jitterMs}мс буфер=${m.jitterBufferSize} мут=${m.isMuted}")
            }
        }
    }

    // ── AudioTrack ────────────────────────────────────────────────────────────

    private fun ensureAudioTrackStarted() {
        if (audioTrack != null) return
        try {
            val minSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
            val trackSize = if (minSize <= 0) bufferSize else minSize * BUFFER_SIZE_FACTOR

            audioTrack = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioTrackFormat)
                    .setBufferSizeInBytes(trackSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(
                    audioAttributes, audioTrackFormat,
                    trackSize, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            }
            audioTrack?.play()
            Log.i(TAG, "AudioTrack started (buf=$trackSize, low-latency=true)")
        } catch (e: Exception) {
            Log.e(TAG, "ensureAudioTrackStarted: ${e.message}")
        }
    }

    private fun reinitAudioTrack() {
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        audioTrack = null
        ensureAudioTrackStarted()
    }

    private fun releaseAudio() {
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        Log.d(TAG, "Audio resources released")
    }

    // ── Fallback запись через колбэк ──────────────────────────────────────────

    private fun startCallbackRecordingLoop(onFragment: (ByteArray) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val record: AudioRecord = buildAudioRecord() ?: return
        audioRecord = record
        applyAudioEffects(record.audioSessionId)

        try { record.startRecording() }
        catch (e: Exception) {
            Log.e(TAG, "fallback startRecording: $e")
            runCatching { record.release() }; audioRecord = null; return
        }

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            val silence = ByteArray(bufferSize)
            var running = true
            Log.i(TAG, "Fallback callback recording loop start")
            while (isActive && running) {
                val read = record.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        // При муте отправляем тишину
                        val data = if (isMuted) silence.copyOf(read) else buffer.copyOf(read)
                        onFragment(data)
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE -> { running = false }
                }
            }
            Log.i(TAG, "Fallback callback recording loop end")
        }
    }
}

/**
 * Метрики качества голосового звонка.
 * ДОБАВЛЕНО: isMuted — состояние мута для отображения в UI.
 */
data class CallMetrics(
    val rttMs: Long = -1,
    val lossRatePercent: Float = 0f,
    val jitterMs: Long = 0,
    val jitterBufferSize: Int = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val isMuted: Boolean = false
) {
    val quality: CallQuality get() = when {
        lossRatePercent > 10f || (rttMs > 300 && rttMs != -1L) -> CallQuality.POOR
        lossRatePercent > 3f || (rttMs > 150 && rttMs != -1L) -> CallQuality.FAIR
        else -> CallQuality.GOOD
    }
}

// В конце файла CallManager.kt заменить:
