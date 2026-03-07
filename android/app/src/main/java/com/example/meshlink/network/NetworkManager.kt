package com.example.meshlink.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.example.meshlink.AppForegroundTracker
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
import com.example.meshlink.network.bluetooth.BluetoothTransport
import com.example.meshlink.network.discovery.NsdDiscovery
import com.example.meshlink.network.discovery.PeerRegistry
import com.example.meshlink.network.discovery.UdpDiscovery
import com.example.meshlink.network.protocol.PacketType
import com.example.meshlink.network.protocol.json
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
        private const val PREFS_NAME = "meshlink_peers"
        private const val PREFS_KEY_KNOWN_IPS = "known_ips"
        private const val MAX_KNOWN_IPS = 20

        // Chunked file transfer packet types
        const val PACKET_FILE_INIT = 100
        const val PACKET_FILE_CHUNK = 101
        const val PACKET_FILE_CHUNK_ACK = 102
        const val PACKET_FILE_RETRY = 103
        const val PACKET_FILE_COMPLETE = 104
        const val PACKET_FILE_STATUS_REQ = 105
        const val PACKET_FILE_STATUS_RESP = 106
        const val PACKET_FILE_CANCEL = 107
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var ownPeerId: String = ""
        private set
    @Volatile private var ownUsername: String = ""
    @Volatile private var ownIpAddress: String? = null
    @Volatile private var started = false

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
    private val _callRequest = MutableStateFlow<NetworkCallRequest?>(null)
    val callRequest: StateFlow<NetworkCallRequest?> = _callRequest
    private val _callResponse = MutableStateFlow<NetworkCallResponse?>(null)
    val callResponse: StateFlow<NetworkCallResponse?> = _callResponse
    private val _callEnd = MutableStateFlow<NetworkCallEnd?>(null)
    val callEnd: StateFlow<NetworkCallEnd?> = _callEnd
    private val _callFragment = MutableStateFlow<ByteArray?>(null)
    val callFragment: StateFlow<ByteArray?> = _callFragment

    // File transfer state
    private val activeTransfers = mutableMapOf<String, FileTransferState>()
    private val transferLock = Any()

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
            reconnectKnownPeers()
            initNsdDiscovery()
            observeUdpPeers()
            observeWifiDirectGroup()
            startKeepaliveLoop()
            warmupProfileCache()
            startPeriodicRediscovery()
        }
    }

    private suspend fun initIdentity() {
        try {
            val profile = ownProfileRepository.getProfile()
            ownUsername = profile.username.ifBlank { Build.MODEL }
            val filesDir = context.filesDir.absolutePath
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
                val rustPeerId = NativeCore.getOwnPeerIdHex()
                val rustShortCode = NativeCore.getOwnShortCode()
                val rustPubKey = NativeCore.getOwnPublicKeyHex()

                if (rustPeerId.isNotBlank()) {
                    ownPeerId = rustPeerId
                    ownAccountRepository.setPeerId(ownPeerId)
                    ownProfileRepository.setPeerId(ownPeerId)
                    Log.i(TAG, "✓ Core ONLINE: sc=$rustShortCode id=${ownPeerId.take(16)}...")
                    udpDiscovery.updateOwnIdentity(
                        peerId = ownPeerId,
                        username = ownUsername,
                        shortCode = rustShortCode,
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
            peerId = ownPeerId,
            username = ownUsername,
            shortCode = ownPeerId.take(4).uppercase(),
            publicKeyHex = ""
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // SERVER HANDLERS SETUP
    // ──────────────────────────────────────────────────────────────────────
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
        server.onTextMessage = { msg: NetworkTextMessage -> handleTextMessage(msg) }
        server.onFileMessage = { msg: NetworkFileMessage -> handleFileMessage(msg) }
        server.onAudioMessage = { msg: NetworkAudioMessage -> handleAudioMessage(msg) }
        server.onAckReceived = { ack: NetworkMessageAck -> handleAckReceived(ack) }
        server.onAckRead = { ack: NetworkMessageAck -> handleAckRead(ack) }

        // Chunked file transfer handlers
        server.onFileInit = { msg: NetworkFileInit -> handleFileInit(msg) }
        server.onFileChunk = { msg: NetworkFileChunk, senderIp: String -> handleFileChunk(msg, senderIp) }
        server.onFileChunkAck = { msg: NetworkFileChunkAck -> handleFileChunkAck(msg) }
        server.onFileComplete = { msg: NetworkFileComplete -> handleFileComplete(msg) }

        // Call Signaling
        server.onCallRequest = { req: NetworkCallRequest ->
            _callRequest.value = req
            if (!AppForegroundTracker.isInForeground()) {
                scope.launch {
                    val contact = contactRepository.getAllContactsAsFlow().first()
                        .find { it.peerId == req.senderId }
                    val callerName = contact?.username ?: req.senderId.take(8).uppercase()
                    NotificationHelper.showCallNotification(context, callerName, req.senderId)
                }
            }
        }
        server.onCallResponse = { res: NetworkCallResponse -> _callResponse.value = res }
        server.onCallEnd = { end: NetworkCallEnd ->
            _callEnd.value = end
            NotificationHelper.dismissCallNotification(context)
        }
        server.onCallAudio = { bytes: ByteArray, _: String -> _callFragment.value = bytes }
    }

    // ──────────────────────────────────────────────────────────────────────
    // PACKET DISPATCH (Bluetooth fallback)
    // ──────────────────────────────────────────────────────────────────────
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
                PacketType.TEXT_MESSAGE -> handleTextMessage(
                    json.decodeFromString<NetworkTextMessage>(payload.decodeToString())
                )
                PacketType.FILE_MESSAGE -> handleFileMessage(
                    json.decodeFromString<NetworkFileMessage>(payload.decodeToString())
                )
                PacketType.AUDIO_MESSAGE -> handleAudioMessage(
                    json.decodeFromString<NetworkAudioMessage>(payload.decodeToString())
                )
                PacketType.PROFILE_REQUEST -> scope.launch {
                    identityReady.await()
                    handleProfileRequest(
                        json.decodeFromString<NetworkProfileRequest>(payload.decodeToString()),
                        senderAddress
                    )
                }
                PacketType.PROFILE_RESPONSE -> handleProfileResponse(
                    json.decodeFromString<NetworkProfileResponse>(payload.decodeToString())
                )
                PacketType.CALL_REQUEST -> _callRequest.value =
                    json.decodeFromString<NetworkCallRequest>(payload.decodeToString())
                PacketType.CALL_RESPONSE -> _callResponse.value =
                    json.decodeFromString<NetworkCallResponse>(payload.decodeToString())
                PacketType.CALL_END -> {
                    _callEnd.value = json.decodeFromString<NetworkCallEnd>(payload.decodeToString())
                    NotificationHelper.dismissCallNotification(context)
                }
                PacketType.CALL_AUDIO -> _callFragment.value = payload

                // Chunked file transfer over Bluetooth
                PACKET_FILE_INIT -> handleFileInit(
                    json.decodeFromString<NetworkFileInit>(payload.decodeToString())
                )
                PACKET_FILE_CHUNK -> handleFileChunk(
                    json.decodeFromString<NetworkFileChunk>(payload.decodeToString()),
                    senderAddress
                )
                PACKET_FILE_CHUNK_ACK -> handleFileChunkAck(
                    json.decodeFromString<NetworkFileChunkAck>(payload.decodeToString())
                )
                PACKET_FILE_COMPLETE -> handleFileComplete(
                    json.decodeFromString<NetworkFileComplete>(payload.decodeToString())
                )

                PacketType.ACK_RECEIVED -> handleAckReceived(
                    json.decodeFromString<NetworkMessageAck>(payload.decodeToString())
                )
                PacketType.ACK_READ -> handleAckRead(
                    json.decodeFromString<NetworkMessageAck>(payload.decodeToString())
                )
                else -> Log.d(TAG, "BT unknown packet type=$type from $senderAddress")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BT dispatch error type=$type: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // CHUNKED FILE TRANSFER — INCOMING
    // ──────────────────────────────────────────────────────────────────────

    private fun handleFileInit(msg: NetworkFileInit) {
        if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
            // Mesh forwarding
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

            // Save transfer metadata
            val transferKey = "transfer_in_${msg.transferId}"
            prefs.edit()
                .putString("${transferKey}_meta", json.encodeToString(msg))
                .putLong("${transferKey}_started", System.currentTimeMillis())
                .putInt("${transferKey}_total", msg.totalChunks)
                .putString("${transferKey}_hash", msg.fileHash)
                .apply()

            // Send status response to sender
            val senderIp = peerRegistry.getIp(msg.senderId)
            if (senderIp != null) {
                val status = NetworkFileStatusResponse(
                    transferId = msg.transferId,
                    totalChunks = msg.totalChunks,
                    receivedChunks = emptyList(),
                    canResume = false
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

            // Decode and verify chunk
            val chunkData = try {
                Base64.decode(msg.data, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "[FILE] Base64 decode failed for chunk ${msg.chunkIndex}")
                sendChunkAck(msg.transferId, msg.chunkIndex, "", false, senderIp)
                return@launch
            }

            val actualHash = FileManager.computeSha256(chunkData)
            if (actualHash != msg.chunkHash) {
                Log.w(TAG, "[FILE] Chunk ${msg.chunkIndex} hash mismatch! expected=${msg.chunkHash.take(8)} got=${actualHash.take(8)}")
                sendChunkAck(msg.transferId, msg.chunkIndex, actualHash, false, senderIp)
                return@launch
            }

            // Save chunk via FileManager
            val saved = fileManager.saveChunkForAssembly(
                transferId = msg.transferId,
                chunkIndex = msg.chunkIndex,
                chunkData = chunkData,
                expectedHash = msg.chunkHash
            )

            if (saved) {
                // Update progress
                val received = prefs.getStringSet("${transferKey}_received", emptySet())?.toMutableSet() ?: mutableSetOf()
                received.add(msg.chunkIndex.toString())
                prefs.edit().putStringSet("${transferKey}_received", received).apply()

                Log.d(TAG, "[FILE] Chunk ${msg.chunkIndex}/${msg.totalChunks} saved ✓")
                sendChunkAck(msg.transferId, msg.chunkIndex, actualHash, true, senderIp)

                // Check if transfer is complete
                if (received.size == meta.totalChunks) {
                    Log.i(TAG, "[FILE] All chunks received, assembling...")
                    val outputFile = fileManager.assembleFile(
                        transferId = msg.transferId,
                        outputFileName = meta.fileName,
                        expectedFileHash = meta.fileHash
                    )

                    val success = outputFile?.exists() == true
                    val finalHash = if (success && outputFile != null) {
                        FileManager.computeSha256(outputFile.readBytes())
                    } else {
                        null
                    }

                    // Notify sender
                    val complete = NetworkFileComplete(
                        transferId = msg.transferId,
                        senderId = ownPeerId,
                        receiverId = meta.senderId,
                        success = success,
                        error = if (!success) "Assembly failed" else null,
                        finalHash = finalHash
                    )
                    val payload = json.encodeToString(NetworkFileComplete.serializer(), complete).encodeToByteArray()
                    client.sendRaw(senderIp, PACKET_FILE_COMPLETE, payload)

                    if (success) {
                        // Add to chat
                        val fileName = outputFile?.name ?: meta.fileName
                        chatRepository.addMessage(
                            FileMessage(
                                messageId = System.currentTimeMillis(),
                                senderId = meta.senderId,
                                receiverId = ownPeerId,
                                timestamp = meta.timestamp,
                                messageState = MessageState.MESSAGE_RECEIVED,
                                fileName = fileName
                            )
                        )
                        contactRepository.addOrUpdateAccount(Account(meta.senderId, System.currentTimeMillis()))

                        if (!AppForegroundTracker.isInForeground()) {
                            val contact = contactRepository.getAllContactsAsFlow().first()
                                .find { it.peerId == meta.senderId }
                            val senderName = contact?.username ?: meta.senderId.take(8).uppercase()
                            NotificationHelper.showMessageNotification(
                                context = context,
                                senderName = senderName,
                                text = "[файл] ${meta.fileName}",
                                peerId = meta.senderId
                            )
                        }
                    }

                    // Cleanup
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
        val ack = NetworkFileChunkAck(
            transferId = transferId,
            chunkIndex = chunkIndex,
            receivedHash = receivedHash,
            success = success
        )
        val payload = json.encodeToString(NetworkFileChunkAck.serializer(), ack).encodeToByteArray()
        client.sendRaw(senderIp, PACKET_FILE_CHUNK_ACK, payload)
    }

    private fun handleFileChunkAck(msg: NetworkFileChunkAck) {
        scope.launch {
            if (!msg.success) {
                Log.w(TAG, "[FILE] Chunk ${msg.chunkIndex} NACK, hash=${msg.receivedHash.take(8)}")
                // TODO: Implement retry logic
            } else {
                Log.d(TAG, "[FILE] Chunk ${msg.chunkIndex} ACK ✓")
                // Update UI progress if needed
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
            // Cleanup outgoing transfer state
            fileManager.cleanupTransferChunks(msg.transferId)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // EXISTING MESSAGE HANDLERS (unchanged except for mesh forwarding)
    // ──────────────────────────────────────────────────────────────────────

    private fun handleKeepalive(keepalive: NetworkKeepalive, senderIp: String) {
        val now = System.currentTimeMillis()
        val ownIps = getAllOwnIps()

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
            if (device.peerId == ownPeerId) {
                Log.v(TAG, "handleKeepalive: пропускаем собственный peerId в чужом keepalive")
                continue
            }
            val effectiveIp = device.ipAddress?.takeIf { it.isNotBlank() } ?: senderIp
            if (effectiveIp in ownIps || senderIp in ownIps) {
                Log.v(TAG, "handleKeepalive: пропускаем устройство с нашим IP ($effectiveIp / $senderIp)")
                continue
            }
            val isDirectPeer = device.peerId == fromPeerId
            val hopCount = if (isDirectPeer) 1 else (device.hopCount + 1).coerceAtMost(5)
            val viaPeer = if (isDirectPeer) null else fromPeerId.takeIf { it.isNotBlank() }
            if (viaPeer == ownPeerId) {
                Log.v(TAG, "handleKeepalive: пропускаем маршрут через себя к ${device.peerId.take(8)}")
                continue
            }
            val updated = device.copy(
                ipAddress = if (isDirectPeer) effectiveIp else senderIp,
                keepalive = now,
                hopCount = hopCount,
                viaPeerId = viaPeer
            )
            peerRegistry.upsert(updated, "keepalive")
            if (isDirectPeer) {
                saveKnownIp(effectiveIp)
                scope.launch { requestProfileIfNeeded(device.peerId, effectiveIp) }
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
                        put("peerId", d.peerId)
                        put("ip", d.ipAddress ?: "")
                        put("hops", d.hopCount)
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

    private fun processRemoteRoutingTable(rtJson: String, viaIp: String, viaPeerId: String) {
        try {
            val arr = org.json.JSONArray(rtJson)
            val now = System.currentTimeMillis()
            var added = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val peerId = obj.optString("peerId")
                val hops = obj.optInt("hops", 1)
                if (peerId.isBlank()) continue
                if (peerId == ownPeerId) {
                    Log.v(TAG, "processRemoteRoutingTable: пропускаем собственный peerId")
                    continue
                }
                if (peerId == viaPeerId) continue
                if (hops < 5) {
                    val existing = peerRegistry.peers.value[peerId]
                    if (existing == null) {
                        peerRegistry.upsert(
                            NetworkDevice(
                                peerId = peerId, username = "", shortCode = "",
                                publicKeyHex = "", ipAddress = viaIp, keepalive = now,
                                hopCount = hops + 1, viaPeerId = viaPeerId
                            ), "mesh-rt"
                        )
                        added++
                    }
                }
            }
            if (added > 0) Log.d(TAG, "processRemoteRoutingTable: добавлено $added mesh-маршрутов от ${viaPeerId.take(8)}")
        } catch (e: Exception) {
            Log.w(TAG, "processRemoteRoutingTable error: ${e.message}")
        }
    }

    private fun handleProfileRequest(req: NetworkProfileRequest, senderIp: String) {
        if (req.senderId.isBlank()) return
        val existing = peerRegistry.peers.value[req.senderId]
        if (existing == null) {
            peerRegistry.upsert(
                NetworkDevice(
                    peerId = req.senderId, username = "", shortCode = "",
                    publicKeyHex = "", ipAddress = senderIp,
                    keepalive = System.currentTimeMillis()
                ), "profile-req"
            )
        }
        scope.launch { sendProfileResponse(req.senderId) }
    }

    private fun handleProfileResponse(res: NetworkProfileResponse) {
        scope.launch {
            Log.i(TAG, "[PROFILE] '${res.username}' from ${res.senderId.take(8)}")
            val imageFileName = res.imageBase64?.let {
                fileManager.saveNetworkProfileImage(res.senderId, it)
            }
            val newTimestamp = System.currentTimeMillis()
            contactRepository.addOrUpdateProfile(
                Profile(
                    peerId = res.senderId,
                    updateTimestamp = newTimestamp,
                    username = res.username,
                    imageFileName = imageFileName
                )
            )
            synchronized(profileLock) {
                profileCache[res.senderId] = newTimestamp
                profileRequestInFlight.remove(res.senderId)
            }
        }
    }

    private fun handleTextMessage(msg: NetworkTextMessage) {
        scope.launch {
            if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
                if (msg.ttl > 0) {
                    Log.i(TAG, "[MESH] Forwarding text to ${msg.receiverId.take(8)} (ttl=${msg.ttl})")
                    forwardTextMessage(msg)
                }
                return@launch
            }
            if (msg.senderId.isBlank()) {
                Log.w(TAG, "Ignoring text message with blank senderId")
                return@launch
            }
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
                val contact = contactRepository.getAllContactsAsFlow().first()
                    .find { it.peerId == msg.senderId }
                val senderName = contact?.username ?: msg.senderId.take(8).uppercase()
                NotificationHelper.showMessageNotification(
                    context = context,
                    senderName = senderName,
                    text = msg.text,
                    peerId = msg.senderId
                )
            }
        }
    }

    private suspend fun forwardTextMessage(msg: NetworkTextMessage) {
        val forwarded = msg.copy(ttl = msg.ttl - 1)
        val payload = json.encodeToString(NetworkTextMessage.serializer(), forwarded).encodeToByteArray()
        sendPacket(msg.receiverId, PacketType.TEXT_MESSAGE, payload, "forward-text")
    }

    private fun handleFileMessage(msg: NetworkFileMessage) {
        scope.launch {
            if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
                if (msg.ttl > 0) forwardFileMessage(msg)
                return@launch
            }
            if (msg.senderId.isBlank()) return@launch
            val fileName = fileManager.saveNetworkFile(msg.fileName, msg.fileBase64) ?: msg.fileName
            chatRepository.addMessage(
                FileMessage(
                    msg.messageId, msg.senderId, msg.receiverId,
                    msg.timestamp, MessageState.MESSAGE_RECEIVED, fileName
                )
            )
            contactRepository.addOrUpdateAccount(Account(msg.senderId, System.currentTimeMillis()))
            sendAckReceived(msg.senderId, msg.messageId)
            if (!AppForegroundTracker.isInForeground()) {
                val contact = contactRepository.getAllContactsAsFlow().first()
                    .find { it.peerId == msg.senderId }
                val senderName = contact?.username ?: msg.senderId.take(8).uppercase()
                NotificationHelper.showMessageNotification(
                    context = context,
                    senderName = senderName,
                    text = "[файл] ${msg.fileName}",
                    peerId = msg.senderId
                )
            }
        }
    }

    private suspend fun forwardFileMessage(msg: NetworkFileMessage) {
        val forwarded = msg.copy(ttl = msg.ttl - 1)
        val payload = json.encodeToString(NetworkFileMessage.serializer(), forwarded).encodeToByteArray()
        sendPacket(msg.receiverId, PacketType.FILE_MESSAGE, payload, "forward-file")
    }

    private fun handleAudioMessage(msg: NetworkAudioMessage) {
        scope.launch {
            if (msg.receiverId != ownPeerId && msg.receiverId.isNotBlank()) {
                if (msg.ttl > 0) sendPacket(
                    msg.receiverId, PacketType.AUDIO_MESSAGE,
                    json.encodeToString(NetworkAudioMessage.serializer(), msg.copy(ttl = msg.ttl - 1)).encodeToByteArray(),
                    "forward-audio"
                )
                return@launch
            }
            if (msg.senderId.isBlank()) return@launch
            val fileName = fileManager.saveNetworkAudio(msg.senderId, msg.timestamp, msg.audioBase64) ?: return@launch
            chatRepository.addMessage(
                AudioMessage(
                    msg.messageId, msg.senderId, msg.receiverId,
                    msg.timestamp, MessageState.MESSAGE_RECEIVED, fileName
                )
            )
            contactRepository.addOrUpdateAccount(Account(msg.senderId, System.currentTimeMillis()))
            sendAckReceived(msg.senderId, msg.messageId)
            if (!AppForegroundTracker.isInForeground()) {
                val contact = contactRepository.getAllContactsAsFlow().first()
                    .find { it.peerId == msg.senderId }
                val senderName = contact?.username ?: msg.senderId.take(8).uppercase()
                NotificationHelper.showMessageNotification(
                    context = context,
                    senderName = senderName,
                    text = "🎵 голосовое сообщение",
                    peerId = msg.senderId
                )
            }
        }
    }

    private fun handleAckReceived(ack: NetworkMessageAck) {
        scope.launch { chatRepository.updateMessageState(ack.messageId, MessageState.MESSAGE_RECEIVED) }
    }

    private fun handleAckRead(ack: NetworkMessageAck) {
        scope.launch { chatRepository.updateMessageState(ack.messageId, MessageState.MESSAGE_READ) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ROUTING & SENDING
    // ──────────────────────────────────────────────────────────────────────

    private fun resolveNextHop(peerId: String): Pair<String?, String?> {
        val directIp = peerRegistry.getIp(peerId)
        val device = peerRegistry.peers.value[peerId]

        if (directIp != null && (device?.isDirect == true || device?.hopCount == 1)) {
            return Pair(directIp, null)
        }
        if (NativeCore.isInitialized()) {
            val nextHopIp = NativeCore.getNextHopIp(peerId)
            if (nextHopIp.isNotBlank()) {
                return Pair(nextHopIp, null)
            }
        }
        if (device?.viaPeerId != null) {
            val viaIp = peerRegistry.getIp(device.viaPeerId)
            if (viaIp != null) {
                return Pair(viaIp, null)
            }
        }
        if (directIp != null) return Pair(directIp, null)
        return Pair(null, peerRegistry.getBluetoothAddress(peerId))
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

    // ──────────────────────────────────────────────────────────────────────
    // PUBLIC API — SENDING MESSAGES
    // ──────────────────────────────────────────────────────────────────────

    fun sendTextMessage(peerId: String, text: String) {
        scope.launch {
            identityReady.await()
            val ts = System.currentTimeMillis()
            var msg = TextMessage(0, ownPeerId, peerId, ts, MessageState.MESSAGE_SENT, text)
            val id = chatRepository.addMessage(msg)
            msg = msg.copy(messageId = id)
            val netMsg = NetworkTextMessage(msg.messageId, ownPeerId, peerId, ts, text, ttl = 5)
            val payload = json.encodeToString(NetworkTextMessage.serializer(), netMsg).encodeToByteArray()
            sendPacket(peerId, PacketType.TEXT_MESSAGE, payload, "sendText")
        }
    }

    // Legacy Base64 file transfer (for small files)
    fun sendFileMessage(peerId: String, fileUri: Uri) {
        scope.launch {
            identityReady.await()
            val fileName = fileManager.saveMessageFile(fileUri) ?: return@launch
            val b64 = fileManager.getFileBase64(fileName) ?: return@launch
            var msg = FileMessage(0, ownPeerId, peerId, System.currentTimeMillis(), MessageState.MESSAGE_SENT, fileName)
            val id = chatRepository.addMessage(msg)
            msg = msg.copy(messageId = id)
            val netMsg = NetworkFileMessage(msg.messageId, ownPeerId, peerId, msg.timestamp, fileName, b64, ttl = 5)
            val payload = json.encodeToString(NetworkFileMessage.serializer(), netMsg).encodeToByteArray()
            sendPacket(peerId, PacketType.FILE_MESSAGE, payload, "sendFile")
        }
    }

    // NEW: Chunked file transfer for large files
    fun sendFileChunked(peerId: String, fileUri: Uri) {
        scope.launch {
            identityReady.await()

            val fileName = fileManager.saveMessageFile(fileUri) ?: return@launch
            val file = fileManager.getFile(fileName)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "sendFileChunked: file empty or not found")
                return@launch
            }

            val fileSize = file.length()
            val fileHash = FileManager.computeSha256(file.readBytes())
            val chunkSize = FileManager.CHUNK_SIZE
            val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

            val transferId = java.util.UUID.randomUUID().toString()
            val initMsg = NetworkFileInit(
                transferId = transferId,
                senderId = ownPeerId,
                receiverId = peerId,
                fileName = fileName,
                fileSize = fileSize,
                fileHash = fileHash,
                chunkSize = chunkSize,
                totalChunks = totalChunks,
                mimeType = context.contentResolver.getType(fileUri),
                timestamp = System.currentTimeMillis(),
                ttl = 5
            )

            // Send init
            val initPayload = json.encodeToString(NetworkFileInit.serializer(), initMsg).encodeToByteArray()
            if (!sendPacket(peerId, PACKET_FILE_INIT, initPayload, "send-file-init")) {
                Log.w(TAG, "sendFileChunked: failed to send init")
                return@launch
            }

            // Chunk and send
            val chunks = fileManager.chunkFile(file, chunkSize)
            var sentCount = 0

            for ((index, pair) in chunks.withIndex()) {
                val (chunkData, chunkHash) = pair
                val chunkMsg = NetworkFileChunk(
                    transferId = transferId,
                    chunkIndex = index,
                    totalChunks = totalChunks,
                    chunkHash = chunkHash,
                    data = Base64.encodeToString(chunkData, Base64.NO_WRAP)
                )

                val chunkPayload = json.encodeToString(NetworkFileChunk.serializer(), chunkMsg).encodeToByteArray()
                if (sendPacket(peerId, PACKET_FILE_CHUNK, chunkPayload, "send-file-chunk")) {
                    sentCount++
                    if (index % 5 == 0) delay(50) // Rate limiting
                } else {
                    Log.w(TAG, "sendFileChunked: chunk $index failed, retrying...")
                    if (sendPacket(peerId, PACKET_FILE_CHUNK, chunkPayload, "send-file-chunk-retry")) {
                        sentCount++
                    }
                }
            }

            Log.i(TAG, "sendFileChunked: sent $sentCount/$totalChunks chunks for '$fileName'")

            // Add optimistic message to chat
            var msg = FileMessage(0, ownPeerId, peerId, System.currentTimeMillis(), MessageState.MESSAGE_SENT, fileName)
            val id = chatRepository.addMessage(msg)
            msg = msg.copy(messageId = id)

            // Send complete
            val completeMsg = NetworkFileComplete(
                transferId = transferId,
                senderId = ownPeerId,
                receiverId = peerId,
                success = true
            )
            val completePayload = json.encodeToString(NetworkFileComplete.serializer(), completeMsg).encodeToByteArray()
            sendPacket(peerId, PACKET_FILE_COMPLETE, completePayload, "send-file-complete")
        }
    }

    fun sendAudioMessage(peerId: String, audioFile: File) {
        scope.launch {
            identityReady.await()
            val ts = System.currentTimeMillis()
            val fileName = fileManager.saveMessageAudio(audioFile, peerId, ts) ?: return@launch
            val b64 = fileManager.getFileBase64(fileName) ?: return@launch
            var msg = AudioMessage(0, ownPeerId, peerId, ts, MessageState.MESSAGE_SENT, fileName)
            val id = chatRepository.addMessage(msg)
            msg = msg.copy(messageId = id)
            val netMsg = NetworkAudioMessage(msg.messageId, ownPeerId, peerId, ts, b64, ttl = 5)
            val payload = json.encodeToString(NetworkAudioMessage.serializer(), netMsg).encodeToByteArray()
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

    // ──────────────────────────────────────────────────────────────────────
    // CALL SIGNALING
    // ──────────────────────────────────────────────────────────────────────

    fun sendCallRequest(peerId: String) {
        val ip = peerRegistry.getIp(peerId) ?: run {
            Log.w(TAG, "sendCallRequest: no IP for ${peerId.take(8)}")
            return
        }
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
                    Log.d(TAG, "sendCallResponse accepted=$accepted OK (attempt ${attempt + 1}) → $ip")
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
        _callRequest.value = null
        _callResponse.value = null
        _callEnd.value = null
        _callFragment.value = null
        NotificationHelper.dismissCallNotification(context)
    }

    // ──────────────────────────────────────────────────────────────────────
    // PROFILE & DISCOVERY HELPERS
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun requestProfileIfNeeded(peerId: String, ip: String) {
        if (peerId == ownPeerId || peerId.isBlank()) return
        val contact = try {
            contactRepository.getAllContactsAsFlow().first().find { it.peerId == peerId }
        } catch (e: Exception) {
            null
        }
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
            val profile = ownProfileRepository.getProfile()
            val imageBase64 = profile.imageFileName?.let { fileManager.getFileBase64(it) }
            client.sendProfileResponse(
                ip, NetworkProfileResponse(
                    senderId = ownPeerId,
                    receiverId = peerId,
                    username = profile.username.ifBlank { ownUsername },
                    imageBase64 = imageBase64
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "sendProfileResponse error: ${e.message}")
        }
    }

    fun startDiscoverPeersHandler() {
        scope.launch {
            while (isActive) {
                receiver.discoverPeers()
                delay(6_000)
            }
        }
        Log.i(TAG, "WiFi Direct discovery loop started")
    }

    fun startSendKeepaliveHandler() {}
    fun startUpdateConnectedDevicesHandler() {}

    private fun initNsdDiscovery() {
        nsdDiscovery = NsdDiscovery(
            context = context,
            onPeerFound = { ip: String, port: Int, peerId: String, username: String ->
                if (peerId != ownPeerId && peerId.isNotBlank()) {
                    Log.i(TAG, "[NSD] Found '$username' @ $ip:$port")
                    val device = NetworkDevice(
                        peerId = peerId, username = username,
                        shortCode = peerId.take(4).uppercase(),
                        publicKeyHex = "", ipAddress = ip,
                        keepalive = System.currentTimeMillis(), hopCount = 1
                    )
                    peerRegistry.upsert(device, "nsd")
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
                        synchronized(profileLock) {
                            profileCache[contact.peerId] = contact.profile.updateTimestamp
                        }
                    }
                }
                Log.d(TAG, "Profile cache warmed: ${profileCache.size} entries")
            } catch (_: Exception) {}
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // UTILS & CLEANUP
    // ──────────────────────────────────────────────────────────────────────

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
        for (ip in knownIps) {
            udpDiscovery.sendDirectAnnounce(ip)
        }
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

    private fun observeUdpPeers() {
        scope.launch {
            Log.d(TAG, "[UDP] observeUdpPeers started, ownPeerId=${ownPeerId.take(8)}")
            udpDiscovery.peers.collect { udpPeers ->
                val myId = ownPeerId
                for ((peerId, discovered) in udpPeers) {
                    if (myId.isNotBlank() && peerId == myId) continue
                    if (peerId.isBlank()) continue
                    val device = NetworkDevice(
                        peerId = peerId,
                        username = discovered.announce.username,
                        shortCode = discovered.announce.shortCode,
                        publicKeyHex = discovered.announce.publicKeyHex,
                        ipAddress = discovered.ip,
                        keepalive = System.currentTimeMillis(),
                        hopCount = 1
                    )
                    peerRegistry.upsert(device, "udp")
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
                        peerId = ownPeerId,
                        username = ownUsername,
                        shortCode = if (NativeCore.isInitialized()) NativeCore.getOwnShortCode() else ownPeerId.take(4).uppercase(),
                        publicKeyHex = if (NativeCore.isInitialized()) NativeCore.getOwnPublicKeyHex() else ""
                    )
                }
                if (!isOwner && ownerIp != null && ownerIp != ownIpAddress) {
                    Log.i(TAG, "[P2P] Connected to GO @ $ownerIp — sending direct announce (x3)")
                    saveKnownIp(ownerIp)
                    udpDiscovery.sendDirectAnnounce(ownerIp)
                    scope.launch {
                        delay(1_000)
                        udpDiscovery.sendDirectAnnounce(ownerIp)
                        delay(2_000)
                        udpDiscovery.sendDirectAnnounce(ownerIp)
                    }
                }
            }
        }
    }

    private fun startPeriodicRediscovery() {
        scope.launch {
            while (isActive) {
                delay(60_000)
                val ownIp = getCurrentOwnIp()
                if (ownIp != null) udpDiscovery.updateOwnIp(ownIp)
                receiver.discoverPeers()
                val allKnownIps = getKnownIps()
                for (ip in allKnownIps) {
                    udpDiscovery.sendDirectAnnounce(ip)
                }
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
            for (ip in allIps) udpDiscovery.sendDirectAnnounce(ip)
        }
    }

    private fun startKeepaliveLoop() {
        scope.launch {
            delay(1_000)
            while (isActive) {
                sendKeepalive()
                delay(KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendKeepalive() {
        if (ownPeerId.isBlank()) return
        try {
            val currentIp = getCurrentOwnIp()
            if (currentIp != null) {
                ownIpAddress = currentIp
                udpDiscovery.updateOwnIp(currentIp)
            }
            val profile = ownProfileRepository.getProfile()
            val username = profile.username.ifBlank { ownUsername }
            val ownDevice = NetworkDevice(
                peerId = ownPeerId,
                username = username,
                shortCode = if (NativeCore.isInitialized()) NativeCore.getOwnShortCode() else ownPeerId.take(4).uppercase(),
                publicKeyHex = if (NativeCore.isInitialized()) NativeCore.getOwnPublicKeyHex() else "",
                ipAddress = currentIp,
                keepalive = System.currentTimeMillis(),
                hopCount = 1
            )
            val knownPeers = peerRegistry.peers.value.values
                .filter { it.shortCode.isNotBlank() && it.peerId != ownPeerId }
            val routingTableJson = if (NativeCore.isInitialized()) NativeCore.getRoutingTableJson() else null
            val keepalive = NetworkKeepalive(
                devices = listOf(ownDevice) + knownPeers,
                senderPeerId = ownPeerId,
                routingTableJson = routingTableJson
            )
            val targets = buildSet<String> {
                if (!receiver.isGroupOwner.value) {
                    add(WiFiDirectBroadcastReceiver.GROUP_OWNER_IP)
                }
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
        } catch (e: Exception) {
            Log.w(TAG, "sendKeepalive error: ${e.message}")
        }
    }

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

    fun stop() {
        nsdDiscovery?.stop()
        server.stop()
        udpDiscovery.stop()
        peerRegistry.stop()
        bluetoothTransport.stopServer()
        scope.cancel()
        Log.i(TAG, "NetworkManager stopped")
    }
}

// ──────────────────────────────────────────────────────────────────────
// FILE TRANSFER STATE MODEL
// ──────────────────────────────────────────────────────────────────────
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