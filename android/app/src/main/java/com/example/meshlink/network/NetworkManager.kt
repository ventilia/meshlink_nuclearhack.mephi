package com.example.meshlink.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.example.meshlink.AppForegroundTracker
import com.example.meshlink.core.MeshLogger
import com.example.meshlink.core.NativeCore
import com.example.meshlink.data.local.FileManager
import com.example.meshlink.domain.model.*
import com.example.meshlink.domain.model.device.Account
import com.example.meshlink.domain.model.device.Profile
import com.example.meshlink.domain.model.message.*
import com.example.meshlink.domain.repository.ChatRepository
import com.example.meshlink.domain.repository.ContactRepository
import com.example.meshlink.domain.repository.OwnAccountRepository
import com.example.meshlink.domain.repository.OwnProfileRepository
import com.example.meshlink.network.bluetooth.BleDiscovery
import com.example.meshlink.network.bluetooth.BluetoothTransport
import com.example.meshlink.network.discovery.NsdDiscovery
import com.example.meshlink.network.discovery.PeerRegistry
import com.example.meshlink.network.discovery.UdpDiscovery
import com.example.meshlink.network.protocol.PacketType
import com.example.meshlink.network.protocol.json
import com.example.meshlink.network.security.PacketDeduplicator
import com.example.meshlink.network.transport.MeshClient
import com.example.meshlink.network.transport.MeshServer
import com.example.meshlink.network.wifidirect.WiFiDirectBroadcastReceiver
import com.example.meshlink.util.NotificationHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkManager(
    private val context: Context,
    private val ownAccountRepository: OwnAccountRepository,
    private val ownProfileRepository: OwnProfileRepository,
    val receiver: WiFiDirectBroadcastReceiver,
    private val chatRepository: ChatRepository,
    private val contactRepository: ContactRepository,
    private val fileManager: FileManager
) {
    companion object {
        private const val TAG = "NetworkManager"
        const val TCP_PORT = 8800
        private const val KEEPALIVE_INTERVAL_MS = 7_000L

        // Адаптивный keepalive — интервалы в зависимости от idle-времени
        private const val KEEPALIVE_ACTIVE_MS   = 7_000L   // активный режим
        private const val KEEPALIVE_IDLE_MS      = 30_000L  // неактивны 1-5 мин
        private const val KEEPALIVE_SLEEP_MS     = 60_000L  // неактивны >5 мин

        private const val PREFS_NAME = "meshlink_peers"
        private const val PREFS_KEY_KNOWN_IPS = "known_ips"
        private const val MAX_KNOWN_IPS = 20

        // Chunked file transfer packet types
        const val PACKET_FILE_INIT         = 100
        const val PACKET_FILE_CHUNK        = 101
        const val PACKET_FILE_CHUNK_ACK    = 102
        const val PACKET_FILE_RETRY        = 103
        const val PACKET_FILE_COMPLETE     = 104
        const val PACKET_FILE_STATUS_REQ   = 105
        const val PACKET_FILE_STATUS_RESP  = 106
        const val PACKET_FILE_CANCEL       = 107
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var ownPeerId: String = ""
        private set
    @Volatile private var ownUsername: String = ""
    @Volatile private var ownIpAddress: String? = null
    @Volatile private var started = false

    // Время последней сетевой активности — для адаптивного keepalive
    @Volatile private var lastActivityMs = System.currentTimeMillis()

    // Управление паузой discovery (когда экран выключен)
    @Volatile private var discoveryPaused = false
    private var keepaliveJob: Job? = null
    private var udpBroadcastJob: Job? = null

    private val identityReady = CompletableDeferred<Unit>()
    private val _ownPeerIdFlow = MutableStateFlow("")
    val ownPeerIdFlow: StateFlow<String> = _ownPeerIdFlow.asStateFlow()

    private val server = MeshServer(TCP_PORT)
    private val client = MeshClient(TCP_PORT)

    private val bluetoothTransport = BluetoothTransport(context) { type: Int, payload: ByteArray, sender: String ->
        dispatchIncomingPacket(type, payload, sender)
    }

    private val udpDiscovery = UdpDiscovery(TCP_PORT)
    val peerRegistry = PeerRegistry()
    private var nsdDiscovery: NsdDiscovery? = null

    val connectedDevices: StateFlow<Map<String, NetworkDevice>> = peerRegistry.peers

    private val profileCache = mutableMapOf<String, Long>()
    private val profileRequestInFlight = mutableSetOf<String>()
    private val profileLock = Any()

    // Call Signaling Flows
    private val _callRequest  = MutableStateFlow<NetworkCallRequest?>(null)
    val callRequest: StateFlow<NetworkCallRequest?> = _callRequest
    private val _callResponse = MutableStateFlow<NetworkCallResponse?>(null)
    val callResponse: StateFlow<NetworkCallResponse?> = _callResponse
    private val _callEnd      = MutableStateFlow<NetworkCallEnd?>(null)
    val callEnd: StateFlow<NetworkCallEnd?> = _callEnd
    private val _callFragment = MutableStateFlow<ByteArray?>(null)
    val callFragment: StateFlow<ByteArray?> = _callFragment

    // File transfer state
    private val activeTransfers = mutableMapOf<String, FileTransferState>()
    private val transferLock = Any()

    // ── BLE Discovery ────────────────────────────────────────────────────────

    private val bleDiscovery = BleDiscovery(
        context = context,
        onPeerFound = { peerId, shortCode, btAddress, tcpPort ->
            scope.launch {
                identityReady.await()
                if (peerId == ownPeerId) return@launch
                MeshLogger.bleПирОбнаружен(peerId, shortCode, btAddress)
                peerRegistry.setBluetoothAddress(peerId, btAddress)
                peerRegistry.upsert(
                    NetworkDevice(
                        peerId       = peerId,
                        username     = "",
                        shortCode    = shortCode,
                        publicKeyHex = "",
                        ipAddress    = null,
                        keepalive    = System.currentTimeMillis(),
                        hopCount     = 1,
                        viaPeerId    = null
                    ),
                    source = "ble"
                )
            }
        },
        onPeerLost = { btAddress ->
            scope.launch { MeshLogger.bleПирПотерян(btAddress) }
        }
    )

    private val packetDeduplicator = PacketDeduplicator()

    // ── Запуск ───────────────────────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "NetworkManager STARTING")

        setupServerHandlers()
        server.start()
        udpDiscovery.start()
        peerRegistry.start()

        if (bluetoothTransport.isAvailable()) {
            bluetoothTransport.startServer()
            Log.i(TAG, "Bluetooth transport started")
        }

        scope.launch {
            initIdentity()
            val initialIp = getLanIp()
            if (initialIp != null) {
                ownIpAddress = initialIp
                udpDiscovery.updateOwnIp(initialIp)
                Log.i(TAG, "Initial LAN IP: $initialIp")
            }

            identityReady.await()

            if (bleDiscovery.isAvailable()) {
                bleDiscovery.start(ownPeerId, NativeCore.getOwnShortCode(), TCP_PORT)
                MeshLogger.bleСтатус("запущен")
                Log.i(TAG, "BLE запущен | peerId=${ownPeerId.take(8)}...")
            } else {
                MeshLogger.bleСтатус("недоступен")
            }

            reconnectKnownPeers()
            initNsdDiscovery()
            observeUdpPeers()
            observeWifiDirectGroup()
            startKeepaliveLoop()
            warmupProfileCache()
            startPeriodicRediscovery()
        }
    }

    // ── Инициализация идентичности ───────────────────────────────────────────

    private suspend fun initIdentity() {
        try {
            val profile = ownProfileRepository.getProfile()
            ownUsername = profile.username.ifBlank { Build.MODEL }
            val filesDir  = context.filesDir.absolutePath
            val androidId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            ) ?: ""

            ensureIdentityIsUnique(filesDir, androidId)

            val deviceSeed = if (androidId.isNotBlank()) {
                "${ownUsername}_${androidId}"
            } else {
                val salt = prefs.getString("device_salt", null) ?: run {
                    val s = java.util.UUID.randomUUID().toString().take(8)
                    prefs.edit().putString("device_salt", s).apply()
                    s
                }
                "${ownUsername}_${salt}"
            }

            val coreInitialized = NativeCore.initWithFilesDir(filesDir, deviceSeed)
            if (coreInitialized) {
                val rustPeerId    = NativeCore.getOwnPeerIdHex()
                val rustShortCode = NativeCore.getOwnShortCode()
                val rustPubKey    = NativeCore.getOwnPublicKeyHex()

                if (rustPeerId.isNotBlank()) {
                    ownPeerId = rustPeerId
                    ownAccountRepository.setPeerId(ownPeerId)
                    ownProfileRepository.setPeerId(ownPeerId)
                    Log.i(TAG, "✓ Core ONLINE: sc=$rustShortCode id=${ownPeerId.take(16)}...")
                    udpDiscovery.updateOwnIdentity(
                        peerId       = ownPeerId,
                        username     = ownUsername,
                        shortCode    = rustShortCode,
                        publicKeyHex = rustPubKey
                    )
                } else {
                    fallbackIdentity()
                }
            } else {
                Log.w(TAG, "Rust core init failed, using fallback")
                fallbackIdentity()
            }
            _ownPeerIdFlow.value = ownPeerId
            identityReady.complete(Unit)
            Log.i(TAG, "Identity ready: ${ownPeerId.take(16)}... (core=${NativeCore.isInitialized()})")
        } catch (e: Exception) {
            Log.e(TAG, "initIdentity failed: ${e.message}", e)
            fallbackIdentity()
            _ownPeerIdFlow.value = ownPeerId
            identityReady.complete(Unit)
        }
    }

    private fun ensureIdentityIsUnique(filesDir: String, androidId: String) {
        val migrationKey = "identity_migrated_v2"
        if (prefs.getBoolean(migrationKey, false)) return
        val identityFile = File(filesDir, "meshlink_identity.bin")
        if (identityFile.exists()) {
            Log.w(TAG, "Resetting old identity — will regenerate with unique seed")
            identityFile.delete()
        }
        prefs.edit().putBoolean(migrationKey, true).apply()
        Log.i(TAG, "Identity migration done (androidId=${androidId.take(8)}...)")
    }

    private suspend fun fallbackIdentity() {
        var peerId = ownAccountRepository.getAccount().peerId
        if (peerId.isBlank()) {
            peerId = java.util.UUID.randomUUID().toString().replace("-", "")
            ownAccountRepository.setPeerId(peerId)
            ownProfileRepository.setPeerId(peerId)
            Log.i(TAG, "Generated fallback peerId: ${peerId.take(16)}...")
        }
        ownPeerId = peerId
        udpDiscovery.updateOwnIdentity(
            peerId       = ownPeerId,
            username     = ownUsername,
            shortCode    = ownPeerId.take(4).uppercase(),
            publicKeyHex = ""
        )
    }

    // ── Handlers setup ───────────────────────────────────────────────────────

    private fun setupServerHandlers() {
        server.onKeepalive = { keepalive: NetworkKeepalive, senderIp: String ->
            scope.launch {
                identityReady.await()
                handleKeepalive(keepalive, senderIp)
            }
        }
        server.onProfileRequest = { req: NetworkProfileRequest, senderIp: String ->
            scope.launch {
                identityReady.await()
                if (req.senderId.isNotBlank()) handleProfileRequest(req, senderIp)
            }
        }
        server.onProfileResponse = { res: NetworkProfileResponse -> handleProfileResponse(res) }
        server.onTextMessage     = { msg: NetworkTextMessage    -> handleTextMessage(msg) }
        server.onFileMessage     = { msg: NetworkFileMessage    -> handleFileMessage(msg) }
        server.onAudioMessage    = { msg: NetworkAudioMessage   -> handleAudioMessage(msg) }
        server.onAckReceived     = { ack: NetworkMessageAck     -> handleAckReceived(ack) }
        server.onAckRead         = { ack: NetworkMessageAck     -> handleAckRead(ack) }

        // Chunked file transfer
        server.onFileInit      = { msg: NetworkFileInit                           -> handleFileInit(msg) }
        server.onFileChunk     = { msg: NetworkFileChunk, senderIp: String        -> handleFileChunk(msg, senderIp) }
        server.onFileChunkAck  = { msg: NetworkFileChunkAck                       -> handleFileChunkAck(msg) }
        server.onFileComplete  = { msg: NetworkFileComplete                       -> handleFileComplete(msg) }

        // Call Signaling
        server.onCallRequest = { req: NetworkCallRequest ->
            notifyActivity()
            _callRequest.value = req
            // Показываем уведомление только если приложение в фоне.
            // Рингтон в foreground обеспечивает GlobalCallManager.
            if (!AppForegroundTracker.isInForeground()) {
                scope.launch {
                    val contact = contactRepository.getAllContactsAsFlow().first()
                        .find { it.peerId == req.senderId }
                    val callerName = contact?.username ?: req.senderId.take(8).uppercase()
                    NotificationHelper.showCallNotification(context, callerName, req.senderId, req.callType)
                }
            }
        }
        server.onCallResponse = { res: NetworkCallResponse ->
            notifyActivity()
            _callResponse.value = res
        }
        server.onCallEnd = { end: NetworkCallEnd ->
            _callEnd.value = end
            NotificationHelper.dismissCallNotification(context)
        }
        server.onCallAudio = { bytes: ByteArray, _: String -> _callFragment.value = bytes }
    }

    // ── Bluetooth packet dispatch ─────────────────────────────────────────────

    private fun dispatchIncomingPacket(type: Int, payload: ByteArray, senderAddress: String) {
        try {
            when (type) {
                PacketType.KEEPALIVE -> scope.launch {
                    identityReady.await()
                    handleKeepalive(
                        json.decodeFromString<NetworkKeepalive>(payload.decodeToString()),
                        senderAddress
                    )
                }
                PacketType.TEXT_MESSAGE    -> handleTextMessage(json.decodeFromString<NetworkTextMessage>(payload.decodeToString()))
                PacketType.FILE_MESSAGE    -> handleFileMessage(json.decodeFromString<NetworkFileMessage>(payload.decodeToString()))
                PacketType.AUDIO_MESSAGE   -> handleAudioMessage(json.decodeFromString<NetworkAudioMessage>(payload.decodeToString()))
                PacketType.PROFILE_REQUEST -> scope.launch {
                    identityReady.await()
                    handleProfileRequest(
                        json.decodeFromString<NetworkProfileRequest>(payload.decodeToString()),
                        senderAddress
                    )
                }
                PacketType.PROFILE_RESPONSE -> handleProfileResponse(json.decodeFromString<NetworkProfileResponse>(payload.decodeToString()))
                PacketType.CALL_REQUEST     -> _callRequest.value  = json.decodeFromString<NetworkCallRequest>(payload.decodeToString())
                PacketType.CALL_RESPONSE    -> _callResponse.value = json.decodeFromString<NetworkCallResponse>(payload.decodeToString())
                PacketType.CALL_END         -> {
                    _callEnd.value = json.decodeFromString<NetworkCallEnd>(payload.decodeToString())
                    NotificationHelper.dismissCallNotification(context)
                }
                PacketType.CALL_AUDIO -> _callFragment.value = payload
                PACKET_FILE_INIT      -> handleFileInit(json.decodeFromString<NetworkFileInit>(payload.decodeToString()))
                PACKET_FILE_CHUNK     -> handleFileChunk(json.decodeFromString<NetworkFileChunk>(payload.decodeToString()), senderAddress)
                PACKET_FILE_CHUNK_ACK -> handleFileChunkAck(json.decodeFromString<NetworkFileChunkAck>(payload.decodeToString()))
                PACKET_FILE_COMPLETE  -> handleFileComplete(json.decodeFromString<NetworkFileComplete>(payload.decodeToString()))
                PacketType.ACK_RECEIVED -> handleAckReceived(json.decodeFromString<NetworkMessageAck>(payload.decodeToString()))
                PacketType.ACK_READ     -> handleAckRead(json.decodeFromString<NetworkMessageAck>(payload.decodeToString()))
                else -> Log.d(TAG, "BT unknown packet type=$type from $senderAddress")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BT dispatch error type=$type: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // CHUNKED FILE TRANSFER — INCOMING
    // ────────────────────────────────────────────────────────────────────────

    val isBleActive: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        scope.launch {
            identityReady.await()
            (flow as MutableStateFlow).value = bleDiscovery.isAvailable()
        }
    }

    private fun handleFileInit(msg: NetworkFileInit) {
        // Mesh forwarding
        if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
            if (msg.ttl > 0) {
                scope.launch {
                    val forwarded = msg.copy(ttl = msg.ttl - 1)
                    val payload = json.encodeToString(NetworkFileInit.serializer(), forwarded).encodeToByteArray()
                    sendPacket(msg.receiverId, PACKET_FILE_INIT, payload, "forward-file-init")
                }
            }
            return
        }
        scope.launch {
            Log.i(TAG, "[FILE] Init: '${msg.fileName}' ${msg.fileSize}B, ${msg.totalChunks} chunks from ${msg.senderId.take(8)}")
            val transferKey = "transfer_in_${msg.transferId}"
            prefs.edit()
                .putString("${transferKey}_meta", json.encodeToString(msg))
                .putLong("${transferKey}_started", System.currentTimeMillis())
                .putInt("${transferKey}_total", msg.totalChunks)
                .putString("${transferKey}_hash", msg.fileHash)
                .apply()

            val senderIp = peerRegistry.getIp(msg.senderId)
            if (senderIp != null) {
                val status = NetworkFileStatusResponse(
                    transferId      = msg.transferId,
                    totalChunks     = msg.totalChunks,
                    receivedChunks  = emptyList(),
                    canResume       = false
                )
                val payload = json.encodeToString(NetworkFileStatusResponse.serializer(), status).encodeToByteArray()
                client.sendRaw(senderIp, PACKET_FILE_STATUS_RESP, payload)
            }
        }
    }

    private fun handleFileChunk(msg: NetworkFileChunk, senderIp: String) {
        scope.launch {
            identityReady.await()
            val transferKey = "transfer_in_${msg.transferId}"
            val metaJson = prefs.getString("${transferKey}_meta", null)
            if (metaJson == null) {
                Log.w(TAG, "[FILE] Chunk received for unknown transfer: ${msg.transferId}")
                sendChunkAck(msg.transferId, msg.chunkIndex, "", false, senderIp)
                return@launch
            }
            val meta = try {
                json.decodeFromString<NetworkFileInit>(metaJson)
            } catch (e: Exception) {
                Log.e(TAG, "[FILE] Failed to parse transfer meta: ${e.message}")
                return@launch
            }
            val chunkData = try {
                Base64.decode(msg.data, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "[FILE] Base64 decode failed for chunk ${msg.chunkIndex}")
                sendChunkAck(msg.transferId, msg.chunkIndex, "", false, senderIp)
                return@launch
            }
            val actualHash = FileManager.computeSha256(chunkData)
            if (actualHash != msg.chunkHash) {
                Log.w(TAG, "[FILE] Chunk ${msg.chunkIndex} hash mismatch!")
                sendChunkAck(msg.transferId, msg.chunkIndex, actualHash, false, senderIp)
                return@launch
            }
            val saved = fileManager.saveChunkForAssembly(
                transferId   = msg.transferId,
                chunkIndex   = msg.chunkIndex,
                chunkData    = chunkData,
                expectedHash = msg.chunkHash
            )
            if (saved) {
                val received = prefs.getStringSet("${transferKey}_received", emptySet())?.toMutableSet() ?: mutableSetOf()
                received.add(msg.chunkIndex.toString())
                prefs.edit().putStringSet("${transferKey}_received", received).apply()

                MeshLogger.файлЧанк(msg.transferId, msg.chunkIndex, msg.totalChunks)
                sendChunkAck(msg.transferId, msg.chunkIndex, actualHash, true, senderIp)

                if (received.size == meta.totalChunks) {
                    Log.i(TAG, "[FILE] All chunks received, assembling...")
                    val outputFile = fileManager.assembleFile(
                        transferId       = msg.transferId,
                        outputFileName   = meta.fileName,
                        expectedFileHash = meta.fileHash
                    )
                    val success    = outputFile?.exists() == true
                    val finalHash  = if (success && outputFile != null) FileManager.computeSha256(outputFile.readBytes()) else null

                    MeshLogger.файлПересборка(meta.fileName, success)

                    val complete = NetworkFileComplete(
                        transferId = msg.transferId,
                        senderId   = ownPeerId,
                        receiverId = meta.senderId,
                        success    = success,
                        error      = if (!success) "Assembly failed" else null,
                        finalHash  = finalHash
                    )
                    val payload = json.encodeToString(NetworkFileComplete.serializer(), complete).encodeToByteArray()
                    client.sendRaw(senderIp, PACKET_FILE_COMPLETE, payload)

                    if (success) {
                        val fileName = outputFile?.name ?: meta.fileName
                        chatRepository.addMessage(
                            FileMessage(
                                messageId    = System.currentTimeMillis(),
                                senderId     = meta.senderId,
                                receiverId   = ownPeerId,
                                timestamp    = meta.timestamp,
                                messageState = MessageState.MESSAGE_RECEIVED,
                                fileName     = fileName
                            )
                        )
                        contactRepository.addOrUpdateAccount(Account(meta.senderId, System.currentTimeMillis()))

                        if (!AppForegroundTracker.isInForeground()) {
                            val contact     = contactRepository.getAllContactsAsFlow().first().find { it.peerId == meta.senderId }
                            val senderName  = contact?.username ?: meta.senderId.take(8).uppercase()
                            NotificationHelper.showMessageNotification(context, senderName, "[файл] ${meta.fileName}", meta.senderId)
                        }
                    }
                    prefs.edit()
                        .remove("${transferKey}_meta")
                        .remove("${transferKey}_received")
                        .remove("${transferKey}_started")
                        .remove("${transferKey}_hash")
                        .apply()
                    fileManager.cleanupTransferChunks(msg.transferId)
                }
            } else {
                Log.e(TAG, "[FILE] Failed to save chunk ${msg.chunkIndex}")
                sendChunkAck(msg.transferId, msg.chunkIndex, actualHash, false, senderIp)
            }
        }
    }

    private suspend fun sendChunkAck(
        transferId: String,
        chunkIndex: Int,
        receivedHash: String,
        success: Boolean,
        senderIp: String
    ) {
        val ack     = NetworkFileChunkAck(transferId, chunkIndex, receivedHash, success)
        val payload = json.encodeToString(NetworkFileChunkAck.serializer(), ack).encodeToByteArray()
        client.sendRaw(senderIp, PACKET_FILE_CHUNK_ACK, payload)
    }

    private fun handleFileChunkAck(msg: NetworkFileChunkAck) {
        scope.launch {
            if (!msg.success) {
                Log.w(TAG, "[FILE] Chunk ${msg.chunkIndex} NACK, hash=${msg.receivedHash.take(8)}")
            } else {
                Log.d(TAG, "[FILE] Chunk ${msg.chunkIndex} ACK ✓")
            }
        }
    }

    private fun handleFileComplete(msg: NetworkFileComplete) {
        scope.launch {
            if (msg.success) {
                Log.i(TAG, "[FILE] Transfer ${msg.transferId.take(8)} completed successfully")
            } else {
                Log.w(TAG, "[FILE] Transfer ${msg.transferId.take(8)} failed: ${msg.error}")
            }
            fileManager.cleanupTransferChunks(msg.transferId)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // KEEPALIVE — обработка входящего
    // ────────────────────────────────────────────────────────────────────────

    private fun handleKeepalive(keepalive: NetworkKeepalive, senderIp: String) {
        val now     = System.currentTimeMillis()
        val ownIps  = getAllOwnIps()

        val fromPeerId: String = keepalive.senderPeerId?.takeIf { it.isNotBlank() }
            ?: keepalive.devices.firstOrNull { d ->
                d.peerId.isNotBlank() && d.peerId != ownPeerId &&
                        (d.ipAddress == senderIp || d.ipAddress.isNullOrBlank())
            }?.peerId
            ?: keepalive.devices.firstOrNull { d ->
                d.peerId.isNotBlank() && d.peerId != ownPeerId
            }?.peerId
            ?: ""

        if (fromPeerId == ownPeerId) {
            Log.v(TAG, "handleKeepalive: игнорируем keepalive от самих себя (self-echo)")
            return
        }

        for (device in keepalive.devices) {
            if (device.peerId.isBlank()) continue
            if (device.peerId == ownPeerId) continue

            val effectiveIp  = device.ipAddress?.takeIf { it.isNotBlank() } ?: senderIp
            if (effectiveIp in ownIps) continue

            val isDirectPeer = device.peerId == fromPeerId
            val hopCount     = if (isDirectPeer) 1 else (device.hopCount + 1).coerceAtMost(5)
            val viaPeer      = if (isDirectPeer) null else fromPeerId.takeIf { it.isNotBlank() }

            if (viaPeer == ownPeerId) continue

            val updated = device.copy(
                ipAddress = if (isDirectPeer) effectiveIp else senderIp,
                keepalive = now,
                hopCount  = hopCount,
                viaPeerId = viaPeer
            )
            peerRegistry.upsert(updated, "keepalive")

            if (isDirectPeer) {
                saveKnownIp(effectiveIp)
                scope.launch { requestProfileIfNeeded(device.peerId, effectiveIp) }
            } else {
                // ── ИСПРАВЛЕНО (баг #2а): mesh-пиры тоже регистрируются как контакты
                // и получают запрос профиля — через реле-узел (senderIp).
                scope.launch {
                    contactRepository.addOrUpdateAccount(Account(device.peerId, now))
                    requestProfileIfNeeded(device.peerId, senderIp)
                }
            }

            if (peerRegistry.isNewPeer(device.peerId) && isDirectPeer) {
                udpDiscovery.sendDirectAnnounce(effectiveIp)
            }
        }

        if (NativeCore.isInitialized() && fromPeerId.isNotBlank()) {
            val peers = keepalive.devices
                .filter { it.peerId != ownPeerId && it.peerId != fromPeerId && it.peerId.isNotBlank() }
                .map { d ->
                    org.json.JSONObject().apply {
                        put("peerId",    d.peerId)
                        put("ip",        d.ipAddress ?: "")
                        put("hops",      d.hopCount)
                        // Передаём имя и shortCode в Rust — он включит их в свою таблицу маршрутов
                        put("username",  d.username)
                        put("shortCode", d.shortCode)
                    }
                }
            val peersJson = org.json.JSONArray(peers).toString()
            NativeCore.updateRoutingTable(fromPeerId, senderIp, peersJson)
        }

        keepalive.routingTableJson?.let { rtJson ->
            if (rtJson.isNotBlank() && rtJson != "[]" && fromPeerId.isNotBlank()) {
                processRemoteRoutingTable(rtJson, senderIp, fromPeerId)
            }
        }
    }

    /**
     * Обрабатывает таблицу маршрутизации от удалённого пира.
     *
     * ИСПРАВЛЕНО (баг #2а):
     *  - Сохраняем username и shortCode из JSON (раньше всегда писали "").
     *  - Регистрируем каждый mesh-пир в contactRepository.
     *  - Запрашиваем профиль через реле-узел viaIp.
     */
    private fun processRemoteRoutingTable(rtJson: String, viaIp: String, viaPeerId: String) {
        try {
            val arr   = org.json.JSONArray(rtJson)
            val now   = System.currentTimeMillis()
            var added = 0

            for (i in 0 until arr.length()) {
                val obj       = arr.getJSONObject(i)
                val peerId    = obj.optString("peerId")
                val hops      = obj.optInt("hops", 1)
                // Берём имена из JSON — туда они попадают при отправке keepalive
                val username  = obj.optString("username", "")
                val shortCode = obj.optString("shortCode", "")

                if (peerId.isBlank() || peerId == ownPeerId || peerId == viaPeerId) continue

                if (hops < 5) {
                    val newHopCount = hops + 1
                    val existing    = peerRegistry.peers.value[peerId]

                    val shouldUpsert = existing == null ||
                            (existing.hopCount > newHopCount && existing.ipAddress.isNullOrBlank())

                    if (shouldUpsert) {
                        peerRegistry.upsert(
                            NetworkDevice(
                                peerId       = peerId,
                                // Используем имена из JSON; если у нас уже есть лучшее — оставляем своё
                                username     = existing?.username?.ifBlank { username } ?: username,
                                shortCode    = existing?.shortCode?.ifBlank { shortCode } ?: shortCode,
                                publicKeyHex = existing?.publicKeyHex ?: "",
                                ipAddress    = viaIp,
                                keepalive    = now,
                                hopCount     = newHopCount,
                                viaPeerId    = viaPeerId
                            ), "mesh-rt"
                        )
                        added++

                        // ИСПРАВЛЕНО: регистрируем контакт и запрашиваем профиль
                        scope.launch {
                            contactRepository.addOrUpdateAccount(Account(peerId, now))
                            // Профиль запрашиваем через viaIp — реле доставит пакет к нужному пиру
                            requestProfileIfNeeded(peerId, viaIp)
                        }
                    }
                }
            }
            if (added > 0) Log.d(TAG, "processRemoteRoutingTable: +$added mesh-маршрутов от ${viaPeerId.take(8)}")
        } catch (e: Exception) {
            Log.w(TAG, "processRemoteRoutingTable error: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // MESSAGE HANDLERS
    // ────────────────────────────────────────────────────────────────────────

    private fun handleProfileRequest(req: NetworkProfileRequest, senderIp: String) {
        if (req.senderId.isBlank()) return
        if (peerRegistry.peers.value[req.senderId] == null) {
            peerRegistry.upsert(
                NetworkDevice(
                    peerId       = req.senderId,
                    username     = "",
                    shortCode    = "",
                    publicKeyHex = "",
                    ipAddress    = senderIp,
                    keepalive    = System.currentTimeMillis()
                ), "profile-req"
            )
        }
        scope.launch { sendProfileResponse(req.senderId) }
    }

    private fun handleProfileResponse(res: NetworkProfileResponse) {
        scope.launch {
            Log.i(TAG, "[PROFILE] '${res.username}' from ${res.senderId.take(8)}")
            val imageFileName = res.imageBase64?.let { fileManager.saveNetworkProfileImage(res.senderId, it) }
            val newTimestamp  = System.currentTimeMillis()
            contactRepository.addOrUpdateProfile(
                Profile(
                    peerId          = res.senderId,
                    updateTimestamp = newTimestamp,
                    username        = res.username,
                    imageFileName   = imageFileName
                )
            )
            // Обновляем username в PeerRegistry, чтобы он сразу отображался в списке устройств
            val existing = peerRegistry.peers.value[res.senderId]
            if (existing != null) {
                peerRegistry.upsert(existing.copy(username = res.username), "profile-response")
            }
            synchronized(profileLock) {
                profileCache[res.senderId] = newTimestamp
                profileRequestInFlight.remove(res.senderId)
            }
        }
    }

    private fun handleTextMessage(msg: NetworkTextMessage) {
        if (!packetDeduplicator.shouldProcess(msg.senderId, msg.messageId.toString(), "TEXT")) {
            MeshLogger.сообщениеДублируется(msg.messageId)
            return
        }

        scope.launch {
            if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
                // ── ИСПРАВЛЕНО (баг #2б): убрано msg.copy(ttl - 1) здесь.
                // TTL уменьшается ОДИН РАЗ — внутри forwardTextMessage.
                if (msg.ttl > 0) {
                    val forwardKey = "${msg.senderId}:${msg.messageId}"
                    if (packetDeduplicator.markForwarded(ownPeerId, forwardKey)) {
                        Log.i(TAG, "[MESH] Forwarding text to ${msg.receiverId.take(8)} (ttl=${msg.ttl})")
                        forwardTextMessage(msg)   // ← передаём msg без изменений
                    }
                }
                return@launch
            }

            if (msg.senderId.isBlank()) {
                Log.w(TAG, "Ignoring text message with blank senderId")
                return@launch
            }
            notifyActivity()
            Log.i(TAG, "[MSG] ← '${msg.text.take(60)}' from ${msg.senderId.take(8)}")

            chatRepository.addMessage(
                TextMessage(
                    msg.messageId, msg.senderId, msg.receiverId,
                    msg.timestamp, MessageState.MESSAGE_RECEIVED, msg.text
                )
            )
            contactRepository.addOrUpdateAccount(Account(msg.senderId, System.currentTimeMillis()))
            sendAckReceived(msg.senderId, msg.messageId)

            if (!AppForegroundTracker.isInForeground()) {
                val contact    = contactRepository.getAllContactsAsFlow().first().find { it.peerId == msg.senderId }
                val senderName = contact?.username ?: msg.senderId.take(8).uppercase()
                NotificationHelper.showMessageNotification(context, senderName, msg.text, msg.senderId)
            }
        }
    }

    /**
     * ИСПРАВЛЕНО (баг #2б): TTL уменьшается строго один раз здесь.
     * Вызывающий код (handleTextMessage) передаёт оригинальный msg.
     */
    private suspend fun forwardTextMessage(msg: NetworkTextMessage) {
        if (msg.ttl <= 0) return
        val forwarded = msg.copy(ttl = msg.ttl - 1)
        val payload   = json.encodeToString(NetworkTextMessage.serializer(), forwarded).encodeToByteArray()
        MeshLogger.пакетРетранслируется("TEXT", msg.senderId, msg.receiverId, forwarded.ttl)
        sendPacket(forwarded.receiverId, PacketType.TEXT_MESSAGE, payload, "forward-text")
    }

    private fun handleFileMessage(msg: NetworkFileMessage) {
        if (!packetDeduplicator.shouldProcess(msg.senderId, msg.messageId.toString(), "FILE")) {
            MeshLogger.сообщениеДублируется(msg.messageId)
            return
        }
        scope.launch {
            if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
                // ИСПРАВЛЕНО: передаём msg без TTL-уменьшения — делается внутри forwardFileMessage
                if (msg.ttl > 0) forwardFileMessage(msg)
                return@launch
            }
            if (msg.senderId.isBlank()) return@launch
            notifyActivity()
            val fileName = fileManager.saveNetworkFile(msg.fileName, msg.fileBase64) ?: msg.fileName
            chatRepository.addMessage(
                FileMessage(msg.messageId, msg.senderId, msg.receiverId, msg.timestamp, MessageState.MESSAGE_RECEIVED, fileName)
            )
            contactRepository.addOrUpdateAccount(Account(msg.senderId, System.currentTimeMillis()))
            sendAckReceived(msg.senderId, msg.messageId)
            if (!AppForegroundTracker.isInForeground()) {
                val contact    = contactRepository.getAllContactsAsFlow().first().find { it.peerId == msg.senderId }
                val senderName = contact?.username ?: msg.senderId.take(8).uppercase()
                NotificationHelper.showMessageNotification(context, senderName, "[файл] ${msg.fileName}", msg.senderId)
            }
        }
    }

    private suspend fun forwardFileMessage(msg: NetworkFileMessage) {
        if (msg.ttl <= 0) return
        val forwarded = msg.copy(ttl = msg.ttl - 1)
        val payload   = json.encodeToString(NetworkFileMessage.serializer(), forwarded).encodeToByteArray()
        MeshLogger.пакетРетранслируется("FILE", msg.senderId, msg.receiverId, forwarded.ttl)
        sendPacket(forwarded.receiverId, PacketType.FILE_MESSAGE, payload, "forward-file")
    }

    private fun handleAudioMessage(msg: NetworkAudioMessage) {
        if (!packetDeduplicator.shouldProcess(msg.senderId, msg.messageId.toString(), "AUDIO")) {
            MeshLogger.сообщениеДублируется(msg.messageId)
            return
        }
        scope.launch {
            if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
                // ИСПРАВЛЕНО: TTL уменьшается один раз — в forwardAudioMessage
                if (msg.ttl > 0) forwardAudioMessage(msg)
                return@launch
            }
            if (msg.senderId.isBlank()) return@launch
            notifyActivity()
            val fileName = fileManager.saveNetworkAudio(msg.senderId, msg.timestamp, msg.audioBase64) ?: return@launch
            chatRepository.addMessage(
                AudioMessage(msg.messageId, msg.senderId, msg.receiverId, msg.timestamp, MessageState.MESSAGE_RECEIVED, fileName)
            )
            contactRepository.addOrUpdateAccount(Account(msg.senderId, System.currentTimeMillis()))
            sendAckReceived(msg.senderId, msg.messageId)
            if (!AppForegroundTracker.isInForeground()) {
                val contact    = contactRepository.getAllContactsAsFlow().first().find { it.peerId == msg.senderId }
                val senderName = contact?.username ?: msg.senderId.take(8).uppercase()
                NotificationHelper.showMessageNotification(context, senderName, "🎵 голосовое", msg.senderId)
            }
        }
    }

    private suspend fun forwardAudioMessage(msg: NetworkAudioMessage) {
        if (msg.ttl <= 0) return
        val forwarded = msg.copy(ttl = msg.ttl - 1)
        val payload   = json.encodeToString(NetworkAudioMessage.serializer(), forwarded).encodeToByteArray()
        MeshLogger.пакетРетранслируется("AUDIO", msg.senderId, msg.receiverId, forwarded.ttl)
        sendPacket(forwarded.receiverId, PacketType.AUDIO_MESSAGE, payload, "forward-audio")
    }

    private fun handleAckReceived(ack: NetworkMessageAck) {
        scope.launch { chatRepository.updateMessageState(ack.messageId, MessageState.MESSAGE_RECEIVED) }
    }

    private fun handleAckRead(ack: NetworkMessageAck) {
        scope.launch { chatRepository.updateMessageState(ack.messageId, MessageState.MESSAGE_READ) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // ROUTING & SENDING
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Определяет IP-адрес следующего хопа для отправки пакета к [peerId].
     *
     * ИСПРАВЛЕНО (баг #2в): прямой IP (directIp) используется ТОЛЬКО если пир
     * является прямым соседом (isDirect == true). Иначе ищем маршрут через
     * viaPeerId — реле-узел, который гарантированно является прямым соседом.
     *
     * Порядок приоритетов:
     *  1. Прямое соединение (hopCount == 1, isDirect)
     *  2. Rust NativeCore (если инициализирован)
     *  3. Mesh-маршрут через viaPeerId (ищем IP реле-узла)
     *  4. Bluetooth fallback
     */
    private fun resolveNextHop(peerId: String): Pair<String?, String?> {
        val device = peerRegistry.peers.value[peerId]

        // 1. Прямой сосед
        if (device?.isDirect == true) {
            val directIp = peerRegistry.getIp(peerId)
            if (directIp != null) {
                Log.v(TAG, "resolveNextHop: прямой маршрут к ${peerId.take(8)} @ $directIp")
                return Pair(directIp, null)
            }
        }

        // 2. Rust routing table
        if (NativeCore.isInitialized()) {
            val nextHopIp = NativeCore.getNextHopIp(peerId)
            if (nextHopIp.isNotBlank()) {
                Log.v(TAG, "resolveNextHop: Rust маршрут к ${peerId.take(8)} → $nextHopIp")
                return Pair(nextHopIp, null)
            }
        }

        // 3. Mesh-маршрут через viaPeerId
        if (device?.viaPeerId != null) {
            val relayDevice = peerRegistry.peers.value[device.viaPeerId]
            // Реле должен быть нашим прямым соседом
            val relayIp = when {
                relayDevice?.isDirect == true -> peerRegistry.getIp(device.viaPeerId)
                // Если реле тоже mesh-пир — идём на один уровень глубже
                relayDevice?.viaPeerId != null -> peerRegistry.getIp(relayDevice.viaPeerId)
                // Последняя попытка: берём IP из registry для viaPeerId
                else -> peerRegistry.getIp(device.viaPeerId)
            }
            if (relayIp != null) {
                Log.v(TAG, "resolveNextHop: mesh-маршрут к ${peerId.take(8)} через реле ${device.viaPeerId.take(8)} @ $relayIp")
                return Pair(relayIp, null)
            }
        }

        // 4. Bluetooth fallback
        val btAddress = peerRegistry.getBluetoothAddress(peerId)
        if (btAddress != null) {
            Log.v(TAG, "resolveNextHop: BT маршрут к ${peerId.take(8)} @ $btAddress")
            return Pair(null, btAddress)
        }

        Log.w(TAG, "resolveNextHop: нет маршрута к ${peerId.take(8)}")
        return Pair(null, null)
    }

    private suspend fun sendPacket(
        peerId: String,
        type: Int,
        payload: ByteArray,
        ctx: String
    ): Boolean {
        val (ip, btAddress) = resolveNextHop(peerId)

        if (ip != null) {
            try {
                client.sendRaw(ip, type, payload)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "$ctx: TCP to $ip failed: ${e.message}")
                removeKnownIp(ip)
            }
        }
        if (btAddress != null && bluetoothTransport.isAvailable()) {
            val ok = bluetoothTransport.sendPacket(btAddress, type, payload)
            if (ok) return true
        }
        if (ip == null && btAddress == null) {
            Log.w(TAG, "$ctx: no route to ${peerId.take(8)}")
        }
        return false
    }

    // ────────────────────────────────────────────────────────────────────────
    // PUBLIC API — SENDING MESSAGES
    // ────────────────────────────────────────────────────────────────────────

    fun sendTextMessage(peerId: String, text: String) {
        scope.launch {
            identityReady.await()
            notifyActivity()
            val ts  = System.currentTimeMillis()
            var msg = TextMessage(0, ownPeerId, peerId, ts, MessageState.MESSAGE_SENT, text)
            val id  = chatRepository.addMessage(msg)
            msg     = msg.copy(messageId = id)
            val netMsg  = NetworkTextMessage(msg.messageId, ownPeerId, peerId, ts, text, ttl = 5)
            val payload = json.encodeToString(NetworkTextMessage.serializer(), netMsg).encodeToByteArray()
            packetDeduplicator.markAsSent(ownPeerId, id.toString())
            sendPacket(peerId, PacketType.TEXT_MESSAGE, payload, "sendText")
            MeshLogger.сообщениеОтправлено("TEXT", peerId, id)
        }
    }

    fun sendFileMessage(peerId: String, fileUri: Uri) {
        scope.launch {
            identityReady.await()
            notifyActivity()
            val fileName = fileManager.saveMessageFile(fileUri) ?: return@launch
            val b64      = fileManager.getFileBase64(fileName) ?: return@launch
            var msg      = FileMessage(0, ownPeerId, peerId, System.currentTimeMillis(), MessageState.MESSAGE_SENT, fileName)
            val id       = chatRepository.addMessage(msg)
            msg          = msg.copy(messageId = id)
            val netMsg   = NetworkFileMessage(msg.messageId, ownPeerId, peerId, msg.timestamp, fileName, b64, ttl = 5)
            val payload  = json.encodeToString(NetworkFileMessage.serializer(), netMsg).encodeToByteArray()
            sendPacket(peerId, PacketType.FILE_MESSAGE, payload, "sendFile")
        }
    }

    fun sendFileChunked(peerId: String, fileUri: Uri) {
        scope.launch {
            identityReady.await()
            notifyActivity()

            val fileName = fileManager.saveMessageFile(fileUri) ?: return@launch
            val file     = fileManager.getFile(fileName)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "sendFileChunked: file empty or not found")
                return@launch
            }

            val fileSize    = file.length()
            val fileHash    = FileManager.computeSha256(file.readBytes())
            val chunkSize   = FileManager.CHUNK_SIZE
            val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
            val transferId  = java.util.UUID.randomUUID().toString()

            MeshLogger.файлОтправляется(fileName, peerId, fileSize)

            val initMsg = NetworkFileInit(
                transferId   = transferId,
                senderId     = ownPeerId,
                receiverId   = peerId,
                fileName     = fileName,
                fileSize     = fileSize,
                fileHash     = fileHash,
                chunkSize    = chunkSize,
                totalChunks  = totalChunks,
                mimeType     = context.contentResolver.getType(fileUri),
                timestamp    = System.currentTimeMillis(),
                ttl          = 5
            )
            val initPayload = json.encodeToString(NetworkFileInit.serializer(), initMsg).encodeToByteArray()
            if (!sendPacket(peerId, PACKET_FILE_INIT, initPayload, "send-file-init")) {
                Log.w(TAG, "sendFileChunked: failed to send init")
                MeshLogger.файлОшибкаОтправки(fileName, "init failed")
                return@launch
            }

            val chunks     = fileManager.chunkFile(file, chunkSize)
            var sentCount  = 0

            for ((index, pair) in chunks.withIndex()) {
                val (chunkData, chunkHash) = pair
                val chunkMsg = NetworkFileChunk(
                    transferId  = transferId,
                    chunkIndex  = index,
                    totalChunks = totalChunks,
                    chunkHash   = chunkHash,
                    data        = Base64.encodeToString(chunkData, Base64.NO_WRAP)
                )
                val chunkPayload = json.encodeToString(NetworkFileChunk.serializer(), chunkMsg).encodeToByteArray()

                if (sendPacket(peerId, PACKET_FILE_CHUNK, chunkPayload, "send-file-chunk")) {
                    sentCount++
                } else {
                    Log.w(TAG, "sendFileChunked: chunk $index failed, retrying...")
                    delay(300)
                    if (sendPacket(peerId, PACKET_FILE_CHUNK, chunkPayload, "send-file-chunk-retry")) {
                        sentCount++
                    }
                }
                // Rate limiting — не заваливаем канал, оставляем место для сообщений и звонков
                if (index % 5 == 0) delay(50)
            }

            Log.i(TAG, "sendFileChunked: sent $sentCount/$totalChunks chunks for '$fileName'")

            var msg = FileMessage(0, ownPeerId, peerId, System.currentTimeMillis(), MessageState.MESSAGE_SENT, fileName)
            val id  = chatRepository.addMessage(msg)
            msg     = msg.copy(messageId = id)

            val completeMsg     = NetworkFileComplete(transferId, ownPeerId, peerId, success = true)
            val completePayload = json.encodeToString(NetworkFileComplete.serializer(), completeMsg).encodeToByteArray()
            sendPacket(peerId, PACKET_FILE_COMPLETE, completePayload, "send-file-complete")

            MeshLogger.файлОтправлен(fileName, peerId)
        }
    }

    fun sendAudioMessage(peerId: String, audioFile: File) {
        scope.launch {
            identityReady.await()
            notifyActivity()
            val ts       = System.currentTimeMillis()
            val fileName = fileManager.saveMessageAudio(audioFile, peerId, ts) ?: return@launch
            val b64      = fileManager.getFileBase64(fileName) ?: return@launch
            var msg      = AudioMessage(0, ownPeerId, peerId, ts, MessageState.MESSAGE_SENT, fileName)
            val id       = chatRepository.addMessage(msg)
            msg          = msg.copy(messageId = id)
            val netMsg   = NetworkAudioMessage(msg.messageId, ownPeerId, peerId, ts, b64, ttl = 5)
            val payload  = json.encodeToString(NetworkAudioMessage.serializer(), netMsg).encodeToByteArray()
            sendPacket(peerId, PacketType.AUDIO_MESSAGE, payload, "sendAudio")
        }
    }

    fun sendMessageReadAck(peerId: String, messageId: Long) {
        val ip = peerRegistry.getIp(peerId) ?: return
        scope.launch { client.sendAckRead(ip, NetworkMessageAck(messageId, ownPeerId, peerId)) }
    }

    private suspend fun sendAckReceived(peerId: String, messageId: Long) {
        val ip = peerRegistry.getIp(peerId) ?: return
        client.sendAckReceived(ip, NetworkMessageAck(messageId, ownPeerId, peerId))
    }

    // ────────────────────────────────────────────────────────────────────────
    // CALL SIGNALING
    // ────────────────────────────────────────────────────────────────────────

    fun sendCallRequest(peerId: String) {
        val ip = peerRegistry.getIp(peerId) ?: run {
            Log.w(TAG, "sendCallRequest: no IP for ${peerId.take(8)}")
            return
        }
        notifyActivity()
        scope.launch {
            repeat(3) { attempt ->
                try {
                    client.sendCallRequest(ip, NetworkCallRequest(ownPeerId, peerId))
                    Log.d(TAG, "sendCallRequest OK (attempt ${attempt + 1}) → $ip")
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "sendCallRequest attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < 2) delay(500)
                }
            }
            Log.e(TAG, "sendCallRequest FAILED after 3 attempts → $ip")
        }
    }

    fun sendCallResponse(peerId: String, accepted: Boolean) {
        val ip = peerRegistry.getIp(peerId) ?: run {
            Log.w(TAG, "sendCallResponse: no IP for ${peerId.take(8)}")
            return
        }
        scope.launch {
            repeat(3) { attempt ->
                try {
                    client.sendCallResponse(ip, NetworkCallResponse(ownPeerId, peerId, accepted))
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "sendCallResponse attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < 2) delay(500)
                }
            }
        }
    }

    fun sendCallEnd(peerId: String) {
        val ip = peerRegistry.getIp(peerId) ?: run {
            Log.w(TAG, "sendCallEnd: no IP for ${peerId.take(8)}")
            return
        }
        scope.launch {
            repeat(2) { attempt ->
                try {
                    client.sendCallEnd(ip, NetworkCallEnd(ownPeerId, peerId))
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "sendCallEnd attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < 1) delay(300)
                }
            }
        }
    }

    fun sendCallFragment(peerId: String, audioBytes: ByteArray) {
        val ip = peerRegistry.getIp(peerId) ?: return
        scope.launch { client.sendCallAudio(ip, audioBytes) }
    }

    fun resetCallState() {
        _callRequest.value  = null
        _callResponse.value = null
        _callEnd.value      = null
        _callFragment.value = null
        NotificationHelper.dismissCallNotification(context)
    }

    // ────────────────────────────────────────────────────────────────────────
    // PROFILE & DISCOVERY
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun requestProfileIfNeeded(peerId: String, ip: String) {
        if (peerId == ownPeerId || peerId.isBlank()) return

        val contact = try {
            contactRepository.getAllContactsAsFlow().first().find { it.peerId == peerId }
        } catch (_: Exception) { null }

        val localTimestamp = contact?.account?.profileUpdateTimestamp ?: 0L

        val shouldRequest = synchronized(profileLock) {
            if (peerId in profileRequestInFlight) {
                false
            } else {
                val cachedTimestamp = profileCache[peerId] ?: -1L
                if (cachedTimestamp < 0L || localTimestamp > cachedTimestamp) {
                    profileRequestInFlight.add(peerId)
                    true
                } else {
                    false
                }
            }
        }
        if (!shouldRequest) return

        identityReady.await()
        Log.d(TAG, "[PROFILE] Requesting from ${peerId.take(8)} @ $ip")
        try {
            client.sendProfileRequest(ip, NetworkProfileRequest(ownPeerId, peerId))
        } catch (e: Exception) {
            Log.w(TAG, "[PROFILE] Request failed to ${peerId.take(8)}: ${e.message}")
            synchronized(profileLock) { profileRequestInFlight.remove(peerId) }
        }
    }

    private suspend fun sendProfileResponse(peerId: String) {
        val ip = peerRegistry.getIp(peerId) ?: run {
            Log.w(TAG, "sendProfileResponse: no IP for ${peerId.take(8)}")
            return
        }
        identityReady.await()
        try {
            val profile     = ownProfileRepository.getProfile()
            val imageBase64 = profile.imageFileName?.let { fileManager.getFileBase64(it) }
            client.sendProfileResponse(
                ip, NetworkProfileResponse(
                    senderId    = ownPeerId,
                    receiverId  = peerId,
                    username    = profile.username.ifBlank { ownUsername },
                    imageBase64 = imageBase64
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "sendProfileResponse error: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // DISCOVERY LOOPS
    // ────────────────────────────────────────────────────────────────────────

    fun startDiscoverPeersHandler() {
        scope.launch {
            while (isActive) {
                if (!discoveryPaused) receiver.discoverPeers()
                delay(6_000)
            }
        }
        Log.i(TAG, "WiFi Direct discovery loop started")
    }

    fun startSendKeepaliveHandler() {}
    fun startUpdateConnectedDevicesHandler() {}

    /**
     * Останавливает UDP broadcast и Wi-Fi Direct discovery.
     * Вызывается из WiFiDirectService когда экран гаснет.
     * TCP-сервер продолжает работать — входящие пакеты принимаются.
     */
    fun pauseDiscovery() {
        if (discoveryPaused) return
        discoveryPaused = true
        keepaliveJob?.cancel()
        keepaliveJob = null
        Log.i(TAG, "Discovery paused (screen off) — TCP server still running")
    }

    /**
     * Возобновляет UDP broadcast и keepalive.
     * Вызывается из WiFiDirectService когда экран включается.
     */
    fun resumeDiscovery() {
        if (!discoveryPaused) return
        discoveryPaused = false
        startKeepaliveLoop()
        // Немедленный keepalive чтобы быстро синхронизировать таблицы маршрутов
        scope.launch { sendKeepalive() }
        Log.i(TAG, "Discovery resumed (screen on)")
    }

    private fun initNsdDiscovery() {
        nsdDiscovery = NsdDiscovery(
            context     = context,
            onPeerFound = { ip: String, port: Int, peerId: String, username: String ->
                if (peerId != ownPeerId && peerId.isNotBlank()) {
                    Log.i(TAG, "[NSD] Found '$username' @ $ip:$port")
                    peerRegistry.upsert(
                        NetworkDevice(
                            peerId       = peerId, username = username,
                            shortCode    = peerId.take(4).uppercase(),
                            publicKeyHex = "", ipAddress = ip,
                            keepalive    = System.currentTimeMillis(), hopCount = 1
                        ), "nsd"
                    )
                    saveKnownIp(ip)
                    scope.launch { requestProfileIfNeeded(peerId, ip) }
                    udpDiscovery.sendDirectAnnounce(ip)
                }
            },
            onPeerLost = { peerId: String -> Log.d(TAG, "[NSD] Peer lost: ${peerId.take(8)}") }
        )
        scope.launch {
            delay(2_000)
            val shortCode = if (NativeCore.isInitialized()) NativeCore.getOwnShortCode() else ownPeerId.take(4).uppercase()
            nsdDiscovery?.start(ownPeerId, ownUsername, shortCode, TCP_PORT)
        }
    }

    private fun warmupProfileCache() {
        scope.launch {
            try {
                contactRepository.getAllContactsAsFlow().first().forEach { contact ->
                    if (contact.profile != null) {
                        synchronized(profileLock) { profileCache[contact.peerId] = contact.profile.updateTimestamp }
                    }
                }
                Log.d(TAG, "Profile cache warmed: ${profileCache.size} entries")
            } catch (_: Exception) {}
        }
    }

    private fun observeUdpPeers() {
        scope.launch {
            Log.d(TAG, "[UDP] observeUdpPeers started, ownPeerId=${ownPeerId.take(8)}")
            udpDiscovery.peers.collect { udpPeers ->
                val myId = ownPeerId
                for ((peerId, discovered) in udpPeers) {
                    if (myId.isNotBlank() && peerId == myId) continue
                    if (peerId.isBlank()) continue
                    peerRegistry.upsert(
                        NetworkDevice(
                            peerId       = peerId,
                            username     = discovered.announce.username,
                            shortCode    = discovered.announce.shortCode,
                            publicKeyHex = discovered.announce.publicKeyHex,
                            ipAddress    = discovered.ip,
                            keepalive    = System.currentTimeMillis(),
                            hopCount     = 1
                        ), "udp"
                    )
                    saveKnownIp(discovered.ip)
                    scope.launch { requestProfileIfNeeded(peerId, discovered.ip) }
                }
            }
        }
    }

    private fun observeWifiDirectGroup() {
        scope.launch {
            combine(receiver.isGroupOwner, receiver.groupOwnerAddress) { isOwner: Boolean, ownerIp: String? ->
                Pair(isOwner, ownerIp)
            }.collectLatest { (isOwner: Boolean, ownerIp: String?) ->
                val newIp = if (isOwner) WiFiDirectBroadcastReceiver.GROUP_OWNER_IP
                else getP2pClientIp() ?: getLanIp()
                if (newIp != null && newIp != ownIpAddress) {
                    ownIpAddress = newIp
                    Log.i(TAG, "[P2P] Own IP: $newIp (isOwner=$isOwner)")
                    udpDiscovery.updateOwnIp(newIp)
                    udpDiscovery.updateOwnIdentity(
                        peerId       = ownPeerId,
                        username     = ownUsername,
                        shortCode    = if (NativeCore.isInitialized()) NativeCore.getOwnShortCode() else ownPeerId.take(4).uppercase(),
                        publicKeyHex = if (NativeCore.isInitialized()) NativeCore.getOwnPublicKeyHex() else ""
                    )
                }
                if (!isOwner && ownerIp != null && ownerIp != ownIpAddress) {
                    Log.i(TAG, "[P2P] Connected to GO @ $ownerIp — sending direct announce (x3)")
                    saveKnownIp(ownerIp)
                    udpDiscovery.sendDirectAnnounce(ownerIp)
                    scope.launch {
                        delay(1_000); udpDiscovery.sendDirectAnnounce(ownerIp)
                        delay(2_000); udpDiscovery.sendDirectAnnounce(ownerIp)
                    }
                }
            }
        }
    }

    private fun startPeriodicRediscovery() {
        scope.launch {
            while (isActive) {
                delay(60_000)
                if (discoveryPaused) continue
                val ownIp = getCurrentOwnIp()
                if (ownIp != null) udpDiscovery.updateOwnIp(ownIp)
                receiver.discoverPeers()
                getKnownIps().forEach { udpDiscovery.sendDirectAnnounce(it) }
            }
        }
    }

    fun forceRediscover() {
        scope.launch {
            Log.i(TAG, "Force rediscover triggered")
            receiver.discoverPeers()
            getCurrentOwnIp()?.let { udpDiscovery.updateOwnIp(it) }
            val allIps = (getKnownIps() + peerRegistry.peers.value.values.mapNotNull { it.ipAddress })
                .filter { it.isNotBlank() }.toSet()
            allIps.forEach { udpDiscovery.sendDirectAnnounce(it) }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // KEEPALIVE — ОТПРАВКА (адаптивный интервал)
    // ────────────────────────────────────────────────────────────────────────

    private fun startKeepaliveLoop() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            delay(1_000)
            while (isActive) {
                if (!discoveryPaused) sendKeepalive()
                delay(adaptiveKeepaliveInterval())
            }
        }
    }

    /**
     * Адаптивный интервал keepalive:
     *  - До 1 мин неактивности  → 7 сек  (активный режим)
     *  - 1–5 мин неактивности   → 30 сек (средний режим)
     *  - Более 5 мин            → 60 сек (режим сна)
     *
     * «Активность» — отправка/получение сообщений, звонки.
     * Это существенно снижает расход батареи без потери связи.
     */
    private fun adaptiveKeepaliveInterval(): Long {
        val idleMs = System.currentTimeMillis() - lastActivityMs
        return when {
            idleMs < 60_000L    -> KEEPALIVE_ACTIVE_MS
            idleMs < 300_000L   -> KEEPALIVE_IDLE_MS
            else                -> KEEPALIVE_SLEEP_MS
        }
    }

    /** Фиксирует момент последней сетевой активности — сбрасывает adaptive backoff. */
    private fun notifyActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    private suspend fun sendKeepalive() {
        if (ownPeerId.isBlank()) return
        try {
            val currentIp = getCurrentOwnIp()
            if (currentIp != null) {
                ownIpAddress = currentIp
                udpDiscovery.updateOwnIp(currentIp)
            }
            val profile  = ownProfileRepository.getProfile()
            val username = profile.username.ifBlank { ownUsername }

            val ownDevice = NetworkDevice(
                peerId       = ownPeerId,
                username     = username,
                shortCode    = if (NativeCore.isInitialized()) NativeCore.getOwnShortCode() else ownPeerId.take(4).uppercase(),
                publicKeyHex = if (NativeCore.isInitialized()) NativeCore.getOwnPublicKeyHex() else "",
                ipAddress    = currentIp,
                keepalive    = System.currentTimeMillis(),
                hopCount     = 1
            )

            // ИСПРАВЛЕНО: включаем всех пиров (не только с непустым shortCode),
            // но ограничиваем hopCount <= 4 чтобы не раздувать пакет.
            // Mesh-пиры с hopCount=5 не ретранслируются — они конечные.
            val knownPeers = peerRegistry.peers.value.values
                .filter { it.peerId != ownPeerId && it.hopCount <= 4 }

            val routingTableJson = if (NativeCore.isInitialized()) NativeCore.getRoutingTableJson() else null

            val keepalive = NetworkKeepalive(
                devices          = listOf(ownDevice) + knownPeers,
                senderPeerId     = ownPeerId,
                routingTableJson = routingTableJson
            )

            val targets = buildSet<String> {
                if (!receiver.isGroupOwner.value) add(WiFiDirectBroadcastReceiver.GROUP_OWNER_IP)
                receiver.groupOwnerAddress.value?.let { if (it != currentIp) add(it) }
                knownPeers.mapNotNull { it.ipAddress }.forEach { add(it) }
            }.filter { it != currentIp && it.isNotBlank() }

            for (ip in targets) {
                val localIp = client.sendKeepaliveReturnLocalIp(ip, keepalive)
                if (localIp != null && localIp != currentIp && localIp != "0.0.0.0") {
                    ownIpAddress = localIp
                    udpDiscovery.updateOwnIp(localIp)
                }
                saveKnownIp(ip)
            }
            MeshLogger.keepaliveОтправлен(targets.size)
        } catch (e: Exception) {
            Log.w(TAG, "sendKeepalive error: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // UTILS
    // ────────────────────────────────────────────────────────────────────────

    fun getIpForPeer(peerId: String): String? = peerRegistry.getIp(peerId)

    fun removePeerFromRegistry(peerId: String) {
        peerRegistry.remove(peerId)
        if (NativeCore.isInitialized()) NativeCore.removeRoute(peerId)
        synchronized(profileLock) {
            profileCache.remove(peerId)
            profileRequestInFlight.remove(peerId)
        }
        Log.d(TAG, "Removed peer ${peerId.take(8)} from registry")
    }

    private fun saveKnownIp(ip: String) {
        if (ip.isBlank()) return
        val current = prefs.getStringSet(PREFS_KEY_KNOWN_IPS, mutableSetOf()) ?: mutableSetOf()
        val updated = (current + ip).take(MAX_KNOWN_IPS).toMutableSet()
        prefs.edit().putStringSet(PREFS_KEY_KNOWN_IPS, updated).apply()
    }

    private fun getKnownIps(): Set<String> =
        prefs.getStringSet(PREFS_KEY_KNOWN_IPS, emptySet()) ?: emptySet()

    private fun removeKnownIp(ip: String) {
        val current = prefs.getStringSet(PREFS_KEY_KNOWN_IPS, mutableSetOf()) ?: mutableSetOf()
        prefs.edit().putStringSet(PREFS_KEY_KNOWN_IPS, (current - ip).toMutableSet()).apply()
    }

    private suspend fun reconnectKnownPeers() {
        val knownIps = getKnownIps()
        if (knownIps.isEmpty()) return
        Log.i(TAG, "Reconnecting to ${knownIps.size} known peer(s)")
        knownIps.forEach { udpDiscovery.sendDirectAnnounce(it) }
    }

    private fun getCurrentOwnIp(): String? {
        if (receiver.isGroupOwner.value) return WiFiDirectBroadcastReceiver.GROUP_OWNER_IP
        return getP2pClientIp() ?: getLanIp()
    }

    private fun getP2pClientIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.map { it.hostAddress ?: "" }
            ?.firstOrNull { it.startsWith("192.168.49.") && it != WiFiDirectBroadcastReceiver.GROUP_OWNER_IP }
    } catch (_: Exception) { null }

    private fun getLanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.map { it.hostAddress ?: "" }
            ?.firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") && !it.startsWith("192.168.49.") }
    } catch (_: Exception) { null }

    private fun getAllOwnIps(): List<String> {
        val ips = mutableListOf<String>()
        if (receiver.isGroupOwner.value) ips.add(WiFiDirectBroadcastReceiver.GROUP_OWNER_IP)
        getP2pClientIp()?.let { ips.add(it) }
        getLanIp()?.let { if (it !in ips) ips.add(it) }
        return ips
    }

    fun stop() {
        keepaliveJob?.cancel()
        nsdDiscovery?.stop()
        server.stop()
        udpDiscovery.stop()
        peerRegistry.stop()
        bluetoothTransport.stopServer()
        bleDiscovery.stop()
        scope.cancel()
        Log.i(TAG, "NetworkManager stopped")
    }
}

// ── File Transfer State ──────────────────────────────────────────────────────

data class FileTransferState(
    val transferId: String,
    val fileName: String,
    val totalChunks: Int,
    val receivedChunks: MutableSet<Int> = mutableSetOf(),
    val status: TransferStatus = TransferStatus.IN_PROGRESS,
    val startTime: Long = System.currentTimeMillis()
)

enum class TransferStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}