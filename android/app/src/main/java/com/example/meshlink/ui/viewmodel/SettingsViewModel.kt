package com.example.meshlink.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meshlink.MeshLinkApp
import com.example.meshlink.core.NativeCore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as MeshLinkApp).container

    val username: StateFlow<String> = container.ownProfileRepository
        .getProfileAsFlow()
        .map { it.username }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * Имя файла фото профиля (null если не установлено).
     * Файл физически лежит в filesDir под этим именем.
     */
    val profileImageFileName: StateFlow<String?> = container.ownProfileRepository
        .getProfileAsFlow()
        .map { it.imageFileName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val peerCount: StateFlow<Int> = container.networkManager.connectedDevices
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val meshRouteCount: StateFlow<Int> = container.networkManager.connectedDevices
        .map { devices -> devices.values.count { it.hopCount > 1 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isWifiDirectEnabled: StateFlow<Boolean> =
        container.networkManager.receiver.isWifiDirectEnabled

    val ownPeerId: StateFlow<String> = container.networkManager.ownPeerIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val ownShortCode: StateFlow<String> = ownPeerId
        .map { peerId ->
            if (NativeCore.isInitialized()) NativeCore.getOwnShortCode()
            else if (peerId.isNotBlank()) peerId.take(4).uppercase()
            else "----"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "----")

    val coreVersion: StateFlow<String> = ownPeerId
        .map { if (NativeCore.isInitialized()) NativeCore.getVersion() else "N/A" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "N/A")

    val rustRouteCount: StateFlow<Int> = ownPeerId
        .map { if (NativeCore.isInitialized()) NativeCore.getRouteCount() else 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setUsername(username: String) {
        if (username.isBlank()) return
        viewModelScope.launch {
            container.ownProfileRepository.setUsername(username)
        }
    }

    /**
     * Сохраняет фото профиля из URI (выбранного через галерею/камеру).
     *
     * Алгоритм:
     * 1. Копируем файл в filesDir через FileManager
     * 2. Обновляем imageFileName в OwnProfileRepository (DataStore)
     * 3. NetworkManager при следующем keepalive/profile-exchange отправит
     *    обновлённый профиль соседним пирам
     */
    fun setProfileImage(uri: Uri) {
        viewModelScope.launch {
            val peerId = ownPeerId.value.ifBlank { "own" }
            val fileName = container.fileManager.saveProfileImage(uri, peerId)
            if (fileName != null) {
                container.ownProfileRepository.setImageFileName(fileName)
            }
        }
    }

    /** Удалить фото профиля */
    fun removeProfileImage() {
        viewModelScope.launch {
            val currentFile = profileImageFileName.value
            if (currentFile != null) {
                container.fileManager.getFile(currentFile).delete()
            }
            container.ownProfileRepository.setImageFileName("")
        }
    }
}