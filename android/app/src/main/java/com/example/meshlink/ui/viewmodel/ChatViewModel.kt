package com.example.meshlink.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.MeshLinkAppProvider
import com.example.meshlink.core.MeshLogger
import com.example.meshlink.data.local.alias.AliasEntity
import com.example.meshlink.domain.model.message.Message
import com.example.meshlink.domain.model.message.MessageState
import com.example.meshlink.network.AudioPlaybackManager
import com.example.meshlink.network.CallManager
import com.example.meshlink.network.CallMetrics
import com.example.meshlink.network.NetworkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * ChatViewModel — управляет логикой чата с конкретным пиром.
 *
 * ИЗМЕНЕНИЯ:
 *  - Рингтон при входящем звонке УДАЛЁН отсюда. Теперь он живёт в GlobalCallManager
 *    (уровень Application) и играет независимо от открытого экрана.
 *  - При acceptCall() / rejectCall() / endCall() явно вызываем
 *    globalCallManager.stopRingtone() для мгновенной остановки.
 *  - observeCallSignals() больше не вызывает startRingtone(), только управляет
 *    UI-состоянием (показать диалог звонка, скрыть и т.д.).
 */
class ChatViewModel(
    application: Application,
    val peerId: String
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val OUTGOING_CALL_TIMEOUT_MS = 30_000L
    }

    private val meshLinkApp = application as MeshLinkApp
    private val container      = meshLinkApp.container
    private val networkManager: NetworkManager = container.networkManager
    private val callManager: CallManager       = container.callManager
    private val audioPlayback = AudioPlaybackManager(application)

    // ── Сообщения ─────────────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    // ── Состояние звонка ──────────────────────────────────────────────────────

    private val _incomingCall = MutableStateFlow<String?>(null)
    val incomingCall: StateFlow<String?> = _incomingCall

    private val _outgoingCall = MutableStateFlow(false)
    val outgoingCall: StateFlow<Boolean> = _outgoingCall

    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration

    val callMetrics: StateFlow<CallMetrics> = callManager.metrics

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    // ── Профиль ───────────────────────────────────────────────────────────────

    val playingFile: StateFlow<String?> = audioPlayback.playingFile

    val contactName: StateFlow<String?> = combine(
        container.aliasDao.getByPeerIdAsFlow(peerId),
        container.contactRepository.getContactByPeerIdAsFlow(peerId)
    ) { alias, contact ->
        alias?.alias?.takeIf { it.isNotBlank() }
            ?: contact?.profile?.username?.takeIf { it.isNotBlank() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val contactImageFileName: StateFlow<String?> = container.contactRepository
        .getContactByPeerIdAsFlow(peerId)
        .map { it?.profile?.imageFileName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentAlias: StateFlow<String> = container.aliasDao
        .getByPeerIdAsFlow(peerId)
        .map { it?.alias ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val isOnline: StateFlow<Boolean> = networkManager.connectedDevices
        .map { it.containsKey(peerId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hopCount: StateFlow<Int> = networkManager.connectedDevices
        .map { it[peerId]?.hopCount ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val ownPeerId: StateFlow<String> = networkManager.ownPeerIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ── Внутреннее ────────────────────────────────────────────────────────────

    private var audioTempFile: File? = null
    private var currentRecorder: android.media.MediaRecorder? = null
    private var outgoingCallTimeoutJob: Job? = null
    private var callDurationJob: Job? = null

    init {
        observeMessages()
        observeCallSignals()
        observeCallFragments()
        MeshLogger.системаСтарт(peerId, "ChatVM")
        Log.i(TAG, "ChatViewModel launched for ${peerId.take(8)}")
    }

    // ── Наблюдение ────────────────────────────────────────────────────────────

    private fun observeMessages() {
        viewModelScope.launch {
            container.chatRepository.getMessagesByPeerIdAsFlow(peerId).collectLatest {
                _messages.value = it
            }
        }
    }

    private fun observeCallSignals() {
        // Входящий звонок — только от НАШЕГО пира
        viewModelScope.launch {
            networkManager.callRequest.collect { req ->
                if (req != null && req.senderId == peerId) {
                    MeshLogger.звонокВходящий(peerId)
                    Log.i(TAG, "Incoming call from ${peerId.take(8)}")
                    _incomingCall.value = req.senderId
                    // Рингтон НЕ ЗАПУСКАЕМ здесь — это делает GlobalCallManager.
                    // Чат просто показывает UI-диалог принятия звонка.
                }
            }
        }

        // Ответ на исходящий звонок
        viewModelScope.launch {
            networkManager.callResponse.collect { res ->
                if (res != null && res.senderId == peerId) {
                    Log.i(TAG, "callResponse from ${peerId.take(8)}: accepted=${res.accepted}")
                    outgoingCallTimeoutJob?.cancel()
                    // GlobalCallManager сам остановит рингтон по callResponse flow,
                    // но дополнительно вызываем явно для мгновенного эффекта
                    stopGlobalRingtone()

                    if (res.accepted) {
                        MeshLogger.звонокПринят(peerId)
                        _outgoingCall.value = false
                        startActiveCall()
                    } else {
                        MeshLogger.звонокОтклонён(peerId)
                        _outgoingCall.value = false
                        networkManager.resetCallState()
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
                    outgoingCallTimeoutJob?.cancel()
                    stopGlobalRingtone()
                    stopCallDurationTimer()
                    _outgoingCall.value = false
                    _incomingCall.value = null
                    _callActive.value   = false
                    callManager.stopSession()
                    _isMuted.value      = false
                    _isSpeakerOn.value  = false
                    networkManager.resetCallState()
                }
            }
        }
    }

    private fun observeCallFragments() {
        viewModelScope.launch {
            networkManager.callFragment.collectLatest { fragment ->
                if (fragment != null && _callActive.value) {
                    callManager.playFragment(fragment)
                }
            }
        }
    }

    // ── Рингтон — делегируем GlobalCallManager ────────────────────────────────

    /**
     * Мгновенно останавливает рингтон через GlobalCallManager.
     * Вызывается при любом явном действии пользователя со звонком.
     */
    private fun stopGlobalRingtone() {
        meshLinkApp.globalCallManager.stopRingtone()
    }

    // ── Таймер звонка ─────────────────────────────────────────────────────────

    private fun startCallDurationTimer() {
        _callDuration.value = 0L
        callDurationJob?.cancel()
        callDurationJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _callDuration.value++
            }
        }
    }

    private fun stopCallDurationTimer() {
        callDurationJob?.cancel()
        callDurationJob = null
        _callDuration.value = 0L
    }

    // ── Сообщения ─────────────────────────────────────────────────────────────

    fun sendText(peerId: String, text: String) {
        MeshLogger.сообщениеОтправлено("ТЕКСТ", peerId, System.currentTimeMillis())
        networkManager.sendTextMessage(peerId, text)
    }

    fun sendFile(peerId: String, uri: Uri) {
        networkManager.sendFileMessage(peerId, uri)
    }

    fun getFile(fileName: String): File = container.fileManager.getFile(fileName)

    fun startRecording(context: Context) {
        if (_isRecording.value) return
        val tempFile = container.fileManager.getAudioTempFile()
        audioTempFile = tempFile
        try {
            val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") android.media.MediaRecorder()
            }
            recorder.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }
            viewModelScope.launch {
                _isRecording.value = true
                currentRecorder = recorder
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: ${e.message}")
            _isRecording.value = false
        }
    }

    fun stopRecordingAndSend(peerId: String) {
        viewModelScope.launch {
            _isRecording.value = false
            try {
                currentRecorder?.stop()
                currentRecorder?.release()
                currentRecorder = null
            } catch (e: Exception) {
                Log.w(TAG, "stopRecorder: ${e.message}")
            }
            val file = audioTempFile
            if (file != null && file.exists() && file.length() > 0) {
                networkManager.sendAudioMessage(peerId, file)
                audioTempFile = null
            }
        }
    }

    fun playAudio(fileName: String) {
        audioPlayback.play(container.fileManager.getFile(fileName))
    }

    fun markAllRead(peerId: String) {
        viewModelScope.launch {
            _messages.value
                .filter { it.senderId == peerId && it.messageState == MessageState.MESSAGE_RECEIVED }
                .forEach { msg ->
                    container.chatRepository.updateMessageState(msg.messageId, MessageState.MESSAGE_READ)
                    MeshLogger.подтверждениеПрочтения(msg.messageId, peerId)
                    networkManager.sendMessageReadAck(peerId, msg.messageId)
                }
        }
    }

    fun saveAlias(alias: String) {
        viewModelScope.launch {
            if (alias.isBlank()) {
                container.aliasDao.deleteByPeerId(peerId)
            } else {
                container.aliasDao.insertOrUpdate(AliasEntity(peerId, alias.trim()))
            }
        }
    }

    // ── Звонки ────────────────────────────────────────────────────────────────

    fun requestCall() {
        MeshLogger.звонокИсходящий(peerId)
        _outgoingCall.value = true
        networkManager.sendCallRequest(peerId)

        outgoingCallTimeoutJob?.cancel()
        outgoingCallTimeoutJob = viewModelScope.launch {
            delay(OUTGOING_CALL_TIMEOUT_MS)
            if (_outgoingCall.value) {
                Log.w(TAG, "Outgoing call timeout to ${peerId.take(8)}")
                MeshLogger.звонокЗавершён(peerId, "таймаут")
                _outgoingCall.value = false
                networkManager.resetCallState()
            }
        }
    }

    fun acceptCall() {
        MeshLogger.звонокПринят(peerId)
        stopGlobalRingtone()           // мгновенно останавливаем рингтон
        networkManager.sendCallResponse(peerId, true)
        startActiveCall()
        _incomingCall.value = null
    }

    fun rejectCall() {
        MeshLogger.звонокОтклонён(peerId)
        stopGlobalRingtone()           // мгновенно останавливаем рингтон
        networkManager.sendCallResponse(peerId, false)
        _incomingCall.value = null
    }

    fun endCall() {
        outgoingCallTimeoutJob?.cancel()
        if (_callActive.value || _outgoingCall.value) {
            MeshLogger.звонокЗавершён(peerId, "локально")
            networkManager.sendCallEnd(peerId)
        }
        stopGlobalRingtone()
        stopCallDurationTimer()
        callManager.stopSession()
        _callActive.value   = false
        _outgoingCall.value = false
        _incomingCall.value = null
        _isMuted.value      = false
        _isSpeakerOn.value  = false
        networkManager.resetCallState()
    }

    private fun startActiveCall() {
        MeshLogger.звонокАктивен(peerId)
        _callActive.value = true
        startCallDurationTimer()

        if (callManager.hasAudioPermission(getApplication())) {
            val ip = networkManager.getIpForPeer(peerId)
            if (ip != null) {
                Log.i(TAG, "Starting UDP audio session → $ip")
                callManager.startSession(ip)
            } else {
                Log.w(TAG, "No IP for ${peerId.take(8)} — fallback TCP")
                callManager.startRecording { fragment ->
                    MeshLogger.фрагментЗвонкаОтправлен(peerId, fragment.size)
                    networkManager.sendCallFragment(peerId, fragment)
                }
            }
        } else {
            Log.w(TAG, "No RECORD_AUDIO permission")
        }
    }

    // ── Управление звуком во время звонка ─────────────────────────────────────

    fun toggleMute() {
        val muted = !_isMuted.value
        _isMuted.value = muted
        callManager.setMuted(muted)
        Log.d(TAG, "Microphone: ${if (muted) "MUTED" else "ACTIVE"}")
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
        _isSpeakerOn.value = callManager.isSpeakerOn
        Log.d(TAG, "Speaker: ${_isSpeakerOn.value}")
    }

    // ── Жизненный цикл ────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        outgoingCallTimeoutJob?.cancel()
        callDurationJob?.cancel()
        if (_callActive.value) endCall()
        audioPlayback.release()
        currentRecorder?.release()
    }
}

class ChatViewModelFactory(private val peerId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            val app = MeshLinkAppProvider.app ?: error("MeshLinkApp не инициализирован")
            return ChatViewModel(app as Application, peerId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}