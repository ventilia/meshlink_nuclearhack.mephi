package com.example.meshlink.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.domain.model.NetworkDevice
import com.example.meshlink.domain.model.chat.ChatPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatListViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as MeshLinkApp).container

    private val _chatPreviews = MutableStateFlow<List<ChatPreview>>(emptyList())
    val chatPreviews: StateFlow<List<ChatPreview>> = _chatPreviews

    private val _connectedDevices = MutableStateFlow<List<NetworkDevice>>(emptyList())
    val connectedDevices: StateFlow<List<NetworkDevice>> = _connectedDevices

    /** true пока идёт pull-to-refresh */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /** Диалог подтверждения удаления: peerId или null */
    private val _deleteConfirmPeerId = MutableStateFlow<String?>(null)
    val deleteConfirmPeerId: StateFlow<String?> = _deleteConfirmPeerId

    init {
        viewModelScope.launch {
            container.chatRepository.getAllChatPreviewsAsFlow().collectLatest {
                _chatPreviews.value = it
            }
        }

        viewModelScope.launch {
            combine(
                container.networkManager.connectedDevices,
                container.chatRepository.getAllChatPreviewsAsFlow()
            ) { devicesMap, previews ->
                val chatPeerIds = previews.map { it.contact.peerId }.toSet()
                devicesMap.values
                    .filter { device ->
                        device.shortCode.isNotBlank() &&
                                device.peerId.isNotBlank() &&
                                device.peerId !in chatPeerIds
                    }
                    .toList()
            }.collectLatest { filtered ->
                _connectedDevices.value = filtered
            }
        }
    }

    /** Pull-to-refresh — принудительный rediscover */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            container.networkManager.forceRediscover()
            // Небольшая задержка чтобы UI показал индикатор
            kotlinx.coroutines.delay(1_500)
            _isRefreshing.value = false
        }
    }

    /** Запросить удаление чата — показывает диалог подтверждения */
    fun requestDeleteChat(peerId: String) {
        _deleteConfirmPeerId.value = peerId
    }

    /** Отмена удаления */
    fun cancelDeleteChat() {
        _deleteConfirmPeerId.value = null
    }

    /** Подтвердить удаление чата — удаляет сообщения, аккаунт и профиль */
    fun confirmDeleteChat(peerId: String) {
        viewModelScope.launch {
            container.chatRepository.deleteAllMessagesByPeerId(peerId)
            // Убираем пира из реестра (опционально, чтобы пропал из NEARBY тоже)
            container.networkManager.removePeerFromRegistry(peerId)
            _deleteConfirmPeerId.value = null
        }
    }
}
