// ФАЙЛ: C:\Users\GAMER\AndroidStudioProjects\meshlink_nuclearhack.mephi\android\app\src\main\java\com\example\meshlink\ui\viewmodel\ChatViewModel.kt
package com.example.meshlink.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.MeshLinkAppProvider
import com.example.meshlink.core.MeshLogger
import com.example.meshlink.data.local.alias.AliasEntity
import com.example.meshlink.domain.model.CallType
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

class ChatViewModel(
    application: Application,
    val peerId: String
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val OUTGOING_CALL_TIMEOUT_MS = 30_000L
    }

    private val container = (application as MeshLinkApp).container
    private val networkManager: NetworkManager = container.networkManager
    private val callManager: CallManager = container.callManager
    private val audioPlayback = AudioPlaybackManager(application)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _incomingCallType = MutableStateFlow<CallType?>(null)
    val incomingCallType: StateFlow<CallType?> = _incomingCallType

    private val _incomingCall = MutableStateFlow<String?>(null)
    val incomingCall: StateFlow<String?> = _incomingCall

    private val _outgoingCall = MutableStateFlow(false)
    val outgoingCall: StateFlow<Boolean> = _outgoingCall

    private val _outgoingCallType = MutableStateFlow<CallType>(CallType.AUDIO)
    val outgoingCallType: StateFlow<CallType> = _outgoingCallType

    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive

    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration

    val callMetrics: StateFlow<CallMetrics> = callManager.metrics

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    // ИСПРАВЛЕНО: Канал для перенаправления пользователя на экран видеозвонка
    private val _incomingVideoCallEvent = MutableSharedFlow<String>()
    val incomingVideoCallEvent = _incomingVideoCallEvent.asSharedFlow()

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

    private var audioTempFile: File? = null
    private var currentRecorder: android.media.MediaRecorder? = null
    private var ringtonePlayer: android.media.MediaPlayer? = null
    private var outgoingCallTimeoutJob: Job? = null
    private var callDurationJob: Job? = null

    init {
        observeMessages()
        observeCallSignals()
        observeCallFragments()
        MeshLogger.системаСтарт(peerId, "ChatVM")
    }

    private fun observeMessages() {
        viewModelScope.launch {
            container.chatRepository.getMessagesByPeerIdAsFlow(peerId).collectLatest {
                _messages.value = it
            }
        }
    }

    private fun observeCallSignals() {
        // Аудио звонки
        viewModelScope.launch {
            networkManager.audioCallRequest.collect { req ->
                if (req != null && req.senderId == peerId) {
                    Log.i(TAG, "Incoming AUDIO call from ${peerId.take(8)}")
                    MeshLogger.звонокВходящий(peerId)
                    _incomingCallType.value = CallType.AUDIO
                    _incomingCall.value = req.senderId
                    startRingtone()
                }
            }
        }

        // ИСПРАВЛЕНО: Эмитим событие при получении видеозвонка для навигации
        viewModelScope.launch {
            networkManager.videoCallRequest.collect { req ->
                if (req != null && req.senderId == peerId) {
                    Log.i(TAG, "Incoming VIDEO call from ${peerId.take(8)}, triggering navigation")
                    MeshLogger.звонокВходящий(peerId)
                    _incomingVideoCallEvent.emit(req.senderId)
                }
            }
        }

        viewModelScope.launch {
            networkManager.callResponse.collect { res ->
                if (res != null && res.senderId == peerId) {
                    outgoingCallTimeoutJob?.cancel()
                    stopRingtone()

                    if (res.callType == _outgoingCallType.value) {
                        if (res.accepted) {
                            MeshLogger.звонокПринят(peerId)
                            _outgoingCall.value = false
                            startActiveCall(_outgoingCallType.value)
                        } else {
                            MeshLogger.звонокОтклонён(peerId)
                            _outgoingCall.value = false
                            networkManager.resetCallState()
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            networkManager.callEnd.collect { end ->
                if (end != null && end.senderId == peerId) {
                    MeshLogger.звонокЗавершён(peerId, "удалённый пир")
                    outgoingCallTimeoutJob?.cancel()
                    stopRingtone()
                    stopCallDurationTimer()
                    _outgoingCall.value = false
                    _incomingCall.value = null
                    _incomingCallType.value = null
                    _callActive.value = false
                    callManager.stopSession()
                    _isMuted.value = false
                    _isSpeakerOn.value = false
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

    private fun startRingtone() {
        stopRingtone()
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = android.media.MediaPlayer().apply {
                setDataSource(getApplication<Application>(), uri)
                isLooping = true
                @Suppress("DEPRECATION")
                setAudioStreamType(android.media.AudioManager.STREAM_RING)
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun stopRingtone() {
        try {
            ringtonePlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            ringtonePlayer = null
        } catch (_: Exception) {}
    }

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

    fun sendText(peerId: String, text: String) {
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
            } catch (_: Exception) {}
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

    fun requestCall(callType: CallType = CallType.AUDIO) {
        MeshLogger.звонокИсходящий(peerId)
        _outgoingCall.value = true
        _outgoingCallType.value = callType

        if (callType == CallType.VIDEO) {
            _outgoingCall.value = false
            return
        }

        networkManager.sendCallRequest(peerId, CallType.AUDIO)
        outgoingCallTimeoutJob?.cancel()
        outgoingCallTimeoutJob = viewModelScope.launch {
            delay(OUTGOING_CALL_TIMEOUT_MS)
            if (_outgoingCall.value) {
                MeshLogger.звонокЗавершён(peerId, "таймаут")
                _outgoingCall.value = false
                networkManager.resetCallState()
            }
        }
    }

    fun acceptCall() {
        val callType = _incomingCallType.value ?: CallType.AUDIO
        MeshLogger.звонокПринят(peerId)
        stopRingtone()
        networkManager.sendCallResponse(peerId, true, callType)
        startActiveCall(callType)
        _incomingCall.value = null
        _incomingCallType.value = null
    }

    fun rejectCall() {
        val callType = _incomingCallType.value ?: CallType.AUDIO
        MeshLogger.звонокОтклонён(peerId)
        stopRingtone()
        networkManager.sendCallResponse(peerId, false, callType)
        _incomingCall.value = null
        _incomingCallType.value = null
    }

    fun endCall() {
        outgoingCallTimeoutJob?.cancel()
        if (_callActive.value || _outgoingCall.value) {
            MeshLogger.звонокЗавершён(peerId, "локально")
            val callType = if (_outgoingCall.value) _outgoingCallType.value else CallType.AUDIO
            networkManager.sendCallEnd(peerId, callType)
        }
        stopRingtone()
        stopCallDurationTimer()
        callManager.stopSession()
        _callActive.value = false
        _outgoingCall.value = false
        _incomingCall.value = null
        _incomingCallType.value = null
        _isMuted.value = false
        _isSpeakerOn.value = false
        networkManager.resetCallState()
    }

    private fun startActiveCall(callType: CallType) {
        MeshLogger.звонокАктивен(peerId)
        _callActive.value = true
        startCallDurationTimer()

        if (callType == CallType.AUDIO) {
            if (callManager.hasAudioPermission(getApplication())) {
                val ip = networkManager.getIpForPeer(peerId)
                if (ip != null) {
                    callManager.startSession(ip)
                } else {
                    callManager.startRecording { fragment ->
                        networkManager.sendCallFragment(peerId, fragment)
                    }
                }
            }
        }
    }

    fun toggleMute() {
        val muted = !_isMuted.value
        _isMuted.value = muted
        callManager.setMuted(muted)
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
        _isSpeakerOn.value = callManager.isSpeakerOn
    }

    override fun onCleared() {
        super.onCleared()
        outgoingCallTimeoutJob?.cancel()
        callDurationJob?.cancel()
        if (_callActive.value) endCall()
        stopRingtone()
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