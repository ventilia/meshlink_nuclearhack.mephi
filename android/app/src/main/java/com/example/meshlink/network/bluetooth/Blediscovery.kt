package com.example.meshlink.network.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BleDiscovery — обнаружение пиров через BLE GATT.
 *
 * Каждое устройство одновременно рекламирует себя (Advertiser)
 * и сканирует соседей (Scanner). При обнаружении MeshLink-сервиса
 * устанавливает GATT-соединение и читает характеристику анонса (38 байт):
 *   [0..31]  — peerId hex (32 байта UTF-8)
 *   [32..35] — shortCode  (4 байта  UTF-8)
 *   [36..37] — TCP-порт   (uint16, Little-Endian)
 *
 * Защита от SecurityException:
 *   1. Перед каждой BLE-операцией вызывается hasPermissions() — явная проверка.
 *   2. Каждый BLE-вызов обёрнут в try/catch(SecurityException) — перехват
 *      runtime-отказа если разрешение отозвано в процессе работы.
 *   3. @SuppressLint("MissingPermission") на класс — подавляет lint, т.к.
 *      проверка выполняется явно через checkSelfPermission, а не через аннотации.
 */
@SuppressLint("MissingPermission")
class BleDiscovery(
    private val context: Context,
    private val onPeerFound: (peerId: String, shortCode: String, btAddress: String, tcpPort: Int) -> Unit,
    private val onPeerLost: (btAddress: String) -> Unit
) {
    companion object {
        private const val TAG = "МешЛинк/BLE"

        val SERVICE_UUID: UUID        = UUID.fromString("12345678-1234-5678-1234-56789abc0000")
        val CHAR_ANNOUNCE_UUID: UUID  = UUID.fromString("12345678-1234-5678-1234-56789abc0001")
        val DESCRIPTOR_CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_PERIOD_MS        = 12_000L
        private const val SCAN_PAUSE_MS         =  4_000L
        private const val GATT_TIMEOUT_MS       =  8_000L
        private const val PEER_REDISCOVER_MS    = 60_000L
        private const val ANNOUNCE_PAYLOAD_SIZE = 38
    }

    private val bluetoothManager  = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var isRunning = false

    private var advertiser:        BluetoothLeAdvertiser?       = null
    private var advertiseCallback: AdvertiseCallback?           = null
    private var scanCallback:      ScanCallback?                = null
    private var gattServer:        BluetoothGattServer?         = null
    private var announceChar:      BluetoothGattCharacteristic? = null

    private val activeGatts         = mutableMapOf<String, BluetoothGatt>()
    private val discoveredAddresses = mutableSetOf<String>()

    @Volatile private var ownPeerId:    String = ""
    @Volatile private var ownShortCode: String = ""
    @Volatile private var tcpPort:      Int    = 8800

    // ═══════════════════════════════════════════════════════════════════════
    // Проверка разрешений — вызывается ЯВНО перед каждым BLE-вызовом
    // ═══════════════════════════════════════════════════════════════════════

    fun isAvailable(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        return hasPermissions()
    }

    fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)   == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)      == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH)            == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    // ═══════════════════════════════════════════════════════════════════════
    // Запуск / Остановка
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Запускает BLE.
     * ВАЖНО: peerId должен быть непустым — вызывать только после identityReady.await()
     * в NetworkManager. Иначе анонс будет содержать нулевой peerId.
     */
    fun start(peerId: String, shortCode: String, port: Int) {
        if (!isAvailable()) {
            Log.i(TAG, "BLE недоступен (разрешения=${hasPermissions()}, bt=${bluetoothAdapter?.isEnabled})")
            return
        }
        if (isRunning) return
        if (peerId.isBlank()) {
            Log.e(TAG, "start() вызван с пустым peerId — отклонено!")
            return
        }
        ownPeerId    = peerId
        ownShortCode = shortCode
        tcpPort      = port
        isRunning    = true
        Log.i(TAG, "BLE запускается | peerId=${peerId.take(8)}... код=$shortCode порт=$port")
        startGattServer()
        startAdvertising()
        startScanLoop()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        stopAdvertising()
        stopScanning()
        closeGattServer()
        synchronized(activeGatts) {
            activeGatts.values.forEach { gatt ->
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close()      } catch (_: Exception) {}
            }
            activeGatts.clear()
        }
        scope.cancel()
        Log.i(TAG, "BLE discovery остановлен")
    }

    fun updateOwnInfo(peerId: String, shortCode: String, port: Int) {
        if (peerId.isBlank()) return
        ownPeerId    = peerId
        ownShortCode = shortCode
        tcpPort      = port
        if (hasPermissions()) {
            announceChar?.value = buildAnnouncePayload()
        }
        Log.d(TAG, "BLE данные обновлены: peerId=${peerId.take(8)}...")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GATT-сервер (мы как Peripheral — отвечаем на чтение характеристики)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startGattServer() {
        if (!hasPermissions()) { Log.w(TAG, "startGattServer: нет разрешений"); return }
        val manager = bluetoothManager ?: return

        val characteristic = BluetoothGattCharacteristic(
            CHAR_ANNOUNCE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                DESCRIPTOR_CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }
        announceChar = characteristic

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

        val serverCallback = object : BluetoothGattServerCallback() {

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val state = if (newState == BluetoothProfile.STATE_CONNECTED) "подключился" else "отключился"
                Log.d(TAG, "GATT-клиент $state: ${device.address}")
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (!hasPermissions()) {
                    Log.w(TAG, "onCharacteristicReadRequest: разрешения отозваны")
                    return
                }
                val responseData = if (characteristic.uuid == CHAR_ANNOUNCE_UUID)
                    buildAnnouncePayload() else null
                val gattStatus = if (responseData != null)
                    BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                try {
                    gattServer?.sendResponse(device, requestId, gattStatus, offset, responseData)
                    if (responseData != null) Log.d(TAG, "Анонс отправлен ${device.address}")
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException sendResponse: ${e.message}")
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray
            ) {
                if (!responseNeeded) return
                if (!hasPermissions()) return
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException sendResponse(descriptor): ${e.message}")
                }
            }
        }

        try {
            gattServer = manager.openGattServer(context, serverCallback)
            gattServer?.addService(service)
            Log.i(TAG, "GATT-сервер запущен")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException openGattServer: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка openGattServer: ${e.message}")
        }
    }

    private fun closeGattServer() {
        if (!hasPermissions()) { gattServer = null; return }
        try { gattServer?.close() }
        catch (e: SecurityException) { Log.w(TAG, "SecurityException closeGattServer: ${e.message}") }
        catch (e: Exception)         { Log.d(TAG, "closeGattServer: ${e.message}") }
        gattServer = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLE Advertiser (рекламируем себя)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startAdvertising() {
        if (!hasPermissions()) { Log.w(TAG, "startAdvertising: нет разрешений"); return }
        val adv = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "bluetoothLeAdvertiser недоступен — устройство не поддерживает BLE Peripheral")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "BLE реклама запущена ✓")
            }
            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED      -> "уже запущена"
                    ADVERTISE_FAILED_DATA_TOO_LARGE       -> "данные слишком большие"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED  -> "не поддерживается устройством"
                    ADVERTISE_FAILED_INTERNAL_ERROR       -> "внутренняя ошибка"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "слишком много advertiser'ов"
                    else                                  -> "код=$errorCode"
                }
                Log.w(TAG, "BLE реклама не запустилась: $reason")
            }
        }
        advertiseCallback = cb

        try {
            adv.startAdvertising(settings, data, cb)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException startAdvertising: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка startAdvertising: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        if (!hasPermissions()) { advertiser = null; return }
        try { advertiser?.stopAdvertising(cb) }
        catch (e: SecurityException) { Log.d(TAG, "SecurityException stopAdvertising: ${e.message}") }
        catch (e: Exception)         { Log.d(TAG, "stopAdvertising: ${e.message}") }
        advertiser = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLE Scanner (сканируем соседей)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startScanLoop() {
        scope.launch {
            while (isActive && isRunning) {
                if (hasPermissions()) performScan()
                else Log.w(TAG, "scanLoop: разрешения недоступны")
                delay(SCAN_PAUSE_MS)
            }
        }
    }

    private suspend fun performScan() {
        if (!hasPermissions()) return
        val leScanner: BluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { handleScanResult(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { handleScanResult(it) } }
            override fun onScanFailed(errorCode: Int) { Log.w(TAG, "BLE scan error: $errorCode") }
        }
        scanCallback = cb

        try {
            leScanner.startScan(filters, settings, cb)
            Log.d(TAG, "BLE сканирование запущено")
            delay(SCAN_PERIOD_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException startScan: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка startScan: ${e.message}")
        } finally {
            stopScanning()
        }
    }

    private fun stopScanning() {
        val cb = scanCallback ?: return
        scanCallback = null
        if (!hasPermissions()) return
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb) }
        catch (e: SecurityException) { Log.d(TAG, "SecurityException stopScan: ${e.message}") }
        catch (e: Exception)         { Log.d(TAG, "stopScan: ${e.message}") }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GATT-клиент (подключаемся → читаем характеристику → отключаемся)
    // ═══════════════════════════════════════════════════════════════════════

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        synchronized(discoveredAddresses) { if (address in discoveredAddresses) return }
        Log.d(TAG, "BLE устройство найдено: $address rssi=${result.rssi}")
        connectGattForRead(result.device)
    }

    private fun connectGattForRead(device: BluetoothDevice) {
        val address = device.address
        synchronized(activeGatts) { if (address in activeGatts) return }
        if (!hasPermissions()) {
            Log.w(TAG, "connectGattForRead: нет разрешений для $address")
            return
        }

        val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT подключён к $address")
                        if (!hasPermissions()) { safeClose(gatt); return }
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "SecurityException discoverServices: ${e.message}")
                            safeClose(gatt)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT отключился от $address (status=$status)")
                        synchronized(activeGatts) { activeGatts.remove(address) }
                        safeClose(gatt)
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            synchronized(discoveredAddresses) { discoveredAddresses.remove(address) }
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "onServicesDiscovered fail $address status=$status")
                    safeDisconnect(gatt); return
                }
                val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_ANNOUNCE_UUID)
                if (char == null) {
                    Log.d(TAG, "MeshLink сервис не найден на $address")
                    safeDisconnect(gatt); return
                }
                if (!hasPermissions()) { safeDisconnect(gatt); return }
                try {
                    gatt.readCharacteristic(char)
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException readCharacteristic: ${e.message}")
                    safeDisconnect(gatt)
                }
            }

            // Для API < 33
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_ANNOUNCE_UUID) {
                        @Suppress("DEPRECATION")
                        parseAnnouncePayload(characteristic.value, address)
                    }
                    safeDisconnect(gatt)
                }
            }

            // Для API >= 33
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CHAR_ANNOUNCE_UUID) {
                        parseAnnouncePayload(value, address)
                    }
                    safeDisconnect(gatt)
                }
            }
        }

        scope.launch {
            if (!hasPermissions()) return@launch
            try {
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
                synchronized(activeGatts) { activeGatts[address] = gatt }

                delay(GATT_TIMEOUT_MS)
                synchronized(activeGatts) {
                    if (activeGatts.containsKey(address)) {
                        Log.d(TAG, "GATT таймаут $address — принудительное отключение")
                        safeDisconnect(gatt)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException connectGatt $address: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка connectGatt $address: ${e.message}")
            }
        }
    }

    private fun safeDisconnect(gatt: BluetoothGatt) {
        if (!hasPermissions()) return
        try { gatt.disconnect() }
        catch (e: SecurityException) { Log.w(TAG, "SecurityException disconnect: ${e.message}") }
    }

    private fun safeClose(gatt: BluetoothGatt) {
        if (!hasPermissions()) return
        try { gatt.close() }
        catch (e: SecurityException) { Log.w(TAG, "SecurityException close: ${e.message}") }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Payload 38 байт
    // [0..31]  peerId hex UTF-8 padEnd('0')
    // [32..35] shortCode UTF-8 padEnd(' ')
    // [36]     port LSB
    // [37]     port MSB
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildAnnouncePayload(): ByteArray {
        val buf = ByteBuffer.allocate(ANNOUNCE_PAYLOAD_SIZE)
        buf.put(ownPeerId.take(32).padEnd(32, '0').toByteArray(Charsets.UTF_8), 0, 32)
        buf.put(ownShortCode.take(4).padEnd(4, ' ').toByteArray(Charsets.UTF_8), 0, 4)
        buf.put((tcpPort and 0xFF).toByte())
        buf.put(((tcpPort shr 8) and 0xFF).toByte())
        return buf.array()
    }

    private fun parseAnnouncePayload(data: ByteArray?, btAddress: String) {
        if (data == null || data.size < ANNOUNCE_PAYLOAD_SIZE) {
            Log.w(TAG, "BLE анонс слишком короткий от $btAddress: ${data?.size ?: 0} < $ANNOUNCE_PAYLOAD_SIZE")
            return
        }
        try {
            val peerId    = String(data, 0, 32, Charsets.UTF_8).trim()
            val shortCode = String(data, 32, 4, Charsets.UTF_8).trim()
            val port      = (data[36].toInt() and 0xFF) or ((data[37].toInt() and 0xFF) shl 8)

            if (peerId.isBlank()) { Log.w(TAG, "Пустой peerId от $btAddress"); return }
            if (peerId == ownPeerId) { Log.v(TAG, "Анонс от самих себя, пропускаем"); return }
            if (port <= 0 || port > 65535) { Log.w(TAG, "Некорректный порт $port от $btAddress"); return }

            Log.i(TAG, "✅ BLE пир: ${peerId.take(8)}... код=$shortCode bt=$btAddress port=$port")
            synchronized(discoveredAddresses) { discoveredAddresses.add(btAddress) }
            onPeerFound(peerId, shortCode, btAddress, port)

            scope.launch {
                delay(PEER_REDISCOVER_MS)
                synchronized(discoveredAddresses) { discoveredAddresses.remove(btAddress) }
                onPeerLost(btAddress)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка разбора BLE анонса от $btAddress: ${e.message}")
        }
    }
}