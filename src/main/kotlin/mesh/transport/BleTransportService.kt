package mesh.transport

import mesh.protocol.MeshPacket
import mesh.protocol.PacketFlags
import mesh.protocol.PacketType
import mesh.routing.MeshRouter
import mesh.routing.PacketTransmitter
import mesh.routing.TransportType
import java.util.*
import java.util.concurrent.*

/**
 * BLE Transport Service - MVP Implementation for Android
 *
 * This service handles:
 * 1. BLE Advertising (Peripheral role) - announce our presence
 * 2. BLE Scanning (Central role) - discover nearby nodes
 * 3. GATT Server - receive incoming connections
 * 4. GATT Client - connect to discovered peers
 *
 * BLE Architecture:
 * =================
 * - Service UUID: Custom UUID for mesh network identification
 * - Characteristic UUIDs:
 *   - TX: Write characteristic (peers write to us)
 *   - RX: Notify characteristic (we notify peers)
 *   - NODE_ID: Read characteristic (our node identity)
 *
 * MTU Negotiation:
 * - Default BLE MTU: 23 bytes (20 payload after ATT headers)
 * - Request MTU: 517 bytes for larger packets
 * - Chunk large packets if MTU negotiation fails
 */

// UUIDs for the mesh service
object MeshBleUuids {
    // Main mesh service
    val SERVICE_UUID: UUID = UUID.fromString("0000FEA0-0000-1000-8000-00805F9B34FB")

    // Characteristic for receiving data (write)
    val CHAR_TX_UUID: UUID = UUID.fromString("0000FEA1-0000-1000-8000-00805F9B34FB")

    // Characteristic for sending data (notify)
    val CHAR_RX_UUID: UUID = UUID.fromString("0000FEA2-0000-1000-8000-00805F9B34FB")

    // Characteristic for node identity (read)
    val CHAR_NODE_ID_UUID: UUID = UUID.fromString("0000FEA3-0000-1000-8000-00805F9B34FB")

    // Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

/**
 * Main BLE Transport Service
 *
 * Note: This is a platform-agnostic design. The actual Android/iOS implementations
 * would extend this and use platform-specific BLE APIs:
 * - Android: BluetoothGattServer, BluetoothLeAdvertiser, BluetoothLeScanner
 * - iOS: CBPeripheralManager, CBCentralManager
 */
abstract class BleTransportService(
    protected val localNodeId: UUID
) : PacketTransmitter {

    // Connected peers: device address -> connection info
    protected val connectedPeers = ConcurrentHashMap<String, BleConnection>()

    // Peer node IDs: device address -> node UUID (after handshake)
    protected val peerNodeIds = ConcurrentHashMap<String, UUID>()

    // Reverse lookup: node UUID -> device address
    protected val nodeAddresses = ConcurrentHashMap<UUID, String>()

    // Message queue for pending transmissions
    protected val transmitQueue = LinkedBlockingQueue<TransmitTask>()

    // Router reference (set after initialization)
    protected var meshRouter: MeshRouter? = null

    // Service state
    protected var isAdvertising = false
    protected var isScanning = false

    // Executor for background operations
    protected val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    // Callback interface for service events
    protected var serviceCallback: BleServiceCallback? = null

    /**
     * Initialize the BLE transport service
     */
    abstract fun initialize(): Boolean

    /**
     * Start BLE advertising (Peripheral mode)
     *
     * Advertising data includes:
     * - Service UUID (for discovery)
     * - Local name (truncated node ID for identification)
     * - TX Power level (for distance estimation)
     */
    abstract fun startAdvertising(): Boolean

    /**
     * Stop BLE advertising
     */
    abstract fun stopAdvertising()

    /**
     * Start BLE scanning (Central mode)
     *
     * Scan filters:
     * - Filter by service UUID to find only mesh nodes
     * - Low latency mode for faster discovery
     */
    abstract fun startScanning(): Boolean

    /**
     * Stop BLE scanning
     */
    abstract fun stopScanning()

    /**
     * Connect to a discovered device
     */
    abstract fun connectToDevice(address: String)

    /**
     * Disconnect from a device
     */
    abstract fun disconnectDevice(address: String)

    /**
     * Write data to a connected device
     */
    protected abstract fun writeToDevice(address: String, data: ByteArray): Boolean

    /**
     * Notify all connected devices (via RX characteristic)
     */
    protected abstract fun notifyAllDevices(data: ByteArray)

    // ==================== PacketTransmitter Interface ====================

    override fun broadcast(packet: MeshPacket) {
        val data = packet.toBytes()

        // Queue for all connected peers
        connectedPeers.keys.forEach { address ->
            transmitQueue.offer(TransmitTask(
                address = address,
                data = data,
                isBroadcast = true
            ))
        }

        // Also advertise/notify
        processTransmitQueue()
    }

    override fun sendTo(nodeId: UUID, packet: MeshPacket) {
        val address = nodeAddresses[nodeId] ?: run {
            // Node not directly connected, broadcast instead
            broadcast(packet)
            return
        }

        transmitQueue.offer(TransmitTask(
            address = address,
            data = packet.toBytes(),
            isBroadcast = false
        ))

        processTransmitQueue()
    }

    protected fun processTransmitQueue() {
        executor.submit {
            while (transmitQueue.isNotEmpty()) {
                val task = transmitQueue.poll() ?: break

                if (task.isBroadcast) {
                    notifyAllDevices(task.data)
                } else if (!writeToDevice(task.address, task.data)) {
                    notifyAllDevices(task.data)
                }

                // Small delay between transmissions to avoid congestion
                Thread.sleep(10)
            }
        }
    }

    // ==================== Data Reception ====================

    /**
     * Called when data is received from a peer
     */
    protected fun onDataReceived(address: String, data: ByteArray, rssi: Int) {
        try {
            val packet = MeshPacket.fromBytes(data)

            // Track peer node ID
            if (!peerNodeIds.containsKey(address)) {
                peerNodeIds[address] = packet.senderId
                nodeAddresses[packet.senderId] = address
                serviceCallback?.onPeerIdentified(address, packet.senderId)
            }

            // Forward to router
            meshRouter?.onPacketReceived(packet, rssi, TransportType.BLE)

        } catch (e: Exception) {
            serviceCallback?.onError("Failed to parse packet: ${e.message}")
        }
    }

    /**
     * Called when a device is discovered during scanning
     */
    protected fun onDeviceDiscovered(address: String, name: String?, rssi: Int) {
        if (!connectedPeers.containsKey(address)) {
            serviceCallback?.onDeviceDiscovered(address, name, rssi)

            // Auto-connect if this looks like a mesh node
            connectToDevice(address)
        }
    }

    /**
     * Called when connection state changes
     */
    protected fun onConnectionStateChanged(address: String, connected: Boolean) {
        if (connected) {
            connectedPeers[address] = BleConnection(
                address = address,
                connectedAt = System.currentTimeMillis(),
                mtu = 23 // Default, will be updated after negotiation
            )
            serviceCallback?.onPeerConnected(address)

            // Request higher MTU for larger packets
            requestMtu(address, 517)

        } else {
            connectedPeers.remove(address)
            peerNodeIds.remove(address)?.let { nodeId ->
                nodeAddresses.remove(nodeId)
            }
            serviceCallback?.onPeerDisconnected(address)
        }
    }

    /**
     * Request MTU size from peer
     */
    protected abstract fun requestMtu(address: String, mtu: Int)

    /**
     * Called when MTU is negotiated
     */
    protected fun onMtuChanged(address: String, mtu: Int) {
        connectedPeers[address]?.let {
            connectedPeers[address] = it.copy(mtu = mtu)
        }
    }

    // ==================== Lifecycle ====================

    fun setRouter(router: MeshRouter) {
        this.meshRouter = router
    }

    fun setCallback(callback: BleServiceCallback) {
        this.serviceCallback = callback
    }

    fun start() {
        startAdvertising()
        startScanning()

        // Schedule periodic tasks
        executor.scheduleAtFixedRate(
            { performMaintenance() },
            30, 30, TimeUnit.SECONDS
        )
    }

    fun stop() {
        stopAdvertising()
        stopScanning()

        // Disconnect all peers
        connectedPeers.keys.forEach { disconnectDevice(it) }
        connectedPeers.clear()

        executor.shutdown()
    }

    protected fun performMaintenance() {
        // Re-start advertising if stopped
        if (!isAdvertising) {
            startAdvertising()
        }

        // Re-start scanning if stopped
        if (!isScanning) {
            startScanning()
        }

        // Ping connected peers
        connectedPeers.keys.forEach { address ->
            sendHeartbeat(address)
        }
    }

    private fun sendHeartbeat(address: String) {
        val packet = MeshPacket(
            type = PacketType.HEARTBEAT,
            flags = PacketFlags(),
            messageId = UUID.randomUUID(),
            senderId = localNodeId,
            recipientId = MeshPacket.BROADCAST_ID,
            timestamp = System.currentTimeMillis(),
            payload = ByteArray(0),
            signature = ByteArray(0)
        )
        writeToDevice(address, packet.toBytes())
    }
}

// ==================== Data Classes ====================

data class BleConnection(
    val address: String,
    val connectedAt: Long,
    val mtu: Int
)

data class TransmitTask(
    val address: String,
    val data: ByteArray,
    val isBroadcast: Boolean
)

interface BleServiceCallback {
    fun onDeviceDiscovered(address: String, name: String?, rssi: Int)
    fun onPeerConnected(address: String)
    fun onPeerDisconnected(address: String)
    fun onPeerIdentified(address: String, nodeId: UUID)
    fun onError(message: String)
}
