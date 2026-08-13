package mesh.transport.android

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
import android.os.ParcelUuid
import mesh.transport.BleTransportService
import mesh.transport.MeshBleUuids
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class AndroidBleService(
    private val context: Context,
    localNodeId: UUID
) : BleTransportService(localNodeId) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    @Volatile
    private var localAlias: String = "@${localNodeId.toString().take(8)}"

    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val advertisedPeers = ConcurrentHashMap<UUID, Long>()
    private val pendingWrites = ConcurrentHashMap<String, ArrayBlockingQueue<Boolean>>()
    private val writeLocks = ConcurrentHashMap<String, Any>()

    fun setLocalAlias(alias: String) {
        val normalized = alias.trim().take(12).ifBlank { "@${localNodeId.toString().take(8)}" }
        if (normalized == localAlias) return
        localAlias = normalized
        if (isAdvertising) {
            stopAdvertising()
            startAdvertising()
        }
    }

    override fun initialize(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            serviceCallback?.onError("Bluetooth is disabled or unavailable")
            return false
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            serviceCallback?.onError("BLE is not supported on this device")
            return false
        }
        if (!hasConnectPermission()) {
            serviceCallback?.onError("Bluetooth connect permission is missing")
            return false
        }

        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        setupGattServer(bluetoothManager)
        return true
    }

    override fun startAdvertising(): Boolean {
        if (isAdvertising) return true
        val bleAdvertiser = advertiser ?: return false
        if (!hasAdvertisePermission()) return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, buildRadioHelloPayload())
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
        return true
    }

    override fun stopAdvertising() {
        if (hasAdvertisePermission()) {
            advertiser?.stopAdvertising(advertiseCallback)
        }
        isAdvertising = false
    }

    override fun startScanning(): Boolean {
        if (isScanning) return true
        val bleScanner = scanner ?: return false
        if (!hasScanPermission()) return false

        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bleScanner.startScan(filters, settings, scanCallback)
        isScanning = true
        return true
    }

    override fun stopScanning() {
        if (hasScanPermission()) {
            scanner?.stopScan(scanCallback)
        }
        isScanning = false
    }

    override fun connectToDevice(address: String) {
        if (!hasConnectPermission() || gattClients.containsKey(address)) return
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        val gatt = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        gattClients[address] = gatt
    }

    override fun disconnectDevice(address: String) {
        if (!hasConnectPermission()) return
        writeLocks.remove(address)
        pendingWrites.remove(address)
        gattClients.remove(address)?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
    }

    override fun writeToDevice(address: String, data: ByteArray): Boolean {
        if (!hasConnectPermission()) return false
        val gatt = gattClients[address] ?: return false
        val service = gatt.getService(MeshBleUuids.SERVICE_UUID) ?: return false
        val tx = service.getCharacteristic(MeshBleUuids.CHAR_TX_UUID) ?: return false
        val mtu = connectedPeers[address]?.mtu ?: 23
        val maxPayload = mtu - 3
        if (data.size > maxPayload) {
            serviceCallback?.onError("Packet ${data.size} bytes exceeds negotiated BLE payload $maxPayload")
            return false
        }
        val lock = writeLocks.getOrPut(address) { Any() }
        synchronized(lock) {
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            tx.value = data
            val completion = ArrayBlockingQueue<Boolean>(1)
            pendingWrites[address] = completion
            val started = gatt.writeCharacteristic(tx)
            if (!started) {
                pendingWrites.remove(address, completion)
                return false
            }
            val completed = completion.poll(GATT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
            pendingWrites.remove(address, completion)
            return completed
        }
    }

    override fun notifyAllDevices(data: ByteArray) {
        if (!hasConnectPermission()) return
        val server = gattServer ?: return
        val rx = server.getService(MeshBleUuids.SERVICE_UUID)
            ?.getCharacteristic(MeshBleUuids.CHAR_RX_UUID) ?: return

        rx.value = data
        connectedPeers.keys.forEach { address ->
            bluetoothAdapter?.getRemoteDevice(address)?.let { device ->
                server.notifyCharacteristicChanged(device, rx, false)
            }
        }
    }

    override fun requestMtu(address: String, mtu: Int) {
        if (hasConnectPermission()) {
            gattClients[address]?.requestMtu(mtu)
        }
    }

    private fun setupGattServer(bluetoothManager: BluetoothManager) {
        if (!hasConnectPermission()) return
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            MeshBleUuids.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val tx = BluetoothGattCharacteristic(
            MeshBleUuids.CHAR_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val rx = BluetoothGattCharacteristic(
            MeshBleUuids.CHAR_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        rx.addDescriptor(
            BluetoothGattDescriptor(
                MeshBleUuids.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val nodeId = BluetoothGattCharacteristic(
            MeshBleUuids.CHAR_NODE_ID_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        nodeId.value = localNodeId.toString().toByteArray(Charsets.UTF_8)

        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        service.addCharacteristic(nodeId)
        gattServer?.addService(service)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            serviceCallback?.onError("BLE advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val radioHello = parseRadioHello(result)
            if (radioHello != null) {
                val now = System.currentTimeMillis()
                val lastSeen = advertisedPeers[radioHello.nodeId] ?: 0L
                peerNodeIds[address] = radioHello.nodeId
                nodeAddresses[radioHello.nodeId] = address
                if (now - lastSeen > ADVERTISED_PEER_NOTIFY_INTERVAL_MS) {
                    advertisedPeers[radioHello.nodeId] = now
                    serviceCallback?.onAdvertisementPeerDiscovered(
                        address,
                        radioHello.nodeId,
                        radioHello.alias,
                        result.rssi
                    )
                }
                if (!connectedPeers.containsKey(address)) {
                    onDeviceDiscovered(address, radioHello.alias, result.rssi)
                }
            } else if (hasMeshServiceUuid(result) && !connectedPeers.containsKey(address)) {
                onDeviceDiscovered(address, result.device.name, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            serviceCallback?.onError("BLE scan failed: $errorCode")
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnectionStateChanged(address, true)
                gatt.requestMtu(517)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattClients.remove(address)
                writeLocks.remove(address)
                onConnectionStateChanged(address, false)
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(MeshBleUuids.SERVICE_UUID) ?: return

            service.getCharacteristic(MeshBleUuids.CHAR_RX_UUID)?.let { rx ->
                gatt.setCharacteristicNotification(rx, true)
                rx.getDescriptor(MeshBleUuids.CCCD_UUID)?.let { descriptor ->
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }

            service.getCharacteristic(MeshBleUuids.CHAR_NODE_ID_UUID)?.let {
                gatt.readCharacteristic(it)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(gatt, characteristic, characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(gatt, characteristic, value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == MeshBleUuids.CHAR_TX_UUID) {
                pendingWrites.remove(gatt.device.address)?.offer(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == MeshBleUuids.CHAR_RX_UUID) {
                onDataReceived(gatt.device.address, characteristic.value, 0)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == MeshBleUuids.CHAR_RX_UUID) {
                onDataReceived(gatt.device.address, value, 0)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onMtuChanged(gatt.device.address, mtu)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnectionStateChanged(device.address, true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionStateChanged(device.address, false)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = if (characteristic.uuid == MeshBleUuids.CHAR_NODE_ID_UUID) {
                localNodeId.toString().toByteArray(Charsets.UTF_8)
            } else {
                characteristic.value ?: ByteArray(0)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MeshBleUuids.CHAR_TX_UUID) {
                onDataReceived(device.address, value, 0)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != MeshBleUuids.CHAR_NODE_ID_UUID) return
        val remoteNodeId = runCatching { UUID.fromString(value.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return
        peerNodeIds[gatt.device.address] = remoteNodeId
        nodeAddresses[remoteNodeId] = gatt.device.address
        serviceCallback?.onPeerIdentified(gatt.device.address, remoteNodeId)
    }

    private fun buildRadioHelloPayload(): ByteArray {
        val aliasBytes = localAlias.toByteArray(Charsets.UTF_8).take(MAX_ADVERTISED_ALIAS_BYTES).toByteArray()
        return ByteBuffer.allocate(1 + 16 + 1 + aliasBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(RADIO_HELLO_VERSION)
            .putLong(localNodeId.mostSignificantBits)
            .putLong(localNodeId.leastSignificantBits)
            .put(aliasBytes.size.toByte())
            .put(aliasBytes)
            .array()
    }

    private fun parseRadioHello(result: ScanResult): RadioHello? {
        val payload = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return null
        if (payload.size < 18 || payload[0] != RADIO_HELLO_VERSION) return null
        return runCatching {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            buffer.get()
            val nodeId = UUID(buffer.long, buffer.long)
            if (nodeId == localNodeId) return null
            val aliasLength = buffer.get().toInt() and 0xFF
            val safeLength = aliasLength.coerceAtMost(buffer.remaining())
            val aliasBytes = ByteArray(safeLength)
            buffer.get(aliasBytes)
            val alias = aliasBytes.toString(Charsets.UTF_8)
                .trim()
                .takeIf { it.startsWith("@") && it.length > 1 }
            RadioHello(nodeId, alias)
        }.getOrNull()
    }

    private fun hasMeshServiceUuid(result: ScanResult): Boolean =
        result.scanRecord
            ?.serviceUuids
            ?.contains(ParcelUuid(MeshBleUuids.SERVICE_UUID)) == true

    private fun hasScanPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private data class RadioHello(
        val nodeId: UUID,
        val alias: String?
    )

    companion object {
        private const val MANUFACTURER_ID = 0x0B17
        private const val RADIO_HELLO_VERSION: Byte = 1
        private const val MAX_ADVERTISED_ALIAS_BYTES = 12
        private const val ADVERTISED_PEER_NOTIFY_INTERVAL_MS = 3_000L
        private const val GATT_WRITE_TIMEOUT_MS = 1_500L
    }
}
