package com.example.bluetoothmanager

import mesh.protocol.MeshPacket
import mesh.routing.OpportunisticPacketTransmitter
import java.util.UUID

class CompositeOpportunisticTransport(
    private val transports: List<OpportunisticPacketTransmitter>
) : OpportunisticPacketTransmitter {
    override fun tryBroadcast(packet: MeshPacket): Boolean {
        var sent = false
        transports.forEach { transport ->
            sent = runCatching { transport.tryBroadcast(packet) }.getOrDefault(false) || sent
        }
        return sent
    }

    override fun trySendTo(nodeId: UUID, packet: MeshPacket): Boolean {
        var sent = false
        transports.forEach { transport ->
            sent = runCatching { transport.trySendTo(nodeId, packet) }.getOrDefault(false) || sent
        }
        return sent
    }

    override fun broadcast(packet: MeshPacket) {
        tryBroadcast(packet)
    }

    override fun sendTo(nodeId: UUID, packet: MeshPacket) {
        trySendTo(nodeId, packet)
    }
}
