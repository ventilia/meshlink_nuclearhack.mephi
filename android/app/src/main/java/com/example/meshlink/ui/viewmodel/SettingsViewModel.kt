package com.example.meshlink.ui.viewmodel

import android.app.Application
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

    val peerCount: StateFlow<Int> = container.networkManager.connectedDevices
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Количество mesh-маршрутов (через relay) */
    val meshRouteCount: StateFlow<Int> = container.networkManager.connectedDevices
        .map { devices -> devices.values.count { it.hopCount > 1 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isWifiDirectEnabled: StateFlow<Boolean> =
        container.networkManager.receiver.isWifiDirectEnabled

    /** Собственный peerId из NetworkManager (живёт в памяти, стабилен после init) */
    val ownPeerId: StateFlow<String> = container.networkManager.ownPeerIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * ShortCode — из Rust ядра (если инициализировано) или первые 4 символа peerId.
     * Именно это видят другие устройства.
     */
    val ownShortCode: StateFlow<String> = ownPeerId
        .map { peerId ->
            if (NativeCore.isInitialized()) NativeCore.getOwnShortCode()
            else if (peerId.isNotBlank()) peerId.take(4).uppercase()
            else "----"
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "----")

    /** Версия Rust ядра */
    val coreVersion: StateFlow<String> = ownPeerId
        .map { if (NativeCore.isInitialized()) NativeCore.getVersion() else "N/A" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "N/A")

    /** Количество маршрутов в Rust routing table */
    val rustRouteCount: StateFlow<Int> = ownPeerId
        .map { if (NativeCore.isInitialized()) NativeCore.getRouteCount() else 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setUsername(username: String) {
        if (username.isBlank()) return
        viewModelScope.launch {
            container.ownProfileRepository.setUsername(username)
            // Имя хранится в профиле — Rust ядро не нужно перезапускать
        }
    }
}
