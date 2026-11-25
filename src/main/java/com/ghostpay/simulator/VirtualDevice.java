package com.ghostpay.simulator;

import com.ghostpay.domain.MeshPacket;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a simulated smartphone node participating in the offline mesh.
 */
@Getter
@Setter
public class VirtualDevice {

    private final String id;
    private final String label;
    private boolean hasInternet;

    // Buffer of MeshPackets held in device RAM: packetId -> MeshPacket
    private final ConcurrentHashMap<String, MeshPacket> packetBuffer = new ConcurrentHashMap<>();

    public VirtualDevice(String id, String label, boolean hasInternet) {
        this.id = id;
        this.label = label;
        this.hasInternet = hasInternet;
    }

    public void storePacket(MeshPacket packet) {
        if (packet != null && !packet.isExpired()) {
            packetBuffer.put(packet.packetId(), packet);
        }
    }

    public boolean holdsPacket(String packetId) {
        return packetBuffer.containsKey(packetId);
    }

    public Collection<MeshPacket> getHeldPackets() {
        return packetBuffer.values();
    }

    public int getPacketCount() {
        return packetBuffer.size();
    }

    /**
     * Decrements TTL for all held packets. Discards any expired packets (TTL <= 0).
     */
    public void decrementTtls() {
        List<String> toRemove = new ArrayList<>();

        for (MeshPacket packet : packetBuffer.values()) {
            MeshPacket decremented = packet.withDecrementedTtl();
            if (decremented.isExpired()) {
                toRemove.add(packet.packetId());
            } else {
                packetBuffer.put(decremented.packetId(), decremented);
            }
        }

        toRemove.forEach(packetBuffer::remove);
    }

    public void clearBuffer() {
        packetBuffer.clear();
    }
}
