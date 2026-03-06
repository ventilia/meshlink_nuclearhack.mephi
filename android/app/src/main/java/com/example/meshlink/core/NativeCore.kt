package com.example.meshlink.core

object NativeCore {
    init {
        System.loadLibrary("meshlink_core")
    }

    external fun initWithFilesDir(filesDir: String, deviceName: String): Boolean


    external fun generateOwnPeer(deviceName: String): Boolean

    external fun isInitialized(): Boolean


    external fun getOwnShortCode(): String

    external fun getOwnPeerIdHex(): String

    external fun getOwnPublicKeyHex(): String


    external fun getOwnName(): String


    external fun getVersion(): String


    external fun signData(data: ByteArray): ByteArray

    external fun verifySignature(publicKeyHex: String, data: ByteArray, signature: ByteArray): Boolean


    external fun updateRoutingTable(fromPeerId: String, fromIp: String, peersJson: String): Int


    external fun getNextHopIp(peerId: String): String

    external fun getRoutingTableJson(): String


    external fun getRouteCount(): Int

    external fun removeRoute(peerId: String)



    external fun startServer(port: Int): Boolean
    external fun connectToPeer(peerId: String, address: String): Boolean
    external fun sendTextMessage(peerId: String, content: String): Boolean
    external fun disconnect(peerId: String)
    external fun shutdown()
    external fun setMessageCallback(callback: MessageCallback)

    const val DEFAULT_PORT = 42420
}

interface MessageCallback {
    fun onMessageReceived(peerId: String, content: String)
}
