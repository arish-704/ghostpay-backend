package com.ghostpay.simulator;

import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.service.BridgeIngestionService;
import com.ghostpay.service.DemoService;
import com.ghostpay.service.IdempotencyService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates the physical mesh network of smartphones:
 * 1. Tracks virtual offline and online devices.
 * 2. Runs gossip rounds where devices broadcast packets to neighbors.
 * 3. Simulates bridge devices walking outside and offloading packets to cloud.
 */
@Service
@RequiredArgsConstructor
public class MeshSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(MeshSimulatorService.class);

    private final BridgeIngestionService ingestionService;
    private final IdempotencyService idempotencyService;
    private final DemoService demoService;

    // deviceId -> VirtualDevice
    private final Map<String, VirtualDevice> devices = new ConcurrentHashMap<>();

    @PostConstruct
    public void initDefaultDevices() {
        devices.clear();
        devices.put("phone-alice", new VirtualDevice("phone-alice", "Alice (Sender)", false));
        devices.put("phone-stranger-1", new VirtualDevice("phone-stranger-1", "Stranger in Basement", false));
        devices.put("phone-stranger-2", new VirtualDevice("phone-stranger-2", "Stranger on Stairs", false));
        devices.put("phone-bob", new VirtualDevice("phone-bob", "Bob (Merchant)", false));
        devices.put("phone-bridge", new VirtualDevice("phone-bridge", "Charlie (Bridge - 4G)", true));
        log.info("Initialized 5 default mesh simulation devices.");
    }

    /**
     * Injects a newly created packet directly into a specific device (e.g. Alice's phone).
     */
    public void injectPacket(String deviceId, MeshPacket packet) {
        VirtualDevice device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Unknown device ID: " + deviceId);
        }
        device.storePacket(packet);
        log.info("Injected packet {} into device {}", packet.packetId(), deviceId);
    }

    /**
     * Simulates 1 round of Bluetooth gossip:
     * Every device broadcasts all its held packets to all other devices within range.
     * Packets that have TTL > 1 are forwarded with decremented TTL.
     */
    public Map<String, Object> runGossipRound() {
        log.info("Running gossip round across {} devices...", devices.size());

        // Snapshot of packets to broadcast: (packetId -> MeshPacket)
        Map<String, MeshPacket> broadcastPool = new HashMap<>();
        for (VirtualDevice device : devices.values()) {
            for (MeshPacket p : device.getHeldPackets()) {
                if (p.ttl() > 0) {
                    broadcastPool.putIfAbsent(p.packetId(), p);
                }
            }
        }

        int packetsRelayed = 0;

        // Propagate to all devices
        for (MeshPacket original : broadcastPool.values()) {
            MeshPacket relayed = original.withDecrementedTtl();
            if (!relayed.isExpired()) {
                for (VirtualDevice target : devices.values()) {
                    target.storePacket(relayed);
                    packetsRelayed++;
                }
            }
        }

        // Decrement TTL on existing packets in device buffers
        devices.values().forEach(VirtualDevice::decrementTtls);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "GOSSIP_ROUND_COMPLETED");
        summary.put("uniquePacketsRelayed", broadcastPool.size());
        summary.put("totalHopsSimulated", packetsRelayed);
        summary.put("networkState", getNetworkState());
        return summary;
    }

    /**
     * Bridge node offload:
     * Every device with hasInternet=true uploads all packets in its buffer to the backend.
     */
    public List<IngestResult> flushBridges() {
        log.info("Flushing bridge nodes with internet coverage...");
        List<IngestResult> results = new ArrayList<>();

        for (VirtualDevice device : devices.values()) {
            if (device.isHasInternet() && device.getPacketCount() > 0) {
                for (MeshPacket packet : device.getHeldPackets()) {
                    int estimatedHops = 5 - packet.ttl(); // Assuming initial TTL was 5
                    IngestResult result = ingestionService.ingest(packet, device.getId(), Math.max(1, estimatedHops));
                    results.add(result);
                }
                device.clearBuffer();
            }
        }

        return results;
    }

    /**
     * Resets the mesh network, clears all device buffers, resets idempotency cache,
     * and restores initial account balances.
     */
    public void resetMesh() {
        devices.values().forEach(VirtualDevice::clearBuffer);
        idempotencyService.reset();
        demoService.resetBalances();
        log.info("Mesh simulator, idempotency cache, and account balances reset.");
    }

    /**
     * Returns a snapshot of all devices and their held packets.
     */
    public List<Map<String, Object>> getNetworkState() {
        List<Map<String, Object>> stateList = new ArrayList<>();

        for (VirtualDevice d : devices.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("label", d.getLabel());
            item.put("hasInternet", d.isHasInternet());
            item.put("packetCount", d.getPacketCount());

            List<Map<String, Object>> packetDetails = new ArrayList<>();
            for (MeshPacket p : d.getHeldPackets()) {
                Map<String, Object> pInfo = new LinkedHashMap<>();
                pInfo.put("packetId", p.packetId());
                pInfo.put("ttl", p.ttl());
                pInfo.put("createdAt", p.createdAt());
                packetDetails.add(pInfo);
            }
            item.put("packets", packetDetails);
            stateList.add(item);
        }

        return stateList;
    }

    public VirtualDevice getDevice(String id) {
        return devices.get(id);
    }

    public Collection<VirtualDevice> getAllDevices() {
        return devices.values();
    }
}
