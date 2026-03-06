package com.example.meshlink.network.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.meshlink.network.protocol.PacketType
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

class BluetoothTransport(
    private val context: Context,
    private val onPacketReceived: (type: Int, payload: ByteArray, senderAddress: String) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothTransport"

        val SERVICE_UUID: UUID = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")
        private const val SERVICE_NAME = "MeshLink"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var isRunning = false


    private val connections = mutableMapOf<String, BluetoothSocket>()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    fun isAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled && hasPermissions()
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun getLocalAddress(): String? {
        if (!isAvailable()) return null
        return try {
            bluetoothAdapter?.address
        } catch (e: Exception) {
            null
        }
    }



    fun startServer() {
        if (!isAvailable()) {
            Log.i(TAG, "Bluetooth unavailable, server not started")
            return
        }
        if (isRunning) return
        isRunning = true

        scope.launch {
            var retries = 0
            while (isActive && isRunning) {
                try {
                    val adapter = bluetoothAdapter ?: break
                    val ss = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                    serverSocket = ss
                    Log.i(TAG, "BT RFCOMM server listening ✓ (uuid=$SERVICE_UUID)")
                    retries = 0

                    while (isActive) {
                        try {
                            val socket = ss.accept()
                            val address = socket.remoteDevice.address
                            Log.i(TAG, "BT connection from $address")
                            launch { handleConnection(socket) }
                        } catch (e: IOException) {
                            if (!isActive) break
                            Log.w(TAG, "BT accept error: ${e.message}")
                            break // Перезапустим сервер
                        }
                    }

                    ss.close()
                } catch (e: Exception) {
                    if (!isActive) break
                    retries++
                    val delay = minOf(5_000L * retries, 60_000L)
                    Log.w(TAG, "BT server start failed (retry $retries): ${e.message} — retry in ${delay}ms")
                    delay(delay)
                }
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        val address = socket.remoteDevice.address
        try {
            socket.use {

                connections[address] = socket
                val dis = DataInputStream(socket.inputStream)

                while (true) {
                    val type = dis.readInt()
                    val length = dis.readInt()
                    if (length < 0 || length > 10 * 1024 * 1024) {
                        Log.w(TAG, "BT bad packet length=$length from $address")
                        break
                    }
                    val payload = ByteArray(length)
                    dis.readFully(payload)
                    onPacketReceived(type, payload, address)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "BT connection closed: $address — ${e.message}")
        } finally {
            connections.remove(address)
        }
    }



    suspend fun sendPacket(btAddress: String, type: Int, payload: ByteArray): Boolean {
        if (!isAvailable()) return false

        return withContext(Dispatchers.IO) {
            try {
                val existing = connections[btAddress]
                if (existing != null && existing.isConnected) {
                    return@withContext writePacket(existing, type, payload)
                }

                val adapter = bluetoothAdapter ?: return@withContext false
                val device = adapter.getRemoteDevice(btAddress)

                if (adapter.isDiscovering) adapter.cancelDiscovery()

                val socket = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    device.createRfcommSocketToServiceRecord(SERVICE_UUID).also { it.connect() }
                } ?: run {
                    Log.w(TAG, "BT connect timeout to $btAddress")
                    return@withContext false
                }

                Log.i(TAG, "BT connected to $btAddress ✓")
                connections[btAddress] = socket
                scope.launch { handleConnection(socket) }

                writePacket(socket, type, payload)
            } catch (e: Exception) {
                Log.w(TAG, "BT connect/send to $btAddress failed: ${e.message}")
                connections.remove(btAddress)
                false
            }
        }
    }

    private fun writePacket(socket: BluetoothSocket, type: Int, payload: ByteArray): Boolean {
        return try {
            val dos = DataOutputStream(socket.outputStream)
            dos.writeInt(type)
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
            true
        } catch (e: Exception) {
            Log.d(TAG, "BT write failed: ${e.message}")
            false
        }
    }



    fun getKnownMeshDevices(): List<Pair<String, String>> {
        if (!isAvailable()) return emptyList()
        return try {
            bluetoothAdapter?.bondedDevices?.map {
                Pair(it.address, it.name ?: "Unknown")
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun stopServer() {
        isRunning = false
        scope.cancel()
        runCatching { serverSocket?.close() }
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        Log.i(TAG, "BT server stopped")
    }
}